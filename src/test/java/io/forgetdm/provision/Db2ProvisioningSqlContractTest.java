package io.forgetdm.provision;

import io.forgetdm.datasource.SqlDialect;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract simulation for DB2-only SQL branches. A live DB2 engine is required
 * for certification; these tests protect the generated statement shapes in CI.
 */
class Db2ProvisioningSqlContractTest {

    @Test
    void db2InPlaceMaskingUsesAliasedMergeWithUnqualifiedTargetAssignments() throws Exception {
        Method method = ProvisioningService.class.getDeclaredMethod("inPlaceUpdateSql",
                SqlDialect.class, String.class, String.class, String.class, List.class, List.class);
        method.setAccessible(true);

        String sql = (String) method.invoke(null, SqlDialect.DB2, "BANK", "CUSTOMERS", "FDM_STAGE",
                List.of("CUSTOMER_ID"), List.of("EMAIL", "PHONE"));

        assertTrue(sql.startsWith("MERGE INTO \"BANK\".\"CUSTOMERS\" AS t USING \"BANK\".\"FDM_STAGE\" AS s"));
        assertTrue(sql.contains("ON (t.\"CUSTOMER_ID\"=s.\"CUSTOMER_ID\")"));
        assertTrue(sql.contains("UPDATE SET \"EMAIL\"=s.\"EMAIL\",\"PHONE\"=s.\"PHONE\""));
        assertFalse(sql.contains("t.\"EMAIL\"=s.\"EMAIL\""));
    }

    @Test
    void db2InsertUpdateUsesValuesMergeWithAllColumnsBoundOnce() throws Exception {
        Class<?> writer = Class.forName("io.forgetdm.provision.ProvisioningService$TableLoadWriter");
        Method method = writer.getDeclaredMethod("upsertSql", SqlDialect.class, String.class, String.class,
                List.class, List.class, List.class);
        method.setAccessible(true);

        String sql = (String) method.invoke(null, SqlDialect.DB2, "BANK", "CUSTOMERS",
                List.of("CUSTOMER_ID", "EMAIL", "STATUS"), List.of("CUSTOMER_ID"), List.of("EMAIL", "STATUS"));

        assertTrue(sql.startsWith("MERGE INTO \"BANK\".\"CUSTOMERS\" AS tgt USING (VALUES (?,?,?)) AS src"));
        assertTrue(sql.contains("AS src (\"CUSTOMER_ID\",\"EMAIL\",\"STATUS\")"));
        assertTrue(sql.contains("ON (tgt.\"CUSTOMER_ID\"=src.\"CUSTOMER_ID\")"));
        assertTrue(sql.contains("WHEN MATCHED THEN UPDATE SET tgt.\"EMAIL\"=src.\"EMAIL\",tgt.\"STATUS\"=src.\"STATUS\""));
        assertTrue(sql.contains("WHEN NOT MATCHED THEN INSERT (\"CUSTOMER_ID\",\"EMAIL\",\"STATUS\") VALUES (src.\"CUSTOMER_ID\",src.\"EMAIL\",src.\"STATUS\")"));
    }
}
