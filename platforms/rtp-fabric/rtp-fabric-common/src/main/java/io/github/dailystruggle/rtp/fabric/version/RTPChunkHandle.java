package io.github.dailystruggle.rtp.fabric.version;

/**
 * Mojmap-name-stable wrapper around a Fabric {@code ChunkAccess} (a.k.a.
 * {@code class_2791}). See {@code rtp-fabric-ADR-007} for the rationale.
 */
public record RTPChunkHandle(Object chunk) {
    public static RTPChunkHandle of(Object chunk) {
        return new RTPChunkHandle(chunk);
    }

    public <T> T as(Class<T> type) {
        return type.cast(chunk);
    }
}
