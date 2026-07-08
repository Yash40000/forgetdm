package io.forgetdm.mainframe;

import io.forgetdm.common.ApiException;
import io.forgetdm.core.copybook.Copybook;
import io.forgetdm.core.copybook.Field;
import io.forgetdm.mainframe.transport.TransportFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** REST surface for the mainframe pipeline: connections (LPARs), copybook registry, and file jobs. */
@RestController
@RequestMapping("/api/mainframe")
public class MainframeController {

    private final MainframeConnectionRepository connections;
    private final CopybookDefRepository copybooks;
    private final CopybookMaskRepository masks;
    private final MainframeJobRepository jobs;
    private final MainframeJobFileRepository jobFiles;
    private final TransportFactory transports;
    private final MainframeMaskingService masking;
    private final MainframeGenService fileGen;

    public MainframeController(MainframeConnectionRepository connections, CopybookDefRepository copybooks,
                              CopybookMaskRepository masks, MainframeJobRepository jobs,
                              MainframeJobFileRepository jobFiles, TransportFactory transports,
                              MainframeMaskingService masking, MainframeGenService fileGen) {
        this.connections = connections; this.copybooks = copybooks; this.masks = masks;
        this.jobs = jobs; this.jobFiles = jobFiles; this.transports = transports; this.masking = masking;
        this.fileGen = fileGen;
    }

    /** Generate synthetic records to a copybook layout, encode to EBCDIC, deliver or download. */
    @PostMapping("/generate-file")
    public Map<String, Object> generateFile(@RequestBody MainframeGenService.GenFileReq req) {
        return fileGen.generateFile(req);
    }

    // ============================================================ connections

    @GetMapping("/connections")
    public List<MainframeConnectionEntity> listConnections() {
        List<MainframeConnectionEntity> all = connections.findAll();
        all.sort(Comparator.comparing(MainframeConnectionEntity::getName, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    @PostMapping("/connections")
    public MainframeConnectionEntity createConnection(@RequestBody MainframeConnectionEntity c) {
        String type = c.getType() == null ? "" : c.getType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("LOCAL", "ZOWE").contains(type)) throw ApiException.bad("type must be LOCAL or ZOWE");
        c.setType(type);
        if (c.getName() == null || c.getName().isBlank()) throw ApiException.bad("name required");
        if (connections.findByName(c.getName()).isPresent())
            throw ApiException.bad("A connection named '" + c.getName() + "' already exists");
        if (type.equals("LOCAL") && (c.getBaseDir() == null || c.getBaseDir().isBlank()))
            throw ApiException.bad("LOCAL connection needs a base directory");
        if (type.equals("ZOWE") && (c.getHost() == null || c.getHost().isBlank()))
            throw ApiException.bad("ZOWE connection needs a host");
        if (c.getCodePage() == null || c.getCodePage().isBlank()) c.setCodePage("Cp037");
        c.setId(null);
        return connections.save(c);
    }

    @DeleteMapping("/connections/{id}")
    public Map<String, Object> deleteConnection(@PathVariable Long id) {
        connections.deleteById(id);
        return Map.of("deleted", id);
    }

    @PostMapping("/connections/{id}/test")
    public Map<String, Object> testConnection(@PathVariable Long id) {
        MainframeConnectionEntity c = conn(id);
        try {
            var files = transports.forConnection(c).list(c, c.getType().equalsIgnoreCase("ZOWE") ? "**" : "*");
            return Map.of("ok", true, "count", files.size());
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    @GetMapping("/connections/{id}/files")
    public List<?> listFiles(@PathVariable Long id, @RequestParam(required = false) String pattern) {
        MainframeConnectionEntity c = conn(id);
        return transports.forConnection(c).list(c, pattern);
    }

    @GetMapping("/connections/{id}/stat")
    public Object statFile(@PathVariable Long id, @RequestParam String name) {
        MainframeConnectionEntity c = conn(id);
        return transports.forConnection(c).stat(c, name);
    }

    // ============================================================== copybooks

    public record CopybookReq(String name, String source, String codePage) {}

    @GetMapping("/copybooks")
    public List<Map<String, Object>> listCopybooks() {
        List<CopybookDefEntity> all = copybooks.findAll();
        all.sort(Comparator.comparing(CopybookDefEntity::getName, String.CASE_INSENSITIVE_ORDER));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CopybookDefEntity d : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId()); m.put("name", d.getName()); m.put("codePage", d.getCodePage());
            m.put("recordName", d.getRecordName()); m.put("recordLength", d.getRecordLength());
            out.add(m);
        }
        return out;
    }

    @GetMapping("/copybooks/{id}")
    public CopybookDefEntity getCopybook(@PathVariable Long id) { return copybook(id); }

    @PostMapping("/copybooks")
    public CopybookDefEntity createCopybook(@RequestBody CopybookReq req) {
        if (req.name() == null || req.name().isBlank()) throw ApiException.bad("name required");
        if (copybooks.findByName(req.name()).isPresent())
            throw ApiException.bad("A copybook named '" + req.name() + "' already exists");
        CopybookDefEntity d = new CopybookDefEntity();
        d.setName(req.name().trim());
        applyCopybook(d, req);
        return copybooks.save(d);
    }

    @PutMapping("/copybooks/{id}")
    public CopybookDefEntity updateCopybook(@PathVariable Long id, @RequestBody CopybookReq req) {
        CopybookDefEntity d = copybook(id);
        if (req.name() != null && !req.name().isBlank()) d.setName(req.name().trim());
        applyCopybook(d, req);
        d.setUpdatedAt(Instant.now());
        return copybooks.save(d);
    }

    private void applyCopybook(CopybookDefEntity d, CopybookReq req) {
        if (req.source() == null || req.source().isBlank()) throw ApiException.bad("copybook source required");
        d.setSource(req.source());
        d.setCodePage(req.codePage() == null || req.codePage().isBlank() ? "Cp037" : req.codePage().trim());
        Copybook cb = CopybookSupport.parse(req.source());   // validates + computes layout
        Field rec = cb.primaryRecord();
        d.setRecordName(rec.name());
        d.setRecordLength(rec.length());
    }

    @DeleteMapping("/copybooks/{id}")
    public Map<String, Object> deleteCopybook(@PathVariable Long id) {
        masks.deleteByCopybookId(id);
        copybooks.deleteById(id);
        return Map.of("deleted", id);
    }

    @GetMapping("/copybooks/{id}/fields")
    public List<CopybookSupport.FieldInfo> copybookFields(@PathVariable Long id) {
        CopybookDefEntity d = copybook(id);
        Copybook cb = CopybookSupport.parse(d.getSource());
        return CopybookSupport.structuralFields(cb, cb.primaryRecord());
    }

    @GetMapping("/copybooks/{id}/masks")
    public List<CopybookMaskEntity> copybookMasks(@PathVariable Long id) {
        return masks.findByCopybookId(id);
    }

    public record MaskReq(String fieldPath, String function, String param1, String param2) {}

    @PutMapping("/copybooks/{id}/masks")
    public List<CopybookMaskEntity> saveCopybookMasks(@PathVariable Long id, @RequestBody List<MaskReq> req) {
        copybook(id);                       // verify exists
        masks.deleteByCopybookId(id);
        List<CopybookMaskEntity> saved = new ArrayList<>();
        for (MaskReq r : req) {
            if (r.fieldPath() == null || r.fieldPath().isBlank()) continue;
            if (r.function() == null || r.function().isBlank()) continue;
            CopybookMaskEntity m = new CopybookMaskEntity();
            m.setCopybookId(id);
            m.setFieldPath(r.fieldPath().trim());
            m.setFunction(r.function().trim().toUpperCase(Locale.ROOT));
            m.setParam1(blankToNull(r.param1()));
            m.setParam2(blankToNull(r.param2()));
            saved.add(masks.save(m));
        }
        return saved;
    }

    // =================================================================== jobs

    public record JobFileReq(String sourceName, Long copybookId, String recfm, Integer lrecl, String codePage,
                             Long targetConnectionId, String targetName) {}
    public record JobReq(String name, Long sourceConnectionId, Long targetConnectionId, String maskingSeed,
                         List<JobFileReq> files) {}

    @GetMapping("/jobs")
    public List<MainframeJobEntity> listJobs() {
        List<MainframeJobEntity> all = jobs.findAll();
        all.sort(Comparator.comparing(MainframeJobEntity::getId).reversed());
        return all;
    }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> getJob(@PathVariable Long id) {
        MainframeJobEntity job = jobs.findById(id).orElseThrow(() -> ApiException.notFound("Job " + id + " not found"));
        return Map.of("job", job, "files", jobFiles.findByJobIdOrderByOrdinalAsc(id));
    }

    @PostMapping("/jobs")
    public Map<String, Object> createJob(@RequestBody JobReq req) {
        if (req.name() == null || req.name().isBlank()) throw ApiException.bad("job name required");
        if (req.sourceConnectionId() == null) throw ApiException.bad("source connection required");
        if (req.targetConnectionId() == null) throw ApiException.bad("target connection required");
        if (req.files() == null || req.files().isEmpty()) throw ApiException.bad("add at least one file");

        MainframeJobEntity job = new MainframeJobEntity();
        job.setName(req.name().trim());
        job.setSourceConnectionId(req.sourceConnectionId());
        job.setTargetConnectionId(req.targetConnectionId());
        job.setMaskingSeed(blankToNull(req.maskingSeed()));
        job.setStatus("PENDING");
        job.setFilesTotal(req.files().size());
        job = jobs.save(job);

        int ord = 0;
        for (JobFileReq f : req.files()) {
            if (f.sourceName() == null || f.sourceName().isBlank()) throw ApiException.bad("each file needs a source name");
            if (f.copybookId() == null) throw ApiException.bad("each file needs a copybook");
            MainframeJobFileEntity e = new MainframeJobFileEntity();
            e.setJobId(job.getId());
            e.setSourceName(f.sourceName().trim());
            e.setCopybookId(f.copybookId());
            e.setRecfm(f.recfm() == null || f.recfm().isBlank() ? "FB" : f.recfm().trim().toUpperCase(Locale.ROOT));
            e.setLrecl(f.lrecl());
            e.setCodePage(blankToNull(f.codePage()));
            e.setTargetConnectionId(f.targetConnectionId());
            e.setTargetName(blankToNull(f.targetName()));
            e.setOrdinal(ord++);
            jobFiles.save(e);
        }

        masking.submitAsync(job.getId());
        return Map.of("job", job, "files", jobFiles.findByJobIdOrderByOrdinalAsc(job.getId()));
    }

    // =============================================================== helpers

    private MainframeConnectionEntity conn(Long id) {
        return connections.findById(id).orElseThrow(() -> ApiException.notFound("Connection " + id + " not found"));
    }

    private CopybookDefEntity copybook(Long id) {
        return copybooks.findById(id).orElseThrow(() -> ApiException.notFound("Copybook " + id + " not found"));
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
