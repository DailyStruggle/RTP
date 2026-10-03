package io.github.dailystruggle.rtp.bukkitplatform.world;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.block.data.BlockDataMock;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet;
import io.github.dailystruggle.rtp.api.safety.SafetyTokenParser;
import io.github.dailystruggle.rtp.bukkitplatform.server.AbstractServerAccessor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests covering teleport safety material predicate checks, palette reconciliation,
 * and chunk safety validation in {@code rtp-bukkit-common} using MockBukkit.
 *
 * <p>Lifecycle is strictly guarded via {@link MockBukkit#mock()} in {@link #setUp()}
 * and {@link MockBukkit#unmock()} in {@link #tearDown()} to prevent cross-worker static leaks.</p>
 */
@DisplayName("BukkitTeleportSafety material predicate and chunk safety tests")
class BukkitTeleportSafetyTest {

    private ServerMock server;
    private WorldMock world;
    private AbstractServerAccessor accessor;

    static class DummyServerAccessor extends AbstractServerAccessor {
        DummyServerAccessor() {
            super();
        }
    }

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("safety-test-world");
        accessor = new DummyServerAccessor();
        RTPAPI.serverAccessor = accessor;
        io.github.dailystruggle.rtp.common.RTP.serverAccessor = accessor;
    }

    @AfterEach
    void tearDown() {
        RTPAPI.serverAccessor = null;
        io.github.dailystruggle.rtp.common.RTP.serverAccessor = null;
        if (accessor != null) {
            accessor.stop();
        }
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("materials() returns uppercase non-empty set of Bukkit materials")
    void testMaterialsSet() {
        Set<String> materials = accessor.materials();
        assertNotNull(materials);
        assertFalse(materials.isEmpty());
        assertTrue(materials.contains("AIR"));
        assertTrue(materials.contains("STONE"));
        assertTrue(materials.contains("LAVA"));
        assertTrue(materials.contains("WATER"));

        for (String m : materials) {
            assertEquals(m.toUpperCase(), m, "All material names must be upper case");
        }
    }

    @Test
    @DisplayName("blockTagSnapshot() returns non-null map and can be rebuilt")
    void testBlockTagSnapshot() {
        Map<String, Set<String>> tags = accessor.blockTagSnapshot();
        assertNotNull(tags);

        // Snapshot caching test
        assertSame(tags, accessor.blockTagSnapshot());

        accessor.rebuildBlockTagSnapshot();
        assertNotNull(accessor.blockTagSnapshot());
    }

    @Test
    @DisplayName("reconcilePaletteIdentifier reconciles raw names to canonical Material names")
    void testPaletteReconciliation() {
        assertEquals("STONE", accessor.reconcilePaletteIdentifier("minecraft:stone"));
        assertEquals("STONE", accessor.reconcilePaletteIdentifier("stone"));
        assertEquals("LAVA", accessor.reconcilePaletteIdentifier("lava"));
        assertEquals("WATER", accessor.reconcilePaletteIdentifier("minecraft:water"));
        assertNull(accessor.reconcilePaletteIdentifier(null));

        Set<String> reconciled = accessor.reconcilePaletteIdentifiers(Set.of("minecraft:stone", "lava"));
        assertNotNull(reconciled);
        assertEquals(Set.of("STONE", "LAVA"), reconciled);
        assertTrue(accessor.reconcilePaletteIdentifiers(null).isEmpty());
        assertTrue(accessor.reconcilePaletteIdentifiers(Collections.emptySet()).isEmpty());
    }

    @Test
    @DisplayName("matchesPaletteIdentifier correctly matches canonical and raw palette entries")
    void testMatchesPaletteIdentifier() {
        Set<String> unsafe = Set.of("LAVA", "FIRE", "WATER");

        assertTrue(accessor.matchesPaletteIdentifier("minecraft:lava", unsafe));
        assertTrue(accessor.matchesPaletteIdentifier("lava", unsafe));
        assertTrue(accessor.matchesPaletteIdentifier("LAVA", unsafe));
        assertFalse(accessor.matchesPaletteIdentifier("minecraft:stone", unsafe));
        assertFalse(accessor.matchesPaletteIdentifier("stone", unsafe));
        assertFalse(accessor.matchesPaletteIdentifier(null, unsafe));
        assertFalse(accessor.matchesPaletteIdentifier("lava", null));
        assertFalse(accessor.matchesPaletteIdentifier("lava", Collections.emptySet()));
    }

    @Test
    @DisplayName("BukkitRTPChunk safety checks with material predicates")
    void testBukkitRTPChunkSafety() {
        // Prepare blocks in world
        Block safeBlock = world.getBlockAt(0, 64, 0);
        safeBlock.setType(Material.STONE);

        Block lavaBlock = world.getBlockAt(1, 64, 0);
        lavaBlock.setType(Material.LAVA);

        Block airBlock = world.getBlockAt(2, 64, 0);
        airBlock.setType(Material.AIR);

        var bukkitChunk = world.getChunkAt(0, 0);
        BukkitRTPChunk rtpChunk = new BukkitRTPChunk(bukkitChunk);

        // 1. Unsafe check with raw set
        Set<String> unsafeMaterials = Set.of("LAVA", "WATER");
        assertTrue(rtpChunk.isSafe(0, 64, 0, unsafeMaterials), "Stone must be safe");
        assertFalse(rtpChunk.isSafe(1, 64, 0, unsafeMaterials), "Lava must be unsafe");
        assertTrue(rtpChunk.isSafe(2, 64, 0, unsafeMaterials), "Air must be safe when not in unsafe list");

        // 2. Unsafe check with CompiledUnsafeSet
        CompiledUnsafeSet compiledUnsafe = CompiledUnsafeSet.compile(
                SafetyTokenParser.parseAll(Arrays.asList("LAVA", "FIRE")).accepted());
        assertTrue(rtpChunk.isSafe(0, 64, 0, compiledUnsafe), "Stone is safe against compiled set");
        assertFalse(rtpChunk.isSafe(1, 64, 0, compiledUnsafe), "Lava is unsafe against compiled set");

        // Null and empty compiled sets are always safe
        assertTrue(rtpChunk.isSafe(1, 64, 0, (CompiledUnsafeSet) null));
        assertTrue(rtpChunk.isSafe(1, 64, 0, CompiledUnsafeSet.EMPTY));

        // Out of world height bounds
        assertFalse(rtpChunk.isSafe(0, -100, 0, compiledUnsafe));
        assertFalse(rtpChunk.isSafe(0, 500, 0, compiledUnsafe));
    }
}
