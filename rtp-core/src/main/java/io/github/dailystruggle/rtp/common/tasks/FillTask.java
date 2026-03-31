package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** Task for pre-filling a region with valid teleport locations */
public class FillTask extends RTPRunnable {
  /** Number of locations to process in each step */
  public static final AtomicLong fillIncrement = new AtomicLong(0L);

  private static final AtomicLong cps = new AtomicLong(128);
  private static final BigInteger increment_big = new BigInteger("1");
  private final Region region;
  private long start;
  private final CompletableFuture<Boolean> done = new CompletableFuture<>();
  private final AtomicLong completionCounter = new AtomicLong();

  /** Whether the task is currently paused */
  public AtomicBoolean pause = new AtomicBoolean(false);

  private BigInteger cps_all = new BigInteger("0");
  private BigInteger cps_divisor = new BigInteger("0");

  {
    RTP.futures.add(done);
  }

  /**
   * Constructor for FillTask
   *
   * @param region the region to fill
   * @param start the starting location index
   */
  public FillTask(Region region, long start) {
    this.region = region;
    this.start = start;

    if (fillIncrement.get() <= 0) {
      long cpu = Runtime.getRuntime().availableProcessors();
      fillIncrement.set(cpu * 1000 / 32);
    } else {
      // try for 5 seconds between messages
      fillIncrement.set(cps.get() * 5);
    }
  }

  /**
   * Constructor for FillTask with additional parameters for performance tracking
   *
   * @param region the region to fill
   * @param start the starting location index
   * @param cps_all total completions per second
   * @param divisor divisor for performance tracking
   */
  public FillTask(Region region, long start, BigInteger cps_all, BigInteger divisor) {
    this.region = region;
    this.start = start;
    this.cps_all = cps_all;
    this.cps_divisor = divisor;

    if (fillIncrement.get() <= 0) {
      long cpu = Runtime.getRuntime().availableProcessors();
      fillIncrement.set(cpu * 10000 / 64);
    } else {
      // try for 5 seconds between messages
      fillIncrement.set(cps.get() * 5);
    }
  }

  /** Stop all running fill tasks */
  public static void kill() {
    RTP.getInstance().fillTasks.forEach((s, fillTask) -> fillTask.setCancelled(true));
    RTP.getInstance().fillTasks.clear();
  }

  @Override
  public void run() {
    if (!isRunning.compareAndSet(false, true)) return;
    if (pause.get() || isCancelled() || fillIncrement.get() <= 0) {
      isRunning.set(false);
      return;
    }

    long timingStart = System.currentTimeMillis();

    MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
    VerticalAdjustor<?> vert = region.getVert();
    if (vert == null) {
      isRunning.set(false);
      return;
    }

    ConfigParser<PerformanceKeys> performance =
        (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
    ConfigParser<SafetyKeys> safety =
        (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
    Object o;
    o = safety.getConfigValue(SafetyKeys.biomeWhitelist, false);
    boolean whitelist = (o instanceof Boolean) ? (Boolean) o : Boolean.parseBoolean(o.toString());

    o = safety.getConfigValue(SafetyKeys.biomes, new ArrayList<String>());
    if (!(o instanceof List<?>)) {
      new IllegalArgumentException(
              "expected list for biomes in safety.yml, received - " + o.getClass().getSimpleName())
          .printStackTrace();
      safety.set(SafetyKeys.biomes, new ArrayList<String>());
    }

    List<?> objList = (o instanceof List) ? ((List<?>) o) : new ArrayList<String>();
    Set<String> biomeSet =
        objList.stream().map(o2 -> o2.toString().toUpperCase()).collect(Collectors.toSet());
    Set<String> defaultBiomes;
    if (whitelist) {
      defaultBiomes = biomeSet;
    } else {
      Set<String> biomes = RTP.serverAccessor.getBiomes(region.getWorld());
      Set<String> set = new HashSet<>();
      for (String s : biomes) {
        if (!biomeSet.contains(s.toUpperCase())) {
          set.add(s);
        }
      }
      defaultBiomes = set;
    }

    o = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
    Set<String> unsafeBlocks =
        (o instanceof Collection)
            ? ((Collection<?>) o)
                .stream().map(o1 -> o1.toString().toUpperCase()).collect(Collectors.toSet())
            : new HashSet<>();

    int safetyRadius = safety.getNumber(SafetyKeys.safetyRadius, 0).intValue();

    boolean biomeRecall =
        Boolean.parseBoolean(
            performance.getConfigValue(PerformanceKeys.biomeRecall, false).toString());

    RTPWorld world = region.getWorld();
    WorldBorder border = (WorldBorder) RTP.serverAccessor.getWorldBorder(world.name());

    long range = Double.valueOf(shape.getRange()).longValue();
    long pos;
    long limit = fillIncrement.get();
    MutableRTPCoords cursor = new MutableRTPCoords(0, 0);
    cursor.setWorldName(region.getWorld().name());

    java.util.HashSet<Long> testedChunks = new HashSet<>();
    for (pos = start; pos < range && pos < start + limit; pos++) {
      if (pause.get() || isCancelled()) {
        isRunning.set(false);
        return;
      }

      shape.locationToXZ(pos, cursor);
      long chunkKey = ((long) (cursor.x >> 4) & 0xFFFFFFFFL) | (((long) (cursor.z >> 4) & 0xFFFFFFFFL) << 32);
      if (!testedChunks.add(chunkKey)) {
        continue;
      }
      if (shape.isKnownBad(cursor)) {
        continue;
      }

      CompletableFuture<Boolean> future = testPos(region, pos, cursor.x, cursor.z, safetyRadius, unsafeBlocks, defaultBiomes, biomeRecall, border);

      try {
        Boolean valid = future.join();
        if (valid) {
          completionCounter.incrementAndGet();
        }
      } catch (CancellationException e) {
        done.complete(false);
      } catch (CompletionException e) {
        RTP.log(Level.WARNING, e.getMessage(), e);
      }
    }

    final long finalPos1 = pos;
    long completedChecks = finalPos1 - start;
    long dt = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - timingStart);
    if (dt <= 0) dt = 1;
    long cps_local = (long) (((double) completedChecks) / (dt));
    cps_all = cps_all.add(new BigInteger(String.valueOf(cps_local)));
    cps_divisor = cps_divisor.add(increment_big);
    cps.set((cps.get() * 7 / 8) + cps_local / 8);

    long numLoadsRemaining = range - finalPos1;
    if (numLoadsRemaining < 0 || numLoadsRemaining > range) numLoadsRemaining = 0;
    long cps_val = cps_all.divide(cps_divisor).longValue();
    if (cps_val <= 0) cps_val = 1;
    long estRemaining = numLoadsRemaining / cps_val;

    ConfigParser<MessagesKeys> langParser =
        (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    String msg = langParser.getConfigValue(MessagesKeys.fillStatus, "").toString();
    if (msg != null && !msg.isEmpty()) {
      long days = TimeUnit.SECONDS.toDays(estRemaining);
      long hours = TimeUnit.SECONDS.toHours(estRemaining) % 24;
      long minutes = TimeUnit.SECONDS.toMinutes(estRemaining) % 60;
      long seconds = estRemaining % 60;

      String replacement = "";
      if (days > 0)
        replacement += days + langParser.getConfigValue(MessagesKeys.days, "").toString() + " ";
      if (hours > 0)
        replacement += hours + langParser.getConfigValue(MessagesKeys.hours, "").toString() + " ";
      if (minutes > 0)
        replacement +=
            minutes + langParser.getConfigValue(MessagesKeys.minutes, "").toString() + " ";
      if (seconds > 0)
        replacement += seconds + langParser.getConfigValue(MessagesKeys.seconds, "").toString();

      msg = msg.replace("[chunks]", String.valueOf(finalPos1));
      msg = msg.replace("[totalChunks]", String.valueOf(range));
      msg = msg.replace("[cps]", String.valueOf(cps_local));
      msg = msg.replace("[eta]", replacement);
      msg = msg.replace("[region]", region.name);

      RTP.serverAccessor.announce(msg, "rtp.fill");
    }

    shape.fillIter.set(finalPos1);
    shape.save(region.name, region.getWorld().name());
    region.getWorld().save();

    start = finalPos1;
    if (finalPos1 < range && !isCancelled() && !pause.get()) {
      if (RTP.getInstance().fillTasks.get(region.name) == this) {
        RTP.scheduler.runTaskAsynchronously(this);
      }
    } else if (finalPos1 >= range) {
      RTP.getInstance().fillTasks.remove(region.name, this);
      done.complete(true);
    }
    isRunning.set(false);
  }

  /**
   * Test if a location within a region is valid for teleportation
   *
   * @param region the region
   * @param pos the location index
   * @param blockX block x coordinate
   * @param blockZ block z coordinate
   * @param safetyRadius safety radius for chunk verification
   * @param unsafeBlocks set of unsafe block names
   * @param defaultBiomes set of allowed biomes
   * @param biomeRecall whether to record bad locations for biomes
   * @param border world border for the current world
   * @return a future that completes with true if the location is valid
   */
  public CompletableFuture<Boolean> testPos(
      Region region,
      final long pos,
      final int blockX,
      final int blockZ,
      int safetyRadius,
      Set<String> unsafeBlocks,
      Set<String> defaultBiomes,
      boolean biomeRecall,
      WorldBorder border) {
    int cx = blockX >> 4;
    int cz = blockZ >> 4;
    MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
    if (shape == null) return CompletableFuture.completedFuture(false);

    VerticalAdjustor<?> vert = region.getVert();
    if (vert == null) return CompletableFuture.completedFuture(false);

    RTPWorld world = region.getWorld();

    String currBiome =
        world.getBiome(blockX, (vert.maxY() + vert.minY()) / 2, blockZ);

    if (!defaultBiomes.contains(currBiome)) {
      if (biomeRecall) {
        shape.addBadLocation(pos, region.getSettings().spatialResolution());
        return CompletableFuture.completedFuture(false);
      }
    }

    if (!border
        .isInside()
        .apply(
            new RTPLocation(
                world, blockX, (vert.maxY() + vert.minY()) / 2, blockZ))) {
      shape.addBadLocation(pos, region.getSettings().spatialResolution());
      return CompletableFuture.completedFuture(false);
    }

    if (isCancelled() || pause.get()) {
      return CompletableFuture.completedFuture(false);
    }

    CompletableFuture<Long> cfChunk =
        RTP.serverAccessor
            .getChunkManager()
            .getChunkAtAsync(world, cx, cz)
            .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .exceptionally(
                ex -> {
                  return null;
                });

    CompletableFuture<Boolean> res = new CompletableFuture<>();
    cfChunk.thenAccept(
        chunkKey -> {
          if (chunkKey == null) {
            shape.addBadLocation(pos, region.getSettings().spatialResolution());
            res.complete(false);
            return;
          }
          if (isCancelled()) {
            res.complete(false);
            return;
          }
          RTPChunk<?> chunk = world.getCachedChunk(chunkKey);
          if (chunk == null) {
            res.complete(false);
            return;
          }

          chunk.keep(true);
          try {
            RTPChunk<?>[] localChunks = new RTPChunk[(safetyRadius * 2 + 1) * (safetyRadius * 2 + 1)];
            MutableRTPCoords localCursor = new MutableRTPCoords(blockX, blockZ);
            localCursor.setWorldName(world.name());
            try {
              if (!vert.adjust(chunk, localCursor)) {
                if (biomeRecall) shape.addBadLocation(pos, region.getSettings().spatialResolution());
                res.complete(false);
                return;
              }

              String currBiome1 = world.getBiome(localCursor.x, localCursor.y, localCursor.z);
              if (!defaultBiomes.contains(currBiome1)) {
                if (biomeRecall) {
                  shape.addBadLocation(pos, region.getSettings().spatialResolution());
                  res.complete(false);
                  return;
                }
              }

              boolean pass = localCursor.y < vert.maxY();
              if (!pass) {
                shape.addBadLocation(pos, region.getSettings().spatialResolution());
                res.complete(false);
                return;
              }

              // todo: waterlogged check
              int centerChunkX = chunk.x();
              int centerChunkZ = chunk.z();
              int L = safetyRadius * 2 + 1;
              localChunks[safetyRadius * L + safetyRadius] = chunk;
              chunk.keep(true);
              try {
                for (int x = localCursor.x - safetyRadius; x <= localCursor.x + safetyRadius && pass; x++) {
                  int chunkX = x >> 4;
                  int xx = x & 15;
                  int dcX = chunkX - centerChunkX;

                  for (int z = localCursor.z - safetyRadius; z <= localCursor.z + safetyRadius && pass; z++) {
                    int chunkZ = z >> 4;
                    int zz = z & 15;
                    int dcZ = chunkZ - centerChunkZ;

                    int index = (dcX + safetyRadius) * L + (dcZ + safetyRadius);
                    RTPChunk<?> chunk1 = localChunks[index];
                    if (chunk1 == null) {
                      long neighborKey =
                          ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
                      chunk1 = region.getWorld().getCachedChunk(neighborKey);
                      if (chunk1 == null) {
                        pass = false;
                        break;
                      }
                      localChunks[index] = chunk1;
                      chunk1.keep(true);
                    }

                    for (int y = localCursor.y - safetyRadius; y <= localCursor.y + safetyRadius && pass; y++) {
                      if (!chunk1.isSafe(xx, y, zz, unsafeBlocks)) {
                        pass = false;
                      }
                    }
                  }
                }
              } finally {
                for (int i = 0; i < localChunks.length; i++) {
                  if (localChunks[i] != null) {
                    localChunks[i].keep(false);
                    localChunks[i] = null;
                  }
                }
              }

              if (isCancelled()) {
                res.complete(false);
                return;
              }

              if (pass) pass = GlobalRegionVerifiers.checkGlobalRegionVerifiers(localCursor);

              if (pass) {
                if (biomeRecall) shape.addBiomeLocation(pos, region.getSettings().spatialResolution(), currBiome1);
                res.complete(true);
              } else {
                shape.addBadLocation(pos, region.getSettings().spatialResolution());
                res.complete(false);
              }
            } finally {
              chunk.keep(false);
            }
          } catch (Exception e) {
            res.complete(false);
          }
        });
    return res;
  }

  @Override
  public void setCancelled(boolean cancelled) {
    if (cancelled) {
      try {
        done.cancel(true);
      } catch (CancellationException | CompletionException ignored) {

      }
    }
    super.setCancelled(cancelled);
  }
}
