package io.forgetdm.cdc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.OwnershipGuard;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Orchestrates true (log-based) CDC on top of {@link CdcProvider} engines.
 *
 * A capture is governed exactly like the data source it reads: the owning user/group is
 * stamped on enable and every read/write is checked through {@link OwnershipGuard}. Every
 * material action is audited.
 */
@Service
public class CdcService {

    private static final int MAX_POLL_CHANGES = 5_000;
    private static final long POLL_BUDGET_MS = 4_000;

    private final CdcCaptureRepository captures;
    private final CdcChangeRepository changes;
    private final DataSourceService dataSources;
    private final List<CdcProvider> providers;
    private final CdcIncrementalApplier applier;
    private final OwnershipGuard ownership;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Map<Long, Object> pollLocks = new ConcurrentHashMap<>();

    public CdcService(CdcCaptureRepository captures, CdcChangeRepository changes,
                      DataSourceService dataSources, List<CdcProvider> providers,
                      CdcIncrementalApplier applier, OwnershipGuard ownership,
                      AuditService audit, ObjectMapper json) {
        this.captures = captures;
        this.changes = changes;
        this.dataSources = dataSources;
        this.providers = providers;
        this.applier = applier;
        this.ownership = ownership;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ queries

    public Preflight preflight(Long dataSourceId) {
        DataSourceEntity ds = requireVisibleDataSource(dataSourceId);
        CdcProvider provider = providerFor(ds);
        CdcProvider.Preflight p = provider.preflight(ds);
        return new Preflight(provider.mechanism(), p.ok(), p.logLevel(), p.privileged(), p.messages());
    }

    public Status status(Long dataSourceId) {
        DataSourceEntity ds = requireVisibleDataSource(dataSourceId);
        CdcProvider provider = providerFor(ds);
        Optional<CdcCaptureEntity> maybe = captures.findByDataSourceId(dataSourceId);
        if (maybe.isEmpty()) {
            return new Status(dataSourceId, ds.getName(), provider.mechanism(),
                    false, "INACTIVE", null, null, null, 0, 0, null, null, null, null,
                    provider.lagUnit());
        }
        CdcCaptureEntity c = maybe.get();
        assertCanTouch(c);
        long buffered = changes.countByDataSourceId(dataSourceId);
        String currentPosition = null;
        Long lag = null;
        if ("ACTIVE".equals(c.getStatus())) {
            try { currentPosition = provider.currentLogPosition(ds); } catch (Exception ignored) {}
            try { lag = provider.lag(ds, c.getConfirmedLsn()); } catch (Exception ignored) {}
        }
        return new Status(dataSourceId, ds.getName(), provider.mechanism(),
                "ACTIVE".equals(c.getStatus()), c.getStatus(), c.getSlotName(),
                c.getConfirmedLsn(), c.getRestartLsn(), c.getRowsCaptured(), buffered,
                c.getLastPolledAt(), c.getLastError(), currentPosition, lag, provider.lagUnit());
    }

    public List<CdcChangeEntity> recentChanges(Long dataSourceId, int limit) {
        CdcCaptureEntity c = requireCapture(dataSourceId);
        assertCanTouch(c);
        int capped = Math.max(1, Math.min(limit, 1000));
        return changes.findByDataSourceIdOrderByIdDesc(dataSourceId, PageRequest.of(0, capped));
    }

    // ------------------------------------------------------------------ lifecycle

    public Status enable(Long dataSourceId, String schema, List<String> tables) {
        DataSourceEntity ds = requireVisibleDataSource(dataSourceId);
        CdcProvider provider = providerFor(ds);

        Optional<CdcCaptureEntity> existing = captures.findByDataSourceId(dataSourceId);
        if (existing.isPresent() && "ACTIVE".equals(existing.get().getStatus())) {
            assertCanTouch(existing.get());
            return status(dataSourceId);
        }

        CdcProvider.Preflight pf = provider.preflight(ds);
        if (!pf.ok()) {
            throw ApiException.bad("Source is not ready for log-based CDC: " + String.join(" ", pf.messages()));
        }

        String slot = slotName(dataSourceId);
        CdcProvider.SlotInfo slotInfo = provider.createSlot(ds, slot);

        CdcCaptureEntity c = existing.orElseGet(CdcCaptureEntity::new);
        c.setDataSourceId(dataSourceId);
        c.setSlotName(slot);
        c.setPlugin(provider.pluginName());
        c.setSchemaName(schema);
        c.setTablesJson(writeTables(tables));
        c.setStatus("ACTIVE");
        c.setRestartLsn(slotInfo.restartLsn());
        c.setConfirmedLsn(slotInfo.confirmedLsn());
        c.setLastError(null);
        if (c.getId() == null) {
            c.setOwnerUserId(ownership.defaultOwnerUserId());
            c.setOwnerUsername(ownership.defaultOwnerUsername());
            c.setOwnerGroupId(ownership.defaultOwnerGroupId());
            c.setVisibility(ownership.defaultVisibility());
        }
        captures.save(c);

        audit.log(actor(), "CDC_ENABLED",
                "dataSource=" + ds.getName() + " slot=" + slot + " plugin=" + provider.pluginName()
                        + " startLsn=" + slotInfo.confirmedLsn());
        return status(dataSourceId);
    }

    public Status disable(Long dataSourceId) {
        CdcCaptureEntity c = requireCapture(dataSourceId);
        assertCanTouch(c);
        DataSourceEntity ds = dataSources.get(dataSourceId);
        CdcProvider provider = providerFor(ds);
        provider.dropSlot(ds, c.getSlotName());
        c.setStatus("INACTIVE");
        captures.save(c);
        audit.log(actor(), "CDC_DISABLED", "dataSource=" + ds.getName() + " slot=" + c.getSlotName());
        return status(dataSourceId);
    }

    /** Read pending changes from the transaction log now, persist them, and advance the LSN. */
    public PollSummary poll(Long dataSourceId) {
        Object lock = pollLocks.computeIfAbsent(dataSourceId, ignored -> new Object());
        synchronized (lock) {
            return pollLocked(dataSourceId);
        }
    }

    private PollSummary pollLocked(Long dataSourceId) {
        CdcCaptureEntity c = requireCapture(dataSourceId);
        assertCanTouch(c);
        if (!"ACTIVE".equals(c.getStatus())) {
            throw ApiException.bad("CDC is not active for this data source. Enable it first.");
        }
        DataSourceEntity ds = dataSources.get(dataSourceId);
        CdcProvider provider = providerFor(ds);

        Set<String> wanted = wantedTables(c);
        String wantSchema = c.getSchemaName();

        CdcProvider.PollResult result;
        try {
            result = provider.poll(ds, c, MAX_POLL_CHANGES, POLL_BUDGET_MS);
        } catch (RuntimeException e) {
            c.setLastError(e.getMessage());
            c.setLastPolledAt(Instant.now());
            captures.save(c);
            audit.log(actor(), "CDC_POLL_FAILED", "dataSource=" + ds.getName() + " error=" + e.getMessage());
            throw e;
        }

        List<CdcChangeEntity> toSave = new ArrayList<>();
        for (CdcProvider.DecodedChange d : result.changes()) {
            if (wantSchema != null && !wantSchema.isBlank() && !wantSchema.equalsIgnoreCase(d.schema)) continue;
            if (!wanted.isEmpty() && !wanted.contains((d.schema + "." + d.table).toLowerCase(Locale.ROOT))) continue;
            CdcChangeEntity e = new CdcChangeEntity();
            e.setCaptureId(c.getId());
            e.setDataSourceId(dataSourceId);
            e.setLsn(d.lsn);
            e.setXid(d.xid);
            e.setSchemaName(d.schema);
            e.setTableName(d.table);
            e.setOp(d.op);
            e.setPkJson(writeJson(d.pk));
            e.setChangeJson(writeJson(d.values));
            toSave.add(e);
        }
        if (!toSave.isEmpty()) changes.saveAll(toSave);

        c.setConfirmedLsn(result.confirmedLsn());
        c.setRowsCaptured(c.getRowsCaptured() + toSave.size());
        c.setLastPolledAt(Instant.now());
        c.setLastError(null);
        captures.save(c);

        audit.log(actor(), "CDC_POLLED",
                "dataSource=" + ds.getName() + " captured=" + toSave.size()
                        + " decoded=" + result.changes().size() + " lsn=" + result.confirmedLsn());

        return new PollSummary(dataSourceId, toSave.size(), result.changes().size(),
                result.confirmedLsn(), result.reachedEnd());
    }

    /**
     * Incremental refresh: apply the buffered CDC changes to a target as netted UPSERT/DELETE
     * (only altered rows), then optionally purge the applied changes from the buffer.
     */
    @Transactional
    public Map<String, Object> applyIncremental(Long dataSourceId, Long targetDataSourceId, boolean purge) {
        return applyIncremental(dataSourceId, targetDataSourceId, purge, null, null);
    }

    /**
     * Point-in-time apply: replay the buffered CDC changes to a target up to a chosen bound —
     * {@code throughChangeId} (inclusive) and/or {@code throughTs} (captured at or before) — so the
     * target reflects the source's exact state as of that moment. With no bound this is a full
     * incremental refresh. Bounded replays default to NOT purging (so the same buffer can be replayed
     * to different points), which is what makes a virtual DB "as of time T".
     */
    @Transactional
    public Map<String, Object> applyIncremental(Long dataSourceId, Long targetDataSourceId, boolean purge,
                                                Long throughChangeId, Instant throughTs) {
        CdcCaptureEntity c = requireCapture(dataSourceId);
        assertCanTouch(c);
        DataSourceEntity target = requireVisibleDataSource(targetDataSourceId);
        boolean pointInTime = throughChangeId != null || throughTs != null;

        List<CdcChangeEntity> all = loadBufferedChanges(c.getId(), 0L);
        List<CdcChangeEntity> ordered = all.stream()
                .filter(ch -> throughChangeId == null || ch.getId() <= throughChangeId)
                .filter(ch -> throughTs == null || ch.getCapturedAt() == null || !ch.getCapturedAt().isAfter(throughTs))
                .toList();
        if (ordered.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("applied", false);
            m.put("reason", all.isEmpty() ? "No buffered changes to apply. Poll first."
                    : "No buffered changes at or before the requested point.");
            return m;
        }

        CdcIncrementalApplier.ApplyResult r = applier.apply(target, ordered);
        // Never purge past the requested point; only a full (unbounded) apply may purge.
        long purged = (purge && !pointInTime)
                ? changes.deleteByCaptureIdAndIdLessThanEqual(c.getId(), r.consumedThroughId()) : 0;

        String pointDesc = pointInTime
                ? ("throughChangeId=" + throughChangeId + " throughTs=" + throughTs)
                : "latest";
        audit.log(actor(), "CDC_APPLIED",
                "source=" + dataSourceId + " target=" + target.getName()
                        + " point=" + pointDesc
                        + " upserts=" + r.upserts() + " deletes=" + r.deletes()
                        + " tables=" + r.tables() + " purged=" + purged);

        Map<String, Object> m = new LinkedHashMap<>(r.asMap());
        m.put("applied", true);
        m.put("targetDataSourceId", targetDataSourceId);
        m.put("pointInTime", pointInTime);
        m.put("appliedThroughChangeId", r.consumedThroughId());
        m.put("changesReplayed", ordered.size());
        m.put("purgedFromBuffer", purged);
        return m;
    }

    /** Poll every active capture once — driven by the continuous background poller. Never throws. */
    public int pollAllActive() {
        int polled = 0;
        for (CdcCaptureEntity c : captures.findByStatus("ACTIVE")) {
            try { poll(c.getDataSourceId()); polled++; }
            catch (RuntimeException e) { /* poll() records the error and leaves the capture retryable */ }
        }
        return polled;
    }

    /** Load an ordered, untruncated change range for TimeFlow and incremental replay. */
    List<CdcChangeEntity> loadBufferedChanges(Long captureId, long afterId) {
        List<CdcChangeEntity> out = new ArrayList<>();
        long cursor = afterId;
        while (true) {
            List<CdcChangeEntity> page = changes.findByCaptureIdAndIdGreaterThanOrderByIdAsc(
                    captureId, cursor, PageRequest.of(0, 5_000));
            if (page.isEmpty()) return out;
            out.addAll(page);
            cursor = page.get(page.size() - 1).getId();
            if (page.size() < 5_000) return out;
        }
    }

    /** Atomically pair the durable source checkpoint with its last persisted change id. */
    CdcCheckpoint checkpoint(Long dataSourceId) {
        Object lock = pollLocks.computeIfAbsent(dataSourceId, ignored -> new Object());
        synchronized (lock) {
            CdcCaptureEntity capture = requireCapture(dataSourceId);
            assertCanTouch(capture);
            return new CdcCheckpoint(capture.getId(), capture.getSchemaName(), capture.getStatus(),
                    capture.getConfirmedLsn(), changes.maxIdForCapture(capture.getId()));
        }
    }

    // ------------------------------------------------------------------ helpers

    private CdcProvider providerFor(DataSourceEntity ds) {
        return providers.stream().filter(p -> p.supports(ds)).findFirst()
                .orElseThrow(() -> ApiException.bad(
                        "Log-based CDC is not yet supported for source '" + ds.getName()
                                + "'. Supported today: PostgreSQL."));
    }

    private DataSourceEntity requireVisibleDataSource(Long dataSourceId) {
        DataSourceEntity ds = dataSources.get(dataSourceId);
        ownership.assertCanSee("data source", dataSourceId,
                ds.getOwnerUserId(), ds.getOwnerGroupId(), ds.getVisibility());
        return ds;
    }

    private CdcCaptureEntity requireCapture(Long dataSourceId) {
        return captures.findByDataSourceId(dataSourceId)
                .orElseThrow(() -> ApiException.notFound("No CDC capture configured for this data source."));
    }

    private void assertCanTouch(CdcCaptureEntity c) {
        ownership.assertCanSee("CDC capture", c.getId(),
                c.getOwnerUserId(), c.getOwnerGroupId(), c.getVisibility());
    }

    private Set<String> wantedTables(CdcCaptureEntity c) {
        List<String> t = readTables(c.getTablesJson());
        return t.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }

    private static String slotName(Long dataSourceId) {
        return "forgetdm_cdc_" + dataSourceId;
    }

    private String actor() {
        return ownership.caller().map(p -> p.username()).orElse("system");
    }

    private String writeTables(List<String> tables) {
        if (tables == null || tables.isEmpty()) return null;
        return writeJson(tables);
    }

    private List<String> readTables(String tablesJson) {
        if (tablesJson == null || tablesJson.isBlank()) return List.of();
        try { return json.readValue(tablesJson, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ DTOs

    public record Preflight(String mechanism, boolean ok, String logLevel,
                            boolean privileged, List<String> messages) {}

    public record Status(Long dataSourceId, String dataSourceName, String mechanism,
                         boolean active, String status, String slotName,
                         String confirmedLsn, String restartLsn, long rowsCaptured,
                         long bufferedChanges, Instant lastPolledAt, String lastError,
                         String currentPosition, Long lag, String lagUnit) {}

    public record PollSummary(Long dataSourceId, int captured, int decoded,
                              String confirmedLsn, boolean reachedEnd) {

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataSourceId", dataSourceId);
            m.put("captured", captured);
            m.put("decoded", decoded);
            m.put("confirmedLsn", confirmedLsn);
            m.put("reachedEnd", reachedEnd);
            return m;
        }
    }

    record CdcCheckpoint(Long captureId, String schemaName, String status,
                         String confirmedPosition, long throughChangeId) {}
}
