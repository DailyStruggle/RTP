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
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tools.ParseString;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.tools.PerformanceTracker;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

public class InfoCmd extends BaseRTPCmdImpl {
  private static final Map<String, Function<RTPWorld, String>> worldDataLookup =
      new ConcurrentHashMap<>();
  private static final Map<String, Function<Region, String>> regionDataLookup =
      new ConcurrentHashMap<>();

  static {
    worldDataLookup.put("world", RTPWorld::name);
    worldDataLookup.put("region", world -> RTP.selectionAPI.getRegion(world).name);
    worldDataLookup.put(
        "requirePermission",
        world -> {
          MultiConfigParser<WorldKeys> worlds =
              (MultiConfigParser<WorldKeys>) RTP.configs.getParser(WorldKeys.class);
          ConfigParser<WorldKeys> parser = worlds.getParser(world.name());
          return parser.getConfigValue(WorldKeys.requirePermission, false).toString();
        });
    worldDataLookup.put(
        "override",
        world -> {
          MultiConfigParser<WorldKeys> worlds =
              (MultiConfigParser<WorldKeys>) RTP.configs.getParser(WorldKeys.class);
          ConfigParser<WorldKeys> parser = worlds.getParser(world.name());
          return parser.getConfigValue(WorldKeys.override, "[0]").toString();
        });

    regionDataLookup.put("region", region -> region.name);
    regionDataLookup.put("world", region -> region.getWorld().name());
    regionDataLookup.put("shape", region -> region.getShape().name);
    regionDataLookup.put("cacheCap", region -> region.getNumber(RegionKeys.cacheCap, 0).toString());
    regionDataLookup.put("cached", region -> String.valueOf(region.queueManager.getPublicQueueLength()));
    regionDataLookup.put("locationQueue", region -> String.valueOf(region.queueManager.locationQueue.size()));
    regionDataLookup.put("locAssChunks", region -> String.valueOf(region.chunkManager.locAssChunks.size()));
    regionDataLookup.put("inFlightCalculations", region -> String.valueOf(region.inFlightCalculations.get()));
    regionDataLookup.put(
        "worldBorderOverride",
        region -> {
          boolean wbo = false;
          EnumMap<RegionKeys, Object> data = region.getData();
          Object o = data.getOrDefault(RegionKeys.worldBorderOverride, false);
          if (o instanceof Boolean) wbo = (Boolean) o;
          else if (o instanceof String) {
            wbo = Boolean.parseBoolean((String) o);
            data.put(RegionKeys.worldBorderOverride, wbo);
          }
          return String.valueOf(wbo);
        });
    regionDataLookup.put(
        "requirePermission",
        region -> {
          boolean req = false;
          EnumMap<RegionKeys, Object> data = region.getData();
          Object o = data.getOrDefault(RegionKeys.requirePermission, false);
          if (o instanceof Boolean) req = (Boolean) o;
          else if (o instanceof String) {
            req = Boolean.parseBoolean((String) o);
            data.put(RegionKeys.requirePermission, req);
          }
          return String.valueOf(req);
        });
  }

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

  @Override
  public String description() {
    return "check the current state of the plugin";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    ConfigParser<MessagesKeys> lang =
        (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);

    if (parameterValues.isEmpty()) {
      String title = lang.getConfigValue(MessagesKeys.infoTitle, "").toString();
      String chunks = lang.getConfigValue(MessagesKeys.infoChunks, "").toString();
      String worldHeader = lang.getConfigValue(MessagesKeys.infoWorldHeader, "").toString();
      String worlds = lang.getConfigValue(MessagesKeys.infoWorld, "").toString();
      String regionHeader = lang.getConfigValue(MessagesKeys.infoRegionHeader, "").toString();
      String regions = lang.getConfigValue(MessagesKeys.infoRegion, "").toString();

      RTP.serverAccessor.sendMessage(callerId, title, "INFO");
      RTP.serverAccessor.sendMessage(callerId, chunks, "INFO");
      RTP.serverAccessor.sendMessage(callerId, worldHeader, "INFO");
      for (RTPWorld world : RTP.serverAccessor.getRTPWorlds()) {
        String msg = worlds.replaceAll("\\[world]", world.name());
        RTP.serverAccessor.sendMessageAndSuggest(callerId, msg, "rtp info world:" + world.name());
      }
      RTP.serverAccessor.sendMessage(callerId, regionHeader, "INFO");
      RTP.selectionAPI
          .permRegionLookup
          .values()
          .forEach(
              region -> {
                String msg = regions.replaceAll("\\[region]", region.name);
                msg = msg.replaceAll("\\[locationQueue]", String.valueOf(region.queueManager.locationQueue.size()));
                msg = msg.replaceAll("\\[locAssChunks]", String.valueOf(region.chunkManager.locAssChunks.size()));
                msg = msg.replaceAll("\\[inFlightCalculations]", String.valueOf(region.inFlightCalculations.get()));
                RTP.serverAccessor.sendMessageAndSuggest(
                    callerId, msg, "rtp info region:" + region.name);
              });


        RTP rtp = RTP.getInstance();
        long activeTickets = ChunkSet.ACTIVE_CHUNK_TICKETS.get();
        long totalLoads = ChunkSet.TOTAL_CHUNK_LOADS.get();

        long totalExpectedTickets = 0;
        long totalActiveChunkCap = 0;

        List<Region> allRegions = new ArrayList<>(RTP.selectionAPI.permRegionLookup.values());
        allRegions.addAll(RTP.selectionAPI.tempRegions.values());

        for (Region region : allRegions) {
            totalActiveChunkCap += region.getSettings().activeChunkCap();
            for (ChunkSet chunkSet : region.chunkManager.locAssChunks.values()) {
                if (chunkSet.keep()) {
                    totalExpectedTickets += chunkSet.chunks.size();
                }
            }
        }

        long discrepancy = activeTickets - totalExpectedTickets;
        double leakRate = (totalLoads > 0) ? ((double) Math.max(0, discrepancy) / totalLoads) * 100.0 : 0.0;

      RTP.serverAccessor.sendMessage(callerId, "&7Global Active Chunk Tickets: &f" + activeTickets, "INFO");
      RTP.serverAccessor.sendMessage(callerId, "&7Active Teleport State Machines: &f" + rtp.latestTeleportData.size(), "INFO");
      RTP.serverAccessor.sendMessage(callerId, "&7Current Plugin MSPT: &f" + String.format("%.4f", PerformanceTracker.pluginMSPT), "INFO");
      RTP.serverAccessor.sendMessage(callerId, "&7Lifetime Chunks Loaded: &f" + totalLoads, "INFO");
      RTP.serverAccessor.sendMessage(callerId, "&7Current Chunk Leak Rate: &f" + String.format("%.4f%%", leakRate), "INFO");

      RTP.serverAccessor.sendMessage(callerId, "&7--- Diagnostic Disclaimer ---", "INFO");
      RTP.serverAccessor.sendMessage(callerId, "&7Diagnostic Note: If the server's total orphaned chunk count exceeds this plugin's lifetime loads ([" + totalLoads + "]), this plugin is not the sole source of the memory leak.", "INFO");
    }

    Set<Character> front = new HashSet<>(Arrays.asList('[', '%'));
    Set<Character> back = new HashSet<>(Arrays.asList(']', '%'));

    List<String> worldNames = parameterValues.get("world");
    if (worldNames != null) {
      Object worldInfoObj = lang.getConfigValue(MessagesKeys.worldInfo, "");
      if (!(worldInfoObj instanceof List)) return true;
      List<String> worldInfo =
          ((List<?>) worldInfoObj).stream().map(String::valueOf).collect(Collectors.toList());
      for (String worldName : worldNames) {
        RTPWorld rtpWorld = RTP.serverAccessor.getRTPWorld(worldName);
        if (rtpWorld == null || rtpWorld.isInactive()) continue;
        ArrayList<String> worldInfoCopy = new ArrayList<>(worldInfo);
        List<String> strings =
            worldInfoCopy.stream()
                .map(
                    s -> {
                      Set<String> keywords =
                          ParseString.keywords(s, worldDataLookup.keySet(), front, back);
                      Map<String, String> placeholders = new HashMap<>();
                      worldDataLookup.forEach(
                          (s1, rtpWorldStringFunction) -> {
                            if (!keywords.contains(s1)) return;
                            placeholders.put(s1, rtpWorldStringFunction.apply(rtpWorld));
                          });
                      // replace all placeholders with their respective string function results
                      for (Map.Entry<String, String> e : placeholders.entrySet()) {
                        s = s.replaceAll("\\[" + e.getKey() + "]", e.getValue());
                      }
                      for (Map.Entry<String, String> e : placeholders.entrySet()) {
                        s = s.replaceAll("%" + e.getKey() + "%", e.getValue());
                      }
                      return s;
                    })
                .collect(Collectors.toList());
        strings.forEach(s -> RTP.serverAccessor.sendMessage(callerId, s, "INFO"));
      }
    }

    List<String> regionNames = parameterValues.get("region");
    if (regionNames != null) {
      Object regionInfoObj = lang.getConfigValue(MessagesKeys.regionInfo, "");
      if (!(regionInfoObj instanceof List)) return true;
      List<String> regionInfo =
          ((List<?>) regionInfoObj).stream().map(String::valueOf).collect(Collectors.toList());
      for (String regionName : regionNames) {
        Region region = RTP.selectionAPI.getRegion(regionName);
        if (region == null) continue;
        ArrayList<String> regionInfoCopy = new ArrayList<>(regionInfo);
        List<String> strings =
            regionInfoCopy.stream()
                .map(
                    s -> {
                      Set<String> keywords =
                          ParseString.keywords(s, regionDataLookup.keySet(), front, back);
                      Map<String, String> placeholders = new HashMap<>();
                      regionDataLookup.forEach(
                          (s1, rtpRegionStringFunction) -> {
                            if (!keywords.contains(s1)) return;
                            placeholders.put(s1, rtpRegionStringFunction.apply(region));
                          });
                      for (Map.Entry<String, String> e : placeholders.entrySet()) {
                        s = s.replaceAll("\\[" + e.getKey() + "]", e.getValue());
                      }
                      for (Map.Entry<String, String> e : placeholders.entrySet()) {
                        s = s.replaceAll("%" + e.getKey() + "%", e.getValue());
                      }
                      return s;
                    })
                .collect(Collectors.toList());
        strings.forEach(s -> RTP.serverAccessor.sendMessage(callerId, s, "INFO"));
      }
    }

    RTPCommandSender sender = RTP.serverAccessor.getSender(callerId);
    if (sender.hasPermission("rtp.admin") || sender.hasPermission("rtp.support")) {
      RTP.serverAccessor.sendMessage(callerId, "&7--- DRM Information ---", "INFO");
      RTP.serverAccessor.sendMessage(callerId, "&7Downloader ID: &f" + RTPAPI.DOWNLOADER_ID, "INFO");
      RTP.serverAccessor.sendMessage(callerId, "&7Download Nonce: &f" + RTPAPI.DOWNLOAD_NONCE, "INFO");
    }

    return true;
  }
}
