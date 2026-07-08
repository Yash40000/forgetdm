package io.forgetdm.provision.loader;

/**
 * Describes the physical load path selected for a target engine.
 *
 * nativeAvailable means the native path is enabled/configured in this runtime. When false, callers should
 * use the fallback strategy and retain this row as evidence of why the fallback was chosen.
 */
public record NativeLoadStrategy(String strategy,
                                 String engine,
                                 boolean nativeAvailable,
                                 String executor,
                                 String fallback,
                                 String launchMode,
                                 String configureHint,
                                 String notes) {
}
