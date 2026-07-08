package io.forgetdm.core.mask;

/** Supplies Lua source for user-defined SCRIPT masking by name. Wired by the host app (DB registry). */
public interface MaskScriptProvider {
    /** @return the Lua source, or null when no script with that name is available to this run. */
    String source(String name);
}
