package io.github.dailystruggle.rtp.common.configuration.yaml;

/**
 * In-house parity object for {@code simpleyaml}'s {@code YamlConfigurationOptions}.
 *
 * <p>The in-house substrate (see ADR-025, 2026-05-15 revision) does not consume
 * any third-party YAML library; this class exists solely so call sites written
 * against the simpleyaml shape — {@code yamlFile.options().copyDefaults(true)},
 * {@code yamlFile.options().indent(2)} — continue to compile after the Session 2/3
 * type swap. The setters are fluent (return {@code this}) to match the original API.</p>
 *
 * <p>Current semantic effects:</p>
 * <ul>
 *   <li>{@link #copyDefaults(boolean)} — stored. The substrate always merges values
 *       set via {@link RtpYamlConfig#addDefault(String, Object)} when a key is
 *       missing, so the flag is currently advisory; it is retained for API parity
 *       and as a hook for future selective-merge behavior.</li>
 *   <li>{@link #indent(int)} — stored. The writer ({@link RtpYamlWriter}) currently
 *       emits a fixed two-space indent; this field is therefore advisory. Values
 *       other than {@code 2} are accepted for API parity but ignored on emit.</li>
 * </ul>
 *
 * <p>This class is intentionally minimal: only knobs with real call sites in the
 * codebase are exposed. Per the Stay-On-Task / YAGNI guidance in
 * {@code .junie/AGENTS.md}, additional simpleyaml options are not mirrored until
 * a caller needs them.</p>
 */
public final class RtpYamlOptions {

    private boolean copyDefaults = false;
    private int indent = 2;

    RtpYamlOptions() {
    }

    /** Mirrors {@code simpleyaml}'s {@code copyDefaults(boolean)}. Fluent. */
    public RtpYamlOptions copyDefaults(boolean value) {
        this.copyDefaults = value;
        return this;
    }

    /** Mirrors {@code simpleyaml}'s {@code copyDefaults()}. */
    public boolean copyDefaults() {
        return copyDefaults;
    }

    /**
     * Mirrors {@code simpleyaml}'s {@code indent(int)}. Fluent. Stored only;
     * the writer currently emits a fixed two-space indent.
     */
    public RtpYamlOptions indent(int value) {
        if (value < 1) throw new IllegalArgumentException("indent must be >= 1");
        this.indent = value;
        return this;
    }

    /** Mirrors {@code simpleyaml}'s {@code indent()}. */
    public int indent() {
        return indent;
    }
}
