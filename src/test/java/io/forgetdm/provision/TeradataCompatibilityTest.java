package io.forgetdm.provision;

import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.core.synth.Generators;
import io.forgetdm.core.util.Luhn;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.SqlDialect;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeradataCompatibilityTest {

    @Test
    void detectsTeradataAndUsesConservativeJdbcBatchDialect() {
        DataSourceEntity source = new DataSourceEntity();
        source.setKind("TERADATA");
        source.setJdbcUrl("jdbc:teradata://vantage.example/DATABASE=tdm_test,CHARSET=UTF8");

        assertEquals(SqlDialect.TERADATA, SqlDialect.of(source));
        assertEquals(SqlDialect.TERADATA, SqlDialect.fromUrl(source.getJdbcUrl()));
        assertFalse(SqlDialect.TERADATA.supportsMultiRowInsert());
        assertEquals("DELETE FROM \"TDM_TEST\".\"CUSTOMERS\" ALL",
                SqlDialect.TERADATA.truncateSql("\"TDM_TEST\".\"CUSTOMERS\""));
    }

    @Test
    void obfuscatesRepresentativeTeradataCustomerValuesDeterministically() {
        MaskingEngine engine = new MaskingEngine("teradata-compatibility-secret");
        MaskContext row = new MaskContext(1);

        String name = engine.mask(MaskFunction.FULL_NAME, "customer.name", "Jennifer Smith", null, null, row);
        String ssn = engine.mask(MaskFunction.SSN, "customer.ssn", "123-45-6789", "VALID_RANDOM_AREA", "DASHED", row);
        String email = engine.mask(MaskFunction.EMAIL, "customer.email", "jennifer.smith@bank.com", "HASH_LOCAL", "SAFE_DOMAIN", row);
        String card = engine.mask(MaskFunction.CREDIT_CARD, "customer.pan", "4111111111111111", "VALID_RANDOM_BIN", "DIGITS_ONLY", row);

        assertNotEquals("Jennifer Smith", name);
        assertTrue(ssn.matches("\\d{3}-\\d{2}-\\d{4}"));
        assertNotEquals("123-45-6789", ssn);
        assertTrue(email.endsWith(".test"));
        assertTrue(Luhn.isValid(card));
        assertEquals(email, engine.mask(MaskFunction.EMAIL, "customer.email", "jennifer.smith@bank.com", "HASH_LOCAL", "SAFE_DOMAIN", row));
    }

    @Test
    void generatesLargeDeterministicTeradataCompatibleRowsWithoutRepeatPressure() {
        Random random = new Random(40000);
        Set<String> names = new HashSet<>();
        for (long row = 1; row <= 25_000; row++) {
            names.add(Generators.of("FULL_NAME_BY_LOCALE", "US", null).apply(row, random));
            String amount = Generators.of("DECIMAL_RANGE", "0.00", "999999.99").apply(row, random);
            assertTrue(amount.matches("\\d+(\\.\\d+)?"));
        }
        assertTrue(names.size() > 24_000, "distinct names=" + names.size());

        String first = Generators.of("ACCOUNT_NUMBER", null, null).apply(42L, new Random(99));
        String replay = Generators.of("ACCOUNT_NUMBER", null, null).apply(42L, new Random(99));
        assertEquals(first, replay);
    }
}
