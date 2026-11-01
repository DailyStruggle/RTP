package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import org.jetbrains.annotations.Nullable;

/** Resolves the already-bound world used to place BIT mock players. */
final class MockProbeWorld {
  static final String DEFAULT_REGION = "default";

  private MockProbeWorld() {}

  @Nullable
  static RTPWorld<?> resolve(String regionName) {
    SelectionAPI selection = ActiveSelection.get();
    Region region = selection == null ? null : selection.getRegion(regionName);
    return region == null ? null : region.getWorld();
  }

  static RTPLocation spawn(RTPWorld<?> world) {
    return new RTPLocation(world, 0, 64, 0);
  }
}
