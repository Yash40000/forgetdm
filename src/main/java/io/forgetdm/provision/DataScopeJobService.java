package io.forgetdm.provision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Reusable, owner-private DataScope provisioning jobs — the DataScope parallel to synthetic saved jobs.
 *
 * A saved job stores the exact {@code /api/jobs} submit payload; running it (manually, via a downloaded
 * PS1/SH runner, or via the in-app scheduler) rebuilds a {@link ProvisionJobEntity} and hands it to
 * {@link ProvisioningService#submit} — so the normal maker-checker approval and governance gates still
 * apply and no separate approval workflow is duplicated here.
 */
@Service
public class DataScopeJobService {

    private final JdbcTemplate jdbc;
    private final ProvisioningService provisioning;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper();

    public DataScopeJobService(JdbcTemplate jdbc, ProvisioningService provisioning, AuditService audit) {
        this.jdbc = jdbc;
        this.provisioning = provisioning;
        this.audit = audit;
    }

    public record SavedJobRequest(String name, String description, JsonNode spec) {}
    public record ScheduleRequest(String cron, String zone, Boolean enabled) {}

    // ─────────────────────────── CRUD ───────────────────────────

    public Map<String, Object> save(SavedJobRequest req) {
        AccessPrincipal p = requirePrincipal();
        String name = cleanName(req == null ? null : req.name());
        JsonNode spec = req == null ? null : req.spec();
        validateSpec(spec);
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        try {
            jdbc.update("INSERT INTO datascope_saved_jobs(id, owner_user_id, owner_username, name, description, spec_json, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    id, p.userId(), p.username(), name, blankNull(req.description()), spec.toString(), now, now);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("You already have a saved DataScope job named " + name);
        }
        audit.log(p.username(), "DATASCOPE_JOB_SAVED", "savedJob=" + id + " name=" + name);
        return query(id);
    }

    public List<Map<String, Object>> list() {
        AccessPrincipal p = requirePrincipal();
        String sql = p.hasPermission("admin.all")
                ? "SELECT * FROM datascope_saved_jobs ORDER BY updated_at DESC"
                : "SELECT * FROM datascope_saved_jobs WHERE owner_user_id = ? OR owner_username = ? ORDER BY updated_at DESC";
        List<Map<String, Object>> rows = p.hasPermission("admin.all")
                ? jdbc.query(sql, (rs, n) -> mapRow(rs, false))
                : jdbc.query(sql, (rs, n) -> mapRow(rs, false), p.userId(), p.username());
        attachLastRunStatus(rows);   // so the UI can badge jobs whose last run is parked awaiting approval
        return rows;
    }

    /** Attach the live status of each job's last run (e.g. AWAITING_APPROVAL) in one batched lookup. */
    private void attachLastRunStatus(List<Map<String, Object>> rows) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Object lr = r.get("lastRunJobId");
            if (lr instanceof Long l && !ids.contains(l)) ids.add(l);
        }
        if (ids.isEmpty()) return;
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        Map<Long, String> statusById = new HashMap<>();
        try {
            jdbc.query("SELECT id, status FROM provision_jobs WHERE id IN (" + placeholders + ")",
                    (java.sql.ResultSet rs) -> { statusById.put(rs.getLong("id"), rs.getString("status")); },
                    ids.toArray());
        } catch (Exception ignore) { return; }   // never let the enrichment break the listing
        for (Map<String, Object> r : rows) {
            Object lr = r.get("lastRunJobId");
            if (lr instanceof Long l && statusById.containsKey(l)) r.put("lastRunStatus", statusById.get(l));
        }
    }

    public Map<String, Object> get(String id) { return query(id); }

    public Map<String, Object> update(String id, SavedJobRequest req) {
        Map<String, Object> existing = query(id);   // access-checks ownership
        String name = req == null || req.name() == null || req.name().isBlank()
                ? String.valueOf(existing.get("name")) : cleanName(req.name());
        JsonNode spec = req == null ? null : req.spec();
        if (spec == null || spec.isNull() || spec.isMissingNode()) spec = readSpec(id);
        validateSpec(spec);
        try {
            jdbc.update("UPDATE datascope_saved_jobs SET name = ?, description = ?, spec_json = ?, updated_at = ? WHERE id = ?",
                    name, req == null ? null : blankNull(req.description()), spec.toString(), Timestamp.from(Instant.now()), id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("You already have a saved DataScope job named " + name);
        }
        audit.log("system", "DATASCOPE_JOB_UPDATED", "savedJob=" + id + " name=" + name);
        return query(id);
    }

    public void delete(String id) {
        Map<String, Object> existing = query(id);
        jdbc.update("DELETE FROM datascope_saved_jobs WHERE id = ?", id);
        audit.log("system", "DATASCOPE_JOB_DELETED", "savedJob=" + id + " name=" + existing.get("name"));
    }

    // ─────────────────────────── Run ───────────────────────────

    /** Manual run (from the UI or a downloaded runner). Ownership is checked, then delegated to submit(). */
    public Map<String, Object> run(String id) {
        Map<String, Object> saved = query(id);   // access-checks ownership
        return runInternal(id, saved, "manual");
    }

    /** Rebuild the provisioning job from the saved spec and submit it through the normal gates. */
    private Map<String, Object> runInternal(String id, Map<String, Object> saved, String trigger) {
        JsonNode spec = readSpec(id);
        ProvisionJobEntity job = buildJob(spec, String.valueOf(saved.get("name")));
        ProvisionJobEntity submitted = provisioning.submit(job);
        jdbc.update("UPDATE datascope_saved_jobs SET last_run_job_id = ?, updated_at = ? WHERE id = ?",
                submitted.getId(), Timestamp.from(Instant.now()), id);
        audit.log("system", "DATASCOPE_JOB_RUN",
                "savedJob=" + id + " run=" + submitted.getId() + " status=" + submitted.getStatus() + " trigger=" + trigger);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("savedJobId", id);
        out.put("runId", submitted.getId());
        out.put("status", submitted.getStatus());
        out.put("approvalStatus", submitted.getApprovalStatus());
        out.put("message", submitted.getMessage());
        return out;
    }

    private ProvisionJobEntity buildJob(JsonNode spec, String fallbackName) {
        ProvisionJobEntity job = new ProvisionJobEntity();
        job.setName(text(spec, "name", fallbackName));
        job.setJobType(text(spec, "jobType", "SUBSET_MASK"));
        job.setSourceId(longOrNull(spec, "sourceId"));
        job.setTargetId(longOrNull(spec, "targetId"));
        job.setPolicyId(longOrNull(spec, "policyId"));
        job.setDatasetId(longOrNull(spec, "datasetId"));
        JsonNode sj = spec.get("specJson");
        if (sj != null && !sj.isNull()) job.setSpecJson(sj.isTextual() ? sj.asText() : sj.toString());
        return job;
    }

    // ─────────────────────────── Scheduling ───────────────────────────

    public Map<String, Object> setSchedule(String id, ScheduleRequest req) {
        query(id);   // access-checks ownership
        boolean enabled = req != null && Boolean.TRUE.equals(req.enabled());
        String cron = req == null ? null : blankNull(req.cron());
        String zone = req == null ? null : blankNull(req.zone());
        ZoneId zoneId = resolveZone(zone);
        Timestamp next = null;
        if (enabled) {
            if (cron == null) throw ApiException.bad("A cron expression is required to enable the schedule.");
            Instant nextInstant = computeNextRun(cron, zoneId, Instant.now());
            if (nextInstant == null) throw ApiException.bad("Cron '" + cron + "' has no future run time.");
            next = Timestamp.from(nextInstant);
        }
        jdbc.update("UPDATE datascope_saved_jobs SET schedule_cron = ?, schedule_zone = ?, schedule_enabled = ?, " +
                        "next_run_at = ?, updated_at = ? WHERE id = ?",
                cron, zoneId.getId(), enabled, next, Timestamp.from(Instant.now()), id);
        audit.log("system", "DATASCOPE_JOB_SCHEDULE_SET",
                "savedJob=" + id + " cron=" + cron + " enabled=" + enabled + " next=" + next);
        return query(id);
    }

    /** Validate a cron expression and return its next run (for UI preview / validation), or throw. */
    public Map<String, Object> previewSchedule(String cron, String zone) {
        if (cron == null || cron.isBlank()) throw ApiException.bad("Enter a cron expression.");
        ZoneId zoneId = resolveZone(zone);
        Instant next = computeNextRun(cron.trim(), zoneId, Instant.now());
        if (next == null) throw ApiException.bad("Cron '" + cron + "' has no future run time.");
        return Map.of("cron", cron.trim(), "zone", zoneId.getId(), "nextRunAt", next.toString());
    }

    private Instant computeNextRun(String cron, ZoneId zone, Instant from) {
        CronExpression expr;
        try { expr = CronExpression.parse(cron); }
        catch (IllegalArgumentException e) {
            throw ApiException.bad("Invalid cron '" + cron + "': " + e.getMessage()
                    + " (Spring 6-field form: second minute hour day-of-month month day-of-week, e.g. '0 0 2 * * *').");
        }
        ZonedDateTime next = expr.next(ZonedDateTime.ofInstant(from, zone));
        return next == null ? null : next.toInstant();
    }

    private ZoneId resolveZone(String zone) {
        if (zone == null || zone.isBlank()) return ZoneId.systemDefault();
        try { return ZoneId.of(zone.trim()); }
        catch (Exception e) { throw ApiException.bad("Unknown time zone '" + zone + "'."); }
    }

    /**
     * Sweep due schedules every 30s and run them. Each job is isolated so one failure (or an approval gate
     * parking a run) never blocks the others; next_run_at is always advanced so a job can't get stuck re-firing.
     */
    @Scheduled(fixedDelay = 30_000)
    public void runDueSchedules() {
        List<Map<String, Object>> due;
        try {
            due = jdbc.query(
                    "SELECT id, name, owner_username, schedule_cron, schedule_zone FROM datascope_saved_jobs " +
                            "WHERE schedule_enabled = TRUE AND next_run_at IS NOT NULL AND next_run_at <= ?",
                    (rs, n) -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", rs.getString("id"));
                        m.put("name", rs.getString("name"));
                        m.put("owner", rs.getString("owner_username"));
                        m.put("cron", rs.getString("schedule_cron"));
                        m.put("zone", rs.getString("schedule_zone"));
                        return m;
                    }, Timestamp.from(Instant.now()));
        } catch (Exception e) {
            return;   // table missing / DB blip — try again next tick
        }
        for (Map<String, Object> row : due) {
            String id = String.valueOf(row.get("id"));
            String cron = (String) row.get("cron");
            ZoneId zone = resolveZone((String) row.get("zone"));
            // Advance next_run_at FIRST so a slow/failing run can't cause a tight re-fire loop.
            Instant next = null;
            try { next = computeNextRun(cron, zone, Instant.now()); } catch (Exception ignore) { /* bad cron → disable below */ }
            jdbc.update("UPDATE datascope_saved_jobs SET next_run_at = ?, last_scheduled_run_at = ?, " +
                            "schedule_enabled = ?, updated_at = ? WHERE id = ?",
                    next == null ? null : Timestamp.from(next), Timestamp.from(Instant.now()),
                    next != null, Timestamp.from(Instant.now()), id);
            try {
                Map<String, Object> saved = new HashMap<>();
                saved.put("name", row.get("name"));
                runInternal(id, saved, "schedule");
            } catch (Exception e) {
                audit.log("system", "DATASCOPE_JOB_SCHEDULE_FAILED", "savedJob=" + id + " error=" + e.getMessage());
            }
        }
    }

    // ─────────────────────────── query / mapping helpers ───────────────────────────

    private Map<String, Object> query(String id) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM datascope_saved_jobs WHERE id = ?",
                (rs, n) -> mapRow(rs, true), id);
        if (rows.isEmpty()) throw ApiException.notFound("Saved DataScope job " + id + " not found");
        Map<String, Object> row = rows.get(0);
        ensureCanSee((Long) row.get("ownerUserId"), (String) row.get("ownerUsername"));
        attachLastRunStatus(Collections.singletonList(row));
        return row;
    }

    private JsonNode readSpec(String id) {
        List<String> rows = jdbc.query("SELECT spec_json FROM datascope_saved_jobs WHERE id = ?",
                (rs, n) -> rs.getString(1), id);
        if (rows.isEmpty()) throw ApiException.notFound("Saved DataScope job " + id + " not found");
        try { return json.readTree(rows.get(0)); }
        catch (Exception e) { throw ApiException.bad("Saved job spec is corrupt: " + e.getMessage()); }
    }

    private Map<String, Object> mapRow(ResultSet rs, boolean includeSpec) throws SQLException {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("id", rs.getString("id"));
        Long ownerUserId = rs.getObject("owner_user_id") == null ? null : rs.getLong("owner_user_id");
        out.put("ownerUserId", ownerUserId);
        out.put("ownerUsername", rs.getString("owner_username"));
        out.put("name", rs.getString("name"));
        putIfNotBlank(out, "description", rs.getString("description"));
        Object lastRun = rs.getObject("last_run_job_id");
        if (lastRun != null) out.put("lastRunJobId", rs.getLong("last_run_job_id"));
        putIfNotBlank(out, "scheduleCron", rs.getString("schedule_cron"));
        putIfNotBlank(out, "scheduleZone", rs.getString("schedule_zone"));
        out.put("scheduleEnabled", rs.getBoolean("schedule_enabled"));
        putIfNotBlank(out, "nextRunAt", instantString(rs.getTimestamp("next_run_at")));
        putIfNotBlank(out, "lastScheduledRunAt", instantString(rs.getTimestamp("last_scheduled_run_at")));
        out.put("createdAt", instantString(rs.getTimestamp("created_at")));
        out.put("updatedAt", instantString(rs.getTimestamp("updated_at")));
        if (includeSpec) {
            try { out.put("spec", json.readTree(rs.getString("spec_json"))); } catch (Exception ignore) { /* leave out */ }
        }
        return out;
    }

    private void validateSpec(JsonNode spec) {
        if (spec == null || spec.isNull() || spec.isMissingNode())
            throw ApiException.bad("A provisioning spec is required to save a DataScope job.");
        String jobType = text(spec, "jobType", null);
        if (jobType == null || !Set.of("SUBSET_MASK", "MASK_COPY").contains(jobType))
            throw ApiException.bad("Saved DataScope jobs must have jobType SUBSET_MASK or MASK_COPY.");
        if (longOrNull(spec, "sourceId") == null) throw ApiException.bad("Saved DataScope job is missing a source.");
        if (longOrNull(spec, "datasetId") == null) throw ApiException.bad("Saved DataScope job is missing a DataScope blueprint id.");
    }

    private void ensureCanSee(Long ownerUserId, String ownerUsername) {
        AccessPrincipal p = requirePrincipal();
        if (p.hasPermission("admin.all")) return;
        if (ownerUserId != null && Objects.equals(ownerUserId, p.userId())) return;
        if (ownerUsername != null && ownerUsername.equalsIgnoreCase(p.username())) return;
        throw new ApiException(HttpStatus.FORBIDDEN, "This DataScope job belongs to another user");
    }

    private AccessPrincipal requirePrincipal() {
        return AccessContext.current().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Login required"));
    }

    private String cleanName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw ApiException.bad("A saved job name is required.");
        if (n.length() > 200) throw ApiException.bad("Saved job name is too long (max 200).");
        return n;
    }

    private static String text(JsonNode n, String field, String def) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? def : v.asText();
    }
    private static Long longOrNull(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isNumber()) return v.asLong();
        try { return v.asText().isBlank() ? null : Long.parseLong(v.asText().trim()); } catch (NumberFormatException e) { return null; }
    }
    private static String blankNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static void putIfNotBlank(Map<String, Object> m, String k, String v) { if (v != null && !v.isBlank()) m.put(k, v); }
    private static String instantString(Timestamp ts) { return ts == null ? null : ts.toInstant().toString(); }
}
