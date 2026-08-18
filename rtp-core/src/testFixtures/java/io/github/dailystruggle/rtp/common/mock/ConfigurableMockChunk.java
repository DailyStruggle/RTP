package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;

import java.util.HashSet;
import java.util.Set;

/**
 * Configurable in-memory {@link RTPChunk} for unit tests.
 *
 * <p>By default every block is air and safe. Callers can mark specific
 * Y-coordinates as solid (non-air, unsafe) via {@link #setSolid(int)}.
 * Sky-light is always 15 and the chunk is always generated/loaded.
 */
public class ConfigurableMockChunk extends RTPChunk<Object> {

    private final int chunkX;
    private final int chunkZ;
    private final RTPWorld<?> world;

    /** Y-coordinates that are solid (non-air) AND unsafe (e.g. lava, fire). */
    private final Set<Integer> solidY = new HashSet<>();

    /** Y-coordinates that are solid (non-air) but safe (e.g. stone floor). */
    private final Set<Integer> solidSafeY = new HashSet<>();

    public ConfigurableMockChunk(int chunkX, int chunkZ, RTPWorld<?> world) {
        super(new Object());
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.world = world;
    }

    /**
     * Mark the given Y-coordinate as a solid block (non-air, unsafe).
     *
     * @param y world Y-coordinate to mark solid
     * @return {@code this} for fluent chaining
     */
    public ConfigurableMockChunk setSolid(int y) {
        solidY.add(y);
        return this;
    }

    /**
     * Mark the given Y-coordinate as a solid but safe block (non-air, safe - e.g. stone floor).
     * {@link #isAir} returns {@code false} and {@link #isSafe} returns {@code true} for these.
     *
     * @param y world Y-coordinate to mark solid-safe
     * @return {@code this} for fluent chaining
     */
    public ConfigurableMockChunk setSolidSafe(int y) {
        solidSafeY.add(y);
        return this;
    }

    @Override
    public int x() { return chunkX; }

    @Override
    public int z() { return chunkZ; }

    @Override
    public boolean isAir(int x, int y, int z) {
        return !solidY.contains(y) && !solidSafeY.contains(y);
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        return 15;
    }

    @Override
    public int getSurfaceHeight(int x, int z) {
        return 64;
    }

    @Override
    public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
        return !solidY.contains(y);
    }

    /** Returns {@code true} if the block at {@code y} is solid-safe (non-air, safe). */
    public boolean isSolidSafe(int y) {
        return solidSafeY.contains(y);
    }

    @Override
    public RTPWorld<?> getWorld() { return world; }

    @Override
    public boolean isGenerated() { return true; }

    @Override
    public boolean isLoaded() { return true; }

    @Override
    public void keep(boolean keep) { }

    @Override
    public void unload() { }
}
