package io.github.dailystruggle.rtp.common.selection;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class SelectionAPI {
  /**
   * pipe of selection tasks to be done. will be done in the order given, trying urgent tasks first
   * A failed selection will go to the back of the line.
   */
  public final ConcurrentLinkedQueue<Runnable> selectionPipeline = new ConcurrentLinkedQueue<>();

  public final ConcurrentLinkedQueue<Runnable> selectionPipelineUrgent =
      new ConcurrentLinkedQueue<>();
  public final ConcurrentHashMap<UUID, Region> tempRegions = new ConcurrentHashMap<>();
  public final ConcurrentHashMap<String, Region> permRegionLookup = new ConcurrentHashMap<>();
  private final Factory<Region> regionFactory = new Factory<>();
  public Factory<Shape<?>> shapeFactory;

  /**
   * getFromString a region by name
   *
   * @param regionName - name of region, case-insensitive
   * @return region object by that name, or null on bad lookup
   */
  @Nullable
  public Region getRegion(String regionName) {
    return permRegionLookup.get(regionName);
  }

  /**
   * getFromString a region by name
   *
   * @param regionName - name of region, case-insensitive
   * @return region object by that name, or null on bad lookup
   */
  @NotNull
  public Region getRegionExceptionally(String regionName) {
    Region res = permRegionLookup.get(regionName);
    if (res == null) throw new IllegalStateException("region:" + regionName + " does not exist");
    return res;
  }

  /**
   * getFromString a region by name
   *
   * @param regionName - name of region
   * @return region by that name, or null if none
   */
  @NotNull
  public Region getRegionOrDefault(String regionName) {
    return getRegionOrDefault(regionName, "DEFAULT");
  }

  /**
   * getFromString a region by name
   *
   * @param regionName - name of region
   * @param defaultName - name of default region to fall back on
   * @return region by that name, or null if none
   */
  @NotNull
  public Region getRegionOrDefault(String regionName, String defaultName) {
    if (permRegionLookup.containsKey(regionName)) return permRegionLookup.get(regionName);
    else {
      Region region = permRegionLookup.get(defaultName);
      if (region == null)
        throw new IllegalStateException(
            "neither '"
                + regionName
                + "' nor '"
                + defaultName
                + "' are known regions\n"
                + permRegionLookup);
      return Objects.requireNonNull(region);
    }
  }

  /**
   * add or update a region by name
   *
   * @param regionName - name of region
   * @param params - mapped parameters, based on parameters in default.yml
   */
  public void setRegion(String regionName, Map<String, String> params) {
    // todo: implement
    //        params.put( "region",regionName );
    //
    //        String worldName = ( String ) Configs.regions.getRegionSetting( regionName,"world",""
    // );
    //        if ( worldName == null || worldName.equals( "" ) || !Configs.worlds.checkWorldExists(
    // worldName) ) {
    //            return null;
    //        }
    //
    //        RegionParams randomSelectParams = new RegionParams( RTP.getInstance().getRTPWorld(
    // worldName ),params );
    //        if( permRegions.containsKey( randomSelectParams) ) {
    //            permRegions.getFromString( randomSelectParams ).shutdown();
    //        }
    //
    //        Configs.regions.setRegion( regionName,randomSelectParams );
    //        return permRegions.put( randomSelectParams,
    //                new Region( regionName,randomSelectParams.params) );
  }

  public Set<String> regionNames() {
    return permRegionLookup.keySet();
  }

  public void compute() {
    RTPServerAccessor serverAccessor = RTP.serverAccessor;

    int req = RTP.minRTPExecutions;

    while (!selectionPipelineUrgent.isEmpty() && (serverAccessor.overTime() < 0 || req > 0)) {
      if (!selectionPipelineUrgent.isEmpty()) selectionPipelineUrgent.poll().run();
      req--;
    }

    while (!selectionPipeline.isEmpty() && (serverAccessor.overTime() < 0 || req > 0)) {
      if (!selectionPipeline.isEmpty()) selectionPipeline.poll().run();
      req--;
    }
  }

  public Region tempRegion(Map<String, Object> regionParams, @Nullable String baseRegionName) {
    if (baseRegionName == null
        || baseRegionName.isEmpty()
        || !permRegionLookup.containsKey(baseRegionName)) baseRegionName = "default";
    Region baseRegion = Objects.requireNonNull(permRegionLookup.get(baseRegionName));

    // Create a dummy ConfigParser to use RegionConfigLoader
    // This is a bit of a hack but it's consistent with moving logic to RegionConfigLoader
    MultiConfigParser<RegionKeys> regionParsers =
        (MultiConfigParser<RegionKeys>) RTP.configs.multiConfigParserMap.get(RegionKeys.class);
    ConfigParser<RegionKeys> baseParser = regionParsers.getParser(baseRegionName);

    // We want to override values from regionParams
    // Since ConfigParser.getData() returns a copy or we shouldn't modify the base one,
    // we'll need a way to create a modified one.
    // For now, let's just manually update RegionSettings from the base region.

    RegionSettings baseSettings = baseRegion.getSettings();

    RTPWorld<?> world = (RTPWorld<?>) regionParams.getOrDefault(RegionKeys.world.name(), baseSettings.world());
    Shape<?> shape = (Shape<?>) regionParams.getOrDefault(RegionKeys.shape.name(), baseSettings.shape());
    VerticalAdjustor<?> vert = (VerticalAdjustor<?>) regionParams.getOrDefault(RegionKeys.vert.name(), baseSettings.vert());
    boolean worldBorderOverride = (boolean) regionParams.getOrDefault(RegionKeys.worldBorderOverride.name(), baseSettings.worldBorderOverride());
    boolean requirePermission = (boolean) regionParams.getOrDefault(RegionKeys.requirePermission.name(), baseSettings.requirePermission());
    long cacheCap = ((Number) regionParams.getOrDefault(RegionKeys.cacheCap.name(), baseSettings.cacheCap())).longValue();
    int activeChunkCap = ((Number) regionParams.getOrDefault(RegionKeys.activeChunkCap.name(), baseSettings.activeChunkCap())).intValue();
    double price = ((Number) regionParams.getOrDefault(RegionKeys.price.name(), baseSettings.price())).doubleValue();
    long spatialResolution = ((Number) regionParams.getOrDefault(RegionKeys.spatialResolution.name(), baseSettings.spatialResolution())).longValue();
    String override = String.valueOf(regionParams.getOrDefault(RegionKeys.override.name(), baseSettings.override()));

    RegionSettings newSettings = new RegionSettings(
            "temp",
            world,
            shape,
            vert,
            worldBorderOverride,
            requirePermission,
            cacheCap,
            activeChunkCap,
            price,
            spatialResolution,
            override,
            baseSettings.detailedRegionInit()
    );

    return new Region("temp", newSettings);
  }

  public Region getRegion(RTPPlayer player) {
    // get region from world name, check for overrides
    Set<String> worldsAttempted = new HashSet<>();
    String worldName = player.getLocation().world().name();
    MultiConfigParser<WorldKeys> worldParsers =
        (MultiConfigParser<WorldKeys>) RTP.configs.multiConfigParserMap.get(WorldKeys.class);
    ConfigParser<WorldKeys> worldParser = worldParsers.getParser(worldName);
    boolean requirePermission =
        Boolean.parseBoolean(
            worldParser.getConfigValue(WorldKeys.requirePermission, false).toString());

    while (requirePermission && !player.hasPermission("rtp.worlds." + worldName)) {
      if (worldsAttempted.contains(worldName))
        throw new IllegalStateException("infinite override loop detected at world - " + worldName);
      worldsAttempted.add(worldName);

      worldName = String.valueOf(worldParser.getConfigValue(WorldKeys.override, "default"));
      worldParser = worldParsers.getParser(worldName);
      requirePermission =
          Boolean.parseBoolean(
              worldParser.getConfigValue(WorldKeys.requirePermission, false).toString());
    }

    String regionName = String.valueOf(worldParser.getConfigValue(WorldKeys.region, "default"));
    MultiConfigParser<RegionKeys> regionParsers =
        (MultiConfigParser<RegionKeys>) RTP.configs.multiConfigParserMap.get(RegionKeys.class);
    ConfigParser<RegionKeys> regionParser = regionParsers.getParser(regionName);
    requirePermission =
        Boolean.parseBoolean(
            regionParser.getConfigValue(RegionKeys.requirePermission, false).toString());

    Set<String> regionsAttempted = new HashSet<>();
    while (requirePermission && !player.hasPermission("rtp.regions." + regionName)) {
      if (regionsAttempted.contains(regionName))
        throw new IllegalStateException(
            "infinite override loop detected at region - " + regionName);
      regionsAttempted.add(regionName);

      regionName = String.valueOf(regionParser.getConfigValue(RegionKeys.override, "default"));
      regionParser = regionParsers.getParser(regionName);
      requirePermission =
          Boolean.parseBoolean(
              regionParser.getConfigValue(RegionKeys.requirePermission, false).toString());
    }
    return getRegion(regionName);
  }

  public Region getRegion(RTPWorld world) {
    // get region from world name, check for overrides
    String worldName = world.name();
    MultiConfigParser<WorldKeys> worldParsers =
        (MultiConfigParser<WorldKeys>) RTP.configs.multiConfigParserMap.get(WorldKeys.class);
    ConfigParser<WorldKeys> worldParser = worldParsers.getParser(worldName);
    String regionName = String.valueOf(worldParser.getConfigValue(WorldKeys.region, "default"));
    return permRegionLookup.get(regionName);
  }
}
