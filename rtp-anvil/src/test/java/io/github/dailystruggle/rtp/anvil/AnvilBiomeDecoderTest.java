package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-016 Phase 2, landing-order step 1: biome-palette decode in
 * {@link BiomePaletteSection} and the public {@link AnvilChunkView#getBiomeAt(int, int, int)} /
 * {@link AnvilChunkView#getBiomesPresent()} surface.
 *
 * <p>These tests are decoder-level only (no platform wiring, no Iris/Terra path).
 * They cover §3 of the plan (packed-palette decode with the biome-specific min-1-bit
 * rule), §4 (the two new view methods, single-entry + multi-entry palette paths,
 * out-of-window null return, missing-biomes-container fallthrough), and the §5
 * trust-model contract that the raw on-disk identifier is preserved verbatim
 * (namespaced strings like {@code iris:volcanic_ash_plains} round-trip without
 * being collapsed).
 */
@DisplayName("ADR-016 (biome) §3–§4: biome palette decoder + AnvilChunkView biome API")
class AnvilBiomeDecoderTest {

    // ----------------------------------------------------------- BiomePaletteSection unit-level

    @Test
    @DisplayName("biomeBitsPerEntry honours the biome-specific max(1, ceil(log2(size))) rule")
    void biomeBitsPerEntryRule() {
        assertAll(
                () -> assertEquals(1, BiomePaletteSection.biomeBitsPerEntry(1)),
                () -> assertEquals(1, BiomePaletteSection.biomeBitsPerEntry(2)),
                () -> assertEquals(2, BiomePaletteSection.biomeBitsPerEntry(3)),
                () -> assertEquals(2, BiomePaletteSection.biomeBitsPerEntry(4)),
                () -> assertEquals(3, BiomePaletteSection.biomeBitsPerEntry(5)),
                () -> assertEquals(3, BiomePaletteSection.biomeBitsPerEntry(8)),
                () -> assertEquals(4, BiomePaletteSection.biomeBitsPerEntry(9)),
                () -> assertEquals(4, BiomePaletteSection.biomeBitsPerEntry(16)),
                () -> assertEquals(5, BiomePaletteSection.biomeBitsPerEntry(17)));
    }

    @Test
    @DisplayName("biomeCellIndex is YZX-major across the 4×4×4 cell grid")
    void biomeCellIndexYZXMajor() {
        assertAll(
                () -> assertEquals(0, BiomePaletteSection.biomeCellIndex(0, 0, 0)),
                () -> assertEquals(1, BiomePaletteSection.biomeCellIndex(1, 0, 0)),
                () -> assertEquals(4, BiomePaletteSection.biomeCellIndex(0, 0, 1)),
                () -> assertEquals(16, BiomePaletteSection.biomeCellIndex(0, 1, 0)),
                () -> assertEquals(63, BiomePaletteSection.biomeCellIndex(3, 3, 3)));
    }

    @Test
    @DisplayName("Single-entry biome palette returns palette[0] for every block in the section")
    void singleEntryBiomePaletteReturnsPaletteZero() {
        BiomePaletteSection bs = new BiomePaletteSection(
                0, List.of("minecraft:plains"), null);
        // Block coords (not cell coords): a single-entry palette must collapse every
        // 1x1x1 block read to the sole palette entry, regardless of its 4x4x4 cell.
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    assertEquals("minecraft:plains", bs.biomeIdAt(x, y, z));
                }
            }
        }
    }

    @Test
    @DisplayName("Multi-entry 2-bit biome palette round-trips a known cell placement")
    void multiEntryBiomeRoundTrip() {
        // 3-entry palette → bits=2 → 32 cells per long → 2 longs for 64 cells.
        List<String> palette = Arrays.asList(
                "minecraft:plains", "minecraft:desert", "minecraft:forest");
        int[] cells = new int[64]; // default: all palette[0] = plains
        cells[BiomePaletteSection.biomeCellIndex(1, 0, 2)] = 1; // desert
        cells[BiomePaletteSection.biomeCellIndex(3, 3, 3)] = 2; // forest (far corner)
        long[] data = AnvilTestFixtures.packBiomeIndices(2, cells);

        BiomePaletteSection bs = new BiomePaletteSection(0, palette, data);

        // Block coord (4..7, 0..3, 8..11) maps to cell (1, 0, 2).
        assertAll("2-bit packed biome cells",
                () -> assertEquals("minecraft:plains", bs.biomeIdAt(0, 0, 0)),
                () -> assertEquals("minecraft:desert", bs.biomeIdAt(4, 0, 8)),
                () -> assertEquals("minecraft:desert", bs.biomeIdAt(7, 3, 11)),
                () -> assertEquals("minecraft:plains", bs.biomeIdAt(8, 0, 8)),  // cell (2,0,2)
                () -> assertEquals("minecraft:forest", bs.biomeIdAt(15, 15, 15))); // cell (3,3,3)
    }

    // ----------------------------------------------------------- AnvilChunkView-level (end-to-end)

    @Test
    @DisplayName("Back-compat 3-arg AnvilChunkView ctor yields no biome data (null getBiomeAt, empty getBiomesPresent)")
    void backCompatConstructorHasEmptyBiomes() {
        AnvilChunkView view = new AnvilChunkView(
                DataVersionSupport.MC_1_20_DATA_VERSION,
                List.of(),
                new long[0]);
        assertAll(
                () -> assertTrue(view.biomeSections().isEmpty()),
                () -> assertNull(view.getBiomeAt(0, 0, 0)),
                () -> assertTrue(view.getBiomesPresent().isEmpty()));
    }

    @Test
    @DisplayName("Reader round-trips a section carrying a single-entry biomes container")
    void readerDecodesSingleEntryBiomesContainer() throws IOException {
        LinkedHashMap<String, Object> sec = AnvilTestFixtures.sectionWithBiomes(
                (byte) 0,
                List.of("minecraft:stone"), null,
                List.of("minecraft:plains"), null);
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, new long[37], List.of(sec));
        byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);

        AnvilChunkView view = AnvilReader.readChunkView(region, 0, 0);
        assertNotNull(view);

        assertAll(
                () -> assertEquals(1, view.biomeSections().size()),
                () -> assertEquals(0, view.biomeSections().get(0).sectionY()),
                () -> assertEquals("minecraft:plains", view.getBiomeAt(0, 0, 0)),
                () -> assertEquals("minecraft:plains", view.getBiomeAt(15, 15, 15)),
                // sectionY=0 only covers worldY 0..15; outside Y falls through to null.
                () -> assertNull(view.getBiomeAt(0, 100, 0)),
                () -> assertNull(view.getBiomeAt(0, -1, 0)),
                () -> assertEquals(Set.of("minecraft:plains"), view.getBiomesPresent()));
    }

    @Test
    @DisplayName("Reader round-trips a multi-entry biomes container and preserves namespaced identifiers verbatim")
    void readerDecodesMultiEntryBiomesAndPreservesNamespaces() throws IOException {
        // Mixed vanilla + Iris-style identifier: the §5 trust-model promise is that
        // the on-disk string is returned verbatim, not collapsed through any Bukkit
        // enum. If this test ever starts returning PLAINS/BADLANDS, the pre-filter
        // has regressed into the live-Bukkit path.
        List<String> biomePalette = Arrays.asList(
                "minecraft:plains",
                "iris:volcanic_ash_plains",
                "minecraft:badlands");
        int[] cells = new int[64];
        cells[BiomePaletteSection.biomeCellIndex(0, 0, 0)] = 1; // iris:volcanic_ash_plains
        cells[BiomePaletteSection.biomeCellIndex(3, 3, 3)] = 2; // minecraft:badlands
        long[] biomeData = AnvilTestFixtures.packBiomeIndices(2, cells);

        LinkedHashMap<String, Object> sec = AnvilTestFixtures.sectionWithBiomes(
                (byte) 2,
                List.of("minecraft:stone"), null,
                biomePalette, biomeData);
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, new long[37], List.of(sec));
        byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);

        AnvilChunkView view = AnvilReader.readChunkView(region, 0, 0);
        assertNotNull(view);

        // Section Y=2 covers worldY 32..47.
        assertAll("multi-entry biome placement",
                () -> assertEquals("iris:volcanic_ash_plains", view.getBiomeAt(0, 32, 0)),
                () -> assertEquals("iris:volcanic_ash_plains", view.getBiomeAt(3, 35, 3)),
                () -> assertEquals("minecraft:plains",         view.getBiomeAt(4, 32, 0)),   // cell (1,0,0) → index 0 → plains
                () -> assertEquals("minecraft:badlands",       view.getBiomeAt(15, 47, 15)), // cell (3,3,3)
                () -> assertNull(view.getBiomeAt(0, 48, 0)),   // outside section Y=2
                () -> assertNull(view.getBiomeAt(0, 31, 0)));

        Set<String> present = view.getBiomesPresent();
        assertAll("getBiomesPresent union",
                () -> assertTrue(present.contains("minecraft:plains")),
                () -> assertTrue(present.contains("iris:volcanic_ash_plains")),
                () -> assertTrue(present.contains("minecraft:badlands")),
                () -> assertEquals(3, present.size()));
    }

    @Test
    @DisplayName("Section with no biomes container yields null getBiomeAt and the biomeSections list stays empty")
    void sectionWithoutBiomesContainerFallsThrough() throws IOException {
        // Pre-Phase-2 shape: block_states only, no biomes container.
        LinkedHashMap<String, Object> sec = AnvilTestFixtures.section(
                (byte) 0, List.of("minecraft:stone"), null);
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, new long[37], List.of(sec));
        byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);

        AnvilChunkView view = AnvilReader.readChunkView(region, 0, 0);
        assertNotNull(view);

        assertAll(
                () -> assertTrue(view.biomeSections().isEmpty(),
                        "a section without a biomes container must not emit a BiomePaletteSection"),
                () -> assertNull(view.getBiomeAt(0, 0, 0),
                        "no biome container → caller must fall through to live world.getBiome (§5)"),
                () -> assertTrue(view.getBiomesPresent().isEmpty()));
        // Paranoia: the block side must still decode — this guards against an edit that
        // accidentally conflates the two containers.
        assertFalse(view.sections().isEmpty());
        assertEquals("minecraft:stone", view.blockIdAt(0, 0, 0));
    }

    @Test
    @DisplayName("getBiomesPresent union spans every decoded biome section in a multi-section chunk")
    void getBiomesPresentUnionsAcrossSections() throws IOException {
        LinkedHashMap<String, Object> secA = AnvilTestFixtures.sectionWithBiomes(
                (byte) 0,
                List.of("minecraft:stone"), null,
                List.of("minecraft:plains"), null);
        LinkedHashMap<String, Object> secB = AnvilTestFixtures.sectionWithBiomes(
                (byte) 1,
                List.of("minecraft:stone"), null,
                List.of("iris:volcanic_ash_plains"), null);
        LinkedHashMap<String, Object> root = AnvilTestFixtures.chunkRoot(
                DataVersionSupport.MC_1_20_DATA_VERSION, new long[37], List.of(secA, secB));
        byte[] region = AnvilTestFixtures.writeSingleChunkRegion(root);

        AnvilChunkView view = AnvilReader.readChunkView(region, 0, 0);
        assertNotNull(view);

        assertEquals(2, view.biomeSections().size());
        assertEquals(
                Set.of("minecraft:plains", "iris:volcanic_ash_plains"),
                view.getBiomesPresent());
    }

    @Test
    @DisplayName("biomeIdAt rejects section-local coords outside 0..15")
    void biomeIdAtRejectsOutOfRangeCoords() {
        BiomePaletteSection bs = new BiomePaletteSection(
                0, List.of("minecraft:plains"), null);
        assertAll(
                () -> assertThrows(IndexOutOfBoundsException.class, () -> bs.biomeIdAt(-1, 0, 0)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> bs.biomeIdAt(16, 0, 0)),
                () -> assertThrows(IndexOutOfBoundsException.class, () -> bs.biomeIdAt(0, 0, 16)));
    }
}
