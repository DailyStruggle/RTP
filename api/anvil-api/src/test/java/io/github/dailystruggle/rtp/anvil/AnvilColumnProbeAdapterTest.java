package io.github.dailystruggle.rtp.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnvilColumnProbeAdapterTest {

    @Test
    @DisplayName("adapter constructor validations and coordinates")
    void testConstructorAndCoordinates() {
        assertThrows(NullPointerException.class, () -> new AnvilColumnProbeAdapter(null, 1, 2));

        ColumnProbe probe = new ColumnProbe(0, 100, 50, List.of(), List.of());
        AnvilColumnProbeAdapter adapter = new AnvilColumnProbeAdapter(probe, 3, 4);

        assertEquals(3, adapter.chunkX());
        assertEquals(4, adapter.chunkZ());
        assertEquals(0, adapter.minY());
        assertEquals(100, adapter.maxY());
        assertEquals(OptionalInt.of(50), adapter.heightmapTopY());

        ColumnProbe probeNoHeightmap = new ColumnProbe(0, 100, Integer.MIN_VALUE, List.of(), List.of());
        AnvilColumnProbeAdapter adapterNoHeightmap = new AnvilColumnProbeAdapter(probeNoHeightmap, 3, 4);
        assertFalse(adapterNoHeightmap.heightmapTopY().isPresent());
    }

    @Test
    @DisplayName("adapter normalization and reconciler")
    void testNormalizationAndReconciler() {
        PaletteSection sec0 = new PaletteSection(0, List.of("minecraft:stone"), null);
        PaletteSection sec1 = new PaletteSection(1, List.of("minecraft:air"), null);
        PaletteSection sec2 = new PaletteSection(2, List.of("cave_air"), null);
        PaletteSection sec3 = new PaletteSection(3, List.of("VOID_AIR"), null);
        BiomePaletteSection bsec0 = new BiomePaletteSection(0, List.of("minecraft:plains"), null);

        ColumnProbe probe = new ColumnProbe(0, 63, 50, List.of(sec0, sec1, sec2, sec3), List.of(bsec0));
        AnvilColumnProbeAdapter defaultAdapter = new AnvilColumnProbeAdapter(probe, 0, 0);

        // Center column: (8, y, 8)
        assertEquals("STONE", defaultAdapter.blockAt(5)); // in sec0 (0..15)
        assertEquals("STONE", defaultAdapter.blockAt(2, 3, 5));
        assertEquals("AIR", defaultAdapter.blockAt(20)); // in sec1 (16..31)
        assertEquals("CAVE_AIR", defaultAdapter.blockAt(35)); // in sec2 (32..47)
        assertEquals("VOID_AIR", defaultAdapter.blockAt(50)); // in sec3 (48..63)
        assertNull(defaultAdapter.blockAt(100)); // outside maxY

        assertEquals("PLAINS", defaultAdapter.biomeAt(5));
        assertNull(defaultAdapter.biomeAt(20)); // no biome in sec1
        assertNull(defaultAdapter.biomeAt(100)); // outside maxY

        // isAirAt(y)
        assertFalse(defaultAdapter.isAirAt(5));
        assertTrue(defaultAdapter.isAirAt(20));
        assertTrue(defaultAdapter.isAirAt(35));
        assertTrue(defaultAdapter.isAirAt(50));
        assertFalse(defaultAdapter.isAirAt(100)); // null -> false

        // Test non-normalized air formats via a custom reconciler to cover isAirIdentifier paths
        AnvilColumnProbeAdapter namespacedAirAdapter = new AnvilColumnProbeAdapter(
                probe, 0, 0, raw -> {
            if ("minecraft:air".equals(raw)) return "minecraft:air";
            if ("cave_air".equals(raw)) return "custom:cave_air";
            if ("VOID_AIR".equals(raw)) return "some:VOID_AIR";
            return raw;
        });
        assertTrue(namespacedAirAdapter.isAirAt(20));
        assertTrue(namespacedAirAdapter.isAirAt(35));
        assertTrue(namespacedAirAdapter.isAirAt(50));

        // isAirAt(lx, lz, y)
        assertFalse(defaultAdapter.isAirAt(1, 1, 5));
        assertTrue(defaultAdapter.isAirAt(1, 1, 20));
        assertTrue(defaultAdapter.isAirAt(1, 1, 35));
        assertTrue(defaultAdapter.isAirAt(1, 1, 50));
        assertFalse(defaultAdapter.isAirAt(1, 1, 100));

        // Custom reconciler
        AnvilColumnProbeAdapter customAdapter = new AnvilColumnProbeAdapter(
                probe, 0, 0, raw -> "custom_" + raw);
        assertEquals("custom_minecraft:stone", customAdapter.blockAt(5));

        // Custom reconciler returning empty or null falls back to raw
        AnvilColumnProbeAdapter emptyReconcilerAdapter = new AnvilColumnProbeAdapter(
                probe, 0, 0, raw -> "");
        assertEquals("minecraft:stone", emptyReconcilerAdapter.blockAt(5));

        AnvilColumnProbeAdapter nullReconcilerReturn = new AnvilColumnProbeAdapter(
                probe, 0, 0, raw -> null);
        assertEquals("minecraft:stone", nullReconcilerReturn.blockAt(5));

        // Null reconciler passed to constructor falls back to default
        AnvilColumnProbeAdapter nullReconciler = new AnvilColumnProbeAdapter(
                probe, 0, 0, null);
        assertEquals("STONE", nullReconciler.blockAt(5));
    }
}
