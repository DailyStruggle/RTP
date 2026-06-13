package io.github.dailystruggle.rtp.api.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers compile-time tag expansion (ADR-017).
 *
 * <p>Verifies that {@link SafetyCompilationCache#getOrCompile(java.util.Collection,
 * Map, java.util.function.Consumer)} produces a tag-free
 * {@link CompiledUnsafeSet} whose {@code plainMaterials} and
 * {@code materialStatePredicates} contain everything that would otherwise be
 * matched by a live tag-membership check in the hot path.
 */
class SafetyCompilationCacheTagExpansionTest {

  @BeforeEach
  void resetCache() {
    SafetyCompilationCache.clear();
  }

  @Test
  @DisplayName("plain tag token expands to all members of the snapshot")
  void plainTagExpandsToMaterials() {
    Map<String, Set<String>> snapshot =
        Map.of("minecraft:leaves", Set.of("OAK_LEAVES", "BIRCH_LEAVES"));

    CompiledUnsafeSet compiled =
        SafetyCompilationCache.getOrCompile(List.of("#minecraft:leaves"), snapshot, null);

    assertTrue(compiled.plainTags().isEmpty(), "tag bucket shall be cleared");
    assertTrue(compiled.tagStatePredicates().isEmpty(), "tag-state bucket shall be cleared");
    assertEquals(Set.of("OAK_LEAVES", "BIRCH_LEAVES"), compiled.plainMaterials());
    assertTrue(compiled.isUnsafe("OAK_LEAVES", Map.of()));
    assertTrue(compiled.isUnsafe("BIRCH_LEAVES", Map.of()));
    assertFalse(compiled.isUnsafe("STONE", Map.of()));
  }

  @Test
  @DisplayName("predicated tag token replicates predicates onto each member material")
  void predicatedTagReplicatesPredicates() {
    Map<String, Set<String>> snapshot =
        Map.of("minecraft:slabs", Set.of("OAK_SLAB", "STONE_SLAB"));

    CompiledUnsafeSet compiled =
        SafetyCompilationCache.getOrCompile(
            List.of("#minecraft:slabs[waterlogged=true]"), snapshot, null);

    assertTrue(compiled.tagStatePredicates().isEmpty());
    assertTrue(compiled.plainTags().isEmpty());
    assertTrue(compiled.materialStatePredicates().containsKey("OAK_SLAB"));
    assertTrue(compiled.materialStatePredicates().containsKey("STONE_SLAB"));

    // Dry slab shall pass; waterlogged slab shall be rejected.
    assertFalse(compiled.isUnsafe("OAK_SLAB", Map.of("waterlogged", "false")));
    assertTrue(compiled.isUnsafe("OAK_SLAB", Map.of("waterlogged", "true")));
    assertTrue(compiled.isUnsafe("STONE_SLAB", Map.of("waterlogged", "true")));
  }

  @Test
  @DisplayName("material already listed as plain-unsafe short-circuits tag-predicate expansion")
  void plainMaterialTakesPrecedenceOverTagPredicate() {
    Map<String, Set<String>> snapshot = Map.of("minecraft:slabs", Set.of("OAK_SLAB"));

    CompiledUnsafeSet compiled =
        SafetyCompilationCache.getOrCompile(
            List.of("OAK_SLAB", "#minecraft:slabs[waterlogged=true]"), snapshot, null);

    // OAK_SLAB shall be plain-unsafe regardless of waterlogged state.
    assertTrue(compiled.plainMaterials().contains("OAK_SLAB"));
    assertFalse(
        compiled.materialStatePredicates().containsKey("OAK_SLAB"),
        "tag predicate shall not replicate onto an already-plain material");
    assertTrue(compiled.isUnsafe("OAK_SLAB", Map.of("waterlogged", "false")));
  }

  @Test
  @DisplayName("tag absent from snapshot contributes no constraints — fail-open")
  void missingTagDropsSilently() {
    Map<String, Set<String>> snapshot = Map.of(); // empty

    CompiledUnsafeSet compiled =
        SafetyCompilationCache.getOrCompile(List.of("#minecraft:leaves"), snapshot, null);

    assertTrue(compiled.plainMaterials().isEmpty());
    assertTrue(compiled.plainTags().isEmpty());
    assertFalse(compiled.isUnsafe("OAK_LEAVES", Map.of()));
  }

  @Test
  @DisplayName("snapshot is ignored when no tag tokens are configured — base reused")
  void snapshotIgnoredWithoutTagTokens() {
    Map<String, Set<String>> snapshot =
        Map.of("minecraft:leaves", Set.of("OAK_LEAVES"));

    CompiledUnsafeSet baseNoSnapshot =
        SafetyCompilationCache.getOrCompile(List.of("LAVA", "FIRE"));
    CompiledUnsafeSet compiledWithSnapshot =
        SafetyCompilationCache.getOrCompile(List.of("LAVA", "FIRE"), snapshot, null);

    assertSame(baseNoSnapshot, compiledWithSnapshot,
        "tag-free token sets shall reuse the underlying cache entry verbatim");
  }

  @Test
  @DisplayName("wildcard state predicates survive tag expansion unchanged")
  void wildcardSurvivesExpansion() {
    Map<String, Set<String>> snapshot =
        Map.of("minecraft:leaves", Set.of("OAK_LEAVES"));

    CompiledUnsafeSet compiled =
        SafetyCompilationCache.getOrCompile(
            List.of("#minecraft:leaves", "*[lit=true]"), snapshot, null);

    assertTrue(compiled.hasWildcardStatePredicate());
    assertTrue(compiled.plainMaterials().contains("OAK_LEAVES"));
    // Wildcard still rejects any lit block.
    assertTrue(compiled.isUnsafe("CAMPFIRE", Map.of("lit", "true")));
    assertFalse(compiled.isUnsafe("CAMPFIRE", Map.of("lit", "false")));
  }
}
