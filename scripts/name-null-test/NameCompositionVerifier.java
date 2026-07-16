import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** Repeatable verification for the null-source, masked-name composition scenario. */
public final class NameCompositionVerifier {
    private static final String SOURCE = "BE_CARDS.FTDM_NAME_NULL_TEST";
    private static final String TARGET = "BE_CARDS.FTDM_NAME_MASKED_TARGET";

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Usage: <jdbc-url> <user> <password> <--inspect|--validate|--source-state|--unique-index-state>");
        }
        try (Connection connection = DriverManager.getConnection(args[0], args[1], args[2])) {
            if ("--inspect".equals(args[3])) inspect(connection);
            else if ("--validate".equals(args[3])) validate(connection);
            else if ("--source-state".equals(args[3])) sourceState(connection);
            else if ("--unique-index-state".equals(args[3])) uniqueIndexState(connection);
            else throw new IllegalArgumentException("Unknown mode: " + args[3]);
        }
    }

    private static void uniqueIndexState(Connection connection) throws Exception {
        long sourceRows = scalar(connection, "SELECT COUNT(*) FROM " + SOURCE);
        long targetRows = scalar(connection, "SELECT COUNT(*) FROM " + TARGET);
        long validUniqueIndexes = scalar(connection,
                "SELECT COUNT(DISTINCT i.index_name) FROM user_indexes i "
                + "JOIN user_ind_columns c ON c.index_name=i.index_name "
                + "WHERE i.table_name='FTDM_NAME_NULL_TEST' AND c.column_name='FULL_NAME' "
                + "AND i.uniqueness='UNIQUE' AND i.status='VALID'");
        System.out.println("sourceRows=" + sourceRows + " targetRows=" + targetRows
                + " validUniqueFullNameIndexes=" + validUniqueIndexes);
        if (sourceRows != 0 || targetRows != 5000 || validUniqueIndexes < 1) {
            throw new IllegalStateException("Unique FULL_NAME preparation validation failed");
        }
    }

    private static void sourceState(Connection connection) throws Exception {
        long primaryKeys = scalar(connection,
                "SELECT COUNT(*) FROM user_constraints WHERE table_name='FTDM_NAME_NULL_TEST' AND constraint_type='P'");
        long[] state = row(connection, "SELECT COUNT(*), "
                + "SUM(CASE WHEN FIRST_NAME IS NULL THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN LAST_NAME IS NULL THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN FULL_NAME IS NULL THEN 1 ELSE 0 END), "
                + "SUM(ORA_HASH(NVL(FIRST_NAME,'<NULL>') || '|' || NVL(LAST_NAME,'<NULL>'))) "
                + "FROM " + SOURCE);
        System.out.println("sourceRows=" + state[0] + " databasePrimaryKeys=" + primaryKeys
                + " nullFirst=" + state[1] + " nullLast=" + state[2]
                + " nullFull=" + state[3] + " nameHash=" + state[4]);
    }

    private static void inspect(Connection connection) throws Exception {
        long exists = scalar(connection,
                "SELECT COUNT(*) FROM user_tables WHERE table_name='FTDM_NAME_MASKED_TARGET'");
        long rows = exists == 0 ? 0 : scalar(connection, "SELECT COUNT(*) FROM " + TARGET);
        long columns = exists == 0 ? 0 : scalar(connection,
                "SELECT COUNT(*) FROM user_tab_columns WHERE table_name='FTDM_NAME_MASKED_TARGET'");
        System.out.println("targetExists=" + (exists == 1) + " targetRows=" + rows + " targetColumns=" + columns);
    }

    private static void validate(Connection connection) throws Exception {
        String sql = "SELECT COUNT(*), "
                + "SUM(CASE WHEN FIRST_NAME IS NULL THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN LAST_NAME IS NULL THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN FULL_NAME IS NULL THEN 1 ELSE 0 END), "
                + "SUM(CASE WHEN FULL_NAME = LAST_NAME || ', ' || FIRST_NAME THEN 1 ELSE 0 END), "
                + "SUM(ORA_HASH(NVL(FIRST_NAME,'<NULL>') || '|' || NVL(LAST_NAME,'<NULL>'))) "
                + "FROM ";
        long[] source = row(connection, sql + SOURCE);
        long[] target = row(connection, sql + TARGET);
        System.out.println("sourceRows=" + source[0] + " sourceNullFirst=" + source[1]
                + " sourceNullLast=" + source[2] + " sourceNullFull=" + source[3]
                + " sourceComposed=" + source[4] + " sourceNameHash=" + source[5]);
        System.out.println("targetRows=" + target[0] + " targetNullFirst=" + target[1]
                + " targetNullLast=" + target[2] + " targetNullFull=" + target[3]
                + " targetComposed=" + target[4] + " targetNameHash=" + target[5]);
        boolean valid = source[0] == 5000 && source[3] == 5000 && target[0] == 5000
                && target[1] == 0 && target[2] == 0 && target[3] == 0
                && target[4] == target[0] && source[5] != target[5];
        System.out.println("validation=" + (valid ? "PASS" : "FAIL"));
        if (!valid) throw new IllegalStateException("Name composition validation failed");
    }

    private static long scalar(Connection connection, String sql) throws Exception {
        return row(connection, sql)[0];
    }

    private static long[] row(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            long[] values = new long[result.getMetaData().getColumnCount()];
            for (int i = 0; i < values.length; i++) values[i] = result.getLong(i + 1);
            return values;
        }
    }
}
