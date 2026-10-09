package io.github.dailystruggle.rtp.anvil;

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
}
