package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.platform.BlockDelta;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surface guard for {@link WorldBlockSchematicPaster} (ADR-058): verifies that its paste
 * plans the decoded schematic into placements, converts them to {@link BlockDelta}s, routes them
 * through {@link RTPWorld#setBlocks(java.util.List)}, and maps the placed count to a
 * {@link PasteResult}. No live world / file I/O - the schematic and world are deterministic doubles.
 */
class WorldBlockSchematicPasterTest {

    private static SchematicSource source() {
        return new SchematicSource("hub", Path.of("schematics", "hub.schem"), "schem");
    }

    /** 1x2x1 column: stone at y=0, air at y=1. Air is skipped by default options. */
    private static LoadedSchematic twoCellColumn() {
        return new LoadedSchematic() {
            @Override public SchematicSource source() {
                return WorldBlockSchematicPasterTest.source();
            }
            @Override public int width() { return 1; }
            @Override public int height() { return 2; }
            @Override public int length() { return 1; }
            @Override public List<String> palette() {
                return List.of("minecraft:stone", "minecraft:air");
            }
            @Override public int paletteIndexAt(int x, int y, int z) {
                if (x != 0 || z != 0) return -1;
                return (y == 0) ? 0 : 1;
            }
        };
    }

    @Test
    void pasteRoutesNonAirPlacementsThroughSetBlocks() {
        RecordingWorld world = new RecordingWorld();
        RTPLocation at = new RTPLocation(world, 5, 64, -3);
        PasteResult result =
                new WorldBlockSchematicPaster().paste(twoCellColumn(), at, PasteOptions.defaults());

        assertEquals(PasteResult.PASTED, result);
        // Only the solid cell is written (air skipped); BOTTOM_CENTER anchor keeps it at arrival.
        assertEquals(1, world.written.size());
        BlockDelta d = world.written.get(0);
        assertEquals("minecraft:stone", d.token());
        assertEquals(5, d.x());
        assertEquals(64, d.y());
        assertEquals(-3, d.z());
    }

    @Test
    void pasteReportsErrorWhenWorldPlacesNothing() {
        RecordingWorld world = new RecordingWorld();
        world.placeNothing = true;
        RTPLocation at = new RTPLocation(world, 0, 64, 0);
        assertSame(PasteResult.PASTE_ERROR,
                new WorldBlockSchematicPaster().paste(twoCellColumn(), at, PasteOptions.defaults()));
        // Even when the world declines to place, the deltas were still offered to it.
        assertTrue(world.written.isEmpty());
        assertEquals(1, world.offered);
    }

    /** Minimal {@link RTPWorld} double capturing the blocks handed to {@link #setBlocks}. */
    private static final class RecordingWorld extends RTPWorld<Object> {
        final List<BlockDelta> written = new ArrayList<>();
        int offered = 0;
        boolean placeNothing = false;

        RecordingWorld() { super(new Object()); }

        @Override
        public int setBlocks(List<BlockDelta> blocks) {
            offered = blocks.size();
            if (placeNothing) return 0;
            written.addAll(blocks);
            return written.size();
        }

        @Override public String name() { return "test"; }
        @Override public UUID id() { return UUID.randomUUID(); }
        @Override public CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ) {
            return CompletableFuture.completedFuture(0L);
        }
        @Override public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Integer> getServerForceLoadedCount() {
            return CompletableFuture.completedFuture(0);
        }
        @Override public RTPChunk<?> getCachedChunk(long key) { return null; }
        @Override public void keepChunkAt(int chunkX, int chunkZ) { }
        @Override public void forgetChunkAt(int chunkX, int chunkZ) { }
        @Override public void forgetChunks() { }
        @Override public String getBiome(int x, int y, int z) { return ""; }
        @Override public void platform(RTPLocation location) { }
        @Override public boolean isInactive() { return false; }
        @Override public void save() { }
        @Override public int getMaxHeight() { return 320; }
        @Override public int getMinHeight() { return -64; }
        @Override public int getCacheSize() { return 0; }
        @Override public long getSeed() { return 0L; }
        @Override protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
