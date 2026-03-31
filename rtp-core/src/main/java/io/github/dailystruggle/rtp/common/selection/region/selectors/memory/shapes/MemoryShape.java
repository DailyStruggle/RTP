package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import java.io.*;
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
  public AtomicLong fillIter = new AtomicLong(0L);

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
    int idx = Arrays.binarySearch(keys, location);
    if (idx >= 0) return true;
    int floorIdx = -(idx + 1) - 1;
    if (floorIdx >= 0 && floorIdx < sums.length) {
      long key = keys[floorIdx];
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
    File dir = new File(dirPath);
    File file = new File(filePath);

    if (!dir.exists()) {
      boolean mkdirs = dir.mkdirs();
      if (!mkdirs) throw new IllegalStateException("failed to make directory");
    }

    try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
      out.writeUTF(worldName);
      out.writeLong(fillIter.get());

      out.writeInt(badKeysCache.length);
      for (int i = 0; i < badKeysCache.length; i++) {
        out.writeLong(badKeysCache[i]);
        long prevSum = (i > 0) ? badPrefixSumsCache[i - 1] : 0L;
        out.writeLong(badPrefixSumsCache[i] - prevSum);
      }

      out.writeInt(biomeKeysCache.size());
      for (Map.Entry<String, long[]> entry : biomeKeysCache.entrySet()) {
        String biome = entry.getKey();
        out.writeUTF(biome);
        long[] keys = entry.getValue();
        long[] sums = biomePrefixSumsCache.get(biome);
        out.writeInt(keys.length);
        for (int i = 0; i < keys.length; i++) {
          out.writeLong(keys[i]);
          long prevSum = (i > 0) ? sums[i - 1] : 0L;
          out.writeLong(sums[i] - prevSum);
        }
      }
    } catch (IOException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
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
    File file = new File(filePath);
    if (!file.exists()) {
      return;
    }

    try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
      String readWorldName = in.readUTF();
      if (!readWorldName.equals(worldName)) return;

      fillIter.set(in.readLong());

      int badSize = in.readInt();
      long[] newBadKeys = new long[badSize];
      long[] newBadSums = new long[badSize];
      long currentSum = 0;
      for (int i = 0; i < badSize; i++) {
        long k = in.readLong();
        long v = in.readLong();
        newBadKeys[i] = k;
        currentSum += v;
        newBadSums[i] = currentSum;
      }
      this.badKeysCache = newBadKeys;
      this.badPrefixSumsCache = newBadSums;

      int biomeSize = in.readInt();
      ConcurrentHashMap<String, long[]> newBiomeKeysCache = new ConcurrentHashMap<>();
      ConcurrentHashMap<String, long[]> newBiomePrefixSumsCache = new ConcurrentHashMap<>();
      for (int i = 0; i < biomeSize; i++) {
        String biome = in.readUTF();
        int innerSize = in.readInt();
        long[] keys = new long[innerSize];
        long[] sums = new long[innerSize];
        long currentBiomeSum = 0;
        for (int j = 0; j < innerSize; j++) {
          long k = in.readLong();
          long v = in.readLong();
          keys[j] = k;
          currentBiomeSum += v;
          sums[j] = currentBiomeSum;
        }
        newBiomeKeysCache.put(biome, keys);
        newBiomePrefixSumsCache.put(biome, sums);
      }
      this.biomeKeysCache = newBiomeKeysCache;
      this.biomePrefixSumsCache = newBiomePrefixSumsCache;
      this.badLocationsDirty = true;
      this.biomeLocationsDirty = true;
    } catch (IOException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  public void addBadLocation(long location, long width) {
    pendingBadLocations.get().put(location, width);
    badLocationsDirty = true;
  }

  public void addBiomeLocation(Long location, String biome) {
    pendingBiomeLocations
        .get()
        .computeIfAbsent(biome, b -> new ConcurrentHashMap<>())
        .put(location, 1L);
    biomeLocationsDirty = true;
  }

  public void clear() {
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

  protected void flushAndRebuild() {
    if (!badLocationsDirty && !biomeLocationsDirty) return;
    if (isRebuilding.compareAndSet(false, true)) {
      try {
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
                if (nKey <= cStart + cLength) {
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
                if (nextKey <= currentStartMapped + currentLengthMapped) {
                  currentLengthMapped = Math.max(currentLengthMapped, nextKey + nextLength - currentStartMapped);
                } else {
                  mergedMappedKeys[mappedIdx] = currentStartMapped;
                  mergedMappedLengths[mappedIdx] = currentLengthMapped;
                  mappedIdx++;
                  currentStartMapped = nextKey;
                  currentLengthMapped = nextLength;
                }
              }
            }

            if (currentStartMapped != -1) {
              mergedMappedKeys[mappedIdx] = currentStartMapped;
              mergedMappedLengths[mappedIdx] = currentLengthMapped;
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
        }
        this.biomeLocationsDirty = false;

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
            if (nextKey <= currentStart + currentLength) {
              currentLength = Math.max(currentLength, nextKey + nextLength - currentStart);
            } else {
              mergedKeys[mergeIndex] = currentStart;
              mergedLengths[mergeIndex] = currentLength;
              mergeIndex++;
              currentStart = nextKey;
              currentLength = nextLength;
            }
          }
        }

        if (currentStart != -1) {
            mergedKeys[mergeIndex] = currentStart;
            mergedLengths[mergeIndex] = currentLength;
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
        this.badLocationsDirty = false;
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
  public MemoryShape<E> clone() {
    MemoryShape<E> shape = (MemoryShape<E>) super.clone();
    shape.fillIter = new AtomicLong(0);
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
