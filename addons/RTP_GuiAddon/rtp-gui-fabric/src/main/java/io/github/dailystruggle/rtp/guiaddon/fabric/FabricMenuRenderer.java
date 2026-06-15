package io.github.dailystruggle.rtp.guiaddon.fabric;

import io.github.dailystruggle.rtp.common.RTP;
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
 * the {@code menuStyle} config value selects it uniformly across platforms. The
 * renderer is discovered through {@code META-INF/services} on Fabric only; on a
 * non-Fabric runtime this class fails to load (its Minecraft superclasses are
 * absent) and the common addon's service loop skips it.
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
    RTP.scheduler.runTask(() -> {
      MinecraftServer server = RTPGuiFabricInitializer.server();
      if (server == null) {
        return;
      }
      ServerPlayer player = server.getPlayerList().getPlayer(playerId);
      if (player == null) {
        return; // logged off between build and open
      }
      player.openMenu(
          new SimpleMenuProvider(
              (id, inv, p) -> new DestinationPickerMenu(id, inv, model),
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
