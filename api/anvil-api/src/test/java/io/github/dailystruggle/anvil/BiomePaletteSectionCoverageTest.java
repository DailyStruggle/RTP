package io.github.dailystruggle.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BiomePaletteSection unit & branch coverage")
class BiomePaletteSectionCoverageTest {

    @Test
    @DisplayName("Constructor rejects null or empty palette")
    void testConstructorValidation() {
        assertThrows(NullPointerException.class, () -> new BiomePaletteSection(0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new BiomePaletteSection(0, List.of(), null));
    }

    @Test
    @DisplayName("biomeBitsPerEntry validates palette size")
    void testBiomeBitsPerEntry() {
        assertThrows(IllegalArgumentException.class, () -> BiomePaletteSection.biomeBitsPerEntry(0));
        assertEquals(1, BiomePaletteSection.biomeBitsPerEntry(1));
        assertEquals(1, BiomePaletteSection.biomeBitsPerEntry(2));
        assertEquals(2, BiomePaletteSection.biomeBitsPerEntry(3));
        assertEquals(2, BiomePaletteSection.biomeBitsPerEntry(4));
        assertEquals(3, BiomePaletteSection.biomeBitsPerEntry(5));
        assertEquals(3, BiomePaletteSection.biomeBitsPerEntry(8));
        assertEquals(4, BiomePaletteSection.biomeBitsPerEntry(9));
    }

    @Test
    @DisplayName("biomeCellIndex validates range 0..3 and computes YZX layout")
    void testBiomeCellIndex() {
        assertEquals(0, BiomePaletteSection.biomeCellIndex(0, 0, 0));
        assertEquals((2 << 4) | (3 << 2) | 1, BiomePaletteSection.biomeCellIndex(1, 2, 3));

        assertThrows(IndexOutOfBoundsException.class, () -> BiomePaletteSection.biomeCellIndex(-1, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> BiomePaletteSection.biomeCellIndex(0, 4, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> BiomePaletteSection.biomeCellIndex(0, 0, 5));
    }

    @Test
    @DisplayName("biomeIdAt bounds checking and fallbacks")
    void testBiomeIdAt() {
        BiomePaletteSection single = new BiomePaletteSection(0, List.of("minecraft:plains"), null);
        assertEquals("minecraft:plains", single.biomeIdAt(0, 0, 0));
        assertEquals("minecraft:plains", single.biomeIdAt(15, 15, 15));

        assertThrows(IndexOutOfBoundsException.class, () -> single.biomeIdAt(-1, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> single.biomeIdAt(0, 16, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> single.biomeIdAt(0, 0, 20));

        // Multi-entry with empty or null data returns null (UNKNOWN)
        BiomePaletteSection multiEmptyData = new BiomePaletteSection(0, List.of("minecraft:plains", "minecraft:desert"), new long[0]);
        assertNull(multiEmptyData.biomeIdAt(4, 4, 4));
        assertTrue(multiEmptyData.hasMalformedData());

        // Multi-entry with short data array falls back to null when index out of range
        BiomePaletteSection shortData = new BiomePaletteSection(0, List.of("minecraft:plains", "minecraft:desert"), new long[0]);
        assertNull(shortData.biomeIdAt(12, 12, 12));

        // When unpacked palette index is out of palette bounds, returns null
        // 2-bit palette: size 3, index 3 is invalid (only 0, 1, 2 exist)
        long wordWith3 = 0b11L; // slot 0 has value 3
        BiomePaletteSection outOfBoundsPaletteIdx = new BiomePaletteSection(0,
                List.of("minecraft:plains", "minecraft:desert", "minecraft:forest"),
                new long[]{wordWith3, 0L});
        assertNull(outOfBoundsPaletteIdx.biomeIdAt(0, 0, 0));
    }

    @Test
    @DisplayName("equals, hashCode, and toString contracts")
    void testEqualsHashCodeToString() {
        long[] data1 = new long[]{42L};
        long[] data2 = new long[]{42L};
        BiomePaletteSection s1 = new BiomePaletteSection(1, List.of("minecraft:plains"), data1);
        BiomePaletteSection s2 = new BiomePaletteSection(1, List.of("minecraft:plains"), data2);
        BiomePaletteSection sDiffY = new BiomePaletteSection(2, List.of("minecraft:plains"), data1);
        BiomePaletteSection sDiffPalette = new BiomePaletteSection(1, List.of("minecraft:desert"), data1);

        assertEquals(s1, s1);
        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());
        assertNotEquals(s1, sDiffY);
        assertNotEquals(s1, sDiffPalette);
        assertNotEquals(s1, null);
        assertNotEquals(s1, "other");

        String str = s1.toString();
        assertTrue(str.contains("sectionY=1"));
        assertTrue(str.contains("minecraft:plains"));
    }

    @Test
    @DisplayName("16x16x16 per-block biome grid helpers and decoding across palette sizes 1..65 (MC 26.4)")
    void testPerBlockBiomeGrid16x16x16() {
        assertEquals(0, BiomePaletteSection.biomeVoxelIndex(0, 0, 0));
        assertEquals((5 << 8) | (11 << 4) | 13, BiomePaletteSection.biomeVoxelIndex(13, 5, 11));
        assertThrows(IndexOutOfBoundsException.class, () -> BiomePaletteSection.biomeVoxelIndex(-1, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> BiomePaletteSection.biomeVoxelIndex(0, 16, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> BiomePaletteSection.biomeVoxelIndex(0, 0, 16));

        // Single-entry palette is resolution-invariant
        BiomePaletteSection uniform = new BiomePaletteSection(0, List.of("minecraft:plains"), null);
        assertEquals(BiomePaletteSection.CELLS_PER_AXIS_4X4X4, uniform.cellsPerAxis());
        assertFalse(uniform.hasMalformedData());
        assertFalse(BiomePaletteSection.isPerBlockGrid(1, null));

        // Verify 4x4x4 vs 16x16x16 long counts are strictly disjoint and decode accurately across bit widths 1..7
        int[] paletteSizes = {2, 3, 4, 8, 9, 17, 33, 64, 65};
        for (int pSize : paletteSizes) {
            int longs4 = BiomePaletteSection.requiredLongs(pSize, BiomePaletteSection.CELL_COUNT_4X4X4);
            int longs16 = BiomePaletteSection.requiredLongs(pSize, BiomePaletteSection.CELL_COUNT_16X16X16);
            assertTrue(longs16 > longs4, "16x16x16 long count must strictly exceed 4x4x4 for paletteSize=" + pSize);

            List<String> palette = new java.util.ArrayList<>(pSize);
            for (int i = 0; i < pSize; i++) {
                palette.add("minecraft:biome_" + i);
            }

            // Pack 4,096 distinct pattern indices using Mojang's non-spanning bit packing
            int bits = BiomePaletteSection.biomeBitsPerEntry(pSize);
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1L;
            long[] packed16 = new long[longs16];
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int idx = BiomePaletteSection.biomeVoxelIndex(x, y, z);
                        int pIdx = (x * 7 + y * 13 + z * 3) % pSize;
                        packed16[idx / perLong] |= (pIdx & mask) << ((idx % perLong) * bits);
                    }
                }
            }

            assertTrue(BiomePaletteSection.isPerBlockGrid(pSize, packed16));
            BiomePaletteSection sec16 = new BiomePaletteSection(2, palette, packed16);
            assertEquals(BiomePaletteSection.CELLS_PER_AXIS_16X16X16, sec16.cellsPerAxis());
            assertFalse(sec16.hasMalformedData(), "valid 16x16x16 data must not be flagged malformed for pSize=" + pSize);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int expectedIdx = (x * 7 + y * 13 + z * 3) % pSize;
                        assertEquals("minecraft:biome_" + expectedIdx, sec16.biomeIdAt(x, y, z));
                    }
                }
            }

            // Truncated 16x16x16 array (longs16 - 1) must be flagged malformed (S-004 fail-closed)
            long[] truncated = java.util.Arrays.copyOf(packed16, longs16 - 1);
            BiomePaletteSection malformed = new BiomePaletteSection(2, palette, truncated);
            assertTrue(malformed.hasMalformedData(), "truncated 16x16x16 array must be malformed for pSize=" + pSize);
        }

        // > 64 biome palette with 4x4x4-sized array is malformed (cannot fit >64 entries in 64 cells)
        List<String> palette65 = new java.util.ArrayList<>(65);
        for (int i = 0; i < 65; i++) palette65.add("minecraft:biome_" + i);
        BiomePaletteSection tooShortFor65 = new BiomePaletteSection(0, palette65, new long[8]);
        assertTrue(tooShortFor65.hasMalformedData());
    }
}
