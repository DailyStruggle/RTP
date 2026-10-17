package io.github.dailystruggle.rtp.bukkitplatform.world;

import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BukkitRegionPaths region-subpath resolution (ADR-016, ADR-077)")
class BukkitRegionPathsTest {

    @TempDir
    Path tmp;

    @AfterEach
    void tearDown() {
        BukkitRegionPaths.invalidate();
    }

    @Test
    @DisplayName("26.1+ unified layout: overworld resolves to dimensions/minecraft/overworld")
    void unifiedOverworld() throws Exception {
        Files.createDirectories(tmp.resolve("dimensions/minecraft/overworld/region"));
        assertEquals("dimensions/minecraft/overworld",
                BukkitRegionPaths.resolveExisting(tmp, "minecraft:overworld", ""));
    }

    @Test
    @DisplayName("26.1+ unified layout: nether resolves to dimensions/minecraft/the_nether, not DIM-1")
    void unifiedNether() throws Exception {
        Files.createDirectories(tmp.resolve("dimensions/minecraft/the_nether/region"));
        assertEquals("dimensions/minecraft/the_nether",
                BukkitRegionPaths.resolveExisting(tmp, "minecraft:the_nether", "DIM-1"));
    }

    @Test
    @DisplayName("legacy layout: nether keeps DIM-1 when no unified directory exists")
    void legacyNether() throws Exception {
        Files.createDirectories(tmp.resolve("DIM-1/region"));
        assertEquals("DIM-1", BukkitRegionPaths.resolveExisting(tmp, "minecraft:the_nether", "DIM-1"));
    }

    @Test
    @DisplayName("legacy layout without World#getKey (Spigot): overworld keeps region/")
    void legacyOverworldWithoutKey() throws Exception {
        Files.createDirectories(tmp.resolve("region"));
        assertEquals("", BukkitRegionPaths.resolveExisting(tmp, null, ""));
    }

    @Test
    @DisplayName("world folder already the dimension folder: falls back to region/")
    void folderIsDimensionFolder() throws Exception {
        Files.createDirectories(tmp.resolve("region"));
        assertEquals("", BukkitRegionPaths.resolveExisting(tmp, "minecraft:the_nether", "DIM-1"));
    }

    @Test
    @DisplayName("no region directory yet: no match")
    void noRegionDirectory() {
        assertNull(BukkitRegionPaths.resolveExisting(tmp, "minecraft:overworld", ""));
    }

    @Test
    @DisplayName("candidates: unified first, legacy next, root last, deduplicated")
    void candidateOrder() {
        assertEquals(List.of("dimensions/minecraft/the_end", "DIM1", ""),
                BukkitRegionPaths.candidates("minecraft:the_end", "DIM1"));
        assertEquals(List.of("dimensions/minecraft/overworld", ""),
                BukkitRegionPaths.candidates("minecraft:overworld", ""));
        assertEquals(List.of(""), BukkitRegionPaths.candidates(null, ""));
    }

    @Test
    @DisplayName("dimensionRegionSubpath: missing directory is not cached, so a later save is observed")
    void missNotCached() throws Exception {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getWorldFolder()).thenReturn(tmp.toFile());
        when(world.getEnvironment()).thenReturn(World.Environment.NETHER);

        assertEquals("DIM-1", BukkitRegionPaths.dimensionRegionSubpath(world));

        Files.createDirectories(tmp.resolve("region"));
        assertEquals("", BukkitRegionPaths.dimensionRegionSubpath(world));
    }
}
