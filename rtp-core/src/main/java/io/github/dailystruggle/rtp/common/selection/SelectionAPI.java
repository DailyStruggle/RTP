package io.github.dailystruggle.rtp.common.selection;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.GenericVerticalAdjustorKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The main API for managing and selecting teleportation regions. This class handles
 * region lookups, creation of temporary regions, and processing of the location
 * selection pipeline.
 */
public class SelectionAPI {
  /** A queue for standard-priority location selection tasks. */
  public final ConcurrentLinkedQueue<Runnable> selectionPipeline = new ConcurrentLinkedQueue<>();

  /** A queue for high-priority (urgent) location selection tasks. */
  public final ConcurrentLinkedQueue<Runnable> selectionPipelineUrgent =
      new ConcurrentLinkedQueue<>();

  /**
   * Cache for temporary regions keyed by UUID (sender UUID for parameter overrides,
   * target world UUID for world overrides).
   */
  public final ConcurrentHashMap<UUID, Region> tempRegions = new ConcurrentHashMap<>();

  /** A lookup map for all permanently configured regions, keyed by name. */
  public final ConcurrentHashMap<String, Region> permRegionLookup = new ConcurrentHashMap<>();

  /** A factory for creating {@link Region} instances. */
  private final Factory<Region> regionFactory = new Factory<>();

  /** A factory for creating {@link Shape} instances. */
  public Factory<Shape<?>> shapeFactory;

  /**
   * Retrieves a region by its name.
   *
   * @param regionName The name of the region (case-insensitive).
   * @return The {@link Region} object, or null if not found.
   */
  @Nullable
  public Region getRegion(String regionName) {
    return permRegionLookup.get(regionName);
  }

  /**
   * Retrieves a region by its name, throwing an exception if not found.
   *
   * @param regionName The name of the region (case-insensitive).
   * @return The {@link Region} object.
   * @throws IllegalStateException if the region does not exist.
   */
  @NotNull
  public Region getRegionExceptionally(String regionName) {
    Region res = permRegionLookup.get(regionName);
    if (res == null) throw new IllegalStateException("region:" + regionName + " does not exist");
    return res;
  }

  /**
   * Retrieves a region by name, falling back to the "default" region if not found.
   *
   * @param regionName The name of the region.
   * @return The requested {@link Region} or the default region.
   */
  @NotNull
  public Region getRegionOrDefault(String regionName) {
    return getRegionOrDefault(regionName, "DEFAULT");
  }

  /**
   * Retrieves a region by name, falling back to a specified default region if not found.
   *
   * @param regionName  The name of the region.
   * @param defaultName The name of the fallback region.
   * @return The requested {@link Region} or the specified default region.
   * @throws IllegalStateException if neither the requested nor the default region exist.
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
   * Creates or updates a region with the given parameters.
   * (Note: This method is not yet fully implemented).
   *
   * @param regionName The name of the region.
   * @param params     A map of parameters to configure the region.
   */
  public void setRegion(String regionName, Map<String, String> params) {
    // TODO: Implement region creation/updating logic.
  }

  /**
   * Returns a set of all configured region names.
   *
   * @return A {@link Set} of region names.
   */
  public Set<String> regionNames() {
    return permRegionLookup.keySet();
  }

  /**
   * Opportunistic on-load biome harvest for resident chunks when enabled by config.
   * Records center-column biome to MemoryShape without extra chunk I/O (S-005 safe).
   *
   * @param world the world the chunk belongs to
   * @param cx    the chunk x coordinate
   * @param cz    the chunk z coordinate
   */
  public void observeChunkLoad(@Nullable RTPWorld<?> world, int cx, int cz) {
    if (world == null) return;
    if (permRegionLookup.isEmpty()) return;

    @SuppressWarnings("unchecked")
    ConfigParser<PerformanceKeys> perf =
        (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
    if (perf == null) return;
    if (!Boolean.parseBoolean(
        perf.getConfigValue(PerformanceKeys.checkOnChunkLoads, false).toString())) {
      return;
    }

    UUID worldId = world.id();
    if (worldId == null) return;

    // Representative column: chunk center, sampled mid-height. Biome data is
    // stored per-(x,z) run, so the exact Y only matters for 3D-biome worlds;
    // the world vertical midpoint is a cheap, stable choice.
    int bx = (cx << 4) + 8;
    int bz = (cz << 4) + 8;
    int y = (world.getMinHeight() + world.getMaxHeight()) / 2;

    for (Region region : permRegionLookup.values()) {
      RTPWorld<?> regionWorld = region.getWorld();
      if (regionWorld == null || !worldId.equals(regionWorld.id())) continue;

      Shape<?> shape = region.getShape();
      if (!(shape instanceof MemoryShape<?> memoryShape)) continue;
      if (!memoryShape.contains(bx, bz)) continue;

      long location = memoryShape.xzToLocation(bx, bz);
      if (location < 0L) continue;

      String biome;
      try {
        biome = world.getBiome(bx, y, bz);
      } catch (Throwable t) {
        // Biome reads never gate anything; a failure here is a quiet no-op.
        continue;
      }
      if (biome == null || biome.isEmpty()) continue;

      memoryShape.addBiomeLocation(location, memoryShape.spatialResolution, biome);
    }
  }

  /**
   * Processes tasks from the selection pipelines, prioritizing urgent tasks.
   * This method is called on each server tick to drive the asynchronous location
   * generation process.
   */
  public void compute() {
    RTPServerAccessor serverAccessor = RTP.serverAccessor;

    int req = RTP.minRTPExecutions;

    // Diagram 02: drain the urgent (private/biome) queue first, then the
    // standard pipeline. FINER traces every individual task; FINE summarises
    // the pulse's overall throughput.
    int urgentRan = 0;
    int standardRan = 0;

    while (serverAccessor.overTime() < 0 || req > 0) {
      Runnable task = selectionPipelineUrgent.poll();
      if (task == null) break;
      RTP.log(java.util.logging.Level.FINER,
          "[SelectionAPI] running urgent pipeline task " + task.getClass().getSimpleName());
      task.run();
      urgentRan++;
      req--;
    }

    while (serverAccessor.overTime() < 0 || req > 0) {
      Runnable task = selectionPipeline.poll();
      if (task == null) break;
      RTP.log(java.util.logging.Level.FINER,
          "[SelectionAPI] running standard pipeline task " + task.getClass().getSimpleName());
      task.run();
      standardRan++;
      req--;
    }

    if (urgentRan > 0 || standardRan > 0) {
      RTP.log(java.util.logging.Level.FINE,
          "[SelectionAPI] compute() drained urgent=" + urgentRan
              + " standard=" + standardRan
              + " urgentRemaining=" + selectionPipelineUrgent.size()
              + " standardRemaining=" + selectionPipeline.size());
    }
  }

  /**
   * Creates a temporary, one-time-use region with custom parameters, based on an
   * existing region's settings.
   *
   * @param regionParams   A map of parameters to override the base region's settings.
   * @param baseRegionName The name of the region to use as a base.
   * @return A new temporary {@link Region} instance.
   */
  public Region tempRegion(Map<String, Object> regionParams, @Nullable String baseRegionName) {
    if (baseRegionName == null
        || baseRegionName.isEmpty()
        || !permRegionLookup.containsKey(baseRegionName)) baseRegionName = "default";
    Region baseRegion = Objects.requireNonNull(permRegionLookup.get(baseRegionName));

    RegionSettings baseSettings = baseRegion.getSettings();

    RTPWorld<?> world = (RTPWorld<?>) regionParams.getOrDefault(RegionKeys.world.name(), baseSettings.world());
    Shape<?> shape = (Shape<?>) regionParams.getOrDefault(RegionKeys.shape.name(), baseSettings.shape());
    VerticalAdjustor<?> vert = (VerticalAdjustor<?>) regionParams.getOrDefault(RegionKeys.vert.name(), baseSettings.vert());
    boolean worldBorderOverride = (boolean) regionParams.getOrDefault(RegionKeys.worldBorderOverride.name(), baseSettings.worldBorderOverride());
    boolean requirePermission = (boolean) regionParams.getOrDefault(RegionKeys.requirePermission.name(), baseSettings.requirePermission());
    long cacheCap = ((Number) regionParams.getOrDefault(RegionKeys.cacheCap.name(), baseSettings.cacheCap())).longValue();
    long backlogCacheCap = ((Number) regionParams.getOrDefault(RegionKeys.backlogCacheCap.name(), baseSettings.backlogCacheCap())).longValue();
    long networkReserveSize = ((Number) regionParams.getOrDefault(RegionKeys.networkReserveSize.name(), baseSettings.networkReserveSize())).longValue();
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
            backlogCacheCap,
            networkReserveSize,
            activeChunkCap,
            price,
            spatialResolution,
            override,
            baseSettings.detailedRegionInit()
    );

    return new Region("temp", newSettings);
  }

  /**
   * Returns a world-override region (ADR-065) with {@code baseRegionName}'s settings
   * bound to {@code worldName}. Cached in {@link #tempRegions}.
   */
  @Nullable
  public Region worldRegion(@Nullable String baseRegionName, @Nullable String worldName) {
    if (worldName == null || worldName.isEmpty()) return null;
    if (baseRegionName == null
        || baseRegionName.isEmpty()
        || !permRegionLookup.containsKey(baseRegionName)) baseRegionName = "default";
    Region baseRegion = permRegionLookup.get(baseRegionName);
    if (baseRegion == null) return null;

    // Already the right world? Don't synthesize a region.
    RTPWorld<?> baseWorld = baseRegion.getWorld();
    if (baseWorld != null && worldName.equals(baseWorld.name())) return baseRegion;

    RTPWorld<?> world = RTP.serverAccessor.getRTPWorld(worldName);
    if (world == null) return null;
    UUID worldId = world.id();
    if (worldId == null) return null;

    String name = baseRegionName + "_" + worldName;
    final Region baseRegionFinal = baseRegion;
    final RTPWorld<?> worldFinal = world;
    return tempRegions.computeIfAbsent(worldId, k -> {
      RegionSettings base = baseRegionFinal.getSettings();
      // Clone base shape with clean spatial-memory state for the target world.
      Shape<?> baseShape = base.shape();
      Shape<?> shape = baseShape != null ? baseShape.clone() : null;
      RegionSettings newSettings = new RegionSettings(
          name,
          worldFinal,
          shape,
          dimensionVert(worldFinal, base.vert()),
          base.worldBorderOverride(),
          base.requirePermission(),
          base.cacheCap(),
          base.backlogCacheCap(),
          base.networkReserveSize(),
          base.activeChunkCap(),
          base.price(),
          base.spatialResolution(),
          base.override(),
          base.detailedRegionInit());
      return new Region(name, newSettings);
    });
  }

  /**
   * Resolves dimension-appropriate vertical adjustor for synthesized world-override regions.
   * Clamps nether/end dimensions to linear downward search where applicable.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private VerticalAdjustor<?> dimensionVert(RTPWorld<?> world, VerticalAdjustor<?> baseVert) {
    String worldName = world.name();
    boolean nether = worldName.endsWith("_nether");
    boolean end = worldName.endsWith("_the_end");
    if (!nether && !end) return baseVert;

    Factory<VerticalAdjustor<?>> factory =
        (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
    if (factory == null) return baseVert;
    VerticalAdjustor<?> resolved = (VerticalAdjustor<?>) factory.get("linear");
    if (resolved == null) return baseVert;

    int maxY = baseVert != null ? baseVert.maxY() : world.getMaxHeight();
    int minY = baseVert != null ? baseVert.minY() : world.getMinHeight();
    if (nether) maxY = Math.min(maxY, 128);
    maxY = Math.min(maxY, world.getMaxHeight());
    if (maxY < minY) minY = world.getMinHeight();
    else minY = Math.max(minY, world.getMinHeight());

    try {
      VerticalAdjustor raw = resolved;
      raw.set(GenericVerticalAdjustorKeys.maxY, maxY);
      raw.set(GenericVerticalAdjustorKeys.minY, minY);
      raw.set(GenericVerticalAdjustorKeys.direction, 2);
      raw.set(GenericVerticalAdjustorKeys.requireSkyLight, false);
    } catch (IllegalArgumentException ignored) {
      return baseVert;
    }
    return resolved;
  }

  /**
   * Determines the appropriate region for a player based on their current world,
   * permissions, and any configured overrides.
   *
   * @param player The player to get the region for.
   * @return The determined {@link Region}.
   * @throws IllegalStateException if an infinite override loop is detected.
   */
  public Region getRegion(RTPPlayer player) {
    Set<String> worldsAttempted = new HashSet<>();
    String worldName = player.getLocation().world().name();
    RTP.log(Level.FINE, "[SELECT_TRACE] SelectionAPI.getRegion ENTER playerId=" + player.uuid()
        + " startingWorld=" + worldName);
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

      String previousWorld = worldName;
      worldName = String.valueOf(worldParser.getConfigValue(WorldKeys.override, "default"));
      RTP.log(Level.FINER, "[SELECT_TRACE] world override step playerId=" + player.uuid()
          + " from=" + previousWorld + " to=" + worldName
          + " attemptedSoFar=" + worldsAttempted);
      worldParser = worldParsers.getParser(worldName);
      requirePermission =
          Boolean.parseBoolean(
              worldParser.getConfigValue(WorldKeys.requirePermission, false).toString());
    }

    String regionName = String.valueOf(worldParser.getConfigValue(WorldKeys.region, "default"));
    RTP.log(Level.FINE, "[SELECT_TRACE] world resolved playerId=" + player.uuid()
        + " resolvedWorld=" + worldName + " initialRegion=" + regionName);
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

      String previousRegion = regionName;
      regionName = String.valueOf(regionParser.getConfigValue(RegionKeys.override, "default"));
      RTP.log(Level.FINER, "[SELECT_TRACE] region override step playerId=" + player.uuid()
          + " from=" + previousRegion + " to=" + regionName
          + " attemptedSoFar=" + regionsAttempted);
      regionParser = regionParsers.getParser(regionName);
      requirePermission =
          Boolean.parseBoolean(
              regionParser.getConfigValue(RegionKeys.requirePermission, false).toString());
    }
    RTP.log(Level.FINE, "[SELECT_TRACE] SelectionAPI.getRegion EXIT playerId=" + player.uuid()
        + " resolvedRegion=" + regionName);
    return getRegion(regionName);
  }

  /**
   * Determines the appropriate region for a given world.
   *
   * @param world The world to get the region for.
   * @return The determined {@link Region}.
   */
  public Region getRegion(RTPWorld world) {
    String worldName = world.name();
    MultiConfigParser<WorldKeys> worldParsers =
        (MultiConfigParser<WorldKeys>) RTP.configs.multiConfigParserMap.get(WorldKeys.class);
    ConfigParser<WorldKeys> worldParser = worldParsers.getParser(worldName);
    String regionName = String.valueOf(worldParser.getConfigValue(WorldKeys.region, "default"));
    return permRegionLookup.get(regionName);
  }
}
