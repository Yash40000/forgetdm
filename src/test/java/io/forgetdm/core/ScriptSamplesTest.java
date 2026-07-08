package io.forgetdm.core;

import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the SAMPLE masking scripts (mirrored from MASK_SAMPLES in app.js) through the real Lua engine,
 * so a broken example can never ship. If a sample here changes, update the copy in app.js and vice versa.
 */
class ScriptSamplesTest {

    private MaskingEngine engine(String name, String code) {
        MaskingEngine e = new MaskingEngine("unit-test-secret");
        e.setScriptProvider(n -> name.equals(n) ? code : null);
        return e;
    }
    private String run(String code, String value, MaskContext ctx) {
        return engine("s", code).mask(MaskFunction.SCRIPT, "salt", value, "s", null, ctx);
    }
    private static MaskContext ctx(Map<String, String> row) {
        MaskContext c = new MaskContext(1);
        row.forEach(c.row::put);
        return c;
    }

    @Test void keepLast4() {
        String out = run("if value == nil then return nil end\nif #value <= 4 then return value end\nreturn forge.fpe(string.sub(value, 1, #value - 4)) .. string.sub(value, -4)",
                "ACCT-123456789", ctx(Map.of()));
        assertTrue(out.endsWith("6789"), out);
        assertNotEquals("ACCT-123456789", out);
    }

    @Test void maskLettersKeepDigits() {
        String out = run("if value == nil then return nil end\nif not string.match(value, \"%a\") then return value end\nlocal name = string.lower(forge.pick(\"first_names.txt\", value))\nreturn (value:gsub(\"%a+\", name, 1))",
                "yash1234", ctx(Map.of()));
        assertTrue(out.matches("[a-z]+1234"), out);
        assertNotEquals("yash1234", out);
    }

    @Test void byIndicator() {
        String code = "local ind = row[\"type_ind\"]\nif ind == \"P\" then return forge.mask(\"PHONE\", value) end\nif ind == \"E\" then return forge.mask(\"EMAIL\", value) end\nreturn forge.fpe(value)";
        assertNotEquals("2125550147", run(code, "2125550147", ctx(Map.of("type_ind", "P"))));
        assertTrue(run(code, "a@b.com", ctx(Map.of("type_ind", "E"))).contains("@"));
    }

    @Test void consentNullout() {
        String code = "if row[\"consent_flag\"] ~= \"Y\" then return nil end\nreturn value";
        assertNull(run(code, "secret", ctx(Map.of("consent_flag", "N"))));
        assertEquals("keepme", run(code, "keepme", ctx(Map.of("consent_flag", "Y"))));
    }

    @Test void deterministicToken() {
        String code = "if value == nil then return nil end\nreturn string.format(\"TKN%010d\", forge.hash(value, 1000000000))";
        String a = run(code, "abc", ctx(Map.of())), b = run(code, "abc", ctx(Map.of()));
        assertEquals(a, b);
        assertTrue(a.matches("TKN\\d{10}"), a);
    }

    @Test void emailFromName() {
        MaskContext c = new MaskContext(1);
        c.masked.put("first_name", "Kim");
        c.masked.put("last_name", "Das");
        String out = run("local f = forge.masked(\"first_name\") or \"user\"\nlocal l = forge.masked(\"last_name\") or tostring(forge.hash(value, 9999))\nreturn string.lower(f .. \".\" .. l) .. \"@example.test\"", "x@y.com", c);
        assertEquals("kim.das@example.test", out);
    }

    @Test void ibanKeepCountry() {
        String out = run("if value == nil or #value < 4 then return forge.fpe(value) end\nreturn string.sub(value, 1, 2) .. forge.fpe(string.sub(value, 3))", "GB29NWBK60161331926819", ctx(Map.of()));
        assertTrue(out.startsWith("GB"), out);
        assertNotEquals("GB29NWBK60161331926819", out);
    }

    @Test void redactKeepLength() {
        assertEquals("*****", run("if value == nil then return nil end\nreturn string.rep(\"*\", #value)", "abcde", ctx(Map.of())));
    }

    @Test void companyFromList() {
        String code = "if value == nil then return nil end\nreturn forge.pick(\"companies.txt\", value)";
        String a = run(code, "acme corp", ctx(Map.of())), b = run(code, "acme corp", ctx(Map.of()));
        assertEquals(a, b);
        assertNotNull(a);
    }

    @Test void cardWithTag() {
        String out = run("if value == nil then return nil end\nreturn forge.mask(\"CREDIT_CARD\", value) .. \" #\" .. string.format(\"%03d\", forge.hash(value, 1000))",
                "4111111111111111", ctx(Map.of()));
        assertTrue(out.matches(".+ #\\d{3}"), out);
    }
}
