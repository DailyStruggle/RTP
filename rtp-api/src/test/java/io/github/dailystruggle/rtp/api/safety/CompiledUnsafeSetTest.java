package io.github.dailystruggle.rtp.api.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Evaluation-semantics tests for {@link CompiledUnsafeSet}.
 *
 * <p>Verifies ADR-017 &sect;2/&sect;4 bucket classification and hot-path ordering,
 * including the wildcard fast-path flag and the fail-open behaviour when property maps
 * are missing or incomplete.</p>
 */
@DisplayName("REQ-RTP-S-001 / ADR-017 - CompiledUnsafeSet")
class CompiledUnsafeSetTest {

  // Matches what the platform adapter would hand us: lowercase key, lowercase value.
  private static Map<String, String> props(String... kv) {
    if (kv.length % 2 != 0) throw new IllegalArgumentException("need pairs");
    Map<String, String> out = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) out.put(kv[i], kv[i + 1]);
    return out;
  }

  private static CompiledUnsafeSet compile(String... rawTokens) {
    return CompiledUnsafeSet.compile(
        SafetyTokenParser.parseAll(Arrays.asList(rawTokens)).accepted());
  }

  @Test
  @DisplayName("EMPTY singleton is returned for null/empty input")
  void emptyInput() {
    assertSame(CompiledUnsafeSet.EMPTY, CompiledUnsafeSet.compile(null));
    assertSame(CompiledUnsafeSet.EMPTY, CompiledUnsafeSet.compile(Collections.emptyList()));
    assertTrue(CompiledUnsafeSet.EMPTY.isEmpty());
    assertFalse(CompiledUnsafeSet.EMPTY.isUnsafe("LAVA", null));
  }

  @Test
  @DisplayName("plain materials bucket into plainMaterials and match on name")
  void plainMaterialBucket() {
    CompiledUnsafeSet c = compile("LAVA", "FIRE");
    assertEquals(Set.of("LAVA", "FIRE"), c.plainMaterials());
    assertTrue(c.isUnsafe("LAVA", null));
    assertTrue(c.isUnsafe("FIRE", null));
    assertFalse(c.isUnsafe("STONE", null));
  }

  @Test
  @DisplayName("duplicate plain materials are coalesced")
  void plainMaterialDedup() {
    CompiledUnsafeSet c = compile("LAVA", "lava", "minecraft:lava");
    // The parser normalises names, but ADR-017 does NOT specify that the parser strips
    // the 'minecraft:' namespace on a bare material; the current parser rejects a
    // colon-containing identifier as illegal. Verify LAVA/lava collapse and the third
    // token lands in rejected (implementation detail of this parser).
    assertEquals(Set.of("LAVA"), c.plainMaterials());
  }

  @Test
  @DisplayName("plain tag tokens bucket into plainTags")
  void plainTagBucket() {
    CompiledUnsafeSet c = compile("#minecraft:leaves");
    assertEquals(Set.of("minecraft:leaves"), c.plainTags());
    assertTrue(c.isUnsafe("OAK_LEAVES",
        Collections.singletonList("minecraft:leaves"), null));
    assertFalse(c.isUnsafe("OAK_LEAVES",
        Collections.singletonList("minecraft:logs"), null),
        "plain tag only matches when live membership includes that tag");
  }

  @Test
  @DisplayName("material state predicate rejects only on property match")
  void materialStatePredicate() {
    CompiledUnsafeSet c = compile("OAK_SLAB[waterlogged=true]");
    assertEquals(1, c.materialStatePredicates().size());
    assertTrue(c.isUnsafe("OAK_SLAB", props("waterlogged", "true")));
    assertFalse(c.isUnsafe("OAK_SLAB", props("waterlogged", "false")),
        "OAK_SLAB without waterlogged=true must remain safe");
    assertFalse(c.isUnsafe("OAK_SLAB", null),
        "missing property map → predicate fails to match (fail-open)");
    assertFalse(c.isUnsafe("STONE", props("waterlogged", "true")),
        "material-scoped predicate must not apply to other materials");
  }

  @Test
  @DisplayName("tag state predicate requires live tag membership")
  void tagStatePredicate() {
    CompiledUnsafeSet c = compile("#minecraft:slabs[waterlogged=true]");
    assertTrue(c.isUnsafe("OAK_SLAB",
        Collections.singletonList("minecraft:slabs"),
        props("waterlogged", "true")));
    assertFalse(c.isUnsafe("OAK_SLAB",
        Collections.emptyList(),
        props("waterlogged", "true")),
        "no live tag snapshot → tag predicate cannot fire (fail-open)");
    assertFalse(c.isUnsafe("OAK_SLAB",
        Collections.singletonList("minecraft:slabs"),
        props("waterlogged", "false")),
        "predicate mismatch → safe");
  }

  @Test
  @DisplayName("wildcard predicate fires for every material when property matches")
  void wildcardPredicate() {
    CompiledUnsafeSet c = compile("*[waterlogged=true]");
    assertTrue(c.hasWildcardStatePredicate());
    assertTrue(c.isUnsafe("OAK_SLAB", props("waterlogged", "true")));
    assertTrue(c.isUnsafe("IRON_BARS", props("waterlogged", "true")));
    assertTrue(c.isUnsafe("LANTERN", props("waterlogged", "true")));
    assertFalse(c.isUnsafe("STONE", props("waterlogged", "false")));
    assertFalse(c.isUnsafe("STONE", null),
        "no property map → wildcard predicate cannot match");
  }

  @Test
  @DisplayName("hasWildcardStatePredicate is false when no wildcard is configured")
  void wildcardFlagOff() {
    CompiledUnsafeSet c = compile("LAVA", "OAK_SLAB[waterlogged=true]", "#minecraft:leaves");
    assertFalse(c.hasWildcardStatePredicate());
  }

  @Test
  @DisplayName("AND-combined multi-predicate requires every property to match")
  void multiPredicateAnd() {
    CompiledUnsafeSet c = compile("OAK_SLAB[waterlogged=true,type=top]");
    assertTrue(c.isUnsafe("OAK_SLAB", props("waterlogged", "true", "type", "top")));
    assertFalse(c.isUnsafe("OAK_SLAB", props("waterlogged", "true", "type", "bottom")),
        "second property mismatch → overall miss");
    assertFalse(c.isUnsafe("OAK_SLAB", props("waterlogged", "true")),
        "missing second property → overall miss (fail-open)");
  }

  @Test
  @DisplayName("evaluation order: plain material beats state predicate (short-circuit)")
  void plainBeforeState() {
    CompiledUnsafeSet c = compile("OAK_SLAB", "OAK_SLAB[waterlogged=true]");
    // Plain name matches immediately; we never need properties.
    assertTrue(c.isUnsafe("OAK_SLAB", null));
  }

  @Test
  @DisplayName("buckets compose: one set can carry material+tag+wildcard+state entries")
  void composedBuckets() {
    CompiledUnsafeSet c = compile(
        "LAVA",
        "#minecraft:leaves",
        "OAK_SLAB[waterlogged=true]",
        "#minecraft:slabs[waterlogged=true]",
        "*[lit=true]");
    assertEquals(Set.of("LAVA"), c.plainMaterials());
    assertEquals(Set.of("minecraft:leaves"), c.plainTags());
    assertEquals(Set.of("OAK_SLAB"), c.materialStatePredicates().keySet());
    assertEquals(Set.of("minecraft:slabs"), c.tagStatePredicates().keySet());
    assertTrue(c.hasWildcardStatePredicate());

    // Spot-check evaluation for each bucket.
    assertTrue(c.isUnsafe("LAVA", null));
    assertTrue(c.isUnsafe("OAK_LEAVES",
        Collections.singletonList("minecraft:leaves"), null));
    assertTrue(c.isUnsafe("OAK_SLAB", props("waterlogged", "true")));
    assertTrue(c.isUnsafe("SPRUCE_SLAB",
        Collections.singletonList("minecraft:slabs"),
        props("waterlogged", "true")));
    assertTrue(c.isUnsafe("CAMPFIRE", props("lit", "true")));
  }

  @Test
  @DisplayName("numeric range predicate: material rejected only when bound is satisfied")
  void numericRangeMaterial() {
    CompiledUnsafeSet c = compile("LAVA[level<=3]");
    assertEquals(Set.of("LAVA"), c.materialStatePredicates().keySet());
    assertTrue(c.isUnsafe("LAVA", props("level", "0")));
    assertTrue(c.isUnsafe("LAVA", props("level", "3")));
    assertFalse(c.isUnsafe("LAVA", props("level", "4")),
        "level above the bound → safe");
    assertFalse(c.isUnsafe("LAVA", props("level", "notanumber")),
        "non-numeric live value → miss (fail-open)");
    assertFalse(c.isUnsafe("LAVA", null),
        "missing property map → miss (fail-open)");
  }

  @Test
  @DisplayName("bounded interval (two bounds on one key) requires both to hold")
  void numericRangeInterval() {
    CompiledUnsafeSet c = compile("LAVA[level>=2,level<=5]");
    assertFalse(c.isUnsafe("LAVA", props("level", "1")));
    assertTrue(c.isUnsafe("LAVA", props("level", "2")));
    assertTrue(c.isUnsafe("LAVA", props("level", "5")));
    assertFalse(c.isUnsafe("LAVA", props("level", "6")));
  }

  @Test
  @DisplayName("equality and range combine: both must match")
  void mixedEqualityAndRangeEval() {
    CompiledUnsafeSet c = compile("WATER[falling=true,level>=5]");
    assertTrue(c.isUnsafe("WATER", props("falling", "true", "level", "5")));
    assertFalse(c.isUnsafe("WATER", props("falling", "false", "level", "5")),
        "equality mismatch → miss");
    assertFalse(c.isUnsafe("WATER", props("falling", "true", "level", "4")),
        "range mismatch → miss");
  }

  @Test
  @DisplayName("wildcard numeric range fires for any material on the bound")
  void wildcardNumericRange() {
    CompiledUnsafeSet c = compile("*[level>=8]");
    assertTrue(c.hasWildcardStatePredicate());
    assertTrue(c.isUnsafe("LAVA", props("level", "8")));
    assertTrue(c.isUnsafe("WATER", props("level", "15")));
    assertFalse(c.isUnsafe("WATER", props("level", "7")));
  }

  @Test
  @DisplayName("null/empty material name never matches")
  void nullMaterial() {
    CompiledUnsafeSet c = compile("LAVA", "*[waterlogged=true]");
    assertFalse(c.isUnsafe(null, props("waterlogged", "true")));
    assertFalse(c.isUnsafe("", props("waterlogged", "true")));
  }

  @Test
  @DisplayName("returned collections are unmodifiable")
  void unmodifiable() {
    CompiledUnsafeSet c = compile("LAVA", "OAK_SLAB[waterlogged=true]",
        "#minecraft:leaves", "#minecraft:slabs[waterlogged=true]", "*[lit=true]");
    assertThrows(UnsupportedOperationException.class,
        () -> c.plainMaterials().add("STONE"));
    assertThrows(UnsupportedOperationException.class,
        () -> c.plainTags().add("minecraft:x"));
    assertThrows(UnsupportedOperationException.class,
        () -> c.materialStatePredicates().clear());
    assertThrows(UnsupportedOperationException.class,
        () -> c.tagStatePredicates().clear());
    assertThrows(UnsupportedOperationException.class,
        () -> c.wildcardStatePredicates().clear());
  }

  @Test
  @DisplayName("normalizeLiveProperties lowercases keys and values")
  void normalizeLive() {
    Map<String, String> out = CompiledUnsafeSet.normalizeLiveProperties(
        props("WATERLOGGED", "True", "FACE", "CEILING"));
    assertEquals("true", out.get("waterlogged"));
    assertEquals("ceiling", out.get("face"));
    assertThrows(UnsupportedOperationException.class, () -> out.put("x", "y"));
    assertTrue(CompiledUnsafeSet.normalizeLiveProperties(null).isEmpty());
  }

  @Test
  @DisplayName("StatePredicate.matches: missing property is a miss (fail-open)")
  void statePredicateFailOpen() {
    StatePredicate p = new StatePredicate(
        Collections.singletonMap("waterlogged", "true"), "src");
    assertTrue(p.matches(props("waterlogged", "true")));
    assertFalse(p.matches(props("waterlogged", "false")));
    assertFalse(p.matches(Collections.emptyMap()));
    assertFalse(p.matches(null));
  }

  @Test
  @DisplayName("StatePredicate rejects empty property map at construction")
  void statePredicateEmptyConstruction() {
    assertThrows(IllegalArgumentException.class,
        () -> new StatePredicate(Collections.emptyMap(), "src"));
    assertThrows(NullPointerException.class,
        () -> new StatePredicate(null, "src"));
  }
}
