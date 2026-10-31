package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.anvil.AnvilReader;
import io.github.dailystruggle.rtp.anvil.BiomePaletteSection;
import io.github.dailystruggle.rtp.anvil.ColumnProbe;
import io.github.dailystruggle.rtp.anvil.PaletteSection;
import io.github.dailystruggle.rtp.anvil.RegionFileReader;
import io.github.dailystruggle.rtp.anvil.RegionFormatRegistry;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * World-name and region-folder resolution, shape bounds, disk sampling and the shape-only fallback of
 * {@link PregenBiomeExtractor} (ADR-104 §4.3, ADR-077). Region files are synthetic and live in a temp
 * directory; no chunk is ever loaded (S-005).
 */
@DisplayName("ADR-104 / ADR-077: PregenBiomeExtractor resolution, bounds and sampling branches")
class PregenBiomeExtractorBranchTest {

    private static final String TEST_EXT = ".pbxtest";

    @TempDir
    Path tempDir;

    private RTPServerAccessor previousAccessor;

    @BeforeEach
    void saveAccessor() {
        previousAccessor = RTPRuntime.serverAccessor();
        RTPRuntime.setServerAccessor(null);
    }

    @AfterEach
    void restore() {
        RTPRuntime.setServerAccessor(previousAccessor);
        RegionFormatRegistry.reset();
    }

    /** Native world exposing Bukkit's {@code getWorldFolder()} for the reflective lookup. */
    public static final class FolderWorld {
        private final File folder;

        public FolderWorld(File folder) {
            this.folder = folder;
        }

        public File getWorldFolder() {
            return folder;
        }
    }

    /** Native world whose {@code getWorldFolder()} is not a {@link File}. */
    public static final class NotFileWorld {
        public Object getWorldFolder() {
            return "not a file";
        }
    }

    /** Reader for {@link #TEST_EXT}: chunk column {@code lx == 3} throws, {@code lx == 6} is absent. */
    private static class PatternReader implements RegionFileReader {
        @Override
        public AnvilReader.ChunkEntry readChunk(byte[] regionBytes, int rx, int rz) throws IOException {
            if (rx == 4) throw new IOException("truncated chunk");
            if (rx == 5) return rz == 1 ? null : new AnvilReader.ChunkEntry(2, 0, null);
            LinkedHashMap<String, Object> root = new LinkedHashMap<>();
            root.put("rcx", rx);
            root.put("rcz", rz);
            return new AnvilReader.ChunkEntry(2, 16, root);
        }

        @Override
        public boolean isChunkGenerated(byte[] regionBytes, int rx, int rz) {
            if (rx == 3) throw new IllegalStateException("corrupt header");
            return rx != 6;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RTPWorld<Object> world(String name, Object nativeWorld) {
        RTPWorld w = mock(RTPWorld.class);
        when(w.name()).thenReturn(name);
        when(w.world()).thenReturn(nativeWorld);
        return w;
    }

    private static Region region(RTPWorld<?> world, String configuredWorldName) {
        Region r = mock(Region.class);
        doReturnWorld(r, world);
        r.configuredWorldName = configuredWorldName;
        return r;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void doReturnWorld(Region r, RTPWorld<?> world) {
        when((RTPWorld) r.getWorld()).thenReturn(world);
    }

    private static MemoryShape<?> shapeAt(int x, int z, String biome) {
        MemoryShape<?> shape = mock(MemoryShape.class);
        when(shape.getRange()).thenReturn(10L);
        when(shape.locationToXZ(anyLong())).thenReturn(new int[]{x, z});
        when(shape.biomeAt(anyInt(), anyInt())).thenReturn(biome);
        return shape;
    }

    private static String relativeToCwd(Path target) {
        Path cwd = Path.of("").toAbsolutePath();
        try {
            return cwd.relativize(target.toAbsolutePath()).toString();
        } catch (IllegalArgumentException differentRoots) {
            assumeTrue(false, "temp dir and working directory on different roots");
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // World name
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("resolveWorldName: live name, configured fallback for missing/empty/throwing worlds, then 'world'")
    void resolveWorldName() {
        assertEquals("world", PregenBiomeExtractor.resolveWorldName(null));
        assertEquals("live", PregenBiomeExtractor.resolveWorldName(region(world("live", null), "cfg")));
        assertEquals("cfg", PregenBiomeExtractor.resolveWorldName(region(world("", null), "cfg")));
        assertEquals("cfg", PregenBiomeExtractor.resolveWorldName(region(world(null, null), "cfg")));
        assertEquals("cfg", PregenBiomeExtractor.resolveWorldName(region(null, "cfg")));
        assertEquals("world", PregenBiomeExtractor.resolveWorldName(region(null, null)));
        assertEquals("world", PregenBiomeExtractor.resolveWorldName(region(world("", null), "")));

        Region throwing = mock(Region.class);
        when(throwing.getWorld()).thenThrow(new IllegalStateException("unloaded"));
        throwing.configuredWorldName = "cfg";
        assertEquals("cfg", PregenBiomeExtractor.resolveWorldName(throwing));
    }

    // ---------------------------------------------------------------------------------------------
    // Shape bounds
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("shapeChunkBounds: empty range and all-null samples give null; malformed samples skipped; last location included")
    void shapeChunkBounds() {
        MemoryShape<?> empty = mock(MemoryShape.class);
        when(empty.getRange()).thenReturn(0L);
        assertNull(PregenBiomeExtractor.shapeChunkBounds(empty, 16));

        MemoryShape<?> blank = mock(MemoryShape.class);
        when(blank.getRange()).thenReturn(4L);
        assertNull(PregenBiomeExtractor.shapeChunkBounds(blank, 4), "every sample null");

        MemoryShape<?> mixed = mock(MemoryShape.class);
        when(mixed.getRange()).thenReturn(4L);
        when(mixed.locationToXZ(0L)).thenReturn(null);
        when(mixed.locationToXZ(1L)).thenReturn(new int[]{99});
        when(mixed.locationToXZ(2L)).thenReturn(new int[]{5, -3});
        when(mixed.locationToXZ(3L)).thenReturn(new int[]{-2, 7});
        assertArrayEquals(new int[]{-2, -3, 5, 7}, PregenBiomeExtractor.shapeChunkBounds(mixed, 4));

        MemoryShape<?> lastMissing = mock(MemoryShape.class);
        when(lastMissing.getRange()).thenReturn(100L);
        when(lastMissing.locationToXZ(0L)).thenReturn(new int[]{1, 2});
        when(lastMissing.locationToXZ(99L)).thenReturn(new int[]{1});
        assertArrayEquals(new int[]{1, 2, 1, 2}, PregenBiomeExtractor.shapeChunkBounds(lastMissing, 0),
                "samples clamp to 1 so only location 0 is sampled");
    }

    // ---------------------------------------------------------------------------------------------
    // Region folder
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("resolveRegionFolder uses the live world folder only when it is a File with a region directory")
    void resolveRegionFolderViaServerAccessor() throws IOException {
        Path okWorld = tempDir.resolve("ok_world");
        Path okRegion = Files.createDirectories(okWorld.resolve("region"));
        Path bareWorld = Files.createDirectories(tempDir.resolve("bare_world"));

        RTPServerAccessor accessor = mock(RTPServerAccessor.class);
        when(accessor.getRTPWorld(anyString())).thenReturn(null);
        when(accessor.getRTPWorld("pbx_no_native")).thenAnswer(inv -> world("pbx_no_native", null));
        when(accessor.getRTPWorld("pbx_no_method")).thenAnswer(inv -> world("pbx_no_method", "plain string"));
        when(accessor.getRTPWorld("pbx_not_file")).thenAnswer(inv -> world("pbx_not_file", new NotFileWorld()));
        when(accessor.getRTPWorld("pbx_bare")).thenAnswer(inv -> world("pbx_bare", new FolderWorld(bareWorld.toFile())));
        when(accessor.getRTPWorld("pbx_ok")).thenAnswer(inv -> world("pbx_ok", new FolderWorld(okWorld.toFile())));
        when(accessor.getRTPWorld("pbx_throws")).thenThrow(new IllegalStateException("accessor down"));
        RTPRuntime.setServerAccessor(accessor);

        assertNull(PregenBiomeExtractor.resolveRegionFolder(null));
        assertEquals(okRegion, PregenBiomeExtractor.resolveRegionFolder(region(world("pbx_ok", null), null)));
        for (String name : List.of("pbx_unknown", "pbx_no_native", "pbx_no_method", "pbx_not_file", "pbx_bare", "pbx_throws")) {
            assertNull(PregenBiomeExtractor.resolveRegionFolder(region(world(name, null), null)), name);
        }
    }

    @Test
    @DisplayName("resolveRegionFolder searches <world>/region, then <world>/dimensions/minecraft/overworld/region")
    void resolveRegionFolderOnDisk() throws IOException {
        Path classic = Files.createDirectories(tempDir.resolve("classic").resolve("region"));
        Path modern = Files.createDirectories(tempDir.resolve("modern").resolve("dimensions/minecraft/overworld/region"));
        Path empty = Files.createDirectories(tempDir.resolve("empty"));

        Path foundClassic = PregenBiomeExtractor.resolveRegionFolder(region(world(relativeToCwd(classic.getParent()), null), null));
        assertNotNull(foundClassic);
        assertTrue(Files.isSameFile(classic, foundClassic));

        Path foundModern = PregenBiomeExtractor.resolveRegionFolder(region(world(relativeToCwd(modern.getParent().getParent().getParent().getParent()), null), null));
        assertNotNull(foundModern);
        assertTrue(Files.isSameFile(modern, foundModern));

        assertNull(PregenBiomeExtractor.resolveRegionFolder(region(world(relativeToCwd(empty), null), null)),
                "a world folder without region directories resolves to nothing");
    }

    // ---------------------------------------------------------------------------------------------
    // Disk sampling
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("sampleRegionFile clips to bounds, skips absent/throwing chunks, prefers the column probe for .mca and readChunk+toView for custom readers, then the shape, then plains")
    void sampleRegionFileBranches() {
        RegionFormatRegistry.register(".mca", new PatternReader());
        RegionFormatRegistry.register(TEST_EXT, new PatternReader());
        ColumnProbe desert = mock(ColumnProbe.class);
        when(desert.hasHeightmap()).thenReturn(true);
        when(desert.heightmapTopY()).thenReturn(70);
        when(desert.biomeAt(70)).thenReturn("minecraft:desert");
        ColumnProbe noBiome = mock(ColumnProbe.class);
        when(noBiome.hasHeightmap()).thenReturn(true);
        when(noBiome.heightmapTopY()).thenReturn(70);
        ColumnProbe noHeightmap = new ColumnProbe(0, 255, Integer.MIN_VALUE, List.of(), List.of());

        long[] heightmap70 = new long[37];
        heightmap70[19] = 7L << 27; // columnIndex(8, 8) = 136 -> longIdx 19, slot 3; minHeight 64 + 7 - 1 = 70
        List<PaletteSection> blockSections = List.of(new PaletteSection(4, List.of("minecraft:stone"), null));
        AnvilChunkView desertView = new AnvilChunkView(
                3465, blockSections, heightmap70, List.of(new BiomePaletteSection(4, List.of("minecraft:desert"), null)));
        AnvilChunkView noBiomeView = new AnvilChunkView(3465, blockSections, heightmap70, List.of());
        AnvilChunkView noHeightmapView = new AnvilChunkView(3465, blockSections, List.of());

        MemoryShape<?> fallback = mock(MemoryShape.class);
        when(fallback.biomeAt(anyInt(), anyInt())).thenAnswer(inv -> switch ((int) inv.getArgument(0)) {
            case 2 * 16 + 8 -> "minecraft:forest";
            case 4 * 16 + 8 -> "";
            default -> null;
        });

        byte[] bytes = new byte[16];
        try (MockedStatic<AnvilReader> anvil = mockStatic(AnvilReader.class)) {
            anvil.when(() -> AnvilReader.readColumnProbe(any(byte[].class), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                    .thenAnswer(inv -> {
                        int lx = inv.getArgument(2);
                        int lz = inv.getArgument(3);
                        if (lx == 1) return lz == 1 ? desert : noBiome;
                        if (lx == 2) return noHeightmap;
                        if (lx == 4) throw new IOException("truncated chunk");
                        return null;
                    });
            anvil.when(() -> AnvilReader.toView(any()))
                    .thenAnswer(inv -> {
                        Map<?, ?> root = inv.getArgument(0);
                        int lx = (Integer) root.get("rcx");
                        int lz = (Integer) root.get("rcz");
                        if (lx == 1) return lz == 1 ? desertView : noBiomeView;
                        if (lx == 2) return lz == 1 ? noHeightmapView : null;
                        return null;
                    });

            List<String> expectedWithFallback = List.of(
                    "1,1=minecraft:desert", "2,1=minecraft:forest", "4,1=minecraft:plains", "5,1=minecraft:plains",
                    "1,2=minecraft:plains", "2,2=minecraft:forest", "4,2=minecraft:plains", "5,2=minecraft:plains");
            List<String> expectedNoFallback = List.of(
                    "minecraft:desert", "minecraft:plains", "minecraft:plains", "minecraft:plains",
                    "minecraft:plains", "minecraft:plains", "minecraft:plains", "minecraft:plains");

            // 1. .mca format uses AnvilReader.readColumnProbe
            List<String> seenMca = new ArrayList<>();
            int generatedMca = PregenBiomeExtractor.sampleRegionFile(bytes, ".mca", 0, 0, 1, 1, 6, 2, fallback,
                    (cx, cz, biome) -> seenMca.add(cx + "," + cz + "=" + biome));
            assertEquals(8, generatedMca, "columns 1, 2, 4, 5 on rows 1 and 2 (.mca)");
            assertEquals(expectedWithFallback, seenMca);

            List<String> noFallbackMca = new ArrayList<>();
            PregenBiomeExtractor.sampleRegionFile(bytes, ".mca", 0, 0, 1, 1, 6, 2, null,
                    (cx, cz, biome) -> noFallbackMca.add(biome));
            assertEquals(expectedNoFallback, noFallbackMca);

            // 2. Non-Anvil custom RegionFileReader (TEST_EXT) uses reader.readChunk + AnvilReader.toView
            List<String> seenCustom = new ArrayList<>();
            int generatedCustom = PregenBiomeExtractor.sampleRegionFile(bytes, TEST_EXT, 0, 0, 1, 1, 6, 2, fallback,
                    (cx, cz, biome) -> seenCustom.add(cx + "," + cz + "=" + biome));
            assertEquals(8, generatedCustom, "columns 1, 2, 4, 5 on rows 1 and 2 (TEST_EXT)");
            assertEquals(expectedWithFallback, seenCustom);

            List<String> noFallbackCustom = new ArrayList<>();
            PregenBiomeExtractor.sampleRegionFile(bytes, TEST_EXT, 0, 0, 1, 1, 6, 2, null,
                    (cx, cz, biome) -> noFallbackCustom.add(biome));
            assertEquals(expectedNoFallback, noFallbackCustom);

            AtomicInteger accepted = new AtomicInteger();
            int early = PregenBiomeExtractor.sampleRegionFile(bytes, TEST_EXT, 0, 0, 0, 0, 31, 31, null,
                    (cx, cz, biome) -> accepted.incrementAndGet() < 0);
            assertEquals(1, early, "a refusing sink stops the file after the first chunk");
            assertEquals(1, accepted.get());
        }

        assertEquals(0, PregenBiomeExtractor.sampleRegionFile(null, ".mca", 0, 0, 0, 0, 31, 31, null, (cx, cz, b) -> true),
                "null bytes: nothing generated");
    }

    @Test
    @DisplayName("extensionOf falls back to .mca for a dot-less name")
    void extensionOf() {
        assertEquals(".mca", PregenBiomeExtractor.extensionOf(Path.of("region")));
        assertEquals(".mca", PregenBiomeExtractor.extensionOf(Path.of("r.0.0.mca")));
    }

    @Test
    @DisplayName("extract scans region files within the margin-padded shape bounds, caps samples and keeps counting")
    void extractFromDisk() throws IOException {
        RegionFormatRegistry.register(TEST_EXT, new PatternReader() {
            @Override
            public boolean isChunkGenerated(byte[] regionBytes, int rx, int rz) {
                return true;
            }
        });
        Path worldDir = tempDir.resolve("disk_world");
        Path regionDir = Files.createDirectories(worldDir.resolve("region"));
        Files.write(regionDir.resolve("r.0.0" + TEST_EXT), new byte[64]);
        Files.write(regionDir.resolve("r.-1.0.mca"), new byte[8192]);

        RTPServerAccessor accessor = mock(RTPServerAccessor.class);
        when(accessor.getRTPWorld("pbx_disk")).thenAnswer(inv -> world("pbx_disk", new FolderWorld(worldDir.toFile())));
        RTPRuntime.setServerAccessor(accessor);

        Region r = region(world("pbx_disk", null), null);
        r.shape = shapeAt(0, 0, "minecraft:taiga");

        PregenBiomeExtractor.ExtractionResult result = PregenBiomeExtractor.extract(r, 10);
        int side = PregenBiomeExtractor.BOUNDS_MARGIN_CHUNKS + 1; // chunks 0..margin inside r.0.0
        assertEquals(2, result.regionFilesScanned(), "r.0.0 and r.-1.0 exist; r.0.-1 and r.-1.-1 are skipped");
        assertEquals(side * side, result.totalGeneratedChunks(), "the empty .mca contributes no chunks");
        assertEquals(10, result.chunks().size(), "capped at maxChunks");
        assertEquals(List.of("minecraft:plains", "minecraft:taiga"), result.palette());
        for (PregenBiomeExtractor.ChunkBiomeSample s : result.chunks()) {
            assertEquals(1, s.biomeIndex());
            assertTrue(s.cx() >= 0 && s.cx() <= PregenBiomeExtractor.BOUNDS_MARGIN_CHUNKS, s.toString());
        }

        Region noShape = region(world("pbx_disk", null), null);
        PregenBiomeExtractor.ExtractionResult wide = PregenBiomeExtractor.extract(noShape, 5);
        assertEquals(5, wide.chunks().size());
        assertEquals(List.of("minecraft:plains"), wide.palette(), "no shape fallback: probe-less chunks are plains");
        assertEquals(32 * 32, wide.totalGeneratedChunks(), "default -128..128 bounds cover all of r.0.0");
    }

    // ---------------------------------------------------------------------------------------------
    // Shape-only fallback
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("extract without region files: null region, shapeless region, and shape sampling with biome fallbacks")
    void extractFromMemoryShape() {
        PregenBiomeExtractor.ExtractionResult none = PregenBiomeExtractor.extract(null, 10);
        assertEquals(List.of("minecraft:unknown"), none.palette());
        assertTrue(none.chunks().isEmpty());

        Region shapeless = region(world("pbx_missing_world", null), null);
        PregenBiomeExtractor.ExtractionResult plain = PregenBiomeExtractor.extract(shapeless, 10);
        assertEquals(List.of("minecraft:plains"), plain.palette());
        assertEquals(0, plain.totalGeneratedChunks());

        MemoryShape<?> shape = mock(MemoryShape.class);
        when(shape.getRange()).thenReturn(0L); // no bounds: default -128..128, stride 4
        when(shape.contains(anyInt(), anyInt())).thenAnswer(inv -> (int) inv.getArgument(0) >= 0);
        AtomicInteger calls = new AtomicInteger();
        when(shape.biomeAt(anyInt(), anyInt())).thenAnswer(inv -> switch (calls.getAndIncrement() % 4) {
            case 0 -> "minecraft:desert";
            case 1 -> null;
            case 2 -> "";
            default -> "minecraft:jungle";
        });
        Region shaped = region(world("pbx_missing_world", null), null);
        shaped.shape = shape;

        PregenBiomeExtractor.ExtractionResult sampled = PregenBiomeExtractor.extract(shaped, 6);
        assertEquals(6, sampled.chunks().size(), "capped at maxChunks");
        assertEquals(6, sampled.totalGeneratedChunks());
        assertEquals(0, sampled.regionFilesScanned());
        assertEquals(List.of("minecraft:plains", "minecraft:desert", "minecraft:jungle"), sampled.palette());
        List<Integer> indices = sampled.chunks().stream().map(PregenBiomeExtractor.ChunkBiomeSample::biomeIndex).toList();
        assertEquals(List.of(1, 0, 0, 2, 1, 0), indices);
        for (PregenBiomeExtractor.ChunkBiomeSample s : sampled.chunks()) {
            assertTrue(s.cx() * 16 + 8 >= 0, "only contained chunks are sampled");
            assertEquals(-128, s.cz(), "first row only");
        }
    }
}
