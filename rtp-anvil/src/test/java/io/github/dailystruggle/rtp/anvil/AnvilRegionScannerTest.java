package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract test for {@link AnvilRegionScanner} — the pregen biome-enumeration
 * utility that feeds the platform adapter's {@code setBiomesGetter} hook
 * (ADR-016 (biome) §6.1, Step 2 of §10).
 *
 * <p>Covers: happy-path union across multiple region files, mtime-keyed cache
 * hit/invalidation, empty-directory and missing-directory fallthrough, silent
 * skip of malformed {@code .mca} files, async dispatch off the caller thread.
 * Tracked in TRACEABILITY.md as the verification for ADR-016 (biome) §6.1.
 */
class AnvilRegionScannerTest {

    @TempDir
    Path tmp;

    @BeforeEach
    void resetCache() {
        // Each test gets a fresh global cache — mtime-signature collisions between
        // tempdirs would otherwise leak a previous test's result across tests.
        AnvilRegionScanner.invalidateCache();
    }

    @Test
    @DisplayName("Missing world folder yields an empty set (never throws)")
    void missingWorldFolderReturnsEmpty() {
        Set<String> result = AnvilRegionScanner.scanBiomes(tmp.resolve("nonexistent-world"), "");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("World folder with no region/ subdirectory yields an empty set")
    void missingRegionSubfolderReturnsEmpty() throws IOException {
        Path world = Files.createDirectory(tmp.resolve("world"));
        // No region/ folder created — this is the initial-generation / unpopulated case.
        Set<String> result = AnvilRegionScanner.scanBiomes(world, "");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Empty region/ folder yields an empty set")
    void emptyRegionFolderReturnsEmpty() throws IOException {
        Path world = Files.createDirectory(tmp.resolve("world"));
        Files.createDirectory(world.resolve("region"));
        assertTrue(AnvilRegionScanner.scanBiomes(world, "").isEmpty());
    }

    @Test
    @DisplayName("Scanner unions biome ids across every r.X.Z.mca in the region folder")
    void unionsAcrossMultipleRegionFiles() throws IOException {
        Path world = Files.createDirectory(tmp.resolve("world"));
        Path region = Files.createDirectory(world.resolve("region"));

        // r.0.0.mca carries minecraft:plains
        writeSingleChunkRegionFile(region.resolve("r.0.0.mca"),
                List.of("minecraft:plains"));
        // r.-1.0.mca carries iris:volcanic_ash_plains (namespaced, non-vanilla —
        // validates that the §5 verbatim-identifier promise propagates through
        // the scanner, not just the view).
        writeSingleChunkRegionFile(region.resolve("r.-1.0.mca"),
                List.of("iris:volcanic_ash_plains"));

        Set<String> result = AnvilRegionScanner.scanBiomes(world, "");
        assertAll(
                () -> assertEquals(2, result.size()),
                () -> assertTrue(result.contains("minecraft:plains")),
                () -> assertTrue(result.contains("iris:volcanic_ash_plains")));
    }

    @Test
    @DisplayName("Non-region files in the folder are ignored (r.*.*.mca pattern only)")
    void unrelatedFilesAreIgnored() throws IOException {
        Path world = Files.createDirectory(tmp.resolve("world"));
        Path region = Files.createDirectory(world.resolve("region"));

        writeSingleChunkRegionFile(region.resolve("r.0.0.mca"), List.of("minecraft:plains"));
        // These must all be skipped: wrong name pattern, wrong extension, or subfolder.
        Files.writeString(region.resolve("README.txt"), "hi");
        Files.writeString(region.resolve("r.0.0.mcc"), "not-an-mca");
        Files.writeString(region.resolve("notes.mca"), "wrong-name");
        Files.createDirectory(region.resolve("r.0.0.mca.backup"));

        Set<String> result = AnvilRegionScanner.scanBiomes(world, "");
        assertEquals(Set.of("minecraft:plains"), result);
    }

    @Test
    @DisplayName("Second call returns the cached instance when no region file has changed")
    void cacheHitWhenMtimeUnchanged() throws IOException {
        Path world = Files.createDirectory(tmp.resolve("world"));
        Path region = Files.createDirectory(world.resolve("region"));
        writeSingleChunkRegionFile(region.resolve("r.0.0.mca"), List.of("minecraft:plains"));

        Set<String> first = AnvilRegionScanner.scanBiomes(world, "");
        Set<String> second = AnvilRegionScanner.scanBiomes(world, "");

        // Identity equality proves the second call returned the cached value rather
        // than re-scanning. This is the performance contract §6.1 depends on
        // (tab-completion must be free on a warm cache).
        assertSame(first, second);
    }

    @Test
    @DisplayName("Cache is invalidated when a region file's mtime advances")
    void cacheInvalidatesOnMtimeBump() throws IOException {
        Path world = Files.createDirectory(tmp.resolve("world"));
        Path region = Files.createDirectory(world.resolve("region"));
        Path rf = region.resolve("r.0.0.mca");
        writeSingleChunkRegionFile(rf, List.of("minecraft:plains"));

        Set<String> first = AnvilRegionScanner.scanBiomes(world, "");
        assertEquals(Set.of("minecraft:plains"), first);

        // Rewrite the region file with a richer biome palette *and* bump its mtime
        // explicitly — some filesystems (notably older FAT variants on CI) only
        // carry 1-2 s mtime resolution, so we set the timestamp explicitly rather
        // than relying on a wall-clock difference between the two writes.
        writeSingleChunkRegionFile(rf, List.of("minecraft:plains", "iris:volcanic_ash_plains"));
        Files.setLastModifiedTime(rf, FileTime.fromMillis(System.currentTimeMillis() + 60_000L));

        Set<String> second = AnvilRegionScanner.scanBiomes(world, "");
        assertAll(
                () -> assertNotSame(first, second, "mtime bump must force a re-scan, not return cached set"),
                () -> assertTrue(second.contains("minecraft:plains")),
                () -> assertTrue(second.contains("iris:volcanic_ash_plains")),
                () -> assertEquals(2, second.size()));
    }

    @Test
    @DisplayName("invalidateCache forces a fresh scan even when mtime is unchanged")
    void invalidateCacheForcesRescan() throws IOException {
        Path world = Files.createDirectory(tmp.resolve("world"));
        Path region = Files.createDirectory(world.resolve("region"));
        writeSingleChunkRegionFile(region.resolve("r.0.0.mca"), List.of("minecraft:plains"));

        Set<String> first = AnvilRegionScanner.scanBiomes(world, "");
        AnvilRegionScanner.invalidateCache();
        Set<String> second = AnvilRegionScanner.scanBiomes(world, "");

        assertAll(
                () -> assertNotSame(first, second, "post-invalidate the scanner must re-read from disk"),
                () -> assertEquals(first, second, "content parity — same region files, same biome union"));
    }

    @Test
    @DisplayName("Malformed region files are silently skipped; valid neighbours still contribute")
    void malformedRegionFilesAreSkipped() throws IOException {
        Path world = Files.createDirectory(tmp.resolve("world"));
        Path region = Files.createDirectory(world.resolve("region"));

        writeSingleChunkRegionFile(region.resolve("r.0.0.mca"), List.of("minecraft:plains"));
        // Garbage bytes: matches the r.*.*.mca glob but is not a valid region file.
        // ADR-016 posture: decoder errors swallow per-file, scanner returns what it
        // can from the remaining files instead of propagating the IOException.
        Files.write(region.resolve("r.1.0.mca"), new byte[]{1, 2, 3, 4, 5});

        Set<String> result = AnvilRegionScanner.scanBiomes(world, "");
        assertEquals(Set.of("minecraft:plains"), result,
                "malformed file must not poison the union from the good file");
    }

    @Test
    @DisplayName("dimensionSubpath resolves to <world>/<sub>/region (nether/end/datapack layout)")
    void dimensionSubpathResolvesCorrectly() throws IOException {
        Path world = Files.createDirectory(tmp.resolve("world"));
        // Emulate the Nether layout: <world>/DIM-1/region/r.0.0.mca
        Path netherRegion = Files.createDirectories(world.resolve("DIM-1").resolve("region"));
        writeSingleChunkRegionFile(netherRegion.resolve("r.0.0.mca"),
                List.of("minecraft:nether_wastes"));

        Set<String> overworld = AnvilRegionScanner.scanBiomes(world, "");
        Set<String> nether = AnvilRegionScanner.scanBiomes(world, "DIM-1");

        assertAll(
                () -> assertTrue(overworld.isEmpty(),
                        "overworld region/ was never created — its scan must stay empty"),
                () -> assertEquals(Set.of("minecraft:nether_wastes"), nether));
    }

    @Test
    @DisplayName("scanBiomesAsync completes with the same set the synchronous variant returns")
    void asyncVariantProducesSameResult() throws IOException, ExecutionException, InterruptedException {
        Path world = Files.createDirectory(tmp.resolve("world"));
        Path region = Files.createDirectory(world.resolve("region"));
        writeSingleChunkRegionFile(region.resolve("r.0.0.mca"),
                List.of("minecraft:plains", "iris:volcanic_ash_plains"));

        CompletableFuture<Set<String>> future = AnvilRegionScanner.scanBiomesAsync(world, "");
        Set<String> asyncResult = future.get();

        // Parity: the async entry point is the supported caller contract (§6.1 dispatches
        // the scan on commonPool); the sync entry point is a test/verification shim.
        // Both must see the same bytes on disk and return the same union.
        Set<String> syncResult = AnvilRegionScanner.scanBiomes(world, "");
        assertEquals(syncResult, asyncResult);
        assertFalse(asyncResult.isEmpty());
    }

    // -------------------------------------------------------------- helpers

    /**
     * Writes a synthetic single-chunk {@code r.?.?.mca} to {@code target}. The chunk
     * carries one section at {@code Y=0} with the given biome palette and a stone
     * block palette (so {@link AnvilReader#readChunkView} produces a non-null view).
     *
     * <p>The "chunk is placed in slot (0,0) of the region file regardless of the
     * filename" detail is intentional: {@link AnvilRegionScanner} does not consult
     * the filename's X/Z coordinates, it iterates all 1024 chunk slots and unions
     * whatever it decodes. The test suite therefore exercises the same code path
     * the real scanner takes on pregenerated worlds.
     */
    private static void writeSingleChunkRegionFile(Path target, List<String> biomePalette) throws IOException {
        LinkedHashMap<String, Object> sec = AnvilTestFixtures.sectionWithBiomes(
                (byte) 0,
                List.of("minecraft:stone"), null,
                biomePalette, null);
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, new long[37], List.of(sec));
        byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);
        Files.write(target, region);
    }
}
