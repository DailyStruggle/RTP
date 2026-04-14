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
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

  // Instance trackers to natively isolate data per FillTask
  public long latestAbsolutePos = 0;
  public long latestAbsoluteTotal = 0;
  public long latestCps = 0;
  public long latestEtaSeconds = 0;

  private final Region region;
  private final AtomicLong fillIter;
  private final CompletableFuture<Boolean> done = new CompletableFuture<>();
  private long currentOffset = 0L;

  /** Whether the task is currently paused */
  public AtomicBoolean pause = new AtomicBoolean(false);

  private static final int MAX_PENDING_CHUNKS = 50;
//  private final AtomicLong pendingChunks = new AtomicLong();

  private long lastSaveTime = 0;

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
    this.fillIter = new AtomicLong(start);
    long[] progress = loadProgress(region.name);
    if (progress != null) {
      if (progress.length > 3) this.currentOffset = progress[3];
    }

    if (fillIncrement.get() <= 0) {
      long cpu = Runtime.getRuntime().availableProcessors();
      fillIncrement.set(cpu * 1000 / 32);
    } else {
      // try for 5 seconds between messages
      fillIncrement.set(cps.get() * 5);
    }

    if(start == 0) {
      if (region.shape instanceof MemoryShape<?> memoryShape) {
        memoryShape.clear();
      }
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
    this.fillIter = new AtomicLong(start);
    this.cps_all = cps_all;
    this.cps_divisor = divisor;
    long[] progress = loadProgress(region.name);
    if (progress != null) {
      if (progress.length > 3) this.currentOffset = progress[3];
    }

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
    if (trackingId != null) {
      io.github.dailystruggle.rtp.common.tools.MemoryTracker.updateTracking(trackingId);
    }

    if (!isRunning.compareAndSet(false, true)) return;
    if (pause.get() || isCancelled() || fillIncrement.get() <= 0) {
      if (pause.get() || isCancelled()) {
        save();
        if (isCancelled()) {
          RTP.getInstance().fillTasks.remove(region.name, this);
          done.complete(true);
        }
      }
      isRunning.set(false);
      return;
    }

    long timingStart = System.currentTimeMillis();

    MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
    VerticalAdjustor<?> vert = region.getVert();
    if (vert == null) {
      RTP.log(Level.WARNING, "null vert");
      isRunning.set(false);
      return;
    }

    ConfigParser<PerformanceKeys> performance =
            (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
    ConfigParser<SafetyKeys> safety =
            (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);

    Object o = safety.getConfigValue(SafetyKeys.biomeWhitelist, false);
    boolean whitelist = (o instanceof Boolean) ? (Boolean) o : Boolean.parseBoolean(o.toString());

    o = safety.getConfigValue(SafetyKeys.biomes, new ArrayList<String>());
    if (!(o instanceof List<?>)) {
      new IllegalArgumentException(
              "expected list for biomes in safety.yml, received - " + o.getClass().getSimpleName())
              .printStackTrace();
      safety.set(SafetyKeys.biomes, new ArrayList<String>());
    }

    List<?> objList = (o instanceof List) ? ((List<?>) o) : new ArrayList<String>();
    Set<String> biomeSet = objList.stream().map(o2 -> o2.toString().toUpperCase()).collect(Collectors.toSet());
    Set<String> defaultBiomes;

    if (whitelist) {
      defaultBiomes = biomeSet;
    } else {
      Set<String> biomes = RTP.serverAccessor.getBiomes(region.getWorld());
      Set<String> set = new HashSet<>();
      for (String s : biomes) {
        if (!biomeSet.contains(s.toUpperCase())) {
          set.add(s.toUpperCase());
        }
      }
      defaultBiomes = set;
    }

    o = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
    Set<String> unsafeBlocks =
            (o instanceof Collection)
                    ? ((Collection<?>) o).stream().map(o1 -> o1.toString().toUpperCase()).collect(Collectors.toSet())
                    : new HashSet<>();

    int safetyRadius = safety.getNumber(SafetyKeys.safetyRadius, 0).intValue();
    boolean biomeRecall = Boolean.parseBoolean(performance.getConfigValue(PerformanceKeys.biomeRecall, false).toString());

    RTPWorld world = region.getWorld();
    WorldBorder border = (WorldBorder) RTP.serverAccessor.getWorldBorder(world.name());

    long range = Double.valueOf(shape.getRange()).longValue();
    long pos;
    long limit = fillIncrement.get();
    long stride = Math.max(1L, shape.spatialResolution);
    long currentStart = fillIter.get();
    if (currentStart == 0) {
      currentStart = currentOffset;
    }
    long limitEnd = currentStart + (limit * stride);
    MutableRTPCoords cursor = new MutableRTPCoords(0, 0);
    cursor.setWorldName(region.getWorld().name());

    long activeChecks = 0;

    // 1. Initialize the sliding window gate
    Semaphore inFlightGate = new Semaphore(MAX_PENDING_CHUNKS);

    for (pos = currentStart; pos < range && pos < limitEnd; pos += stride) {
      if (pause.get() || isCancelled()) {
        break;
      }

      if (shape.isKnownBad(pos)) {
        // Temporarily disabled: Force re-evaluation of locations falsely marked bad by the void bug
        // continue;
      }

      // 2. Throttle the loop natively.
      try {
        inFlightGate.acquire();
      } catch (InterruptedException e) {
        break;
      }

      shape.locationToXZ(pos, cursor);
      int centerBlockX = (cursor.x << 4) + 8;
      int centerBlockZ = (cursor.z << 4) + 8;
      final long currentPos = pos;
      activeChecks++;

      try {
        CompletableFuture<Boolean> posFuture = testPos(region, currentPos, centerBlockX, centerBlockZ, safetyRadius, unsafeBlocks, defaultBiomes, biomeRecall, border);

        // 3. Release the permit on asynchronous completion
        posFuture.whenComplete((res, err) -> inFlightGate.release());
      } catch (Exception e) {
        // Failsafe: Release the permit instantly if a synchronous error occurs
        inFlightGate.release();
        shape.addBadLocation(currentPos);
        RTP.log(Level.WARNING, "Synchronous calculation failure at " + currentPos, e);
      }
    }

    final long finalPos1 = pos;
    final long finalActiveChecks = activeChecks;

    // 4. Drain the gate to wait for the final trailing chunks of the iteration to complete
    try {
      inFlightGate.acquire(MAX_PENDING_CHUNKS);
      inFlightGate.release(MAX_PENDING_CHUNKS);
    } catch (InterruptedException ignored) {}

    // 5. Execute synchronously on the async thread, avoiding detached callback contexts
    wrapUpBatch(finalPos1, finalActiveChecks, timingStart, range, shape, limitEnd);
  }

  private void wrapUpBatch(long finalPos1, long activeChecks, long timingStart, long range, MemoryShape<?> shape, long limitEnd) {
    if (activeChecks > 0) {
      long dtMillis = System.currentTimeMillis() - timingStart;
      if (dtMillis <= 0) dtMillis = 1;
      long cps_local = (activeChecks * 1000L) / dtMillis;
      cps_all = cps_all.add(new BigInteger(String.valueOf(cps_local)));
      cps_divisor = cps_divisor.add(increment_big);
      cps.set((cps.get() * 7 / 8) + cps_local / 8);

      long etaSeconds = getEtaSeconds(range, finalPos1, shape);

      // --- NEW LAND PERCENTAGE CALCULATION ---
      long good = shape.getEffectiveGoodCount();
      long bad = shape.getEffectiveBadCount();
      double totalEvaluated = (double) good + bad;
      double landPercentage = (totalEvaluated > 0) ? (good * 100.0 / totalEvaluated) : 0.0;
      // ---------------------------------------

      this.latestAbsolutePos = ((currentOffset * range) + finalPos1) / Math.max(1L, shape.spatialResolution);
      this.latestAbsoluteTotal = range;
      this.latestCps = cps_local;
      this.latestEtaSeconds = etaSeconds;

      long now = System.currentTimeMillis();
      if (now - lastSaveTime > 5000 || finalPos1 >= range || pause.get() || isCancelled()) {
        lastSaveTime = now;

        ConfigParser<MessagesKeys> langParser = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        String msg = langParser.getConfigValue(MessagesKeys.fillStatus, "").toString();

        if (msg != null && !msg.isEmpty()) {
          // Replace the placeholder with the formatted number
          if (msg.contains("[fill_landPercentage]")) {
            msg = msg.replace("[fill_landPercentage]", String.format("%.2f", landPercentage));
          }
          RTP.serverAccessor.announce(msg, "rtp.fill", "");
        }

        save();
        shape.save(region.name, region.getWorld().name());
      }
    }

    fillIter.set(finalPos1);

    if (finalPos1 >= range) {
      if (currentOffset < Math.max(1L, shape.spatialResolution) - 1) {
        currentOffset++;
        fillIter.set(0);
        save();
        shape.save(region.name, region.getWorld().name());
        shape.exportDebugJson(region.name, region.getWorld().name());
        isRunning.set(false);
        shape.flushAndRebuild(shape.spatialResolution);
        if (!isCancelled() && !pause.get()) {
          RTP.scheduler.runTaskAsynchronously(this);
        }
        return;
      }

      save(); // Ensure final pass is securely flushed before deletion
      RTP.getInstance().fillTasks.remove(region.name, this);
      delete();
      done.complete(true);
      super.setCancelled(true);
      isRunning.set(false);
    } else if (!isCancelled() && !pause.get()) {
      if (RTP.getInstance().fillTasks.get(region.name) == this) {
        isRunning.set(false);
        shape.flushAndRebuild(shape.spatialResolution);
        RTP.scheduler.runTaskAsynchronously(this);
      } else {
        isRunning.set(false);
      }
    } else {
      isRunning.set(false);
      if (isCancelled()) {
        RTP.getInstance().fillTasks.remove(region.name, this);
        done.complete(true);
      }
    }
  }

  private long getEtaSeconds(long range, long finalPos1, MemoryShape<?> shape) {
    long totalRemainingPoints = (range - finalPos1) + (Math.max(0, shape.spatialResolution - 1 - currentOffset) * range);
    if (totalRemainingPoints < 0) totalRemainingPoints = 0;
    long effectiveBad = shape.getEffectiveBadCount();
    long totalEvaluated = shape.getEffectiveGoodCount() + effectiveBad;
    double badDensity = (double) effectiveBad / (double) Math.max(1, totalEvaluated);
    long estimatedActivePointsRemaining = (long) (totalRemainingPoints * (1.0 - badDensity));

    long currentPointsPerSecond = cps_all.divide(cps_divisor).longValue();
    if (currentPointsPerSecond <= 0) currentPointsPerSecond = 1;
    return estimatedActivePointsRemaining / currentPointsPerSecond;
  }

  public void save() {
    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    File dir = new File(pluginDir, "database" + File.separator + "regionData");
    if (!dir.exists()) dir.mkdirs();
    File file = new File(dir, region.name + ".fill");

    try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
      ByteBuffer buf = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN);
      buf.putLong(fillIter.get());
      Shape<?> shape = region.getShape();
      if (shape instanceof MemoryShape<?> memoryShape) {buf.putLong(memoryShape.spatialResolution);}
      buf.putLong(currentOffset);
      buf.put((byte) 0);
      out.write(buf.array());
    } catch (java.io.IOException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  public static long[] loadProgress(String regionName) {
    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    File file =
        new File(
            pluginDir,
            "database" + File.separator + "regionData" + File.separator + regionName + ".fill");
    if (!file.exists()) return null;

    try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
      byte[] bytes = new byte[25];
      int read = in.read(bytes);
      if (read >= 16) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        long iter = buf.getLong();
        long stride = buf.getLong();
        long offset = 0;
        long isFine = 0;
        if (read >= 24) {
          offset = buf.getLong();
          if (read >= 25) {
            isFine = bytes[24] & 0xFF;
          }
        } else if (read == 17) {
          isFine = bytes[16] & 0xFF;
        } else if (stride == 1) {
          isFine = 1;
        }
        return new long[] {iter, stride, isFine, offset, 0L, 0L};
      }
    } catch (java.io.IOException e) {
      // ignore
    }
    return null;
  }

  public void delete() {
    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    File file =
        new File(
            pluginDir,
            "database" + File.separator + "regionData" + File.separator + region.name + ".fill");
    if (file.exists()) file.delete();
  }

  public static void delete(String regionName) {
    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    File file =
        new File(
            pluginDir,
            "database" + File.separator + "regionData" + File.separator + regionName + ".fill");
    if (file.exists()) file.delete();
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

    try {
      safetyRadius = Math.min(safetyRadius, 7);

      int cx = blockX >> 4;
      int cz = blockZ >> 4;
      MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
      if (shape == null) return CompletableFuture.completedFuture(false);

      if(shape.isKnownBad(pos)) { return CompletableFuture.completedFuture(false); }

      VerticalAdjustor<?> vert = region.getVert();
      if (vert == null) return CompletableFuture.completedFuture(false);

      RTPWorld<?> world = region.getWorld();

      String midBiome = world.getBiome(blockX, (vert.maxY() + vert.minY()) / 2, blockZ).toUpperCase();
      if (!defaultBiomes.contains(midBiome)) {
        shape.addBadLocation(pos);
        if (biomeRecall) shape.addBiomeLocation(pos, 1, midBiome);
        return CompletableFuture.completedFuture(false);
      }

      // Fast mathematical rejection ONLY for World Border.
      // Mathematical biome check is completely removed to force chunk generation.
      if (!border.isInside().apply(new RTPLocation(world, blockX, (vert.maxY() + vert.minY()) / 2, blockZ))) {
        shape.addBadLocation(pos);
        return CompletableFuture.completedFuture(false);
      }

      if (isCancelled() || pause.get()) {
        return CompletableFuture.completedFuture(false);
      }

      CompletableFuture<Boolean> res = new CompletableFuture<>();
      int finalSafetyRadius = safetyRadius;

      // Unconditional chunk generation request
      world.getChunkAt(cx, cz)
              .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
              .whenComplete((chunkKey, throwable) -> {
                try {
                  if (throwable != null || chunkKey == null || isCancelled() || pause.get()) {
                    if (throwable != null) {
                      RTP.log(Level.WARNING, "[FillTask] Chunk generation exception at " + pos, throwable);
                    } else if (chunkKey == null) {
                      RTP.log(Level.WARNING, "[FillTask] INSTANT BYPASS: Chunk manager returned a null chunkKey for " + pos);
                    } else {
                        if (!isCancelled() && !pause.get()) {
                          RTP.log(Level.WARNING, "[FillTask] undetermined failure at " + pos);
                        }
                    }
                      res.complete(false);
                    return;
                  }

                  RTPLocation targetLoc = new RTPLocation(world, blockX, 0, blockZ);
                  RTP.serverAccessor.getScheduler().runTask(targetLoc, () -> {
                    try {
                      RTPChunk<?> chunk = world.getCachedChunk(chunkKey);
                      if (chunk == null) {
                        shape.addBadLocation(pos);
                        res.complete(false);
                        return;
                      }

                      MutableRTPCoords localCursor = new MutableRTPCoords(blockX, blockZ);
                      localCursor.setWorldName(world.name());

                      if (!vert.adjust(chunk, localCursor)) {
                        shape.addBadLocation(pos);
                        res.complete(false);
                        return;
                      }

                      // PHYSICAL BIOME CHECK: Evaluated directly from the generated 3D block array
                      String actualBiome = world.getBiome(localCursor.x, localCursor.y, localCursor.z);

                      if (biomeRecall) shape.addBiomeLocation(pos, 1, actualBiome);

                      if (!defaultBiomes.contains(actualBiome.toUpperCase())) {
                        shape.addBadLocation(pos);
                        res.complete(false);
                        return;
                      }

                      boolean pass = localCursor.y < vert.maxY();
                      if (!pass) {
                        shape.addBadLocation(pos);
                        res.complete(false);
                        return;
                      }

                      int localX = localCursor.x & 15;
                      int localZ = localCursor.z & 15;

                      int minX = Math.max(0, localX - finalSafetyRadius);
                      int maxX = Math.min(15, localX + finalSafetyRadius);
                      int minZ = Math.max(0, localZ - finalSafetyRadius);
                      int maxZ = Math.min(15, localZ + finalSafetyRadius);

                      for (int xx = minX; xx <= maxX && pass; xx++) {
                        for (int zz = minZ; zz <= maxZ && pass; zz++) {
                          for (int y = localCursor.y - finalSafetyRadius; y <= localCursor.y + finalSafetyRadius && pass; y++) {
                            if (!chunk.isSafe(xx, y, zz, unsafeBlocks)) {
                              pass = false;
                            }
                          }
                        }
                      }

                      if (isCancelled() || pause.get()) {
                        res.complete(false);
                        return;
                      }

                      if (pass) pass = GlobalRegionVerifiers.checkGlobalRegionVerifiers(localCursor).join();

                      if (pass) {
                        res.complete(true);
                      } else {
                        shape.addBadLocation(pos);
                        res.complete(false);
                      }
                    } catch (Throwable t) {
                      RTP.log(Level.SEVERE, "[FillTask] Validation crashed on Region Thread at location " + pos, t);
                      shape.addBadLocation(pos);
                      res.complete(false);
                    }
                  });
                } catch (Throwable t) {
                  RTP.log(Level.SEVERE, "[FillTask] Async callback crashed at location " + pos, t);
                  shape.addBadLocation(pos);
                  res.complete(false);
                }
              });

      return res;

    } catch (Throwable t) {
      RTP.log(Level.SEVERE, "[FillTask] Synchronous abort at testPos for location " + pos, t);
      return CompletableFuture.completedFuture(false);
    }
  }

  public void pause() {
    pause.set(true);
    MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
    shape.flushAndRebuild(shape.spatialResolution);
    save();
  }

  @Override
  public void setCancelled(boolean cancelled) {
    if (cancelled) {
      try {
        done.cancel(true);
      } catch (CancellationException | CompletionException ignored) { }
      MemoryShape<?> shape = (MemoryShape<?>) region.getShape();
      if (shape != null) {
        shape.flushAndRebuild(shape.spatialResolution);
      }
      save();
      RTP.getInstance().fillTasks.remove(region.name, this);
    }
    super.setCancelled(cancelled);
  }
}
