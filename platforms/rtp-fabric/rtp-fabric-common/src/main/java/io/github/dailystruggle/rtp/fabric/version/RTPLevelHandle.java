package io.github.dailystruggle.rtp.fabric.version;

/**
 * Mojmap-name-stable wrapper around a Fabric {@code ServerLevel} (a.k.a.
 * {@code class_3218}). Lets {@code rtp-fabric-common}'s SPI signature stay
 * Mojmap-immune across point releases - see
 * {@code rtp-fabric-ADR-007-mojmap-name-decoupling.md}.
 *
 * <p>The payload is held as {@link Object} so common's compiled bytecode
 * never references the Mojmap type directly. Per-version adapters cast on
 * entry via {@link #as(Class)}; common-side callers construct an instance
 * via {@link #of(Object)} and treat it as an opaque token.</p>
 */
public record RTPLevelHandle(Object level) {
    public static RTPLevelHandle of(Object level) {
        return new RTPLevelHandle(level);
    }

    /**
     * Cast the wrapped level to the adapter-local Mojmap type. Adapters call
     * this once on method entry; the cast is the only Mojmap-typed reference
     * to the level and lives inside the adapter's class file (which is
     * compiled against that version's mappings).
     */
    public <T> T as(Class<T> type) {
        return type.cast(level);
    }
}
