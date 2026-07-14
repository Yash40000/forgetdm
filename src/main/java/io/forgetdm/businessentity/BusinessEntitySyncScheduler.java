package io.forgetdm.businessentity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import io.forgetdm.platform.ClusterLeaseService;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class BusinessEntitySyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(BusinessEntitySyncScheduler.class);

    private final JdbcTemplate jdbc;
    private final BusinessEntitySyncService sync;
    private final boolean enabled;
    private final ClusterLeaseService leases;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public BusinessEntitySyncScheduler(JdbcTemplate jdbc, BusinessEntitySyncService sync, ClusterLeaseService leases,
                                       @Value("${forgetdm.business-entity.sync.auto-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.sync = sync;
        this.leases = leases;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${forgetdm.business-entity.sync.poll-ms:60000}")
    public void runDuePolicies() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            if (!leases.acquire("business-entity-freshness-scheduler", Duration.ofSeconds(55))) return;
            List<Map<String, Object>> policies = jdbc.queryForList("""
                    SELECT p.id, p.max_lag_seconds AS "maxLagSeconds", p.schedule_cron AS "scheduleCron",
                           MAX(r.started_at) AS "lastRunAt"
                      FROM be_sync_policies p
                 LEFT JOIN be_sync_runs r ON r.policy_id = p.id AND r.run_type = 'FRESHNESS_CHECK'
                     WHERE p.status = 'ACTIVE' AND p.auto_refresh_enabled = TRUE
                  GROUP BY p.id, p.max_lag_seconds, p.schedule_cron
                  ORDER BY p.id
                     LIMIT 25
                    """);
            Instant now = Instant.now();
            for (Map<String, Object> policy : policies) {
                Long policyId = num(policy.get("id"));
                if (policyId == null || !isDue(policy, now)) continue;
                try {
                    sync.checkFreshness(policyId);
                } catch (Exception e) {
                    log.warn("Business Entity freshness policy {} failed during auto check: {}", policyId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Business Entity sync scheduler skipped this pass: {}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private boolean isDue(Map<String, Object> policy, Instant now) {
        Instant lastRun = toInstant(policy.get("lastRunAt"));
        String cron = blank((String) policy.get("scheduleCron"));
        if (cron != null) return cronDue(cron, lastRun, now);
        if (lastRun == null) return true;
        long maxLag = Math.max(60, num(policy.get("maxLagSeconds")) == null ? 900 : num(policy.get("maxLagSeconds")));
        long interval = Math.max(30, Math.min(maxLag, maxLag / 2));
        return Duration.between(lastRun, now).getSeconds() >= interval;
    }

    private boolean cronDue(String cron, Instant lastRun, Instant now) {
        try {
            CronExpression expression = CronExpression.parse(cron);
            LocalDateTime base = lastRun == null
                    ? LocalDateTime.ofInstant(now.minus(Duration.ofDays(1)), ZoneId.systemDefault())
                    : LocalDateTime.ofInstant(lastRun, ZoneId.systemDefault());
            LocalDateTime next = expression.next(base);
            return next != null && !next.atZone(ZoneId.systemDefault()).toInstant().isAfter(now);
        } catch (Exception e) {
            log.warn("Invalid Business Entity sync cron '{}': {}", cron, e.getMessage());
            return false;
        }
    }

    private static Long num(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(value).trim()); } catch (Exception e) { return null; }
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof Timestamp t) return t.toInstant();
        if (value instanceof java.util.Date d) return d.toInstant();
        if (value instanceof LocalDateTime l) return l.atZone(ZoneId.systemDefault()).toInstant();
        try { return Instant.parse(String.valueOf(value)); } catch (Exception ignored) {}
        return null;
    }

    private static String blank(String value) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.isEmpty() ? null : clean.toUpperCase(Locale.ROOT).equals("NULL") ? null : clean;
    }
}
