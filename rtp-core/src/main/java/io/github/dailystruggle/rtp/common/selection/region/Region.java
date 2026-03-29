package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.tasks.FillTask;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.teleport.LoadChunks;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.MemorySection;

public class Region extends FactoryValue<RegionKeys> {
  public static final List<BiConsumer<Region, UUID>> onPlayerQueuePush = new ArrayList<>();
  public static final List<BiConsumer<Region, UUID>> onPlayerQueuePop = new ArrayList<>();
  // semaphore needed in case of async usage
  // storage for region verifiers to use for ALL regions
  private static final Semaphore regionVerifiersLock = new Semaphore(1);
  private static final List<Predicate<RTPCoords>> regionVerifiers = new ArrayList<>();
  private static final Set<String> unsafeBlocks = new ConcurrentSkipListSet<>();
  private static final AtomicLong lastUpdate = new AtomicLong(0);
  private static final AtomicInteger safetyRadius = new AtomicInteger(0);
  public static int maxBiomeChecksPerGen = 100;
  private final Semaphore cacheGuard = new Semaphore(1);

  public java.util.concurrent.ArrayBlockingQueue<Map.Entry<RTPCoords, Long>> locationQueue;

  public ConcurrentHashMap<RTPCoords, ChunkSet> locAssChunks = new ConcurrentHashMap<>();

  /** When reserving/recycling locations for specific players, I want to guard against */
  public ConcurrentHashMap<UUID, java.util.concurrent.ArrayBlockingQueue<Map.Entry<RTPCoords, Long>>>
      perPlayerLocationQueue = new ConcurrentHashMap<>();

  /** */
  public ConcurrentHashMap<UUID, CompletableFuture<Map.Entry<RTPCoords, Long>>> fastLocations =
      new ConcurrentHashMap<>();

  public RTPTaskPipe cachePipeline;
  public RTPTaskPipe miscPipeline;
  protected java.util.concurrent.ConcurrentLinkedQueue<UUID> playerQueue =
      new java.util.concurrent.ConcurrentLinkedQueue<>();
  protected RTPWorld<?> savedWorld = null;

  public Region(String name, EnumMap<RegionKeys, Object> params) {
    super(RegionKeys.class, name);
    this.name = name;
    this.data.putAll(params);
    this.cachePipeline = (RTPTaskPipe) RTP.serverAccessor.createTaskPipe();
    this.miscPipeline = (RTPTaskPipe) RTP.serverAccessor.createTaskPipe();

    ConfigParser<LoggingKeys> logging =
        (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);

    boolean detailed_region_init = true;
    if (logging != null) {
      Object o = logging.getConfigValue(LoggingKeys.detailed_region_init, false);
      if (o instanceof Boolean) {
        detailed_region_init = (Boolean) o;
      } else {
        detailed_region_init = Boolean.parseBoolean(o.toString());
      }
    }

    Object shape = getShape();
    Object world = params.get(RegionKeys.world);
    String worldName;
    if (world instanceof RTPWorld) worldName = ((RTPWorld<?>) world).name();
    else {
      worldName = String.valueOf(world);
    }
    if (shape instanceof MemoryShape<?>) {
      if (detailed_region_init) {
        RTP.log(
            Level.INFO,
            "&00FFFF[RTP] ["
                + name
                + "] memory shape detected, reading location data from file...");
      }

      ((MemoryShape<?>) shape).load(name + ".bin", worldName);
      long iter = ((MemoryShape<?>) shape).fillIter.get();
      if (iter > 0 && iter < Double.valueOf(((MemoryShape<?>) shape).getRange()).longValue())
        RTP.getInstance().fillTasks.put(name, new FillTask(this, iter));
    }

    long cacheCap = getNumber(RegionKeys.cacheCap, 10L).longValue();
    this.locationQueue = new java.util.concurrent.ArrayBlockingQueue<>((int) Math.max(1, cacheCap));

    for (long i = cachePipeline.size(); i < cacheCap; i++) {
      cachePipeline.add(new Cache());
    }
  }

  /**
   * addGlobalRegionVerifier - add a region verifier to use for ALL regions
   *
   * @param locationCheck verifier method to reference. param: world name, 3D point return: boolean
   *     - true on good location, false on bad location
   */
  public static void addGlobalRegionVerifier(Predicate<RTPCoords> locationCheck) {
    try {
      regionVerifiersLock.acquire();
    } catch (InterruptedException e) {
      regionVerifiersLock.release();
      return;
    }
    regionVerifiers.add(locationCheck);
    regionVerifiersLock.release();
  }

  public static void clearGlobalRegionVerifiers() {
    try {
      regionVerifiersLock.acquire();
    } catch (InterruptedException e) {
      regionVerifiersLock.release();
      return;
    }
    regionVerifiers.clear();
    regionVerifiersLock.release();
  }

  public static boolean checkGlobalRegionVerifiers(RTPCoords location) {
    try {
      regionVerifiersLock.acquire();
    } catch (InterruptedException e) {
      regionVerifiersLock.release();
      return false;
    }

    for (Predicate<RTPCoords> verifier : regionVerifiers) {
      try {
        // if invalid placement, stop and return invalid
        // clone location to prevent methods from messing with the data
        if (!verifier.test(location)) {
          regionVerifiersLock.release();
          return false;
        }
      } catch (Throwable throwable) {
        RTP.log(Level.WARNING, throwable.getMessage(), throwable);
      }
    }
    regionVerifiersLock.release();
    return true;
  }

  /**
   * execute - localized task for pre-generating locations
   *
   * @param availableTime available time in nanoseconds
   */
  public void execute(long availableTime) {
    long start = System.nanoTime();
    long currentAvailable = availableTime;

    miscPipeline.execute(currentAvailable);
    currentAvailable -= (System.nanoTime() - start);

    long cacheCap = getNumber(RegionKeys.cacheCap, 10L).longValue();
    cacheCap = Math.max(cacheCap, playerQueue.size());
    try {
      cacheGuard.acquire();
      if (locationQueue.size() >= cacheCap) return;
      while (cachePipeline.size() + locationQueue.size() < cacheCap + playerQueue.size())
        cachePipeline.add(new Cache());
      cachePipeline.execute(currentAvailable);
    } catch (InterruptedException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    } finally {
      cacheGuard.release();
    }

    while (!locationQueue.isEmpty() && !playerQueue.isEmpty()) {
      UUID playerId = playerQueue.poll();
      if (playerId == null) break;

      TeleportData teleportData = RTP.getInstance().latestTeleportData.get(playerId);
      if (teleportData == null || teleportData.completed) {
        RTP.getInstance().processingPlayers.remove(playerId);
        return;
      }

      RTPPlayer player = RTP.serverAccessor.getPlayer(playerId);
      if (player == null) continue;

      Map.Entry<RTPCoords, Long> pair = locationQueue.poll();
      if (pair == null) {
        playerQueue.offer(playerId);
        continue;
      }

      teleportData.attempts = pair.getValue();
      teleportData.selectedCoords = pair.getKey();

      RTPCommandSender sender = RTP.serverAccessor.getSender(CommandsAPI.serverId);
      LoadChunks loadChunks = new LoadChunks(sender, player, pair.getKey(), this);
      teleportData.nextTask = loadChunks;
      RTP.getInstance().latestTeleportData.put(playerId, teleportData);
      RTP.getInstance().loadChunksPipeline.add(loadChunks);
      onPlayerQueuePop.forEach(consumer -> consumer.accept(this, playerId));

      Iterator<UUID> iterator = playerQueue.iterator();
      int i = 0;
      while (iterator.hasNext()) {
        UUID id = iterator.next();
        ++i;
        TeleportData data = RTP.getInstance().latestTeleportData.get(id);
        RTP.getInstance().processingPlayers.add(id);
        if (data == null) {
          data = new TeleportData();
          data.completed = false;
          data.sender = RTP.serverAccessor.getSender(CommandsAPI.serverId);
          data.time = System.currentTimeMillis();
          data.delay = sender.delay();
          data.targetRegion = this;
          data.originalCoords =
              new RTPCoords(
                  player.getLocation().world().name(),
                  player.getLocation().x(),
                  player.getLocation().y(),
                  player.getLocation().z());
          RTP.getInstance().latestTeleportData.put(id, data);
        }
        data.queueLocation = i;
        RTP.serverAccessor.sendMessage(id, MessagesKeys.queueUpdate);
      }
    }
  }

  /**
   * hasLocation - check if this region has a location ready for a player
   *
   * @param uuid player uuid
   * @return true if location is ready
   */
  public boolean hasLocation(@Nullable UUID uuid) {
    boolean res = !locationQueue.isEmpty();
    res |= (uuid != null) && (perPlayerLocationQueue.containsKey(uuid));
    return res;
  }

  /**
   * getLocation - get a location from cache or generate one
   *
   * @param sender command sender
   * @param player player to teleport
   * @param biomeNames optional set of biomes to filter by
   * @return location and number of attempts
   */
  public Map.Entry<RTPCoords, Long> getLocation(
      RTPCommandSender sender, RTPPlayer player, @Nullable Set<String> biomeNames) {
    Map.Entry<RTPCoords, Long> pair = null;

    getShape(); // validate shape before using cache

    UUID playerId = player.uuid();

    boolean custom = biomeNames != null && !biomeNames.isEmpty();

    if (!custom && perPlayerLocationQueue.containsKey(playerId)) {
      java.util.concurrent.ArrayBlockingQueue<Map.Entry<RTPCoords, Long>> playerLocationQueue =
          perPlayerLocationQueue.get(playerId);
      while (!playerLocationQueue.isEmpty()) {
        pair = playerLocationQueue.poll();
        if (pair == null || pair.getKey() == null) continue;
        RTPCoords left = pair.getKey();
        boolean pass = true;

        RTPWorld<?> world = getWorld();
        int cx = (left.x() > 0) ? left.x() / 16 : left.x() / 16 - 1;
        int cz = (left.z() > 0) ? left.z() / 16 : left.z() / 16 - 1;
        CompletableFuture<Long> chunkAt =
            RTP.serverAccessor.getChunkManager().getChunkAtAsync(world, cx, cz);
        RTPChunk chunk = null;
        try {
          try {
            Long chunkKey = chunkAt.get();
            if (chunkKey != null) chunk = world.getCachedChunk(chunkKey);
          } catch (InterruptedException | ExecutionException e) {
            RTP.log(Level.WARNING, e.getMessage(), e);
            continue;
          }
          if (chunk == null) return null;

        long t = System.currentTimeMillis();
        long dt = t - lastUpdate.get();
        if (dt > 5000 || dt < 0) {
          ConfigParser<SafetyKeys> safety =
              (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
          Object value = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
          if (value instanceof Collection) {
            Collection<?> collection = (Collection<?>) value;
            if (((Collection<?>) value).isEmpty()) unsafeBlocks.clear();
            else if (((Collection<?>) value).size() == unsafeBlocks.size()) {
              if (value instanceof List<?>) {
                List<?> list = (List<?>) value;
                if (list.get(0) instanceof String) {
                  List<String> stringList = (List<String>) list;
                  boolean same = true;
                  for (String s : stringList) {
                    if (!unsafeBlocks.contains(s)) {
                      same = false;
                      break;
                    }
                  }
                  if (!same) {
                    unsafeBlocks.clear();
                    unsafeBlocks.addAll(
                        list.stream().map(Object::toString).collect(Collectors.toSet()));
                  }
                } else {
                  unsafeBlocks.clear();
                  unsafeBlocks.addAll(
                      list.stream().map(Object::toString).collect(Collectors.toSet()));
                }
              }
            }

            unsafeBlocks.addAll(
                collection.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .collect(Collectors.toSet()));
          }
          lastUpdate.set(t);
          safetyRadius.set(safety.getNumber(SafetyKeys.safetyRadius, 0).intValue());
        }

        // todo: waterlogged check
        int safe = safetyRadius.get();
        Map<Long, RTPChunk> chunks = new HashMap<>();
        long initialChunkKey = ((long) chunk.x() & 0xFFFFFFFFL) | (((long) chunk.z() & 0xFFFFFFFFL) << 32);
        chunks.put(initialChunkKey, chunk);
        chunk.keep(true);
        try {
          for (int x = left.x() - safe; x < left.x() + safe && pass; x++) {
          int xx = x;
          int dx = Math.abs(xx / 16);
          int chunkX = chunk.x();

          if (xx < 0) {
            chunkX -= dx + 1;
            if (xx % 16 == 0) xx += 16 * dx;
            else xx += 16 * (dx + 1);
          } else if (xx >= 16) {
            chunkX += dx;
            xx -= 16 * dx;
          }

          for (int z = left.z() - safe; z < left.z() + safe && pass; z++) {
            int zz = z;

            int dz = Math.abs(zz / 16);

            int chunkZ = chunk.z();

            if (zz < 0) {
              chunkZ -= dz + 1;
              if (zz % 16 == 0) zz += 16 * dz;
              else zz += 16 * (dz + 1);
            } else if (zz >= 16) {
              chunkZ += dz;
              zz -= 16 * dz;
            }

            long chunkKey = ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
            RTPChunk chunk1;
            if (chunks.containsKey(chunkKey)) chunk1 = chunks.get(chunkKey);
            else {
              try {
                Long key =
                    RTP.serverAccessor
                        .getChunkManager()
                        .getChunkAtAsync(world, chunkX, chunkZ)
                        .get();
                chunk1 = world.getCachedChunk(key);
                chunks.put(chunkKey, chunk1);
                chunk1.keep(true);
              } catch (InterruptedException | ExecutionException e) {
                return null;
              }
            }

            if (chunk1 == null) {
              pass = false;
              break;
            }

            for (int y = left.y() - safe; y < left.y() + safe && pass; y++) {
              if (y > world.getMaxHeight() || y < world.getMinHeight()) continue;
              if (!chunk1.isSafe(xx, y, zz, unsafeBlocks)) {
                pass = false;
                break;
              }
            }
          }
        }
      } finally {
          for (RTPChunk usedChunk : chunks.values()) {
            if (usedChunk != null) usedChunk.keep(false);
          }
        }

        if (pass) {
          if (!checkGlobalRegionVerifiers(left)) pass = false;
        }

        if (pass) return pair;
        } finally {
          if (chunk != null) chunk.unload();
        }
      }
    }

    while (!custom && !locationQueue.isEmpty()) {
      pair = locationQueue.poll();
      if (pair == null) return null;
      RTPCoords left = pair.getKey();
      if (left == null) return pair;
      boolean pass = checkGlobalRegionVerifiers(left);
      if (pass) return pair;
    }

    if (custom || sender.hasPermission("rtp.unqueued")) {
      pair = getLocation(biomeNames);
      if (pair != null) {
        long attempts = pair.getValue();
        TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
        if (data != null && !data.completed) {
          data.attempts = attempts;
        }
      }
    } else {
      RTP.getInstance().processingPlayers.add(playerId);
      TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
      if (data == null) {
        data = new TeleportData();
        data.sender = (sender != null) ? sender : player;
        data.completed = false;
        data.time = System.currentTimeMillis();
        data.delay = sender.delay();
        data.targetRegion = this;
        data.originalCoords =
            new RTPCoords(
                player.getLocation().world().name(),
                player.getLocation().x(),
                player.getLocation().y(),
                player.getLocation().z());
        RTP.getInstance().latestTeleportData.put(playerId, data);
      }
      onPlayerQueuePush.forEach(consumer -> consumer.accept(this, playerId));
      playerQueue.offer(playerId);
      data.queueLocation = playerQueue.size();
      RTP.serverAccessor.sendMessage(playerId, MessagesKeys.queueUpdate);
    }
    return pair;
  }

  /**
   * getLocation - generate a location with biome requirements
   *
   * @param biomeNames set of biomes to filter by
   * @return location and number of attempts
   */
  @Nullable
  public Map.Entry<RTPCoords, Long> getLocation(@Nullable Set<String> biomeNames) {

    boolean defaultBiomes = false;
    ConfigParser<PerformanceKeys> performance =
        (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
    ConfigParser<SafetyKeys> safety =
        (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
    ConfigParser<LoggingKeys> logging =
        (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
    Object o;
    if (biomeNames == null || biomeNames.isEmpty()) {
      defaultBiomes = true;
      o = safety.getConfigValue(SafetyKeys.biomeWhitelist, false);
      boolean whitelist = (o instanceof Boolean) ? (Boolean) o : Boolean.parseBoolean(o.toString());

      o = safety.getConfigValue(SafetyKeys.biomes, null);
      List<String> biomeList =
          (o instanceof List)
              ? ((List<?>) o).stream().map(Object::toString).collect(Collectors.toList())
              : null;
      Set<String> biomeSet =
          (biomeList == null)
              ? new HashSet<>()
              : biomeList.stream().map(String::toUpperCase).collect(Collectors.toSet());
      if (whitelist) {
        biomeNames = biomeSet;
      } else {
        Set<String> biomes = RTP.serverAccessor.getBiomes(getWorld());
        Set<String> set = new HashSet<>();
        for (String s : biomes) {
          if (!biomeSet.contains(s.toUpperCase())) {
            set.add(s);
          }
        }
        biomeNames = set;
      }
    }

    boolean verbose = false;
    if (logging != null) {
      o = logging.getConfigValue(LoggingKeys.selection_failure, false);
      if (o instanceof Boolean) {
        verbose = (Boolean) o;
      } else {
        verbose = Boolean.parseBoolean(o.toString());
      }
    }

    Shape<?> shape = getShape();
    if (shape == null) {
      new IllegalStateException("[RTP] invalid state, null shape").printStackTrace();
      return null;
    }

    VerticalAdjustor<?> vert = getVert();
    if (vert == null) {
      new IllegalStateException("[RTP] invalid state, null vert").printStackTrace();
      return null;
    }

    o = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
    Set<String> unsafeBlocks =
        (o instanceof Collection)
            ? ((Collection<?>) o)
                .stream().map(o1 -> o1.toString().toUpperCase()).collect(Collectors.toSet())
            : new HashSet<>();

    int safetyRadius = safety.getNumber(SafetyKeys.safetyRadius, 0).intValue();

    long maxAttemptsBase = performance.getNumber(PerformanceKeys.maxAttempts, 20).longValue();
    maxAttemptsBase = Math.max(maxAttemptsBase, 1);
    long maxAttempts = maxAttemptsBase;
    long maxBiomeChecks = maxBiomeChecksPerGen * maxAttempts;
    if (!defaultBiomes) maxBiomeChecks *= 10;
    long biomeChecks = 0L;

    RTPWorld world = getWorld();

    Map<FailTypes, Map<String, Long>> failMap = new EnumMap<>(FailTypes.class);
    for (FailTypes f : FailTypes.values()) failMap.put(f, new HashMap<>());
    List<Map.Entry<Long, Long>> selections = new ArrayList<>();

    int finalX = 0, finalY = 0, finalZ = 0;
    boolean locationFound = false;
    long i = 1;

    boolean biomeRecall =
        Boolean.parseBoolean(
            performance.getConfigValue(PerformanceKeys.biomeRecall, false).toString());
    boolean biomeRecallForced =
        Boolean.parseBoolean(
            performance.getConfigValue(PerformanceKeys.biomeRecallForced, false).toString());

    for (; i <= maxAttempts; i++) {
      long l = -1;
      int[] select;
      if (shape instanceof MemoryShape) {
        MemoryShape<?> memoryShape = (MemoryShape<?>) shape;
        if (biomeRecall && !defaultBiomes) {
          List<Map.Entry<Long, Long>> biomes = new ArrayList<>();
          for (String biomeName : biomeNames) {
            ConcurrentSkipListMap<Long, Long> map = memoryShape.biomeLocations.get(biomeName);
            if (map != null) {
              biomes.addAll(map.entrySet());
            }
          }
          Map.Entry<Long, Long> entry;
          if (biomes.size() > 0) {
            int nextInt = ThreadLocalRandom.current().nextInt(biomes.size());
            entry = biomes.get(nextInt);
            l = entry.getKey() + ThreadLocalRandom.current().nextLong(entry.getValue());
          } else if (biomeRecallForced) {
            new IllegalStateException(
                    "[RTP] invalid state, biome recall enabled but biomes are not in memory - "
                        + Arrays.toString(biomeNames.toArray()))
                .printStackTrace();
            return new AbstractMap.SimpleEntry<>(null, i);
          } else l = memoryShape.rand();
        } else {
          l = memoryShape.rand();
        }

        select = memoryShape.locationToXZ(l);

      } else {
        select = shape.select();
      }
      if (verbose) {
        //                if( shape instanceof MemoryShape ) selections.add( new
        // AbstractMap.SimpleEntry<>( (long ) selections.size(), l) );
        //                else selections.add( new AbstractMap.SimpleEntry<>( (long ) select[0], (
        // long ) select[1]) );
        selections.add(new AbstractMap.SimpleEntry<>((long) select[0], (long) select[1]));
      }

      String currBiome =
          world.getBiome(select[0] * 16 + 7, (vert.minY() + vert.maxY()) / 2, select[1] * 16 + 7);

      for (;
          biomeChecks < maxBiomeChecks && !biomeNames.contains(currBiome);
          biomeChecks++, maxAttempts++, i++) {
        if (shape instanceof MemoryShape) {
          MemoryShape<?> memoryShape = (MemoryShape<?>) shape;
          if (defaultBiomes && biomeRecall) {
            memoryShape.addBadLocation(l);
          }
          if (biomeRecall && !defaultBiomes) {
            List<Map.Entry<Long, Long>> biomes = new ArrayList<>();
            for (String biomeName : biomeNames) {
              ConcurrentSkipListMap<Long, Long> map = memoryShape.biomeLocations.get(biomeName);
              if (map != null) {
                biomes.addAll(map.entrySet());
              }
            }
            Map.Entry<Long, Long> entry;
            if (biomes.size() > 0) {
              int nextInt = ThreadLocalRandom.current().nextInt(biomes.size());
              entry = biomes.get(nextInt);
              l = entry.getKey() + ThreadLocalRandom.current().nextLong(entry.getValue());
            } else if (biomeRecallForced) {
              new IllegalStateException(
                      "[RTP] invalid state, biome recall enabled but biomes are not in memory - "
                          + Arrays.toString(biomeNames.toArray()))
                  .printStackTrace();
              return new AbstractMap.SimpleEntry<>(null, i);
            } else l = memoryShape.rand();
          } else {
            l = memoryShape.rand();
          }

          select = memoryShape.locationToXZ(l);
        } else {
          select = shape.select();
        }

        if (verbose) {
          //                if( shape instanceof MemoryShape ) selections.add( new
          // AbstractMap.SimpleEntry<>( (long ) selections.size(), l) );
          //                else selections.add( new AbstractMap.SimpleEntry<>( (long ) select[0], (
          // long ) select[1]) );
          selections.add(new AbstractMap.SimpleEntry<>((long) select[0], (long) select[1]));
        }

        String key = "biome=" + currBiome;
        if (verbose) {
          failMap
              .get(FailTypes.biome)
              .compute(
                  key,
                  (s, aLong) -> {
                    if (aLong == null) return 1L;
                    return ++aLong;
                  });
        }
        currBiome =
            world.getBiome(select[0] * 16 + 7, (vert.minY() + vert.maxY()) / 2, select[1] * 16 + 7);
      }
      if (biomeChecks >= maxBiomeChecks) break;

      WorldBorder border = (WorldBorder) RTP.serverAccessor.getWorldBorder(world.name());
      if (!border
          .isInside()
          .apply(
              new RTPLocation(
                  world, select[0] * 16, (vert.maxY() + vert.minY()) / 2, select[1] * 16))) {
        new IllegalStateException(
                "worldborder check failed. region/selection is likely outside the worldborder")
            .printStackTrace();
        maxAttempts++;
        Long worldBorderFails =
            failMap.get(FailTypes.worldBorder).getOrDefault("OUTSIDE_BORDER", 0L);
        worldBorderFails++;
        if (worldBorderFails > 1000) {
          new IllegalStateException(
                  "1000 worldborder checks failed. region/selection is likely outside the worldborder")
              .printStackTrace();
          return new AbstractMap.SimpleEntry<>(null, i);
        }
        failMap.get(FailTypes.worldBorder).put("OUTSIDE_BORDER", worldBorderFails);
        continue;
      }

      CompletableFuture<Long> cfChunk =
          RTP.serverAccessor.getChunkManager().getChunkAtAsync(world, select[0], select[1]);
      RTP.futures.add(cfChunk);

      RTPChunk<?> chunk;

      try {
        Long key = cfChunk.get();
        chunk = world.getCachedChunk(key);
      } catch (InterruptedException | ExecutionException e) {
        RTP.log(Level.WARNING, e.getMessage(), e);
        return new AbstractMap.SimpleEntry<>(null, i);
      }
      if (chunk == null) {
        new IllegalStateException("[RTP] null chunk").printStackTrace();
        return null;
      }

      try {
        RTPCoords res = vert.adjust(chunk);
        if (res == null) {
          if (defaultBiomes && shape instanceof MemoryShape && biomeRecall) {
            ((MemoryShape<?>) shape).addBadLocation(l);
          }
          if (verbose) {
            failMap
                .get(FailTypes.vert)
                .compute("biome=" + currBiome, (s, aLong) -> (aLong == null) ? (1L) : (++aLong));
          }
          continue;
        }

        finalX = res.x();
        finalY = res.y();
        finalZ = res.z();
        currBiome = world.getBiome(finalX, finalY, finalZ);

        if (!biomeNames.contains(currBiome)) {
          biomeChecks++;
          maxAttempts++;
          if (defaultBiomes && shape instanceof MemoryShape && biomeRecall) {
            ((MemoryShape<?>) shape).addBadLocation(l);
          }

          if (verbose) {
            failMap
                .get(FailTypes.biome)
                .compute("biome=" + currBiome, (s, aLong) -> (aLong == null) ? 1L : ++aLong);
          }
          continue;
        }

      boolean pass = true;

      // todo: waterlogged check
      RTPChunk chunk1;
      Map<Long, RTPChunk> chunks = new HashMap<>();
      long initialChunkKey = ((long) chunk.x() & 0xFFFFFFFFL) | (((long) chunk.z() & 0xFFFFFFFFL) << 32);
      chunks.put(initialChunkKey, chunk);
      chunk.keep(true);
      try {
        for (int x = finalX - safetyRadius; x < finalX + safetyRadius && pass; x++) {
        int xx = x;
        int dx = Math.abs(xx / 16);
        int chunkX = chunk.x();

        if (xx < 0) {
          chunkX -= dx + 1;
          if (xx % 16 == 0) xx += 16 * dx;
          else xx += 16 * (dx + 1);
        } else if (xx >= 16) {
          chunkX += dx;
          xx -= 16 * dx;
        }

        for (int z = finalZ - safetyRadius; z < finalZ + safetyRadius && pass; z++) {
          int zz = z;

          int dz = Math.abs(zz / 16);

          int chunkZ = chunk.z();

          if (zz < 0) {
            chunkZ -= dz + 1;
            if (zz % 16 == 0) zz += 16 * dz;
            else zz += 16 * (dz + 1);
          } else if (zz >= 16) {
            chunkZ += dz;
            zz -= 16 * dz;
          }

          long chunkKey = ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
          if (chunks.containsKey(chunkKey)) chunk1 = chunks.get(chunkKey);
          else {
            try {
              Long key =
                  RTP.serverAccessor
                      .getChunkManager()
                      .getChunkAtAsync(getWorld(), chunkX, chunkZ)
                      .get();
              chunk1 = getWorld().getCachedChunk(key);
              chunks.put(chunkKey, chunk1);
              chunk1.keep(true);
            } catch (InterruptedException | ExecutionException e) {
              RTP.log(Level.WARNING, e.getMessage(), e);
              return null;
            }
          }

          for (int y = finalY - safetyRadius; y < finalY + safetyRadius && pass; y++) {
            if (y > getWorld().getMaxHeight() || y < getWorld().getMinHeight()) continue;
            if (!chunk1.isSafe(xx, y, zz, unsafeBlocks)) {
              pass = false;
            }
          }
        }
      }
    } finally {
        for (RTPChunk usedChunk : chunks.values()) {
          if (usedChunk != null) usedChunk.keep(false);
        }
      }

      pass &= checkGlobalRegionVerifiers(new RTPCoords(world.name(), finalX, finalY, finalZ));

      if (pass) {
        if (shape instanceof MemoryShape) {
          if (l > 0) ((MemoryShape<?>) shape).addBiomeLocation(l, currBiome);
        }
        locationFound = true;
        break;
      } else {
        if (verbose)
          failMap
              .get(FailTypes.misc)
              .compute(
                  "location=" + "(" + finalX + "," + finalY + "," + finalZ,
                  (s, aLong) -> (aLong == null) ? 1L : ++aLong);
        if (shape instanceof MemoryShape) {
          ((MemoryShape<?>) shape).addBadLocation(l);
        }
      }
      } finally {
        chunk.unload();
      }
    }

    //        if ( verbose ) {
    if (verbose && i >= maxAttempts || i > maxAttemptsBase * maxBiomeChecksPerGen) {
      RTP.log(
          Level.INFO,
          "#00ff80[RTP] ["
              + name
              + "] failed to generate a location within "
              + maxAttempts
              + " tries. Adjust your configuration.");
      for (Map.Entry<FailTypes, Map<String, Long>> mapEntry : failMap.entrySet()) {
        Map<String, Long> map = mapEntry.getValue();
        String[] output = new String[map.size()];
        int pos = 0;
        long count = 0;
        for (Map.Entry<String, Long> entry : map.entrySet()) {
          output[pos] =
              "#00ff80[RTP] ["
                  + name
                  + "] "
                  + " cause="
                  + mapEntry.getKey()
                  + " "
                  + entry.getKey()
                  + " fails="
                  + entry.getValue();
          count += entry.getValue();
          pos++;
        }
        RTP.log(
            Level.INFO,
            "#00ff80[RTP] [" + name + "] " + " cause=" + mapEntry.getKey() + " fails=" + count);
        for (String out : output) {
          RTP.log(Level.INFO, out);
        }
      }

      StringBuilder selectionsStr = new StringBuilder();
      boolean first = true;
      selectionsStr = selectionsStr.append("{");
      for (Map.Entry<Long, Long> entry : selections) {
        if (!first) {
          selectionsStr = selectionsStr.append(",");
        }
        selectionsStr =
            selectionsStr
                .append("(")
                .append(entry.getKey())
                .append(",")
                .append(entry.getValue())
                .append(")");
      }
      selectionsStr = selectionsStr.append("}");
      RTP.log(Level.INFO, "#0f0080[RTP] [" + name + "] selections: " + selectionsStr);
    }

    i = Math.min(i, maxAttempts);

    if (!locationFound) return new AbstractMap.SimpleEntry<>(null, i);
    RTPCoords coords = new RTPCoords(world.name(), finalX, finalY, finalZ);
    return new AbstractMap.SimpleEntry<>(coords, i);
  }

  /** shutDown - save and clear data */
  public void shutDown() {
    Shape<?> shape = getShape();
    if (shape == null) return;

    RTPWorld world = getWorld();
    if (world == null) return;

    if (shape instanceof MemoryShape<?>) {
      ((MemoryShape<?>) shape).save(this.name + ".bin", world.name());
    }

    cachePipeline.stop();
    cachePipeline.clear();

    for (Map.Entry<RTPCoords, Long> pair : locationQueue) {
      removeChunks(pair.getKey());
    }
    locationQueue.clear();

    for (java.util.concurrent.ArrayBlockingQueue<Map.Entry<RTPCoords, Long>> queue :
        perPlayerLocationQueue.values()) {
      for (Map.Entry<RTPCoords, Long> pair : queue) {
        removeChunks(pair.getKey());
      }
    }
    perPlayerLocationQueue.clear();

    locAssChunks.forEach((coords, chunkSet) -> chunkSet.keep(false, getWorld()));
    locAssChunks.clear();
  }

  @Override
  public Region clone() {
    Region clone = (Region) super.clone();
    clone.data = data.clone();
    int intCap = locationQueue.remainingCapacity() + locationQueue.size();
    if (intCap < 1) intCap = 1;
    clone.locationQueue = new ArrayBlockingQueue<>(intCap);
    clone.locAssChunks = new ConcurrentHashMap<>();
    clone.playerQueue = new ConcurrentLinkedQueue<>();
    clone.perPlayerLocationQueue = new ConcurrentHashMap<>();
    clone.fastLocations = new ConcurrentHashMap<>();
    return clone;
  }

  /**
   * params - get current selection parameters
   *
   * @return map of parameters
   */
  public Map<String, String> params() {
    Map<String, String> res = new ConcurrentHashMap<>();
    for (Map.Entry<? extends Enum<?>, ?> e : data.entrySet()) {
      Object value = e.getValue();
      if (value instanceof RTPWorld) {
        res.put("world", ((RTPWorld<?>) value).name());
      } else if (value instanceof Shape) {
        res.put("shape", ((Shape<?>) value).name);
        EnumMap<? extends Enum<?>, Object> data = ((Shape<?>) value).getData();
        for (Map.Entry<? extends Enum<?>, ?> dataEntry : data.entrySet()) {
          res.put(dataEntry.getKey().name(), dataEntry.getValue().toString());
        }
      } else if (value instanceof VerticalAdjustor) {
        res.put("vert", ((VerticalAdjustor<?>) value).name);
        EnumMap<? extends Enum<?>, Object> data = ((VerticalAdjustor<?>) value).getData();
        for (Map.Entry<? extends Enum<?>, ?> dataEntry : data.entrySet()) {
          res.put(dataEntry.getKey().name(), dataEntry.getValue().toString());
        }
      } else if (value instanceof String) res.put(e.getKey().name(), (String) value);
      else {
        res.put(e.getKey().name(), value.toString());
      }
    }
    return res;
  }

  /**
   * chunks - get a set of chunks around a coordinate
   *
   * @param coords center coordinates
   * @param radius chunk radius
   * @return set of chunks
   */
  public ChunkSet chunks(RTPCoords coords, long radius) {
    long sz = (radius * 2 + 1) * (radius * 2 + 1);
    if (locAssChunks.containsKey(coords)) {
      ChunkSet chunkSet = locAssChunks.get(coords);
      if (chunkSet.chunks.size() >= sz) return chunkSet;
      chunkSet.keep(false, getWorld());
      locAssChunks.remove(coords);
    }

    int cx = coords.x();
    int cz = coords.z();
    cx = (cx > 0) ? cx / 16 : cx / 16 - 1;
    cz = (cz > 0) ? cz / 16 : cz / 16 - 1;

    List<CompletableFuture<Long>> chunks = new ArrayList<>();

    Shape<?> shape = getShape();
    if (shape == null) return null;

    VerticalAdjustor<?> vert = getVert();
    if (vert == null) return null;

    RTPWorld<?> rtpWorld = getWorld();
    if (rtpWorld == null) return null;

    for (long i = -radius; i <= radius; i++) {
      for (long j = -radius; j <= radius; j++) {
        CompletableFuture<Long> cfChunk =
            RTP.serverAccessor
                .getChunkManager()
                .getChunkAtAsync(rtpWorld, (int) (cx + i), (int) (cz + j));
        chunks.add(cfChunk);
      }
    }

    ChunkSet chunkSet = new ChunkSet(chunks, new CompletableFuture<>());
    chunkSet.keep(true, rtpWorld);
    locAssChunks.put(coords, chunkSet);
    return chunkSet;
  }

  /**
   * removeChunks - stop keeping chunks loaded for a coordinate
   *
   * @param coords coordinates to remove chunks for
   */
  public void removeChunks(RTPCoords coords) {
    if (!locAssChunks.containsKey(coords)) return;
    ChunkSet chunkSet = locAssChunks.get(coords);
    chunkSet.keep(false, getWorld());
    locAssChunks.remove(coords);
  }

  /**
   * fastQueue - get a location as fast as possible for a player
   *
   * @param id player uuid
   * @return future location and number of attempts
   */
  public CompletableFuture<Map.Entry<RTPCoords, Long>> fastQueue(UUID id) {
    if (fastLocations.containsKey(id)) return fastLocations.get(id);
    CompletableFuture<Map.Entry<RTPCoords, Long>> res = new CompletableFuture<>();
    fastLocations.put(id, res);
    miscPipeline.add(new Cache(id));
    return res;
  }

  /**
   * queue - add a player to the queue for this region
   *
   * @param id player uuid
   */
  public void queue(UUID id) {
    perPlayerLocationQueue.putIfAbsent(id, new java.util.concurrent.ArrayBlockingQueue<>(3));
    miscPipeline.add(new Cache(id));
  }

  /**
   * getTotalQueueLength - get combined length of public and private queues
   *
   * @param uuid player uuid
   * @return combined queue length
   */
  public long getTotalQueueLength(UUID uuid) {
    long res = locationQueue.size();
    java.util.concurrent.ArrayBlockingQueue<Map.Entry<RTPCoords, Long>> queue =
        perPlayerLocationQueue.get(uuid);
    if (queue != null) res += queue.size();
    if (fastLocations.containsKey(uuid)) res++;
    return res;
  }

  /**
   * getPublicQueueLength - get number of locations available to everyone
   *
   * @return public queue length
   */
  public long getPublicQueueLength() {
    return locationQueue.size();
  }

  /**
   * getPersonalQueueLength - get number of locations reserved for a specific player
   *
   * @param uuid player uuid
   * @return personal queue length
   */
  public long getPersonalQueueLength(UUID uuid) {
    long res = 0;
    java.util.concurrent.ArrayBlockingQueue<Map.Entry<RTPCoords, Long>> queue =
        perPlayerLocationQueue.get(uuid);
    if (queue != null) res += queue.size();
    if (fastLocations.containsKey(uuid)) res++;
    return res;
  }

  public Shape<?> getShape() {
    boolean wbo = false;
    Object o = data.getOrDefault(RegionKeys.worldBorderOverride, false);
    if (o instanceof Boolean) wbo = (Boolean) o;
    else if (o instanceof String) {
      wbo = Boolean.parseBoolean((String) o);
      data.put(RegionKeys.worldBorderOverride, wbo);
    }

    RTPWorld world = getWorld();
    if (world == null) world = RTP.serverAccessor.getRTPWorlds().get(0);

    Object shapeObj = data.get(RegionKeys.shape);
    Shape<?> shape;
    if (shapeObj instanceof Shape) {
      shape = (Shape<?>) shapeObj;
    } else if (shapeObj instanceof MemorySection) {
      final Map<String, Object> shapeMap = ((MemorySection) shapeObj).getMapValues(true);
      String shapeName = String.valueOf(shapeMap.get("name"));
      Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
      shape = (Shape<?>) factory.get(shapeName);
      EnumMap<?, Object> shapeData = shape.getData();
      for (Map.Entry<? extends Enum<?>, Object> e : shapeData.entrySet()) {
        String name = e.getKey().name();
        if (shapeMap.containsKey(name)) {
          e.setValue(shapeMap.get(name));
        } else {
          Object altName = shape.language_mapping.get(name);
          if (altName != null && shapeMap.containsKey(altName.toString())) {
            e.setValue(shapeMap.get(altName.toString()));
          }
        }
      }
      shape.setData(shapeData);
      data.put(RegionKeys.shape, shape);
    } else throw new IllegalArgumentException("invalid shape\n" + shapeObj);

    if (wbo) {
      Shape<?> worldShape;
      worldShape = ((WorldBorder) RTP.serverAccessor.getWorldBorder(world.name())).getShape().get();
      if (!worldShape.equals(shape)) {
        shape = worldShape;
        data.put(RegionKeys.shape, shape);
        for (Map.Entry<UUID, java.util.concurrent.ArrayBlockingQueue<Map.Entry<RTPCoords, Long>>>
            entry : perPlayerLocationQueue.entrySet()) {
          java.util.concurrent.ArrayBlockingQueue<Map.Entry<RTPCoords, Long>> value =
              entry.getValue();
          for (Map.Entry<RTPCoords, Long> entry1 : value) removeChunks(entry1.getKey());
          value.clear();
        }
        perPlayerLocationQueue.clear();
        for (Map.Entry<RTPCoords, Long> entry : locationQueue) removeChunks(entry.getKey());
        locationQueue.clear();
      }
    }
    return shape;
  }

  public VerticalAdjustor<?> getVert() {
    Object vertObj = data.get(RegionKeys.vert);
    VerticalAdjustor<?> vert;
    if (vertObj instanceof VerticalAdjustor) {
      vert = (VerticalAdjustor<?>) vertObj;
    } else if (vertObj instanceof MemorySection) {
      final Map<String, Object> vertMap = ((MemorySection) vertObj).getMapValues(true);
      String vertName = String.valueOf(vertMap.get("name"));
      Factory<VerticalAdjustor<?>> factory =
          (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
      vert = (VerticalAdjustor<?>) factory.get(vertName);
      EnumMap<?, Object> vertData = vert.getData();
      for (Map.Entry<? extends Enum<?>, Object> e : vertData.entrySet()) {
        String name = e.getKey().name();
        if (vertMap.containsKey(name)) {
          e.setValue(vertMap.get(name));
        } else {
          Object altName = vert.language_mapping.get(name);
          if (altName != null && vertMap.containsKey(altName.toString())) {
            e.setValue(vertMap.get(altName.toString()));
          }
        }
      }
      Map<String, Object> validY = new HashMap<>();
      validY.put("minY", Math.max(getWorld().getMinHeight(), vert.minY()));
      validY.put("maxY", Math.min(getWorld().getMaxHeight(), vert.maxY()));

      vert.setData(vertData);
      vert.setData(validY);

      data.put(RegionKeys.vert, vert);
    } else throw new IllegalArgumentException("invalid shape\n" + vertObj);

    return vert;
  }

  public RTPWorld<?> getWorld() {
    if (savedWorld instanceof RTPWorld && savedWorld.isActive()) {
      return savedWorld;
    }

    Object world = data.get(RegionKeys.world);
    String worldName = String.valueOf(world);
    if (worldName.startsWith("[") && worldName.endsWith("]")) {
      int num = Integer.parseInt(worldName.substring(1, worldName.length() - 1));
      savedWorld = RTP.serverAccessor.getRTPWorlds().get(num);
    } else savedWorld = RTP.serverAccessor.getRTPWorld(worldName);
    if (savedWorld == null) savedWorld = RTP.serverAccessor.getRTPWorlds().get(0);

    return savedWorld;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof Region)) return false;
    Region region = (Region) other;

    if (!getShape().equals(region.getShape())) return false;
    if (!getVert().equals(region.getVert())) return false;
    if (!getWorld().equals(region.getWorld())) return false;
    return Boolean.parseBoolean(region.data.get(RegionKeys.worldBorderOverride).toString())
        == Boolean.parseBoolean(data.get(RegionKeys.worldBorderOverride).toString());
  }

  protected enum FailTypes {
    biome,
    worldBorder,
    timeout,
    vert,
    safety,
    safetyExternal,
    misc
  }

  // localized generic task for
  protected class Cache extends RTPRunnable {
    private final UUID playerId;

    public Cache() {
      playerId = null;
    }

    public Cache(UUID playerId) {
      this.playerId = playerId;
    }

    @Override
    public void run() {
      final long cacheCap = getNumber(RegionKeys.cacheCap, 10L).longValue();
      final long playerQueueSize = playerQueue.size();
      final long totalCap = Math.max(cacheCap, playerQueueSize);
      Map.Entry<RTPCoords, Long> pair = getLocation(null);
      if (pair != null) {
        RTPCoords coords = pair.getKey();
        if (coords == null) {
          if (cachePipeline.size() + locationQueue.size() < totalCap)
            cachePipeline.add(new Cache());
          return;
        }

        ConfigParser<PerformanceKeys> perf =
            (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        long radius = perf.getNumber(PerformanceKeys.viewDistanceSelect, 0L).longValue();

        ChunkSet chunkSet = chunks(coords, radius);

        chunkSet.whenComplete(
            aBoolean -> {
              if (aBoolean) {
                if (playerId == null) {
                  locationQueue.offer(pair);
                  locAssChunks.put(coords, chunkSet);
                } else if (fastLocations.containsKey(playerId)
                    && !fastLocations.get(playerId).isDone()) {
                  fastLocations.get(playerId).complete(pair);
                } else {
                  perPlayerLocationQueue.putIfAbsent(
                      playerId, new java.util.concurrent.ArrayBlockingQueue<>(3));
                  perPlayerLocationQueue.get(playerId).offer(pair);
                }
              } else {
                chunkSet.keep(false, getWorld());
                locAssChunks.remove(coords);
              }
            });
      }
      if (cachePipeline.size() + locationQueue.size() < totalCap)
        cachePipeline.add(new Cache());
    }
  }
}
