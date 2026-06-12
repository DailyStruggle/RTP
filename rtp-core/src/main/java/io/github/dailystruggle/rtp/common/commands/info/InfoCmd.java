package io.github.dailystruggle.rtp.common.commands.info;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
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

  /**
   * Routes a raw line to {@link RTP#messageTap} if one is installed for this
   * thread (book-mirroring path; see PROPOSAL-info-as-book.md section 4.6),
   * or otherwise to {@link RTP#serverAccessor} for the normal chat path. When
   * the tap is installed the line is run through
   * {@link PlaceholderProvider#fillPlaceholders(String, UUID)} first so it
   * arrives at the sink in the same fully-substituted form the chat path
   * would have produced.
   */
  private static void emit(UUID callerId, String line) {
    java.util.function.Consumer<String> tap = RTP.messageTap.get();
    if (tap != null) {
      tap.accept(PlaceholderProvider.fillPlaceholders(line, callerId));
      return;
    }
    RTP.serverAccessor.sendMessage(callerId, line);
  }

  /**
   * {@link MessagesKeys}-keyed variant of {@link #emit(UUID, String)}. When the
   * tap is active the key is resolved from the language parser, substituted,
   * and forwarded; otherwise the call is delegated to the normal accessor.
   */
  private static void emit(UUID callerId, MessagesKeys key) {
    java.util.function.Consumer<String> tap = RTP.messageTap.get();
    if (tap != null) {
      ConfigParser<MessagesKeys> lang =
          (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
      String raw = lang == null ? "" : lang.getConfigValue(key, "").toString();
      if (!raw.isEmpty()) tap.accept(PlaceholderProvider.fillPlaceholders(raw, callerId));
      return;
    }
    RTP.serverAccessor.sendMessage(callerId, key);
  }

  /**
   * {@code sendMessageAndSuggest} variant of {@link #emit(UUID, String)}. The
   * book path has no chat-click affordance to attach a suggestion to, so the
   * suggestion is discarded when the tap is active (the book renderer mints
   * its own click events independently). Falls through to the normal accessor
   * otherwise.
   */
  private static void emitAndSuggest(UUID callerId, String line, String suggestion) {
    java.util.function.Consumer<String> tap = RTP.messageTap.get();
    if (tap != null) {
      tap.accept(PlaceholderProvider.fillPlaceholders(line, callerId));
      return;
    }
    RTP.serverAccessor.sendMessageAndSuggest(callerId, line, suggestion);
  }

  private void sendWorldInfo(UUID callerId, RTPWorld world, ConfigParser<MessagesKeys> lang) {
    Object worldInfoObj = lang.getConfigValue(MessagesKeys.worldInfo, "");
    if (!(worldInfoObj instanceof List)) return;
    List<String> worldInfo =
        ((List<?>) worldInfoObj).stream().map(String::valueOf).collect(Collectors.toList());

    try {
      RTP.worldContext.set(world);
      worldInfo.forEach(s -> emit(callerId, s));
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
      regionInfo.forEach(s -> emit(callerId, s));
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
        emit(callerId, MessagesKeys.infoTitle);
        emit(callerId, MessagesKeys.infoConsoleWorldHeader);
        for (RTPWorld world : RTP.serverAccessor.getRTPWorlds()) {
          sendWorldInfo(callerId, world, lang);
        }

        emit(callerId, "");
        emit(callerId, MessagesKeys.infoConsoleRegionHeader);
        RTP.selectionAPI.permRegionLookup.values().forEach(region -> {
          sendRegionInfo(callerId, region, lang);
        });
      } else {
        emit(callerId, MessagesKeys.infoTitle);
        emit(callerId, MessagesKeys.infoWorldHeader);
        String worlds = lang.getConfigValue(MessagesKeys.infoWorld, "").toString();
        for (RTPWorld world : RTP.serverAccessor.getRTPWorlds()) {
          try {
            RTP.worldContext.set(world);
            emitAndSuggest(
                callerId,
                worlds,
                "/rtp info world" + CommandsAPI.parameterDelimiter + world.name());
          } finally {
            RTP.worldContext.remove();
          }
        }

        emit(callerId, MessagesKeys.infoRegionHeader);
        String regions = lang.getConfigValue(MessagesKeys.infoRegion, "").toString();
        RTP.selectionAPI
                .permRegionLookup
                .values()
                .forEach(
                        region -> {
                          try {
                            RTP.regionContext.set(region);
                            emitAndSuggest(
                                    callerId,
                                    regions,
                                    "/rtp info region"
                                            + CommandsAPI.parameterDelimiter
                                            + region.name);
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
      String infoPipelinePercentiles = lang.getConfigValue(MessagesKeys.infoPipelinePercentiles, "").toString();
      String infoSlowPipeline = lang.getConfigValue(MessagesKeys.infoSlowPipeline, "").toString();
      String infoQueueGrowth = lang.getConfigValue(MessagesKeys.infoQueueGrowth, "").toString();
      String infoHeap = lang.getConfigValue(MessagesKeys.infoHeap, "").toString();
      String infoHealthPipelineHeader = lang.getConfigValue(MessagesKeys.infoHealthPipelineHeader, "").toString();
      String infoTps = lang.getConfigValue(MessagesKeys.infoTps, "").toString();
      String infoMSPTLive = lang.getConfigValue(MessagesKeys.infoMSPTLive, "").toString();
      String infoSoftCap = lang.getConfigValue(MessagesKeys.infoSoftCap, "").toString();
      String infoDatabaseLatencyMs = lang.getConfigValue(MessagesKeys.infoDatabaseLatencyMs, "").toString();
      // ADR-052: generation success/failure rate + per-cause rejection breakdown.
      String infoFailureRate = lang.getConfigValue(MessagesKeys.infoFailureRate, "").toString();
      String infoFailureBreakdown = lang.getConfigValue(MessagesKeys.infoFailureBreakdown, "").toString();
      String infoTopRejectionCause = lang.getConfigValue(MessagesKeys.infoTopRejectionCause, "").toString();
      String infoDisclaimerHeader = lang.getConfigValue(MessagesKeys.infoDisclaimerHeader, "").toString();
      String infoDisclaimer = lang.getConfigValue(MessagesKeys.infoDisclaimer, "").toString();

      // Live-metric rows render the documented "n/a" sentinel when no metrics
      // binding has sampled yet. Suppress those rows entirely (rather than print a
      // wall of "n/a") so /rtp info stays compact on servers without a live binding.
      boolean hasLiveTps = PlaceholderProvider.hasLiveTps();
      boolean hasLiveMspt = PlaceholderProvider.hasLiveMspt();
      boolean hasPipelineSamples = PlaceholderProvider.pipelineSampleCount() > 0;
      boolean hasDatabaseLatency = PlaceholderProvider.hasDatabaseLatency();

      if (!infoTickets.isEmpty()) emit(callerId, infoTickets);
      if (!infoTeleports.isEmpty()) emit(callerId, infoTeleports);
      if (!infoMSPT.isEmpty() && hasLiveMspt) emit(callerId, infoMSPT);
      if (!infoTotalLoads.isEmpty()) emit(callerId, infoTotalLoads);
      if (!infoLoadsByOrigin.isEmpty()) emit(callerId, infoLoadsByOrigin);
      if (!infoLeakRate.isEmpty()) emit(callerId, infoLeakRate);
      // Health — pipeline group (METRICS_PLAN.md > /rtp info Surface). Header line
      // is rendered ahead of the metrics group so operators can visually anchor it.
      if (!infoHealthPipelineHeader.isEmpty())
        emit(callerId, infoHealthPipelineHeader);
      if (!infoTps.isEmpty() && hasLiveTps) emit(callerId, infoTps);
      if (!infoMSPTLive.isEmpty() && hasLiveMspt) emit(callerId, infoMSPTLive);
      if (!infoSoftCap.isEmpty()) emit(callerId, infoSoftCap);
      if (!infoQueueDepth.isEmpty()) emit(callerId, infoQueueDepth);
      if (!infoPendingTeleports.isEmpty()) emit(callerId, infoPendingTeleports);
      if (!infoAvgPipelineMs.isEmpty() && hasPipelineSamples) emit(callerId, infoAvgPipelineMs);
      // ADR-053: pipeline-latency percentiles + slow-teleport / queue-growth audit counters.
      if (!infoPipelinePercentiles.isEmpty() && hasPipelineSamples) emit(callerId, infoPipelinePercentiles);
      if (!infoSlowPipeline.isEmpty()) emit(callerId, infoSlowPipeline);
      if (!infoQueueGrowth.isEmpty()) emit(callerId, infoQueueGrowth);
      if (!infoHeap.isEmpty()) emit(callerId, infoHeap);
      if (!infoDatabaseLatencyMs.isEmpty() && hasDatabaseLatency) emit(callerId, infoDatabaseLatencyMs);
      // ADR-052: generation outcome metrics. Empty templates skip silently so locales
      // without the new keys keep working unchanged.
      if (!infoFailureRate.isEmpty()) emit(callerId, infoFailureRate);
      if (!infoTopRejectionCause.isEmpty()) emit(callerId, infoTopRejectionCause);
      if (!infoFailureBreakdown.isEmpty()) emit(callerId, infoFailureBreakdown);

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
          emit(callerId, infoFoliaRegionsHeader);
        if (!infoFoliaRegion.isEmpty()) {
          for (io.github.dailystruggle.metrics.api.FoliaRegionSample sample : foliaRegions) {
            String row = infoFoliaRegion
                .replace("[regionId]", String.valueOf(sample.regionId))
                .replace("[tps1m]", formatDouble(sample.tps1m))
                .replace("[mspt]", formatDouble(sample.mspt))
                .replace("[playerCount]", Integer.toString(sample.playerCount))
                .replace("[queueDepth]", Integer.toString(sample.queueDepth))
                .replace("[tickBudgetUtilisation]", formatDouble(sample.tickBudgetUtilisation()));
            emit(callerId, row);
          }
        }
      }

      if (!infoDisclaimerHeader.isEmpty()) emit(callerId, infoDisclaimerHeader);
      if (!infoDisclaimer.isEmpty()) emit(callerId, infoDisclaimer);
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
      // DRM block: deliberately hardcoded (never sourced from the player-editable
      // messages.yml) and condensed to a single line to keep /rtp info compact.
      StringBuilder drm = new StringBuilder("&7DRM: &f")
          .append(DownloadInfo.source())
          .append(" &7id=&f").append(DownloadInfo.userId())
          .append(" &7nonce=&f").append(DownloadInfo.nonce());
      if (DownloadInfo.source() == DownloadInfo.Source.BUILTBYBIT) {
        drm.append(" &7bbb=&f").append(DownloadInfo.resourceId())
           .append("@").append(DownloadInfo.timestamp());
      }
      emit(callerId, drm.toString());
    }

    return true;
  }
}
