package io.github.dailystruggle.rtp.guiaddon.neoforge;

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
 * NeoForge implementation of the platform-neutral {@link MenuRenderer} seam.
 *
 * <p>Opens the destination picker as a real server-side container screen (a
 * {@link DestinationPickerMenu} chest GUI). Registered under the {@code "chest"}
 * style key, matching the Bukkit and Fabric renderers, so {@code menuStyle: chest}
 * works uniformly. It is published via a {@code MenuRenderer} SPI service file so
 * {@code RTPGuiCommonAddon} can register it on the {@link java.util.ServiceLoader}
 * load path (the {@code plugins/RTP/addons/} folder or the bundled-in-jar demo),
 * where the NeoForge mod entry point never runs. The shared {@code "chest"} key is
 * safe across platforms because {@link #isAvailable()} is gated on the NeoForge
 * loader being present, so on a Bukkit or Fabric runtime this renderer reports
 * unavailable and is skipped rather than clobbering the live one. When the addon IS
 * installed as a standalone NeoForge mod, {@code RTPGuiNeoForgeMod} also registers
 * the same renderer programmatically - harmless, since {@code GuiRenderers} keys by
 * style.
 */
public final class NeoForgeMenuRenderer implements MenuRenderer {

  public static final String STYLE = "chest";

  @Override
  public String key() {
    return STYLE;
  }

  @Override
  public boolean isAvailable() {
    // Gate on the NeoForge loader being present rather than on a bound server: the
    // SPI registration may run before the server starts, and a player can only run
    // /rtp once the server is up, so open() resolves the live server defensively.
    // The marker also disambiguates this renderer from the Fabric one (both only
    // reference net.minecraft types, so both load on either modded runtime).
    return isNeoForgeRuntime();
  }

  /** @return true when the NeoForge loader is on the classpath (i.e. a NeoForge runtime). */
  private static boolean isNeoForgeRuntime() {
    try {
      Class.forName("net.neoforged.fml.loading.FMLLoader");
      return true;
    } catch (Throwable notNeoForge) {
      return false;
    }
  }

  /**
   * Resolves the live {@link MinecraftServer}: the NeoForge mod entry point's captured
   * reference when running standalone, otherwise RTP's own bound server (via the
   * platform accessor's {@code getServer()}), which is the only source on the
   * ServiceLoader / bundled-in-jar load path where the mod entry point never runs.
   */
  private static MinecraftServer currentServer() {
    MinecraftServer s = RTPGuiNeoForgeMod.server();
    if (s != null) {
      return s;
    }
    Object accessor = RTP.serverAccessor;
    if (accessor == null) {
      RTP.log(java.util.logging.Level.WARNING,
          "[RTP-GUI] NeoForge renderer: RTP.serverAccessor is null; cannot resolve server");
      return null;
    }
    Object result;
    try {
      result = accessor.getClass().getMethod("getServer").invoke(accessor);
    } catch (Throwable noServer) {
      RTP.log(java.util.logging.Level.WARNING,
          "[RTP-GUI] NeoForge renderer: reflective getServer() on "
              + accessor.getClass().getName() + " threw", noServer);
      return null;
    }
    if (result == null) {
      RTP.log(java.util.logging.Level.WARNING,
          "[RTP-GUI] NeoForge renderer: " + accessor.getClass().getName()
              + ".getServer() returned null (server not yet bound?)");
      return null;
    }
    if (result instanceof MinecraftServer) {
      return (MinecraftServer) result;
    }
    RTP.log(java.util.logging.Level.WARNING,
        "[RTP-GUI] NeoForge renderer: getServer() returned a "
            + result.getClass().getName()
            + " not assignable to this renderer's MinecraftServer ("
            + MinecraftServer.class.getClassLoader() + " vs "
            + result.getClass().getClassLoader() + ")");
    return null;
  }

  /**
   * Resolves the live {@link ServerPlayer} for {@code playerId}. Primary path: RTP's own
   * player registry ({@code RTP.serverAccessor.getPlayer(uuid).handle()}), which holds the
   * {@code ServerPlayer} directly and does not depend on the accessor's typed
   * {@code getServer()} resolving cleanly across the addon's classloader. Fallback: the
   * resolved {@link MinecraftServer}'s player list. The {@code handle()} method is invoked
   * reflectively because the addon does not depend on {@code rtp-neoforge-common}.
   */
  private static ServerPlayer resolvePlayer(UUID playerId) {
    try {
      Object accessor = RTP.serverAccessor;
      if (accessor != null) {
        Object rtpPlayer = accessor.getClass().getMethod("getPlayer", UUID.class)
            .invoke(accessor, playerId);
        if (rtpPlayer != null) {
          Object handle = rtpPlayer.getClass().getMethod("handle").invoke(rtpPlayer);
          if (handle instanceof ServerPlayer) {
            return (ServerPlayer) handle;
          }
        }
      }
    } catch (Throwable viaRegistry) {
      RTP.log(java.util.logging.Level.FINE,
          "[RTP-GUI] NeoForge renderer: player registry resolution failed for " + playerId
              + "; falling back to server player list", viaRegistry);
    }
    MinecraftServer server = currentServer();
    if (server == null) {
      return null;
    }
    return server.getPlayerList().getPlayer(playerId);
  }

  @Override
  public void open(UUID playerId, MenuModel model) {
    if (playerId == null || model == null) {
      return;
    }
    RTP.scheduler.runTask(() -> {
      ServerPlayer player = resolvePlayer(playerId);
      if (player == null) {
        RTP.log(java.util.logging.Level.INFO,
            "[RTP-GUI] NeoForge renderer: could not resolve player at open time for " + playerId
                + " (offline, or neither the player registry nor a bound server was available)");
        return;
      }
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
