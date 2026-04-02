package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * Abstract class for shapes that store data in memory
 *
 * @param <E> enum for configuration values
 */
public abstract class MemoryShape<E extends Enum<E>> extends Shape<E> {
  private static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;
  protected volatile long[] badKeysCache = new long[0];
  protected volatile long[] badPrefixSumsCache = new long[0];
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
  protected final AtomicLong fillStride = new AtomicLong(-1L);
  private final AtomicLong totalBadCount = new AtomicLong(0L);
  private final AtomicLong totalBiomeCount = new AtomicLong(0L);

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
  public abstract double getRange();

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
   * Determine if points are within the shape using SIMD
   *
   * @param xVec the x coordinates (in chunks)
   * @param zVec the z coordinates (in chunks)
   * @param mask the lane mask
   * @return a mask with lanes set for points within the shape
   */
  public VectorMask<Integer> contains(IntVector xVec, IntVector zVec, VectorMask<Integer> mask) {
    int length = mask.length();
    boolean[] result = new boolean[length];
    for (int i = 0; i < length; i++) {
      if (mask.laneIsSet(i)) {
        result[i] = contains(xVec.lane(i), zVec.lane(i));
      }
    }
    return VectorMask.fromArray(xVec.species(), result, 0);
  }

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
    long[] sums = badPrefixSumsCache;
    long[] keys = badKeysCache;
    if (keys.length == 0) return false;

    int floorIdx = -1;
    int k = 0;
    int bound = SPECIES.loopBound(keys.length);
    if (bound > 0) {
      LongVector vLocation = LongVector.broadcast(SPECIES, location);
      for (; k < bound; k += SPECIES.length()) {
        LongVector vKeys = LongVector.fromArray(SPECIES, keys, k);
        var mask = vKeys.compare(VectorOperators.LE, vLocation);
        if (mask.anyTrue()) {
          // Update floorIdx to the highest index in this vector where key <= location
          // mask.lastTrue() gives the relative index in the vector
          floorIdx = k + mask.lastTrue();
        } else {
          // Since keys are sorted, if no key in this batch is <= location,
          // all subsequent keys will also be > location.
          break;
        }
      }
    }

    // Process remaining elements if not already found in vectorized part or if there are leftovers
    // or if we need to search further in the remaining scalar part.
    // Actually, if floorIdx was found in vector part, and the next vector failed,
    // we don't need to check leftovers unless k < keys.length.
    for (; k < keys.length; k++) {
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

  public void save(String fileName, String worldName) {
    if (!fileName.endsWith(".bin")) fileName = fileName + ".bin";

    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    String dirPath =
        pluginDir.getAbsolutePath() + File.separator + "database" + File.separator + "regionData";
    String filePath = dirPath + File.separator + fileName;

    // Snapshot under write lock to avoid concurrent modifications
    long[] sBadKeys;
    long[] sBadSums;
    Map<String, long[]> sBiomeKeys;
    Map<String, long[]> sBiomeSums;

    writeLock.lock();
    try {
      sBadKeys = Arrays.copyOf(badKeysCache, badKeysCache.length);
      sBadSums = Arrays.copyOf(badPrefixSumsCache, badPrefixSumsCache.length);
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

    // Build a binary payload (big-endian) without any synchronous disk I/O here
    byte[] worldBytes = worldName.getBytes(StandardCharsets.UTF_8);
    int size = 0;
    size += 4 + worldBytes.length; // world name length + bytes
    size += 8; // fillStride
    size += 4; // bad array length
    size += sBadKeys.length * 16; // key + delta per entry
    size += 4; // biome map size
    for (Map.Entry<String, long[]> e : sBiomeKeys.entrySet()) {
      byte[] bName = e.getKey().getBytes(StandardCharsets.UTF_8);
      long[] keys = e.getValue();
      size += 4 + bName.length; // biome name length + bytes
      size += 4; // inner size
      size += keys.length * 16; // key + delta
    }

    ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
    buf.putInt(worldBytes.length).put(worldBytes);
    buf.putLong(fillStride.get());

    buf.putInt(sBadKeys.length);
    long prev = 0L;
    for (int i = 0; i < sBadKeys.length; i++) {
      buf.putLong(sBadKeys[i]);
      long delta = sBadSums[i] - prev;
      buf.putLong(delta);
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

    // Enqueue background write via the core database accessor's queue
    if (RTP.getInstance() != null && RTP.getInstance().databaseAccessor != null) {
      RTP.getInstance().databaseAccessor.saveFile(filePath, buf.array());
    }
  }

  public void load(String fileName, String worldName) {
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

    if (RTP.getInstance() == null || RTP.getInstance().databaseAccessor == null) return;

    RTP.getInstance()
        .databaseAccessor
        .loadFile(filePath)
        .thenAccept(
            optBytes -> {
              if (!optBytes.isPresent()) return;
              byte[] data = optBytes.get();
              try {
                ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                int wLen = buf.getInt();
                if (wLen < 0 || wLen > buf.remaining()) return;
                byte[] w = new byte[wLen];
                buf.get(w);
                String readWorld = new String(w, StandardCharsets.UTF_8);
                if (!readWorld.equals(worldName)) return;

                if (buf.remaining() >= 8) {
                  fillStride.set(buf.getLong());
                } else {
                  fillStride.set(-1L);
                }

                int badSize = buf.getInt();
                if (badSize < 0 || badSize > (buf.remaining() / 16)) return;
                long[] newBadKeys = new long[badSize];
                long[] newBadSums = new long[badSize];
                long running = 0L;
                for (int i = 0; i < badSize; i++) {
                  long k = buf.getLong();
                  long d = buf.getLong();
                  newBadKeys[i] = k;
                  running += d;
                  newBadSums[i] = running;
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
  }

  public void addBadLocation(long location, long width) {
    pendingBadLocations.get().put(location, width);
    badLocationsDirty = true;
  }

  public void addBiomeLocation(Long location, long width, String biome) {
    pendingBiomeLocations
        .get()
        .computeIfAbsent(biome, b -> new ConcurrentHashMap<>())
        .put(location, width);
    biomeLocationsDirty = true;
  }

  public void clear() {
    fillStride.set(-1L);
    badKeysCache = new long[0];
    badPrefixSumsCache = new long[0];
    biomeKeysCache = new ConcurrentHashMap<>();
    biomePrefixSumsCache = new ConcurrentHashMap<>();
    biomeMappedKeysCache = new long[0];
    biomeMappedPrefixSumsCache = new long[0];
    badLocationsDirty = true;
    biomeLocationsDirty = true;
  }

  public long[] getBiomeKeys(String biome) {
    return biomeKeysCache.get(biome);
  }

  public long[] getBiomePrefixSums(String biome) {
    return biomePrefixSumsCache.get(biome);
  }

  /**
   * Remove a location from biome-specific valid locations
   *
   * @param location the location value
   * @param biome the biome name
   */
  public void removeBiomeLocation(Long location, String biome) {
    pendingBiomeRemovals
        .get()
        .computeIfAbsent(biome, b -> new ConcurrentHashMap<>())
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

            long[] pendingBiomeKeys = new long[additions.size()];
            long[] pendingBiomeLengths = new long[additions.size()];
            int pbIdx = 0;
            for (Map.Entry<Long, Long> entry : additions.entrySet()) {
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
              long[] sortedRemovals = new long[removals.size()];
              int rIdx = 0;
              for (Long rLoc : removals.keySet()) {
                sortedRemovals[rIdx++] = rLoc;
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

        // 5. Merge values from capturedBad into local data with RLE compression
        long[] pendingKeys = new long[localPendingBad.size()];
        int pIdx = 0;
        for (Long loc : localPendingBad.keySet()) {
            pendingKeys[pIdx++] = loc;
        }
        Arrays.sort(pendingKeys);

        long[] mergedKeys = new long[currentBadKeys.length + pendingKeys.length];
        long[] mergedLengths = new long[currentBadKeys.length + pendingKeys.length];
        int mergeIndex = 0;

        int i = 0; // currentBadKeys index
        int j = 0; // pendingKeys index

        long currentStart = -1;
        long currentLength = -1;

        while (i < currentBadKeys.length || j < pendingKeys.length) {
          long nextKey;
          long nextLength;

          if (i < currentBadKeys.length && j < pendingKeys.length) {
            if (currentBadKeys[i] <= pendingKeys[j]) {
              nextKey = currentBadKeys[i];
              long prevSum = (i > 0) ? currentBadSums[i - 1] : 0L;
              nextLength = currentBadSums[i] - prevSum;
              i++;
            } else {
              nextKey = pendingKeys[j];
              nextLength = localPendingBad.get(nextKey);
              j++;
            }
          } else if (i < currentBadKeys.length) {
            nextKey = currentBadKeys[i];
            long prevSum = (i > 0) ? currentBadSums[i - 1] : 0L;
            nextLength = currentBadSums[i] - prevSum;
            i++;
          } else {
            nextKey = pendingKeys[j];
            nextLength = localPendingBad.get(nextKey);
            j++;
          }

          if (nextKey < 0) continue;

          if (currentStart == -1) {
            currentStart = nextKey;
            currentLength = nextLength;
          } else {
            if (nextKey <= currentStart + currentLength + spatialResolution) {
              currentLength = Math.max(currentLength, nextKey + nextLength - currentStart);
            } else {
              mergedKeys[mergeIndex] = currentStart;
              mergedLengths[mergeIndex] = currentLength;
              currentBadSum += currentLength;
              mergeIndex++;
              currentStart = nextKey;
              currentLength = nextLength;
            }
          }
        }

        if (currentStart != -1) {
            mergedKeys[mergeIndex] = currentStart;
            mergedLengths[mergeIndex] = currentLength;
            currentBadSum += currentLength;
            mergeIndex++;
        }

        // Build newKeys and newSums local arrays
        long[] newKeys = new long[mergeIndex];
        long[] newSums = new long[mergeIndex];
        long runningSum = 0;
        for (int k = 0; k < mergeIndex; k++) {
          newKeys[k] = mergedKeys[k];
          runningSum += mergedLengths[k];
          newSums[k] = runningSum;
        }

        this.badKeysCache = newKeys;
        this.badPrefixSumsCache = newSums;
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
    long l = xzToLocation(x,z);
    return l >= 0 && l < getRange();
  }

  @Override
  public MemoryShape<E> clone() {
    MemoryShape<E> shape = (MemoryShape<E>) super.clone();
    shape.badKeysCache = new long[0];
    shape.badPrefixSumsCache = new long[0];
    shape.biomeKeysCache = new ConcurrentHashMap<>();
    shape.biomePrefixSumsCache = new ConcurrentHashMap<>();
    shape.biomeMappedKeysCache = new long[0];
    shape.biomeMappedPrefixSumsCache = new long[0];
    shape.badLocationsDirty = true;
    shape.biomeLocationsDirty = true;
    return shape;
  }
}
