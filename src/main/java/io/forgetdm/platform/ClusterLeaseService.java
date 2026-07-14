package io.forgetdm.platform;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Database-backed lease used to make scheduled work single-winner across HA replicas. */
@Service
public class ClusterLeaseService {
    private final JdbcTemplate jdbc;
    private final String ownerId = instanceId();

    public ClusterLeaseService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean acquire(String name, Duration ttl) {
        Instant now = Instant.now();
        Instant until = now.plus(ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(30) : ttl);
        int changed = jdbc.update("UPDATE forge_scheduler_leases SET owner_id = ?, lease_until = ?, updated_at = ? " +
                        "WHERE lease_name = ? AND (lease_until < ? OR owner_id = ?)",
                ownerId, ts(until), ts(now), name, ts(now), ownerId);
        if (changed == 1) return true;
        try {
            jdbc.update("INSERT INTO forge_scheduler_leases(lease_name, owner_id, lease_until, updated_at) VALUES (?, ?, ?, ?)",
                    name, ownerId, ts(until), ts(now));
            return true;
        } catch (DuplicateKeyException ignored) {
            return false;
        }
    }

    public String ownerId() { return ownerId; }

    private static Timestamp ts(Instant value) { return Timestamp.from(value); }

    private static String instanceId() {
        String configured = System.getenv("FORGETDM_INSTANCE_ID");
        if (configured != null && !configured.isBlank()) return configured.trim();
        try { return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID().toString().substring(0, 8); }
        catch (Exception e) { return "forgetdm-" + UUID.randomUUID().toString().substring(0, 8); }
    }
}
