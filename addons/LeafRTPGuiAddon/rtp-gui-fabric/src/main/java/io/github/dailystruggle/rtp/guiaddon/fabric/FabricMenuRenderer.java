package io.github.dailystruggle.rtp.guiaddon.fabric;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.guiaddon.common.MenuLayout;
import io.github.dailystruggle.rtp.guiaddon.common.MenuModel;
import io.github.dailystruggle.rtp.guiaddon.common.MenuRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

import java.util.UUID;

/**
 * Fabric implementation of the platform-neutral {@link MenuRenderer} seam.
 *
 * <p>Opens the destination picker as a real server-side container screen (a
 * {@link DestinationPickerMenu} chest GUI), the Fabric counterpart of the Bukkit
 * chest renderer. The model is built in {@code common}; this class only opens the
 * screen on the server thread for the resolved {@link ServerPlayer}.
 *
 * <p>Registered under the same {@code "chest"} style key as the Bukkit renderer, so
 * the {@code menuStyle} config value selects it uniformly across platforms. Because
 * every platform's renderer shares that key, this one is registered programmatically
 * by {@code RTPGuiFabricInitializer} (Fabric-only entry point) rather than via a
 * {@code MenuRenderer} SPI service file: a cross-platform SPI loop would let this
 * renderer and the Bukkit/NeoForge ones overwrite each other in the shared
 * {@code GuiRenderers} map.
 */
public final class FabricMenuRenderer implements MenuRenderer {

  /** Style key; matches the Bukkit chest renderer so {@code menuStyle: chest} works everywhere. */
  public static final String STYLE = "chest";

  @Override
  public String key() {
    return STYLE;
  }

  @Override
  public boolean isAvailable() {
    return RTPGuiFabricInitializer.server() != null;
  }

  @Override
  public void open(UUID playerId, MenuModel model) {
    if (playerId == null || model == null) {
      return;
    }
    // Opening a menu touches the player entity and must run on the server thread.
    RTP.log(java.util.logging.Level.INFO,
        "[RTP-GUI] Fabric renderer scheduling chest open for " + playerId);
    RTP.scheduler.runTask(() -> {
      MinecraftServer server = RTPGuiFabricInitializer.server();
      if (server == null) {
        RTP.log(java.util.logging.Level.INFO,
            "[RTP-GUI] Fabric renderer: server null at open time for " + playerId);
        return;
      }
      ServerPlayer player = server.getPlayerList().getPlayer(playerId);
      if (player == null) {
        RTP.log(java.util.logging.Level.INFO,
            "[RTP-GUI] Fabric renderer: player not online at open time for " + playerId);
        return; // logged off between build and open
      }
      RTP.log(java.util.logging.Level.INFO,
          "[RTP-GUI] Fabric renderer opening chest menu for " + playerId);
      MenuLayout layout = MenuLayout.compute(model);
      player.openMenu(
          new SimpleMenuProvider(
              (id, inv, p) -> new DestinationPickerMenu(id, inv, model, layout),
              Component.literal(stripTitle(model.title()))));
    });
  }

  private static String stripTitle(String title) {
    if (title == null || title.isEmpty()) {
      return "Random Teleport";
    }
    return title.replaceAll("(?i)[&\u00a7][0-9a-fk-or]", "");
  }
}
