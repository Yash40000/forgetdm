package io.forgetdm.platform;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterLeaseServiceTest {
    @Test
    void onlyOneReplicaWinsUntilLeaseExpires() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:lease_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE forge_scheduler_leases(lease_name VARCHAR(160) PRIMARY KEY,owner_id VARCHAR(160),lease_until TIMESTAMP,updated_at TIMESTAMP)");
        ClusterLeaseService first = new ClusterLeaseService(jdbc);
        ClusterLeaseService second = new ClusterLeaseService(jdbc);
        assertTrue(first.acquire("scheduler", Duration.ofMillis(25)));
        assertFalse(second.acquire("scheduler", Duration.ofSeconds(1)));
        Thread.sleep(40);
        assertTrue(second.acquire("scheduler", Duration.ofSeconds(1)));
    }
}
