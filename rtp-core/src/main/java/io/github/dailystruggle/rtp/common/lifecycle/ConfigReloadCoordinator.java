package io.github.dailystruggle.rtp.common.lifecycle;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.ConfigReloadLifecycle;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.network.NetworkHooks;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.ProbeFirstGovernor;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionConfigLoader;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.scan.ScanTask;
import io.github.dailystruggle.rtp.common.tasks.teleport.RTPTeleportCancel;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import io.github.dailystruggle.rtp.common.tasks.tick.TickBudget;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Tier 4 reload coordinator (ADR-115): the task, teleport and region steps of a config reload.
 *
 * <p>Installed into {@link Configs} by the composition root ({@link RTP}). Keeps the
 * {@code configuration} package free of dependencies on {@code tasks} and
 * {@code selection.region}.
 */
public final class ConfigReloadCoordinator implements ConfigReloadLifecycle {

  /**
   * Kills the scan task and cancels every in-flight teleport so no task reads a parser
   * mid-swap. Each cancel runs {@link RTPTeleportCancel}, which reports the failure (S-004).
   */
  @Override
  public void beforeReload() {
    RTPRuntime.log(Level.FINE, "[RTP] reloadConfigs(): killing ScanTask and clearing processingPlayers");
    ScanTask.kill();
    TeleportSessions.current().processingPlayers.clear();

    int cancelled = 0;
    for (Map.Entry<UUID, TeleportData> e : TeleportSessions.current().latestTeleportData.entrySet()) {
      TeleportData data = e.getValue();
      if (data == null || data.completed) continue;
      RTPRuntime.log(Level.FINER, "[RTP] reloadConfigs(): cancelling in-flight teleport for " + e.getKey());
      new RTPTeleportCancel(e.getKey()).run();
      cancelled++;
    }
    RTPRuntime.log(Level.FINE, "[RTP] reloadConfigs(): cancelled " + cancelled + " in-flight teleport(s)");
  }

  @Override
  public void onParsersChanged() {
    TickBudget.refresh();
  }

  /**
   * Shuts down existing regions and re-instantiates them from the region parsers of
   * {@code configs}.
   */
  @Override
  public void reloadRegions(Configs configs) {
    RTPRuntime.log(Level.FINE,
        "[RTP] reloadRegions(): shutting down " + ActiveSelection.get().permRegionLookup.size()
            + " permanent and " + ActiveSelection.get().tempRegions.size() + " temp region(s)");
    ProbeFirstGovernor.resetAll();
    for (Region r : ActiveSelection.get().permRegionLookup.values()) {
      RTPRuntime.log(Level.FINER, "[RTP] reloadRegions(): shutDown perm region '" + r.name + "'");
      r.shutDown();
    }
    ActiveSelection.get().permRegionLookup.clear();

    for (Region r : ActiveSelection.get().tempRegions.values()) {
      RTPRuntime.log(Level.FINER, "[RTP] reloadRegions(): shutDown temp region '" + r.name + "'");
      r.shutDown();
    }
    ActiveSelection.get().tempRegions.clear();

    @SuppressWarnings("unchecked")
    MultiConfigParser<RegionKeys> regions =
            (MultiConfigParser<RegionKeys>) configs.multiConfigParserMap.get(RegionKeys.class);
    if (regions == null) return;

    @SuppressWarnings("unchecked")
    ConfigParser<LoggingKeys> logging =
            (ConfigParser<LoggingKeys>) configs.configParserMap.get(LoggingKeys.class);

    boolean detailed_region_init = true;
    if (logging != null) {
      Object o = logging.getConfigValue(LoggingKeys.detailed_region_init, false);
      if (o instanceof Boolean) {
        detailed_region_init = (Boolean) o;
      } else {
        detailed_region_init = Boolean.parseBoolean(o.toString());
      }
    }

    // Lobby backends never serve teleports to local coords - they only
    // dispatch players to peer backends via cross-server /rtp. Registering the
    // default (or any) local region here would advertise a local destination via
    // BackendStateSampler.regions and let /rtp resolve a no-arg request to the
    // local region instead of routing it through the network. Skip the entire
    // per-region instantiation loop; permRegionLookup stays empty on a lobby.
    if (NetworkHooks.lobbyMode()) {
      RTPRuntime.log(Level.FINE,
          "[RTP] reloadRegions(): lobbyMode=true, skipping local region registration");
      return;
    }

    int regionCount = 0;
    int dormantCount = 0;
    for (ConfigParser<RegionKeys> regionConfig : regions.configParserFactory.map.values()) {
      RTPRuntime.log(Level.FINER, "[RTP] reloadRegions(): loading RegionSettings from '" + regionConfig.name + "'");
      RegionSettings settings = RegionConfigLoader.load(regionConfig);
      String name = settings.name();
      regionCount++;

      // Detect "dormant region" - the configured world wasn't loaded yet (common when
      // automatic world generators like Multiverse load their worlds after plugin enable).
      // The region is constructed with a null world; OnWorldLoadUnload rebinds the real
      // world via Region.rebindWorld once WorldLoadEvent fires.
      String configuredWorldName =
          RegionConfigLoader.detectFallbackConfiguredWorld(regionConfig, settings);
      boolean worldFallback = configuredWorldName != null;
      if (worldFallback) dormantCount++;
      RTPRuntime.log(Level.FINER,
          "[RTP] reloadRegions(): region '" + name + "' dormant=" + worldFallback
              + (worldFallback ? " configuredWorld='" + configuredWorldName + "'" : ""));
      if (worldFallback) {
        RTPRuntime.log(
            Level.INFO,
            "[RTP] Region '" + name + "' configured for world '" + configuredWorldName
                + "' which isn't loaded yet; region is dormant and will activate when the "
                + "world loads.");
      }

      Region region = new Region(name, settings, worldFallback, configuredWorldName);

      ActiveSelection.get().permRegionLookup.put(region.name, region);
      if (detailed_region_init) {
        RTPRuntime.log(
                Level.INFO,
                "&#00FFFF[RTP] [" + name + "] successfully created teleport region - " + region.name);
      }
      MiscTasks.async()
              .add(
                      new RTPRunnable(
                              () -> {
                                // Dormant regions (world not yet loaded) must not select a
                                // shape region - the shape's data file hasn't been loaded.
                                if (region.getWorld() == null) {
                                  RTPRuntime.log(Level.FINER,
                                      "[RTP] reloadRegions(): skipping shape.select for dormant region '"
                                          + region.name + "'");
                                  return;
                                }
                                if (region.getShape() == null) {
                                  RTPRuntime.log(Level.FINER,
                                      "[RTP] reloadRegions(): skipping shape.select for region '"
                                          + region.name + "' (null shape)");
                                  return;
                                }
                                RTPRuntime.log(Level.FINER,
                                    "[RTP] reloadRegions(): shape.select for region '" + region.name + "'");
                                region.getShape().select();
                              },
                              60));
    }
    RTPRuntime.log(Level.FINE,
        "[RTP] reloadRegions(): built " + regionCount + " region(s) (" + dormantCount + " dormant)");
  }
}
