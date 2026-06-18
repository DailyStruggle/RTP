package io.github.dailystruggle.rtp.common.commands;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the corrected {@code /rtp} world-argument semantics.
 *
 * <p>The world-override region (duplicate the base region and rebind it to the
 * requested world) must apply ONLY to the region sub-parameter grammar
 * {@code rtp region=<r> world=<w>}. Top-level {@code rtp world=<w>} must resolve
 * its region from world {@code w}'s own configuration ({@code WorldKeys.region})
 * and is left unmodified, so a world configured to redirect to region
 * {@code default} teleports to region {@code default} as configured rather than
 * being forced into world {@code w}.
 *
 * <p>This supersedes the original ADR-065 behavior, which forced both grammars
 * into world {@code w} (and so ignored the per-world {@code region} redirect for
 * the top-level {@code world=} parameter).
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
