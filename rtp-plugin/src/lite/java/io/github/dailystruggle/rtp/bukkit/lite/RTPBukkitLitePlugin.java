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
 * RTP-lite bootstrap (ADR-024). DRAFT SKELETON pending Rule D-005 approval.
 *
 * <p>Mirrors the surviving steps of {@link RTPBukkitPlugin} for the lite assembly variant
 * and OMITS the following, in this exact order of omission:
 * <ol>
 *   <li>{@code org.sqlite.JDBC} probe in {@code onLoad}.</li>
 *   <li>{@code BukkitDatabaseHandler.setupDatabase} -- lite uses {@code YamlFileDatabase}
 *       (or an in-memory accessor) wired by {@link RTP}'s default constructor path.</li>
 *   <li>Login reserve cache initialization (ADR-023).</li>
 *   <li>{@code isFolia()} branching -- lite is Spigot/Paper only.</li>
 *   <li>PlaceholderAPI registration -- lite does not declare PAPI as a softdepend.</li>
 *   <li>Visitor / observation mode wiring ({@code PerformanceKeys.visitorEnabled}).</li>
 * </ol>
 *
 * <p>S-001..S-007 compliance is shared with the full bootstrap because all chunk I/O,
 * MemoryTracker accounting, and stale-chunk guard logic live in {@code rtp-core} and
 * are loaded identically.
 *
 * <p>This class is intentionally divergent from {@link RTPBukkitPlugin}. Do NOT collapse
 * the two via runtime flags; the whole point of ADR-024 is that the lite bootstrap reads
 * top-to-bottom with no "if (lite)" branches and no dead code paths.
 *
 * <p><b>Implementation note for the proposal phase:</b> several method calls below
 * (database setup, command registration, listener registration) currently route through
 * package-private helpers on {@code RTPBukkitPlugin}. Before this skeleton is moved out
 * of draft status, those helpers must be either:
 * (a) extracted to a shared {@code BootstrapSupport} class in
 *     {@code io.github.dailystruggle.rtp.bukkit} (preferred), or
 * (b) given package-visible counterparts in a way that preserves the "no shared bootstrap
 *     conditionals" invariant of ADR-024.
 * The TODO markers below pinpoint each such site.
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

      // Step 1: server-model resolve + accessor / scheduler wiring.
      // Lite supports Spigot and Paper only (no Folia branch). BukkitServerProvider
      // already returns the correct accessor/scheduler pair for the host; lite simply
      // never reaches the Folia code path because :rtp-folia:** is not on the classpath.
      // Shared helper with the full bootstrap (ADR-024).
      if (!BootstrapSupport.wireServerAccessorAndScheduler(this, "LIFECYCLE-LITE")) {
        onDisable();
        return;
      }
    }

    // Step 2: bStats with a distinct pluginId so lite installs are tracked separately.
    // ADR-024: lite uses the v2-branch bStats id (12277) to keep historical continuity
    // for the lite-style install base; full uses 30865.
    RTP.log(Level.FINE, "[RTP] onEnable initializing bStats id=12277");
    metrics = new Metrics(this, 12277);
    // Same cost-metrics chart catalogue as the full assembly, with the
    // assembly_variant pie reporting "lite" so dashboards can split.
    io.github.dailystruggle.rtp.bukkit.metrics.RTPCostMetricsCharts.register(metrics, "lite");

    // CHECKLIST-metrics-and-multiserver.md row B9: install the platform-
    // appropriate MetricsBinding (Paper vs raw Spigot). Mirrors the full
    // bootstrap; lite has no Folia branch, but the dispatcher's Paper
    // probe is platform-symmetric so the same call works. Best-effort.
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

    // Step 3: command registration. Shared with the full bootstrap (ADR-024 helper).
    // The helper builds the platform-neutral CoreRtpRoot (ADR-070) with the
    // Bukkit seams; passing `this` (the lite JavaPlugin) is the parity-correct
    // call for both editions.
    BootstrapSupport.registerRtpAndWildCommands(this);

    // Step 4: drain startup tasks (region binding etc.). Shared helper (ADR-024).
    BootstrapSupport.drainStartupTasks();

    // Step 5: register listeners. Lite registers a strict subset:
    //   - OnPlayerJoin, OnPlayerQuit, OnWorldLoadUnload (required for region lifecycle)
    //   - OnEventTeleports (for rtp.onevent.* permissions that survive in lite, if any)
    // It does NOT register the PAPI hook or the visitor-mode listener.
    // TODO(ADR-024): decide whether to keep rtp.onevent.* in lite at all (per the open
    // question in the research thread). If dropped, register only OnWorldLoadUnload +
    // the join/quit listeners required for region lifecycle.
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

    // Step 6: chunk-unload processor (Spigot/Paper only -- safe in lite, no Folia branch).
    RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1);

    // Step 7: database processor. With yaml-only persistence this is effectively a
    // no-op flush loop; kept for symmetry with the full bootstrap so /rtp reload
    // behaves identically.
    DatabaseProcessing.start();

    SendMessage.sendMessage(Bukkit.getConsoleSender(), "");

    // Step 8: drain late startup tasks. Shared helper (ADR-024).
    BootstrapSupport.drainStartupTasks();

    // Maps subsystem (ADR-047 / CHECKLIST-metrics-to-maps Stage 2.6). The
    // maps-api, BukkitMapBinding, BukkitBiomeColorSource, and the
    // visualization resolvers are all shipped in the lite jar (ADR-024 does
    // not exclude mapsapi/**). Without this install MapDispatch stays on
    // NoopMapBinding and every `/rtp visualization ...` click bottoms out
    // in the configurable `mapBindingMissing` message ("map rendering is
    // not available on this server"). Lite is Paper-only per ADR-024
    // (line 190), so unconditionally install the plain BukkitMapBinding;
    // no Folia branch is needed here. Failure is logged and swallowed so a
    // hypothetical platform that can't allocate maps degrades gracefully
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

    // Step 9: per-permission effects parse, deferred to tick+1 to mirror the
    // full bootstrap. Lite ships effects-api shaded so per-permission effects
    // (e.g. rtp.effects.<name>) must fire identically to the full edition.
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

    // Step 10: Vault/economy is wired here identically to the full bootstrap: when Vault
    // is present and no economy provider is bound yet, bind VaultChecker through
    // the public RTPHooks facade. Lite ships economy.yml, so optional per-region
    // teleport charging works out of the box (ADR-024, 2026-06-01 amendment).
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

    // Bundled stripped admin docs (ADR-024). The lite jar carries a small
    // `lite-docs/` resource tree (intentionally not under `docs/` inside the
    // jar, which the ADR-024 `liteJarStructureCheck` audit forbids in lite).
    // The resources are extracted into the operator-facing `docs/` folder under
    // the plugin data directory. Extract on every start so upgrades pick up
    // updated bundled copies; existing files are overwritten, which is safe
    // because the extracted docs are read-only reference material, not
    // operator-edited config.
    extractBundledLiteDocs();
    RTP.log(Level.INFO,
        "[RTP] Documentation extracted to "
            + new java.io.File(getDataFolder(), "docs").getAbsolutePath());
  }

  /**
   * Copies every classpath resource under `lite-docs/` into
   * `<plugin-data>/docs/`. The list of resources is hard-coded rather than
   * scanned because Bukkit's classloader does not expose directory listings on
   * shaded jars. The jar-internal resource tree stays under `lite-docs/`
   * because the ADR-024 `liteJarStructureCheck` audit forbids a `docs/` entry
   * in the lite jar; only the extracted, operator-facing destination is named
   * `docs/`. Adding a new bundled doc requires updating both the resource file
   * and the list below; the lite assembly's smoke test verifies that every
   * entry resolves.
   */
  private void extractBundledLiteDocs() {
    String[] resources = {
        "lite-docs/INDEX.md",
        "lite-docs/QUICK_START.md",
        "lite-docs/COMMANDS.md",
        "lite-docs/CONFIGURATION.md",
        "lite-docs/SAFETY.md",
        "lite-docs/FAQ.md",
        "lite-docs/figures/region-shape.txt",
        "lite-docs/figures/spiral-mapping.txt",
    };
    java.io.File dataFolder = getDataFolder();
    //noinspection ResultOfMethodCallIgnored
    new java.io.File(dataFolder, "docs/figures").mkdirs();
    for (String resource : resources) {
      // Map the jar-internal `lite-docs/` prefix to the operator-facing `docs/`
      // destination folder.
      String relativeTarget = resource.replaceFirst("^lite-docs/", "docs/");
      java.io.File target = new java.io.File(dataFolder, relativeTarget);
      //noinspection ResultOfMethodCallIgnored
      target.getParentFile().mkdirs();
      try (java.io.InputStream in = getResource(resource)) {
        if (in == null) {
          RTP.log(Level.WARNING,
              "[RTP] Missing bundled doc resource: " + resource);
          continue;
        }
        try (java.io.OutputStream out = new java.io.FileOutputStream(target)) {
          byte[] buf = new byte[8192];
          int n;
          while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
          }
        }
      } catch (java.io.IOException e) {
        RTP.log(Level.WARNING,
            "[RTP] Failed to extract bundled doc " + resource, e);
      }
    }
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
    // CHECKLIST-metrics-and-multiserver.md row B9: mirror the full bootstrap
    // teardown so a /reload cycle reinstalls the binding cleanly. Idempotent.
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
