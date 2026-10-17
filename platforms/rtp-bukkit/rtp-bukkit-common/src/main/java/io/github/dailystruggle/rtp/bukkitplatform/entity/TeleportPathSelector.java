package io.github.dailystruggle.rtp.bukkitplatform.entity;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * Chooses between an inline {@code player.teleport} and Paper's {@code teleportAsync} for one
 * teleport (ADR-005 Amendment 2, {@code performance.yml teleportPath}).
 *
 * <p>{@code teleportAsync} always completes through Paper's main-thread process queue, drained once
 * per tick, so a call made after that drain waits for the next tick even when the chunk is resident.
 * The inline path is taken only when all hold: mode {@code AUTO}, caller on the primary thread,
 * not Folia (cross-region teleports must use {@code teleportAsync}), and the destination chunk is
 * fully loaded. At block centre the player's box (+/-0.3) stays inside that one chunk, so no load
 * can be triggered (S-005); the arrival hold keeps it loaded through the first tick. Any probe
 * failure selects the async path.
 */
public final class TeleportPathSelector {

  /** {@code AUTO}: inline when safe, else async. {@code ASYNC}: always {@code teleportAsync}. */
  public enum Mode {
    AUTO,
    ASYNC
  }

  private static final boolean FOLIA = detectFolia();
  private static volatile BooleanSupplier primaryThread = TeleportPathSelector::bukkitPrimaryThread;
  private static volatile Boolean foliaOverride;

  private TeleportPathSelector() {}

  /** True when the teleport to block {@code (x, z)} in {@code world} may run inline now. */
  public static boolean canTeleportDirect(World world, int blockX, int blockZ) {
    if (world == null || mode() != Mode.AUTO) return false;
    Boolean folia = foliaOverride;
    if (folia != null ? folia : FOLIA) return false;
    try {
      return primaryThread.getAsBoolean() && world.isChunkLoaded(blockX >> 4, blockZ >> 4);
    } catch (Throwable t) {
      return false;
    }
  }

  /** Configured mode; {@code AUTO} when unset or unreadable. */
  public static Mode mode() {
    try {
      @SuppressWarnings("unchecked")
      ConfigParser<PerformanceKeys> perf =
          RTP.configs == null
              ? null
              : (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
      if (perf == null) return Mode.AUTO;
      Object o = perf.getConfigValue(PerformanceKeys.teleportPath, "AUTO");
      return o != null && "ASYNC".equalsIgnoreCase(o.toString().trim()) ? Mode.ASYNC : Mode.AUTO;
    } catch (Throwable t) {
      return Mode.AUTO;
    }
  }

  private static boolean bukkitPrimaryThread() {
    return Bukkit.isPrimaryThread();
  }

  private static boolean detectFolia() {
    try {
      Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
      return true;
    } catch (Throwable t) {
      return false;
    }
  }

  public static void setForTesting(BooleanSupplier primary, Boolean folia) {
    primaryThread = primary;
    foliaOverride = folia;
  }

  public static void resetForTesting() {
    primaryThread = TeleportPathSelector::bukkitPrimaryThread;
    foliaOverride = null;
  }
}
