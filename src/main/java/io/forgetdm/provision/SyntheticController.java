package io.forgetdm.provision;

import io.forgetdm.core.synth.Generators;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

@RestController
@RequestMapping("/api/synthetic")
public class SyntheticController {

    private final SyntheticGenService gen;
    private final SyntheticProfileService profiler;

    public SyntheticController(SyntheticGenService gen, SyntheticProfileService profiler) {
        this.gen = gen;
        this.profiler = profiler;
    }

    @GetMapping("/generators")
    public List<Generators.GeneratorSpec> generators() {
        return Generators.catalogDetails();
    }

    /** Profile a real table and suggest realistic generators/params for each column (learn from production). */
    @PostMapping("/profile")
    public Map<String, Object> profile(@RequestBody Map<String, Object> body) {
        Long dsId = body.get("dataSourceId") == null ? null : Long.valueOf(String.valueOf(body.get("dataSourceId")));
        String schema = stringOrNull(body.get("schema"));
        String table = stringOrNull(body.get("table"));
        boolean allowSourceDistributions = boolOr(body.get("allowSourceDistributions"), false);
        boolean bankingSafe = boolOr(body.get("bankingSafeProfile"), true) && !allowSourceDistributions;
        return profiler.profile(dsId, schema, table, bankingSafe);
    }

    /**
     * Multi-table synthetic generation with referential integrity and a chosen receiver
     * (DB load / CSV / JSON / SQL). Returns generated files inline for the file receivers.
     */
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody SyntheticGenService.GenPlan plan) {
        return gen.generate(plan);
    }

    @PostMapping("/generate/start")
    public Map<String, Object> startGenerate(@RequestBody SyntheticGenService.GenPlan plan) {
        return gen.startGenerate(plan);
    }

    @PostMapping("/plan-summary")
    public Map<String, Object> planSummary(@RequestBody SyntheticGenService.GenPlan plan) {
        return gen.planSummary(plan);
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> generateJobs() {
        return gen.generateJobs();
    }

    @GetMapping("/jobs/{id}")
    public Map<String, Object> generateJob(@PathVariable String id) {
        return gen.generateJob(id);
    }

    @PostMapping("/jobs/{id}/cancel")
    public Map<String, Object> cancelGenerate(@PathVariable String id) {
        return gen.cancelGenerate(id);
    }

    @PostMapping("/jobs/{jobId}/partitions/{partitionId}/cancel")
    public Map<String, Object> cancelPartition(@PathVariable String jobId, @PathVariable String partitionId) {
        return gen.cancelPartition(jobId, partitionId);
    }

    @PostMapping("/jobs/{jobId}/partitions/{partitionId}/retry")
    public Map<String, Object> retryPartition(@PathVariable String jobId, @PathVariable String partitionId) {
        return gen.retryPartition(jobId, partitionId);
    }

    @GetMapping("/saved-jobs")
    public List<Map<String, Object>> savedJobs() {
        return gen.savedJobs();
    }

    @PostMapping("/saved-jobs")
    public Map<String, Object> saveSyntheticJob(@RequestBody SyntheticGenService.SavedSyntheticJobRequest request) {
        return gen.saveSyntheticJob(request);
    }

    @GetMapping("/saved-jobs/{id}")
    public Map<String, Object> savedJob(@PathVariable String id) {
        return gen.savedJob(id);
    }

    @PutMapping("/saved-jobs/{id}")
    public Map<String, Object> updateSavedJob(@PathVariable String id, @RequestBody SyntheticGenService.SavedSyntheticJobRequest request) {
        return gen.updateSavedJob(id, request);
    }

    @DeleteMapping("/saved-jobs/{id}")
    public void deleteSavedJob(@PathVariable String id) {
        gen.deleteSavedJob(id);
    }

    @PostMapping("/saved-jobs/{id}/run")
    public Map<String, Object> runSavedJob(@PathVariable String id) {
        return gen.runSavedJob(id);
    }

    @PostMapping("/saved-jobs/{id}/export")
    public Map<String, Object> exportSavedJobRunner(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return gen.exportSavedJobRunner(id, body);
    }

    @PostMapping("/saved-jobs/{id}/approval/request")
    public Map<String, Object> requestSavedJobApproval(@PathVariable String id,
                                                       @RequestBody(required = false) SyntheticGenService.ApprovalRequest request) {
        return gen.requestSavedJobApproval(id, request);
    }

    @PostMapping("/saved-jobs/{id}/approval/approve")
    public Map<String, Object> approveSavedJob(@PathVariable String id,
                                               @RequestBody(required = false) SyntheticGenService.ApprovalRequest request) {
        return gen.approveSavedJob(id, request);
    }

    @PostMapping("/saved-jobs/{id}/approval/reject")
    public Map<String, Object> rejectSavedJob(@PathVariable String id,
                                              @RequestBody(required = false) SyntheticGenService.ApprovalRequest request) {
        return gen.rejectSavedJob(id, request);
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
        String generator = String.valueOf(body.getOrDefault("generator", "SEQUENCE"));
        String param1 = stringOrNull(body.get("param1"));
        String param2 = stringOrNull(body.get("param2"));
        long seed = longOr(body.get("seed"), 42L);
        int rows = Math.max(1, Math.min(25, (int) longOr(body.get("rows"), 5L)));

        Random rng = new Random(seed);
        var fn = Generators.of(generator, param1, param2, seed, "preview." + generator);
        List<String> values = new ArrayList<>();
        for (long i = 1; i <= rows; i++) values.add(fn.apply(i, rng));
        return Map.of("generator", generator, "values", values);
    }

    private static String stringOrNull(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return s.isBlank() ? null : s;
    }

    private static long longOr(Object value, long fallback) {
        try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); }
        catch (Exception e) { return fallback; }
    }

    private static boolean boolOr(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        String s = String.valueOf(value).trim();
        if (s.isBlank()) return fallback;
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "y".equalsIgnoreCase(s);
    }
}
