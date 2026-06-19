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

  private final Plugin plugin;

  public BukkitMenuRenderer(Plugin plugin) {
    this.plugin = plugin;
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
    } else {
      Bukkit.getScheduler().runTask(plugin, openTask);
    }
  }
}
