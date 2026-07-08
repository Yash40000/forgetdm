package io.forgetdm.core;

import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** User-defined Lua masking scripts (Optim-style exits), sandboxed with deterministic forge.* helpers. */
class ScriptMaskTest {

    private MaskingEngine engineWith(Map<String, String> scripts) {
        MaskingEngine e = new MaskingEngine("unit-test-secret");
        e.setScriptProvider(scripts::get);
        return e;
    }

    private static MaskContext ctx() { return new MaskContext(1); }

    @Test void runsAScriptAndIsDeterministic() {
        MaskingEngine e = engineWith(Map.of("keep4",
                "return forge.fpe(string.sub(value, 1, #value - 4)) .. string.sub(value, -4)"));
        String a = e.mask(MaskFunction.SCRIPT, "s", "ABCD1234", "keep4", null, ctx());
        String b = e.mask(MaskFunction.SCRIPT, "s", "ABCD1234", "keep4", null, ctx());
        assertEquals(a, b);                       // deterministic
        assertTrue(a.endsWith("1234"), a);        // custom logic honored
        assertNotEquals("ABCD1234", a);
    }

    @Test void forgeMaskDelegatesToBuiltinsWithCanonicalSalts() {
        MaskingEngine e = engineWith(Map.of("name-tag",
                "return forge.mask(\"FIRST_NAME\", value) .. \"-\" .. forge.hash(value, 1000)"));
        String scripted = e.mask(MaskFunction.SCRIPT, "s", "yash", "name-tag", null, ctx());
        String plain = e.mask(MaskFunction.FIRST_NAME, "name.first", "yash", null, null, ctx());
        assertTrue(scripted.startsWith(plain + "-"), scripted);   // same masked name as a plain rule
    }

    @Test void scriptSeesRowAndParam() {
        MaskingEngine e = engineWith(Map.of("cond",
                "if row[\"type_ind\"] == \"P\" then return forge.mask(\"PHONE\", value) end return param"));
        MaskContext p = ctx();
        p.row.put("type_ind", "P");
        assertNotEquals("2125550147", e.mask(MaskFunction.SCRIPT, "s", "2125550147", "cond", "fallback", p));
        MaskContext other = ctx();
        other.row.put("type_ind", "X");
        assertEquals("fallback", e.mask(MaskFunction.SCRIPT, "s", "2125550147", "cond", "fallback", other));
    }

    @Test void nilReturnMeansSqlNull() {
        MaskingEngine e = engineWith(Map.of("nuller", "return nil"));
        assertNull(e.mask(MaskFunction.SCRIPT, "s", "secret", "nuller", null, ctx()));
    }

    @Test void sandboxHasNoOsIoOrFiles() {
        MaskingEngine e = engineWith(Map.of(
                "probe", "return tostring(os) .. \"/\" .. tostring(io) .. \"/\" .. tostring(loadfile) .. \"/\" .. tostring(require)"));
        assertEquals("nil/nil/nil/nil", e.mask(MaskFunction.SCRIPT, "s", "x", "probe", null, ctx()));
    }

    @Test void scriptErrorsFailLoudlyNeverLeak() {
        MaskingEngine e = engineWith(Map.of("boom", "error(\"broken\")"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> e.mask(MaskFunction.SCRIPT, "s", "pii-value", "boom", null, ctx()));
        assertTrue(ex.getMessage().contains("boom"), ex.getMessage());
    }

    @Test void syntaxCheckPointsAtTheOffendingLine() {
        MaskingEngine e = engineWith(Map.of());
        // error on line 3: 'then' is missing
        String err = e.checkScriptSyntax("local x = 1\nlocal y = 2\nif x == y return value end");
        assertNotNull(err);
        assertTrue(err.contains(":3"), "must point at line 3: " + err);
        // valid script → no error
        assertNull(e.checkScriptSyntax("return forge.fpe(value)"));
        assertNotNull(e.checkScriptSyntax("   "));   // empty is rejected too
    }

    @Test void missingScriptFailsLoudly() {
        MaskingEngine e = engineWith(Map.of());
        assertThrows(IllegalStateException.class,
                () -> e.mask(MaskFunction.SCRIPT, "s", "x", "does-not-exist", null, ctx()));
    }
}
