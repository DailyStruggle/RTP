package io.github.dailystruggle.helpers.stresstestrtp;

/**
 * Platform-neutral immutable world coordinate used across {@code stresstest-core}
 * (ADR-122 Decision 1 & 2).
 */
public record StressLocation(String world, double x, double y, double z) {

    public StressLocation {
        world = world != null ? world : "";
    }

    public int blockX() {
        return (int) Math.floor(x);
    }

    public int blockY() {
        return (int) Math.floor(y);
    }

    public int blockZ() {
        return (int) Math.floor(z);
    }

    public int chunkX() {
        return blockX() >> 4;
    }

    public int chunkZ() {
        return blockZ() >> 4;
    }
}
