package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;

import java.util.Set;

/**
 * In-memory {@link RTPChunk} for unit tests.
 * Blocks at Y <= 31 are solid; blocks at Y >= 32 are air (valid landing at Y=32 for LinearAdjustor).
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

    /**
     * Blocks at Y &le; 31 are solid (non-air) to simulate a ground layer beneath the
     * default {@link io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor}
     * minY of 32.  All blocks at Y &ge; 32 are air, so the bottom-up scan finds a
     * valid landing at Y=32 ({@code !isAir(31)} ✓, {@code isAir(32,33)} ✓).
     */
    @Override
    public boolean isAir(int x, int y, int z) {
        return y > 31;
    }

    /** Full sky-light everywhere - satisfies requireSkyLight checks. */
    @Override
    public int getSkyLight(int x, int y, int z) {
        return 15;
    }

    /** Returns a fixed surface height of 64 (used by sky-light path). */
    @Override
    public int getSurfaceHeight(int x, int z) {
        return 64;
    }

    /** All positions are safe - no unsafe-block filtering needed in tests. */
    @Override
    public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
        if (world instanceof MockRTPWorld m) {
            m.isSafeCallCount.incrementAndGet();
        }
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
