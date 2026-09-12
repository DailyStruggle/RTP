package io.github.dailystruggle.rtp.bukkitplatform.world;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BukkitRTPWorldBiomesTest {

    @AfterEach
    void resetBiomesGetter() {
        BukkitRTPWorld.setBiomesGetter(world -> BukkitRTPWorld.defaultBiomes());
    }

    @Test
    void getBiomes_whenGetterReturnsNull_fallsBackToDefaultBiomes() {
        BukkitRTPWorld.setBiomesGetter(world -> null);
        Set<String> biomes = BukkitRTPWorld.getBiomes(null);
        assertNotNull(biomes);
        assertFalse(biomes.isEmpty());
        assertTrue(biomes.contains("PLAINS") || biomes.contains("minecraft:plains"));
    }

    @Test
    void getBiomes_whenGetterReturnsEmpty_fallsBackToDefaultBiomes() {
        BukkitRTPWorld.setBiomesGetter(world -> Set.of());
        Set<String> biomes = BukkitRTPWorld.getBiomes(null);
        assertNotNull(biomes);
        assertFalse(biomes.isEmpty());
        assertTrue(biomes.contains("PLAINS") || biomes.contains("minecraft:plains"));
    }

    @Test
    void getBiomes_whenGetterReturnsNonEmpty_returnsCustomBiomes() {
        BukkitRTPWorld.setBiomesGetter(world -> Set.of("CUSTOM_BIOME"));
        Set<String> biomes = BukkitRTPWorld.getBiomes(null);
        assertNotNull(biomes);
        assertEquals(Set.of("CUSTOM_BIOME"), biomes);
    }
}
