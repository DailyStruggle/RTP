package io.github.dailystruggle.rtp.guiaddon.fabric;

import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.guiaddon.common.GuiRenderers;
import io.github.dailystruggle.rtp.guiaddon.common.RTPGuiCommonAddon;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Fabric entry point for the RTP GUI addon.
 *
 * <p>Thin by design, mirroring the Bukkit plugin: the platform-neutral
 * {@code rtp-gui-common} module owns config registration and the bare {@code /rtp}
 * root-action binding; this initializer only does the Fabric-specific wiring:
 *
 * <ul>
 *   <li>captures the {@link MinecraftServer} when it starts (the renderer needs it
 *       to resolve players and open screens), and</li>
 *   <li>installs the {@link FabricMenuRenderer} and registers the common addon with
 *       RTP once the server is up (core is guaranteed loaded by then).</li>
 * </ul>
 *
 * <p>This is bundled in the single GUI addon jar alongside the Bukkit and NeoForge
 * renderers; Fabric only loads this initializer (declared in {@code fabric.mod.json}),
 * so the other platforms' classes are never touched here.
 */
public final class RTPGuiFabricInitializer implements ModInitializer {

  private static volatile MinecraftServer server;

  /** @return the live server, or {@code null} before start / after stop. */
  static MinecraftServer server() {
    return server;
  }

  @Override
  public void onInitialize() {
    ServerLifecycleEvents.SERVER_STARTED.register(
        startedServer -> {
          server = startedServer;
          // Core is up by SERVER_STARTED; register the renderer and the common addon
          // (config + bare-/rtp binding). Each platform registers its own renderer
          // programmatically (no MenuRenderer SPI): every platform's chest renderer
          // shares the "chest" style key, so a SPI loop would let one platform's
          // renderer clobber another's in the shared GuiRenderers map.
          // Gate self-registration on platform + version via the general RTP API
          // (no addon-local probing): the Fabric chest renderer registers only on a
          // Fabric runtime at or above the server-side container-menu floor.
          GuiRenderers.registerIfCompatible(
              new FabricMenuRenderer(), PlatformFamily.FABRIC, 20, Integer.MAX_VALUE);
          RTP.log(java.util.logging.Level.INFO,
              "[RTP-GUI] Fabric chest renderer registration attempted; registering common addon");
          RTP.addons.register(new RTPGuiCommonAddon());
        });

    ServerLifecycleEvents.SERVER_STOPPING.register(
        stoppingServer -> {
          GuiRenderers.unregister(FabricMenuRenderer.STYLE);
          server = null;
        });
  }
}
