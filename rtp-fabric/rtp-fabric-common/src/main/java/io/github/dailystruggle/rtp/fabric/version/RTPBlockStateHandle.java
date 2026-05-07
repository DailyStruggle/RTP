package io.github.dailystruggle.rtp.fabric.version;

/**
 * Mojmap-name-stable wrapper around a Fabric {@code BlockState} (a.k.a.
 * {@code class_2680}). See {@code rtp-fabric-ADR-007} for the rationale.
 */
public record RTPBlockStateHandle(Object state) {
    public static RTPBlockStateHandle of(Object state) {
        return new RTPBlockStateHandle(state);
    }

    public <T> T as(Class<T> type) {
        return type.cast(state);
    }
}
