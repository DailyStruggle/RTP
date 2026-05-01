package io.github.dailystruggle.rtp.bukkit.lite;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.bukkit.commands.RTPCmdBukkit;
import io.github.dailystruggle.rtp.bukkit.spigotListeners.OnPlayerJoin;
import io.github.dailystruggle.rtp.bukkit.spigotListeners.OnPlayerQuit;
import io.github.dailystruggle.rtp.bukkit.server.BukkitServerProvider;
import io.github.dailystruggle.rtp.bukkit.spigotListeners.OnEventTeleports;
import io.github.dailystruggle.rtp.bukkit.spigotListeners.OnWorldLoadUnload;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.ChunkUnloadProcessor;
import io.github.dailystruggle.rtp.spigot.server.AsyncTeleportProcessing;
import io.github.dailystruggle.rtp.spigot.server.DatabaseProcessing;
import io.github.dailystruggle.rtp.spigot.server.SyncTeleportProcessing;
import io.github.dailystruggle.rtp.spigot.tools.SendMessage;
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
 *   <li>{@code setupIntegrations()} -- claim plugin softdepends (ADR-019).</li>
 *   <li>{@code BukkitEffectsHandler.setupEffects} -- effects pipeline runs without the
 *       per-permission startup parse ({@code PerformanceKeys.effectParsing}).</li>
 *   <li>{@code isFolia()} branching -- lite is Spigot/Paper only.</li>
 *   <li>PlaceholderAPI registration -- lite does not declare PAPI as a softdepend.</li>
 *   <li>Multilingual bootstrap (ADR-020) -- locale is hardcoded to English; the lite
 *       JAR contains no {@code lang/**} resources and no {@code language.yml}.</li>
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
      BukkitServerProvider.ServerModel serverModel;
      try {
        serverModel = BukkitServerProvider.resolveServerModel(this);
        RTP.serverAccessor =
            (io.github.dailystruggle.rtp.api.server.RTPServerAccessor)
                Class.forName(serverModel.accessorClassName)
                    .getDeclaredConstructor().newInstance();
        RTP.scheduler =
            (io.github.dailystruggle.rtp.api.scheduling.RTPScheduler)
                Class.forName(serverModel.schedulerClassName)
                    .getDeclaredConstructor(JavaPlugin.class).newInstance(this);
      } catch (Exception e) {
        RTP.log(Level.WARNING,
            "[LIFECYCLE-LITE] onEnable reflection failure -- bailing out via onDisable", e);
        onDisable();
        return;
      }
    }

    // Step 2: bStats with a distinct pluginId so lite installs are tracked separately.
    // TODO(ADR-024): allocate a dedicated bStats plugin id and replace the placeholder
    // below before the lite JAR ships publicly.
    RTP.log(Level.FINE, "[LIFECYCLE-LITE] onEnable initializing bStats (lite id)");
    metrics = new Metrics(this, /* TODO replace */ 30865);

    if (RTP.getInstance() == null) {
      RTP.serverAccessor.start(this);
      // Lite skips multilingual bootstrap (ADR-020). RTP() default-constructs with
      // an English locale; no LanguageBootstrap call, no detectAndPreserveLocaleMismatch
      // path is exercised at runtime because language.yml is not on disk.
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

    // Step 3: command registration. Shared with the full bootstrap.
    // TODO(ADR-024): RTPCmdBukkit currently takes a JavaPlugin parameter; this works
    // for both bootstraps unchanged. Verify no tab-completer code reaches into
    // RTPBukkitPlugin.getInstance() expecting the full type.
    RTPCmdBukkit mainCommand = new RTPCmdBukkit(/* see TODO */ null);
    RTP.baseCommand = mainCommand;
    org.bukkit.command.PluginCommand rtpCommand = getCommand("rtp");
    if (rtpCommand != null) {
      rtpCommand.setExecutor(mainCommand);
      rtpCommand.setTabCompleter(mainCommand);
    }
    org.bukkit.command.PluginCommand wildCommand = getCommand("wild");
    if (wildCommand != null) {
      wildCommand.setExecutor(mainCommand);
      wildCommand.setTabCompleter(mainCommand);
    }

    // Step 4: drain startup tasks (region binding etc.).
    while (RTP.getInstance().startupTasks.size() > 0) {
      RTP.getInstance().startupTasks.execute(Long.MAX_VALUE);
    }

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

    // Step 8: drain late startup tasks.
    while (RTP.getInstance().startupTasks.size() > 0) {
      RTP.getInstance().startupTasks.execute(Long.MAX_VALUE);
    }

    // Lite OMITS:
    //   - initLoginReserveCache()                (ADR-023)
    //   - setupIntegrations()                    (ADR-019 claim plugins)
    //   - BukkitEffectsHandler.setupEffects(...) (effectParsing tree)
    //   - PlaceholderAPI hook
    //   - Visitor-mode wiring                    (PerformanceKeys.visitorEnabled)
    //
    // Each omission corresponds to a documented support-load source per ADR-024.

    // Help link, in lieu of bundled docs.
    RTP.log(Level.INFO,
        "[RTP-lite] Documentation: https://<docs-url-tbd>/lite -- see ADR-024 for scope.");
  }

  @Override
  public void onDisable() {
    RTP.log(Level.FINE, "[LIFECYCLE-LITE] onDisable ENTER");
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
