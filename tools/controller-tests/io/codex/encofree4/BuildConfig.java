package io.codex.encofree4;

/**
 * Minimal stand-in for the generated build configuration so the production
 * controller can be compiled and exercised on a plain JVM.
 */
public final class BuildConfig {
    private BuildConfig() {
    }

    /** Mirrors the debug build: frame logging on. */
    public static final boolean PROBE = true;
}
