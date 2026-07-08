package io.forgetdm.provision;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticPartitioningTest {

    @Test
    void rangesCoverEveryRowExactlyOnce() {
        List<SyntheticPartitioning.RowRange> ranges = SyntheticPartitioning.ranges(1_000_003, 8, null);
        assertFalse(ranges.isEmpty());
        long expected = 1;
        long covered = 0;
        for (SyntheticPartitioning.RowRange range : ranges) {
            assertEquals(expected, range.startInclusive());
            assertTrue(range.endExclusive() > range.startInclusive());
            expected = range.endExclusive();
            covered += range.size();
        }
        assertEquals(1_000_004, expected);
        assertEquals(1_000_003, covered);
    }

    @Test
    void explicitChunkSizeCreatesStableRanges() {
        List<SyntheticPartitioning.RowRange> ranges = SyntheticPartitioning.ranges(25, 2, 10L);
        assertEquals(List.of(10L, 10L, 5L), ranges.stream().map(SyntheticPartitioning.RowRange::size).toList());
    }

    @Test
    void rowRandomDoesNotDependOnPartitionLayout() {
        List<Long> oneWorker = valuesForRanges(SyntheticPartitioning.ranges(100, 1, null));
        List<Long> eightWorkers = valuesForRanges(SyntheticPartitioning.ranges(100, 8, null));
        assertEquals(oneWorker, eightWorkers);
    }

    @Test
    void partitionMigrationRunsOnPortableMetadataDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:partition_migration;MODE=PostgreSQL", "sa", "")) {
            connection.createStatement().execute("CREATE TABLE synthetic_generation_jobs(id VARCHAR(80) PRIMARY KEY)");
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new FileSystemResource("src/main/resources/db/migration/V29__synthetic_partitions.sql"), StandardCharsets.UTF_8));
            try (var rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM synthetic_job_partitions")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
        }
    }

    private List<Long> valuesForRanges(List<SyntheticPartitioning.RowRange> ranges) {
        List<Long> values = new ArrayList<>();
        for (SyntheticPartitioning.RowRange range : ranges) {
            for (long row = range.startInclusive(); row < range.endExclusive(); row++) {
                values.add(SyntheticPartitioning.rowRandom(42, "accounts", row).nextLong());
            }
        }
        return values;
    }
}
