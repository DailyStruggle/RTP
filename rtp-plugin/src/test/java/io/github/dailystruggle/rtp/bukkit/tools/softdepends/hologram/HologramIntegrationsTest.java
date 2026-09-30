package io.github.dailystruggle.rtp.bukkit.tools.softdepends.hologram;

import io.github.dailystruggle.effectsapi.common.hologram.HologramHandle;
import io.github.dailystruggle.effectsapi.common.hologram.HologramRegistry;
import io.github.dailystruggle.effectsapi.common.volumetric.SpatialBounds;
import io.github.dailystruggle.effectsapi.common.volumetric.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HologramIntegrationsTest {

    @AfterEach
    void tearDown() {
        HologramRegistry.register(null);
    }

    @Test
    @DisplayName("DecentHologramsChecker availability test degrades safely without external plugin")
    void testDecentHologramsCheckerDegrades() {
        assertFalse(DecentHologramsChecker.isAvailable());

        DecentHologramsChecker checker = new DecentHologramsChecker();
        // Spawning when Bukkit.getWorld returns null falls back to VirtualHologramHandle safely
        HologramHandle handle = checker.spawnHologram("holo_test", "world", new Vector3d(0, 64, 0), List.of("Line 1"));
        assertNotNull(handle);
        assertEquals("holo_test", handle.id());
        assertEquals("world", handle.worldName());
        assertEquals(List.of("Line 1"), handle.lines());

        handle.updateLines(List.of("Line 2"));
        assertEquals(List.of("Line 2"), handle.lines());

        handle.teleport(new Vector3d(10, 70, 10));
        assertEquals(new Vector3d(10, 70, 10), handle.position());

        assertDoesNotThrow(handle::close);
    }

    @Test
    @DisplayName("HolographicDisplaysChecker availability test degrades safely without external plugin")
    void testHolographicDisplaysCheckerDegrades() {
        assertFalse(HolographicDisplaysChecker.isAvailable());

        HolographicDisplaysChecker checker = new HolographicDisplaysChecker(null);
        HologramHandle handle = checker.spawnHologram("holo_test2", "world", new Vector3d(0, 64, 0), List.of("Line 1"));
        assertNotNull(handle);
        assertEquals("holo_test2", handle.id());
        assertEquals("world", handle.worldName());
        assertEquals(List.of("Line 1"), handle.lines());

        assertDoesNotThrow(handle::close);
    }

    @Test
    @DisplayName("BukkitTextDisplayHologramProvider degrades safely in mock environment")
    void testBukkitTextDisplayHologramProviderDegrades() {
        BukkitTextDisplayHologramProvider provider = new BukkitTextDisplayHologramProvider();
        HologramHandle handle = provider.spawnHologram("display_test", "world", new Vector3d(5, 64, 5), List.of("Countdown"));
        assertNotNull(handle);
        assertEquals("display_test", handle.id());
        assertEquals(List.of("Countdown"), handle.lines());
        assertDoesNotThrow(handle::close);
    }

    @Test
    @DisplayName("HologramIntegrations setup runs without throwing")
    void testHologramIntegrationsSetup() {
        assertDoesNotThrow(() -> HologramIntegrations.setup(null));
    }
}
