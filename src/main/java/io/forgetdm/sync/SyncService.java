package io.forgetdm.sync;

import io.forgetdm.audit.AuditService;
import io.forgetdm.cdc.CdcProvider;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.OwnershipGuard;
import io.forgetdm.virtualization.VirtualSnapshotEntity;
import io.forgetdm.virtualization.VirtualizationService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Coordinated multi-source extraction (RFP §3.1.1 "Timeflow Synchronization").
 *
 * <p>A run pins every member source's transaction-log position (LSN/SCN) at one coordination instant,
 * then extracts each source in parallel using the existing lock-free TimeFlow snapshot (MVCC read —
 * no locks on production tables). The pinned positions let the members be aligned to a single sync
 * point downstream via CDC. Members are governed by the sync set's ownership.
 */
@Service
public class SyncService {

    private final SyncSetRepository sets;
    private final SyncSetMemberRepository members;
    private final SyncRunRepository runs;
    private final SyncRunMemberRepository runMembers;
    private final VirtualizationService virtualization;
    private final DataSourceService dataSources;
    private final List<CdcProvider> cdcProviders;
    private final OwnershipGuard ownership;
    private final AuditService audit;

    public SyncService(SyncSetRepository sets, SyncSetMemberRepository members, SyncRunRepository runs,
                       SyncRunMemberRepository runMembers, VirtualizationService virtualization,
                       DataSourceService dataSources, List<CdcProvider> cdcProviders,
                       OwnershipGuard ownership, AuditService audit) {
        this.sets = sets;
        this.members = members;
        this.runs = runs;
        this.runMembers = runMembers;
        this.virtualization = virtualization;
        this.dataSources = dataSources;
        this.cdcProviders = cdcProviders;
        this.ownership = ownership;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ sync set CRUD

    public List<SyncSetEntity> listSets() {
        return sets.findAll().stream()
                .filter(s -> ownership.canSee(s.getOwnerUserId(), s.getOwnerGroupId(), s.getVisibility()))
                .toList();
    }

    public SyncSetEntity getSet(Long id) {
        SyncSetEntity s = sets.findById(id).orElseThrow(() -> ApiException.notFound("Sync set not found"));
        ownership.assertCanSee("sync set", id, s.getOwnerUserId(), s.getOwnerGroupId(), s.getVisibility());
        return s;
    }

    public SyncSetEntity createSet(String name, String description) {
        if (name == null || name.isBlank()) throw ApiException.bad("Sync set name is required");
        sets.findByName(name.trim()).ifPresent(x -> { throw ApiException.conflict("A sync set '" + name.trim() + "' already exists"); });
        SyncSetEntity s = new SyncSetEntity();
        s.setName(name.trim());
        s.setDescription(blankToNull(description));
        s.setOwnerUserId(ownership.defaultOwnerUserId());
        s.setOwnerUsername(ownership.defaultOwnerUsername());
        s.setOwnerGroupId(ownership.defaultOwnerGroupId());
        s.setVisibility(ownership.defaultVisibility());
        SyncSetEntity saved = sets.save(s);
        audit.log(actor(), "SYNC_SET_CREATED", "syncSet=" + saved.getName());
        return saved;
    }

    public void deleteSet(Long id) {
        SyncSetEntity s = getSet(id);
        sets.delete(s);   // members/runs cascade via FK
        audit.log(actor(), "SYNC_SET_DELETED", "syncSet=" + s.getName());
    }

    public List<SyncSetMemberEntity> listMembers(Long setId) {
        getSet(setId);
        return members.findBySyncSetId(setId);
    }

    public SyncSetMemberEntity addMember(Long setId, Long dataSourceId, String schema) {
        getSet(setId);
        DataSourceEntity ds = dataSources.get(dataSourceId); // 404 if missing
        ownership.assertCanSee("data source", dataSourceId, ds.getOwnerUserId(), ds.getOwnerGroupId(), ds.getVisibility());
        members.findBySyncSetId(setId).stream()
                .filter(m -> m.getDataSourceId().equals(dataSourceId))
                .findAny()
                .ifPresent(m -> { throw ApiException.conflict("'" + ds.getName() + "' is already a member"); });
        SyncSetMemberEntity m = new SyncSetMemberEntity();
        m.setSyncSetId(setId);
        m.setDataSourceId(dataSourceId);
        m.setSchemaName(blankToNull(schema));
        SyncSetMemberEntity saved = members.save(m);
        audit.log(actor(), "SYNC_MEMBER_ADDED", "syncSet=" + setId + " source=" + ds.getName());
        return saved;
    }

    public void removeMember(Long memberId) {
        SyncSetMemberEntity m = members.findById(memberId).orElseThrow(() -> ApiException.notFound("Member not found"));
        getSet(m.getSyncSetId());   // ownership check via the set
        members.delete(m);
    }

    // ------------------------------------------------------------------ coordinated run

    public SyncRunView run(Long setId) {
        SyncSetEntity set = getSet(setId);
        List<SyncSetMemberEntity> memberList = members.findBySyncSetId(setId);
        if (memberList.isEmpty()) throw ApiException.bad("Sync set has no members to extract");

        // Phase 1 — pin every source's log position at one coordination instant (fast, near-simultaneous).
        Instant targetTs = Instant.now();
        record Pin(DataSourceEntity ds, SyncSetMemberEntity member, String point, String mechanism) {}
        List<Pin> pins = new ArrayList<>();
        for (SyncSetMemberEntity m : memberList) {
            DataSourceEntity ds = dataSources.get(m.getDataSourceId());
            CdcProvider provider = providerFor(ds);
            String point = provider == null ? null : safe(() -> provider.currentLogPosition(ds));
            String mechanism = provider == null ? "snapshot-only (no CDC provider)" : provider.mechanism();
            pins.add(new Pin(ds, m, point, mechanism));
        }

        SyncRunEntity run = new SyncRunEntity();
        run.setSyncSetId(setId);
        run.setStatus("RUNNING");
        run.setTargetTs(targetTs);
        run.setMemberCount(memberList.size());
        run = runs.save(run);
        final Long runId = run.getId();

        // Phase 2 — extract each source in parallel (lock-free TimeFlow snapshot).
        long t0 = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(pins.size(), 8));
        List<SyncRunMemberEntity> results = new ArrayList<>();
        try {
            List<Future<SyncRunMemberEntity>> futures = new ArrayList<>();
            for (Pin p : pins) {
                Callable<SyncRunMemberEntity> task = () -> extractMember(runId, p.ds(), p.member(), p.point(), p.mechanism(), set);
                futures.add(pool.submit(task));
            }
            for (Future<SyncRunMemberEntity> f : futures) {
                try { results.add(f.get()); }
                catch (Exception e) { /* individual failure already captured as FAILED member below */ }
            }
        } finally {
            pool.shutdown();
        }

        int succeeded = (int) results.stream().filter(r -> "SUCCESS".equals(r.getStatus())).count();
        run.setStatus(succeeded == memberList.size() ? "SUCCESS" : succeeded == 0 ? "FAILED" : "PARTIAL");
        run.setSucceededCount(succeeded);
        run.setFinishedAt(Instant.now());
        run.setWindowMs(System.currentTimeMillis() - t0);
        runs.save(run);

        audit.log(actor(), "SYNC_RUN", "syncSet=" + set.getName() + " run=" + runId
                + " status=" + run.getStatus() + " members=" + succeeded + "/" + memberList.size()
                + " windowMs=" + run.getWindowMs());

        return new SyncRunView(run, runMembers.findBySyncRunId(runId));
    }

    /** Runs on a worker thread — extracts one member and records its result. Never throws. */
    private SyncRunMemberEntity extractMember(Long runId, DataSourceEntity ds, SyncSetMemberEntity member,
                                              String point, String mechanism, SyncSetEntity set) {
        SyncRunMemberEntity rm = new SyncRunMemberEntity();
        rm.setSyncRunId(runId);
        rm.setDataSourceId(ds.getId());
        rm.setDataSourceName(ds.getName());
        rm.setSchemaName(member.getSchemaName());
        rm.setConsistencyPoint(point);
        rm.setMechanism(mechanism);
        long t0 = System.currentTimeMillis();
        try {
            String name = set.getName() + " · " + ds.getName();
            VirtualSnapshotEntity snap = virtualization.snapshotDataSource(
                    ds.getId(), member.getSchemaName(), name, "Coordinated extraction run " + runId, "POOL");
            rm.setSnapshotId(snap.getId());
            rm.setRowCount(snap.getRowCount());
            rm.setStatus("SUCCESS");
        } catch (Exception e) {
            rm.setStatus("FAILED");
            rm.setError(rootMessage(e));
        }
        rm.setElapsedMs(System.currentTimeMillis() - t0);
        return runMembers.save(rm);
    }

    public List<SyncRunEntity> listRuns(Long setId) {
        getSet(setId);
        return runs.findBySyncSetIdOrderByStartedAtDesc(setId);
    }

    public SyncRunView getRun(Long runId) {
        SyncRunEntity run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Run not found"));
        getSet(run.getSyncSetId());  // ownership check via the set
        return new SyncRunView(run, runMembers.findBySyncRunId(runId));
    }

    // ------------------------------------------------------------------ helpers

    public record SyncRunView(SyncRunEntity run, List<SyncRunMemberEntity> members) {}

    private CdcProvider providerFor(DataSourceEntity ds) {
        return cdcProviders.stream().filter(p -> p.supports(ds)).findFirst().orElse(null);
    }

    private String actor() {
        return ownership.caller().map(p -> p.username()).orElse("system");
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String safe(java.util.function.Supplier<String> s) {
        try { return s.get(); } catch (Exception e) { return null; }
    }

    private static String rootMessage(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() == null ? r.toString() : r.getMessage();
    }
}
