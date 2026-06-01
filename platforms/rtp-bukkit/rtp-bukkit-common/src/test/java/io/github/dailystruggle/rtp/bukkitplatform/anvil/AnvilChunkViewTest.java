package io.github.dailystruggle.rtp.bukkitplatform.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 2 of ADR-016: {@link AnvilChunkView} / {@link PaletteSection} /
 * {@link PackedPaletteDecoder} semantics.
 *
 * <p>The decoder does not yet produce a pre-filter verdict — that's Phase 3 — but it must
 * correctly translate on-disk palette-packed bytes into raw identifier strings, because
 * a silent off-by-one in this layer would cause the verdict layer to misclassify safe as
 * unsafe (or vice versa) everywhere on vanilla Spigot. These tests exercise both
 * synthetic fixtures (for precise placement assertions) and real server-produced region
 * files (for sanity parity against heights reported by live worldgen).
 */
class AnvilChunkViewTest {

    // ------------------------------------------------------------------ PackedPaletteDecoder units

    @Test
    @DisplayName("bitsPerEntry follows the vanilla max(4, ceil(log2(size))) rule")
    void bitsPerEntryVanillaRule() {
        assertAll(
                () -> assertEquals(4, PackedPaletteDecoder.bitsPerEntry(1)),
                () -> assertEquals(4, PackedPaletteDecoder.bitsPerEntry(2)),
                () -> assertEquals(4, PackedPaletteDecoder.bitsPerEntry(16)),
                () -> assertEquals(5, PackedPaletteDecoder.bitsPerEntry(17)),
                () -> assertEquals(5, PackedPaletteDecoder.bitsPerEntry(32)),
                () -> assertEquals(6, PackedPaletteDecoder.bitsPerEntry(33)),
                () -> assertEquals(6, PackedPaletteDecoder.bitsPerEntry(64)),
                () -> assertEquals(7, PackedPaletteDecoder.bitsPerEntry(65)));
    }

    @Test
    @DisplayName("entryIndex is YZX-major with y outermost and x innermost")
    void entryIndexYZXMajor() {
        assertAll(
                () -> assertEquals(0, PackedPaletteDecoder.entryIndex(0, 0, 0)),
                () -> assertEquals(1, PackedPaletteDecoder.entryIndex(1, 0, 0)),
                () -> assertEquals(16, PackedPaletteDecoder.entryIndex(0, 0, 1)),
                () -> assertEquals(256, PackedPaletteDecoder.entryIndex(0, 1, 0)),
                () -> assertEquals(4095, PackedPaletteDecoder.entryIndex(15, 15, 15)));
    }

    @Test
    @DisplayName("decode rejects paletteSize<2 because single-entry palettes have no data on disk")
    void decodeRejectsSingleEntryPalette() {
        assertThrows(IllegalArgumentException.class,
                () -> PackedPaletteDecoder.decode(new long[]{0L}, 1, 0));
    }

    // --------------------------------------------------------------------- synthetic view round-trip

    @Test
    @DisplayName("Synthetic section round-trips a known palette placement through the reader")
    void syntheticSectionRoundTripsKnownPlacement() throws IOException {
        // 2-entry palette → bits=4, so 16 entries per long, 256 longs total.
        List<String> palette = Arrays.asList("minecraft:air", "minecraft:lava");
        int[] indices = new int[4096]; // all palette[0] = air
        indices[PackedPaletteDecoder.entryIndex(7, 3, 5)] = 1;   // lava at local (7,3,5)
        indices[PackedPaletteDecoder.entryIndex(15, 15, 15)] = 1; // and at the far corner
        long[] data = AnvilTestFixtures.packIndices(4, indices);

        byte sectionY = 0;
        LinkedHashMap<String, Object> sec = AnvilTestFixtures.section(sectionY, palette, data);
        long[] heightmap = new long[37];
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, heightmap, List.of(sec));
        byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);

        AnvilChunkView view = AnvilReader.readChunkView(region, 0, 0);
        assertNotNull(view);

        assertAll("synthetic placement",
                () -> assertEquals(1, view.sections().size()),
                () -> assertEquals(sectionY, view.sections().get(0).sectionY()),
                () -> assertEquals("minecraft:air",  view.blockIdAt(0,  0,  0)),
                () -> assertEquals("minecraft:lava", view.blockIdAt(7,  3,  5)),
                () -> assertEquals("minecraft:lava", view.blockIdAt(15, 15, 15)),
                () -> assertEquals("minecraft:air",  view.blockIdAt(14, 15, 15)),
                () -> assertEquals("minecraft:air",  view.blockIdAt(8,  3,  5)));
    }

    @Test
    @DisplayName("Synthetic 17-entry palette forces bits=5 and decoder still round-trips")
    void syntheticBitsFivePalette() throws IOException {
        List<String> palette = new ArrayList<>();
        for (int i = 0; i < 17; i++) palette.add("minecraft:block_" + i);
        int[] indices = new int[4096];
        indices[PackedPaletteDecoder.entryIndex(1, 2, 3)] = 16; // highest index in 17-entry palette
        indices[PackedPaletteDecoder.entryIndex(4, 5, 6)] = 7;
        long[] data = AnvilTestFixtures.packIndices(
                PackedPaletteDecoder.bitsPerEntry(palette.size()), indices);

        LinkedHashMap<String, Object> sec = AnvilTestFixtures.section((byte) 1, palette, data);
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, new long[37], List.of(sec));
        byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);

        AnvilChunkView view = AnvilReader.readChunkView(region, 0, 0);
        assertNotNull(view);

        assertAll("bits=5 placement",
                () -> assertEquals("minecraft:block_0",  view.blockIdAt(0, 16, 0)),   // section Y=1 → worldY 16..31
                () -> assertEquals("minecraft:block_16", view.blockIdAt(1, 16 + 2, 3)),
                () -> assertEquals("minecraft:block_7",  view.blockIdAt(4, 16 + 5, 6)));
    }

    @Test
    @DisplayName("Single-entry palette returns palette[0] regardless of data presence")
    void singleEntryPaletteReturnsPaletteZero() throws IOException {
        List<String> palette = List.of("minecraft:stone");
        LinkedHashMap<String, Object> sec = AnvilTestFixtures.section((byte) -4, palette, null);
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, new long[37], List.of(sec));
        byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);

        AnvilChunkView view = AnvilReader.readChunkView(region, 0, 0);
        assertNotNull(view);
        PaletteSection ps = view.sections().get(0);
        assertNull(ps.data(), "vanilla omits data for single-entry palettes");
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    assertEquals("minecraft:stone", ps.blockIdAt(x, y, z));
                }
            }
        }
    }

    @Test
    @DisplayName("blockIdAt returns null for a Y outside the emitted section range")
    void absentSectionYieldsNull() throws IOException {
        LinkedHashMap<String, Object> sec = AnvilTestFixtures.section(
                (byte) 0, List.of("minecraft:stone"), null);
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, new long[37], List.of(sec));
        byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);

        AnvilChunkView view = AnvilReader.readChunkView(region, 0, 0);
        assertNotNull(view);

        // Section Y=0 only covers worldY 0..15.
        assertNull(view.blockIdAt(0, 100, 0));
        assertNull(view.blockIdAt(0, -1, 0));
        assertEquals("minecraft:stone", view.blockIdAt(0, 15, 0));
        assertEquals("minecraft:stone", view.blockIdAt(0, 0, 0));
    }

    // --------------------------------------------------------------------- real-fixture sanity

    @ParameterizedTest(name = "real fixture {0} decodes into a typed view with at least one section")
    @CsvSource({"1_20_R1", "1_21_R1", "26_1_R1"})
    @DisplayName("Real region files lift into an AnvilChunkView with non-empty sections and vanilla ids")
    void realFixtureTypedViewSanity(String dirName) throws IOException {
        byte[] regionBytes = loadRealFixture(dirName);
        AnvilChunkView view = AnvilReader.readChunkView(regionBytes, 0, 0);
        assertNotNull(view, "real fixture " + dirName + " must produce a view");
        assertTrue(DataVersionSupport.isSupported(view.dataVersion()),
                "view DataVersion " + view.dataVersion() + " should be in the support whitelist");
        assertTrue(view.sections().size() > 0,
                "real fixture must have at least one section");
        assertNotNull(view.motionBlockingNoLeaves(),
                "real fixture must expose MOTION_BLOCKING_NO_LEAVES");

        // Spot-check: at least one section somewhere in the chunk decodes to a non-null,
        // non-empty, namespaced identifier. We don't pin the exact material because that's
        // a function of whatever biome/biomesource the server used when producing this
        // fixture — that precision is for Phase 3 integration tests, not decoder tests.
        boolean sawNamespacedId = false;
        for (PaletteSection ps : view.sections()) {
            String id = ps.blockIdAt(0, 0, 0);
            if (id != null && id.contains(":")) {
                sawNamespacedId = true;
                break;
            }
        }
        assertTrue(sawNamespacedId,
                "at least one section in " + dirName + " should produce a namespaced block id");
    }

    // ---------------------------------------------------------------------------- helpers

    private static byte[] loadRealFixture(String dirName) throws IOException {
        String resource = "/anvil/real/" + dirName + "/r.0.0.mca";
        try (InputStream in = AnvilChunkViewTest.class.getResourceAsStream(resource)) {
            if (in == null) fail("Real fixture not found on classpath: " + resource);
            return in.readAllBytes();
        }
    }
}
