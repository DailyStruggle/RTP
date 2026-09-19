package io.github.dailystruggle.rtp.guiaddon.fabric;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.guiaddon.common.MenuLayout;
import io.github.dailystruggle.rtp.guiaddon.common.MenuModel;
import io.github.dailystruggle.rtp.guiaddon.common.MenuRenderer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
 * the {@code menuStyle} config value selects it uniformly across platforms. It is
 * published via a {@code MenuRenderer} SPI service file so {@code RTPGuiCommonAddon}
 * can register it on the {@link java.util.ServiceLoader} load path (the
 * {@code plugins/RTP/addons/} folder or the bundled-in-jar demo), where the Fabric
 * mod entry point never runs. The shared {@code "chest"} key is safe across platforms
 * because {@link #isAvailable()} is gated on the Fabric loader being present, so on a
 * Bukkit or NeoForge runtime this renderer reports unavailable and is skipped rather
 * than clobbering the live one. When the addon IS installed as a standalone Fabric
 * mod, {@code RTPGuiFabricInitializer} also registers the same renderer
 * programmatically - harmless, since {@code GuiRenderers} keys by style.
 */
public final class FabricMenuRenderer implements MenuRenderer {

  /** Style key; matches the Bukkit chest renderer so {@code menuStyle: chest} works everywhere. */
  public static final String STYLE = "chest";

  /**
   * Live server captured via Fabric lifecycle/tick events. This is the server
   * resolution path that does NOT touch the obf carrier classes: reflecting over
   * {@code FabricServerAccessor} / {@code FabricRTPPlayer} forces the JVM to resolve
   * every declared method signature, some of which are typed with the intermediary
   * {@code net.minecraft.class_3222} (ServerPlayer). That name does not exist on the
   * deobfuscated MC 26.x runtime this renderer is compiled against, so the reflective
   * lookup throws {@link NoClassDefFoundError}. Capturing the Mojmap-typed server here
   * and resolving players through {@link MinecraftServer#getPlayerList()} keeps the
   * whole path on the deobf surface.
   */
  private static volatile MinecraftServer capturedServer;

  /** Guards one-time registration of the lifecycle/tick capture listeners. */
  private static volatile boolean lifecycleHooked;

  public FabricMenuRenderer() {
    ensureServerCapture();
  }

  /**
   * Idempotently registers Fabric lifecycle + tick listeners that capture the live
   * {@link MinecraftServer}. The renderer may be constructed either from
   * {@code RTPGuiFabricInitializer} (at SERVER_STARTED) or via the {@code MenuRenderer}
   * SPI discovery path (where the mod entry point never runs), so we cannot rely on
   * SERVER_STARTED alone: the tick listener back-fills the reference on the next tick
   * when the server is already running by the time this listener is registered.
   */
  private static synchronized void ensureServerCapture() {
    if (lifecycleHooked) {
      return;
    }
    // Touching ServerLifecycleEvents/ServerTickEvents links Fabric API classes that
    // do not exist off a Fabric runtime. The SPI/bundled-in-jar load path constructs
    // this renderer on every platform (Bukkit, NeoForge, ...) before isAvailable()
    // is consulted, so guard the wiring on the loader being present. Without this
    // gate a fresh Paper start logs an alarming NoClassDefFoundError WARN even though
    // the renderer is correctly skipped as unavailable afterwards.
    if (!isFabricRuntime()) {
      return;
    }
    lifecycleHooked = true;
    try {
      ServerLifecycleEvents.SERVER_STARTED.register(s -> capturedServer = s);
      ServerLifecycleEvents.SERVER_STOPPING.register(s -> capturedServer = null);
      ServerTickEvents.START_SERVER_TICK.register(s -> {
        if (capturedServer == null) {
          capturedServer = s;
        }
      });
    } catch (Throwable cannotHook) {
      RTP.log(java.util.logging.Level.WARNING,
          "[RTP-GUI] Fabric renderer: could not register server-capture listeners", cannotHook);
    }
  }

  @Override
  public String key() {
    return STYLE;
  }

  @Override
  public boolean isAvailable() {
    // Gate on the Fabric loader being present rather than on a bound server: the
    // SPI registration may run before SERVER_STARTED, and a player can only run
    // /rtp once the server is up, so open() resolves the live server defensively.
    // The marker also disambiguates this renderer from the NeoForge one (both only
    // reference net.minecraft types, so both load on either modded runtime).
    return isFabricRuntime();
  }

  /** @return true when the Fabric loader is on the classpath (i.e. a Fabric runtime). */
  private static boolean isFabricRuntime() {
    try {
      Class.forName("net.fabricmc.loader.api.FabricLoader");
      return true;
    } catch (Throwable notFabric) {
      return false;
    }
  }

  /**
   * Resolves the live {@link MinecraftServer} without touching the obf carrier classes.
   * Prefers the Fabric mod entry point's captured reference (set at SERVER_STARTED when
   * the addon runs as a standalone mod), then the renderer's own lifecycle/tick capture
   * (the only source on the ServiceLoader / bundled-in-jar load path, where the mod
   * entry point never runs).
   *
   * <p>We deliberately do NOT reflect over {@code RTP.serverAccessor.getServer()} here:
   * {@code Class#getMethod} eagerly resolves every declared method signature of
   * {@code FabricServerAccessor}, and some are typed with the intermediary
   * {@code net.minecraft.class_3222} (ServerPlayer) which does not exist on the
   * deobfuscated MC 26.x runtime, throwing {@link NoClassDefFoundError}.
   */
  private static MinecraftServer currentServer() {
    MinecraftServer s = RTPGuiFabricInitializer.server();
    if (s != null) {
      return s;
    }
    return capturedServer;
  }

  /**
   * Resolves the live {@link ServerPlayer} for {@code playerId} via the captured
   * Mojmap {@link MinecraftServer}'s player list. This stays entirely on the
   * deobfuscated surface the renderer is compiled against, avoiding any reflection
   * over the obf carrier types ({@code FabricServerAccessor} / {@code FabricRTPPlayer}),
   * whose {@code class_3222}-typed method signatures cannot be resolved on MC 26.x.
   */
  private static ServerPlayer resolvePlayer(UUID playerId) {
    ensureServerCapture();
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
    // Opening a menu touches the player entity and must run on the server thread.
    RTP.log(java.util.logging.Level.INFO,
        "[RTP-GUI] Fabric renderer scheduling chest open for " + playerId);
    RTP.scheduler.runTask(() -> {
      ServerPlayer player = resolvePlayer(playerId);
      if (player == null) {
        RTP.log(java.util.logging.Level.INFO,
            "[RTP-GUI] Fabric renderer: could not resolve player at open time for " + playerId
                + " (offline, or neither the player registry nor a bound server was available);"
                + " falling back to a classic teleport so the command never silently no-ops");
        fallbackTeleport(playerId);
        return;
      }
      RTP.log(java.util.logging.Level.INFO,
          "[RTP-GUI] Fabric renderer opening chest menu for " + playerId);
      try {
        MenuLayout layout = MenuLayout.compute(model);
        player.openMenu(
            new SimpleMenuProvider(
                (id, inv, p) -> new DestinationPickerMenu(id, inv, model, layout),
                Component.literal(stripTitle(model.title()))));
      } catch (Throwable cannotOpen) {
        // The menu could not be displayed (e.g. a screen/menu-type linkage
        // failure on this runtime). Honour the MenuRenderer contract and fall
        // back to the classic teleport rather than leaving the player with
        // neither a menu nor a teleport.
        RTP.log(java.util.logging.Level.WARNING,
            "[RTP-GUI] Fabric renderer: opening the chest menu for " + playerId
                + " threw; falling back to a classic teleport", cannotOpen);
        fallbackTeleport(playerId);
      }
    });
  }

  /**
   * Performs RTP's classic teleport for {@code playerId} when the menu cannot be
   * shown. The bare {@code /rtp} root action commits to the menu (suppressing the
   * command's own classic-teleport fallback) before {@link #open} runs on the
   * server thread, so if the menu fails to open here this is the only remaining
   * place to honour the "never silently no-op" contract (REQ-RTP-S-004).
   */
  private static void fallbackTeleport(UUID playerId) {
    try {
      RTPAPI.teleport(playerId, RtpTarget.defaultRegion());
    } catch (Throwable noTeleport) {
      RTP.log(java.util.logging.Level.WARNING,
          "[RTP-GUI] Fabric renderer: classic-teleport fallback for " + playerId
              + " threw", noTeleport);
    }
  }

  private static String stripTitle(String title) {
    if (title == null || title.isEmpty()) {
      return "Random Teleport";
    }
    return title.replaceAll("(?i)[&\u00a7][0-9a-fk-or]", "");
  }
}
