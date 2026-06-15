package io.github.dailystruggle.rtp.guiaddon.neoforge;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.guiaddon.common.MenuModel;
import io.github.dailystruggle.rtp.guiaddon.common.MenuRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

import java.util.UUID;

/**
 * NeoForge implementation of the platform-neutral {@link MenuRenderer} seam.
 *
 * <p>Opens the destination picker as a real server-side container screen (a
 * {@link DestinationPickerMenu} chest GUI). Registered under the {@code "chest"}
 * style key, matching the Bukkit and Fabric renderers, so {@code menuStyle: chest}
 * works uniformly. Discovered via {@code META-INF/services} on NeoForge only; on a
 * non-NeoForge runtime this class fails to load and the common addon skips it.
 */
public final class NeoForgeMenuRenderer implements MenuRenderer {

  public static final String STYLE = "chest";

  @Override
  public String key() {
    return STYLE;
  }

  @Override
  public boolean isAvailable() {
    return RTPGuiNeoForgeMod.server() != null;
  }

  @Override
  public void open(UUID playerId, MenuModel model) {
    if (playerId == null || model == null) {
      return;
    }
    RTP.scheduler.runTask(() -> {
      MinecraftServer server = RTPGuiNeoForgeMod.server();
      if (server == null) {
        return;
      }
      ServerPlayer player = server.getPlayerList().getPlayer(playerId);
      if (player == null) {
        return;
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
