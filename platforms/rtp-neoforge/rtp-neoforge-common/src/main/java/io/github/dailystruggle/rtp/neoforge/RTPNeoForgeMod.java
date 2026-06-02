package io.github.dailystruggle.rtp.neoforge;

import io.github.dailystruggle.rtp.neoforge.commands.NeoForgeCommandRegistrar;
import io.github.dailystruggle.rtp.neoforge.scheduling.NeoForgeScheduler;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge {@code @Mod} entry point (Phase N1 skeleton).
 *
 * <p>This is the NeoForge analogue of Fabric's {@code ModInitializer}. The
 * registration trampoline differs from Fabric (NEOFORGE_NOTES.md §2/§4): the
 * mod constructor receives the mod-bus {@link IEventBus} and we subscribe the
 * game-bus lifecycle/tick/command events on {@link NeoForge#EVENT_BUS}. The
 * {@code commands-api} Brigadier bridge ({@code commands-api-ADR-001}) is reused
 * unchanged; only the trampoline ({@link RegisterCommandsEvent}) is platform
 * specific.</p>
 *
 * <h2>Phase N1 status (read me)</h2>
 * <p>This skeleton wires the lifecycle and the {@link NeoForgeScheduler}. The
 * remaining seams are deliberately left as TODO for the platform maintainer
 * (project lead, {@code @leaf_26}) to fill in on a network-capable host where
 * the NeoForge/ModDevGradle artifacts resolve:</p>
 * <ul>
 *   <li>Constructing and binding the {@code NeoForgeServerAccessor}
 *       ({@code RTPServerAccessor}) into {@code RTPAPI.serverAccessor} and
 *       installing the scheduler via {@code RTP.scheduler} — the S-006 fail-loud
 *       contract must hold for any API entry before this completes.</li>
 *   <li>The event bridge (join / quit / world-load) and database handler.</li>
 *   <li>The S-005 async chunk-generation path and the
 *       {@code ReqRtpNeoforgeS005ChunkLoadingTest} / {@code ReqRtpNeoforgeS006EarlyApiTest}
 *       REQ-traceable guards (see {@code platforms/rtp-neoforge/REQUIREMENTS.md}).</li>
 * </ul>
 */
@Mod(RTPNeoForgeMod.MOD_ID)
public final class RTPNeoForgeMod {

  /** Mod id; must match {@code modId} in {@code META-INF/neoforge.mods.toml}. */
  public static final String MOD_ID = "rtp";

  private final NeoForgeScheduler scheduler = new NeoForgeScheduler();

  /**
   * NeoForge invokes this constructor during mod loading, injecting the
   * mod-bus event bus. We register game-bus listeners for the server
   * lifecycle, per-tick scheduler drain, and command registration.
   *
   * @param modBus the mod-specific event bus (used for setup-phase events)
   */
  public RTPNeoForgeMod(IEventBus modBus) {
    // Game-bus events (server lifecycle, tick, command registration) live on
    // the global NeoForge.EVENT_BUS rather than the mod bus.
    NeoForge.EVENT_BUS.register(this);
    // modBus is retained for future setup-phase wiring (registries, config).
    // Intentionally unused in the skeleton; see Phase N1 TODO in the class doc.
  }

  @SubscribeEvent
  public void onServerStarted(ServerStartedEvent event) {
    MinecraftServer server = event.getServer();
    scheduler.setServer(server);
    // TODO(Phase N1, @leaf_26): construct NeoForgeServerAccessor(server),
    // bind it into RTPAPI.serverAccessor, install `scheduler` via RTP.scheduler,
    // and start the core (region pre-fill / database). Until this lands the
    // mod is a no-op beyond lifecycle wiring.
  }

  @SubscribeEvent
  public void onServerStopping(ServerStoppingEvent event) {
    // TODO(Phase N1): flush database, release MemoryTracker tickets, stop core.
    scheduler.clearServer();
  }

  @SubscribeEvent
  public void onServerTick(ServerTickEvent.Post event) {
    scheduler.tick(event.getServer());
  }

  @SubscribeEvent
  public void onRegisterCommands(RegisterCommandsEvent event) {
    // Reuse the commands-api Brigadier bridge (commands-api-ADR-001). The
    // registrar adapts NeoForge's CommandDispatcher<CommandSourceStack> to the
    // shared command tree; only this trampoline is platform specific.
    NeoForgeCommandRegistrar.register(event.getDispatcher());
  }
}
