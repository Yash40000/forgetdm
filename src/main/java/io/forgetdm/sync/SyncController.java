package io.forgetdm.sync;

import io.forgetdm.common.ApiException;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Coordinated multi-source extraction (RFP §3.1.1). Routes are governed by the virtualization
 * permission family in AccessControlFilter (read = virtualization.read, writes = virtualization.manage).
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService svc;

    public SyncController(SyncService svc) { this.svc = svc; }

    // ---- sync sets ----------------------------------------------------------

    @GetMapping("/sets")
    public List<SyncSetEntity> listSets() { return svc.listSets(); }

    @GetMapping("/sets/{id}")
    public SyncSetEntity getSet(@PathVariable Long id) { return svc.getSet(id); }

    @PostMapping("/sets")
    public SyncSetEntity createSet(@RequestBody Map<String, Object> body) {
        return svc.createSet(str(body.get("name")), str(body.get("description")));
    }

    @DeleteMapping("/sets/{id}")
    public Map<String, Object> deleteSet(@PathVariable Long id) {
        svc.deleteSet(id);
        return Map.of("deleted", true);
    }

    // ---- members ------------------------------------------------------------

    @GetMapping("/sets/{id}/members")
    public List<SyncSetMemberEntity> members(@PathVariable Long id) { return svc.listMembers(id); }

    @PostMapping("/sets/{id}/members")
    public SyncSetMemberEntity addMember(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (body == null || body.get("dataSourceId") == null) throw ApiException.bad("dataSourceId is required");
        Long dsId = Long.valueOf(String.valueOf(body.get("dataSourceId")));
        return svc.addMember(id, dsId, str(body.get("schema")));
    }

    @DeleteMapping("/members/{memberId}")
    public Map<String, Object> removeMember(@PathVariable Long memberId) {
        svc.removeMember(memberId);
        return Map.of("removed", true);
    }

    // ---- coordinated run ----------------------------------------------------

    @PostMapping("/sets/{id}/run")
    public SyncService.SyncRunView run(@PathVariable Long id) { return svc.run(id); }

    @GetMapping("/sets/{id}/runs")
    public List<SyncRunEntity> runs(@PathVariable Long id) { return svc.listRuns(id); }

    @GetMapping("/runs/{runId}")
    public SyncService.SyncRunView getRun(@PathVariable Long runId) { return svc.getRun(runId); }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
