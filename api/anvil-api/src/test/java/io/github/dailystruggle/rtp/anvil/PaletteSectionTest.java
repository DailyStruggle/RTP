package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PaletteSection record and blockIdAt branch coverage")
class PaletteSectionTest {

    @Test
    void testConstructorValidation() {
        assertThrows(NullPointerException.class, () -> new PaletteSection(0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PaletteSection(0, List.of(), null));

        // Single entry with non-empty data is tolerated
        PaletteSection singleWithData = new PaletteSection(0, List.of("minecraft:stone"), new long[]{1L});
        assertEquals("minecraft:stone", singleWithData.blockIdAt(0, 0, 0));

        // Multi entry without data is tolerated
        PaletteSection multiWithoutData = new PaletteSection(0, List.of("minecraft:stone", "minecraft:air"), null);
        assertEquals("minecraft:stone", multiWithoutData.blockIdAt(0, 0, 0));

        // Multi entry with empty data is tolerated
        PaletteSection multiWithEmptyData = new PaletteSection(0, List.of("minecraft:stone", "minecraft:air"), new long[0]);
        assertEquals("minecraft:stone", multiWithEmptyData.blockIdAt(0, 0, 0));
    }

    @Test
    void testBlockIdAtCorruptedIndexFallsBackToFirstEntry() {
        // 2 entries in palette -> PackedPaletteDecoder uses 4 bits per entry (minimum 4)
        // If data contains invalid palette index (e.g. 5 >= 2), blockIdAt should fall back to palette.get(0)
        List<String> palette = List.of("minecraft:stone", "minecraft:dirt");
        int[] indices = new int[4096];
        indices[0] = 5; // index out of bounds for palette of size 2
        long[] packed = AnvilTestFixtures.packIndices(4, indices);

        PaletteSection section = new PaletteSection(0, palette, packed);
        assertEquals("minecraft:stone", section.blockIdAt(0, 0, 0));
    }

    @Test
    void testBlockIdAtValidDecode() {
        List<String> palette = List.of("minecraft:stone", "minecraft:dirt");
        int[] indices = new int[4096];
        indices[0] = 1; // lx=0, ly=0, lz=0 -> dirt
        indices[1] = 0; // lx=1, ly=0, lz=0 -> stone
        long[] packed = AnvilTestFixtures.packIndices(4, indices);

        PaletteSection section = new PaletteSection(0, palette, packed);
        assertEquals("minecraft:dirt", section.blockIdAt(0, 0, 0));
        assertEquals("minecraft:stone", section.blockIdAt(1, 0, 0));
    }

    @Test
    void testEqualsHashCodeToString() {
        List<String> pal1 = List.of("minecraft:air", "minecraft:bedrock");
        List<String> pal2 = List.of("minecraft:air", "minecraft:bedrock");
        long[] data1 = new long[]{1L, 2L};
        long[] data2 = new long[]{1L, 2L};

        PaletteSection sec1 = new PaletteSection(1, pal1, data1);
        PaletteSection sec2 = new PaletteSection(1, pal2, data2);
        PaletteSection diffY = new PaletteSection(2, pal1, data1);
        PaletteSection diffPal = new PaletteSection(1, List.of("minecraft:air"), null);
        PaletteSection diffData = new PaletteSection(1, pal1, new long[]{3L});

        assertEquals(sec1, sec1);
        assertEquals(sec1, sec2);
        assertEquals(sec1.hashCode(), sec2.hashCode());

        assertNotEquals(sec1, null);
        assertNotEquals(sec1, "not-a-section");
        assertNotEquals(sec1, diffY);
        assertNotEquals(sec1, diffPal);
        assertNotEquals(sec1, diffData);

        String str = sec1.toString();
        assertTrue(str.contains("PaletteSection["));
        assertTrue(str.contains("sectionY=1"));
        assertTrue(str.contains("minecraft:bedrock"));
    }
}
