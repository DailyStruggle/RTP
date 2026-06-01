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
 * Abstract class for shapes that store data in memory.
 *
 * <p>This class implements the "learning algorithm" of the RTP plugin. It maintains
 * caches of known "bad" locations (e.g., oceans, lava) and known biome locations.
 * By keeping track of this data in memory (and persisting it via the database),
 * the shape avoids randomly selecting previously checked invalid coordinates.
 *
 * <p>It relies on custom implementations of Archimedean spirals (used in CIRCLE and SQUARE shapes)
 * to map 2D coordinate spaces into 1D sequences. This algorithm was specifically chosen over
 * alternatives like image compression algorithms because it enables the use of efficient 1D data
 * structures (like parallel arrays for keys and prefix sums) to perform extremely fast spatial
 * lookups and binary searches when generating random points.
 *
 * @param <E> enum for configuration values
 */
public abstract class MemoryShape<E extends Enum<E>> extends Shape<E> {
  public long spatialResolution = 1L;
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

  /** {@code FailTypes.misc} ordinal as a byte: the default / unknown cause. */
  protected static final byte MISC_CAUSE = (byte) LocationGenerator.FailTypes.misc.ordinal();

  /**
   * First int of a {@code .bin} payload written with the cause-tagged format.
   * Distinguishable from a legacy file whose first int is the (small, positive)
   * world-name byte length. Spells "RTP1" big-endian.
   */
  private static final int BIN_MAGIC = 0x52545031;
  /** Current cause-tagged {@code .bin} format version (1 == legacy, no magic). */
  private static final int BIN_VERSION = 2;
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

    int floorIdx = -1;
    for (int k = 0; k < keys.length; k++) {
      if (keys[k] <= location) {
        floorIdx = k;
      } else {
        break;
      }
    }

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
   * Returns the recorded rejection cause for the bad-location run that
   * contains {@code (x, z)}, or {@code -1} if the coordinate is not known
   * bad. The returned byte (when {@code >= 0}) is a
   * {@link LocationGenerator.FailTypes} ordinal, mirroring the per-run
   * {@link #badCauseCache}. Used by the region bad-locations chart bridge to
   * colorize each unsafe pixel by outcome rather than a single hazard color.
   *
   * @param x the x coordinate
   * @param z the z coordinate
   * @return the {@link LocationGenerator.FailTypes} ordinal, or {@code -1}
   *     if {@code (x, z)} is not known bad
   */
  public int causeAt(int x, int z) {
    return causeAt((long) xzToLocation(x, z));
  }

  /**
   * Returns the recorded rejection cause for the bad-location run that
   * contains {@code location}, or {@code -1} if it is not known bad. The
   * returned byte (when {@code >= 0}) is a
   * {@link LocationGenerator.FailTypes} ordinal. Pending / rebuilding
   * entries with no resolved run cause read as {@code misc}.
   *
   * @param location the location value
   * @return the {@link LocationGenerator.FailTypes} ordinal, or {@code -1}
   *     if {@code location} is not known bad
   */
  public int causeAt(long location) {
    if (pendingBadLocations.get().containsKey(location)) return MISC_CAUSE & 0xFF;

    ConcurrentHashMap<Long, Long> rebuilding = rebuildingBadLocations;
    if (rebuilding != null && rebuilding.containsKey(location)) return MISC_CAUSE & 0xFF;

    long[] sums = badPrefixSumsCache;
    long[] keys = badKeysCache;
    byte[] causes = badCauseCache;
    if (keys.length == 0) return -1;

    int floorIdx = -1;
    for (int k = 0; k < keys.length; k++) {
      if (keys[k] <= location) {
        floorIdx = k;
      } else {
        break;
      }
    }

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
   * Returns an immutable snapshot of the current bad-location key array
   * (sorted, packed long-encoded XZ keys). Used by the ADR-047 declarative
   * chart bridge ({@code BadPointsHeatmapResolver} in rtp-core) to compose a
   * {@code Heatmap2D} without touching internal state. No chunk I/O, no
   * locking on the read path (the field is {@code volatile} and reassigned
   * atomically by writers under {@code writeLock}).
   *
   * @return a fresh array copy; never {@code null}, may be zero-length
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
    Map<String, long[]> sBiomeKeys;
    Map<String, long[]> sBiomeSums;

    writeLock.lock();
    try {
      sBadKeys = Arrays.copyOf(badKeysCache, badKeysCache.length);
      sBadSums = Arrays.copyOf(badPrefixSumsCache, badPrefixSumsCache.length);
      sBadCauses = Arrays.copyOf(badCauseCache, badCauseCache.length);
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
    // Cause-tagged format (BIN_VERSION 2): a magic+version header precedes the
    // legacy layout, and each bad-run carries a trailing cause byte.
    byte[] worldBytes = worldName.getBytes(StandardCharsets.UTF_8);
    int size = 0;
    size += 8; // BIN_MAGIC + BIN_VERSION
    size += 4 + worldBytes.length; // world name length + bytes
    size += 8; // scanStride
    size += 4; // bad array length
    size += sBadKeys.length * 17; // key + delta + cause byte per entry
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

    buf.putInt(sBadKeys.length);
    long prev = 0L;
    for (int i = 0; i < sBadKeys.length; i++) {
      buf.putLong(sBadKeys[i]);
      long delta = sBadSums[i] - prev;
      buf.putLong(delta);
      buf.put(i < sBadCauses.length ? sBadCauses[i] : MISC_CAUSE);
      prev = sBadSums[i];
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
                // trailing cause byte. Legacy runs load with cause `misc`.
                int badEntryWidth = (version >= 2) ? 17 : 16;
                int badSize = buf.getInt();
                if (badSize < 0 || badSize > (buf.remaining() / badEntryWidth)) return;
                long[] newBadKeys = new long[badSize];
                long[] newBadSums = new long[badSize];
                byte[] newBadCauses = new byte[badSize];
                long running = 0L;
                for (int i = 0; i < badSize; i++) {
                  long k = buf.getLong();
                  long d = buf.getLong();
                  byte cause = (version >= 2) ? buf.get() : MISC_CAUSE;
                  newBadKeys[i] = k;
                  running += d;
                  newBadSums[i] = running;
                  newBadCauses[i] = cause;
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
   * Marks a single 1D index bad and records the rejection {@code cause}. The cause
   * is carried on the pending entry's value (its {@link LocationGenerator.FailTypes}
   * ordinal) and surfaces as the per-run cause after the next rebuild.
   */
  public void addBadLocation(long location, LocationGenerator.FailTypes cause) {
    long ord = (cause == null) ? MISC_CAUSE : cause.ordinal();
    pendingBadLocations.get().put(location, ord);
    badLocationsDirty = true;
  }

  /** Shared empty result for {@link #chunkToLocations(int, int)} when a chunk has no preimage. */
  protected static final long[] EMPTY_LONG_ARRAY = new long[0];

  /**
   * Maximum walk distance (in 1D index steps) used by the default
   * {@link #chunkToLocations(int, int)} implementation when probing for the
   * (≤ 1) twin spiral index that may also decode to the same chunk.
   *
   * <p>The spiral's chunk-unit parameterisation guarantees that the second
   * preimage, if any, lies within a small constant offset of the representative
   * index (angular neighbour {@code ±1}, or radial neighbour on the adjacent
   * ring whose offset scales with ring circumference). Subclasses that have an
   * exact ring-circumference formula may override
   * {@link #neighbourRingOffset(int, int)} to short-circuit the walk.
   */
  protected static final int CHUNK_TO_LOCATIONS_WALK_BUDGET = 8;

  /**
   * Override hook: estimated number of 1D indices between two spiral cells at
   * the same angle on adjacent rings, evaluated at the ring containing the
   * given chunk. Shape-specific subclasses (notably {@code Circle} and
   * {@code Square}) may return an exact value to let
   * {@link #chunkToLocations(int, int)} probe the radial-twin candidate
   * directly. The default returns {@code 0}, which causes the default
   * implementation to skip the radial probe and rely on the angular walk only.
   *
   * @return non-negative offset, or {@code 0} to disable the radial probe.
   */
  protected long neighbourRingOffset(int cx, int cz) {
    return 0L;
  }

  /**
   * Inverse of {@link #xzToLocation(long, long)} at chunk granularity. Returns
   * every 1D index {@code n} in {@code [0, getRange())} for which
   * {@code locationToXZ(n)} decodes to the chunk {@code (cx, cz)}.
   *
   * <p>The result is bounded by <strong>≤ 2 elements</strong> for the
   * spiral-based shapes ({@code CIRCLE}, {@code SQUARE}): the spiral's
   * inter-turn radial spacing is one chunk, and a chunk's diagonal is
   * {@code √2 &lt; 2}, so at most two consecutive turns of the curve can
   * intersect a unit-square chunk. See ADR-001 and
   * {@code docs/dev/scratch/CHECKLIST-chunk-to-locations-inverse.md}.
   *
   * <p>Returned indices are sorted ascending and distinct. May be empty for
   * chunks outside the shape's annulus. Never {@code null}.
   *
   * <p>The default implementation is shape-agnostic and uses only
   * {@link #xzToLocation(long, long)}, {@link #locationToXZ(long)} and
   * {@link #contains(int, int)}. It is O(1) with a small constant.
   *
   * @param cx chunk x in the shape's chunk-unit coordinate system
   * @param cz chunk z in the shape's chunk-unit coordinate system
   * @return 0-, 1- or 2-element array of 1D indices; never {@code null}.
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
    // direction as soon as the decoded coordinate leaves the chunk — the
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
    // index — otherwise the angular walk would already have found it and we
    // would be double-counting a chunk that touches a single arc of the curve.
    if (second < 0L) {
      long ringOffset = neighbourRingOffset(cx, cz);
      if (ringOffset > (long) CHUNK_TO_LOCATIONS_WALK_BUDGET) {
        long base = (first >= 0L) ? first : representative;
        long[] candidates = new long[] { base + ringOffset, base - ringOffset };
        for (long cand : candidates) {
          if (cand < 0L || cand >= range) continue;
          // Reject candidates that fall within the angular-walk window of an
          // already-found index — they aren't on the adjacent ring.
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
   * Marks the given 1D index plus every other 1D index that decodes to the
   * same chunk as bad. Use only for <strong>chunk-attributable</strong>
   * rejections (biome, claim/protection, force-load mask, world-border,
   * anvil pre-filter, ocean). Do <strong>not</strong> use for per-column
   * safety failures ({@code FailTypes.safety}, {@code FailTypes.vert}) or
   * for the {@code uniqueplacements} bookkeeping — those would mark valid
   * locations bad (false positives), shrinking the effective region.
   *
   * <p>By the ≤ 2 preimage bound on {@link #chunkToLocations(int, int)},
   * this method marks at most 2 indices per call. The expected amplification
   * over a circular annulus is {@code 1 + p₂ ≈ 1.57} (worst case 2.0 on
   * pathologically narrow annuli) — see the scratch checklist for the
   * derivation.
   *
   * @param location any 1D index that decodes to the target chunk.
   * @return number of indices newly marked (0, 1 or 2). A return of 0 means
   *         every preimage was already marked bad.
   */
  public int addBadChunk(long location) {
    return addBadChunk(location, LocationGenerator.FailTypes.misc);
  }

  /**
   * Cause-tagged variant of {@link #addBadChunk(long)}. Every preimage marked bad
   * records {@code cause} (carried through to the per-run {@link #badCauseCache}
   * after the next rebuild). See {@link #addBadChunk(long)} for the preimage
   * semantics and amplification bound.
   *
   * @param location any 1D index that decodes to the target chunk.
   * @param cause the rejection cause to attribute to the marked indices.
   * @return number of indices newly marked (0, 1 or 2).
   */
  public int addBadChunk(long location, LocationGenerator.FailTypes cause) {
    int[] xz = locationToXZ(location);
    long[] preimage = chunkToLocations(xz[0], xz[1]);
    if (preimage.length == 0) {
      // FP slop at the boundary, or chunk outside shape: fall back to the
      // single-index mark so the original rejection is at least recorded.
      if (!isKnownBad(location)) {
        addBadLocation(location, cause);
        return 1;
      }
      return 0;
    }
    int marked = 0;
    for (long p : preimage) {
      if (!isKnownBad(p)) {
        addBadLocation(p, cause);
        marked++;
      }
    }
    return marked;
  }

  /**
   * Coerces a raw {@code uniquePlacements} config value into a non-negative
   * chunk radius. The knob is an integer (default {@code 0} = off), but the
   * loader may hand back a {@link Boolean} (legacy {@code true}/{@code false}
   * configs), a {@link Number}, or a {@link String}. Coercion rules:
   *
   * <ul>
   *   <li>{@code null} -&gt; {@code 0}</li>
   *   <li>{@link Boolean}: {@code true} -&gt; {@code 1}, {@code false} -&gt; {@code 0}</li>
   *   <li>{@link Number}: truncated to {@code int}, clamped to {@code >= 0}</li>
   *   <li>{@link String}: {@code "true"}/{@code "false"} map to {@code 1}/{@code 0};
   *       otherwise parsed as an integer (unparseable -&gt; {@code 0})</li>
   * </ul>
   *
   * <p>A return of {@code 0} means unique-placement marking is disabled; a
   * value {@code n >= 1} marks a {@code (2n-1) x (2n-1)} chunk square centered
   * on the landing chunk (so {@code 1} == the legacy single-chunk behavior).
   *
   * @param raw the raw config value (any type, or {@code null}).
   * @return a non-negative chunk radius.
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
   * Marks every chunk within Chebyshev radius {@code chunkRadius - 1} of the
   * chunk containing {@code location} as bad (a {@code (2r-1) x (2r-1)} square
   * of chunks, where {@code r == chunkRadius}). Used for the
   * {@code uniquePlacements} knob: {@code chunkRadius == 1} marks only the
   * landing chunk (equivalent to {@link #addBadChunk(long)}), while larger
   * values clear a radius of chunks around the selection so subsequent
   * placements are spread out.
   *
   * @param location any 1D index that decodes to the center chunk.
   * @param chunkRadius the unique-placements radius ({@code <= 0} is a no-op).
   * @return number of indices newly marked bad across all touched chunks.
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
   * Returns the saved biome identifier recorded for the location run that
   * contains {@code (x, z)}, or {@code null} if no saved biome data covers
   * that coordinate. Reads only the in-memory, persisted biome-location cache
   * ({@link #biomeKeysCache} / {@link #biomePrefixSumsCache}); performs no
   * chunk I/O. Used by the region biome chart bridge to colorize each pixel
   * from saved region data rather than live / on-disk anvil reads.
   *
   * @param x the x coordinate
   * @param z the z coordinate
   * @return the canonical biome name, or {@code null} if not recorded
   */
  public String biomeAt(int x, int z) {
    return biomeAt((long) xzToLocation(x, z));
  }

  /**
   * Returns the saved biome identifier recorded for the location run that
   * contains {@code location}, or {@code null} if no saved biome data covers
   * it. Mirrors the floor-search used by {@link #causeAt(long)} but scans each
   * per-biome run table in {@link #biomeKeysCache} /
   * {@link #biomePrefixSumsCache}. Reads only persisted, in-memory data; no
   * chunk I/O.
   *
   * @param location the location value
   * @return the canonical biome name, or {@code null} if not recorded
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
   * Returns the union of biome identifiers that have been observed producing at least one
   * candidate within this shape. The returned set is a live, unmodifiable view of
   * {@link #biomePrefixSumsCache}'s key set and reflects subsequent updates.
   *
   * <p>This is the authoritative enumeration consulted by the biome-allow-list inversion
   * path in {@code LocationGenerator} — it is strictly tighter than
   * {@code RTPServerAccessor#getBiomes(world)} and, by virtue of being populated only
   * through the `LocationGenerator` pipeline (which is Anvil-first on every platform),
   * is upgrade-drift proof: observations reflect what `.mca` palettes actually contain
   * rather than what the current server seed / algorithm would synthesise live.</p>
   *
   * @return unmodifiable view of observed biome identifiers; empty before the first
   *         successful candidate selection.
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
    this.spatialResolution = spatialResolution;
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

        // 5. Merge values from capturedBad into local data with RLE compression.
        //    Each pending entry's map value carries the FailTypes ordinal (cause)
        //    of the rejection that marked it (default `misc`). One cause is kept
        //    per coalesced run (first-cause-wins; small-scale info loss accepted).
        long[] pendingKeys = new long[localPendingBad.size()];
        long[] pendingCauses = new long[localPendingBad.size()];
        {
          int pk = 0;
          for (java.util.Map.Entry<Long, Long> e : localPendingBad.entrySet()) {
            pendingKeys[pk] = e.getKey();
            Long v = e.getValue();
            pendingCauses[pk] = (v == null) ? MISC_CAUSE : v;
            pk++;
          }
        }
        // Sort keys while keeping the parallel cause column aligned.
        sortParallelArrays(pendingKeys, pendingCauses, 0, pendingKeys.length - 1);

        long[] mergedKeys = new long[currentBadKeys.length + pendingKeys.length];
        long[] mergedLengths = new long[currentBadKeys.length + pendingKeys.length];
        byte[] mergedCauses = new byte[currentBadKeys.length + pendingKeys.length];
        int mergeIndex = 0;

        int i = 0; // currentBadKeys index
        int j = 0; // pendingKeys index

        long currentStart = -1;
        long currentLength = -1;
        byte currentCause = MISC_CAUSE;

        while (i < currentBadKeys.length || j < pendingKeys.length) {
          long nextKey;
          long nextLength;
          byte nextCause;

          if (i < currentBadKeys.length && j < pendingKeys.length) {
            if (currentBadKeys[i] <= pendingKeys[j]) {
              nextKey = currentBadKeys[i];
              long prevSum = (i > 0) ? currentBadSums[i - 1] : 0L;
              nextLength = currentBadSums[i] - prevSum;
              nextCause = (i < currentBadCauses.length) ? currentBadCauses[i] : MISC_CAUSE;
              i++;
            } else {
              nextKey = pendingKeys[j];
              nextLength = 1L;
              nextCause = (byte) pendingCauses[j];
              j++;
            }
          } else if (i < currentBadKeys.length) {
            nextKey = currentBadKeys[i];
            long prevSum = (i > 0) ? currentBadSums[i - 1] : 0L;
            nextLength = currentBadSums[i] - prevSum;
            nextCause = (i < currentBadCauses.length) ? currentBadCauses[i] : MISC_CAUSE;
            i++;
          } else {
            nextKey = pendingKeys[j];
            nextLength = 1L;
            nextCause = (byte) pendingCauses[j];
            j++;
          }

          if (nextKey < 0) continue;

          if (currentStart == -1) {
            currentStart = nextKey;
            currentLength = nextLength;
            currentCause = nextCause;
          } else {
            if (nextKey <= currentStart + currentLength + spatialResolution) {
              currentLength = Math.max(currentLength, nextKey + nextLength - currentStart);
              // first-cause-wins: keep currentCause when runs coalesce.
            } else {
              mergedKeys[mergeIndex] = currentStart;
              mergedLengths[mergeIndex] = currentLength;
              mergedCauses[mergeIndex] = currentCause;
              currentBadSum += currentLength;
              mergeIndex++;
              currentStart = nextKey;
              currentLength = nextLength;
              currentCause = nextCause;
            }
          }
        }

        if (currentStart != -1) {
            mergedKeys[mergeIndex] = currentStart;
            mergedLengths[mergeIndex] = currentLength;
            mergedCauses[mergeIndex] = currentCause;
            currentBadSum += currentLength;
            mergeIndex++;
        }

        // Build newKeys and newSums local arrays
        long[] newKeys = new long[mergeIndex];
        long[] newSums = new long[mergeIndex];
        byte[] newCauses = new byte[mergeIndex];
        long runningSum = 0;
        for (int k = 0; k < mergeIndex; k++) {
          newKeys[k] = mergedKeys[k];
          runningSum += mergedLengths[k];
          newSums[k] = runningSum;
          newCauses[k] = mergedCauses[k];
        }


        this.badKeysCache = newKeys;
        this.badPrefixSumsCache = newSums;
        this.badCauseCache = newCauses;
        this.rebuildingBadLocations = null; // Clear the reference only after the arrays update

        this.badLocationsDirty = !pendingBadLocations.get().isEmpty();

        totalBadCount.set(currentBadSum);
        totalBiomeCount.set(currentBiomeSum);
      } finally {
        isRebuilding.set(false);
      }
    }
  }

  /**
   * Get a random location value within the shape
   *
   * @return the random location value
   */
  public abstract long rand();

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

  @Override
  public boolean contains(int x, int z) {
    long l = xzToLocation(x, z);
    long range = (long) getRange();

    boolean expand = false;
    for (Map.Entry<E, Object> entry : data.entrySet()) {
      if (entry.getKey().name().equalsIgnoreCase("expand")) {
        Object val = entry.getValue();
        if (val instanceof Boolean) expand = (Boolean) val;
        else if (val != null) expand = Boolean.parseBoolean(val.toString());
        break;
      }
    }

    if (expand) {
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
    shape.biomeKeysCache = new ConcurrentHashMap<>();
    shape.biomePrefixSumsCache = new ConcurrentHashMap<>();
    shape.biomeMappedKeysCache = new long[0];
    shape.biomeMappedPrefixSumsCache = new long[0];
    shape.badLocationsDirty = true;
    shape.biomeLocationsDirty = true;
    return shape;
  }
}
