package io.github.dailystruggle.rtp.bukkit.lite;

import io.github.dailystruggle.rtp.bukkit.BootstrapSupport;
import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.effects.BukkitEffectsHandler;
import io.github.dailystruggle.rtp.bukkit.tools.softdepends.VaultChecker;
import io.github.dailystruggle.rtp.bukkit.bukkitListeners.OnPlayerJoin;
import io.github.dailystruggle.rtp.bukkit.bukkitListeners.OnPlayerQuit;
import io.github.dailystruggle.rtp.bukkit.bukkitListeners.OnEventTeleports;
import io.github.dailystruggle.rtp.bukkit.bukkitListeners.OnWorldLoadUnload;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.network.NetworkModeBootstrap;
import io.github.dailystruggle.rtp.common.tasks.ChunkUnloadProcessor;
import io.github.dailystruggle.rtp.bukkitplatform.server.AsyncTeleportProcessing;
import io.github.dailystruggle.rtp.common.server.DatabaseProcessing;
import io.github.dailystruggle.rtp.bukkitplatform.server.SyncTeleportProcessing;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * RTP-lite bootstrap (ADR-024) for the lite assembly variant. Mirrors the surviving
 * steps of {@link RTPBukkitPlugin} and OMITS, in this order: the {@code org.sqlite.JDBC}
 * probe (no SQL drivers shipped); {@code BukkitDatabaseHandler.setupDatabase} (lite uses
 * {@code YamlFileDatabase}); the login reserve cache (ADR-023); Folia branching (lite is
 * Spigot/Paper only); PlaceholderAPI registration; and visitor/observation mode wiring.
 *
 * <p>S-001..S-007 compliance is shared with the full bootstrap: all chunk I/O,
 * MemoryTracker accounting, and stale-chunk guard logic live in {@code rtp-core} and load
 * identically. Shared, branch-free bootstrap steps route through {@code BootstrapSupport}.
 *
 * <p>Intentionally divergent from {@link RTPBukkitPlugin}. Do NOT collapse the two via
 * runtime flags: ADR-024 requires the lite bootstrap to read top-to-bottom with no
 * {@code if (lite)} branches and no dead code paths.
 */
@SuppressWarnings("unused")
public final class RTPBukkitLitePlugin extends JavaPlugin {

  private static RTPBukkitLitePlugin instance = null;
  private static Metrics metrics;

  /**
   * Network-mode bootstrap (ADR-036). Lite ships only the DB-free tiers
   * (plugin-message / proxy-cache, ADR-024 amendment); the durable SQL/Redis
   * transports are excluded from the lite jar. Without this the entire
   * cross-server transport machinery is inert under the lite assembly.
   */
  private final NetworkModeBootstrap networkBootstrap = new NetworkModeBootstrap();

  /** @return the single plugin instance initialized at bukkit startup */
  public static RTPBukkitLitePlugin getInstance() {
    return instance;
  }

  @Override
  public void onLoad() {
    // Lite intentionally does NOT probe org.sqlite.JDBC. SQL drivers are not shipped.
    RTP.log(Level.FINE, "[RTP] onLoad ENTER");
    RTP.log(Level.FINE, "[RTP] onLoad EXIT (no SQL probe; ADR-024)");
  }

  @Override
  public void onEnable() {
    if (instance == null) {
      instance = this;

      // Server-model resolve + accessor/scheduler wiring (shared helper, ADR-024).
      // Lite is Spigot/Paper only: :rtp-folia:** is not on the classpath, so the
      // Folia code path is never reached even though BukkitServerProvider handles it.
      if (!BootstrapSupport.wireServerAccessorAndScheduler(this, "LIFECYCLE-LITE")) {
        onDisable();
        return;
      }
    }

    // bStats: lite uses a distinct pluginId (12277) so lite installs are tracked
    // separately from full (30865), preserving continuity for the lite install base.
    RTP.log(Level.FINE, "[RTP] onEnable initializing bStats id=12277");
    metrics = new Metrics(this, 12277);
    // Same cost-metrics chart catalogue as the full assembly, with the
    // assembly_variant pie reporting "lite" so dashboards can split.
    io.github.dailystruggle.rtp.bukkit.metrics.RTPCostMetricsCharts.register(metrics, "lite");

    // Install the platform-appropriate MetricsBinding (Paper vs raw Spigot).
    // The dispatcher's Paper probe is platform-symmetric, so the same call works
    // as in the full bootstrap. Best-effort.
    io.github.dailystruggle.rtp.bukkit.metrics.MetricsBindingDispatcher.install();

    if (RTP.getInstance() == null) {
      RTP.serverAccessor.start(this);
      // Multilingual bootstrap (ADR-020) ships in lite as of the 2026-05-11 ADR-024
      // language-options amendment. No explicit call is needed here: Configs#reloadConfigs()
      // invokes LanguageBootstrap.resolve(pluginDirectory) unconditionally, and the lite jar
      // now ships lang/** plus language.yml so the locale resource lookups succeed.
      RTP rtp = new RTP();

      // Read routing.lobbyMode from network.yml BEFORE regions are loaded
      // (reloadRegions below constructs Region instances). On a lobby this
      // skips the local region processing (ScanTask pre-fill, DB hydrate,
      // Region.execute pulse) so the lobby does not initialise default
      // regions. The full bootstrap performs this same early-read; omitting
      // it on lite was the cause of lite lobbies hydrating regions.
      // Defensive: any failure resolves to lobbyMode=false.
      try {
        java.io.File earlyNetworkYml = NetworkModeBootstrap.ensureNetworkYml(
            getDataFolder(), RTPBukkitLitePlugin.class);
        RTP.lobbyMode = NetworkModeBootstrap.readLobbyModeEarly(earlyNetworkYml);
        if (RTP.lobbyMode) {
          RTP.log(Level.INFO,
              "[RTP] onEnable routing.lobbyMode=true -- local region"
                  + " processing skipped; this backend acts as a pure cross-server"
                  + " dispatcher.");
        }
      } catch (Throwable t) {
        RTP.lobbyMode = false;
        RTP.log(Level.FINE,
            "[RTP] onEnable lobbyMode early-read failed; defaulting to false: "
                + t.getMessage());
      }

      // Yaml-only persistence wiring (ADR-024). Mirrors the minimum subset of
      // BukkitDatabaseHandler.setupDatabase that lite still needs:
      //   1. Ensure the database/ directory exists (YamlFileDatabase reads/writes there).
      //   2. Call reloadConfigs() so MultiConfigParser<WorldKeys>/<RegionKeys> are
      //      registered before any listener fires (otherwise SelectionAPI.getRegion
      //      NPEs on the first PlayerJoinEvent -- see issue trace).
      //   3. Construct YamlFileDatabase as the sole DatabaseAccessor.
      //   4. reloadRegions() + start the database accessor on the next tick.
      try {
        java.io.File databaseDirectory =
            new java.io.File(RTP.configs.pluginDirectory, "database");
        //noinspection ResultOfMethodCallIgnored
        databaseDirectory.mkdirs();
        RTP.configs.reloadConfigs();
        rtp.databaseAccessor =
            new io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase(
                databaseDirectory);
        RTP.configs.reloadRegions();
        RTP.scheduler.runTaskLater(() -> RTP.getInstance().databaseAccessor.startup(), 1);
        // ADR-060: emergency-platform restore reaper (in-memory only under yaml persistence).
        RTP.scheduler.runTaskLater(
            io.github.dailystruggle.rtp.common.platform.PlatformRestoreManager::startGlobal, 2);
      } catch (Exception e) {
        RTP.log(Level.WARNING,
            "[RTP] yaml-only persistence wiring failed", e);
      }

      // Boot backend-side network mode (ADR-036). No-op when network.yml is
      // absent or network.enabled=false. Lite resolves to the DB-free transport
      // tiers only (plugin-message / proxy-cache); failure is logged but never
      // aborts plugin enable (network mode is strictly optional).
      try {
        java.io.File networkYml = NetworkModeBootstrap.ensureNetworkYml(
            getDataFolder(), RTPBukkitLitePlugin.class);
        networkBootstrap.boot(networkYml);
      } catch (Throwable t) {
        RTP.log(Level.WARNING,
            "[RTP] onEnable network-mode boot failed; continuing without it: "
                + t.getMessage(), t);
      }
    }

    // Command registration (shared helper, ADR-024). Builds the platform-neutral
    // CoreRtpRoot (ADR-070) with the Bukkit seams; passing `this` is parity-correct
    // for both editions.
    BootstrapSupport.registerRtpAndWildCommands(this);

    // Drain startup tasks (region binding etc.); shared helper (ADR-024).
    BootstrapSupport.drainStartupTasks();

    // Register a strict listener subset: join/quit/world-load (region lifecycle) plus
    // OnEventTeleports (surviving rtp.onevent.* permissions). No PAPI hook, no
    // visitor-mode listener.
    // TODO(ADR-024): decide whether rtp.onevent.* stays in lite; if dropped, keep only
    // the world-load + join/quit listeners required for region lifecycle.
    Bukkit.getPluginManager().registerEvents(new OnPlayerJoin(), this);
    Bukkit.getPluginManager().registerEvents(new OnPlayerQuit(), this);
    Bukkit.getPluginManager().registerEvents(new OnWorldLoadUnload(), this);
    Bukkit.getPluginManager().registerEvents(new OnEventTeleports(), this);
    OnWorldLoadUnload.rebindFallbackRegionsForAllLoadedWorlds();

    // Cross-server arrival wiring (ADR-036). Registers the network-mode
    // JoinTriggerSource and the cross-server waitlist quit listener so a
    // proxied player arriving on this backend redeems their pending RTP.
    // All no-ops when network mode is disabled (boot() left them null).
    try {
      networkBootstrap.registerJoinTriggerSource();
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP] JoinTriggerSource registration failed; continuing: "
              + t.getMessage(), t);
    }
    try {
      networkBootstrap.registerWaitlistQuitListener();
      java.util.function.Predicate<io.github.dailystruggle.rtp.api.entity.RTPCommandSender> guard =
          networkBootstrap.waitlistCommandGuard();
      if (guard != null
          && RTP.baseCommand instanceof io.github.dailystruggle.rtp.common.commands.CoreRtpRoot cmd) {
        // commands-api-ADR-003: sender-check list is Predicate<RTPCommandSender>;
        // register the neutral guard directly (console senders always pass).
        cmd.addSenderCheck(rs ->
            !(rs instanceof io.github.dailystruggle.rtp.api.entity.RTPPlayer) || guard.test(rs));
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP] waitlist wiring failed; continuing: " + t.getMessage(), t);
    }

    // Chunk-unload processor (Spigot/Paper only; safe in lite, no Folia branch).
    RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1);

    // Database processor. Under yaml-only persistence this is effectively a no-op
    // flush loop, kept for symmetry so /rtp reload behaves identically to full.
    DatabaseProcessing.start();

    SendMessage.sendMessage(Bukkit.getConsoleSender(), "");

    // Drain late startup tasks; shared helper (ADR-024).
    BootstrapSupport.drainStartupTasks();

    // Maps subsystem (ADR-047). The maps-api, BukkitMapBinding, BukkitBiomeColorSource,
    // and visualization resolvers all ship in the lite jar. Without this install
    // MapDispatch stays on NoopMapBinding and every `/rtp visualization ...` click
    // yields the configurable `mapBindingMissing` message. Lite is Paper-only, so
    // install the plain BukkitMapBinding unconditionally; failure degrades gracefully
    // to the same `mapBindingMissing` UX rather than crashing onEnable.
    try {
      io.github.dailystruggle.mapsapi.bukkit.BukkitMapBinding binding =
          new io.github.dailystruggle.mapsapi.bukkit.BukkitMapBinding();
      io.github.dailystruggle.rtp.common.commands.maps.MapDispatch.setMapBinding(binding);
      RTP.log(Level.FINE,
          "[RTP] onEnable installed " + binding.getClass().getSimpleName()
              + " (MapDispatch active binding)");
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP] onEnable MapBinding install failed; MapDispatch will fall back to NoopMapBinding",
          t);
    }
    // Install the Bukkit-family BiomeColorSource so the biomes visualisation
    // asks the server for each biome's native cartography colour (rather
    // than falling back to the built-in 16-entry categorical palette).
    try {
      io.github.dailystruggle.mapsapi.BiomeColorSource.install(
          new io.github.dailystruggle.rtp.bukkit.maps.BukkitBiomeColorSource());
      RTP.log(Level.FINE,
          "[RTP] onEnable installed BukkitBiomeColorSource");
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP] onEnable BiomeColorSource install failed;"
              + " biomes viz will use the built-in categorical palette",
          t);
    }

    // Per-permission effects parse, deferred to tick+1 to mirror full. effects-api
    // is shaded into lite so rtp.effects.<name> fires identically to the full edition.
    RTP.log(Level.FINE,
        "[RTP] onEnable scheduling deferred BukkitEffectsHandler.setupEffects (tick+1)");
    RTP.scheduler.runTaskLater(() -> {
      RTP.log(Level.FINER,
          "[RTP] deferred BukkitEffectsHandler.setupEffects firing");
      // The `plugin` parameter is unused inside setupEffects (event dispatch
      // goes through Bukkit.getPluginManager()), so passing null is safe and
      // avoids touching the full-edition singleton from the lite bootstrap.
      BukkitEffectsHandler.setupEffects(null);
    }, 1);

    // Vault/economy wiring, identical to full: when Vault is present and no economy
    // provider is bound yet, bind VaultChecker through the public RTPHooks facade.
    // Lite ships economy.yml, so optional per-region teleport charging works out of
    // the box (ADR-024).
    RTP.scheduler.runTaskLater(() -> {
      try {
        if (RTP.economy == null
            && Bukkit.getServer().getPluginManager().getPlugin("Vault") != null) {
          VaultChecker.setupEconomy();
          VaultChecker.setupPermissions();
          if (VaultChecker.getEconomy() != null) {
            io.github.dailystruggle.rtp.api.RTPAPI.hooks().economy().bind(new VaultChecker());
          }
        }
      } catch (Throwable t) {
        RTP.log(Level.WARNING,
            "[RTP] Failed to initialize Vault economy; continuing without it.",
            t);
      }
      // Bundled combat-tag plugin integrations for the optional PvP gate (ADR-055).
      // Binds the first enabled combat plugin (PvPManager / CombatLogX / Simple Combat
      // Log) to PvPCombatStateRegistry; no-op when none is present (native fallback).
      try {
        io.github.dailystruggle.rtp.bukkit.tools.softdepends.pvp.PvPIntegrations.setup(this);
      } catch (Throwable t) {
        RTP.log(Level.WARNING,
            "[RTP] Failed to initialize combat-tag integrations; continuing with the native PvP tracker.",
            t);
      }
    }, 1);

    // Lite OMITS:
    //   - initLoginReserveCache()                (ADR-023)
    //   - PlaceholderAPI hook
    //   - Visitor-mode wiring                    (PerformanceKeys.visitorEnabled)
    //
    // Each omission corresponds to a documented support-load source per ADR-024.

    // Bundled operator docs. Lite ships the same `docs/` tree as the Pro
    // edition (config/docs parity) and extracts it through the shared
    // JarUtils.extractDocs, identical to the Pro bootstrap. The docs are
    // version-stamped and only overwritten on a version change, so
    // operator-visible reference material stays in sync with the jar.
    io.github.dailystruggle.rtp.bukkit.utils.JarUtils.extractDocs(
        getDataFolder(), getDescription().getVersion());
    RTP.log(Level.INFO,
        "[RTP] Documentation extracted to "
            + new java.io.File(getDataFolder(), "docs").getAbsolutePath());
  }

  @Override
  public void onDisable() {
    RTP.log(Level.FINE, "[RTP] onDisable ENTER");
    // Stop the backend heartbeat publisher + close the network transport
    // first (reverse-order teardown). Idempotent; safe if network mode was
    // never enabled this lifecycle.
    try {
      networkBootstrap.shutdown();
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP] onDisable network-mode shutdown failed (continuing): "
              + t.getMessage(), t);
    }
    // Mirror the full-bootstrap metrics teardown so a /reload cycle reinstalls the
    // binding cleanly. Idempotent.
    try {
      io.github.dailystruggle.rtp.bukkit.metrics.MetricsBindingDispatcher.uninstall();
    } catch (Throwable ignored) {
    }
    // Fan out the host-plugin disable to every registered MapBindingLifecycle
    // so the active BukkitMapBinding (installed in onEnable above) releases
    // its cached MapHandles. Idempotent; safe if MapDispatch was never used.
    try {
      io.github.dailystruggle.rtp.common.commands.maps.MapDispatch.fireDisable();
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP] onDisable MapDispatch.fireDisable failed (continuing): "
              + t.getMessage(), t);
    }
    // Lite has no shutdown-flush phase (no SQL/Redis backend to drain).
    // Async/sync teleport processors are stopped via the same hooks the full bootstrap
    // uses; biomeRecall, MemoryTracker, and queue state are in-memory only and are
    // released by region/queue manager teardown.
    try {
      AsyncTeleportProcessing.kill();
    } catch (Throwable ignored) {
      // matches the defensive style in RTPBukkitPlugin.onDisable
    }
    try {
      SyncTeleportProcessing.kill();
    } catch (Throwable ignored) {
      // matches the defensive style in RTPBukkitPlugin.onDisable
    }
    try {
      DatabaseProcessing.kill();
    } catch (Throwable ignored) {
      // yaml flush is synchronous; no shutdown-flush race to manage
    }
    instance = null;
    RTP.log(Level.FINE, "[RTP] onDisable EXIT");
  }
}
