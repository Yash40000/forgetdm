package io.forgetdm.provision;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Deterministic row-range planning shared by local and distributed synthetic workers. */
final class SyntheticPartitioning {
    static final int MAX_WORKERS = 32;
    static final int MAX_PARTITIONS_PER_TABLE = 4096;

    record RowRange(int number, long startInclusive, long endExclusive) {
        long size() { return endExclusive - startInclusive; }
    }

    private SyntheticPartitioning() {}

    static String mode(String value) {
        String mode = value == null ? "SINGLE" : value.trim().toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "LOCAL_PARTITIONED", "DISTRIBUTED" -> mode;
            default -> "SINGLE";
        };
    }

    static int workers(Integer requested) {
        int fallback = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        return Math.max(1, Math.min(MAX_WORKERS, requested == null ? fallback : requested));
    }

    static List<RowRange> ranges(long rows, int workers, Long requestedPartitionSize) {
        if (rows <= 0) return List.of();
        long size = requestedPartitionSize == null || requestedPartitionSize <= 0
                ? ceilDiv(rows, Math.max(1, workers))
                : requestedPartitionSize;
        size = Math.max(1, size);
        long count = ceilDiv(rows, size);
        if (count > MAX_PARTITIONS_PER_TABLE) {
            throw new IllegalArgumentException("Partition size creates " + count + " partitions for one table; maximum is "
                    + MAX_PARTITIONS_PER_TABLE + ". Increase the partition size.");
        }
        List<RowRange> out = new ArrayList<>((int) count);
        long start = 1;
        for (int number = 1; start <= rows; number++) {
            long end = Math.min(rows + 1, start + size);
            out.add(new RowRange(number, start, end));
            start = end;
        }
        return out;
    }

    static Random rowRandom(long seed, String table, long globalRow) {
        long mixed = mix64(seed ^ hash(table == null ? "" : table.toLowerCase(Locale.ROOT)) ^ mix64(globalRow));
        return new Random(mixed);
    }

    static long partitionSeed(long seed, String table, int partitionNumber) {
        return mix64(seed ^ hash(table == null ? "" : table) ^ mix64(partitionNumber));
    }

    private static long ceilDiv(long value, long divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    private static long hash(String value) {
        long h = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xffL;
            h *= 0x100000001b3L;
        }
        return mix64(h);
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
