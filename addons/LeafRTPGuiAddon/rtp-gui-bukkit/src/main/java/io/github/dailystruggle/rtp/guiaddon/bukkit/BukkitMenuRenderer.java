package io.github.dailystruggle.rtp.guiaddon.bukkit;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.guiaddon.common.MenuModel;
import io.github.dailystruggle.rtp.guiaddon.common.MenuRenderer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/**
 * Bukkit implementation of the platform-neutral {@link MenuRenderer} seam.
 *
 * <p>Builds a {@link DestinationPickerGui} from the model and opens it on the thread
 * that owns the player. Inventory opens are not thread-safe, and on Folia a player may
 * only be touched from its entity scheduler (the main thread throws on a wrong-region
 * access). The open is therefore routed through {@link RTPRunnable#setTarget(RTPPlayer)}
 * + {@link RTPRunnable#schedule()}, which dispatches onto the player's entity scheduler
 * on Folia and collapses to the main server thread on Paper/Spigot. The model is built
 * in {@code common}; this class only renders.
 */
public final class BukkitMenuRenderer implements MenuRenderer {

  /** Style key under which this renderer registers; the {@code menuStyle} default. */
  public static final String STYLE = "chest";

  /**
   * Guards against registering the {@link DestinationPickerListener} more than once
   * (e.g. SPI path then a {@code /rtp reload}, or both load paths in one JVM). The
   * click/drag listener is process-global, so a single registration is enough no
   * matter how many renderer instances exist.
   */
  private static final java.util.concurrent.atomic.AtomicBoolean LISTENER_REGISTERED =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  private final Plugin plugin;

  /**
   * SPI / no-arg constructor. Resolves the host RTP plugin from the server's plugin
   * manager so the renderer works when it is discovered via
   * {@code META-INF/services} (the addons-folder / bundled load path, where the
   * Bukkit {@code JavaPlugin} entry point never runs) rather than registered by
   * {@code RTPGuiBukkitPlugin}. The {@code RTP} plugin is only needed for the
   * non-Folia main-thread fallback scheduler; the primary open path uses the
   * player's entity scheduler and tolerates a {@code null} plugin.
   *
   * <p>On this load path the {@code JavaPlugin} entry point that would normally
   * register the {@link DestinationPickerListener} never runs, so the listener is
   * registered here against the host RTP plugin. Without it the menu opens but
   * clicks are never translated into a teleport.
   */
  public BukkitMenuRenderer() {
    this(Bukkit.getPluginManager().getPlugin("RTP"));
    registerClickListener(this.plugin);
  }

  public BukkitMenuRenderer(Plugin plugin) {
    this.plugin = plugin;
  }

  /**
   * Registers the {@link DestinationPickerListener} once per JVM against the given
   * host plugin. Used by the SPI / no-arg load path, where no {@code JavaPlugin}
   * {@code onEnable} runs to register it.
   */
  static void registerClickListener(Plugin host) {
    if (host == null) {
      RTP.log(java.util.logging.Level.WARNING, "[RTP-GUI] no host plugin available to"
          + " register the menu click listener; menu clicks will not respond");
      return;
    }
    if (LISTENER_REGISTERED.compareAndSet(false, true)) {
      Bukkit.getPluginManager().registerEvents(new DestinationPickerListener(), host);
      RTP.log(java.util.logging.Level.FINE,
          "[RTP-GUI] registered destination picker click listener via SPI load path");
    }
  }

  @Override
  public String key() {
    return STYLE;
  }

  @Override
  public void open(UUID playerId, MenuModel model) {
    Runnable openTask =
        () -> {
          Player player = Bukkit.getPlayer(playerId);
          if (player == null) {
            RTP.log(java.util.logging.Level.FINE, "[RTP-GUI] open: player " + playerId
                + " not found at open time (logged off between build and open)");
            return; // logged off between build and open
          }
          try {
            player.openInventory(DestinationPickerGui.from(model).getInventory());
            RTP.log(java.util.logging.Level.FINE,
                "[RTP-GUI] open: opened destination picker for " + playerId);
          } catch (Throwable t) {
            RTP.log(java.util.logging.Level.WARNING,
                "[RTP-GUI] open: failed to open destination picker for " + playerId, t);
          }
        };

    // Prefer the player's entity scheduler (Folia-safe; main thread on Paper/Spigot).
    RTPPlayer rtpPlayer =
        (RTP.serverAccessor == null) ? null : RTP.serverAccessor.getPlayer(playerId);
    RTP.log(java.util.logging.Level.FINE, "[RTP-GUI] open: scheduling picker for " + playerId
        + " via " + (rtpPlayer != null ? "entity scheduler" : "main-thread fallback"));
    if (rtpPlayer != null) {
      new RTPRunnable(openTask).setTarget(rtpPlayer).schedule();
      return;
    }

    // Fallback when no rtp-api player wrapper is available (non-Folia only):
    // open directly on the main thread.
    if (Bukkit.isPrimaryThread()) {
      openTask.run();
    } else if (plugin != null) {
      Bukkit.getScheduler().runTask(plugin, openTask);
    } else {
      RTP.log(java.util.logging.Level.WARNING, "[RTP-GUI] open: no host plugin available to"
          + " schedule the main-thread menu open for " + playerId);
    }
  }
}
