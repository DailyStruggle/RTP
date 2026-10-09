package io.github.dailystruggle.rtp.bukkitplatform.world;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("BukkitMaterialResolver tests")
class BukkitMaterialResolverTest {

    @Test
    @DisplayName("resolve exact match returns material silently")
    void testExactMatch() {
        Material m1 = BukkitMaterialResolver.resolve("GLASS", Material.STONE, "Test");
        assertEquals(Material.GLASS, m1);

        Material m2 = BukkitMaterialResolver.resolve("diamond_sword", Material.STONE, "Test");
        assertEquals(Material.DIAMOND_SWORD, m2);
    }

    @Test
    @DisplayName("resolve perceptible typo autocorrects with warning log")
    void testPerceptibleTypo() {
        // "GLAS" -> GLASS (edit dist 1)
        Material m1 = BukkitMaterialResolver.resolve("GLAS", Material.STONE, "Test");
        assertEquals(Material.GLASS, m1);

        // "COMPAS" -> COMPASS (edit dist 1)
        Material m2 = BukkitMaterialResolver.resolve("COMPAS", Material.STONE, "Test");
        assertEquals(Material.COMPASS, m2);

        // "DIAMOND_SWORDD" -> DIAMOND_SWORD (edit dist 1)
        Material m3 = BukkitMaterialResolver.resolve("DIAMOND_SWORDD", Material.STONE, "Test");
        assertEquals(Material.DIAMOND_SWORD, m3);
    }

    @Test
    @DisplayName("resolve imperceptible input falls back to default safely")
    void testImperceptibleFallback() {
        Material fallback = Material.GLASS;
        Material m1 = BukkitMaterialResolver.resolve("xyz12345", fallback, "Test");
        assertEquals(fallback, m1);

        Material m2 = BukkitMaterialResolver.resolve(null, fallback, "Test");
        assertEquals(fallback, m2);

        Material m3 = BukkitMaterialResolver.resolve("", fallback, "Test");
        assertEquals(fallback, m3);
    }
}
