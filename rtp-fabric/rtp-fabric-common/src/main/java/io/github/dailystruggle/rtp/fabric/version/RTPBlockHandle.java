package io.github.dailystruggle.rtp.fabric.version;

/**
 * Mojmap-name-stable wrapper around a Fabric {@code Block} (a.k.a.
 * {@code class_2248}). See {@code rtp-fabric-ADR-007} for the rationale.
 *
 * <p>Use {@link #as(Class)} from inside per-version adapters to recover the
 * Mojmap-typed reference; common-side callers treat the handle as an opaque
 * token.</p>
 */
public record RTPBlockHandle(Object block) {
    public static RTPBlockHandle of(Object block) {
        return new RTPBlockHandle(block);
    }

    public <T> T as(Class<T> type) {
        return type.cast(block);
    }
}
