package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaletteIdentifierNormalizerTest {

    @Test
    @DisplayName("normalize handles null, empty, whitespaces, namespace, and root uppercase")
    void testNormalize() {
        assertNull(PaletteIdentifierNormalizer.normalize(null));
        assertEquals("", PaletteIdentifierNormalizer.normalize(""));
        assertEquals("", PaletteIdentifierNormalizer.normalize("   \t\n  "));
        assertEquals("STONE", PaletteIdentifierNormalizer.normalize("minecraft:stone"));
        assertEquals("DEEPSLATE_ORE", PaletteIdentifierNormalizer.normalize("modid:deepslate_ore"));
        assertEquals("GRASS_BLOCK", PaletteIdentifierNormalizer.normalize("grass_block"));
        assertEquals("OAK_LOG", PaletteIdentifierNormalizer.normalize("  minecraft:oak_log  "));
        assertEquals("DIAMOND_SWORD", PaletteIdentifierNormalizer.normalize("DIAMOND_SWORD"));
    }

    @Test
    @DisplayName("normalizeAll handles null, empty, mixed inputs")
    void testNormalizeAll() {
        assertTrue(PaletteIdentifierNormalizer.normalizeAll(null).isEmpty());
        assertTrue(PaletteIdentifierNormalizer.normalizeAll(Collections.emptyList()).isEmpty());

        List<String> list = Arrays.asList(null, "", "   ", "minecraft:stone", "mod:dirt", null, "stone");
        Set<String> normalized = PaletteIdentifierNormalizer.normalizeAll(list);
        assertEquals(Set.of("STONE", "DIRT"), normalized);
        assertThrows(UnsupportedOperationException.class, () -> normalized.add("OTHER"));
    }

    @Test
    @DisplayName("matches checks membership in normalized set")
    void testMatches() {
        Set<String> unsafe = Set.of("LAVA", "WATER", "FIRE");

        assertThrows(NullPointerException.class, () -> PaletteIdentifierNormalizer.matches("minecraft:lava", null));
        assertTrue(PaletteIdentifierNormalizer.matches("minecraft:lava", unsafe));
        assertTrue(PaletteIdentifierNormalizer.matches("mod:water", unsafe));
        assertTrue(PaletteIdentifierNormalizer.matches("fire", unsafe));

        assertFalse(PaletteIdentifierNormalizer.matches(null, unsafe));
        assertFalse(PaletteIdentifierNormalizer.matches("", unsafe));
        assertFalse(PaletteIdentifierNormalizer.matches("   ", unsafe));
        assertFalse(PaletteIdentifierNormalizer.matches("minecraft:stone", unsafe));
    }
}
