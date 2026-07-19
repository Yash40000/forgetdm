package io.forgetdm.mainframe;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.copybook.Copybook;
import io.forgetdm.core.copybook.Field;
import io.forgetdm.core.copybook.RecordCodec;
import io.forgetdm.core.copybook.RecordValue;
import io.forgetdm.core.copybook.codec.Ebcdic;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.mainframe.transport.TransportFactory;
import io.forgetdm.security.AccessContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Runs a mainframe file-masking job end to end, asynchronously:
 *   fetch each file from the source LPAR → split into records (FB/VB) → decode with the file's
 *   copybook → apply the copybook's field masks (deterministic engine) → re-encode in place →
 *   rejoin → write to the target LPAR (same or different) under the target name.
 */
@Service
public class MainframeMaskingService {

    private final MainframeJobRepository jobs;
    private final MainframeJobFileRepository jobFiles;
    private final MainframeConnectionRepository connections;
    private final CopybookDefRepository copybooks;
    private final CopybookMaskRepository masks;
    private final TransportFactory transports;
    private final MaskingEngine engine;
    private final ExecutorService executor;
    private final AuditService audit;

    public MainframeMaskingService(MainframeJobRepository jobs, MainframeJobFileRepository jobFiles,
                                   MainframeConnectionRepository connections, CopybookDefRepository copybooks,
                                   CopybookMaskRepository masks, TransportFactory transports,
                                   MaskingEngine engine, ExecutorService provisioningExecutor, AuditService audit) {
        this.jobs = jobs; this.jobFiles = jobFiles; this.connections = connections;
        this.copybooks = copybooks; this.masks = masks; this.transports = transports;
        this.engine = engine; this.executor = provisioningExecutor; this.audit = audit;
    }

    public void submitAsync(Long jobId) {
        jobs.findById(jobId).ifPresent(job -> audit.record(job.getCreatedBy(), "MAINFRAME_JOB_QUEUED", "MASKING",
                "MAINFRAME_JOB", String.valueOf(jobId), job.getName(), "SUCCESS",
                "Mainframe masking job queued", "{\"files\":" + job.getFilesTotal() + "}"));
        executor.submit(() -> run(jobId));
    }

    public MainframeJobEntity cancel(Long jobId) {
        MainframeJobEntity job = job(jobId);
        if (terminal(job.getStatus())) return job;
        job.setCancelRequested(true);
        job.setMessage("Cancellation requested; the active record batch will stop at a safe boundary");
        if ("PENDING".equals(job.getStatus())) {
            job.setStatus("CANCELED");
            job.setFinishedAt(Instant.now());
        }
        MainframeJobEntity saved = jobs.save(job);
        audit.record(actor(), "MAINFRAME_JOB_CANCEL_REQUESTED", "CANCEL", "MAINFRAME_JOB", String.valueOf(jobId),
                job.getName(), "SUCCESS", "Mainframe masking cancellation requested", null);
        if ("CANCELED".equals(saved.getStatus())) auditTerminal(saved);
        return saved;
    }

    /** A retry is a new immutable attempt; files already delivered successfully are retained as completed evidence. */
    public MainframeJobEntity retry(Long jobId) {
        MainframeJobEntity previous = job(jobId);
        if (!java.util.Set.of("FAILED", "COMPLETED_WITH_ERRORS", "CANCELED").contains(previous.getStatus())) {
            throw ApiException.bad("Only failed, partially completed, or canceled mainframe jobs can be retried");
        }
        MainframeJobEntity next = new MainframeJobEntity();
        next.setName(previous.getName());
        next.setSourceConnectionId(previous.getSourceConnectionId());
        next.setTargetConnectionId(previous.getTargetConnectionId());
        next.setMaskingSeed(previous.getMaskingSeed());
        next.setCreatedBy(actor());
        next.setFilesTotal(previous.getFilesTotal());
        next.setStatus("PENDING");
        next.setMessage("Retry queued from job " + previous.getId());
        next = jobs.save(next);

        int completed = 0;
        long completedRecords = 0;
        for (MainframeJobFileEntity priorFile : jobFiles.findByJobIdOrderByOrdinalAsc(previous.getId())) {
            MainframeJobFileEntity file = copyFile(priorFile, next.getId());
            if ("COMPLETED".equals(priorFile.getStatus())) {
                file.setStatus("COMPLETED");
                file.setRecordCount(priorFile.getRecordCount());
                file.setMessage("Retained from successful attempt " + previous.getId());
                completed++;
                completedRecords += priorFile.getRecordCount();
            } else {
                file.setStatus("PENDING");
                file.setRecordCount(0);
                file.setMessage(null);
            }
            jobFiles.save(file);
        }
        next.setFilesDone(completed);
        next.setRecordsProcessed(completedRecords);
        next = jobs.save(next);
        audit.record(actor(), "MAINFRAME_JOB_RETRIED", "MASKING", "MAINFRAME_JOB", String.valueOf(next.getId()),
                next.getName(), "SUCCESS", "Created retry attempt",
                "{\"previousJobId\":" + previous.getId() + ",\"retainedFiles\":" + completed + "}");
        submitAsync(next.getId());
        return next;
    }

    void run(Long jobId) {
        MainframeJobEntity job = jobs.findById(jobId).orElse(null);
        if (job == null) return;
        if (job.isCancelRequested() || "CANCELED".equals(job.getStatus())) {
            if (!"CANCELED".equals(job.getStatus())) {
                job.setStatus("CANCELED");
                job.setFinishedAt(Instant.now());
                jobs.save(job);
                auditTerminal(job);
            }
            return;
        }
        job.setStatus("RUNNING");
        job.setStartedAt(Instant.now());
        jobs.save(job);
        audit.record(job.getCreatedBy(), "MAINFRAME_JOB_STARTED", "MASKING", "MAINFRAME_JOB", String.valueOf(jobId),
                job.getName(), "SUCCESS", "Mainframe masking worker started", null);

        List<MainframeJobFileEntity> files = jobFiles.findByJobIdOrderByOrdinalAsc(jobId);
        long totalRecords = files.stream().filter(f -> "COMPLETED".equals(f.getStatus()))
                .mapToLong(MainframeJobFileEntity::getRecordCount).sum();
        int ok = (int) files.stream().filter(f -> "COMPLETED".equals(f.getStatus())).count();
        int failed = 0;
        for (MainframeJobFileEntity file : files) {
            if ("COMPLETED".equals(file.getStatus())) continue;
            if (cancelRequested(jobId)) { cancelRunningJob(jobId, totalRecords, ok + failed); return; }
            try {
                file.setStatus("RUNNING");
                file.setMessage("Masking in progress");
                jobFiles.save(file);
                long n = processFile(job, file);
                file.setStatus("COMPLETED");
                file.setRecordCount(n);
                file.setMessage("Masked " + n + " records");
                totalRecords += n; ok++;
            } catch (JobCanceled e) {
                file.setStatus("CANCELED");
                file.setMessage("Canceled before target delivery");
                jobFiles.save(file);
                cancelRunningJob(jobId, totalRecords, ok + failed);
                return;
            } catch (Exception e) {
                file.setStatus("FAILED");
                file.setMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                failed++;
            }
            jobFiles.save(file);
            job.setFilesDone(ok + failed);
            job.setRecordsProcessed(totalRecords);
            jobs.save(job);
        }

        job.setStatus(failed == 0 ? "COMPLETED" : (ok == 0 ? "FAILED" : "COMPLETED_WITH_ERRORS"));
        job.setMessage(ok + " file(s) masked, " + failed + " failed, " + totalRecords + " records total");
        job.setFinishedAt(Instant.now());
        jobs.save(job);
        auditTerminal(job);
    }

    private long processFile(MainframeJobEntity job, MainframeJobFileEntity file) {
        MainframeConnectionEntity src = conn(job.getSourceConnectionId(), "source");
        byte[] bytes = transports.forConnection(src).fetch(src, file.getSourceName());

        CopybookDefEntity def = copybooks.findById(file.getCopybookId() == null ? -1L : file.getCopybookId())
                .orElseThrow(() -> ApiException.bad("File '" + file.getSourceName() + "' has no copybook assigned"));
        Copybook cb = CopybookSupport.parse(def.getSource());
        Field record = cb.primaryRecord();

        String codePage = firstNonBlank(file.getCodePage(), def.getCodePage(), src.getCodePage(), "Cp037");
        String recfm = file.getRecfm() == null ? "FB" : file.getRecfm();
        int lrecl = file.getLrecl() != null ? file.getLrecl() : record.length();

        Map<String, CopybookMaskEntity> maskMap = new HashMap<>();
        for (CopybookMaskEntity m : masks.findByCopybookId(def.getId()))
            maskMap.put(CopybookSupport.stripSubscripts(m.getFieldPath()).toUpperCase(Locale.ROOT), m);

        RecordCodec codec = new RecordCodec(record, new Ebcdic(codePage));
        MaskingEngine eng = (job.getMaskingSeed() == null || job.getMaskingSeed().isBlank())
                ? engine : engine.withSeed(job.getMaskingSeed());

        List<byte[]> recs = RecordSplitter.split(bytes, recfm, lrecl);
        List<byte[]> out = new ArrayList<>(recs.size());
        long rowIndex = 0;
        for (byte[] rec : recs) {
            rowIndex++;
            if (rowIndex == 1 || rowIndex % 250 == 0) checkCanceled(job.getId());
            RecordValue rv = codec.decode(rec);
            MaskContext ctx = new MaskContext(rowIndex);
            for (RecordValue.DecodedField df : rv.fields())
                ctx.row.put(df.field().name().toLowerCase(Locale.ROOT), df.value());

            Map<String, String> changes = new LinkedHashMap<>();
            for (RecordValue.DecodedField df : rv.fields()) {
                CopybookMaskEntity m = maskMap.get(CopybookSupport.stripSubscripts(df.path()).toUpperCase(Locale.ROOT));
                if (m == null) continue;
                String masked = eng.mask(MaskFunction.valueOf(m.getFunction().trim().toUpperCase(Locale.ROOT)),
                        df.field().name().toLowerCase(Locale.ROOT), df.value(),
                        blankToNull(m.getParam1()), blankToNull(m.getParam2()), ctx);
                if (masked == null) masked = df.numeric() ? "0" : "";
                ctx.masked.put(df.field().name().toLowerCase(Locale.ROOT), masked);
                changes.put(df.path(), masked);
            }
            out.add(codec.encodeOverlay(rv, rec, changes));
        }

        checkCanceled(job.getId());

        byte[] outBytes = RecordSplitter.join(out, recfm);
        MainframeConnectionEntity tgt = conn(
                file.getTargetConnectionId() != null ? file.getTargetConnectionId() : job.getTargetConnectionId(),
                "target");
        String targetName = (file.getTargetName() != null && !file.getTargetName().isBlank())
                ? file.getTargetName() : file.getSourceName();
        transports.forConnection(tgt).put(tgt, targetName, outBytes, recfm, lrecl);

        audit.record(job.getCreatedBy(), "MAINFRAME_FILE_MASKED", "MASKING", "MAINFRAME_JOB_FILE",
                String.valueOf(file.getId()), file.getSourceName(), "SUCCESS",
                "records=" + recs.size() + " target=" + tgt.getName() + ":" + targetName, null);
        return recs.size();
    }

    private void cancelRunningJob(Long jobId, long records, int filesDone) {
        MainframeJobEntity job = job(jobId);
        job.setCancelRequested(true);
        job.setStatus("CANCELED");
        job.setRecordsProcessed(records);
        job.setFilesDone(filesDone);
        job.setMessage("Canceled safely before the next target delivery");
        job.setFinishedAt(Instant.now());
        jobs.save(job);
        auditTerminal(job);
    }

    private boolean cancelRequested(Long jobId) {
        return jobs.findById(jobId).map(MainframeJobEntity::isCancelRequested).orElse(true)
                || Thread.currentThread().isInterrupted();
    }

    private void checkCanceled(Long jobId) {
        if (cancelRequested(jobId)) throw new JobCanceled();
    }

    private void auditTerminal(MainframeJobEntity job) {
        String status = job.getStatus() == null ? "FAILED" : job.getStatus().toUpperCase(Locale.ROOT);
        audit.record(job.getCreatedBy(), "MAINFRAME_JOB_" + status, "MASKING", "MAINFRAME_JOB",
                String.valueOf(job.getId()), job.getName(), "FAILED".equals(status) ? "FAILURE" : "SUCCESS",
                "files=" + job.getFilesDone() + "/" + job.getFilesTotal() + " records=" + job.getRecordsProcessed(),
                "{\"status\":\"" + status + "\"}");
    }

    private MainframeJobEntity job(Long id) {
        return jobs.findById(id).orElseThrow(() -> ApiException.notFound("Mainframe job " + id + " not found"));
    }

    private static MainframeJobFileEntity copyFile(MainframeJobFileEntity source, Long jobId) {
        MainframeJobFileEntity copy = new MainframeJobFileEntity();
        copy.setJobId(jobId);
        copy.setSourceName(source.getSourceName());
        copy.setCopybookId(source.getCopybookId());
        copy.setRecfm(source.getRecfm());
        copy.setLrecl(source.getLrecl());
        copy.setCodePage(source.getCodePage());
        copy.setTargetConnectionId(source.getTargetConnectionId());
        copy.setTargetName(source.getTargetName());
        copy.setOrdinal(source.getOrdinal());
        return copy;
    }

    private static boolean terminal(String status) {
        return java.util.Set.of("COMPLETED", "FAILED", "COMPLETED_WITH_ERRORS", "CANCELED").contains(status);
    }

    private static String actor() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }

    private static final class JobCanceled extends RuntimeException { }

    private MainframeConnectionEntity conn(Long id, String role) {
        if (id == null) throw ApiException.bad("No " + role + " connection set for the job");
        return connections.findById(id).orElseThrow(() -> ApiException.bad(role + " connection " + id + " not found"));
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "Cp037";
    }
}
