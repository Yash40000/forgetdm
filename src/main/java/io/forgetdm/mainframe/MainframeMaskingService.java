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
        executor.submit(() -> run(jobId));
    }

    void run(Long jobId) {
        MainframeJobEntity job = jobs.findById(jobId).orElse(null);
        if (job == null) return;
        job.setStatus("RUNNING");
        job.setStartedAt(Instant.now());
        jobs.save(job);
        audit.log("system", "MF_JOB_STARTED", "id=" + jobId + " '" + job.getName() + "'");

        List<MainframeJobFileEntity> files = jobFiles.findByJobIdOrderByOrdinalAsc(jobId);
        long totalRecords = 0;
        int ok = 0, failed = 0;
        for (MainframeJobFileEntity file : files) {
            try {
                long n = processFile(job, file);
                file.setStatus("COMPLETED");
                file.setRecordCount(n);
                file.setMessage("Masked " + n + " records");
                totalRecords += n; ok++;
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
        audit.log("system", "MF_JOB_" + job.getStatus(), "id=" + jobId + " records=" + totalRecords);
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

        byte[] outBytes = RecordSplitter.join(out, recfm);
        MainframeConnectionEntity tgt = conn(
                file.getTargetConnectionId() != null ? file.getTargetConnectionId() : job.getTargetConnectionId(),
                "target");
        String targetName = (file.getTargetName() != null && !file.getTargetName().isBlank())
                ? file.getTargetName() : file.getSourceName();
        transports.forConnection(tgt).put(tgt, targetName, outBytes, recfm, lrecl);

        audit.log("system", "MF_FILE_MASKED", file.getSourceName() + " -> " + tgt.getName() + ":" + targetName
                + " records=" + recs.size());
        return recs.size();
    }

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
