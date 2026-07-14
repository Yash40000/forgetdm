package io.forgetdm.businessentity;

import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.DataSourceEntity;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/** Connector-aware identifier quoting shared by cross-application Business Entity services. */
final class BusinessEntitySql {
    private BusinessEntitySql() {}

    static String name(DataSourceEntity source, String schema, String table) {
        if (schema == null || schema.isBlank()) return identifier(source, table);
        return identifier(source, schema) + "." + identifier(source, table);
    }

    static String identifier(DataSourceEntity source, String identifier) {
        return quote(identifier, source == null ? "" : source.getKind());
    }

    static String name(Connection connection, String schema, String table) throws SQLException {
        if (schema == null || schema.isBlank()) return identifier(connection, table);
        return identifier(connection, schema) + "." + identifier(connection, table);
    }

    static String identifier(Connection connection, String identifier) throws SQLException {
        return quote(identifier, connection == null ? "" : connection.getMetaData().getDatabaseProductName());
    }

    private static String quote(String identifier, String dialect) {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_$#]*")) {
            throw ApiException.bad("Illegal identifier: " + identifier);
        }
        String kind = dialect == null ? "" : dialect.toLowerCase(Locale.ROOT);
        String normalized = kind.contains("oracle") || kind.contains("db2")
                ? identifier.toUpperCase(Locale.ROOT)
                : identifier;
        if (kind.contains("mysql") || kind.contains("mariadb")) return "`" + normalized.replace("`", "``") + "`";
        if (kind.contains("sqlserver") || kind.contains("microsoft sql")) return "[" + normalized.replace("]", "]]" ) + "]";
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }
}
