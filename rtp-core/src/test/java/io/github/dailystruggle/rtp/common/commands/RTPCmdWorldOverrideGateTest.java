package io.github.dailystruggle.rtp.common.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for {@code /rtp} world-argument semantics.
 * World-override applies only to {@code region=<r> world=<w>}.
 * Top-level {@code world=<w>} resolves region from {@code WorldKeys.region}.
 */
@DisplayName("RTPCmd - world-override gate (region sub-parameter only)")
public class RTPCmdWorldOverrideGateTest {

  @Test
  @DisplayName("top-level `world=x` (no region) does NOT trigger the world-override")
  void topLevelWorldOnly_doesNotOverride() {
    Map<String, List<String>> args = new HashMap<>();
    args.put("world", List.of("nether_world"));
    assertFalse(RTPCmd.shouldApplyWorldOverride(args),
        "top-level `rtp world=x` must honor world x's configured region, not rebind to x");
  }

  @Test
  @DisplayName("`region=y world=x` triggers the world-override")
  void regionAndWorld_overrides() {
    Map<String, List<String>> args = new HashMap<>();
    args.put("region", List.of("default"));
    args.put("world", List.of("nether_world"));
    assertTrue(RTPCmd.shouldApplyWorldOverride(args),
        "`rtp region=y world=x` must duplicate region y and adjust it for world x");
  }

  @Test
  @DisplayName("plain `region=y` (no world) does NOT trigger the world-override")
  void regionOnly_doesNotOverride() {
    Map<String, List<String>> args = new HashMap<>();
    args.put("region", List.of("default"));
    assertFalse(RTPCmd.shouldApplyWorldOverride(args));
  }

  @Test
  @DisplayName("empty / null arg maps do NOT trigger the world-override")
  void emptyOrNull_doesNotOverride() {
    assertFalse(RTPCmd.shouldApplyWorldOverride(new HashMap<>()));
    assertFalse(RTPCmd.shouldApplyWorldOverride(null));
  }
}
