package io.forgetdm.core.mask;

/** Resolves a governed masking lookup list by name, optionally bypassing the provider cache. */
@FunctionalInterface
public interface MaskLookupProvider {
    String valuesFor(String name, boolean useCache);
}
