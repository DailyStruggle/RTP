package io.github.dailystruggle.rtp.common.anvil;

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
 * Regression guard for {@link PaletteNormalizer} canonical lookup forms (ADR-016 section 8.1).
 */
@DisplayName("ADR-016 section 8.1 — PaletteNormalizer reconciler parity")
class PaletteNormalizerTest {

    @Test
    @DisplayName("vanilla namespaced ID -> bare upper-case form")
    void reconcile_vanilla_namespacedId() {
        assertEquals("LAVA", PaletteNormalizer.reconcile("minecraft:lava"));
        assertEquals("WATER", PaletteNormalizer.reconcile("MINECRAFT:WATER"));
        assertEquals("CAVE_AIR", PaletteNormalizer.reconcile("minecraft:cave_air"));
    }

    @Test
    @DisplayName("Bukkit-style bare Material name -> identical bare form (config compat)")
    void reconcile_bukkitMaterialNameStyle() {
        // Configs authored on Spigot/Paper use the Material#name() form; on
        // Fabric these must continue to match the same canonical key.
        assertEquals("LAVA", PaletteNormalizer.reconcile("LAVA"));
        assertEquals("STONE", PaletteNormalizer.reconcile("stone"));
    }

    @Test
    @DisplayName("null / empty / whitespace handling")
    void reconcile_nullAndEmpty() {
        assertNull(PaletteNormalizer.reconcile(null));
        assertEquals("", PaletteNormalizer.reconcile(""));
        assertEquals("", PaletteNormalizer.reconcile("   "));
        assertEquals("", PaletteNormalizer.reconcile("minecraft:"));
    }

    @Test
    @DisplayName("modded identifier: namespace stripped, path upper-cased")
    void reconcile_modded() {
        assertEquals("CRUSHING_WHEEL", PaletteNormalizer.reconcile("create:crushing_wheel"));
    }

    @Test
    @DisplayName("reconcileAll: filters null/empty, preserves order, immutable")
    void reconcileAll_basic() {
        Set<String> raw = new LinkedHashSet<>(Arrays.asList(
                "minecraft:lava", null, "", "minecraft:water", "create:crushing_wheel"));
        Set<String> out = PaletteNormalizer.reconcileAll(raw);
        assertEquals(Set.of("LAVA", "WATER", "CRUSHING_WHEEL"), out);
        // Set.of returns an unmodifiable set; the normalizer's contract also
        // returns one - ensure that mutation throws.
        assertThrowsUnsupported(out);
    }

    @Test
    @DisplayName("reconcileAll: null and empty inputs return empty set")
    void reconcileAll_nullAndEmpty() {
        assertTrue(PaletteNormalizer.reconcileAll(null).isEmpty());
        assertTrue(PaletteNormalizer.reconcileAll(Collections.emptySet()).isEmpty());
    }

    @Test
    @DisplayName("matches: cross-form lookup (namespaced raw vs bare reconciled)")
    void matches_crossForm() {
        Set<String> reconciled = PaletteNormalizer.reconcileAll(
                Arrays.asList("LAVA", "minecraft:fire"));
        // The reconciled set is what the Anvil view receives; lookups against it
        // should succeed regardless of the raw-side form.
        assertTrue(PaletteNormalizer.matches("minecraft:lava", reconciled));
        assertTrue(PaletteNormalizer.matches("LAVA", reconciled));
        assertTrue(PaletteNormalizer.matches("minecraft:fire", reconciled));
        assertFalse(PaletteNormalizer.matches("minecraft:grass_block", reconciled));
        assertFalse(PaletteNormalizer.matches(null, reconciled));
        assertFalse(PaletteNormalizer.matches("", reconciled));
    }

    @Test
    @DisplayName("matches: empty/null reconciled set returns false")
    void matches_emptyHaystack() {
        assertFalse(PaletteNormalizer.matches("minecraft:lava", null));
        assertFalse(PaletteNormalizer.matches("minecraft:lava", Collections.emptySet()));
    }

    private static void assertThrowsUnsupported(Set<String> immutable) {
        try {
            immutable.add("X");
            // Reached here only if the set was mutable - fail.
            org.junit.jupiter.api.Assertions.fail(
                    "reconcileAll must return an unmodifiable set");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
