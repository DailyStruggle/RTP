package io.github.dailystruggle.rtp.fabric.anvil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for {@link FabricPaletteNormalizer} — the Fabric-side
 * reconciler that produces the canonical lookup form expected by
 * {@code rtp-anvil}'s {@code AnvilChunkView#isAir} / {@code isSafe}. Mirrors
 * the rtp-api {@code PaletteIdentifierNormalizerTest} contract so that a
 * config authored against Bukkit-family Material names continues to match
 * vanilla palette IDs on Fabric.
 *
 * <p>Trace: rtp-fabric-ADR-005 follow-up (full anvil-backed {@code RTPChunk}
 * parity); ADR-016 §8.1.</p>
 */
@DisplayName("rtp-fabric-ADR-005 — FabricPaletteNormalizer reconciler parity")
class FabricPaletteNormalizerTest {

    @Test
    @DisplayName("vanilla namespaced ID → bare upper-case form")
    void reconcile_vanilla_namespacedId() {
        assertEquals("LAVA", FabricPaletteNormalizer.reconcile("minecraft:lava"));
        assertEquals("WATER", FabricPaletteNormalizer.reconcile("MINECRAFT:WATER"));
        assertEquals("CAVE_AIR", FabricPaletteNormalizer.reconcile("minecraft:cave_air"));
    }

    @Test
    @DisplayName("Bukkit-style bare Material name → identical bare form (config compat)")
    void reconcile_bukkitMaterialNameStyle() {
        // Configs authored on Spigot/Paper use the Material#name() form; on
        // Fabric these must continue to match the same canonical key.
        assertEquals("LAVA", FabricPaletteNormalizer.reconcile("LAVA"));
        assertEquals("STONE", FabricPaletteNormalizer.reconcile("stone"));
    }

    @Test
    @DisplayName("null / empty / whitespace handling")
    void reconcile_nullAndEmpty() {
        assertNull(FabricPaletteNormalizer.reconcile(null));
        assertEquals("", FabricPaletteNormalizer.reconcile(""));
        assertEquals("", FabricPaletteNormalizer.reconcile("   "));
        assertEquals("", FabricPaletteNormalizer.reconcile("minecraft:"));
    }

    @Test
    @DisplayName("modded identifier: namespace stripped, path upper-cased")
    void reconcile_modded() {
        assertEquals("CRUSHING_WHEEL", FabricPaletteNormalizer.reconcile("create:crushing_wheel"));
    }

    @Test
    @DisplayName("reconcileAll: filters null/empty, preserves order, immutable")
    void reconcileAll_basic() {
        Set<String> raw = new LinkedHashSet<>(Arrays.asList(
                "minecraft:lava", null, "", "minecraft:water", "create:crushing_wheel"));
        Set<String> out = FabricPaletteNormalizer.reconcileAll(raw);
        assertEquals(Set.of("LAVA", "WATER", "CRUSHING_WHEEL"), out);
        // Set.of returns an unmodifiable set; the normalizer's contract also
        // returns one — ensure that mutation throws.
        assertThrowsUnsupported(out);
    }

    @Test
    @DisplayName("reconcileAll: null and empty inputs return empty set")
    void reconcileAll_nullAndEmpty() {
        assertTrue(FabricPaletteNormalizer.reconcileAll(null).isEmpty());
        assertTrue(FabricPaletteNormalizer.reconcileAll(Collections.emptySet()).isEmpty());
    }

    @Test
    @DisplayName("matches: cross-form lookup (namespaced raw vs bare reconciled)")
    void matches_crossForm() {
        Set<String> reconciled = FabricPaletteNormalizer.reconcileAll(
                Arrays.asList("LAVA", "minecraft:fire"));
        // The reconciled set is what the Anvil view receives; lookups against it
        // should succeed regardless of the raw-side form.
        assertTrue(FabricPaletteNormalizer.matches("minecraft:lava", reconciled));
        assertTrue(FabricPaletteNormalizer.matches("LAVA", reconciled));
        assertTrue(FabricPaletteNormalizer.matches("minecraft:fire", reconciled));
        assertFalse(FabricPaletteNormalizer.matches("minecraft:grass_block", reconciled));
        assertFalse(FabricPaletteNormalizer.matches(null, reconciled));
        assertFalse(FabricPaletteNormalizer.matches("", reconciled));
    }

    @Test
    @DisplayName("matches: empty/null reconciled set returns false")
    void matches_emptyHaystack() {
        assertFalse(FabricPaletteNormalizer.matches("minecraft:lava", null));
        assertFalse(FabricPaletteNormalizer.matches("minecraft:lava", Collections.emptySet()));
    }

    private static void assertThrowsUnsupported(Set<String> immutable) {
        try {
            immutable.add("X");
            // Reached here only if the set was mutable — fail.
            org.junit.jupiter.api.Assertions.fail(
                    "reconcileAll must return an unmodifiable set");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
