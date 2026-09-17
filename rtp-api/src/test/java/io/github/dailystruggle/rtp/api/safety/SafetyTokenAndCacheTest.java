package io.github.dailystruggle.rtp.api.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SafetyTokenAndCacheTest {

  @BeforeEach
  void setUp() {
    SafetyCompilationCache.clear();
  }

  @Test
  @DisplayName("SafetyToken factory methods, kind, and invariants")
  void testSafetyTokenBasics() {
    SafetyToken mat = SafetyToken.material("STONE", List.of(), "STONE");
    assertEquals(SafetyToken.Kind.MATERIAL, mat.kind());
    assertEquals("STONE", mat.identifier());
    assertFalse(mat.isWildcard());
    assertFalse(mat.isPredicated());
    assertEquals("STONE", mat.sourceToken());

    SafetyToken tag = SafetyToken.tag("minecraft:beds", List.of(), "#minecraft:beds");
    assertEquals(SafetyToken.Kind.TAG, tag.kind());
    assertEquals("minecraft:beds", tag.identifier());
    assertFalse(tag.isWildcard());
    assertFalse(tag.isPredicated());

    StatePredicate pred = new StatePredicate(Map.of("lit", "true"), "lit=true");
    SafetyToken predicated = SafetyToken.material("CAMPFIRE", List.of(pred), "CAMPFIRE[lit=true]");
    assertTrue(predicated.isPredicated());
    assertEquals(1, predicated.predicates().size());

    SafetyToken wildcard = SafetyToken.material("*", List.of(pred), "*[lit=true]");
    assertTrue(wildcard.isWildcard());
    assertTrue(wildcard.isPredicated());
    assertTrue(wildcard.toString().contains("SafetyToken"));

    // Equals and hashcode
    SafetyToken matSame = SafetyToken.material("STONE", List.of(), "STONE");
    SafetyToken matDiff = SafetyToken.material("DIRT", List.of(), "DIRT");
    SafetyToken matPred = SafetyToken.material("STONE", List.of(pred), "STONE[lit=true]");
    assertTrue(mat.equals(mat));
    assertEquals(mat, matSame);
    assertEquals(mat.hashCode(), matSame.hashCode());
    assertFalse(mat.equals(null));
    assertFalse(mat.equals("str"));
    assertFalse(mat.equals(matDiff));
    assertFalse(mat.equals(matPred));
    assertFalse(mat.equals(tag));

    // Bare wildcard rejected
    assertThrows(IllegalArgumentException.class, () -> SafetyToken.material("*", List.of(), "*"));
    assertThrows(IllegalArgumentException.class, () -> SafetyToken.material("*", null, "*"));
    assertThrows(IllegalArgumentException.class, () -> SafetyToken.material("", List.of(), ""));

    // Tag validation
    assertThrows(IllegalArgumentException.class, () -> SafetyToken.tag("no_colon", List.of(), "no_colon"));
    assertThrows(NullPointerException.class, () -> SafetyToken.tag(null, List.of(), ""));

    // Null checks
    assertThrows(NullPointerException.class, () -> SafetyToken.material(null, List.of(), ""));
  }

  @Test
  @DisplayName("StatePredicate numeric comparison and property matching")
  void testStatePredicateEvaluation() {
    StatePredicate.NumericComparison cmpGte = new StatePredicate.NumericComparison("level", StatePredicate.Comparator.GE, 5);
    StatePredicate predGte = new StatePredicate(Map.of(), List.of(cmpGte), "level>=5");
    assertTrue(predGte.matches(Map.of("level", "5")));
    assertTrue(predGte.matches(Map.of("level", "8")));
    assertFalse(predGte.matches(Map.of("level", "3")));
    assertFalse(predGte.matches(Map.of("level", "not-a-number")));

    StatePredicate.NumericComparison cmpLte = new StatePredicate.NumericComparison("level", StatePredicate.Comparator.LE, 5);
    StatePredicate predLte = new StatePredicate(Map.of(), List.of(cmpLte), "level<=5");
    assertTrue(predLte.matches(Map.of("level", "5")));
    assertTrue(predLte.matches(Map.of("level", "2")));
    assertFalse(predLte.matches(Map.of("level", "7")));

    StatePredicate.NumericComparison cmpGt = new StatePredicate.NumericComparison("level", StatePredicate.Comparator.GT, 5);
    StatePredicate predGt = new StatePredicate(Map.of(), List.of(cmpGt), "level>5");
    assertTrue(predGt.matches(Map.of("level", "6")));
    assertFalse(predGt.matches(Map.of("level", "5")));

    StatePredicate.NumericComparison cmpLt = new StatePredicate.NumericComparison("level", StatePredicate.Comparator.LT, 5);
    StatePredicate predLt = new StatePredicate(Map.of(), List.of(cmpLt), "level<5");
    assertTrue(predLt.matches(Map.of("level", "4")));
    assertFalse(predLt.matches(Map.of("level", "5")));

    StatePredicate predEq = new StatePredicate(Map.of("facing", "north"), "facing=north");
    assertTrue(predEq.matches(Map.of("facing", "north")));
    assertFalse(predEq.matches(Map.of("facing", "south")));
    assertFalse(predEq.matches(Collections.emptyMap()));
    assertFalse(predEq.matches(null));

    // Equals, hashCode, toString
    StatePredicate predEqSame = new StatePredicate(Map.of("facing", "north"), "facing=north");
    StatePredicate predEqDiff = new StatePredicate(Map.of("facing", "south"), "facing=south");
    assertTrue(predEq.equals(predEq));
    assertEquals(predEq, predEqSame);
    assertEquals(predEq.hashCode(), predEqSame.hashCode());
    assertFalse(predEq.equals(null));
    assertFalse(predEq.equals("other"));
    assertFalse(predEq.equals(predEqDiff));
    assertTrue(predEq.toString().contains("StatePredicate"));

    // NumericComparison equals, hashCode, toString, getters
    assertEquals("level", cmpGte.key());
    assertEquals(StatePredicate.Comparator.GE, cmpGte.op());
    assertEquals(5, cmpGte.bound());
    assertEquals(">=", StatePredicate.Comparator.GE.symbol());
    assertEquals("<=", StatePredicate.Comparator.LE.symbol());
    assertEquals(">", StatePredicate.Comparator.GT.symbol());
    assertEquals("<", StatePredicate.Comparator.LT.symbol());

    StatePredicate.NumericComparison cmpGteSame = new StatePredicate.NumericComparison("level", StatePredicate.Comparator.GE, 5);
    StatePredicate.NumericComparison cmpGteDiffKey = new StatePredicate.NumericComparison("other", StatePredicate.Comparator.GE, 5);
    StatePredicate.NumericComparison cmpGteDiffOp = new StatePredicate.NumericComparison("level", StatePredicate.Comparator.LE, 5);
    StatePredicate.NumericComparison cmpGteDiffBound = new StatePredicate.NumericComparison("level", StatePredicate.Comparator.GE, 6);
    assertTrue(cmpGte.equals(cmpGte));
    assertEquals(cmpGte, cmpGteSame);
    assertEquals(cmpGte.hashCode(), cmpGteSame.hashCode());
    assertFalse(cmpGte.equals(null));
    assertFalse(cmpGte.equals("other"));
    assertFalse(cmpGte.equals(cmpGteDiffKey));
    assertFalse(cmpGte.equals(cmpGteDiffOp));
    assertFalse(cmpGte.equals(cmpGteDiffBound));
    assertEquals("level>=5", cmpGte.toString());

    // Validation
    assertThrows(NullPointerException.class, () -> new StatePredicate.NumericComparison(null, StatePredicate.Comparator.GE, 0));
    assertThrows(NullPointerException.class, () -> new StatePredicate.NumericComparison("k", null, 0));
    assertThrows(NullPointerException.class, () -> new StatePredicate(null, ""));
    assertThrows(NullPointerException.class, () -> new StatePredicate(Map.of(), null));
    assertThrows(IllegalArgumentException.class, () -> new StatePredicate(Map.of(), "token"));
    assertThrows(IllegalArgumentException.class, () -> new StatePredicate(Map.of(), List.of(), "token"));
  }

  @Test
  @DisplayName("SafetyCompilationCache caching, tag expansion, and rejection sink")
  void testSafetyCompilationCache() {
    assertEquals(0, SafetyCompilationCache.size());

    // Empty or null inputs
    assertEquals(CompiledUnsafeSet.EMPTY, SafetyCompilationCache.getOrCompile(null));
    assertEquals(CompiledUnsafeSet.EMPTY, SafetyCompilationCache.getOrCompile(List.of()));

    // Cache hit
    CompiledUnsafeSet set1 = SafetyCompilationCache.getOrCompile(List.of("LAVA", "FIRE"));
    CompiledUnsafeSet set2 = SafetyCompilationCache.getOrCompile(List.of("LAVA", "FIRE"));
    assertEquals(set1, set2);
    assertTrue(SafetyCompilationCache.size() >= 1);

    // Rejection sink
    java.util.concurrent.atomic.AtomicInteger rejections = new java.util.concurrent.atomic.AtomicInteger(0);
    SafetyCompilationCache.getOrCompile(List.of("VALID", "invalid[unclosed"), r -> rejections.incrementAndGet());
    assertTrue(rejections.get() >= 1);

    // Tag expansion variant
    Map<String, Set<String>> tags = Map.of("minecraft:beds", Set.of("RED_BED", "BLUE_BED"));
    CompiledUnsafeSet expanded = SafetyCompilationCache.getOrCompile(List.of("#minecraft:beds"), tags, null);
    assertTrue(expanded.isUnsafe("RED_BED", Map.of()));
    assertTrue(expanded.isUnsafe("BLUE_BED", Map.of()));
    assertFalse(expanded.isUnsafe("WHITE_BED", Map.of()));

    // Wildcard in compiled set
    CompiledUnsafeSet wildcardSet = SafetyCompilationCache.getOrCompile(List.of("*[waterlogged=true]"));
    assertTrue(wildcardSet.hasWildcardStatePredicate());
    assertTrue(wildcardSet.isUnsafe("STONE", Map.of("waterlogged", "true")));
    assertFalse(wildcardSet.isUnsafe("STONE", Map.of("waterlogged", "false")));

    SafetyCompilationCache.clear();
    assertEquals(0, SafetyCompilationCache.size());
  }

  @Test
  @DisplayName("CompiledUnsafeSet models and evaluation branches")
  void testCompiledUnsafeSetDetails() {
    assertTrue(CompiledUnsafeSet.EMPTY.isEmpty());
    assertFalse(CompiledUnsafeSet.EMPTY.hasWildcardStatePredicate());
    assertTrue(CompiledUnsafeSet.EMPTY.plainMaterials().isEmpty());
    assertTrue(CompiledUnsafeSet.EMPTY.plainTags().isEmpty());
    assertTrue(CompiledUnsafeSet.EMPTY.materialStatePredicates().isEmpty());
    assertTrue(CompiledUnsafeSet.EMPTY.tagStatePredicates().isEmpty());
    assertTrue(CompiledUnsafeSet.EMPTY.wildcardStatePredicates().isEmpty());
    assertFalse(CompiledUnsafeSet.EMPTY.isUnsafe("STONE", Map.of()));
    assertFalse(CompiledUnsafeSet.EMPTY.isUnsafe(null, Map.of()));
    assertFalse(CompiledUnsafeSet.EMPTY.isUnsafe("", Map.of()));

    StatePredicate pred = new StatePredicate(Map.of("lit", "true"), "lit=true");
    SafetyToken matPlain = SafetyToken.material("LAVA", List.of(), "LAVA");
    SafetyToken tagPlain = SafetyToken.tag("minecraft:beds", List.of(), "#minecraft:beds");
    SafetyToken tagPred = SafetyToken.tag("minecraft:campfires", List.of(pred), "#minecraft:campfires[lit=true]");
    SafetyToken wildcard = SafetyToken.material("*", List.of(pred), "*[lit=true]");

    java.util.List<SafetyToken> tokensWithNull = new java.util.ArrayList<>();
    tokensWithNull.add(null);
    tokensWithNull.add(matPlain);
    tokensWithNull.add(tagPlain);
    tokensWithNull.add(tagPred);
    tokensWithNull.add(wildcard);

    CompiledUnsafeSet set = CompiledUnsafeSet.compile(tokensWithNull);
    assertFalse(set.isEmpty());
    assertTrue(set.hasWildcardStatePredicate());
    assertEquals(Set.of("LAVA"), set.plainMaterials());
    assertEquals(Set.of("minecraft:beds"), set.plainTags());
    assertEquals(1, set.tagStatePredicates().size());
    assertEquals(1, set.wildcardStatePredicates().size());
    assertTrue(set.toString().contains("CompiledUnsafeSet"));

    // Plain material hit
    assertTrue(set.isUnsafe("LAVA", Map.of()));

    // Plain tag hit
    assertTrue(set.isUnsafe("RED_BED", List.of("minecraft:beds"), Map.of()));
    assertFalse(set.isUnsafe("RED_BED", List.of("minecraft:other"), Map.of()));

    // Tag predicated hit
    assertTrue(set.isUnsafe("CAMPFIRE", List.of("minecraft:campfires"), Map.of("lit", "true")));
    assertFalse(set.isUnsafe("CAMPFIRE", List.of("minecraft:campfires"), Map.of("lit", "false")));

    // Wildcard hit
    assertTrue(set.isUnsafe("ANY_BLOCK", List.of(), Map.of("lit", "true")));
    assertFalse(set.isUnsafe("ANY_BLOCK", List.of(), Map.of("lit", "false")));
    assertFalse(set.isUnsafe("ANY_BLOCK", List.of(), Map.of()));

    // withTagsExpanded
    Map<String, Set<String>> tags = Map.of(
        "minecraft:beds", Set.of("RED_BED", "LAVA"), // LAVA is already plain material
        "minecraft:campfires", Set.of("CAMPFIRE", "LAVA")
    );
    CompiledUnsafeSet expanded = set.withTagsExpanded(tags);
    assertTrue(expanded.plainMaterials().contains("RED_BED"));
    assertTrue(expanded.plainMaterials().contains("LAVA"));
    assertTrue(expanded.materialStatePredicates().containsKey("CAMPFIRE"));
    assertFalse(expanded.materialStatePredicates().containsKey("LAVA")); // skip already plain unsafe

    // Tagless set returns itself on withTagsExpanded
    CompiledUnsafeSet noTags = CompiledUnsafeSet.compile(List.of(matPlain));
    assertEquals(noTags, noTags.withTagsExpanded(tags));

    // normalizeLiveProperties
    assertTrue(CompiledUnsafeSet.normalizeLiveProperties(null).isEmpty());
    assertTrue(CompiledUnsafeSet.normalizeLiveProperties(Map.of()).isEmpty());
    java.util.Map<String, String> rawProps = new java.util.HashMap<>();
    rawProps.put("FACING", "NORTH");
    rawProps.put(null, "val");
    rawProps.put("key", null);
    Map<String, String> norm = CompiledUnsafeSet.normalizeLiveProperties(rawProps);
    assertEquals("north", norm.get("facing"));
    assertEquals(1, norm.size());

    // Equals & hashCode
    CompiledUnsafeSet setSame = CompiledUnsafeSet.compile(tokensWithNull);
    assertTrue(set.equals(set));
    assertEquals(set, setSame);
    assertEquals(set.hashCode(), setSame.hashCode());
    assertFalse(set.equals(null));
    assertFalse(set.equals("other"));
    assertFalse(set.equals(noTags));
  }
}
