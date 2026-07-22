package io.forgetdm.provision;

import io.forgetdm.common.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvisioningTargetFitTest {

    @Test
    void preservesNullAndTruncatesTextByUnicodeCodePoint() {
        assertNull(ProvisioningService.fitValueForTarget(
                null, "public", "customers", "display_name", Types.VARCHAR, 4, 0));

        String fitted = (String) ProvisioningService.fitValueForTarget(
                "ab\uD83D\uDE00\u00E7de", "public", "customers", "display_name", Types.VARCHAR, 4, 0);

        assertEquals("ab\uD83D\uDE00\u00E7", fitted);
        assertEquals(4, fitted.codePointCount(0, fitted.length()));
    }

    @Test
    void leavesUnboundedTextIntact() {
        String value = "note \uD83D\uDE00 with more than four code points";

        assertEquals(value, ProvisioningService.fitValueForTarget(
                value, "public", "claims", "note", Types.CLOB, 4, 0));
    }

    @Test
    void convertsValidMaskedScalarsBeforeJdbcBinding() {
        assertEquals(42, ProvisioningService.fitValueForTarget(
                "42", "public", "accounts", "status_code", Types.INTEGER, 10, 0));
        assertEquals(Boolean.TRUE, ProvisioningService.fitValueForTarget(
                "1", "public", "accounts", "active", Types.BOOLEAN, 1, 0));
        assertEquals(java.sql.Date.valueOf("2026-07-22"), ProvisioningService.fitValueForTarget(
                "2026-07-22", "public", "accounts", "opened_on", Types.DATE, 10, 0));
    }

    @Test
    void rejectsInvalidConversionWithTargetColumnContext() {
        ApiException error = assertThrows(ApiException.class, () -> ProvisioningService.fitValueForTarget(
                "DECLINED", "public", "accounts", "status_code", Types.BIGINT, 19, 0));

        assertTrue(error.getMessage().contains("public.accounts.status_code"));
        assertTrue(error.getMessage().contains("BIGINT"));
    }

    @Test
    void rejectsInvalidBooleanInsteadOfSilentlyTurningItFalse() {
        ApiException error = assertThrows(ApiException.class, () -> ProvisioningService.fitValueForTarget(
                "not-a-boolean", "public", "accounts", "active", Types.BOOLEAN, 1, 0));

        assertTrue(error.getMessage().contains("public.accounts.active"));
    }

    @Test
    void enforcesDecimalPrecisionAndScaleWithoutRoundingMaskedValues() {
        assertEquals(new BigDecimal("924.41"), ProvisioningService.fitValueForTarget(
                "924.41", "public", "payments", "amount", Types.DECIMAL, 5, 2));
        assertEquals(new BigDecimal("12.345"), ProvisioningService.fitValueForTarget(
                "12.345", "public", "payments", "unconstrained_amount", Types.DECIMAL, 0, -1));

        ApiException precision = assertThrows(ApiException.class, () -> ProvisioningService.fitValueForTarget(
                "1000.00", "public", "payments", "amount", Types.DECIMAL, 5, 2));
        ApiException scale = assertThrows(ApiException.class, () -> ProvisioningService.fitValueForTarget(
                "12.345", "public", "payments", "amount", Types.DECIMAL, 5, 2));

        assertTrue(precision.getMessage().contains("precision 5"));
        assertTrue(scale.getMessage().contains("more than 2 decimal place"));
    }
}
