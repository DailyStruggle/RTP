package io.github.dailystruggle.rtp.common.selection.region;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link BiomeNames#matches(Set, String)}.
 *
 * <p>The helper exists because Bukkit-family platforms expose biome ids in two shapes:
 * older releases (enum {@code Biome}) yield bare uppercase names like {@code "PLAINS"};
 * newer releases (Keyed registry {@code Biome}, 1.21.5+) yield namespaced uppercase names
 * like {@code "MINECRAFT:PLAINS"}. The same input typed at a command argument or written
 * into {@code safety.yml} may follow either convention; the comparator treats the vanilla
 * {@code MINECRAFT:} prefix as optional on either side while preserving modded namespaces
 * (e.g. {@code IRIS:OVERWORLD:PLAINS}) verbatim.
 */
class BiomeNamesTest {

  @Nested
  @DisplayName("vanilla namespace optionality")
  class VanillaOptional {

    @Test
    @DisplayName("bare filter accepts bare probe")
    void bareFilterBareProbe() {
      assertTrue(BiomeNames.matches(Set.of("PLAINS"), "PLAINS"));
    }

    @Test
    @DisplayName("bare filter accepts namespaced probe")
    void bareFilterNamespacedProbe() {
      assertTrue(BiomeNames.matches(Set.of("PLAINS"), "MINECRAFT:PLAINS"));
    }

    @Test
    @DisplayName("namespaced filter accepts bare probe")
    void namespacedFilterBareProbe() {
      assertTrue(BiomeNames.matches(Set.of("MINECRAFT:PLAINS"), "PLAINS"));
    }

    @Test
    @DisplayName("namespaced filter accepts namespaced probe")
    void namespacedFilterNamespacedProbe() {
      assertTrue(BiomeNames.matches(Set.of("MINECRAFT:PLAINS"), "MINECRAFT:PLAINS"));
    }
  }

  @Nested
  @DisplayName("modded namespace preservation")
  class ModdedPreserved {

    @Test
    @DisplayName("modded filter rejects bare probe (no implicit strip)")
    void moddedFilterRejectsBare() {
      assertFalse(BiomeNames.matches(Set.of("IRIS:OVERWORLD:PLAINS"), "PLAINS"));
    }

    @Test
    @DisplayName("modded filter rejects vanilla namespaced probe")
    void moddedFilterRejectsVanillaProbe() {
      assertFalse(BiomeNames.matches(Set.of("IRIS:OVERWORLD:PLAINS"), "MINECRAFT:PLAINS"));
    }

    @Test
    @DisplayName("modded filter accepts identical modded probe")
    void moddedFilterAcceptsModded() {
      assertTrue(BiomeNames.matches(Set.of("IRIS:OVERWORLD:PLAINS"), "IRIS:OVERWORLD:PLAINS"));
    }

    @Test
    @DisplayName("bare filter rejects modded probe")
    void bareFilterRejectsModded() {
      assertFalse(BiomeNames.matches(Set.of("PLAINS"), "TERRALITH:PLAINS"));
    }
  }

  @Nested
  @DisplayName("miss cases")
  class Misses {

    @Test
    @DisplayName("empty filter never matches")
    void emptyFilter() {
      assertFalse(BiomeNames.matches(Set.of(), "PLAINS"));
      assertFalse(BiomeNames.matches(Set.of(), "MINECRAFT:PLAINS"));
    }

    @Test
    @DisplayName("non-matching biome misses")
    void nonMatchingBiome() {
      assertFalse(BiomeNames.matches(Set.of("PLAINS"), "DESERT"));
      assertFalse(BiomeNames.matches(Set.of("MINECRAFT:PLAINS"), "MINECRAFT:DESERT"));
    }
  }

  @Nested
  @DisplayName("multi-entry filter")
  class MultiEntry {

    @Test
    @DisplayName("mixed bare and namespaced filter resolves either probe shape")
    void mixedFilter() {
      Set<String> filter = Set.of("PLAINS", "MINECRAFT:DESERT", "IRIS:OVERWORLD:HIGHLANDS");
      // bare in filter, bare probe
      assertTrue(BiomeNames.matches(filter, "PLAINS"));
      // bare in filter, namespaced probe
      assertTrue(BiomeNames.matches(filter, "MINECRAFT:PLAINS"));
      // namespaced in filter, bare probe
      assertTrue(BiomeNames.matches(filter, "DESERT"));
      // namespaced in filter, namespaced probe
      assertTrue(BiomeNames.matches(filter, "MINECRAFT:DESERT"));
      // modded in filter, modded probe (verbatim)
      assertTrue(BiomeNames.matches(filter, "IRIS:OVERWORLD:HIGHLANDS"));
      // modded in filter, vanilla probe (no cross-namespace match)
      assertFalse(BiomeNames.matches(filter, "HIGHLANDS"));
    }
  }
}
