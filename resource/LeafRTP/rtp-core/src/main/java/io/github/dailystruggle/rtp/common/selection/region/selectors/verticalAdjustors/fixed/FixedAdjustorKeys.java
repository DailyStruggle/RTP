package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.fixed;

/**
 * Configurable parameters for the {@link FixedAdjustor}.
 *
 * <p>The fixed adjustor places the player at a single configured Y level in
 * mid-air, with no terrain scan. Intended for skyblock-style worlds where a
 * platform is built around the player after teleport (see the platform tool
 * in the Bukkit / Folia world adapters).</p>
 */
public enum FixedAdjustorKeys {
  /**
   * The exact Y-level at which the player shall be placed. The destination
   * cell {@code (x, y, z)} and the head cell {@code (x, y+1, z)} must both
   * be air; any non-air block in either cell is treated as unsafe and the
   * adjustor reports no acceptable Y for that chunk.
   */
  y
}
