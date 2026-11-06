package io.github.dailystruggle.anvil;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void regionDirectoryFor(@TempDir Path world) throws IOException {
        assertEquals(world.resolve("region"), RegionFileResolver.regionDirectoryFor(world, null));
        assertEquals(world.resolve("region"), RegionFileResolver.regionDirectoryFor(world, ""));
        assertEquals(world.resolve("DIM-1").resolve("region"),
                RegionFileResolver.regionDirectoryFor(world, "DIM-1"));
        assertEquals(world.resolve("DIM1").resolve("region"),
                RegionFileResolver.regionDirectoryFor(world, "DIM1"));

        Path unifiedOverworld = Files.createDirectories(
                world.resolve("dimensions").resolve("minecraft").resolve("overworld").resolve("region"));
        Path unifiedNether = Files.createDirectories(
                world.resolve("dimensions").resolve("minecraft").resolve("the_nether").resolve("region"));
        Path unifiedEnd = Files.createDirectories(
                world.resolve("dimensions").resolve("minecraft").resolve("the_end").resolve("region"));

        assertEquals(unifiedOverworld, RegionFileResolver.regionDirectoryFor(world, null));
        assertEquals(unifiedOverworld, RegionFileResolver.regionDirectoryFor(world, ""));
        assertEquals(unifiedNether, RegionFileResolver.regionDirectoryFor(world, "DIM-1"));
        assertEquals(unifiedEnd, RegionFileResolver.regionDirectoryFor(world, "DIM1"));
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

    @Test
    @DisplayName("listRegionCoords lists and deduplicates coordinates across registered formats")
    void listRegionCoordsAcrossFormats(@TempDir Path world) throws IOException {
        Path regionDir = Files.createDirectories(world.resolve("region"));
        Files.write(regionDir.resolve("r.0.0.mca"), new byte[8]);
        Files.write(regionDir.resolve("r.0.0.linear"), new byte[8]); // Duplicate coord, different format
        Files.write(regionDir.resolve("r.-3.12.linear"), new byte[8]);
        Files.write(regionDir.resolve("r.5.5.mca"), new byte[8]);
        Files.write(regionDir.resolve("r.1.1.linear"), new byte[0]); // Zero-length stub skipped
        Files.write(regionDir.resolve("r.9.9.unregistered"), new byte[8]); // Unregistered extension skipped

        List<int[]> coords = RegionFileResolver.listRegionCoords(regionDir);
        Set<String> got = coords.stream().map(c -> c[0] + "," + c[1]).collect(Collectors.toSet());
        assertEquals(Set.of("0,0", "-3,12", "5,5"), got);
    }

    @Test
    @DisplayName("resolve defaults to .linear when directory profile is pure LINEAR")
    void resolveDefaultsToLinearInLinearWorld(@TempDir Path world) throws IOException {
        Path regionDir = Files.createDirectories(world.resolve("region"));
        Files.write(regionDir.resolve("r.0.0.linear"), new byte[8]);

        RegionFileResolver.invalidateMemo();
        // Unallocated chunk (32, 32) -> region r.1.1
        RegionFileResolver.ResolvedRegion res = RegionFileResolver.resolve(world, "", 32, 32);
        assertNotNull(res);
        assertEquals(regionDir.resolve("r.1.1.linear"), res.path());
        assertSame(LinearRegionReader.INSTANCE, res.reader());
    }

    @Test
    @DisplayName("Fast path in pure MCA directory does not probe .linear")
    void fastPathPureMcaDirectory(@TempDir Path world) throws IOException {
        Path regionDir = Files.createDirectories(world.resolve("region"));
        Path mca = Files.write(regionDir.resolve("r.0.0.mca"), new byte[8]);

        RegionFileResolver.invalidateMemo();
        RegionFileResolver.ResolvedRegion res = RegionFileResolver.resolveExisting(world, "", 0, 0);
        assertNotNull(res);
        assertEquals(mca, res.path());
        assertSame(AnvilReader.INSTANCE, res.reader());

        // Absent chunk in pure MCA directory returns null directly
        assertNull(RegionFileResolver.resolveExisting(world, "", 64, 64));
    }

    @Test
    @DisplayName("patchChunkBlocks performs storage-agnostic read-modify-write across .lca and .mca regions")
    void patchChunkBlocksAcrossLcaAndMca(@TempDir Path tmp) throws IOException {
        java.util.LinkedHashMap<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("DataVersion", 4671);
        root.put("xPos", 0);
        root.put("zPos", 0);
        root.put("Status", "minecraft:full");

        java.util.LinkedHashMap<String, Object> sec4 = new java.util.LinkedHashMap<>();
        sec4.put("Y", (byte) 4);
        java.util.LinkedHashMap<String, Object> bs = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Object> airEntry = new java.util.LinkedHashMap<>();
        airEntry.put("Name", "minecraft:air");
        java.util.LinkedHashMap<String, Object> stoneEntry = new java.util.LinkedHashMap<>();
        stoneEntry.put("Name", "minecraft:stone");
        bs.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(airEntry, stoneEntry)));
        int[] yzx = new int[4096];
        yzx[PackedPaletteDecoder.entryIndex(2, 0, 2)] = 1; // (2, 64, 2) = stone
        bs.put("data", LcaSectionCodec.packBlockLongs(yzx, 2));
        sec4.put("block_states", bs);
        root.put("sections", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of(sec4)));
        byte[] rawNbt = Nbt.writeNamedRoot("", root);

        List<RegionFileReader.BlockPatch> patches = List.of(
                new RegionFileReader.BlockPatch(2, 64, 2, "minecraft:oak_leaves[distance=1,persistent=false]"),
                new RegionFileReader.BlockPatch(2, 68, 2, "minecraft:oak_leaves[distance=1,persistent=false]"));

        // 1. Test on .lca world
        Path lcaWorld = tmp.resolve("lca-world");
        Path lcaRegionDir = Files.createDirectories(lcaWorld.resolve("region"));
        try (LcaRegionFileStorage storage = new LcaRegionFileStorage(lcaRegionDir)) {
            storage.writeChunkNbtBytes(0, 0, rawNbt);
        }
        RegionFormatDetector.INSTANCE.invalidateCache();
        RegionFileResolver.invalidateMemo();

        assertTrue(RegionFileResolver.patchChunkBlocks(
                lcaWorld, "", 0, 0, patches, state -> state != null && state.contains("air")));
        RegionFileResolver.ResolvedRegion resolvedLca =
                RegionFileResolver.resolveExisting(lcaWorld, "", 0, 0);
        assertNotNull(resolvedLca);
        AnvilChunkView lcaView =
                LcaRegionReader.readChunkView(Files.readAllBytes(resolvedLca.path()), 0, 0);
        assertNotNull(lcaView);
        assertEquals("minecraft:stone", lcaView.blockIdAt(2, 64, 2),
                "Non-replaceable stone block must be preserved during .lca RMW");
        assertEquals("minecraft:oak_leaves", lcaView.blockIdAt(2, 68, 2),
                "Replaceable air block must be updated to oak_leaves during .lca RMW");

        // 2. Test on .mca world
        Path mcaWorld = tmp.resolve("mca-world");
        Path mcaRegionDir = Files.createDirectories(mcaWorld.resolve("region"));
        byte[] lcaBytes = Files.readAllBytes(lcaRegionDir.resolve("r.0.0.lca"));
        try (LcaRegionFileStorage cleanLca = new LcaRegionFileStorage(tmp.resolve("clean-lca"))) {
            cleanLca.writeChunkNbtBytes(0, 0, rawNbt);
            lcaBytes = Files.readAllBytes(tmp.resolve("clean-lca").resolve("r.0.0.lca"));
        }
        Files.write(mcaRegionDir.resolve("r.0.0.mca"), LcaRegionWriter.transcodeToMca(lcaBytes));
        RegionFormatDetector.INSTANCE.invalidateCache();
        RegionFileResolver.invalidateMemo();

        assertTrue(RegionFileResolver.patchChunkBlocks(
                mcaWorld, "", 0, 0, patches, state -> state != null && state.contains("air")));
        RegionFileResolver.ResolvedRegion resolvedMca =
                RegionFileResolver.resolveExisting(mcaWorld, "", 0, 0);
        assertNotNull(resolvedMca);
        AnvilChunkView mcaView =
                AnvilReader.readChunkView(Files.readAllBytes(resolvedMca.path()), 0, 0);
        assertNotNull(mcaView);
        assertEquals("minecraft:stone", mcaView.blockIdAt(2, 64, 2),
                "Non-replaceable stone block must be preserved during .mca RMW");
        assertEquals("minecraft:oak_leaves", mcaView.blockIdAt(2, 68, 2),
                "Replaceable air block must be updated to oak_leaves during .mca RMW");

        // 3. Edge cases: new section creation, void_air force-clearing, no-op duplicate patch,
        // empty patches, unallocated chunk, and null worldFolder.
        List<RegionFileReader.BlockPatch> extraPatches = List.of(
                new RegionFileReader.BlockPatch(2, 64, 2, "minecraft:void_air"), // force-clear stone -> air
                new RegionFileReader.BlockPatch(2, 68, 2, "minecraft:oak_leaves[distance=1,persistent=false]"), // already same state
                new RegionFileReader.BlockPatch(5, 80, 5, "oak_log[axis=y]")); // new section Y=5 without namespace prefix
        assertTrue(RegionFileResolver.patchChunkBlocks(
                lcaWorld, "", 0, 0, extraPatches, state -> state != null && state.contains("air")));
        assertTrue(RegionFileResolver.patchChunkBlocks(
                mcaWorld, "", 0, 0, extraPatches, state -> state != null && state.contains("air")));
        assertTrue(RegionFileResolver.patchChunkBlocks(
                lcaWorld, "", 0, 0, List.of(), null));
        assertTrue(RegionFileResolver.patchChunkBlocks(
                mcaWorld, "", 0, 0, List.of(), null));
        assertFalse(RegionFileResolver.patchChunkBlocks(
                lcaWorld, "", 7, 7, extraPatches, null));
        assertFalse(RegionFileResolver.patchChunkBlocks(
                mcaWorld, "", 7, 7, extraPatches, null));
        assertFalse(RegionFileResolver.patchChunkBlocks(
                null, "", 0, 0, extraPatches, null));
        assertFalse(RegionFileResolver.patchChunkBlocks(
                tmp.resolve("non-existent-world"), "", 0, 0, extraPatches, null));
        assertFalse(dummyReader().patchChunkBlocks(resolvedMca.path(), 0, 0, extraPatches, null));

        AnvilChunkView mcaAfterExtra =
                AnvilReader.readChunkView(Files.readAllBytes(resolvedMca.path()), 0, 0);
        assertNotNull(mcaAfterExtra);
        assertEquals("minecraft:air", mcaAfterExtra.blockIdAt(2, 64, 2));
        assertEquals("minecraft:oak_log", mcaAfterExtra.blockIdAt(5, 80, 5));
    }
}
