package io.github.dailystruggle.rtp.common.commands.info;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter;
import io.github.dailystruggle.rtp.common.commands.parameters.WorldParameter;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tools.PlaceholderProvider;
import io.github.dailystruggle.rtp.api.DownloadInfo;
import io.github.dailystruggle.rtp.api.RTPAPI;
import java.util.*;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

public class InfoCmd extends BaseRTPCmdImpl {
  public InfoCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter(
        "world",
        new WorldParameter("rtp.info", "check on a world's configuration", (uuid, s) -> true));
    addParameter(
        "region",
        new RegionParameter(
            "rtp.info", "check on a region's state and configuration", (uuid, s) -> true));
  }

  @Override
  public String name() {
    return "info";
  }

  @Override
  public String permission() {
    return "rtp.info";
  }

  /**
   * Formats a double for the per-region table — two decimal places when finite,
   * literal {@code NaN} when not sampled yet. Keeps the row layout stable across
   * locales without dragging a {@code Locale}-sensitive formatter into core.
   */
  private static String formatDouble(double v) {
    if (Double.isNaN(v) || Double.isInfinite(v)) return "NaN";
    return String.format(java.util.Locale.ROOT, "%.2f", v);
  }

  private void sendWorldInfo(UUID callerId, RTPWorld world, ConfigParser<MessagesKeys> lang) {
    Object worldInfoObj = lang.getConfigValue(MessagesKeys.worldInfo, "");
    if (!(worldInfoObj instanceof List)) return;
    List<String> worldInfo =
        ((List<?>) worldInfoObj).stream().map(String::valueOf).collect(Collectors.toList());

    try {
      RTP.worldContext.set(world);
      worldInfo.forEach(s -> RTP.serverAccessor.sendMessage(callerId, s));
    } finally {
      RTP.worldContext.remove();
    }
  }

  private void sendRegionInfo(UUID callerId, Region region, ConfigParser<MessagesKeys> lang) {
    Object regionInfoObj = lang.getConfigValue(MessagesKeys.regionInfo, "");
    if (!(regionInfoObj instanceof List)) return;
    List<String> regionInfo =
        ((List<?>) regionInfoObj).stream().map(String::valueOf).collect(Collectors.toList());

    try {
      RTP.regionContext.set(region);
      regionInfo.forEach(s -> RTP.serverAccessor.sendMessage(callerId, s));
    } finally {
      RTP.regionContext.remove();
    }
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    // Capture exactly one MetricsSnapshot for the entire /rtp info invocation so
    // every metrics-backed placeholder ([tps1m], [mspt], [queueDepth], …) reads
    // from the same atomic view. See PlaceholderProvider.withSnapshot and
    // METRICS_PLAN.md > /rtp info Surface (B11 cleanup).
    final boolean[] result = {true};
    PlaceholderProvider.withSnapshot(() -> result[0] = onCommandInner(callerId, parameterValues));
    return result[0];
  }

  private boolean onCommandInner(
      UUID callerId, Map<String, List<String>> parameterValues) {
    ConfigParser<MessagesKeys> lang =
        (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

    if (parameterValues.isEmpty()) {
      if (callerId.equals(RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, MessagesKeys.infoTitle);
        RTP.serverAccessor.sendMessage(callerId, MessagesKeys.infoConsoleWorldHeader);
        for (RTPWorld world : RTP.serverAccessor.getRTPWorlds()) {
          sendWorldInfo(callerId, world, lang);
        }

        RTP.serverAccessor.sendMessage(callerId, "");
        RTP.serverAccessor.sendMessage(callerId, MessagesKeys.infoConsoleRegionHeader);
        RTP.selectionAPI.permRegionLookup.values().forEach(region -> {
          sendRegionInfo(callerId, region, lang);
        });
      } else {
        RTP.serverAccessor.sendMessage(callerId, MessagesKeys.infoTitle);
        RTP.serverAccessor.sendMessage(callerId, MessagesKeys.infoWorldHeader);
        String worlds = lang.getConfigValue(MessagesKeys.infoWorld, "").toString();
        for (RTPWorld world : RTP.serverAccessor.getRTPWorlds()) {
          try {
            RTP.worldContext.set(world);
            RTP.serverAccessor.sendMessageAndSuggest(callerId, worlds, "rtp info world:" + world.name());
          } finally {
            RTP.worldContext.remove();
          }
        }

        RTP.serverAccessor.sendMessage(callerId, MessagesKeys.infoRegionHeader);
        String regions = lang.getConfigValue(MessagesKeys.infoRegion, "").toString();
        RTP.selectionAPI
                .permRegionLookup
                .values()
                .forEach(
                        region -> {
                          try {
                            RTP.regionContext.set(region);
                            RTP.serverAccessor.sendMessageAndSuggest(
                                    callerId, regions, "rtp info region:" + region.name);
                          } finally {
                            RTP.regionContext.remove();
                          }
                        });
      }

      String infoTickets = lang.getConfigValue(MessagesKeys.infoTickets, "").toString();
      String infoTeleports = lang.getConfigValue(MessagesKeys.infoTeleports, "").toString();
      String infoMSPT = lang.getConfigValue(MessagesKeys.infoMSPT, "").toString();
      String infoTotalLoads = lang.getConfigValue(MessagesKeys.infoTotalLoads, "").toString();
      String infoLoadsByOrigin = lang.getConfigValue(MessagesKeys.infoLoadsByOrigin, "").toString();
      String infoLeakRate = lang.getConfigValue(MessagesKeys.infoLeakRate, "").toString();
      // Metrics SPI health block — surfaces the same MetricsSnapshot data that the
      // bStats integration and (planned) multi-server publisher consume. Per
      // METRICS_PLAN.md > /rtp info Surface, these are operator-facing live signals
      // for triage. Empty templates skip silently so existing locale files without
      // the new keys keep working unchanged.
      String infoQueueDepth = lang.getConfigValue(MessagesKeys.infoQueueDepth, "").toString();
      String infoPendingTeleports = lang.getConfigValue(MessagesKeys.infoPendingTeleports, "").toString();
      String infoAvgPipelineMs = lang.getConfigValue(MessagesKeys.infoAvgPipelineMs, "").toString();
      String infoHeap = lang.getConfigValue(MessagesKeys.infoHeap, "").toString();
      String infoHealthPipelineHeader = lang.getConfigValue(MessagesKeys.infoHealthPipelineHeader, "").toString();
      String infoTps = lang.getConfigValue(MessagesKeys.infoTps, "").toString();
      String infoMSPTLive = lang.getConfigValue(MessagesKeys.infoMSPTLive, "").toString();
      String infoSoftCap = lang.getConfigValue(MessagesKeys.infoSoftCap, "").toString();
      String infoDatabaseLatencyMs = lang.getConfigValue(MessagesKeys.infoDatabaseLatencyMs, "").toString();
      String infoDisclaimerHeader = lang.getConfigValue(MessagesKeys.infoDisclaimerHeader, "").toString();
      String infoDisclaimer = lang.getConfigValue(MessagesKeys.infoDisclaimer, "").toString();

      if (!infoTickets.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoTickets);
      if (!infoTeleports.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoTeleports);
      if (!infoMSPT.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoMSPT);
      if (!infoTotalLoads.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoTotalLoads);
      if (!infoLoadsByOrigin.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoLoadsByOrigin);
      if (!infoLeakRate.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoLeakRate);
      // Health — pipeline group (METRICS_PLAN.md > /rtp info Surface). Header line
      // is rendered ahead of the metrics group so operators can visually anchor it.
      if (!infoHealthPipelineHeader.isEmpty())
        RTP.serverAccessor.sendMessage(callerId, infoHealthPipelineHeader);
      if (!infoTps.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoTps);
      if (!infoMSPTLive.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoMSPTLive);
      if (!infoSoftCap.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoSoftCap);
      if (!infoQueueDepth.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoQueueDepth);
      if (!infoPendingTeleports.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoPendingTeleports);
      if (!infoAvgPipelineMs.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoAvgPipelineMs);
      if (!infoHeap.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoHeap);
      if (!infoDatabaseLatencyMs.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoDatabaseLatencyMs);

      // Section C / METRICS_PLAN.md > Folia Aggregation — render the per-region
      // table when the active MetricsBinding publishes per-region detail (Folia).
      // foliaRegions() is structurally empty on Paper / Bukkit / Fabric (single
      // region runtimes), so this whole block is a no-op there. Empty templates
      // skip silently for locales that haven't merged the new keys.
      List<io.github.dailystruggle.metrics.api.FoliaRegionSample> foliaRegions =
          io.github.dailystruggle.rtp.common.tools.PlaceholderProvider.currentSnapshot().foliaRegions;
      if (!foliaRegions.isEmpty()) {
        String infoFoliaRegionsHeader = lang.getConfigValue(MessagesKeys.infoFoliaRegionsHeader, "").toString();
        String infoFoliaRegion = lang.getConfigValue(MessagesKeys.infoFoliaRegion, "").toString();
        if (!infoFoliaRegionsHeader.isEmpty())
          RTP.serverAccessor.sendMessage(callerId, infoFoliaRegionsHeader);
        if (!infoFoliaRegion.isEmpty()) {
          for (io.github.dailystruggle.metrics.api.FoliaRegionSample sample : foliaRegions) {
            String row = infoFoliaRegion
                .replace("[regionId]", String.valueOf(sample.regionId))
                .replace("[tps1m]", formatDouble(sample.tps1m))
                .replace("[mspt]", formatDouble(sample.mspt))
                .replace("[playerCount]", Integer.toString(sample.playerCount))
                .replace("[queueDepth]", Integer.toString(sample.queueDepth))
                .replace("[tickBudgetUtilisation]", formatDouble(sample.tickBudgetUtilisation()));
            RTP.serverAccessor.sendMessage(callerId, row);
          }
        }
      }

      if (!infoDisclaimerHeader.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoDisclaimerHeader);
      if (!infoDisclaimer.isEmpty()) RTP.serverAccessor.sendMessage(callerId, infoDisclaimer);
    }

    List<String> worldNames = parameterValues.get("world");
    if (worldNames != null) {
      for (String worldName : worldNames) {
        RTPWorld rtpWorld = RTP.serverAccessor.getRTPWorld(worldName);
        if (rtpWorld == null || rtpWorld.isInactive()) continue;
        sendWorldInfo(callerId, rtpWorld, lang);
      }
    }

    List<String> regionNames = parameterValues.get("region");
    if (regionNames != null) {
      for (String regionName : regionNames) {
        Region region = RTP.selectionAPI.getRegion(regionName);
        if (region == null) continue;
        sendRegionInfo(callerId, region, lang);
      }
    }

    RTPCommandSender sender = RTP.serverAccessor.getSender(callerId);
    if (sender.hasPermission("rtp.admin") || sender.hasPermission("rtp.support")) {
      RTP.serverAccessor.sendMessage(callerId, "&7--- DRM Information ---");
      RTP.serverAccessor.sendMessage(callerId, "&7Source: &f" + DownloadInfo.source());
      RTP.serverAccessor.sendMessage(callerId, "&7Downloader ID: &f" + DownloadInfo.userId());
      RTP.serverAccessor.sendMessage(callerId, "&7Download Nonce: &f" + DownloadInfo.nonce());
      if (DownloadInfo.source() == DownloadInfo.Source.BUILTBYBIT) {
        RTP.serverAccessor.sendMessage(callerId, "&7BBB Resource: &f" + DownloadInfo.resourceId());
        RTP.serverAccessor.sendMessage(callerId, "&7BBB Timestamp: &f" + DownloadInfo.timestamp());
      }
    }

    return true;
  }
}
