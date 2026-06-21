package io.github.dailystruggle.rtp.guiaddon.neoforge;

import io.github.dailystruggle.rtp.api.server.PlatformFamily;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.guiaddon.common.GuiRenderers;
import io.github.dailystruggle.rtp.guiaddon.common.RTPGuiCommonAddon;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * NeoForge {@code @Mod} entry point for the RTP GUI addon.
 *
 * <p>NeoForge analogue of Fabric's {@code RTPGuiFabricInitializer}: it captures the
 * {@link MinecraftServer} at server-start, installs the {@link NeoForgeMenuRenderer},
 * and registers the platform-neutral {@link RTPGuiCommonAddon} (config + bare
 * {@code /rtp} binding) with RTP once core is up. Game-bus lifecycle events are
 * subscribed on {@link NeoForge#EVENT_BUS}, mirroring {@code RTPNeoForgeMod}.
 *
 * <p>Bundled in the single multi-loader addon jar; only NeoForge instantiates this
 * class (declared in {@code META-INF/neoforge.mods.toml}).
 */
@Mod(RTPGuiNeoForgeMod.MOD_ID)
public final class RTPGuiNeoForgeMod {

  /** Mod id; must match {@code modId} in {@code META-INF/neoforge.mods.toml}. */
  public static final String MOD_ID = "rtp_gui";

  private static volatile MinecraftServer server;

  /** @return the live server, or {@code null} before start / after stop. */
  static MinecraftServer server() {
    return server;
  }

  public RTPGuiNeoForgeMod(IEventBus modBus) {
    NeoForge.EVENT_BUS.register(this);
  }

  @SubscribeEvent
  public void onServerStarted(ServerStartedEvent event) {
    server = event.getServer();
    // Gate self-registration on platform + version via the general RTP API
    // (no addon-local probing): the NeoForge chest renderer registers only on a
    // NeoForge runtime at or above the server-side container-menu floor.
    GuiRenderers.registerIfCompatible(
        new NeoForgeMenuRenderer(), PlatformFamily.NEOFORGE, 20, Integer.MAX_VALUE);
    RTP.addons.register(new RTPGuiCommonAddon());
  }

  @SubscribeEvent
  public void onServerStopping(ServerStoppingEvent event) {
    GuiRenderers.unregister(NeoForgeMenuRenderer.STYLE);
    server = null;
  }
}
