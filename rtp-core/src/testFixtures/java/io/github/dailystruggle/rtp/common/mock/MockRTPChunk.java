package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;

import java.util.Set;

/**
 * Minimal in-memory implementation of {@link RTPChunk} for use in unit tests.
 *
 * <p>All blocks are reported as air and safe, sky-light is always 15, and
 * {@link #getSurfaceHeight} always returns 64.  This is sufficient for
 * {@link io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor}
 * to find a valid Y coordinate without requiring a real platform chunk.
 */
public class MockRTPChunk extends RTPChunk<Object> {

    private final int chunkX;
    private final int chunkZ;
    private final RTPWorld<?> world;

    /**
     * @param chunkX chunk X coordinate (not block X)
     * @param chunkZ chunk Z coordinate (not block Z)
     * @param world  the world this chunk belongs to
     */
    public MockRTPChunk(int chunkX, int chunkZ, RTPWorld<?> world) {
        super(new Object());
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.world = world;
    }

    @Override
    public int x() {
        return chunkX;
    }

    @Override
    public int z() {
        return chunkZ;
    }

    /** All blocks are air — {@link io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor} bottom-up scan will find Y=minY immediately. */
    @Override
    public boolean isAir(int x, int y, int z) {
        return true;
    }

    /** Full sky-light everywhere — satisfies requireSkyLight checks. */
    @Override
    public int getSkyLight(int x, int y, int z) {
        return 15;
    }

    /** Returns a fixed surface height of 64 (used by sky-light path). */
    @Override
    public int getSurfaceHeight(int x, int z) {
        return 64;
    }

    /** All positions are safe — no unsafe-block filtering needed in tests. */
    @Override
    public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
        return true;
    }

    @Override
    public RTPWorld<?> getWorld() {
        return world;
    }

    @Override
    public boolean isGenerated() {
        return true;
    }

    @Override
    public boolean isLoaded() {
        return true;
    }

    @Override
    public void keep(boolean keep) {
        // no-op in tests
    }

    @Override
    public void unload() {
        // no-op in tests
    }
}
