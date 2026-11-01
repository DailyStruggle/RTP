package io.github.dailystruggle.rtp.common.selection.region;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link MaterialNames#matches(Set, String)}.
 * Verifies optional vanilla {@code MINECRAFT:} prefixes and preservation of modded namespaces.
 */
class MaterialNamesTest {

  @Nested
  @DisplayName("vanilla namespace optionality")
  class VanillaOptional {

    @Test
    @DisplayName("bare filter accepts bare probe")
    void bareFilterBareProbe() {
      assertTrue(MaterialNames.matches(Set.of("LAVA"), "LAVA"));
    }

    @Test
    @DisplayName("bare filter accepts namespaced probe")
    void bareFilterNamespacedProbe() {
      assertTrue(MaterialNames.matches(Set.of("LAVA"), "MINECRAFT:LAVA"));
    }

    @Test
    @DisplayName("namespaced filter accepts bare probe")
    void namespacedFilterBareProbe() {
      assertTrue(MaterialNames.matches(Set.of("MINECRAFT:LAVA"), "LAVA"));
    }

    @Test
    @DisplayName("namespaced filter accepts namespaced probe")
    void namespacedFilterNamespacedProbe() {
      assertTrue(MaterialNames.matches(Set.of("MINECRAFT:LAVA"), "MINECRAFT:LAVA"));
    }
  }

  @Nested
  @DisplayName("modded namespace preservation")
  class ModdedPreserved {

    @Test
    @DisplayName("modded filter rejects bare probe (no implicit strip)")
    void moddedFilterRejectsBare() {
      assertFalse(MaterialNames.matches(Set.of("CREATEDECO:LIMESTONE"), "LIMESTONE"));
    }

    @Test
    @DisplayName("modded filter rejects vanilla namespaced probe")
    void moddedFilterRejectsVanillaProbe() {
      assertFalse(MaterialNames.matches(Set.of("CREATEDECO:LIMESTONE"), "MINECRAFT:LIMESTONE"));
    }

    @Test
    @DisplayName("modded filter accepts identical modded probe")
    void moddedFilterAcceptsModded() {
      assertTrue(MaterialNames.matches(Set.of("CREATEDECO:LIMESTONE"), "CREATEDECO:LIMESTONE"));
    }

    @Test
    @DisplayName("bare filter rejects modded probe")
    void bareFilterRejectsModded() {
      assertFalse(MaterialNames.matches(Set.of("LAVA"), "TERRALITH:LAVA"));
    }
  }

  @Nested
  @DisplayName("miss cases")
  class Misses {

    @Test
    @DisplayName("empty filter never matches")
    void emptyFilter() {
      assertFalse(MaterialNames.matches(Set.of(), "LAVA"));
      assertFalse(MaterialNames.matches(Set.of(), "MINECRAFT:LAVA"));
    }

    @Test
    @DisplayName("non-matching block misses")
    void nonMatchingBlock() {
      assertFalse(MaterialNames.matches(Set.of("LAVA"), "STONE"));
      assertFalse(MaterialNames.matches(Set.of("MINECRAFT:LAVA"), "MINECRAFT:STONE"));
    }
  }

  @Nested
  @DisplayName("multi-entry filter")
  class MultiEntry {

    @Test
    @DisplayName("mixed bare and namespaced filter resolves either probe shape")
    void mixedFilter() {
      Set<String> filter = Set.of("LAVA", "MINECRAFT:MAGMA_BLOCK", "CREATEDECO:LIMESTONE");
      // bare in filter, bare probe
      assertTrue(MaterialNames.matches(filter, "LAVA"));
      // bare in filter, namespaced probe
      assertTrue(MaterialNames.matches(filter, "MINECRAFT:LAVA"));
      // namespaced in filter, bare probe
      assertTrue(MaterialNames.matches(filter, "MAGMA_BLOCK"));
      // namespaced in filter, namespaced probe
      assertTrue(MaterialNames.matches(filter, "MINECRAFT:MAGMA_BLOCK"));
      // modded in filter, modded probe (verbatim)
      assertTrue(MaterialNames.matches(filter, "CREATEDECO:LIMESTONE"));
      // modded in filter, vanilla probe (no cross-namespace match)
      assertFalse(MaterialNames.matches(filter, "LIMESTONE"));
    }
  }
}
