package io.forgetdm.virtualization;

import java.util.List;

/**
 * The metadata describing one point-in-time snapshot in a TimeFlow.
 * The manifest references row-batch chunks by content hash; it never embeds data.
 * The manifest itself is also stored in the pool, so a snapshot row in the config DB
 * is just (timeflow, manifestHash, stats) — "metadata plus changed blocks".
 */
public record SnapshotManifest(
        int formatVersion,
        String schemaName,
        List<TableManifest> tables,
        List<FkInfo> foreignKeys) {

    public record ColumnInfo(String name, int jdbcType, String typeName, int size, int scale, boolean nullable) {}

    public record TableManifest(
            String name,
            List<ColumnInfo> columns,
            List<String> primaryKey,
            long rowCount,
            List<String> chunks) {}

    public record FkInfo(String childTable, List<String> childColumns,
                         String parentTable, List<String> parentColumns) {}
}
