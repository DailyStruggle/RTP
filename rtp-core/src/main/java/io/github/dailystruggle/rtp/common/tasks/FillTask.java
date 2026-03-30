package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
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
  private final long start;
  private final CompletableFuture<Boolean> done = new CompletableFuture<>();
  private final AtomicLong completionCounter = new AtomicLong();
  private final Semaphore completionGuard = new Semaphore(1);
  private final List<CompletableFuture<Boolean>> chunks = new ArrayList<>();
  private final Semaphore testsGuard = new Semaphore(1);

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
    isRunning.set(true);
    if (pause.get() || isCancelled() || fillIncrement.get() <= 0) return;

    long timingStart = System.currentTimeMillis();

    MemoryShape<?> shape = (MemoryShape<?>) region.getShape();

    long range = Double.valueOf(shape.getRange()).longValue();
    long pos;
    long limit = fillIncrement.get();
    MutableRTPCoords cursor = new MutableRTPCoords(region.getWorld().name(), 0, 0, 0);
    for (pos = start; pos < range && pos < start + limit; pos++) {
      if (pause.get() || isCancelled()) {
        isRunning.set(false);
        return;
      }

      int[] select = shape.locationToXZ(pos);
      cursor.setXZ(select[0], select[1]);
      if (shape.isKnownBad(cursor)) {
        continue;
      }

      CompletableFuture<Boolean> future = testPos(region, pos, cursor);

      long finalPos = pos;
      future.thenAccept(
          aBoolean -> {
            if (isCancelled()) return;
            try {
              completionGuard.acquire();
              long l = completionCounter.incrementAndGet();
              if (finalPos == range - 1 || l == limit) {
                done.complete(true);
              }
            } catch (CancellationException e) {
              done.complete(false);
            } catch (InterruptedException | IllegalStateException e) {
              RTP.log(Level.WARNING, e.getMessage(), e);
              done.complete(false);
            } finally {
              completionGuard.release();
            }
          });

      chunks.add(future);
    }

    final long finalPos1 = pos;
    CompletableFuture.allOf(chunks.toArray(new CompletableFuture[0]))
        .thenRun(
            () -> {
              long completedChecks = finalPos1 - start;
              long dt = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - timingStart);
              if (dt <= 0) dt = 1;
              long cps_local = (long) (((double) completedChecks) / (dt));
              cps_all = cps_all.add(new BigInteger(String.valueOf(cps_local)));
              cps_divisor = cps_divisor.add(increment_big);
              cps.set((cps.get() * 7 / 8) + cps_local / 8);

              long numLoadsRemaining = range - finalPos1;
              if (numLoadsRemaining < 0 || numLoadsRemaining > range) numLoadsRemaining = 0;
              long estRemaining = numLoadsRemaining / cps_all.divide(cps_divisor).longValue();

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
                  replacement +=
                      days + langParser.getConfigValue(MessagesKeys.days, "").toString() + " ";
                if (hours > 0)
                  replacement +=
                      hours + langParser.getConfigValue(MessagesKeys.hours, "").toString() + " ";
                if (minutes > 0)
                  replacement +=
                      minutes + langParser.getConfigValue(MessagesKeys.minutes, "").toString() + " ";
                if (seconds > 0)
                  replacement +=
                      seconds + langParser.getConfigValue(MessagesKeys.seconds, "").toString();

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

              if (finalPos1 < range && !isCancelled() && !pause.get()) {
                RTP.getInstance()
                    .fillTasks
                    .put(region.name, new FillTask(region, finalPos1, cps_all, cps_divisor));
              } else RTP.getInstance().fillTasks.remove(region.name);
              isRunning.set(false);
            });
  }

  /**
   * Test if a location within a region is valid for teleportation
   *
   * @param region the region
   * @param pos the location index
   * @param cursor mutable coordinates cursor
   * @return a future that completes with true if the location is valid
   */
  public CompletableFuture<Boolean> testPos(Region region, final long pos, MutableRTPCoords cursor) {
    Set<String> defaultBiomes;

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

    MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
    if (shape == null) return CompletableFuture.completedFuture(false);

    VerticalAdjustor<?> vert = region.getVert();
    if (vert == null) return CompletableFuture.completedFuture(false);

    o = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
    Set<String> unsafeBlocks =
        (o instanceof Collection)
            ? ((Collection<?>) o)
                .stream().map(o1 -> o1.toString().toUpperCase()).collect(Collectors.toSet())
            : new HashSet<>();

    int safetyRadius = safety.getNumber(SafetyKeys.safetyRadius, 0).intValue();

    RTPWorld world = region.getWorld();

    boolean biomeRecall =
        Boolean.parseBoolean(
            performance.getConfigValue(PerformanceKeys.biomeRecall, false).toString());

    String currBiome =
        world.getBiome(cursor.x * 16 + 7, (vert.maxY() + vert.minY()) / 2, cursor.z * 16 + 7);

    if (!defaultBiomes.contains(currBiome)) {
      if (biomeRecall) {
        shape.addBadLocation(pos);
        return CompletableFuture.completedFuture(false);
      }
    }

    WorldBorder border = (WorldBorder) RTP.serverAccessor.getWorldBorder(world.name());
    if (!border
        .isInside()
        .apply(
            new RTPLocation(
                world, cursor.x * 16, (vert.maxY() + vert.minY()) / 2, cursor.z * 16))) {
      shape.addBadLocation(pos);
      return CompletableFuture.completedFuture(false);
    }

    if (isCancelled() || pause.get()) {
      return CompletableFuture.completedFuture(false);
    }

    CompletableFuture<Long> cfChunk =
        RTP.serverAccessor.getChunkManager().getChunkAtAsync(world, cursor.x, cursor.z);

    CompletableFuture<Boolean> res = new CompletableFuture<>();
    cfChunk.thenAccept(
        chunkKey -> {
          if (chunkKey == null || isCancelled()) {
            res.complete(false);
            return;
          }
          RTPChunk<?> chunk = world.getCachedChunk(chunkKey);
          if (chunk == null) {
            res.complete(false);
            return;
          }
          RTPCoords coords = vert.adjust(chunk);
          if (coords == null) {
            if (biomeRecall) shape.addBadLocation(pos);
            res.complete(false);
            chunk.unload();
            return;
          }

          String currBiome1 = world.getBiome(coords.x(), coords.y(), coords.z());
          if (!defaultBiomes.contains(currBiome1)) {
            if (biomeRecall) {
              shape.addBadLocation(pos);
              res.complete(false);
              chunk.unload();
              return;
            }
          }

          boolean pass = coords.y() < vert.maxY();
          if (!pass) {
            shape.addBadLocation(pos);
            res.complete(false);
            chunk.unload();
            return;
          }

          // todo: waterlogged check
          RTPChunk<?> chunk1;
          Map<Long, RTPChunk<?>> chunks = new HashMap<>();
          long initialKey = ((long) chunk.x() & 0xFFFFFFFFL) | (((long) chunk.z() & 0xFFFFFFFFL) << 32);
          chunks.put(initialKey, chunk);
          chunk.keep(true);
          try {
            for (int x = coords.x() - safetyRadius; x < coords.x() + safetyRadius && pass; x++) {
              int chunkX = Math.floorDiv(x, 16);
              int xx = Math.floorMod(x, 16);

              for (int z = coords.z() - safetyRadius;
                  z < coords.z() + safetyRadius && pass;
                  z++) {
                int chunkZ = Math.floorDiv(z, 16);
                int zz = Math.floorMod(z, 16);

                long neighborKey = ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
                if (chunks.containsKey(neighborKey)) chunk1 = chunks.get(neighborKey);
                else {
                  chunk1 = region.getWorld().getCachedChunk(neighborKey);
                  if (chunk1 == null) {
                    pass = false;
                    break;
                  }
                  chunks.put(neighborKey, chunk1);
                  chunk1.keep(true);
                }

                for (int y = coords.y() - safetyRadius;
                    y < coords.y() + safetyRadius && pass;
                    y++) {
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

          try {
            if (isCancelled()) {
              res.complete(false);
              return;
            }

            if (pass) pass = GlobalRegionVerifiers.checkGlobalRegionVerifiers(coords);

            if (pass) {
              if (biomeRecall) shape.addBiomeLocation(pos, currBiome1);
              res.complete(true);
            } else {
              shape.addBadLocation(pos);
              res.complete(false);
            }
          } finally {
            chunk.unload();
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
      try {
        chunks.forEach(rtpChunkCompletableFuture -> rtpChunkCompletableFuture.cancel(true));
      } catch (CancellationException | CompletionException ignored) {

      }
    }
    super.setCancelled(cancelled);
  }
}
