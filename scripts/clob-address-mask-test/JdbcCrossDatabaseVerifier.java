import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class JdbcCrossDatabaseVerifier {
    private static final Pattern MASKED_US_ADDRESS = Pattern.compile(
            "^.+, Apt \\d{3}, .+, [A-Z]{2} \\d{5}, USA$");

    private JdbcCrossDatabaseVerifier() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 6 && args.length != 7) {
            throw new IllegalArgumentException(
                    "Expected: oracleUrl oracleUser oraclePassword postgresUrl postgresUser postgresPassword [COPY|PARITY]");
        }
        boolean parityMode = args.length == 7 && "PARITY".equalsIgnoreCase(args[6]);
        Class.forName("oracle.jdbc.OracleDriver");
        Class.forName("org.postgresql.Driver");

        try (Connection oracle = DriverManager.getConnection(args[0], args[1], args[2]);
             Connection postgres = DriverManager.getConnection(args[3], args[4], args[5]);
             Statement sourceStatement = oracle.createStatement();
             Statement targetStatement = postgres.createStatement();
             ResultSet source = sourceStatement.executeQuery(
                     "SELECT ADDRESS_ID, CUSTOMER_REF, FULL_ADDRESS, CREATED_AT " +
                             "FROM BE_CARDS.CLOB_ADDRESS_SOURCE ORDER BY ADDRESS_ID");
             ResultSet target = targetStatement.executeQuery(
                     "SELECT \"ADDRESS_ID\", \"CUSTOMER_REF\", \"FULL_ADDRESS\", \"CREATED_AT\" " +
                             "FROM clob_mask_test.\"CLOB_ADDRESS_TARGET\" ORDER BY \"ADDRESS_ID\"")) {

            int sourceRows = 0;
            int targetRows = 0;
            int matchingIds = 0;
            int unchangedAddresses = 0;
            int changedAddresses = 0;
            int nullMaskedAddresses = 0;
            int validSourceMaskedFormat = 0;
            int validMaskedFormat = 0;
            int unchangedCustomerRefs = 0;
            int unchangedTimestamps = 0;
            Set<String> distinctSourceAddresses = new HashSet<>();
            Set<String> distinctTargetAddresses = new HashSet<>();

            while (true) {
                boolean hasSource = source.next();
                boolean hasTarget = target.next();
                if (!hasSource && !hasTarget) break;
                if (hasSource) sourceRows++;
                if (hasTarget) targetRows++;
                if (!hasSource || !hasTarget) continue;

                long sourceId = source.getLong(1);
                long targetId = target.getLong(1);
                if (sourceId == targetId) matchingIds++;

                String sourceAddress = clobText(source.getClob(3));
                String targetAddress = target.getString(3);
                if (sourceAddress != null) {
                    distinctSourceAddresses.add(sourceAddress);
                    if (MASKED_US_ADDRESS.matcher(sourceAddress).matches()) validSourceMaskedFormat++;
                }
                if (targetAddress == null) {
                    nullMaskedAddresses++;
                } else {
                    distinctTargetAddresses.add(targetAddress);
                    if (MASKED_US_ADDRESS.matcher(targetAddress).matches()) validMaskedFormat++;
                }
                if (Objects.equals(sourceAddress, targetAddress)) unchangedAddresses++;
                else changedAddresses++;

                if (Objects.equals(source.getString(2), target.getString(2))) unchangedCustomerRefs++;
                if (sameTimestamp(source.getTimestamp(4), target.getTimestamp(4))) unchangedTimestamps++;
            }

            System.out.println("sourceRows=" + sourceRows);
            System.out.println("targetRows=" + targetRows);
            System.out.println("matchingIds=" + matchingIds);
            System.out.println("changedAddresses=" + changedAddresses);
            System.out.println("unchangedAddresses=" + unchangedAddresses);
            System.out.println("nullMaskedAddresses=" + nullMaskedAddresses);
            System.out.println("validSourceMaskedFormat=" + validSourceMaskedFormat);
            System.out.println("validMaskedFormat=" + validMaskedFormat);
            System.out.println("distinctSourceAddresses=" + distinctSourceAddresses.size());
            System.out.println("distinctTargetAddresses=" + distinctTargetAddresses.size());
            System.out.println("unchangedCustomerRefs=" + unchangedCustomerRefs);
            System.out.println("unchangedTimestamps=" + unchangedTimestamps);

            boolean common = sourceRows == 5_000 && targetRows == 5_000 && matchingIds == 5_000
                    && nullMaskedAddresses == 0 && validMaskedFormat == 5_000
                    && unchangedCustomerRefs == 5_000 && unchangedTimestamps == 5_000;
            boolean valid = parityMode
                    ? common && changedAddresses == 0 && unchangedAddresses == 5_000
                        && validSourceMaskedFormat == 5_000
                    : common && changedAddresses == 5_000 && unchangedAddresses == 0;
            if (!valid) throw new IllegalStateException("Cross-database masking validation failed");
        }
    }

    private static String clobText(Clob clob) throws Exception {
        if (clob == null) return null;
        return clob.getSubString(1, Math.toIntExact(clob.length()));
    }

    private static boolean sameTimestamp(Timestamp left, Timestamp right) {
        if (left == null || right == null) return left == right;
        return left.toLocalDateTime().equals(right.toLocalDateTime());
    }
}
