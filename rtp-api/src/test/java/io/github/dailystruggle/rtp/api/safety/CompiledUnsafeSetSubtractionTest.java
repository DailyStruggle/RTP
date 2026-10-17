package io.github.dailystruggle.rtp.api.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Subtraction-clause semantics of {@link CompiledUnsafeSet} built from hand-assembled
 * {@link SafetyToken}s, covering token shapes the textual parser does not emit directly.
 */
@DisplayName("REQ-RTP-S-001 / ADR-017 - CompiledUnsafeSet subtraction edge cases")
class CompiledUnsafeSetSubtractionTest {

  private static final String SLABS = "minecraft:slabs";
  private static final String WOODEN = "minecraft:wooden_slabs";

  private static StatePredicate waterlogged() {
    return new StatePredicate(Map.of("waterlogged", "true"), "[waterlogged=true]");
  }

  private static SafetyToken mat(String id) {
    return SafetyToken.material(id, null, id);
  }

  private static SafetyToken matPred(String id) {
    return SafetyToken.material(id, List.of(waterlogged()), id + "[waterlogged=true]");
  }

  private static SafetyToken tag(String id) {
    return SafetyToken.tag(id, null, "#" + id);
  }

  @Test
  @DisplayName("plain material subtracting a tag or a predicated self is not self-subtracted")
  void materialNotSelfSubtracted() {
    SafetyToken lava = SafetyToken.material("LAVA", null,
        List.of(tag(WOODEN), matPred("LAVA")), "LAVA - ...");
    CompiledUnsafeSet c = CompiledUnsafeSet.compile(List.of(lava));
    assertEquals(Set.of("LAVA"), c.plainMaterials());
    assertTrue(c.isUnsafe("LAVA", null));
  }

  @Test
  @DisplayName("withTagsExpanded removes a plain material subtracted by a tag snapshot")
  void plainMaterialSubtractedByTagOnExpand() {
    SafetyToken oak = SafetyToken.material("OAK_SLAB", null, List.of(tag(WOODEN)), "OAK_SLAB - #wooden");
    SafetyToken stone = SafetyToken.material("STONE", null, List.of(tag("minecraft:missing")), "STONE - #missing");
    CompiledUnsafeSet c = CompiledUnsafeSet.compile(List.of(oak, stone, tag("minecraft:leaves")));
    CompiledUnsafeSet expanded = c.withTagsExpanded(Map.of(
        WOODEN, Set.of("minecraft:oak_slab"),
        "minecraft:leaves", Set.of("OAK_LEAVES")));
    assertFalse(expanded.plainMaterials().contains("OAK_SLAB"));
    assertTrue(expanded.plainMaterials().contains("STONE"),
        "subtracting an unknown tag leaves the material intact");
    assertTrue(expanded.plainMaterials().contains("OAK_LEAVES"));
  }

  @Test
  @DisplayName("withTagsExpanded drops a predicated material subtracted by itself")
  void predicatedMaterialSelfSubtractionOnExpand() {
    SafetyToken oak = SafetyToken.material("OAK_SLAB", List.of(waterlogged()),
        List.of(mat("minecraft:oak_slab")), "OAK_SLAB[waterlogged=true] - OAK_SLAB");
    SafetyToken birch = SafetyToken.material("BIRCH_SLAB", List.of(waterlogged()),
        List.of(mat("SPRUCE_SLAB")), "BIRCH_SLAB[waterlogged=true] - SPRUCE_SLAB");
    CompiledUnsafeSet c = CompiledUnsafeSet.compile(List.of(oak, birch, tag("minecraft:leaves")));
    assertTrue(c.materialStatePredicates().containsKey("OAK_SLAB"));

    CompiledUnsafeSet expanded = c.withTagsExpanded(Collections.emptyMap());
    assertFalse(expanded.materialStatePredicates().containsKey("OAK_SLAB"));
    assertTrue(expanded.materialStatePredicates().containsKey("BIRCH_SLAB"));
  }

  @Test
  @DisplayName("withTagsExpanded keeps predicated tag members, removing only subtracted ones")
  void predicatedTagSubtractionOnExpand() {
    SafetyToken slabs = SafetyToken.tag(SLABS, List.of(waterlogged()),
        List.of(mat("OAK_SLAB")), "#slabs[waterlogged=true] - OAK_SLAB");
    SafetyToken missing = SafetyToken.tag("minecraft:missing", null,
        List.of(mat("STONE")), "#missing - STONE");
    CompiledUnsafeSet c = CompiledUnsafeSet.compile(List.of(slabs, missing));
    CompiledUnsafeSet expanded = c.withTagsExpanded(Map.of(SLABS, Set.of("OAK_SLAB", "BIRCH_SLAB")));
    assertFalse(expanded.materialStatePredicates().containsKey("OAK_SLAB"));
    assertTrue(expanded.materialStatePredicates().containsKey("BIRCH_SLAB"));
    assertTrue(expanded.plainMaterials().isEmpty());
  }

  @Test
  @DisplayName("live tag check honours a predicated material subtraction")
  void livePredicatedMaterialSubtraction() {
    SafetyToken slabs = SafetyToken.tag(SLABS, null, List.of(matPred("OAK_SLAB")), "#slabs - OAK_SLAB[...]");
    CompiledUnsafeSet c = CompiledUnsafeSet.compile(List.of(slabs));
    List<String> live = List.of(SLABS);
    assertFalse(c.isUnsafe("OAK_SLAB", live, Map.of("waterlogged", "true")),
        "subtraction predicate matches -> excluded");
    assertTrue(c.isUnsafe("OAK_SLAB", live, Map.of("waterlogged", "false")),
        "subtraction predicate misses -> still unsafe");
  }

  @Test
  @DisplayName("live tag check honours plain and predicated tag subtractions")
  void liveTagSubtraction() {
    SafetyToken slabs = SafetyToken.tag(SLABS, null, List.of(tag(WOODEN)), "#slabs - #wooden");
    CompiledUnsafeSet c = CompiledUnsafeSet.compile(List.of(slabs));
    assertFalse(c.isUnsafe("OAK_SLAB", List.of(SLABS, WOODEN), null));
    assertTrue(c.isUnsafe("STONE_SLAB", List.of(SLABS), null));

    SafetyToken slabsPred = SafetyToken.tag(SLABS, null,
        List.of(SafetyToken.tag(WOODEN, List.of(waterlogged()), "#wooden[waterlogged=true]")),
        "#slabs - #wooden[waterlogged=true]");
    CompiledUnsafeSet p = CompiledUnsafeSet.compile(List.of(slabsPred));
    assertFalse(p.isUnsafe("OAK_SLAB", List.of(SLABS, WOODEN), Map.of("waterlogged", "true")));
    assertTrue(p.isUnsafe("OAK_SLAB", List.of(SLABS, WOODEN), Map.of("waterlogged", "false")));
  }
}
