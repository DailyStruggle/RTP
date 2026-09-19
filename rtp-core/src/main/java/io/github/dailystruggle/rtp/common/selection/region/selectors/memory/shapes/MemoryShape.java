package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * Abstract base class for memory-backed shapes storing bad and biome locations.
 * Uses Archimedean spiral 1D mapping for efficient spatial lookups.
 *
 * @param <E> enum type for configuration values
 */
public abstract class MemoryShape<E extends Enum<E>> extends Shape<E> {
  /**
   * Coalescing distance applied when new marks are folded into the learned bad-run array.
   *
   * <p>Sourced from the owning region's {@code spatialResolution} setting: it describes how
   * compactly that region stores its learned state, not the shape's geometry - it has no effect on
   * {@link #xzToLocation(long, long)}, {@link #locationToXZ(long)} or {@link #getRange()}. It is
   * therefore deliberately absent from the region cache key (see
   * {@code RegionCacheKey}): a run array coalesced at a coarser resolution stays a valid, merely
   * conservative, superset when read back under a finer one, so changing the setting does not
   * invalidate persisted data.
   *
   * <p>Encapsulated rather than public because the value has exactly one source (region config)
   * and several consumers; write it through {@link #setSpatialResolution(long)} so the assignment
   * sites stay auditable.
   */
  private volatile long spatialResolution = 1L;

  protected volatile long[] badKeysCache = new long[0];
  protected volatile long[] badPrefixSumsCache = new long[0];
  /**
   * Per-run rejection cause, aligned 1:1 with {@link #badKeysCache} (one
   * {@link LocationGenerator.FailTypes} ordinal, stored as a byte, per coalesced
   * bad-location run). When adjacent runs coalesce during rebuild the first run's
   * cause is kept (first-cause-wins; small-scale information loss is acceptable).
   * Legacy {@code .bin} files and untagged callers default to {@code misc}.
   */
  protected volatile byte[] badCauseCache = new byte[0];
  /**
   * Per-run expiration timestamp in unix epoch seconds, aligned 1:1 with {@link #badKeysCache}.
   * Values <= 0 indicate static / permanent retention (infinite TTL).
   */
  protected volatile long[] badExpiryCache = new long[0];

  /**
   * Sorted dual-arrays for the probationary tier (ADR-079). Holds expired segments
   * where {@code 1 * TTL <= now < 2 * TTL}. Bypasses active candidate avoidance,
   * but restored in O(log M) if candidate verification re-rejects them.
   */
  protected volatile long[] probationKeysCache = new long[0];
  protected volatile long[] probationPrefixSumsCache = new long[0];
  protected volatile byte[] probationCauseCache = new byte[0];
  protected volatile long[] probationExpiryCache = new long[0];

  /** {@code FailTypes.misc} ordinal as a byte: the default / unknown cause. */
  protected static final byte MISC_CAUSE = (byte) LocationGenerator.FailTypes.misc.ordinal();

  /**
   * First int of a {@code .bin} payload written with the cause-tagged format.
   * Distinguishable from a legacy file whose first int is the (small, positive)
   * world-name byte length. Spells "RTP1" big-endian.
   */
  private static final int BIN_MAGIC = 0x52545031;
  /** Current cause-tagged {@code .bin} format version (1 == legacy, 2 == cause-tagged, 3 == ttl/epoch-tagged). */
  private static final int BIN_VERSION = 3;
  protected volatile ConcurrentHashMap<String, long[]> biomeKeysCache = new ConcurrentHashMap<>();
  protected volatile ConcurrentHashMap<String, long[]> biomePrefixSumsCache =
      new ConcurrentHashMap<>();
  protected volatile long[] biomeMappedKeysCache = new long[0];
  protected volatile long[] biomeMappedPrefixSumsCache = new long[0];

  protected volatile boolean badLocationsDirty = true;
  protected volatile boolean biomeLocationsDirty = true;
  protected final java.util.concurrent.atomic.AtomicBoolean isRebuilding =
      new java.util.concurrent.atomic.AtomicBoolean(false);
  protected final java.util.concurrent.locks.ReentrantLock writeLock = new ReentrantLock();
  protected final AtomicLong scanStride = new AtomicLong(-1L);
  private volatile CompletableFuture<Void> loadFuture = CompletableFuture.completedFuture(null);
  private final AtomicLong totalBadCount = new AtomicLong(0L);
  private final AtomicLong totalBiomeCount = new AtomicLong(0L);

  protected volatile ConcurrentHashMap<Long, Long> rebuildingBadLocations = null;

  protected final java.util.concurrent.atomic.AtomicReference<
          java.util.concurrent.ConcurrentHashMap<Long, Long>>
      pendingBadLocations =
          new java.util.concurrent.atomic.AtomicReference<>(
              new java.util.concurrent.ConcurrentHashMap<>());
  protected final java.util.concurrent.atomic.AtomicReference<
          java.util.concurrent.ConcurrentHashMap<
              String, java.util.concurrent.ConcurrentHashMap<Long, Long>>>
      pendingBiomeLocations =
          new java.util.concurrent.atomic.AtomicReference<>(
              new java.util.concurrent.ConcurrentHashMap<>());

  protected final java.util.concurrent.atomic.AtomicReference<
          java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<Long, Boolean>>>
      pendingBiomeRemovals =
          new java.util.concurrent.atomic.AtomicReference<>(
              new java.util.concurrent.ConcurrentHashMap<>());

  /**
   * RNG source used by {@link #rand()} and all subclass overrides.
   *
   * <p>Defaults to {@link ThreadLocalRandom#current()} at call time, giving production behaviour
   * identical to before this field was introduced. Tests can inject a seeded {@link Random} via
   * {@link #setRng(Random)} to make location selection fully deterministic.
   */
  protected Random rng = null;

  /**
   * Returns the RNG to use for this shape. Falls back to {@link ThreadLocalRandom#current()} when
   * no explicit RNG has been set so that production code is unaffected.
   *
   * @return the active {@link Random} instance; never {@code null}
   */
  protected final Random rng() {
    return rng != null ? rng : ThreadLocalRandom.current();
  }

  /**
   * Injects a deterministic RNG. Intended for unit tests only.
   *
   * @param rng the {@link Random} instance to use; pass {@code null} to restore
   *     {@link ThreadLocalRandom} behaviour.
   */
  public final void setRng(Random rng) {
    this.rng = rng;
  }

  /**
   * Constructor for MemoryShape
   *
   * @param eClass - enum class to use
   * @param name - unique name of shape
   * @param data - default data
   * @throws IllegalArgumentException - in case of invalid inputs for expected types
   */
  public MemoryShape(Class<E> eClass, String name, EnumMap<E, Object> data)
      throws IllegalArgumentException {
    super(eClass, name, data);
  }

  /**
   * @return the active coalescing distance for learned bad runs; always the value last applied
   *     from the owning region's configuration, defaulting to {@code 1}
   */
  public long spatialResolution() {
    return spatialResolution;
  }

  /**
   * Apply the owning region's configured resolution.
   *
   * <p>Does not rewrite already-coalesced runs: a lower value only makes subsequent marks merge
   * less aggressively, and a higher one only makes them merge more. Operators wanting the finer
   * precision retroactively have to rescan.
   *
   * @param spatialResolution the configured value; clamped to at least {@code 1}
   */
  public void setSpatialResolution(long spatialResolution) {
    this.spatialResolution = Math.max(1L, spatialResolution);
  }


  /**
   * Get the range of the shape
   *
   * @return the range
   */
  public abstract long getRange();

  /**
   * Convert xz coordinates to a location value
   *
   * @param x the x coordinate
   * @param z the z coordinate
   * @return the location value
   */
  public abstract long xzToLocation(long x, long z);

  /**
   * Convert xz coordinates to a location value
   *
   * @param coords the coordinates
   * @return the location value
   */
  public abstract long xzToLocation(MutableRTPCoords coords);

  /**
   * Convert a location value to xz coordinates
   *
   * @param location the location value
   * @return an array containing x and z coordinates
   */
  public abstract int[] locationToXZ(long location);



  /**
   * Convert a location value to xz coordinates and store in the output object
   *
   * @param location the location value
   * @param output the output object
   */
  public abstract void locationToXZ(long location, MutableRTPCoords output);

  /**
   * Check if a location is known to be bad (e.g. invalid teleport target)
   *
   * @param x the x coordinate
   * @param z the z coordinate
   * @return true if known bad, false otherwise
   */
  public boolean isKnownBad(int x, int z) {
    return isKnownBad((long) xzToLocation(x, z));
  }

  /**
   * Check if a location is known to be bad
   *
   * @param coords the coordinates
   * @return true if known bad, false otherwise
   */
  public boolean isKnownBad(MutableRTPCoords coords) {
    return isKnownBad((long) xzToLocation(coords));
  }

  /**
   * Check if a location is known to be bad
   *
   * @param location the location value
   * @return true if known bad, false otherwise
   */
  public boolean isKnownBad(long location) {
    if (pendingBadLocations.get().containsKey(location)) return true;

    ConcurrentHashMap<Long, Long> rebuilding = rebuildingBadLocations;
    if (rebuilding != null && rebuilding.containsKey(location)) return true;

    long[] sums = badPrefixSumsCache;
    long[] keys = badKeysCache;
    if (keys.length == 0) return false;

    int floorIdx = floorRunIndex(keys, location);

    if (floorIdx >= 0 && floorIdx < sums.length) {
      long key = keys[floorIdx];
      if (key == location) return true;
      long sum = sums[floorIdx];
      long prevSum = (floorIdx > 0) ? sums[floorIdx - 1] : 0L;
      return location < (key + (sum - prevSum));
    }
    return false;
  }

  /**
   * Returns rejection cause for bad-location run containing {@code (x, z)}, or {@code -1}.
   * Non-negative return is a {@link LocationGenerator.FailTypes} ordinal.
   *
   * @param x the x coordinate
   * @param z the z coordinate
   * @return {@link LocationGenerator.FailTypes} ordinal, or {@code -1} if not known bad
   */
  public int causeAt(int x, int z) {
    return causeAt((long) xzToLocation(x, z));
  }

  /**
   * Returns rejection cause for bad-location run containing {@code location}, or {@code -1}.
   * Non-negative return is a {@link LocationGenerator.FailTypes} ordinal. Pending/rebuilding
   * entries with no resolved run cause read as {@code misc}.
   *
   * @param location the location value
   * @return {@link LocationGenerator.FailTypes} ordinal, or {@code -1} if not known bad
   */
  public int causeAt(long location) {
    if (pendingBadLocations.get().containsKey(location)) return MISC_CAUSE & 0xFF;

    ConcurrentHashMap<Long, Long> rebuilding = rebuildingBadLocations;
    if (rebuilding != null && rebuilding.containsKey(location)) return MISC_CAUSE & 0xFF;

    long[] sums = badPrefixSumsCache;
    long[] keys = badKeysCache;
    byte[] causes = badCauseCache;
    if (keys.length == 0) return -1;

    int floorIdx = floorRunIndex(keys, location);

    if (floorIdx >= 0 && floorIdx < sums.length) {
      long key = keys[floorIdx];
      long sum = sums[floorIdx];
      long prevSum = (floorIdx > 0) ? sums[floorIdx - 1] : 0L;
      boolean inRun = (key == location) || (location < (key + (sum - prevSum)));
      if (!inRun) return -1;
      return (floorIdx < causes.length) ? (causes[floorIdx] & 0xFF) : (MISC_CAUSE & 0xFF);
    }
    return -1;
  }

  /**
   * Index of the last run whose start key is {@code <= location}, or {@code -1} when every run
   * starts after it.
   *
   * <p>{@code badKeysCache} is produced sorted by the merge in {@link #flushAndRebuild}, so this
   * is a binary search. It used to be a forward linear scan, which made every {@link #isKnownBad}
   * and {@link #causeAt} call O(runs) - and {@link #addBadChunkRadius} calls {@code isKnownBad}
   * once per probed cell on the selection path.
   *
   * @param keys sorted run start keys (snapshot)
   * @param location the 1D index to locate
   * @return floor index, or {@code -1}
   */
  private static int floorRunIndex(long[] keys, long location) {
    int idx = Arrays.binarySearch(keys, location);
    if (idx >= 0) return idx;
    return -(idx + 1) - 1;
  }

  /**
   * Returns an immutable snapshot of current bad-location key array (sorted packed XZ keys).
   * Used by {@code BadPointsHeatmapResolver}. Volatile read without locking.
   *
   * @return fresh array copy; never {@code null}
   */
  public long[] badKeysSnapshot() {
    long[] keys = badKeysCache;
    return Arrays.copyOf(keys, keys.length);
  }

  /**
   * Returns an immutable snapshot of the per-run rejection-cause array, aligned
   * 1:1 with {@link #badKeysSnapshot()} (each element is a
   * {@link LocationGenerator.FailTypes} ordinal stored as a byte). Runs with no
   * recorded cause read as {@code misc}.
   *
   * @return a fresh array copy; never {@code null}, may be zero-length
   */
  public byte[] badCausesSnapshot() {
    byte[] causes = badCauseCache;
    return Arrays.copyOf(causes, causes.length);
  }

  public long[] badExpiriesSnapshot() {
    long[] expiries = badExpiryCache;
    return Arrays.copyOf(expiries, expiries.length);
  }

  public long[] probationKeysSnapshot() {
    long[] keys = probationKeysCache;
    return Arrays.copyOf(keys, keys.length);
  }

  public long[] probationPrefixSumsSnapshot() {
    long[] sums = probationPrefixSumsCache;
    return Arrays.copyOf(sums, sums.length);
  }

  public byte[] probationCausesSnapshot() {
    byte[] causes = probationCauseCache;
    return Arrays.copyOf(causes, causes.length);
  }

  public long[] probationExpiriesSnapshot() {
    long[] expiries = probationExpiryCache;
    return Arrays.copyOf(expiries, expiries.length);
  }

  public void save(String fileName, String worldName) {
    if (!fileName.endsWith(".bin")) fileName = fileName + ".bin";

    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    String dirPath =
        pluginDir.getAbsolutePath() + File.separator + "database" + File.separator + "regionData";
    String filePath = dirPath + File.separator + fileName;

    // Snapshot under write lock to avoid concurrent modifications
    long[] sBadKeys;
    long[] sBadSums;
    byte[] sBadCauses;
    long[] sBadExpiries;
    long[] sProbKeys;
    long[] sProbSums;
    byte[] sProbCauses;
    long[] sProbExpiries;
    int totalRuns;
    long[] allKeys;
    long[] allDeltas;
    byte[] allCauses;
    long[] allExpiries;
    Map<String, long[]> sBiomeKeys;
    Map<String, long[]> sBiomeSums;

    writeLock.lock();
    try {
      sBadKeys = Arrays.copyOf(badKeysCache, badKeysCache.length);
      sBadSums = Arrays.copyOf(badPrefixSumsCache, badPrefixSumsCache.length);
      sBadCauses = Arrays.copyOf(badCauseCache, badCauseCache.length);
      sBadExpiries = Arrays.copyOf(badExpiryCache, badExpiryCache.length);

      sProbKeys = Arrays.copyOf(probationKeysCache, probationKeysCache.length);
      sProbSums = Arrays.copyOf(probationPrefixSumsCache, probationPrefixSumsCache.length);
      sProbCauses = Arrays.copyOf(probationCauseCache, probationCauseCache.length);
      sProbExpiries = Arrays.copyOf(probationExpiryCache, probationExpiryCache.length);

      // Merge active and probation non-overlapping runs into a single sorted stream for disk
      totalRuns = sBadKeys.length + sProbKeys.length;
      allKeys = new long[totalRuns];
      allDeltas = new long[totalRuns];
      allCauses = new byte[totalRuns];
      allExpiries = new long[totalRuns];
      int runIdx = 0;
      int aIdx = 0, pIdx = 0;
      long aPrev = 0L, pPrev = 0L;
      while (aIdx < sBadKeys.length || pIdx < sProbKeys.length) {
        if (aIdx < sBadKeys.length && pIdx < sProbKeys.length) {
          if (sBadKeys[aIdx] <= sProbKeys[pIdx]) {
            allKeys[runIdx] = sBadKeys[aIdx];
            allDeltas[runIdx] = sBadSums[aIdx] - aPrev;
            allCauses[runIdx] = (aIdx < sBadCauses.length) ? sBadCauses[aIdx] : MISC_CAUSE;
            allExpiries[runIdx] = (aIdx < sBadExpiries.length) ? sBadExpiries[aIdx] : 0L;
            aPrev = sBadSums[aIdx];
            aIdx++;
          } else {
            allKeys[runIdx] = sProbKeys[pIdx];
            allDeltas[runIdx] = sProbSums[pIdx] - pPrev;
            allCauses[runIdx] = (pIdx < sProbCauses.length) ? sProbCauses[pIdx] : MISC_CAUSE;
            allExpiries[runIdx] = (pIdx < sProbExpiries.length) ? sProbExpiries[pIdx] : 0L;
            pPrev = sProbSums[pIdx];
            pIdx++;
          }
        } else if (aIdx < sBadKeys.length) {
          allKeys[runIdx] = sBadKeys[aIdx];
          allDeltas[runIdx] = sBadSums[aIdx] - aPrev;
          allCauses[runIdx] = (aIdx < sBadCauses.length) ? sBadCauses[aIdx] : MISC_CAUSE;
          allExpiries[runIdx] = (aIdx < sBadExpiries.length) ? sBadExpiries[aIdx] : 0L;
          aPrev = sBadSums[aIdx];
          aIdx++;
        } else {
          allKeys[runIdx] = sProbKeys[pIdx];
          allDeltas[runIdx] = sProbSums[pIdx] - pPrev;
          allCauses[runIdx] = (pIdx < sProbCauses.length) ? sProbCauses[pIdx] : MISC_CAUSE;
          allExpiries[runIdx] = (pIdx < sProbExpiries.length) ? sProbExpiries[pIdx] : 0L;
          pPrev = sProbSums[pIdx];
          pIdx++;
        }
        runIdx++;
      }

      sBiomeKeys = new HashMap<>(biomeKeysCache.size());
      sBiomeSums = new HashMap<>(biomePrefixSumsCache.size());
      for (Map.Entry<String, long[]> e : biomeKeysCache.entrySet()) {
        sBiomeKeys.put(e.getKey(), Arrays.copyOf(e.getValue(), e.getValue().length));
      }
      for (Map.Entry<String, long[]> e : biomePrefixSumsCache.entrySet()) {
        sBiomeSums.put(e.getKey(), Arrays.copyOf(e.getValue(), e.getValue().length));
      }
    } finally {
      writeLock.unlock();
    }

    // Build a binary payload (big-endian) without any synchronous disk I/O here.
    // BIN_VERSION 3: magic(4) + version(4) + world(4+len) + stride(8) + badSize(4) +
    // entries * 25 bytes (key 8 + delta 8 + cause 1 + expiresAt 8).
    byte[] worldBytes = worldName.getBytes(StandardCharsets.UTF_8);
    int size = 0;
    size += 8; // BIN_MAGIC + BIN_VERSION
    size += 4 + worldBytes.length; // world name length + bytes
    size += 8; // scanStride
    size += 4; // bad array length
    size += totalRuns * 25; // key + delta + cause + expiresAt per entry
    size += 4; // biome map size
    for (Map.Entry<String, long[]> e : sBiomeKeys.entrySet()) {
      byte[] bName = e.getKey().getBytes(StandardCharsets.UTF_8);
      long[] keys = e.getValue();
      size += 4 + bName.length; // biome name length + bytes
      size += 4; // inner size
      size += keys.length * 16; // key + delta
    }

    ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
    buf.putInt(BIN_MAGIC);
    buf.putInt(BIN_VERSION);
    buf.putInt(worldBytes.length).put(worldBytes);
    buf.putLong(scanStride.get());

    buf.putInt(totalRuns);
    for (int i = 0; i < totalRuns; i++) {
      buf.putLong(allKeys[i]);
      buf.putLong(allDeltas[i]);
      buf.put(allCauses[i]);
      buf.putLong(allExpiries[i]);
    }

    buf.putInt(sBiomeKeys.size());
    for (Map.Entry<String, long[]> e : sBiomeKeys.entrySet()) {
      String biome = e.getKey();
      byte[] bName = biome.getBytes(StandardCharsets.UTF_8);
      buf.putInt(bName.length).put(bName);
      long[] keys = e.getValue();
      long[] sums = sBiomeSums.getOrDefault(biome, new long[0]);
      buf.putInt(keys.length);
      long p = 0L;
      for (int i = 0; i < keys.length; i++) {
        buf.putLong(keys[i]);
        long d = (i < sums.length ? sums[i] : p) - p;
        buf.putLong(d);
        p = (i < sums.length ? sums[i] : p);
      }
    }

    // Write directly to disk (async-safe: called from async scan/shutdown threads)
    try {
      java.nio.file.Path p = java.nio.file.Paths.get(filePath);
      java.nio.file.Files.createDirectories(p.getParent());
      java.nio.file.Files.write(p, buf.array());
    } catch (Exception e) {
      RTP.log(Level.WARNING, "[MemoryShape] Failed to write binary file: " + filePath + " - " + e.getMessage(), e);
    }
  }

  public void exportDebugJson(String fileName, String worldName) {
    if (!fileName.endsWith(".json")) fileName = fileName + ".json";

    // 1. Snapshot under write lock to avoid concurrent modifications
    long[] sBadKeys;
    long[] sBadSums;
    byte[] sBadCauses;
    java.util.Map<String, long[]> sBiomeKeys;
    java.util.Map<String, long[]> sBiomeSums;

    writeLock.lock();
    try {
      sBadKeys = java.util.Arrays.copyOf(badKeysCache, badKeysCache.length);
      sBadSums = java.util.Arrays.copyOf(badPrefixSumsCache, badPrefixSumsCache.length);
      sBadCauses = java.util.Arrays.copyOf(badCauseCache, badCauseCache.length);
      sBiomeKeys = new java.util.HashMap<>(biomeKeysCache.size());
      sBiomeSums = new java.util.HashMap<>(biomePrefixSumsCache.size());
      for (java.util.Map.Entry<String, long[]> e : biomeKeysCache.entrySet()) {
        sBiomeKeys.put(e.getKey(), java.util.Arrays.copyOf(e.getValue(), e.getValue().length));
      }
      for (java.util.Map.Entry<String, long[]> e : biomePrefixSumsCache.entrySet()) {
        sBiomeSums.put(e.getKey(), java.util.Arrays.copyOf(e.getValue(), e.getValue().length));
      }
    } finally {
      writeLock.unlock();
    }

    // 2. Convert Bad Location prefix sums back to discrete lengths, tagging each
    //    run with its rejection cause (one cause per run; `misc` when unknown).
    LocationGenerator.FailTypes[] failTypes = LocationGenerator.FailTypes.values();
    java.util.List<java.util.Map<String, Object>> badList = new java.util.ArrayList<>();
    long prev = 0L;
    for (int i = 0; i < sBadKeys.length; i++) {
      java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
      entry.put("start", sBadKeys[i]);
      entry.put("length", sBadSums[i] - prev);
      int causeOrd = (i < sBadCauses.length) ? (sBadCauses[i] & 0xFF) : (MISC_CAUSE & 0xFF);
      String causeName = (causeOrd >= 0 && causeOrd < failTypes.length)
          ? failTypes[causeOrd].name()
          : LocationGenerator.FailTypes.misc.name();
      entry.put("cause", causeName);
      badList.add(entry);
      prev = sBadSums[i];
    }

    // 3. Convert Biome Location prefix sums back to discrete lengths
    java.util.Map<String, java.util.List<java.util.Map<String, Long>>> biomeMap = new java.util.LinkedHashMap<>();
    for (java.util.Map.Entry<String, long[]> e : sBiomeKeys.entrySet()) {
      String biome = e.getKey();
      long[] keys = e.getValue();
      long[] sums = sBiomeSums.getOrDefault(biome, new long[0]);

      java.util.List<java.util.Map<String, Long>> bList = new java.util.ArrayList<>();
      long p = 0L;
      for (int i = 0; i < keys.length; i++) {
        java.util.Map<String, Long> entry = new java.util.LinkedHashMap<>();
        entry.put("start", keys[i]);
        long currentSum = (i < sums.length ? sums[i] : p);
        entry.put("length", currentSum - p);
        bList.add(entry);
        p = currentSum;
      }
      biomeMap.put(biome, bList);
    }

    // 4. Construct Root JSON Object
    java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
    root.put("world", worldName);
    root.put("scanStride", scanStride.get());
    root.put("spatialResolution", spatialResolution);
    root.put("badLocations", badList);
    root.put("biomeLocations", biomeMap);

    // 5. Write to File
    java.io.File pluginDir = io.github.dailystruggle.rtp.common.RTP.serverAccessor.getPluginDirectory();
    java.io.File outDir = new java.io.File(pluginDir, "database" + java.io.File.separator + "regionData" + java.io.File.separator + "debug");
    if (!outDir.exists()) outDir.mkdirs();
    java.io.File outFile = new java.io.File(outDir, fileName);

    try (java.io.FileWriter writer = new java.io.FileWriter(outFile)) {
      com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
      gson.toJson(root, writer);
    } catch (java.io.IOException ex) {
      io.github.dailystruggle.rtp.common.RTP.log(java.util.logging.Level.WARNING, "Failed to write debug JSON: " + ex.getMessage(), ex);
    }
  }

  public CompletableFuture<Void> getLoadFuture() {
    return loadFuture;
  }

  public CompletableFuture<Void> load(String fileName, String worldName) {
    if (!fileName.endsWith(".bin")) fileName = fileName + ".bin";

    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    String filePath =
        pluginDir.getAbsolutePath()
            + File.separator
            + "database"
            + File.separator
            + "regionData"
            + File.separator
            + fileName;

    loadFuture = CompletableFuture.runAsync(() -> {
      java.nio.file.Path p = java.nio.file.Paths.get(filePath);
      if (!java.nio.file.Files.exists(p)) return;
      byte[] data;
      try {
        data = java.nio.file.Files.readAllBytes(p);
      } catch (Exception e) {
        RTP.log(Level.WARNING, "[MemoryShape] Failed to read binary file: " + filePath + " - " + e.getMessage(), e);
        return;
      }
      if (data.length < 4) {
        // Empty or truncated binary - nothing to load. Most often produced
        // by a save() call that hit an error before writing the world-name
        // length header, or by a zero-byte file from a prior crash.
        return;
      }
      try {
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                // Format detection: a cause-tagged payload (BIN_VERSION >= 2)
                // begins with BIN_MAGIC; a legacy payload begins directly with the
                // (small, positive) world-name byte length.
                int first = buf.getInt();
                int version;
                int wLen;
                if (first == BIN_MAGIC) {
                  version = buf.getInt();
                  wLen = buf.getInt();
                } else {
                  version = 1;
                  wLen = first;
                }
                if (wLen < 0 || wLen > buf.remaining()) return;
                byte[] w = new byte[wLen];
                buf.get(w);
                String readWorld = new String(w, StandardCharsets.UTF_8);
                if (!readWorld.equals(worldName)) return;

                if (buf.remaining() >= 8) {
                  scanStride.set(buf.getLong());
                } else {
                  scanStride.set(-1L);
                }

                // Per-bad-run on-disk width: legacy = key(8) + delta(8); v2 adds a
                // trailing cause byte; v3 adds expiresAt epoch seconds (8 bytes).
                int badEntryWidth = (version >= 3) ? 25 : ((version >= 2) ? 17 : 16);
                int badSize = buf.getInt();
                if (badSize < 0 || badSize > (buf.remaining() / badEntryWidth)) return;

                long now = java.time.Instant.now().getEpochSecond();
                long[] rawKeys = new long[badSize];
                long[] rawDeltas = new long[badSize];
                byte[] rawCauses = new byte[badSize];
                long[] rawExpiries = new long[badSize];

                int activeCount = 0;
                int probCount = 0;

                for (int i = 0; i < badSize; i++) {
                  long k = buf.getLong();
                  long d = buf.getLong();
                  byte cause = (version >= 2) ? buf.get() : MISC_CAUSE;
                  long exp = (version >= 3) ? buf.getLong() : 0L;
                  rawKeys[i] = k;
                  rawDeltas[i] = d;
                  rawCauses[i] = cause;
                  rawExpiries[i] = exp;

                  if (exp <= 0L || now < exp) {
                    activeCount++;
                  } else {
                    // Check if within probation window (1x TTL to 2x TTL)
                    LocationGenerator.FailTypes[] values = LocationGenerator.FailTypes.values();
                    LocationGenerator.FailTypes ft = (cause >= 0 && cause < values.length)
                        ? values[cause] : LocationGenerator.FailTypes.misc;
                    long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(ft, null);
                    long window = (ttl > 0L) ? ttl : 14L * 86400L;
                    if (now < exp + window) {
                      probCount++;
                    }
                  }
                }

                long[] newBadKeys = new long[activeCount];
                long[] newBadSums = new long[activeCount];
                byte[] newBadCauses = new byte[activeCount];
                long[] newBadExpiries = new long[activeCount];

                long[] newProbKeys = new long[probCount];
                long[] newProbSums = new long[probCount];
                byte[] newProbCauses = new byte[probCount];
                long[] newProbExpiries = new long[probCount];

                int aIdx = 0, pIdx = 0;
                long aRunning = 0L, pRunning = 0L;

                for (int i = 0; i < badSize; i++) {
                  long k = rawKeys[i];
                  long d = rawDeltas[i];
                  byte cause = rawCauses[i];
                  long exp = rawExpiries[i];

                  if (exp <= 0L || now < exp) {
                    newBadKeys[aIdx] = k;
                    aRunning += d;
                    newBadSums[aIdx] = aRunning;
                    newBadCauses[aIdx] = cause;
                    newBadExpiries[aIdx] = exp;
                    aIdx++;
                  } else {
                    LocationGenerator.FailTypes[] values = LocationGenerator.FailTypes.values();
                    LocationGenerator.FailTypes ft = (cause >= 0 && cause < values.length)
                        ? values[cause] : LocationGenerator.FailTypes.misc;
                    long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(ft, null);
                    long window = (ttl > 0L) ? ttl : 14L * 86400L;
                    if (now < exp + window) {
                      newProbKeys[pIdx] = k;
                      pRunning += d;
                      newProbSums[pIdx] = pRunning;
                      newProbCauses[pIdx] = cause;
                      newProbExpiries[pIdx] = exp;
                      pIdx++;
                    }
                  }
                }

                int biomeSize = buf.getInt();
                if (biomeSize < 0) return;
                ConcurrentHashMap<String, long[]> newBiomeKeysCache = new ConcurrentHashMap<>();
                ConcurrentHashMap<String, long[]> newBiomePrefixSumsCache = new ConcurrentHashMap<>();
                for (int i = 0; i < biomeSize; i++) {
                  int nLen = buf.getInt();
                  if (nLen < 0 || nLen > buf.remaining()) return;
                  byte[] nb = new byte[nLen];
                  buf.get(nb);
                  String biome = new String(nb, StandardCharsets.UTF_8);
                  int inner = buf.getInt();
                  if (inner < 0 || inner > (buf.remaining() / 16)) return;
                  long[] keys = new long[inner];
                  long[] sums = new long[inner];
                  long r = 0L;
                  for (int j = 0; j < inner; j++) {
                    long k = buf.getLong();
                    long d = buf.getLong();
                    keys[j] = k;
                    r += d;
                    sums[j] = r;
                  }
                  newBiomeKeysCache.put(biome, keys);
                  newBiomePrefixSumsCache.put(biome, sums);
                }

                // Apply under write lock
                writeLock.lock();
                try {
                  badKeysCache = newBadKeys;
                  badPrefixSumsCache = newBadSums;
                  badCauseCache = newBadCauses;
                  badExpiryCache = newBadExpiries;
                  probationKeysCache = newProbKeys;
                  probationPrefixSumsCache = newProbSums;
                  probationCauseCache = newProbCauses;
                  probationExpiryCache = newProbExpiries;
                  biomeKeysCache = newBiomeKeysCache;
                  biomePrefixSumsCache = newBiomePrefixSumsCache;
                  badLocationsDirty = true;
                  biomeLocationsDirty = true;
                } finally {
                  writeLock.unlock();
                }
              } catch (Throwable t) {
                RTP.log(Level.WARNING, t.getMessage(), t);
              }
            });
    return loadFuture;
  }

  public void addBadLocation(long location) {
    addBadLocation(location, LocationGenerator.FailTypes.misc);
  }

  /**
   * Marks a single 1D index bad with cause and custom retention TTL in seconds (ADR-079).
   *
   * @param location   the 1D spiral index to mark bad
   * @param cause      the rejection reason; {@code null} is treated as {@link LocationGenerator.FailTypes#misc}
   * @param ttlSeconds retention duration in seconds; {@code <= 0} indicates infinite retention
   */
  public void addBadLocation(long location, LocationGenerator.FailTypes cause, long ttlSeconds) {
    checkAndRestoreFromProbation(location);
    if (absorbIntoAdjacentRun(location)) return;
    long ord = (cause == null) ? MISC_CAUSE : cause.ordinal();
    long epochSec = (ttlSeconds <= 0) ? 0L : (java.time.Instant.now().getEpochSecond() + ttlSeconds);
    // Combine cause (low 8 bits) and epochSec (shifted 8 bits) into pending value
    long pendingVal = (ord & 0xFFL) | (epochSec << 8);
    pendingBadLocations.get().put(location, pendingVal);
    badLocationsDirty = true;
  }

  /**
   * Checks if {@code location} falls within an existing probationary run. If so, immediately
   * re-promotes that entire run back into {@code pendingBadLocations} with refreshed TTL.
   *
   * @param location candidate spiral index
   * @return true if a probationary run was restored
   */
  public boolean checkAndRestoreFromProbation(long location) {
    long[] pKeys = probationKeysCache;
    long[] pSums = probationPrefixSumsCache;
    byte[] pCauses = probationCauseCache;
    int m = Math.min(pKeys.length, pSums.length);
    if (m == 0 || location < 0L) return false;

    int idx = floorRunIndex(pKeys, location);
    if (idx < 0 || idx >= m) return false;

    long start = pKeys[idx];
    long prevSum = (idx > 0) ? pSums[idx - 1] : 0L;
    long len = pSums[idx] - prevSum;
    if (location >= start && location < start + len) {
      // Re-promote the whole run
      byte causeByte = (idx < pCauses.length) ? pCauses[idx] : MISC_CAUSE;
      LocationGenerator.FailTypes[] values = LocationGenerator.FailTypes.values();
      LocationGenerator.FailTypes cause = (causeByte >= 0 && causeByte < values.length)
          ? values[causeByte] : LocationGenerator.FailTypes.misc;
      long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(cause, null);
      long epochSec = (ttl <= 0) ? 0L : (java.time.Instant.now().getEpochSecond() + ttl);
      long pendingVal = (causeByte & 0xFFL) | (epochSec << 8);
      for (long k = start; k < start + len; k++) {
        pendingBadLocations.get().put(k, pendingVal);
      }
      badLocationsDirty = true;
      return true;
    }
    return false;
  }

  /**
   * Marks a single 1D index bad and records the rejection {@code cause}. The cause
   * is carried on the pending entry's value (its {@link LocationGenerator.FailTypes}
   * ordinal) and surfaces as the per-run cause after the next rebuild.
   *
   * @param location the 1D spiral index to mark bad
   * @param cause    the rejection reason; {@code null} is treated as {@link LocationGenerator.FailTypes#misc}
   */
  public void addBadLocation(long location, LocationGenerator.FailTypes cause) {
    long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(cause, null);
    addBadLocation(location, cause, ttl);
  }

  /**
   * Extend an existing bad run in place when {@code location} sits within {@code
   * spatialResolution} of its end, instead of queueing a pending entry.
   *
   * <p>A genuinely new run has to flow through {@code pendingBadLocations} and the dirty flag,
   * because inserting a key changes the array length. But a mark that the merge in
   * {@link #flushAndRebuild} would coalesce into the preceding run only changes that run's
   * length, which is representable in place - so it costs a suffix add rather than a full
   * re-merge, and it does not dirty the state at all.
   *
   * <p>Concurrency: prefix sums are updated back-to-front, so a lock-free reader mid-update sees
   * either the old or the new value at each index and the array stays non-decreasing either way;
   * {@code keys} is not touched, so a concurrent {@link #floorRunIndex} binary search stays
   * valid. Serialized against {@link #flushAndRebuild} through {@code isRebuilding}, and skipped
   * (falling back to the pending path) whenever a rebuild holds that flag.
   *
   * @param location the 1D index being marked bad
   * @return true when the mark was absorbed and no pending entry is needed
   */
  private boolean absorbIntoAdjacentRun(long location) {
    if (location < 0L) return false;
    if (!isRebuilding.compareAndSet(false, true)) return false;
    try {
      long[] keys = badKeysCache;
      long[] sums = badPrefixSumsCache;
      int n = Math.min(keys.length, sums.length);
      if (n == 0) return false;

      int idx = floorRunIndex(keys, location);
      if (idx < 0 || idx >= n) return false;

      long key = keys[idx];
      long prevSum = (idx > 0) ? sums[idx - 1] : 0L;
      long end = key + (sums[idx] - prevSum); // exclusive

      if (location < end) return true; // already covered: nothing to record
      if (location > end + spatialResolution) return false; // genuinely new run

      long newEnd = location + 1L;
      // A mark that would also bridge to the next run changes the array length; leave that
      // coalescing to the merge.
      if (idx + 1 < keys.length && keys[idx + 1] <= newEnd + spatialResolution) return false;

      long delta = newEnd - end;
      // writeLock excludes the snapshot readers that require a coherent view (save, load,
      // learnedStateSummary); the lock-free readers are safe by the monotonic-suffix argument
      // above.
      writeLock.lock();
      try {
        for (int k = n - 1; k >= idx; k--) sums[k] += delta;
      } finally {
        writeLock.unlock();
      }
      totalBadCount.addAndGet(delta);
      return true;
    } finally {
      isRebuilding.set(false);
    }
  }

  /** Shared empty result for {@link #chunkToLocations(int, int)} when a chunk has no preimage. */
  protected static final long[] EMPTY_LONG_ARRAY = new long[0];

  /**
   * Maximum walk distance (1D steps) used by default {@link #chunkToLocations(int, int)}
   * when probing for twin spiral index decoding to the same chunk.
   */
  protected static final int CHUNK_TO_LOCATIONS_WALK_BUDGET = 8;

  /**
   * Estimated 1D index offset between adjacent spiral rings at the same angle for given chunk.
   * Override hook for subclasses with exact geometry (e.g. Circle, Square). Returns 0 by default.
   *
   * @param cx chunk x in shape chunk-units
   * @param cz chunk z in shape chunk-units
   * @return non-negative ring offset, or 0 to rely solely on angular walk
   */
  protected long neighbourRingOffset(int cx, int cz) {
    return 0L;
  }

  /**
   * Inverse of {@link #xzToLocation(long, long)} at chunk granularity. Returns every 1D index
   * in {@code [0, getRange())} where {@code locationToXZ(n)} decodes to chunk {@code (cx, cz)}.
   * Bounded by <= 2 elements for Archimedean spirals (ADR-001).
   *
   * @param cx chunk x in shape chunk-units
   * @param cz chunk z in shape chunk-units
   * @return sorted distinct array of 0, 1, or 2 1D indices; never {@code null}
   */
  public long[] chunkToLocations(int cx, int cz) {
    if (!contains(cx, cz)) return EMPTY_LONG_ARRAY;

    final long range = getRange();
    if (range <= 0L) return EMPTY_LONG_ARRAY;

    final long representative = xzToLocation(cx, cz);
    if (representative < 0L || representative >= range) return EMPTY_LONG_ARRAY;

    // Collect up to 2 distinct indices that decode back to (cx, cz).
    long first = -1L;
    long second = -1L;

    int[] decoded = locationToXZ(representative);
    if (decoded[0] == cx && decoded[1] == cz) {
      first = representative;
    }

    // Angular walk: ±1 .. ±CHUNK_TO_LOCATIONS_WALK_BUDGET. We stop in each
    // direction as soon as the decoded coordinate leaves the chunk - the
    // representative is on the curve so the chunk's intersection with the
    // curve is contiguous in either direction.
    for (int delta = 1; delta <= CHUNK_TO_LOCATIONS_WALK_BUDGET; delta++) {
      long up = representative + delta;
      if (up < range) {
        decoded = locationToXZ(up);
        if (decoded[0] == cx && decoded[1] == cz) {
          if (first < 0L) first = up;
          else if (second < 0L && up != first) { second = up; break; }
        } else if (first >= 0L) {
          // Left the chunk on the +delta side; don't probe further up.
          break;
        }
      }
    }
    if (second < 0L) {
      for (int delta = 1; delta <= CHUNK_TO_LOCATIONS_WALK_BUDGET; delta++) {
        long down = representative - delta;
        if (down >= 0L) {
          decoded = locationToXZ(down);
          if (decoded[0] == cx && decoded[1] == cz) {
            if (first < 0L) first = down;
            else if (down != first) { second = down; break; }
          } else if (first >= 0L) {
            break;
          }
        }
      }
    }

    // Radial probe (twin on adjacent ring at roughly the same angle). Skip
    // when subclass has no exact ring offset, or when we already have 2 hits.
    // The candidate must be at least one ring away from any already-found
    // index - otherwise the angular walk would already have found it and we
    // would be double-counting a chunk that touches a single arc of the curve.
    if (second < 0L) {
      long ringOffset = neighbourRingOffset(cx, cz);
      if (ringOffset > (long) CHUNK_TO_LOCATIONS_WALK_BUDGET) {
        long base = (first >= 0L) ? first : representative;
        long[] candidates = new long[] { base + ringOffset, base - ringOffset };
        for (long cand : candidates) {
          if (cand < 0L || cand >= range) continue;
          // Reject candidates that fall within the angular-walk window of an
          // already-found index - they aren't on the adjacent ring.
          if (first >= 0L
              && Math.abs(cand - first) <= (long) CHUNK_TO_LOCATIONS_WALK_BUDGET) {
            continue;
          }
          decoded = locationToXZ(cand);
          if (decoded[0] == cx && decoded[1] == cz) {
            if (first < 0L) first = cand;
            else if (cand != first) { second = cand; break; }
          }
        }
      }
    }

    if (first < 0L) return EMPTY_LONG_ARRAY;
    if (second < 0L) return new long[] { first };
    return (first <= second) ? new long[] { first, second } : new long[] { second, first };
  }

  /**
   * Marks given 1D index and every twin index in the same chunk as bad.
   * Use only for chunk-attributable failures (biome, claims, borders, anvil).
   *
   * @param location 1D index decoding to target chunk
   * @return count of newly marked indices (0, 1, or 2)
   */
  public int addBadChunk(long location) {
    return addBadChunk(location, LocationGenerator.FailTypes.misc);
  }

  /**
   * Cause-tagged variant of {@link #addBadChunk(long)}.
   *
   * @param location 1D index decoding to target chunk
   * @param cause rejection cause attributed to marked indices
   * @return count of newly marked indices (0, 1, or 2)
   */
  public int addBadChunk(long location, LocationGenerator.FailTypes cause) {
    long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(cause, null);
    return addBadChunk(location, cause, ttl);
  }

  /**
   * Cause- and TTL-tagged variant of {@link #addBadChunk(long)} (ADR-079).
   *
   * @param location   1D index decoding to target chunk
   * @param cause      rejection cause attributed to marked indices
   * @param ttlSeconds retention duration in seconds; {@code <= 0} indicates infinite retention
   * @return count of newly marked indices (0, 1, or 2)
   */
  public int addBadChunk(long location, LocationGenerator.FailTypes cause, long ttlSeconds) {
    int[] xz = locationToXZ(location);
    long[] preimage = chunkToLocations(xz[0], xz[1]);
    if (preimage.length == 0) {
      if (!isKnownBad(location)) {
        addBadLocation(location, cause, ttlSeconds);
        return 1;
      }
      return 0;
    }
    int marked = 0;
    for (long p : preimage) {
      if (!isKnownBad(p)) {
        addBadLocation(p, cause, ttlSeconds);
        marked++;
      }
    }
    return marked;
  }

  /**
   * Coerces uniquePlacements config value into a non-negative chunk radius.
   * Handles Boolean (true->1, false->0), Number, String. 0 disables unique placements.
   *
   * @param raw raw config object or {@code null}
   * @return non-negative chunk radius
   */
  public static int uniquePlacementsRadius(Object raw) {
    if (raw == null) return 0;
    if (raw instanceof Boolean b) return b ? 1 : 0;
    if (raw instanceof Number n) return Math.max(0, n.intValue());
    String s = String.valueOf(raw).trim();
    if (s.equalsIgnoreCase("true")) return 1;
    if (s.equalsIgnoreCase("false") || s.isEmpty()) return 0;
    try {
      return Math.max(0, Integer.parseInt(s));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * Marks chunks within Chebyshev radius {@code chunkRadius - 1} around {@code location} as bad.
   *
   * @param location center 1D index
   * @param chunkRadius Chebyshev radius (<= 0 is no-op)
   * @return newly marked index count
   */
  public int addBadChunkRadius(long location, int chunkRadius) {
    if (chunkRadius <= 0) return 0;
    if (chunkRadius == 1) {
      return addBadChunk(location, LocationGenerator.FailTypes.uniquePlacement);
    }
    int[] center = locationToXZ(location);
    int reach = chunkRadius - 1;
    int marked = 0;
    for (int dx = -reach; dx <= reach; dx++) {
      for (int dz = -reach; dz <= reach; dz++) {
        long[] preimage = chunkToLocations(center[0] + dx, center[1] + dz);
        for (long p : preimage) {
          if (p < 0L) continue;
          if (!isKnownBad(p)) {
            addBadLocation(p, LocationGenerator.FailTypes.uniquePlacement);
            marked++;
          }
        }
      }
    }
    return marked;
  }

  public void addBiomeLocation(Long location, long width, String biome) {
    // Canonicalise the biome key so that `FOREST` and `MINECRAFT:FOREST`
    // alias to the same per-biome bucket; the lookup side (PregenTask
    // biome-recall, getBiomeKeys / getBiomePrefixSums) does the same.
    String key = io.github.dailystruggle.rtp.common.selection.region.BiomeNames.canonical(biome);
    pendingBiomeLocations
        .get()
        .computeIfAbsent(key, b -> new ConcurrentHashMap<>())
        .put(location, width);
    biomeLocationsDirty = true;
  }

  public void clear() {
    scanStride.set(-1L);
    badKeysCache = new long[0];
    badPrefixSumsCache = new long[0];
    badCauseCache = new byte[0];
    badExpiryCache = new long[0];
    probationKeysCache = new long[0];
    probationPrefixSumsCache = new long[0];
    probationCauseCache = new byte[0];
    probationExpiryCache = new long[0];
    biomeKeysCache = new ConcurrentHashMap<>();
    biomePrefixSumsCache = new ConcurrentHashMap<>();
    biomeMappedKeysCache = new long[0];
    biomeMappedPrefixSumsCache = new long[0];
    badLocationsDirty = true;
    biomeLocationsDirty = true;
  }

  public long[] getBiomeKeys(String biome) {
    return biomeKeysCache.get(
        io.github.dailystruggle.rtp.common.selection.region.BiomeNames.canonical(biome));
  }

  public long[] getBiomePrefixSums(String biome) {
    return biomePrefixSumsCache.get(
        io.github.dailystruggle.rtp.common.selection.region.BiomeNames.canonical(biome));
  }

  /**
   * Returns saved biome identifier for location run containing {@code (x, z)}, or {@code null}.
   * Reads in-memory persisted cache without chunk I/O.
   *
   * @param x the x coordinate
   * @param z the z coordinate
   * @return canonical biome name, or {@code null}
   */
  public String biomeAt(int x, int z) {
    return biomeAt((long) xzToLocation(x, z));
  }

  /**
   * Returns saved biome identifier for location run containing {@code location}, or {@code null}.
   * Scans in-memory per-biome run tables without chunk I/O.
   *
   * @param location the location value
   * @return canonical biome name, or {@code null}
   */
  public String biomeAt(long location) {
    for (Map.Entry<String, long[]> e : biomeKeysCache.entrySet()) {
      long[] keys = e.getValue();
      if (keys == null || keys.length == 0) continue;
      long[] sums = biomePrefixSumsCache.get(e.getKey());
      if (sums == null || sums.length != keys.length) continue;

      int floorIdx = -1;
      for (int k = 0; k < keys.length; k++) {
        if (keys[k] <= location) {
          floorIdx = k;
        } else {
          break;
        }
      }

      if (floorIdx >= 0) {
        long key = keys[floorIdx];
        long sum = sums[floorIdx];
        long prevSum = (floorIdx > 0) ? sums[floorIdx - 1] : 0L;
        if (key == location || location < (key + (sum - prevSum))) {
          return e.getKey();
        }
      }
    }
    return null;
  }

  /**
   * Returns union of biome identifiers observed producing at least one candidate.
   * Unmodifiable view of {@link #biomePrefixSumsCache} keys, populated via Anvil observations.
   *
   * @return unmodifiable set of observed biome identifiers
   */
  public Set<String> getObservedBiomes() {
    return Collections.unmodifiableSet(biomePrefixSumsCache.keySet());
  }

  /**
   * Remove a location from biome-specific valid locations
   *
   * @param location the location value
   * @param biome the biome name
   */
  public void removeBiomeLocation(Long location, String biome) {
    String key = io.github.dailystruggle.rtp.common.selection.region.BiomeNames.canonical(biome);
    pendingBiomeRemovals
        .get()
        .computeIfAbsent(key, b -> new ConcurrentHashMap<>())
        .put(location, true);
    biomeLocationsDirty = true;
  }

  /**
   * Get the sum of all bad location segments
   *
   * @return the sum of all bad location segments
   */
  public long getEffectiveBadCount() {
    return totalBadCount.get();
  }

  /**
   * Get the sum of all good location segments
   *
   * @return the sum of all good location segments
   */
  public long getEffectiveGoodCount() {
    return totalBiomeCount.get();
  }

  /**
   * Immutable, human-oriented roll-up of this shape's persistent learned state.
   * Backs the per-region {@code [mem*]} placeholders surfaced by
   * {@code /rtp info region:<name>} (see {@code docs/admin/COMMANDS.md} and the
   * placeholder catalog in {@code PlaceholderProvider}). All figures are derived
   * from the same data {@link #exportDebugJson} writes; no chunk I/O, no scan is
   * triggered.
   *
   * @param range          total candidate cells the shape can address
   * @param badCount        cells currently flagged bad (learned rejects)
   * @param goodCount       cells with a recorded biome (learned good)
   * @param coveragePercent percentage of {@code range} that has been learned
   *                        (bad + good), {@code NaN} when {@code range == 0}
   * @param badPercent      percentage of {@code range} flagged bad, {@code NaN}
   *                        when {@code range == 0}
   * @param topCause        name of the rejection cause covering the most bad
   *                        cells, or {@code "none"} when nothing is flagged
   * @param topCausePercent that cause's share of all bad cells, {@code NaN} when
   *                        nothing is flagged
   */
  public record LearnedStateSummary(
      long range,
      long badCount,
      long goodCount,
      double coveragePercent,
      double badPercent,
      String topCause,
      double topCausePercent) {}

  /**
   * Builds a {@link LearnedStateSummary} from a single write-locked snapshot of
   * the bad-location arrays (the same snapshot strategy as {@link #save} /
   * {@link #exportDebugJson}). The per-cause tally is cell-weighted via the
   * prefix sums, so a long run counts proportionally more than a short one.
   *
   * @return a fresh summary; never {@code null}
   */
  public LearnedStateSummary learnedStateSummary() {
    long[] sBadKeys;
    long[] sBadSums;
    byte[] sBadCauses;
    writeLock.lock();
    try {
      sBadKeys = Arrays.copyOf(badKeysCache, badKeysCache.length);
      sBadSums = Arrays.copyOf(badPrefixSumsCache, badPrefixSumsCache.length);
      sBadCauses = Arrays.copyOf(badCauseCache, badCauseCache.length);
    } finally {
      writeLock.unlock();
    }

    long range = getRange();
    long badCount = getEffectiveBadCount();
    long goodCount = getEffectiveGoodCount();
    long learned = badCount + goodCount;
    double coveragePercent =
        range > 0 ? Math.min(100.0, 100.0 * learned / range) : Double.NaN;
    double badPercent = range > 0 ? 100.0 * badCount / range : Double.NaN;

    LocationGenerator.FailTypes[] failTypes = LocationGenerator.FailTypes.values();
    long[] perCause = new long[failTypes.length];
    long prev = 0L;
    long totalRunCells = 0L;
    for (int i = 0; i < sBadKeys.length; i++) {
      long len = sBadSums[i] - prev;
      prev = sBadSums[i];
      int causeOrd = (i < sBadCauses.length) ? (sBadCauses[i] & 0xFF) : (MISC_CAUSE & 0xFF);
      if (causeOrd < 0 || causeOrd >= failTypes.length) causeOrd = MISC_CAUSE & 0xFF;
      perCause[causeOrd] += len;
      totalRunCells += len;
    }

    String topCause = "none";
    double topCausePercent = Double.NaN;
    long topCells = 0L;
    for (int i = 0; i < perCause.length; i++) {
      if (perCause[i] > topCells) {
        topCells = perCause[i];
        topCause = failTypes[i].name();
      }
    }
    if (totalRunCells > 0) topCausePercent = 100.0 * topCells / totalRunCells;

    return new LearnedStateSummary(
        range, badCount, goodCount, coveragePercent, badPercent, topCause, topCausePercent);
  }

  public void flushAndRebuild(long spatialResolution) {
    setSpatialResolution(spatialResolution);
    if (!badLocationsDirty && !biomeLocationsDirty) return;
    if (isRebuilding.compareAndSet(false, true)) {
      try {
        long currentBadSum = 0L;
        long currentBiomeSum = 0L;
        ConcurrentHashMap<Long, Long> localPendingBad =
            pendingBadLocations.getAndSet(new ConcurrentHashMap<>());
        ConcurrentHashMap<String, ConcurrentHashMap<Long, Long>> localPendingBiome =
            pendingBiomeLocations.getAndSet(new ConcurrentHashMap<>());
        ConcurrentHashMap<String, ConcurrentHashMap<Long, Boolean>> localPendingBiomeRemovals =
            pendingBiomeRemovals.getAndSet(new ConcurrentHashMap<>());

        this.rebuildingBadLocations = localPendingBad;

        // Biome logic (similar to bad locations but per biome)
        if (!localPendingBiome.isEmpty() || !localPendingBiomeRemovals.isEmpty()) {
          ConcurrentHashMap<String, long[]> newBiomeKeysCache = new ConcurrentHashMap<>(biomeKeysCache);
          ConcurrentHashMap<String, long[]> newBiomePrefixSumsCache = new ConcurrentHashMap<>(biomePrefixSumsCache);

          Set<String> affectedBiomes = new HashSet<>();
          affectedBiomes.addAll(localPendingBiome.keySet());
          affectedBiomes.addAll(localPendingBiomeRemovals.keySet());

          for (String biome : affectedBiomes) {
            long[] currentBiomeKeys = biomeKeysCache.getOrDefault(biome, new long[0]);
            long[] currentBiomePrefixSums = biomePrefixSumsCache.getOrDefault(biome, new long[0]);

            ConcurrentHashMap<Long, Long> additions = localPendingBiome.getOrDefault(biome, new ConcurrentHashMap<>());
            ConcurrentHashMap<Long, Boolean> removals = localPendingBiomeRemovals.getOrDefault(biome, new ConcurrentHashMap<>());

            java.util.List<java.util.Map.Entry<Long, Long>> tempBiomeEntries = new java.util.ArrayList<>();
            for (java.util.Map.Entry<Long, Long> entry : additions.entrySet()) {
                tempBiomeEntries.add(entry);
            }
            long[] pendingBiomeKeys = new long[tempBiomeEntries.size()];
            long[] pendingBiomeLengths = new long[tempBiomeEntries.size()];
            int pbIdx = 0;
            for (java.util.Map.Entry<Long, Long> entry : tempBiomeEntries) {
                pendingBiomeKeys[pbIdx] = entry.getKey();
                pendingBiomeLengths[pbIdx] = entry.getValue();
                pbIdx++;
            }

            // Sort keys while maintaining length mappings
            sortParallelArrays(pendingBiomeKeys, pendingBiomeLengths, 0, pendingBiomeKeys.length - 1);

            long[] mKeys = new long[currentBiomeKeys.length + pendingBiomeKeys.length];
            long[] mLengths = new long[currentBiomeKeys.length + pendingBiomeKeys.length];
            int mIdx = 0;

            int bi = 0;
            int bj = 0;
            long cStart = -1;
            long cLength = -1;

            while (bi < currentBiomeKeys.length || bj < pendingBiomeKeys.length) {
              long nKey;
              long nLength;

              if (bi < currentBiomeKeys.length && bj < pendingBiomeKeys.length) {
                if (currentBiomeKeys[bi] <= pendingBiomeKeys[bj]) {
                  nKey = currentBiomeKeys[bi];
                  long prevSum = (bi > 0) ? currentBiomePrefixSums[bi - 1] : 0L;
                  nLength = currentBiomePrefixSums[bi] - prevSum;
                  bi++;
                } else {
                  nKey = pendingBiomeKeys[bj];
                  nLength = pendingBiomeLengths[bj];
                  bj++;
                }
              } else if (bi < currentBiomeKeys.length) {
                nKey = currentBiomeKeys[bi];
                long prevSum = (bi > 0) ? currentBiomePrefixSums[bi - 1] : 0L;
                nLength = currentBiomePrefixSums[bi] - prevSum;
                bi++;
              } else {
                nKey = pendingBiomeKeys[bj];
                nLength = pendingBiomeLengths[bj];
                bj++;
              }

              if (nKey < 0) continue;

              if (cStart == -1) {
                cStart = nKey;
                cLength = nLength;
              } else {
                if (nKey <= cStart + cLength + spatialResolution) {
                  cLength = Math.max(cLength, nKey + nLength - cStart);
                } else {
                  mKeys[mIdx] = cStart;
                  mLengths[mIdx] = cLength;
                  mIdx++;
                  cStart = nKey;
                  cLength = nLength;
                }
              }
            }
            if (cStart != -1) {
              mKeys[mIdx] = cStart;
              mLengths[mIdx] = cLength;
              mIdx++;
            }

            // Handle removals
            if (!removals.isEmpty()) {
              java.util.List<Long> tempRemovals = new java.util.ArrayList<>();
              for (Long rLoc : removals.keySet()) {
                tempRemovals.add(rLoc);
              }
              long[] sortedRemovals = new long[tempRemovals.size()];
              for (int k = 0; k < tempRemovals.size(); k++) {
                sortedRemovals[k] = tempRemovals.get(k);
              }
              Arrays.sort(sortedRemovals);

              long[] postRemovalKeys = new long[mIdx + sortedRemovals.length];
              long[] postRemovalLengths = new long[mIdx + sortedRemovals.length];
              int prIdx = 0;

              int currentSpanIdx = 0;
              int currentRemIdx = 0;

              while (currentSpanIdx < mIdx) {
                long spanStart = mKeys[currentSpanIdx];
                long spanEnd = spanStart + mLengths[currentSpanIdx];

                long lastStart = spanStart;
                while (currentRemIdx < sortedRemovals.length && sortedRemovals[currentRemIdx] < spanEnd) {
                  long rLoc = sortedRemovals[currentRemIdx];
                  if (rLoc >= lastStart) {
                    if (rLoc > lastStart) {
                      postRemovalKeys[prIdx] = lastStart;
                      postRemovalLengths[prIdx] = rLoc - lastStart;
                      prIdx++;
                    }
                    lastStart = rLoc + 1;
                  }
                  currentRemIdx++;
                }

                if (lastStart < spanEnd) {
                  postRemovalKeys[prIdx] = lastStart;
                  postRemovalLengths[prIdx] = spanEnd - lastStart;
                  prIdx++;
                }
                currentSpanIdx++;
              }

              mKeys = postRemovalKeys;
              mIdx = prIdx;
              mLengths = postRemovalLengths;
            }

            long[] nbKeys = new long[mIdx];
            long[] nbSums = new long[mIdx];
            long bRunningSum = 0;
            for (int k = 0; k < mIdx; k++) {
                nbKeys[k] = mKeys[k];
                bRunningSum += mLengths[k];
                nbSums[k] = bRunningSum;
            }
            newBiomeKeysCache.put(biome, nbKeys);
            newBiomePrefixSumsCache.put(biome, nbSums);
          }

          // 1. Collect all intervals from every biome entry into a temporary list of primitive arrays.
          List<long[]> allKeysList = new ArrayList<>();
          List<long[]> allLengthsList = new ArrayList<>();
          for (Map.Entry<String, long[]> entry : newBiomeKeysCache.entrySet()) {
            long[] keys = entry.getValue();
            if (keys.length == 0) continue;
            long[] sums = newBiomePrefixSumsCache.get(entry.getKey());
            long[] lengths = new long[keys.length];
            for (int k = 0; k < keys.length; k++) {
              long prev = (k > 0) ? sums[k - 1] : 0L;
              lengths[k] = sums[k] - prev;
            }
            allKeysList.add(keys);
            allLengthsList.add(lengths);
          }

          // 2. Perform a multi-way primitive merge
          int numBiomes = allKeysList.size();
          if (numBiomes > 0) {
            int[] indices = new int[numBiomes];
            PriorityQueue<Integer> pq = new PriorityQueue<>(Comparator.comparingLong(idx -> allKeysList.get(idx)[indices[idx]]));

            int totalPotentialIntervals = 0;
            for (int k = 0; k < numBiomes; k++) {
              pq.add(k);
              totalPotentialIntervals += allKeysList.get(k).length;
            }

            long[] mergedMappedKeys = new long[totalPotentialIntervals];
            long[] mergedMappedLengths = new long[totalPotentialIntervals];
            int mappedIdx = 0;

            long currentStartMapped = -1;
            long currentLengthMapped = -1;

            while (!pq.isEmpty()) {
              int bIdx = pq.poll();
              int iIdx = indices[bIdx];

              long nextKey = allKeysList.get(bIdx)[iIdx];
              long nextLength = allLengthsList.get(bIdx)[iIdx];

              indices[bIdx]++;
              if (indices[bIdx] < allKeysList.get(bIdx).length) {
                pq.add(bIdx);
              }

              // 3. Use the same interval union math
              if (currentStartMapped == -1) {
                currentStartMapped = nextKey;
                currentLengthMapped = nextLength;
              } else {
                if (nextKey <= currentStartMapped + currentLengthMapped + spatialResolution) {
                  currentLengthMapped = Math.max(currentLengthMapped, nextKey + nextLength - currentStartMapped);
                } else {
                  mergedMappedKeys[mappedIdx] = currentStartMapped;
                  mergedMappedLengths[mappedIdx] = currentLengthMapped;
                  currentBiomeSum += currentLengthMapped;
                  mappedIdx++;
                  currentStartMapped = nextKey;
                  currentLengthMapped = nextLength;
                }
              }
            }

            if (currentStartMapped != -1) {
              mergedMappedKeys[mappedIdx] = currentStartMapped;
              mergedMappedLengths[mappedIdx] = currentLengthMapped;
              currentBiomeSum += currentLengthMapped;
              mappedIdx++;
            }

            // 4. Build the final biomeMappedKeysCache and biomeMappedPrefixSumsCache
            long[] finalMappedKeys = new long[mappedIdx];
            long[] finalMappedPrefixSums = new long[mappedIdx];
            long runningSumMapped = 0;
            for (int k = 0; k < mappedIdx; k++) {
              finalMappedKeys[k] = mergedMappedKeys[k];
              runningSumMapped += mergedMappedLengths[k];
              finalMappedPrefixSums[k] = runningSumMapped;
            }
            this.biomeMappedKeysCache = finalMappedKeys;
            this.biomeMappedPrefixSumsCache = finalMappedPrefixSums;
          } else {
            this.biomeMappedKeysCache = new long[0];
            this.biomeMappedPrefixSumsCache = new long[0];
          }

          this.biomeKeysCache = newBiomeKeysCache;
          this.biomePrefixSumsCache = newBiomePrefixSumsCache;
        } else {
          currentBiomeSum = (biomeMappedPrefixSumsCache.length > 0) ? biomeMappedPrefixSumsCache[biomeMappedPrefixSumsCache.length-1] : 0L;
        }
        this.biomeLocationsDirty = (!pendingBiomeLocations.get().isEmpty() || !pendingBiomeRemovals.get().isEmpty());

        // 4. Read current volatile arrays
        long[] currentBadKeys = badKeysCache;
        long[] currentBadSums = badPrefixSumsCache;
        byte[] currentBadCauses = badCauseCache;
        long[] currentBadExpiries = badExpiryCache;

        long[] currentProbKeys = probationKeysCache;
        long[] currentProbSums = probationPrefixSumsCache;
        byte[] currentProbCauses = probationCauseCache;
        long[] currentProbExpiries = probationExpiryCache;

        // Merge active and probation runs into a single sorted stream of existing runs
        int existingTotal = currentBadKeys.length + currentProbKeys.length;
        long[] existingKeys = new long[existingTotal];
        long[] existingLengths = new long[existingTotal];
        byte[] existingCauses = new byte[existingTotal];
        long[] existingExpiries = new long[existingTotal];
        int exIdx = 0;
        int curA = 0, curP = 0;
        long prevA = 0L, prevP = 0L;
        while (curA < currentBadKeys.length || curP < currentProbKeys.length) {
          if (curA < currentBadKeys.length && curP < currentProbKeys.length) {
            if (currentBadKeys[curA] <= currentProbKeys[curP]) {
              existingKeys[exIdx] = currentBadKeys[curA];
              long s = currentBadSums[curA];
              existingLengths[exIdx] = s - prevA;
              prevA = s;
              existingCauses[exIdx] = (curA < currentBadCauses.length) ? currentBadCauses[curA] : MISC_CAUSE;
              existingExpiries[exIdx] = (curA < currentBadExpiries.length) ? currentBadExpiries[curA] : 0L;
              curA++;
            } else {
              existingKeys[exIdx] = currentProbKeys[curP];
              long s = currentProbSums[curP];
              existingLengths[exIdx] = s - prevP;
              prevP = s;
              existingCauses[exIdx] = (curP < currentProbCauses.length) ? currentProbCauses[curP] : MISC_CAUSE;
              existingExpiries[exIdx] = (curP < currentProbExpiries.length) ? currentProbExpiries[curP] : 0L;
              curP++;
            }
          } else if (curA < currentBadKeys.length) {
            existingKeys[exIdx] = currentBadKeys[curA];
            long s = currentBadSums[curA];
            existingLengths[exIdx] = s - prevA;
            prevA = s;
            existingCauses[exIdx] = (curA < currentBadCauses.length) ? currentBadCauses[curA] : MISC_CAUSE;
            existingExpiries[exIdx] = (curA < currentBadExpiries.length) ? currentBadExpiries[curA] : 0L;
            curA++;
          } else {
            existingKeys[exIdx] = currentProbKeys[curP];
            long s = currentProbSums[curP];
            existingLengths[exIdx] = s - prevP;
            prevP = s;
            existingCauses[exIdx] = (curP < currentProbCauses.length) ? currentProbCauses[curP] : MISC_CAUSE;
            existingExpiries[exIdx] = (curP < currentProbExpiries.length) ? currentProbExpiries[curP] : 0L;
            curP++;
          }
          exIdx++;
        }

        // 5. Merge values from capturedBad into local data with RLE compression.
        //    Each pending entry's map value carries: (causeByte & 0xFF) | (epochSec << 8).
        long[] pendingKeys = new long[localPendingBad.size()];
        long[] pendingCauses = new long[localPendingBad.size()];
        long[] pendingExpiries = new long[localPendingBad.size()];
        {
          int pk = 0;
          for (java.util.Map.Entry<Long, Long> e : localPendingBad.entrySet()) {
            pendingKeys[pk] = e.getKey();
            Long v = e.getValue();
            long val = (v == null) ? MISC_CAUSE : v;
            pendingCauses[pk] = val & 0xFFL;
            pendingExpiries[pk] = val >>> 8;
            pk++;
          }
        }
        // Sort keys while keeping the parallel cause and expiry columns aligned.
        sortParallelThreeArrays(pendingKeys, pendingCauses, pendingExpiries, 0, pendingKeys.length - 1);

        int maxMerged = existingKeys.length + pendingKeys.length;
        long[] mergedKeys = new long[maxMerged];
        long[] mergedLengths = new long[maxMerged];
        byte[] mergedCauses = new byte[maxMerged];
        long[] mergedExpiries = new long[maxMerged];
        int mergeIndex = 0;

        int i = 0; // existingKeys index
        int j = 0; // pendingKeys index

        long currentStart = -1;
        long currentLength = -1;
        byte currentCause = MISC_CAUSE;
        long currentExpiry = 0L;

        while (i < existingKeys.length || j < pendingKeys.length) {
          long nextKey;
          long nextLength;
          byte nextCause;
          long nextExpiry;

          if (i < existingKeys.length && j < pendingKeys.length) {
            if (existingKeys[i] <= pendingKeys[j]) {
              nextKey = existingKeys[i];
              nextLength = existingLengths[i];
              nextCause = existingCauses[i];
              nextExpiry = existingExpiries[i];
              i++;
            } else {
              nextKey = pendingKeys[j];
              nextLength = 1L;
              nextCause = (byte) pendingCauses[j];
              nextExpiry = pendingExpiries[j];
              j++;
            }
          } else if (i < existingKeys.length) {
            nextKey = existingKeys[i];
            nextLength = existingLengths[i];
            nextCause = existingCauses[i];
            nextExpiry = existingExpiries[i];
            i++;
          } else {
            nextKey = pendingKeys[j];
            nextLength = 1L;
            nextCause = (byte) pendingCauses[j];
            nextExpiry = pendingExpiries[j];
            j++;
          }

          if (nextKey < 0) continue;

          if (currentStart == -1) {
            currentStart = nextKey;
            currentLength = nextLength;
            currentCause = nextCause;
            currentExpiry = nextExpiry;
          } else {
            boolean adjacent = (nextKey <= currentStart + currentLength + spatialResolution);
            boolean bothStatic = (currentExpiry <= 0L && nextExpiry <= 0L);
            boolean bothDynamic = (currentExpiry > 0L && nextExpiry > 0L);
            boolean sameTier = bothStatic || bothDynamic;

            if (adjacent && sameTier) {
              currentLength = Math.max(currentLength, nextKey + nextLength - currentStart);
              if (bothDynamic) {
                currentExpiry = Math.max(currentExpiry, nextExpiry);
              }
              // first-cause-wins: keep currentCause when runs coalesce (ADR-052).
            } else {
              mergedKeys[mergeIndex] = currentStart;
              mergedLengths[mergeIndex] = currentLength;
              mergedCauses[mergeIndex] = currentCause;
              mergedExpiries[mergeIndex] = currentExpiry;
              mergeIndex++;
              currentStart = nextKey;
              currentLength = nextLength;
              currentCause = nextCause;
              currentExpiry = nextExpiry;
            }
          }
        }

        if (currentStart != -1) {
          mergedKeys[mergeIndex] = currentStart;
          mergedLengths[mergeIndex] = currentLength;
          mergedCauses[mergeIndex] = currentCause;
          mergedExpiries[mergeIndex] = currentExpiry;
          mergeIndex++;
        }

        // 6. Partition merged runs into Active vs Probation vs Evicted based on wall-clock time
        long now = java.time.Instant.now().getEpochSecond();
        int activeCount = 0;
        int probCount = 0;

        for (int k = 0; k < mergeIndex; k++) {
          long exp = mergedExpiries[k];
          if (exp <= 0L || now < exp) {
            activeCount++;
          } else {
            LocationGenerator.FailTypes[] values = LocationGenerator.FailTypes.values();
            byte c = mergedCauses[k];
            LocationGenerator.FailTypes ft = (c >= 0 && c < values.length) ? values[c] : LocationGenerator.FailTypes.misc;
            long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(ft, null);
            long window = (ttl > 0L) ? ttl : 14L * 86400L;
            if (now < exp + window) {
              probCount++;
            }
          }
        }

        long[] newKeys = new long[activeCount];
        long[] newSums = new long[activeCount];
        byte[] newCauses = new byte[activeCount];
        long[] newExpiries = new long[activeCount];

        long[] newProbKeys = new long[probCount];
        long[] newProbSums = new long[probCount];
        byte[] newProbCauses = new byte[probCount];
        long[] newProbExpiries = new long[probCount];

        int aK = 0, pK = 0;
        long runningSum = 0L, runningProbSum = 0L;

        for (int k = 0; k < mergeIndex; k++) {
          long exp = mergedExpiries[k];
          long len = mergedLengths[k];
          byte c = mergedCauses[k];
          long start = mergedKeys[k];

          if (exp <= 0L || now < exp) {
            newKeys[aK] = start;
            runningSum += len;
            newSums[aK] = runningSum;
            newCauses[aK] = c;
            newExpiries[aK] = exp;
            aK++;
          } else {
            LocationGenerator.FailTypes[] values = LocationGenerator.FailTypes.values();
            LocationGenerator.FailTypes ft = (c >= 0 && c < values.length) ? values[c] : LocationGenerator.FailTypes.misc;
            long ttl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig.resolveTtlSeconds(ft, null);
            long window = (ttl > 0L) ? ttl : 14L * 86400L;
            if (now < exp + window) {
              newProbKeys[pK] = start;
              runningProbSum += len;
              newProbSums[pK] = runningProbSum;
              newProbCauses[pK] = c;
              newProbExpiries[pK] = exp;
              pK++;
            }
          }
        }

        currentBadSum = runningSum;

        this.badKeysCache = newKeys;
        this.badPrefixSumsCache = newSums;
        this.badCauseCache = newCauses;
        this.badExpiryCache = newExpiries;

        this.probationKeysCache = newProbKeys;
        this.probationPrefixSumsCache = newProbSums;
        this.probationCauseCache = newProbCauses;
        this.probationExpiryCache = newProbExpiries;

        this.rebuildingBadLocations = null; // Clear the reference only after the arrays update
        this.badLocationsDirty = !pendingBadLocations.get().isEmpty();

        totalBadCount.set(currentBadSum);
        totalBiomeCount.set(currentBiomeSum);
      } finally {
        isRebuilding.set(false);
      }
    }
  }

  /** Canonical {@code mode} values. {@link #mode()} upper-cases before comparing. */
  protected static final String MODE_ACCUMULATE = "ACCUMULATE";

  protected static final String MODE_NEAREST = "NEAREST";
  protected static final String MODE_REROLL = "REROLL";

  private volatile Knobs<E> knobs;
  private volatile boolean expandWarningLogged = false;

  /**
   * The four knobs the shared selection path reads, resolved to enum constants. Shapes expose
   * four different params enums, so the template cannot name constants directly - but it also
   * must not look them up by string per call: the {@link #data} {@code EnumMap} exists precisely
   * to make a knob read an ordinal array index, and a name index in front of it reinstates the
   * hashing the {@code EnumMap} was chosen to avoid. Resolved once per shape instance instead,
   * so {@link #rand()} pays only the {@code EnumMap} reads it paid before the unification.
   */
  private static final class Knobs<E extends Enum<E>> {
    private final E mode;
    private final E expand;
    private final E weight;
    private final E uniquePlacements;

    private Knobs(Class<E> myClass) {
      E m = null;
      E e = null;
      E w = null;
      E u = null;
      // One pass at resolution time. getEnumConstants() clones its array, so it is touched here
      // and nowhere on the selection path.
      for (E value : myClass.getEnumConstants()) {
        switch (value.name().toLowerCase(Locale.ROOT)) {
          case "mode" -> {
            if (m == null) m = value;
          }
          case "expand" -> {
            if (e == null) e = value;
          }
          case "weight" -> {
            if (w == null) w = value;
          }
          case "uniqueplacements" -> {
            if (u == null) u = value;
          }
          default -> {}
        }
      }
      this.mode = m;
      this.expand = e;
      this.weight = w;
      this.uniquePlacements = u;
    }
  }

  /** Benign race: construction is idempotent, and publication is via the volatile field. */
  private Knobs<E> knobs() {
    Knobs<E> cached = knobs;
    if (cached == null) {
      cached = new Knobs<>(myClass);
      knobs = cached;
    }
    return cached;
  }

  /**
   * Resolve a parameter by name rather than by enum constant. Init-time only: the selection path
   * uses {@link #knobs()}. Scans the params enum, so do not call it per selection.
   *
   * @param name parameter name, matched case-insensitively
   * @return the matching constant, or {@code null} if this shape does not expose it
   */
  protected final E keyByName(String name) {
    for (E value : myClass.getEnumConstants()) {
      if (value.name().equalsIgnoreCase(name)) return value;
    }
    return null;
  }

  /**
   * Read a parameter by resolved key.
   *
   * @param key the parameter constant, or {@code null} when the shape does not expose it
   * @param def returned when {@code key} is {@code null} or holds no value
   * @return the configured value, or {@code def}
   */
  private Object paramByKey(E key, Object def) {
    if (key == null) return def;
    EnumMap<E, Object> snapshot = data;
    synchronized (snapshot) {
      Object value = snapshot.get(key);
      return value != null ? value : def;
    }
  }

  /**
   * Read a parameter by name. Init-time convenience; see {@link #keyByName(String)}.
   *
   * @param name parameter name
   * @param def returned when the shape does not expose the parameter or holds no value
   * @return the configured value, or {@code def}
   */
  protected final Object paramByName(String name, Object def) {
    return paramByKey(keyByName(name), def);
  }

  /**
   * @return the configured selection mode, upper-cased. Case normalization is not optional: the
   *     dispatch in {@link #resolve} matches exact strings, so a lower-case {@code mode: nearest}
   *     would otherwise silently degrade to "no repair, no reroll".
   */
  protected final String mode() {
    return paramByKey(knobs().mode, MODE_ACCUMULATE).toString().toUpperCase(Locale.ROOT);
  }

  /** @return true if this shape exposes an {@code expand} knob at all. */
  protected final boolean declaresExpand() {
    return knobs().expand != null;
  }

  /** @return the configured {@code expand} flag, tolerating a string-typed YAML value. */
  protected final boolean expand() {
    Object raw = paramByKey(knobs().expand, Boolean.FALSE);
    if (raw instanceof Boolean b) return b;
    return raw != null && Boolean.parseBoolean(raw.toString());
  }

  /**
   * Whether {@code expand} is meaningful for this shape. Shapes whose 1D range bounds a larger
   * construction than the shape itself (the polygon mask, the ellipse inscribed in its bounding
   * circle) must return {@code false}: expanding past the learned bad runs pushes samples into
   * space {@link #contains(int, int)} rejects, so the knob can only hurt.
   *
   * @return true unless the shape is bounded smaller than its range
   */
  protected boolean supportsExpand() {
    return true;
  }

  /**
   * Force {@code expand} off for shapes that cannot honor it, warning once. Written back into
   * {@code data} rather than merely ignored so {@link #contains(int, int)}, which reads the same
   * knob, stays consistent with the sampling path.
   */
  private void coerceUnsupportedExpand() {
    if (supportsExpand() || !declaresExpand() || !expand()) return;
    E key = knobs().expand;
    if (key != null) set(key, Boolean.FALSE);
    if (!expandWarningLogged) {
      expandWarningLogged = true;
      RTP.log(
          Level.WARNING,
          "["
              + getClass().getSimpleName()
              + "] expand=true is not supported on "
              + getClass().getSimpleName()
              + " shapes; forcing expand=false");
    }
  }

  /**
   * Adjust the sampling range for the learned bad area.
   *
   * <p>Applied only when the shape exposes an {@code expand} knob. Shapes without one (notably
   * {@code Rectangle}) sample their raw range on a plain uniform curve, and introducing the
   * adjustment here would silently change their distribution.
   *
   * @param range the geometric range from {@link #getRange()}
   * @param badSum total learned bad area
   * @param mode the normalized selection mode
   * @return the range to sample against
   */
  protected double adjustRange(double range, long badSum, String mode) {
    if (!declaresExpand()) return range;
    boolean expand = expand();
    boolean accumulate = MODE_ACCUMULATE.equals(mode);
    if (!expand && accumulate) return range - badSum;
    if (expand && !accumulate) return range + badSum;
    return range;
  }

  /**
   * Draw a raw 1D sample in {@code [0, range)}. This is the shape's distribution model and the
   * only part of selection that is expected to differ between shapes.
   *
   * <p>The default is a power curve biased by the {@code weight} knob. Shapes that expose no
   * {@code weight} get {@code weight == 1.0}, i.e. a plain uniform draw.
   *
   * @param range the adjusted range
   * @return the sampled scalar
   */
  protected double sample(double range) {
    E weightKey = knobs().weight;
    double weight = (weightKey == null) ? 1.0 : getNumber(weightKey, 1.0).doubleValue();
    return range * Math.pow(rng().nextDouble(), weight);
  }

  /**
   * Post-selection hook, applied to the resolved location before it is returned. Lets a shape
   * reject a sample its range cannot express (e.g. a polygon mask) without re-implementing
   * {@link #rand()}.
   *
   * @param location the resolved location, or {@code -1} if already rejected
   * @return the location to return, or {@code -1} to request a re-roll
   */
  protected long postProcess(long location) {
    return location;
  }

  /**
   * Get a random location value within the shape.
   *
   * <p>Template method: the shared steps (bad-run snapshot, accumulate resolution, mode dispatch,
   * unique-placement marking) live here, and shapes contribute only {@link #getRange()},
   * {@link #adjustRange}, {@link #sample} and {@link #postProcess}. Deliberately not {@code final}
   * - {@code ChunkyRTPShape} and test doubles substitute their own selection wholesale.
   *
   * @return the random location value, or {@code -1} to request a re-roll
   */
  public long rand() {
    maybeFlushAndRebuild();
    coerceUnsupportedExpand();

    // Snapshot both arrays together to avoid races with concurrent rebuilds where
    // badKeysCache and badPrefixSumsCache may be observed at different lengths.
    long[] keys = badKeysCache;
    long[] sums = badPrefixSumsCache;
    if (keys.length != sums.length) {
      // Length mismatch indicates a concurrent rebuild was observed mid-update.
      // Clamp to the shorter common length to keep indexing safe.
      int common = Math.min(keys.length, sums.length);
      if (keys.length != common) keys = Arrays.copyOf(keys, common);
      if (sums.length != common) sums = Arrays.copyOf(sums, common);
    }
    long badSum = (sums.length > 0) ? sums[sums.length - 1] : 0L;

    String mode = mode();
    double range = adjustRange(getRange(), badSum, mode);
    double res = sample(range);
    return postProcess(resolve(res, range, mode, keys, sums));
  }

  /**
   * Upper bound on how many pending marks {@link #rand()} lets accumulate before rebuilding.
   * Caps the staleness of the learned-state snapshot on shapes that are never pulsed.
   */
  private static final int MAX_PENDING_BEFORE_REBUILD = 256;

  /**
   * Rebuild the learned-state arrays only when the pending batch is large enough to amortize the
   * cost.
   *
   * <p>{@link #flushAndRebuild} is a full merge of the whole run array, so calling it per
   * selection makes bulk selection quadratic once anything marks locations bad on the selection
   * path (see {@code uniquePlacements}). The pulse-level callers - {@code ScanTask},
   * {@code PregenTask}, {@code Region} - already rebuild on their own cadence; this only stops
   * {@code rand()} from forcing one per call.
   *
   * <p>The batch size scales with the existing run count, so a small learned state still
   * rebuilds eagerly (the merge is cheap there) while a large one amortizes the O(runs) merge
   * over proportionally many marks. Correctness does not depend on the timing:
   * {@link #isKnownBad} consults {@code pendingBadLocations} directly, so a pending mark is
   * honored immediately; only the ACCUMULATE bad-area subtraction lags, and it already reads a
   * possibly-stale snapshot by design.
   */
  private void maybeFlushAndRebuild() {
    if (!badLocationsDirty && !biomeLocationsDirty) return;
    int pending = pendingBadLocations.get().size();
    if (badLocationsDirty && !biomeLocationsDirty && pending > 0) {
      int batch = Math.min(MAX_PENDING_BEFORE_REBUILD, Math.max(1, badKeysCache.length / 8));
      if (pending < batch) return;
    }
    flushAndRebuild(spatialResolution);
  }

  /**
   * Turn a raw sample into a concrete location, applying the mode's bad-location policy.
   *
   * @param res the raw sample
   * @param range the adjusted range
   * @param mode the normalized selection mode
   * @param keys bad-run start keys (snapshot)
   * @param sums bad-run prefix sums (snapshot)
   * @return the resolved location, or {@code -1} to request a re-roll
   */
  private long resolve(double res, double range, String mode, long[] keys, long[] sums) {
    long location;
    if (MODE_ACCUMULATE.equals(mode)) {
      long target = (long) res;
      long currentBadSum = 0;

      // Iterate until the number of bad spots preceding our physical guess stabilizes.
      while (true) {
        // Search physical keys using a physical guess (target + current shift).
        int index = Arrays.binarySearch(keys, target + currentBadSum);

        if (index < 0) {
          // Point is between keys (or after all keys). Invert insertion point.
          index = -index - 1;
        } else {
          // Exact match: the coordinate sits exactly on the start of a bad interval.
          // Force the index forward to include this interval's bad sum.
          index = index + 1;
        }

        // Clamp defensively against the prefix-sums length to avoid AIOOBE if a
        // concurrent rebuild slipped a longer keys snapshot past us.
        if (index > sums.length) index = sums.length;

        long newBadSum = (index > 0) ? sums[index - 1] : 0;

        if (newBadSum == currentBadSum) break;
        currentBadSum = newBadSum;
      }
      location = target + currentBadSum;
    } else {
      location = (long) res;
    }

    if (MODE_NEAREST.equals(mode) && isKnownBad(location)) {
      location = nearestGood(location, range, keys, sums);
    }

    // NEAREST falls through to the REROLL check on purpose: if the nearest-good search
    // could not repair the sample, handing back a known-bad location wastes a full
    // pipeline attempt, so re-roll instead.
    if ((MODE_NEAREST.equals(mode) || MODE_REROLL.equals(mode)) && isKnownBad(location)) {
      return -1;
    }

    int uniqueRadius = uniquePlacementsRadius(paramByKey(knobs().uniquePlacements, 0));
    // addBadChunkRadius: chunk-uniform (uniqueplacements knob) - within a chunk the per-column
    // selection order is deterministic, so re-rolling onto the same chunk produces the
    // same effective placement. Marking the landing chunk (radius 1) prevents that chunk-level
    // re-roll; a larger radius additionally clears the surrounding chunks so placements spread out.
    if (uniqueRadius > 0) addBadChunkRadius(location, uniqueRadius);

    return location;
  }

  /**
   * Snap a known-bad location to whichever end of its bad run is nearer.
   *
   * @return the repaired location, or {@code location} unchanged when the containing run cannot
   *     be identified (the caller then re-rolls)
   */
  private long nearestGood(long location, double range, long[] keys, long[] sums) {
    int idx = Arrays.binarySearch(keys, location);
    int floorIdx = (idx >= 0) ? idx : -(idx + 1) - 1;
    if (floorIdx < 0 || floorIdx >= sums.length) return location;

    long key = keys[floorIdx];
    long prevSum = (floorIdx > 0) ? sums[floorIdx - 1] : 0L;
    long val = sums[floorIdx] - prevSum;

    long lowerGood = key - 1;
    long upperGood = key + val;

    if (lowerGood < 0) return upperGood;
    if (upperGood >= range) return lowerGood;
    return (location - lowerGood < upperGood - location) ? lowerGood : upperGood;
  }

  @Override
  public int[] select() {
    return locationToXZ(rand());
  }

  private void sortParallelArrays(long[] keys, long[] lengths, int left, int right) {
    if (left >= right) return;
    int pivotIdx = left + (right - left) / 2;
    long pivot = keys[pivotIdx];
    int i = left, j = right;
    while (i <= j) {
      while (keys[i] < pivot) i++;
      while (keys[j] > pivot) j--;
      if (i <= j) {
        long tempKey = keys[i];
        keys[i] = keys[j];
        keys[j] = tempKey;
        long tempLen = lengths[i];
        lengths[i] = lengths[j];
        lengths[j] = tempLen;
        i++;
        j--;
      }
    }
    if (left < j) sortParallelArrays(keys, lengths, left, j);
    if (i < right) sortParallelArrays(keys, lengths, i, right);
  }

  private void sortParallelThreeArrays(long[] keys, long[] b, long[] c, int left, int right) {
    if (left >= right) return;
    int pivotIdx = left + (right - left) / 2;
    long pivot = keys[pivotIdx];
    int i = left, j = right;
    while (i <= j) {
      while (keys[i] < pivot) i++;
      while (keys[j] > pivot) j--;
      if (i <= j) {
        long tempKey = keys[i];
        keys[i] = keys[j];
        keys[j] = tempKey;
        long tempB = b[i];
        b[i] = b[j];
        b[j] = tempB;
        long tempC = c[i];
        c[i] = c[j];
        c[j] = tempC;
        i++;
        j--;
      }
    }
    if (left < j) sortParallelThreeArrays(keys, b, c, left, j);
    if (i < right) sortParallelThreeArrays(keys, b, c, i, right);
  }

  @Override
  public boolean contains(int x, int z) {
    long l = xzToLocation(x, z);
    long range = (long) getRange();

    if (supportsExpand() && expand()) {
      long[] sums = badPrefixSumsCache;
      long badSum = (sums.length > 0) ? sums[sums.length - 1] : 0L;
      range += badSum;
    }

    return l >= 0 && l < range;
  }

  @Override
  public MemoryShape<E> clone() {
    MemoryShape<E> shape = (MemoryShape<E>) super.clone();
    shape.badKeysCache = new long[0];
    shape.badPrefixSumsCache = new long[0];
    shape.badCauseCache = new byte[0];
    shape.badExpiryCache = new long[0];
    shape.probationKeysCache = new long[0];
    shape.probationPrefixSumsCache = new long[0];
    shape.probationCauseCache = new byte[0];
    shape.probationExpiryCache = new long[0];
    shape.biomeKeysCache = new ConcurrentHashMap<>();
    shape.biomePrefixSumsCache = new ConcurrentHashMap<>();
    shape.biomeMappedKeysCache = new long[0];
    shape.biomeMappedPrefixSumsCache = new long[0];
    shape.badLocationsDirty = true;
    shape.biomeLocationsDirty = true;
    return shape;
  }
}
