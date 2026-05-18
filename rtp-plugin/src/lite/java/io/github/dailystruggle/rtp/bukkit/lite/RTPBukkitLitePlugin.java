package io.github.dailystruggle.rtp.bukkit.lite;

import io.github.dailystruggle.rtp.bukkit.BootstrapSupport;
import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.effects.BukkitEffectsHandler;
import io.github.dailystruggle.rtp.bukkit.tools.softdepends.claims.ClaimIntegrations;
import io.github.dailystruggle.rtp.bukkit.bukkitListeners.OnPlayerJoin;
import io.github.dailystruggle.rtp.bukkit.bukkitListeners.OnPlayerQuit;
import io.github.dailystruggle.rtp.bukkit.bukkitListeners.OnEventTeleports;
import io.github.dailystruggle.rtp.bukkit.bukkitListeners.OnWorldLoadUnload;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.ChunkUnloadProcessor;
import io.github.dailystruggle.rtp.bukkitplatform.server.AsyncTeleportProcessing;
import io.github.dailystruggle.rtp.bukkitplatform.server.DatabaseProcessing;
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

  /** @return the single plugin instance initialized at bukkit startup */
  public static RTPBukkitLitePlugin getInstance() {
    return instance;
  }

  @Override
  public void onLoad() {
    // Lite intentionally does NOT probe org.sqlite.JDBC. SQL drivers are not shipped.
    RTP.log(Level.FINE, "[LIFECYCLE-LITE] onLoad ENTER");
    RTP.log(Level.FINE, "[LIFECYCLE-LITE] onLoad EXIT (no SQL probe; ADR-024)");
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
    RTP.log(Level.FINE, "[LIFECYCLE-LITE] onEnable initializing bStats id=12277");
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
      } catch (Exception e) {
        RTP.log(Level.WARNING,
            "[LIFECYCLE-LITE] yaml-only persistence wiring failed", e);
      }
    }

    // Step 3: command registration. Shared with the full bootstrap (ADR-024 helper).
    // RTPCmdBukkit takes a Plugin parameter; passing `this` (the lite JavaPlugin)
    // is the parity-correct call for both editions.
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

    // Step 6: chunk-unload processor (Spigot/Paper only -- safe in lite, no Folia branch).
    RTP.scheduler.runTaskTimer(new ChunkUnloadProcessor(), 1, 1);

    // Step 7: database processor. With yaml-only persistence this is effectively a
    // no-op flush loop; kept for symmetry with the full bootstrap so /rtp reload
    // behaves identically.
    DatabaseProcessing.start(this);

    SendMessage.sendMessage(Bukkit.getConsoleSender(), "");

    // Step 8: drain late startup tasks. Shared helper (ADR-024).
    BootstrapSupport.drainStartupTasks();

    // Step 9: per-permission effects parse, deferred to tick+1 to mirror the
    // full bootstrap. Lite ships effects-api shaded so per-permission effects
    // (e.g. rtp.effects.<name>) must fire identically to the full edition.
    RTP.log(Level.FINE,
        "[LIFECYCLE-LITE] onEnable scheduling deferred BukkitEffectsHandler.setupEffects (tick+1)");
    RTP.scheduler.runTaskLater(() -> {
      RTP.log(Level.FINER,
          "[LIFECYCLE-LITE] deferred BukkitEffectsHandler.setupEffects firing");
      // The `plugin` parameter is unused inside setupEffects (event dispatch
      // goes through Bukkit.getPluginManager()), so passing null is safe and
      // avoids touching the full-edition singleton from the lite bootstrap.
      BukkitEffectsHandler.setupEffects(null);
    }, 1);

    // Step 10: bundled claim-plugin integrations (ADR-019). Lite registers the
    // claim-plugin verifiers identically to the full bootstrap: each enabled
    // soft-dep (Lands, GriefDefender, GriefPrevention, Towny, HuskTowns,
    // Factions, RedProtect, WorldGuard) contributes a GlobalRegionVerifier.
    // Vault/economy is intentionally NOT wired here -- lite still ships no
    // economy.yml and the Vault soft-dep is Pro-only (ADR-024).
    RTP.scheduler.runTaskLater(() -> {
      try {
        ClaimIntegrations.setup(this);
      } catch (Throwable t) {
        RTP.log(Level.WARNING,
            "[LIFECYCLE-LITE] Failed to initialize claim-plugin integrations; continuing without them.",
            t);
      }
    }, 1);

    // Lite OMITS:
    //   - initLoginReserveCache()                (ADR-023)
    //   - Vault/economy wiring                   (ADR-024; economy.yml not shipped)
    //   - PlaceholderAPI hook
    //   - Visitor-mode wiring                    (PerformanceKeys.visitorEnabled)
    //
    // Each omission corresponds to a documented support-load source per ADR-024.

    // Bundled stripped admin docs (ADR-024). The lite jar carries a small
    // `lite-docs/` resource tree (intentionally not under `docs/`, which the
    // ADR-024 `liteJarStructureCheck` audit forbids in lite). Extract on every
    // start so upgrades pick up updated bundled copies; existing files are
    // overwritten, which is safe because lite-docs/** is read-only reference
    // material, not operator-edited config.
    extractBundledLiteDocs();
    RTP.log(Level.INFO,
        "[RTP-lite] Documentation extracted to "
            + new java.io.File(getDataFolder(), "lite-docs").getAbsolutePath()
            + " -- see ADR-024 for scope.");
  }

  /**
   * Copies every classpath resource under `lite-docs/` into
   * `<plugin-data>/lite-docs/`. The list of resources is hard-coded rather than
   * scanned because Bukkit's classloader does not expose directory listings on
   * shaded jars. Adding a new bundled doc requires updating both the resource
   * file and `LITE_DOCS_RESOURCES` below; the lite assembly's smoke test
   * verifies that every entry resolves.
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
    new java.io.File(dataFolder, "lite-docs/figures").mkdirs();
    for (String resource : resources) {
      java.io.File target = new java.io.File(dataFolder, resource);
      //noinspection ResultOfMethodCallIgnored
      target.getParentFile().mkdirs();
      try (java.io.InputStream in = getResource(resource)) {
        if (in == null) {
          RTP.log(Level.WARNING,
              "[RTP-lite] Missing bundled doc resource: " + resource);
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
            "[RTP-lite] Failed to extract bundled doc " + resource, e);
      }
    }
  }

  @Override
  public void onDisable() {
    RTP.log(Level.FINE, "[LIFECYCLE-LITE] onDisable ENTER");
    // CHECKLIST-metrics-and-multiserver.md row B9: mirror the full bootstrap
    // teardown so a /reload cycle reinstalls the binding cleanly. Idempotent.
    try {
      io.github.dailystruggle.rtp.bukkit.metrics.MetricsBindingDispatcher.uninstall();
    } catch (Throwable ignored) {
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
    RTP.log(Level.FINE, "[LIFECYCLE-LITE] onDisable EXIT");
  }
}
