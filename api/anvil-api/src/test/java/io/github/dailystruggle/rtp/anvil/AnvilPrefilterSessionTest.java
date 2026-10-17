package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session binding, byte-cache lease, addon-format and column-probe paths of {@link AnvilPrefilter}
 * (ADR-016, ADR-077). Every failure must surface as {@link Verdict#UNKNOWN}, never silently (S-004).
 */
@DisplayName("ADR-016 / ADR-077 - AnvilPrefilter sessions, leases and column probes")
class AnvilPrefilterSessionTest {

    private static final String EXT = ".rtpsessiontest";

    private long savedWindowMillis;

    @BeforeEach
    void setUp() {
        savedWindowMillis = AnvilRegionByteCache.revalidateIntervalMillis();
        AnvilRegionByteCache.setRevalidateIntervalMillis(60_000L);
        RegionFileResolver.invalidateMemo();
    }

    @AfterEach
    void tearDown() {
        RegionFormatRegistry.unregister(EXT);
        AnvilRegionByteCache.setRevalidateIntervalMillis(savedWindowMillis);
        RegionFileResolver.invalidateMemo();
        AnvilRegionByteCache.resetAll();
    }

    private static LinkedHashMap<String, Object> stoneRoot() throws IOException {
        long[] heightmap = new long[37];
        heightmap[0] = 1L;
        return AnvilTestFixtures.chunkRoot(DataVersionSupport.MC_1_20_DATA_VERSION, heightmap,
                List.of(AnvilTestFixtures.section((byte) 0, List.of("minecraft:stone"), null)));
    }

    /** Section 0 whose block indices point past the palette at the given section-local Y levels. */
    private static LinkedHashMap<String, Object> outOfRangeIndexRoot(int... badY) throws IOException {
        List<String> palette = Arrays.asList("minecraft:air", "minecraft:stone", "minecraft:dirt");
        int[] indices = new int[4096];
        Arrays.fill(indices, 1);
        for (int y : badY) {
            for (int i = 0; i < 256; i++) indices[y * 256 + i] = 7;
        }
        long[] packed = AnvilTestFixtures.packIndices(4, indices);
        long[] heightmap = new long[37];
        heightmap[0] = 1L; // column (0,0): ground at world Y 0
        return AnvilTestFixtures.chunkRoot(DataVersionSupport.MC_1_20_DATA_VERSION, heightmap,
                List.of(AnvilTestFixtures.section((byte) 0, palette, packed)));
    }

    private static Path writeRegion(Path world, String fileName, LinkedHashMap<String, Object> root)
            throws IOException {
        Path regionDir = world.resolve("region");
        Files.createDirectories(regionDir);
        Path file = regionDir.resolve(fileName);
        Files.write(file, AnvilTestFixtures.writeSingleChunkRegion(root));
        return file;
    }

    /** Makes {@code file} resident in both the header and byte caches so {@code residentLease} hits. */
    private static void makeResident(Path file) throws IOException {
        assertNotNull(AnvilRegionHeaderCache.get(file));
        try (AnvilRegionByteCache.Lease lease = AnvilRegionByteCache.acquire(file)) {
            assertNotNull(lease);
        }
        AnvilRegionByteCache.Lease resident = AnvilPrefilter.residentLease(file);
        assertNotNull(resident, "fixture must be resident for the lease path");
        resident.close();
    }

    private static RegionFileReader delegatingReader() {
        return new RegionFileReader() {
            @Override
            public AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int rx, int rz) throws IOException {
                return AnvilReader.INSTANCE.readChunk(regionBytes, rx, rz);
            }

            @Override
            public boolean isChunkGenerated(byte[] regionBytes, int rx, int rz) {
                return AnvilReader.INSTANCE.isChunkGenerated(regionBytes, rx, rz);
            }
        };
    }

    @Test
    @DisplayName("probeDetailed with a null executor falls back to the Anvil I/O pool")
    void nullExecutorUsesPool(@TempDir Path world) throws IOException {
        writeRegion(world, "r.0.0.mca", stoneRoot());
        AnvilPrefilter.ProbeResult res = AnvilPrefilter.probeDetailed(
                world, "", 0, 0, Set.of("LAVA"), AnvilPrefilter.DEFAULT_RECONCILER, null).join();
        assertEquals(Verdict.ACCEPT, res.verdict());
    }

    @Test
    @DisplayName("Batch probe tolerates null/empty input and null entries")
    void batchNullHandling(@TempDir Path world) throws IOException {
        assertTrue(AnvilPrefilter.probeBatchSyncDetailed(world, "", null, Set.of("LAVA"), null).isEmpty());
        assertTrue(AnvilPrefilter.probeBatchSyncDetailed(world, "", List.of(), Set.of("LAVA"), null).isEmpty());

        writeRegion(world, "r.0.0.mca", stoneRoot());
        List<ChunkCoord> chunks = new ArrayList<>();
        chunks.add(null);
        chunks.add(new ChunkCoord(0, 0));
        Map<ChunkCoord, AnvilPrefilter.ProbeResult> out =
                AnvilPrefilter.probeBatchSyncDetailed(world, "", chunks, Set.of("LAVA"), null);
        assertEquals(1, out.size());
        assertEquals(Verdict.ACCEPT, out.get(new ChunkCoord(0, 0)).verdict());
    }

    @Test
    @DisplayName("A session bound to one region probes a foreign-region chunk through a throwaway session")
    void foreignRegionChunk(@TempDir Path world) throws IOException {
        writeRegion(world, "r.0.0.mca", stoneRoot());
        try (AnvilPrefilter.RegionProbeSession s = AnvilPrefilter.openSession(world, null, Set.of("LAVA"), null)) {
            assertEquals(Verdict.ACCEPT, s.probe(0, 0).verdict());
            AnvilPrefilter.ProbeResult foreign = s.probe(40, 0);
            assertEquals(Verdict.UNKNOWN, foreign.verdict(), "no r.1.0.mca on disk");
            assertNull(foreign.view());
            assertEquals(Verdict.ACCEPT, s.probe(0, 0).verdict(), "session stays bound to r.0.0");
        }
    }

    @Test
    @DisplayName("A resident byte-cache lease serves the chunk without opening a sector session")
    void residentLeaseServesChunk(@TempDir Path world) throws IOException {
        Path file = writeRegion(world, "r.0.0.mca", stoneRoot());
        makeResident(file);
        try (AnvilPrefilter.RegionProbeSession s = AnvilPrefilter.openSession(world, "", Set.of("LAVA"), null)) {
            AnvilPrefilter.ProbeResult res = s.probe(0, 0);
            assertEquals(Verdict.ACCEPT, res.verdict());
            assertNotNull(res.view());
            assertEquals(Verdict.UNKNOWN, s.probe(1, 0).verdict(), "empty slot in the same leased file");
        }
    }

    @Test
    @DisplayName("Addon-registered formats are decoded through one whole-file lease")
    void addonFormatViaLease(@TempDir Path world) throws IOException {
        RegionFormatRegistry.register(EXT, delegatingReader());
        writeRegion(world, "r.0.0" + EXT, stoneRoot());
        RegionFileResolver.invalidateMemo();
        try (AnvilPrefilter.RegionProbeSession s = AnvilPrefilter.openSession(world, "", Set.of("LAVA"), null)) {
            assertEquals(Verdict.ACCEPT, s.probe(0, 0).verdict());
            assertEquals(Verdict.UNKNOWN, s.probe(1, 0).verdict());
        }
    }

    @Test
    @DisplayName("An addon-format file that vanishes after resolution yields UNKNOWN")
    void addonFormatUnreadable(@TempDir Path world) throws IOException {
        RegionFormatRegistry.register(EXT, delegatingReader());
        Path file = writeRegion(world, "r.0.0" + EXT, stoneRoot());
        RegionFileResolver.invalidateMemo();
        RegionFileResolver.ResolvedRegion resolved = RegionFileResolver.resolveExisting(world, "", 0, 0);
        assertNotNull(resolved);
        assertEquals(file, resolved.path());
        Files.delete(file); // memoized resolution still points at the deleted file

        AnvilPrefilter.ProbeResult res = AnvilPrefilter.probeSyncDetailed(world, "", 0, 0, Set.of("LAVA"));
        assertEquals(Verdict.UNKNOWN, res.verdict());
        assertNull(res.view());
    }

    @Test
    @DisplayName("Out-of-range palette indices at ground, ground+1 or ground+2 yield UNKNOWN")
    void malformedGroundPalette(@TempDir Path world) throws IOException {
        for (int badY = 0; badY <= 2; badY++) {
            Path w = world.resolve("w" + badY);
            writeRegion(w, "r.0.0.mca", outOfRangeIndexRoot(badY));
            AnvilPrefilter.ProbeResult res = AnvilPrefilter.probeSyncDetailed(w, "", 0, 0, Set.of("LAVA"));
            assertEquals(Verdict.UNKNOWN, res.verdict(), "bad index at section-local Y " + badY);
            assertNull(res.view());
        }
        Path ok = world.resolve("ok");
        writeRegion(ok, "r.0.0.mca", outOfRangeIndexRoot(5));
        assertEquals(Verdict.ACCEPT, AnvilPrefilter.probeSyncDetailed(ok, "", 0, 0, Set.of("LAVA")).verdict(),
                "bad indices above the sampled window do not affect the verdict");
    }

    @Test
    @DisplayName("probeColumn returns null for missing inputs, absent files and non-Anvil formats")
    void probeColumnNullCases(@TempDir Path world) throws IOException {
        assertNull(AnvilPrefilter.probeColumn(null, "", 0, 0, 0, 16));
        assertNull(AnvilPrefilter.probeColumn(world, "", 0, 0, 16, 0));
        assertNull(AnvilPrefilter.probeColumn(world, null, 0, 0, 0, 16));

        RegionFormatRegistry.register(EXT, delegatingReader());
        Path other = world.resolve("other");
        writeRegion(other, "r.0.0" + EXT, stoneRoot());
        RegionFileResolver.invalidateMemo();
        assertNull(AnvilPrefilter.probeColumn(other, "", 0, 0, 0, 16));
    }

    @Test
    @DisplayName("probeColumn reads from a resident lease and from disk with identical answers")
    void probeColumnLeaseAndDisk(@TempDir Path world) throws IOException {
        Path file = writeRegion(world, "r.0.0.mca", stoneRoot());
        ColumnProbe fromDisk = AnvilPrefilter.probeColumn(world, null, 0, 0, 0, 15);
        assertNotNull(fromDisk);
        makeResident(file);
        ColumnProbe fromLease = AnvilPrefilter.probeColumn(world, "", 0, 0, 0, 15);
        assertNotNull(fromLease);
        assertEquals(fromDisk.minY(), fromLease.minY());
        assertEquals(fromDisk.maxY(), fromLease.maxY());
        assertEquals(fromDisk.heightmapTopY(), fromLease.heightmapTopY());
    }

    @Test
    @DisplayName("probeColumnAsync short-circuits invalid input and serves real requests on the pool")
    void probeColumnAsync(@TempDir Path world) throws IOException {
        CompletableFuture<ColumnProbe> nullWorld = AnvilPrefilter.probeColumnAsync(null, "", 0, 0, 0, 15);
        assertTrue(nullWorld.isDone());
        assertNull(nullWorld.join());
        CompletableFuture<ColumnProbe> inverted = AnvilPrefilter.probeColumnAsync(world, "", 0, 0, 15, 0);
        assertTrue(inverted.isDone());
        assertNull(inverted.join());

        writeRegion(world, "r.0.0.mca", stoneRoot());
        long requestsBefore = AnvilPrefilter.columnProbeRequests();
        ColumnProbe probe = AnvilPrefilter.probeColumnAsync(world, null, 0, 0, 0, 15).join();
        assertNotNull(probe);
        assertEquals(0, probe.minY());
        assertTrue(AnvilPrefilter.columnProbeRequests() > requestsBefore);
        assertTrue(AnvilPrefilter.columnProbeGroups() > 0);
    }
}
