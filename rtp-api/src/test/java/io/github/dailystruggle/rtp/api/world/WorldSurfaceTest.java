package io.github.dailystruggle.rtp.api.world;

import io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surface coverage for the platform-neutral {@code world} package: value types
 * ({@link RTPCoords}, {@link MutableRTPCoords}, {@link BiomeSampleCapability}),
 * {@link RTPLocation}, the ref-counted ticket bookkeeping in {@link RTPWorld}
 * (S-002), {@link ChunkReservation}/{@link ChunkSet} lifecycle, {@link RTPChunk}
 * defaults, and {@link ChunkColumnProbe} default air classification.
 */
class WorldSurfaceTest {

    // --- RTPCoords ---

    @Test
    void coordsChunkKeyPacksXandZ() {
        RTPCoords c = new RTPCoords("world", 33, 70, -17);
        // 33>>4 = 2, -17>>4 = -2 -> packed low/high 32-bit halves.
        long expected = ((long) (33 >> 4) & 0xFFFFFFFFL) | (((long) (-17 >> 4) & 0xFFFFFFFFL) << 32);
        assertEquals(expected, c.getChunkKey());
        assertEquals("world", c.worldName());
        assertEquals(70, c.y());
    }

    // --- MutableRTPCoords ---

    @Test
    void mutableCoordsHorizontalCtorDefaults() {
        MutableRTPCoords m = new MutableRTPCoords(4, 8);
        assertEquals("", m.worldName);
        assertEquals(4, m.x);
        assertEquals(0, m.y);
        assertEquals(8, m.z);
    }

    @Test
    void mutableCoordsSettersAndSnapshot() {
        MutableRTPCoords m = new MutableRTPCoords("nether", 1, 2, 3);
        m.setXZ(10, 20);
        m.setY(64);
        m.setWorldName("end");
        RTPCoords snap = m.toImmutable();
        assertEquals(new RTPCoords("end", 10, 64, 20), snap);
    }

    // --- BiomeSampleCapability ---

    @Test
    void biomeSampleCapabilityRoundTrips() {
        for (BiomeSampleCapability c : BiomeSampleCapability.values()) {
            assertSame(c, BiomeSampleCapability.valueOf(c.name()));
        }
        assertEquals(3, BiomeSampleCapability.values().length);
    }

    // --- RTPLocation ---

    @Test
    void locationAccessorsAndReservation() {
        TestWorld world = new TestWorld("w");
        RTPLocation loc = new RTPLocation(world, 5, 64, -3);
        assertEquals(5, loc.getBlockX());
        assertEquals(64, loc.getBlockY());
        assertEquals(-3, loc.getBlockZ());
        assertEquals(5, loc.x());
        assertEquals(64, loc.y());
        assertEquals(-3, loc.z());
        assertSame(world, loc.world());
        assertNull(loc.getReservation());
        assertTrue(loc.toString().contains("w"));

        ChunkSet set = new ChunkSet(world, 0, 0,
                Collections.singletonList(CompletableFuture.completedFuture(0L)),
                new CompletableFuture<>());
        ChunkReservation res = new ChunkReservation(set, world);
        loc.setReservation(res);
        assertSame(res, loc.getReservation());
        res.close();
    }

    @Test
    void locationDistanceMatchesWorldEquality() {
        TestWorld world = new TestWorld("w");
        RTPLocation a = new RTPLocation(world, 0, 0, 0);
        RTPLocation b = new RTPLocation(world, 3, 4, 0);
        assertEquals(25L, a.distanceSquared(b));
        assertEquals(9L, new RTPLocation(world, 0, 100, 0).distanceSquaredXZ(new RTPLocation(world, 3, 0, 0)));

        // Different world -> MAX_VALUE sentinel for both metrics.
        RTPLocation other = new RTPLocation(new TestWorld("other"), 0, 0, 0);
        assertEquals(Long.MAX_VALUE, a.distanceSquared(other));
        assertEquals(Long.MAX_VALUE, a.distanceSquaredXZ(other));
    }

    @Test
    void locationEqualityHashCloneIgnoreReservation() {
        TestWorld world = new TestWorld("w");
        RTPLocation a = new RTPLocation(world, 1, 2, 3);
        RTPLocation b = new RTPLocation(world, 1, 2, 3);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a, a);
        assertNotEquals(a, new RTPLocation(world, 9, 2, 3));
        assertNotEquals(a, null);
        assertNotEquals(a, "loc");

        RTPLocation clone = a.clone();
        assertEquals(a, clone);
        assertNotSame(a, clone);
    }

    // --- ChunkSet ---

    @Test
    void chunkSetCompletesTrueWhenAllLoad() throws Exception {
        TestWorld world = new TestWorld("w");
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        ChunkSet set = new ChunkSet(world, 2, 3,
                List.of(CompletableFuture.completedFuture(1L), CompletableFuture.completedFuture(2L)),
                done);
        assertEquals(2, set.getX());
        assertEquals(3, set.getZ());
        assertTrue(done.get());
    }

    @Test
    void chunkSetCompletesFalseWhenAnyFails() throws Exception {
        TestWorld world = new TestWorld("w");
        CompletableFuture<Long> failing = new CompletableFuture<>();
        failing.completeExceptionally(new IllegalStateException("load failed"));
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        new ChunkSet(world, 0, 0, List.of(failing), done);
        assertFalse(done.get());
    }

    // --- RTPWorld ref-counted tickets (S-002) ---

    @Test
    void forceLoadRefCountsAndReleasesOnce() {
        TestWorld world = new TestWorld("w");
        // Two acquires on the same chunk -> single native apply, count == 2.
        world.setForceLoaded(1, 1, true);
        world.setForceLoaded(1, 1, true);
        assertEquals(1, world.numForceLoaded());
        assertEquals(2, world.activeChunkTickets.get());
        assertEquals(2, world.lifetimeTicketsIssued.get());
        assertEquals(1, world.applyTrue.get(), "native force-load applied exactly once for the ref-counted chunk");

        // First release decrements only; second release drops the ticket + native call.
        world.setForceLoaded(1, 1, false);
        assertEquals(1, world.numForceLoaded());
        world.setForceLoaded(1, 1, false);
        assertEquals(0, world.numForceLoaded());
        assertEquals(1, world.applyFalse.get());

        // Releasing an unknown chunk is a safe no-op returning a completed future.
        assertNotNull(world.setForceLoaded(9, 9, false));
    }

    @Test
    void refreshForceLoadedAppliesWithoutTicketChange() {
        TestWorld world = new TestWorld("w");
        world.refreshForceLoaded(4, 5);
        assertEquals(0, world.numForceLoaded());
        assertEquals(1, world.applyTrue.get());
    }

    @Test
    void releaseOrphanedTicketsDrainsUnprotectedChunks() {
        TestWorld world = new TestWorld("w");
        world.setForceLoaded(0, 0, true);
        world.setForceLoaded(0, 0, true); // count 2 on (0,0)
        long keepKey = ((long) 0 & 0xffffffffL) | ((long) 0 << 32);
        world.setForceLoaded(7, 7, true); // orphan candidate

        world.releaseOrphanedTickets(Set.of(keepKey));
        assertEquals(1, world.numForceLoaded(), "protected (0,0) survives; orphan (7,7) drained");
        assertEquals(1, world.lifetimeOrphanedTicketsScanned.get());
    }

    @Test
    void getOrLoadChunkCachedShortCircuits() throws Exception {
        TestWorld world = new TestWorld("w");
        TestChunk cached = new TestChunk("cached", world);
        world.cached = cached;
        RTPChunk<?> got = world.getOrLoadChunk(1, 2, "startup").get();
        assertSame(cached, got);
        // Cache hit must not attribute a live load.
        assertTrue(world.chunkLoadsByOrigin.isEmpty());
    }

    @Test
    void getOrLoadChunkLiveLoadRecordsOrigin() throws Exception {
        TestWorld world = new TestWorld("w");
        world.cached = null;
        world.liveChunk = new TestChunk("live", world);
        RTPChunk<?> got = world.getOrLoadChunk(3, 4, "pipeline").get();
        assertSame(world.liveChunk, got);
        assertEquals(1, world.chunkLoadsByOrigin.get("pipeline").get());

        // Untagged overload attributes to "unknown" (reset cache so it falls through to a live load).
        world.cached = null;
        world.liveChunk = new TestChunk("live2", world);
        world.getOrLoadChunk(5, 6).get();
        assertEquals(1, world.chunkLoadsByOrigin.get("unknown").get());
    }

    @Test
    void worldDefaultsAndValueSemantics() {
        TestWorld world = new TestWorld("w");
        assertFalse(world.isVanilla());
        assertNull(world.environment());
        assertTrue(world.isChunkLoaded(0, 0));
        assertTrue(world.isChunkGenerated(0, 0));
        assertTrue(world.isActive());
        assertEquals(Collections.emptyMap(), world.readBiomesInRegionFile(0, 0, 0));
        assertEquals(0, world.setBlocks(Collections.emptyList()));
        assertFalse(world.restoreBlocks(Collections.emptyList()));
        assertEquals(0, world.restoreBlockEntities(Collections.emptyList()));
        assertNotNull(world.schematicPaster());
        assertEquals("payload-w", world.world());

        assertEquals(new TestWorld("w"), new TestWorld("w"));
        assertNotEquals(new TestWorld("w"), new TestWorld("x"));
        assertTrue(world.toString().contains("TestWorld"));
    }

    // --- RTPChunk defaults ---

    @Test
    void chunkDefaultsAndCompiledUnsafeOverload() {
        TestWorld world = new TestWorld("w");
        TestChunk chunk = new TestChunk("payload", world);
        assertFalse(chunk.isSelfContained());
        assertEquals("biome:w", chunk.getBiome(0, 64, 0));
        // Compiled overload delegates to the plain-materials Set overload.
        assertFalse(chunk.isSafe(0, 64, 0, CompiledUnsafeSet.EMPTY));
        assertEquals(chunk, new TestChunk("payload", world));
        assertNotEquals(chunk, new TestChunk("other", world));
        assertEquals(chunk.hashCode(), new TestChunk("payload", world).hashCode());
        assertTrue(chunk.toString().contains("payload"));
    }

    // --- ChunkColumnProbe default methods ---

    @Test
    void probeDefaultAirClassification() {
        ChunkColumnProbe probe = new ChunkColumnProbe() {
            @Override public int chunkX() { return 0; }
            @Override public int chunkZ() { return 0; }
            @Override public int minY() { return 0; }
            @Override public int maxY() { return 16; }
            @Override public OptionalInt heightmapTopY() { return OptionalInt.empty(); }
            @Override public String blockAt(int y) {
                if (y == 1) return "minecraft:air";
                if (y == 2) return "cave_air";
                if (y == 3) return "minecraft:stone";
                return null;
            }
            @Override public String biomeAt(int y) { return "minecraft:plains"; }
        };
        assertTrue(probe.isAirAt(1));
        assertTrue(probe.isAirAt(2));
        assertFalse(probe.isAirAt(3));
        assertFalse(probe.isAirAt(99), "null block is not air");
        // 3-arg defaults delegate to the single-arg column query.
        assertEquals("minecraft:air", probe.blockAt(5, 5, 1));
        assertTrue(probe.isAirAt(5, 5, 1));
        assertFalse(probe.isAirAt(5, 5, 3));
    }

    private static void assertNotNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNotNull(o);
    }

    private static void assertNotSame(Object a, Object b) {
        org.junit.jupiter.api.Assertions.assertNotSame(a, b);
    }

    /** Minimal concrete {@link RTPWorld} counting native force-load applications. */
    private static final class TestWorld extends RTPWorld<String> {
        private final String name;
        final AtomicInteger applyTrue = new AtomicInteger();
        final AtomicInteger applyFalse = new AtomicInteger();
        RTPChunk<?> cached;
        RTPChunk<?> liveChunk;

        TestWorld(String name) {
            // Distinct underlying payload per name so RTPWorld.equals (which compares the
            // wrapped platform object) distinguishes different worlds.
            super("payload-" + name);
            this.name = name;
        }

        @Override
        protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
            if (forceLoad) applyTrue.incrementAndGet(); else applyFalse.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        @Override public String name() { return name; }
        @Override public UUID id() { return UUID.nameUUIDFromBytes(name.getBytes()); }
        @Override public CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
            long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
            // Prime the "cache" so the post-load lookup resolves to the live chunk.
            cached = liveChunk;
            return CompletableFuture.completedFuture(
                    new ChunkSet(this, cx, cz,
                            List.of(CompletableFuture.completedFuture(key)),
                            new CompletableFuture<>()));
        }
        @Override public CompletableFuture<Integer> getServerForceLoadedCount() {
            return CompletableFuture.completedFuture((int) numForceLoaded());
        }
        @Override public RTPChunk<?> getCachedChunk(long key) { return cached; }
        @Override public void keepChunkAt(int chunkX, int chunkZ) { }
        @Override public void forgetChunkAt(int chunkX, int chunkZ) { }
        @Override public void forgetChunks() { }
        @Override public String getBiome(int x, int y, int z) { return "biome:" + name; }
        @Override public void platform(RTPLocation location) { }
        @Override public boolean isInactive() { return false; }
        @Override public void save() { }
        @Override public int getMaxHeight() { return 320; }
        @Override public int getMinHeight() { return -64; }
        @Override public int getCacheSize() { return 0; }
        @Override public long getSeed() { return 42L; }
    }

    /** Minimal concrete {@link RTPChunk} for default-method coverage. */
    private static final class TestChunk extends RTPChunk<String> {
        private final RTPWorld<?> world;

        TestChunk(String payload, RTPWorld<?> world) {
            super(payload);
            this.world = world;
        }

        @Override public int x() { return 0; }
        @Override public int z() { return 0; }
        @Override public boolean isAir(int x, int y, int z) { return false; }
        @Override public int getSkyLight(int x, int y, int z) { return 15; }
        @Override public int getSurfaceHeight(int x, int z) { return 64; }
        @Override public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) { return false; }
        @Override public RTPWorld<?> getWorld() { return world; }
        @Override public boolean isGenerated() { return true; }
        @Override public boolean isLoaded() { return true; }
        @Override public void keep(boolean keep) { }
        @Override public void unload() { }
    }
}
