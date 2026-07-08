package io.forgetdm.virtualization;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/virtualization")
public class VirtualizationController {
    private final VirtualizationService svc;

    public VirtualizationController(VirtualizationService svc) {
        this.svc = svc;
    }

    // ---------------------------------------------------------------- listings

    @GetMapping("/snapshots")
    public List<VirtualSnapshotEntity> snapshots() {
        return svc.listSnapshots();
    }

    @GetMapping("/vdbs")
    public List<VirtualDatabaseEntity> vdbs() {
        return svc.listVdbs();
    }

    @GetMapping("/timeflows")
    public List<TimeFlowEntity> timeflows() {
        return svc.listTimeflows();
    }

    @GetMapping("/pool")
    public Map<String, Object> pool() {
        return svc.poolStats();
    }

    @GetMapping("/docker")
    public Map<String, Object> docker() {
        return svc.dockerStatus();
    }

    @GetMapping("/zfs")
    public Map<String, Object> zfs() {
        return svc.zfsStatus();
    }

    /** Validate the ZFS engine over SSH (zfs/docker/nfs) before capturing a snapshot. */
    @GetMapping("/engine-test")
    public Map<String, Object> engineTest() {
        return svc.engineTest();
    }

    // ---------------------------------------------------------- snapshot CRUD

    /** Async: returns { opId } immediately; poll GET /operations/{opId} for live progress. */
    @PostMapping("/snapshots")
    public Map<String, Object> snapshotDataSource(@RequestBody Map<String, Object> body) {
        Long dataSourceId = Long.valueOf(String.valueOf(body.get("dataSourceId")));
        String schemaName = str(body.get("schemaName"));
        String name = str(body.get("name"));
        String note = str(body.get("note"));
        String provider = str(body.get("provider"));
        return svc.startSnapshot(dataSourceId, schemaName, name, note, provider);
    }

    /** Live progress for an in-flight snapshot/provision operation. */
    @GetMapping("/operations/{id}")
    public Map<String, Object> operation(@PathVariable String id) {
        return svc.operation(id);
    }

    @GetMapping("/operations")
    public List<Map<String, Object>> operations() {
        return svc.operations();
    }

    /** Cancel a running snapshot/provision operation. */
    @PostMapping("/operations/{id}/cancel")
    public Map<String, Object> cancelOperation(@PathVariable String id) {
        return svc.cancelOperation(id);
    }

    @DeleteMapping("/snapshots/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSnapshot(@PathVariable Long id) {
        svc.deleteSnapshot(id);
    }

    // ------------------------------------------------------------ VDB CRUD

    /** Async: returns { opId } immediately; poll GET /operations/{opId} for live progress. */
    @PostMapping("/vdbs")
    public Map<String, Object> provision(@RequestBody Map<String, Object> body) {
        Long snapshotId   = Long.valueOf(String.valueOf(body.get("snapshotId")));
        String name        = str(body.get("name"));
        String targetStr   = str(body.get("targetDataSourceId"));
        String pointInTime = str(body.get("pointInTime"));
        String envStr      = str(body.get("environmentId"));
        Long targetDataSourceId = targetStr == null ? null : Long.valueOf(targetStr);
        Long environmentId      = envStr    == null ? null : Long.valueOf(envStr);
        return svc.startProvision(snapshotId, name, targetDataSourceId, pointInTime, environmentId);
    }

    @DeleteMapping("/vdbs/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVdb(@PathVariable Long id) {
        svc.deleteVdb(id);
    }

    // ----------------------------------------------------- VDB operations

    @PostMapping("/vdbs/{id}/snapshots")
    public VirtualSnapshotEntity snapshotVdb(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        boolean bookmark = body.get("bookmark") != null && Boolean.parseBoolean(String.valueOf(body.get("bookmark")));
        return svc.snapshotVdb(id, name, bookmark);
    }

    @PostMapping("/vdbs/{id}/refresh")
    public VirtualDatabaseEntity refresh(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long snapshotId = Long.valueOf(String.valueOf(body.get("snapshotId")));
        return svc.refresh(id, snapshotId);
    }

    @PostMapping("/vdbs/{id}/rewind")
    public VirtualDatabaseEntity rewind(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long snapshotId = Long.valueOf(String.valueOf(body.get("snapshotId")));
        return svc.rewind(id, snapshotId);
    }

    // --------------------------------------------------- LogSync (ZFS + Postgres)

    @PostMapping("/datasources/{id}/logsync/enable")
    public Map<String, Object> enableLogSync(@PathVariable Long id) {
        return svc.enableLogSync(id);
    }

    @PostMapping("/datasources/{id}/logsync/disable")
    public Map<String, Object> disableLogSync(@PathVariable Long id) {
        return svc.disableLogSync(id);
    }

    @GetMapping("/datasources/{id}/logsync")
    public Map<String, Object> logSyncStatus(@PathVariable Long id) {
        return svc.getLogSyncStatus(id);
    }

    // ------------------------------------------- target environments CRUD

    @GetMapping("/environments")
    public List<TargetEnvironmentEntity> listEnvironments() {
        return svc.listEnvironments();
    }

    @PostMapping("/environments")
    public TargetEnvironmentEntity createEnvironment(@RequestBody TargetEnvironmentEntity body) {
        return svc.createEnvironment(body);
    }

    @DeleteMapping("/environments/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEnvironment(@PathVariable Long id) {
        svc.deleteEnvironment(id);
    }

    // ---------------------------------------------------------------- helpers

    private static String str(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return s.isBlank() ? null : s.trim();
    }
}
