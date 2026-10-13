package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ADR-077 - RegionFileResolver path resolution, memo and directory listing")
class RegionFileResolverTest {

    private long savedWindowMillis;

    @BeforeEach
    void setUp() {
        savedWindowMillis = AnvilRegionByteCache.revalidateIntervalMillis();
        RegionFormatRegistry.reset();
        RegionFileResolver.invalidateMemo();
    }

    @AfterEach
    void tearDown() {
        AnvilRegionByteCache.setRevalidateIntervalMillis(savedWindowMillis);
        RegionFormatRegistry.reset();
        RegionFileResolver.invalidateMemo();
    }

    private static RegionFileReader dummyReader() {
        return new RegionFileReader() {
            @Override
            public AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int rx, int rz) {
                return null;
            }

            @Override
            public boolean isChunkGenerated(byte[] regionBytes, int rx, int rz) {
                return false;
            }
        };
    }

    @Test
    @DisplayName("regionDirectoryFor maps null/empty to the overworld and nests dimension subpaths")
    void regionDirectoryFor(@TempDir Path world) {
        assertEquals(world.resolve("region"), RegionFileResolver.regionDirectoryFor(world, null));
        assertEquals(world.resolve("region"), RegionFileResolver.regionDirectoryFor(world, ""));
        assertEquals(world.resolve("DIM-1").resolve("region"),
                RegionFileResolver.regionDirectoryFor(world, "DIM-1"));
    }

    @Test
    @DisplayName("resolve maps negative chunk coordinates to the floor region and defaults to .mca")
    void resolveNegativeCoordinatesInDimension(@TempDir Path world) {
        RegionFileResolver.ResolvedRegion res = RegionFileResolver.resolve(world, "DIM1", -1, -33);
        assertEquals(world.resolve("DIM1").resolve("region").resolve("r.-1.-2.mca"), res.path());
        assertSame(AnvilReader.INSTANCE, res.reader());
    }

    @Test
    @DisplayName("A registered format without a file on disk falls through to an existing .mca")
    void registeredFormatMissingFallsBackToMca(@TempDir Path world) throws IOException {
        RegionFormatRegistry.register(".srf", dummyReader());
        Path regionDir = Files.createDirectories(world.resolve("region"));
        Path mca = Files.write(regionDir.resolve("r.0.0.mca"), new byte[8]);
        AnvilRegionByteCache.setRevalidateIntervalMillis(0L);

        RegionFileResolver.ResolvedRegion res = RegionFileResolver.resolveExisting(world, "", 0, 0);
        assertNotNull(res);
        assertEquals(mca, res.path());
        assertSame(AnvilReader.INSTANCE, res.reader());
    }

    @Test
    @DisplayName("resolveExisting memoizes presence within the window and re-checks after invalidation")
    void resolveExistingMemoizesPresence(@TempDir Path world) throws IOException {
        AnvilRegionByteCache.setRevalidateIntervalMillis(60_000L);
        Path regionDir = Files.createDirectories(world.resolve("region"));
        Path mca = Files.write(regionDir.resolve("r.0.0.mca"), new byte[8]);

        RegionFileResolver.ResolvedRegion first = RegionFileResolver.resolveExisting(world, "", 0, 0);
        assertNotNull(first);
        assertEquals(mca, first.path());

        Files.delete(mca);
        assertSame(first, RegionFileResolver.resolveExisting(world, "", 0, 0),
                "presence is memoized within the window");

        RegionFileResolver.invalidateMemo();
        assertNull(RegionFileResolver.resolveExisting(world, "", 0, 0));
    }

    @Test
    @DisplayName("resolveExisting re-stats an expired memo entry")
    void resolveExistingExpiredMemoRechecks(@TempDir Path world) throws IOException, InterruptedException {
        AnvilRegionByteCache.setRevalidateIntervalMillis(1L);
        assertNull(RegionFileResolver.resolveExisting(world, "", 0, 0));
        Path regionDir = Files.createDirectories(world.resolve("region"));
        Path mca = Files.write(regionDir.resolve("r.0.0.mca"), new byte[8]);
        Thread.sleep(5L);

        RegionFileResolver.ResolvedRegion res = RegionFileResolver.resolveExisting(world, "", 0, 0);
        assertNotNull(res);
        assertEquals(mca, res.path());
    }

    @Test
    @DisplayName("listAnvilRegionCoords returns empty for a null or missing directory")
    void listAnvilRegionCoordsMissingDirectory(@TempDir Path world) throws IOException {
        assertTrue(RegionFileResolver.listAnvilRegionCoords(null).isEmpty());
        assertTrue(RegionFileResolver.listAnvilRegionCoords(world.resolve("absent")).isEmpty());
        Path file = Files.write(world.resolve("not-a-dir"), new byte[1]);
        assertTrue(RegionFileResolver.listAnvilRegionCoords(file).isEmpty());
    }

    @Test
    @DisplayName("listAnvilRegionCoords skips malformed names and zero-length stubs")
    void listAnvilRegionCoordsFilters(@TempDir Path world) throws IOException {
        Path regionDir = Files.createDirectories(world.resolve("region"));
        Files.write(regionDir.resolve("r.0.0.mca"), new byte[8]);
        Files.write(regionDir.resolve("r.-3.12.mca"), new byte[8]);
        Files.write(regionDir.resolve("r.1.1.mca"), new byte[0]);
        Files.write(regionDir.resolve("r.a.b.mca"), new byte[8]);
        Files.write(regionDir.resolve("r.2.2.linear"), new byte[8]);

        List<int[]> coords = RegionFileResolver.listAnvilRegionCoords(regionDir);
        Set<String> got = coords.stream().map(c -> c[0] + "," + c[1]).collect(Collectors.toSet());
        assertEquals(Set.of("0,0", "-3,12"), got);
    }
}
