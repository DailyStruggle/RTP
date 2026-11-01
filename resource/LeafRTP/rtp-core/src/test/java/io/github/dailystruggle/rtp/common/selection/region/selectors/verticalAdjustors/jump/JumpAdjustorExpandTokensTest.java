package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Probe-path safety-token expansion in {@link JumpAdjustor} for set-subtraction tokens
 * (ADR-017 section 1), plus the unloaded-world guards of the live adjust paths.
 */
class JumpAdjustorExpandTokensTest {

  @BeforeAll
  static void setUp() {
    RTP.ensureInitialized();
  }

  @SuppressWarnings("unchecked")
  private static Set<String> expand(Map<String, Set<String>> tags, String... tokens) throws Exception {
    Method m = JumpAdjustor.class.getDeclaredMethod("expandTokens", Object.class, Map.class, Set.class);
    m.setAccessible(true);
    Set<String> sink = new HashSet<>();
    m.invoke(null, new ArrayList<>(Arrays.asList(tokens)), tags, sink);
    return sink;
  }

  private static Map<String, Set<String>> tags() {
    Map<String, Set<String>> tags = new HashMap<>();
    Set<String> leaves = new HashSet<>();
    leaves.add("minecraft:oak_leaves");
    leaves.add("minecraft:birch_leaves");
    leaves.add("minecraft:azalea_leaves");
    leaves.add(null);
    tags.put("minecraft:leaves", leaves);
    Set<String> azaleas = new HashSet<>();
    azaleas.add("minecraft:azalea_leaves");
    azaleas.add(null);
    tags.put("minecraft:azaleas", azaleas);
    tags.put("minecraft:slabs", Set.of("minecraft:oak_slab", "minecraft:stone_slab"));
    return tags;
  }

  @Test
  @DisplayName("Tag base minus tag/unknown-tag/predicated subtrahends keeps only unsubtracted members")
  void tagBaseWithMixedSubtrahends() throws Exception {
    Set<String> out = expand(tags(),
        "#minecraft:leaves - #minecraft:azaleas - #minecraft:unknown - OAK_LEAVES[persistent=true]");
    assertEquals(Set.of("OAK_LEAVES", "BIRCH_LEAVES"), out,
        "predicated subtrahends are skipped on the probe path; null tag members are ignored");
  }

  @Test
  @DisplayName("Unresolved tag base with subtraction contributes nothing")
  void unresolvedTagBase() throws Exception {
    assertTrue(expand(tags(), "#minecraft:missing - STONE").isEmpty());
  }

  @Test
  @DisplayName("Material base with subtraction is canonicalised unless subtracted")
  void materialBase() throws Exception {
    assertEquals(Set.of("STONE"), expand(tags(), "stone - DIRT"));
    assertTrue(expand(tags(), "DIRT - DIRT").isEmpty());
  }

  @Test
  @DisplayName("Predicated bases with subtraction are dropped on the probe path")
  void predicatedBasesDropped() throws Exception {
    assertTrue(expand(tags(), "#minecraft:slabs[type=top] - OAK_SLAB").isEmpty());
    assertTrue(expand(tags(), "CAMPFIRE[lit=true] - LAVA").isEmpty());
  }

  @Test
  @DisplayName("Live adjust paths reject a chunk whose world is not available")
  void nullWorldGuards() {
    JumpAdjustor adjustor = new JumpAdjustor(new ArrayList<>());
    adjustor.set(JumpAdjustorKeys.minY, 32L);
    adjustor.set(JumpAdjustorKeys.maxY, 127L);
    RTPChunk chunk = mock(RTPChunk.class);
    when(chunk.getWorld()).thenReturn(null);

    MutableRTPCoords output = new MutableRTPCoords("w", 0, 0, 0);
    assertFalse(adjustor.adjust(chunk, output));
    assertNull(adjustor.adjustColumn(chunk, 7, 7));
  }

  @Test
  @DisplayName("Adjustor exposes its keys and parameters")
  void keysAndParameters() {
    JumpAdjustor adjustor = new JumpAdjustor(new ArrayList<>());
    List<String> keys = new ArrayList<>(adjustor.keys());
    assertTrue(keys.contains(JumpAdjustorKeys.maxY.name()));
    assertTrue(adjustor.getParameters().containsKey("requireskylight"));
  }
}
