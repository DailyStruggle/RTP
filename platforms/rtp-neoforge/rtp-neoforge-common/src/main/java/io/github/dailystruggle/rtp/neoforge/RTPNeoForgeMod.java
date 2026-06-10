package io.github.dailystruggle.rtp.neoforge;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.server.DatabaseProcessing;
import io.github.dailystruggle.rtp.common.tasks.ChunkUnloadProcessor;
import io.github.dailystruggle.rtp.neoforge.commands.NeoForgeCommandRegistrar;
import io.github.dailystruggle.rtp.neoforge.database.NeoForgeDatabaseHandler;
import io.github.dailystruggle.rtp.neoforge.events.NeoForgeEventBridge;
import io.github.dailystruggle.rtp.neoforge.server.NeoForgePlayerLifecycleHook;
import io.github.dailystruggle.rtp.neoforge.server.NeoForgeServerAccessor;
import io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapter;
import io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapterRegistry;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.logging.Level;

/**
 * NeoForge {@code @Mod} entry point (Phase N2.2 core binding).
 *
 * <p>This is the NeoForge analogue of Fabric's {@code ModInitializer}. The
 * registration trampoline differs from Fabric (NEOFORGE_NOTES.md §2/§4): the
 * mod constructor receives the mod-bus {@link IEventBus} and we subscribe the
 * game-bus lifecycle/tick/command events on {@link NeoForge#EVENT_BUS}. The
 * {@code commands-api} Brigadier bridge ({@code commands-api-ADR-001}) is reused
 * unchanged; only the trampoline ({@link RegisterCommandsEvent}) is platform
 * specific.</p>
 *
 * <h2>Status (read me)</h2>
 * <p>At server-start this entry point selects the per-MC version adapter,
 * constructs the {@code NeoForgeServerAccessor}, binds it into
 * {@code RTP.serverAccessor} + {@code RTP.scheduler}, constructs the rtp-core
 * {@code RTP} singleton, schedules the {@code ChunkUnloadProcessor}, and drains
 * the startup tasks (Phase N2.2). The per-tick {@code onServerTick} drains the
 * accessor's scheduler and refreshes any version-adapter chunk tickets.</p>
 * <p>The remaining seams are left as TODO for the platform maintainer
 * (project lead, {@code @leaf_26}) to fill in on a network-capable host where
 * the NeoForge/ModDevGradle artifacts resolve:</p>
 * <ul>
 *   <li>The native PvP combat tag (ADR-055) and the live network-mode boot
 *       (reservation-token redemption, ADR-049 Step J). The permission chain
 *       (N2.5), the menu / book / map / metrics / backend-sampler sinks (N2.6)
 *       are wired.</li>
 *   <li>The {@code ReqRtpNeoforgeS005ChunkLoadingTest} /
 *       {@code ReqRtpNeoforgeS006EarlyApiTest} REQ-traceable guards
 *       (see {@code platforms/rtp-neoforge/REQUIREMENTS.md}).</li>
 * </ul>
 *
 * <p>The event bridge (join / quit / world-load), player lifecycle hook, and
 * database handler (N2.3) and the {@code /rtp} Brigadier command tree (N2.4,
 * registered on {@link RegisterCommandsEvent}) are wired.</p>
 */
@Mod(RTPNeoForgeMod.MOD_ID)
public final class RTPNeoForgeMod {

  /** Mod id; must match {@code modId} in {@code META-INF/neoforge.mods.toml}. */
  public static final String MOD_ID = "rtp";

  /**
   * The platform accessor for this runtime. Constructed at server-start
   * ({@link #onServerStarted}) and bound into {@code RTP.serverAccessor} /
   * {@code RTP.scheduler}; {@code null} until then so any API entry before
   * core load fails loud per S-006 rather than silently no-opping.
   */
  private NeoForgeServerAccessor accessor;

  /**
   * Guards {@link #prepareCore()} so the server-independent core (accessor +
   * scheduler + {@code RTP} singleton) is constructed exactly once, regardless
   * of whether {@link #onRegisterCommands} or {@link #onServerStarted} reaches
   * it first. NeoForge fires {@link RegisterCommandsEvent} on a worker thread
   * before the {@link MinecraftServer} object even exists, so the flag is
   * {@code volatile} and the method is {@code synchronized}.
   */
  private volatile boolean corePrepared = false;

  /**
   * Game-bus bridge for player join/quit and world load/unload events.
   * Constructed and registered in {@link #bootCore(MinecraftServer)} once the
   * accessor exists; {@code null} until then.
   */
  private NeoForgeEventBridge eventBridge;

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
    // Select the per-MC version adapter FIRST, before anything in
    // rtp-neoforge-common touches a version-volatile call site (S-006).
    installVersionAdapter(server);
    try {
      bootCore(server);
    } catch (Throwable t) {
      // Loud failure: a swallowed bootstrap exception would leave the mod
      // silently non-functional, violating REQ-RTP-S-004.
      RTP.log(Level.SEVERE,
          "[RTP][NeoForge] Core bootstrap failed; RTP will be non-functional this run: "
              + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
    }
  }

  /**
   * Construct the <b>server-independent</b> core exactly once: the
   * {@link NeoForgeServerAccessor}, the {@code RTP.serverAccessor} /
   * {@code RTP.scheduler} bindings, and the rtp-core {@link RTP} singleton
   * (which builds {@code Configs} from {@code accessor.getPluginDirectory()},
   * backed by {@code FMLPaths.CONFIGDIR} — no {@link MinecraftServer} needed).
   *
   * <p>This is split out of {@link #bootCore(MinecraftServer)} because NeoForge
   * fires {@link RegisterCommandsEvent} (where the {@code /rtp} tree is built)
   * <b>before</b> the server object exists and before {@link ServerStartedEvent}
   * — in {@code Main.main}'s initial datapack/{@code ReloadableServerResources}
   * load, on a worker thread. Building the command tree dereferences
   * {@code RTP.getInstance()} and {@code RTP.configs} (e.g. {@code ReloadCmd}),
   * so the core must already exist at that point. {@link #onRegisterCommands}
   * calls this first; {@link #bootCore} calls it again (no-op) before binding
   * the live server.</p>
   *
   * <p>Ordering note: {@code RTP.scheduler} must be assigned <b>before</b>
   * {@code new RTP()} because the rtp-core constructor self-schedules its
   * pipeline / DB-flush timers via {@code RTP.scheduler}; the
   * {@link NeoForgeScheduler} merely queues those timers into its tick map
   * (drained once the server starts), so it is safe to use before bind.</p>
   */
  private synchronized void prepareCore() {
    if (corePrepared) {
      return;
    }
    NeoForgeServerAccessor acc = this.accessor;
    if (acc == null) {
      acc = new NeoForgeServerAccessor();
      this.accessor = acc;
    }
    RTP.serverAccessor = acc;
    RTP.scheduler = acc.getScheduler();
    if (RTP.getInstance() == null) {
      new RTP();
    }
    corePrepared = true;
  }

  private void bootCore(MinecraftServer server) {
    // Ensure the server-independent core exists. On NeoForge this has usually
    // already run in onRegisterCommands (which fires before the server object
    // exists), so this is normally a no-op here; it stays for paths where
    // command registration did not run first.
    prepareCore();
    NeoForgeServerAccessor acc = this.accessor;
    // Bind the running server into the accessor + its scheduler, and rebuild
    // the block-tag snapshot now BuiltInRegistries is fully populated.
    acc.bindServer(server);

    RTP rtp = RTP.getInstance();

    // N2.6 / ADR-049 step 4 - install the platform sampler factory so
    // NetworkModeBootstrap.boot(...) can construct a backend heartbeat sampler
    // that reads live NeoForge host state. Mirrors RTPFabricMod /
    // AbstractServerAccessor's RTP.backendStateSamplerFactory assignment.
    RTP.backendStateSamplerFactory =
        io.github.dailystruggle.rtp.neoforge.network.NeoForgeBackendStateSampler::new;

    // N2.6 - install the NeoForge metrics binding (single-region EMA sampler),
    // driven once per server tick from onServerTick via an instanceof probe.
    // Suppliers read playerCount / softCap off the bound MinecraftServer so the
    // binding has no net.minecraft imports. Mirrors RTPFabricMod's install.
    try {
      io.github.dailystruggle.rtp.neoforge.metrics.NeoForgeMetricsBinding metrics =
          new io.github.dailystruggle.rtp.neoforge.metrics.NeoForgeMetricsBinding(
              () -> { MinecraftServer s = acc.getServer(); return s == null ? 0 : s.getPlayerCount(); },
              () -> { MinecraftServer s = acc.getServer(); return s == null ? 0 : s.getPlayerList().getMaxPlayers(); });
      RTP.metrics.setBinding(metrics);
      RTP.log(Level.INFO, "[RTP][NeoForge] metrics binding installed (NeoForgeMetricsBinding).");
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP][NeoForge] NeoForgeMetricsBinding install failed: "
          + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
    }

    // Non-Folia ChunkUnloadProcessor timer (S-002): NeoForge has no Folia-style
    // region threading, so the non-Folia branch always applies. Without this,
    // chunks loaded by the teleport pipeline with keep(true) are never released
    // and MemoryTracker tickets accumulate. Mirrors RTPBukkitPlugin / Fabric.
    RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1);

    // Drain RTP.startupTasks the same way Bukkit / Fabric do. The RTP()
    // constructor enqueues region parsing, world rebind, and scan-task seeding
    // onto startupTasks rather than running them inline; without these drains
    // the region prefill never starts and /rtp falls through to "no location
    // available". Empty drains are no-ops.
    if (rtp != null) {
      while (rtp.startupTasks.size() > 0) {
        rtp.startupTasks.execute(Long.MAX_VALUE);
      }
      RTP.scheduler.runTaskLater(() -> {
        RTP r2 = RTP.getInstance();
        if (r2 != null) {
          while (r2.startupTasks.size() > 0) {
            r2.startupTasks.execute(Long.MAX_VALUE);
          }
        }
      }, 1);
      while (rtp.startupTasks.size() > 0) {
        rtp.startupTasks.execute(Long.MAX_VALUE);
      }
    }

    // N2.3 — register the game-bus event bridge (player join/quit, world
    // load/unload). Created here (not in the constructor) because it needs the
    // accessor, which only exists once the server has started.
    NeoForgeEventBridge bridge = new NeoForgeEventBridge(acc);
    this.eventBridge = bridge;
    bridge.register();

    // Register every already-loaded ServerLevel — overworld + nether + end (and
    // any datapack dimensions) come up before ServerStartedEvent fires, so the
    // LevelEvent.Load callbacks have already passed. Mirrors the Fabric
    // SERVER_STARTED world sweep.
    try {
      for (ServerLevel level : server.getAllLevels()) {
        if (level == null) continue;
        try {
          acc.registerWorld(level);
        } catch (Throwable t) {
          RTP.log(Level.WARNING, "[RTP][NeoForge] registerWorld failed: "
              + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP][NeoForge] world sweep at server-start failed: "
          + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    // N2.6 - install the NeoForge map binding so /rtp visualization charts
    // render to a vanilla filled-map instead of bottoming out on
    // NoopMapBinding. Only install when the active version adapter actually
    // implements the renderMapChart seam (supportsMapCharts); otherwise leave
    // MapDispatch on the NoopMapBinding sentinel so the configurable
    // mapBindingMissing message still surfaces. Mirrors RTPFabricMod.
    try {
      NeoForgeVersionAdapter mapAdapter = NeoForgeVersionAdapterRegistry.peek();
      if (mapAdapter != null && mapAdapter.supportsMapCharts()) {
        io.github.dailystruggle.rtp.common.commands.maps.MapDispatch.setMapBinding(
            new io.github.dailystruggle.rtp.neoforge.maps.NeoForgeMapBinding());
        // REQ-RTP-MAP-003 - release per-viewer map state on disconnect.
        NeoForgePlayerLifecycleHook lh = acc.getNeoForgePlayerLifecycleHook();
        if (lh != null) {
          lh.onPlayerQuit(uuid ->
              io.github.dailystruggle.rtp.common.commands.maps.MapDispatch.firePlayerQuit(uuid));
        }
        RTP.log(Level.INFO, "[RTP][NeoForge] map binding installed (NeoForgeMapBinding, carrier="
            + mapAdapter.mcVersion() + ").");
      } else {
        RTP.log(Level.INFO, "[RTP][NeoForge] map binding NOT installed: version adapter "
            + (mapAdapter == null ? "<none>" : mapAdapter.mcVersion())
            + " does not support map charts; /rtp charts will report mapBindingMissing.");
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP][NeoForge] map binding install failed; MapDispatch stays on "
          + "NoopMapBinding: " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
    }

    // N2.3 — database bootstrap (mirrors BukkitDatabaseHandler / Fabric's
    // SERVER_STARTED setupDatabase): selects the accessor from config, runs
    // the migration chain, and schedules databaseAccessor.startup(). Then start
    // the periodic async query drain (needed for SQL-transport network mode).
    try {
      NeoForgeDatabaseHandler.setupDatabase(rtp);
    } catch (java.nio.file.FileSystemException fse) {
      RTP.log(Level.SEVERE,
          "[RTP][NeoForge] database setup failed \u2014 RTP will run without persistence.", fse);
    } catch (Throwable t) {
      RTP.log(Level.SEVERE,
          "[RTP][NeoForge] database setup failed: "
              + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
    }
    try {
      DatabaseProcessing.start();
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP][NeoForge] DatabaseProcessing.start failed: "
          + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    // effects-api-ADR-003 / ADR-005 — wire the effect layer to the teleport
    // pipeline (SOUND/PARTICLE/TITLE/POTION on the rtp.effect.* lifecycle
    // hooks, driven by effects/<group>.yml + permissions). Reuses the Mojmap
    // effectsapi.fabric runtime (shared with Fabric; flagged for rename).
    try {
      io.github.dailystruggle.rtp.neoforge.effects.NeoForgeEffectsHandler.setupEffects(server);
      RTP.log(Level.INFO, "[RTP][NeoForge] effects layer installed (NeoForgeEffectsHandler).");
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP][NeoForge] effects layer install failed: "
          + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
    }

    // ADR-023 — login reserve cache on the default-world region (no-op unless
    // PerformanceKeys.loginCacheEnabled). Runs after setupDatabase so regions
    // have been (re)built.
    bridge.initLoginReserveCache(server);

    RTP.log(Level.INFO,
        "[RTP][NeoForge] Core bound \u2014 accessor + scheduler + event bridge + database + "
            + "permissions (N2.5) + menu/book/map/metrics/backend sinks (N2.6) installed; "
            + "/rtp command tree registers on RegisterCommandsEvent.");
    // TODO(Phase N2.7+, @leaf_26): the S-00x regression guards + traceability
    // rows (N2.7), the live network-mode boot (reservation-token redemption,
    // ADR-049 Step J), and the native PvP combat tag (ADR-055). The version
    // adapter (installed in onServerStarted) supplies the S-005 async chunk
    // path and non-persistent chunk tickets.
  }

  /**
   * Classify the running MC version and reflectively install the matching
   * carrier's {@link NeoForgeVersionAdapter} into the
   * {@link NeoForgeVersionAdapterRegistry} (NeoForge analogue of Fabric's
   * {@code installVersionAdapter}). Carriers live in sibling
   * {@code rtp-neoforge-vXX_YY_RN} modules that depend on this common module,
   * so common resolves them by fully-qualified name rather than a compile-time
   * reference (which would invert the module dependency).
   *
   * <p>If no carrier matches, the registry stays empty and every
   * version-volatile call site throws {@link IllegalStateException} per S-006
   * (fail loud, never silently no-op).</p>
   */
  private void installVersionAdapter(MinecraftServer server) {
    if (NeoForgeVersionAdapterRegistry.isInstalled()) {
      return;
    }
    // Resolve the running MC version from the live server first
    // (MinecraftServer#getServerVersion is stable across the 1.21.x line, e.g.
    // "1.21.11"). SharedConstants.getCurrentVersion().getName() is only a
    // fallback: its accessor names are version-volatile (the 1.21.11 mapping
    // rename broke it, yielding "<unknown>" and leaving no carrier installed).
    String mcVersion = "<unknown>";
    try {
      if (server != null) {
        String v = server.getServerVersion();
        if (v != null && !v.isEmpty()) mcVersion = v;
      }
    } catch (Throwable ignored) {
      // fall through to the SharedConstants probe
    }
    if ("<unknown>".equals(mcVersion)) {
      try {
        mcVersion = SharedConstants.getCurrentVersion().getName();
      } catch (Throwable ignored) {
        mcVersion = "<unknown>";
      }
    }
    // Carrier FQCN candidates, most-specific first. Extend as carriers land
    // (rolling 1.21.x head, 26.x) per platforms/rtp-neoforge/README.md.
    String fqcn = null;
    if (mcVersion.startsWith("1.21")) {
      fqcn = "io.github.dailystruggle.rtp.neoforge.v1_21_R1.V1_21_R1NeoForgeVersionAdapter";
    } else if (mcVersion.startsWith("26.")) {
      // EARLY/EXPERIMENTAL 26.x carrier (Java 25). Present in the unified jar
      // only when built on a JDK-25 host (settings.gradle gates it under
      // -PexcludeJdk25); on a JDK-21-only build the Class.forName below fails
      // gracefully and the S-006 fail-loud branch logs a warning.
      fqcn = "io.github.dailystruggle.rtp.neoforge.v26_1_R1.V26_1_R1NeoForgeVersionAdapter";
    }
    if (fqcn == null) {
      RTP.log(Level.WARNING, "[RTP][NeoForge] No version adapter carrier for MC " + mcVersion
          + " — version-sensitive call sites will throw (S-006).");
      return;
    }
    try {
      Class<?> clazz = Class.forName(fqcn);
      NeoForgeVersionAdapter adapter =
          (NeoForgeVersionAdapter) clazz.getDeclaredConstructor().newInstance();
      NeoForgeVersionAdapterRegistry.install(adapter);
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP][NeoForge] Failed to install version adapter " + fqcn
          + " for MC " + mcVersion + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
  }

  @SubscribeEvent
  public void onServerStopping(ServerStoppingEvent event) {
    // Stop the periodic databaseAccessor.processQueries drain BEFORE the server
    // tears down its tick loop (mirrors RTPBukkitPlugin.onDisable /
    // FabricEventBridge.onServerStopping). The final drain of pending mutations
    // is handled by rtp-core's RTPRunnable shutdown via RTP.stop().
    try {
      DatabaseProcessing.kill();
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP][NeoForge] DatabaseProcessing.kill failed: "
          + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
    try {
      if (RTP.getInstance() != null) {
        RTP.stop();
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[RTP][NeoForge] RTP.stop() raised on shutdown", t);
    } finally {
      // Drop the effects runtime's server binding so post-stop effect fires
      // become no-ops instead of dispatching onto a torn-down server.
      try {
        io.github.dailystruggle.effectsapi.fabric.FabricEffectRuntime.unbindServer();
      } catch (Throwable ignored) {
        // effects-api absent or already unbound; nothing to do.
      }
      if (accessor != null) {
        accessor.unbindServer();
      }
    }
  }

  @SubscribeEvent
  public void onServerTick(ServerTickEvent.Post event) {
    // Drain the scheduler bound into RTP.scheduler (the accessor's scheduler).
    if (accessor != null) {
      accessor.getNeoForgeScheduler().tick(event.getServer());
    }
    // Drive any auto-expiring chunk-ticket refresh the active carrier needs
    // (no-op for the 1.21.1 carrier, which uses non-expiring tickets).
    NeoForgeVersionAdapter adapter = NeoForgeVersionAdapterRegistry.peek();
    if (adapter != null) {
      adapter.tickRefresh();
    }
    // N2.6 - drive the metrics sampler EMA once per tick if a
    // NeoForgeMetricsBinding is installed. Best-effort; never break the tick.
    try {
      if (RTP.getInstance() != null && RTP.metrics != null) {
        Object binding = RTP.metrics.getBinding();
        if (binding instanceof io.github.dailystruggle.rtp.neoforge.metrics.NeoForgeMetricsBinding nmb) {
          nmb.tick();
        }
      }
    } catch (Throwable t) {
      RTP.log(Level.FINER, "[RTP][NeoForge] metrics tick raised "
          + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
  }

  @SubscribeEvent
  public void onRegisterCommands(RegisterCommandsEvent event) {
    // NeoForge fires this event during the initial datapack /
    // ReloadableServerResources load in Main.main -- BEFORE the MinecraftServer
    // object exists and before ServerStartedEvent (where bootCore binds the
    // live server). Building the /rtp tree dereferences RTP.getInstance() and
    // RTP.configs (e.g. ReloadCmd), so the server-independent core must already
    // exist here, otherwise NeoForgeCommandRegistrar.register NPEs while
    // constructing RTPCmdNeoForgeRoot. prepareCore() needs no server, so it is
    // safe to run on this worker thread; bootCore() re-runs it as a no-op.
    try {
      prepareCore();
    } catch (Throwable t) {
      RTP.log(Level.SEVERE,
          "[RTP][NeoForge] Core preparation for command registration failed; /rtp will be "
              + "unavailable this run: " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
      return;
    }
    // Reuse the commands-api Brigadier bridge (commands-api-ADR-001). The
    // registrar adapts NeoForge's CommandDispatcher<CommandSourceStack> to the
    // shared command tree; only this trampoline is platform specific.
    NeoForgeCommandRegistrar.register(event.getDispatcher());
  }
}
