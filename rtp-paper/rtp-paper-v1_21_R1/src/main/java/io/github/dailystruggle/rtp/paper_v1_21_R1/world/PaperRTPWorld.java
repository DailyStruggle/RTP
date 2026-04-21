package io.github.dailystruggle.rtp.paper_v1_21_R1.world;

import io.github.dailystruggle.rtp.spigot.world.BukkitRTPWorld;
import org.bukkit.World;

/**
 * Paper v1_21_R1 {@code RTPWorld}.
 *
 * <p>ADR-016 §13.2 compliance: this class intentionally does <b>not</b> override
 * {@link BukkitRTPWorld#getChunkAt(int, int)}. The parent Spigot implementation
 * already reflectively invokes {@code World#getChunkAtAsync(int, int)} as its
 * live-load path — which is exactly Paper's native async chunk API — and it
 * additionally threads every candidate through the ADR-016 Anvil pre-filter
 * before the live load. Overriding here would silently bypass the pre-filter
 * and therefore the §13.1 chunk-data precedence rule, producing upgrade-drift
 * regressions after a Minecraft version bump.
 */
public final class PaperRTPWorld extends BukkitRTPWorld {

  public PaperRTPWorld(World world) {
    super(world);
  }
}
