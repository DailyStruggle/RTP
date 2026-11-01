package io.github.dailystruggle.rtp.paper_v1_20_R1.world;

import io.github.dailystruggle.rtp.bukkitplatform.world.BukkitRTPWorld;
import org.bukkit.World;

/**
 * Paper v1_20_R1 {@code RTPWorld}.
 *
 * <p>ADR-016 section 13.2 compliance: this class intentionally does <b>not</b> override
 * {@link BukkitRTPWorld#getChunkAt(int, int)}. The parent Spigot implementation
 * already reflectively invokes {@code World#getChunkAtAsync(int, int)} as its
 * live-load path - which is exactly Paper's native async chunk API - and it
 * additionally threads every candidate through the ADR-016 Anvil pre-filter
 * before the live load. Overriding here would silently bypass the pre-filter
 * and therefore the section 13.1 chunk-data precedence rule, producing upgrade-drift
 * regressions after a Minecraft version bump.
 */
public final class PaperRTPWorld extends BukkitRTPWorld {

  public PaperRTPWorld(World world) {
    super(world);
  }

  /**
   * Paper override: no-op. Paper's chunk-system-v2 persists generated chunks
   * via its own dirty-tracking and autosave path, so the forced
   * {@code World.save()} that {@link BukkitRTPWorld#save()} performs (to work
   * around the Spigot+Chunky "chunks never reach disk" symptom captured in
   * {@code docs/dev/LESSONS_LEARNED.md} → "Pre-Generation &amp; Shutdown") is
   * unnecessary on Paper and would only add I/O pressure during long
   * {@code rtp scan} runs. See also {@code helpers/PeriodicWorldSaver}.
   */
  @Override
  public void save() {
    // intentional no-op on Paper; see Javadoc.
  }
}
