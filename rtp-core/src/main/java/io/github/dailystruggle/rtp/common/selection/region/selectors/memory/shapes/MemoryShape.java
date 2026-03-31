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
  public AtomicLong badLocationSum = new AtomicLong(0L);
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
          java.util.concurrent.ConcurrentHashMap<Long, Boolean>>
      pendingBadLocations =
          new java.util.concurrent.atomic.AtomicReference<>(
              new java.util.concurrent.ConcurrentHashMap<>());
  protected final java.util.concurrent.atomic.AtomicReference<
          java.util.concurrent.ConcurrentHashMap<
              String, java.util.concurrent.ConcurrentHashMap<Long, Boolean>>>
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
  public abstract double xzToLocation(long x, long z);

  /**
   * Convert xz coordinates to a location value
   *
   * @param coords the coordinates
   * @return the location value
   */
  public abstract double xzToLocation(MutableRTPCoords coords);

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

      badLocationSum.set(0);

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
        this.badLocationSum.addAndGet(v);
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

  public void addBadLocation(Long location) {
    pendingBadLocations.get().put(location, true);
    badLocationSum.incrementAndGet();
    badLocationsDirty = true;
  }

  public void addBiomeLocation(Long location, String biome) {
    pendingBiomeLocations
        .get()
        .computeIfAbsent(biome, b -> new ConcurrentHashMap<>())
        .put(location, true);
    biomeLocationsDirty = true;
  }

  public void clear() {
    badKeysCache = new long[0];
    badPrefixSumsCache = new long[0];
    biomeKeysCache = new ConcurrentHashMap<>();
    biomePrefixSumsCache = new ConcurrentHashMap<>();
    biomeMappedKeysCache = new long[0];
    biomeMappedPrefixSumsCache = new long[0];
    badLocationSum.set(0);
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
        ConcurrentHashMap<Long, Boolean> localPendingBad =
            pendingBadLocations.getAndSet(new ConcurrentHashMap<>());
        ConcurrentHashMap<String, ConcurrentHashMap<Long, Boolean>> localPendingBiome =
            pendingBiomeLocations.getAndSet(new ConcurrentHashMap<>());
        ConcurrentHashMap<String, ConcurrentHashMap<Long, Boolean>> localPendingBiomeRemovals =
            pendingBiomeRemovals.getAndSet(new ConcurrentHashMap<>());

        if (localPendingBad.isEmpty()
            && localPendingBiome.isEmpty()
            && localPendingBiomeRemovals.isEmpty()) {
          return;
        }

        // 4. Read current volatile arrays
        long[] currentBadKeys = badKeysCache;
        long[] currentBadSums = badPrefixSumsCache;

        // 5. Merge values from capturedBad into local data with RLE compression
        TreeMap<Long, Long> badMap = new TreeMap<>();
        for (int i = 0; i < currentBadKeys.length; i++) {
          long prevSum = (i > 0) ? currentBadSums[i - 1] : 0L;
          badMap.put(currentBadKeys[i], currentBadSums[i] - prevSum);
        }

        for (Long loc : localPendingBad.keySet()) {
          if (loc < 0) continue;
          Map.Entry<Long, Long> floor = badMap.floorEntry(loc);
          if (floor != null && loc < floor.getKey() + floor.getValue()) continue;

          Map.Entry<Long, Long> ceiling = badMap.ceilingEntry(loc);
          if (floor != null && loc == floor.getKey() + floor.getValue()) {
            badMap.put(floor.getKey(), floor.getValue() + 1);
            floor = badMap.floorEntry(loc); // update floor after merge
          } else {
            badMap.put(loc, 1L);
            floor = badMap.floorEntry(loc);
          }

          if (ceiling != null && floor.getKey() + floor.getValue() >= ceiling.getKey()) {
            badMap.put(floor.getKey(), floor.getValue() + ceiling.getValue());
            badMap.remove(ceiling.getKey());
          }
        }

        // Build newKeys and newSums local arrays
        int newSize = badMap.size();
        long[] newKeys = new long[newSize];
        long[] newSums = new long[newSize];
        long runningSum = 0;
        int idx = 0;
        for (Map.Entry<Long, Long> entry : badMap.entrySet()) {
          newKeys[idx] = entry.getKey();
          runningSum += entry.getValue();
          newSums[idx] = runningSum;
          idx++;
        }

        // 7. Perform bottom-up swap
        this.badKeysCache = newKeys;
        this.badPrefixSumsCache = newSums;
        this.badLocationsDirty = false;

        // Biome logic (similar to bad locations but per biome)
        // Note: The issue description primarily focuses on the bad locations,
        // but we should maintain consistency for biomes if they were dirty.
        if (!localPendingBiome.isEmpty() || !localPendingBiomeRemovals.isEmpty()) {
          ConcurrentHashMap<String, long[]> newBiomeKeysCache = new ConcurrentHashMap<>(biomeKeysCache);
          ConcurrentHashMap<String, long[]> newBiomePrefixSumsCache = new ConcurrentHashMap<>(biomePrefixSumsCache);

          Set<String> affectedBiomes = new HashSet<>();
          affectedBiomes.addAll(localPendingBiome.keySet());
          affectedBiomes.addAll(localPendingBiomeRemovals.keySet());

          for (String biome : affectedBiomes) {
            TreeMap<Long, Long> bMap = new TreeMap<>();
            long[] bKeys = biomeKeysCache.get(biome);
            long[] bSums = biomePrefixSumsCache.get(biome);
            if (bKeys != null && bSums != null) {
              for (int i = 0; i < bKeys.length; i++) {
                long prevSum = (i > 0) ? bSums[i - 1] : 0L;
                bMap.put(bKeys[i], bSums[i] - prevSum);
              }
            }

            // Additions
            ConcurrentHashMap<Long, Boolean> additions = localPendingBiome.get(biome);
            if (additions != null) {
              for (Long loc : additions.keySet()) {
                Map.Entry<Long, Long> floor = bMap.floorEntry(loc);
                if (floor != null && loc < floor.getKey() + floor.getValue()) continue;
                Map.Entry<Long, Long> ceiling = bMap.ceilingEntry(loc);
                if (floor != null && loc == floor.getKey() + floor.getValue()) {
                  bMap.put(floor.getKey(), floor.getValue() + 1);
                  floor = bMap.floorEntry(loc);
                } else {
                  bMap.put(loc, 1L);
                  floor = bMap.floorEntry(loc);
                }
                if (ceiling != null && floor.getKey() + floor.getValue() >= ceiling.getKey()) {
                  bMap.put(floor.getKey(), floor.getValue() + ceiling.getValue());
                  bMap.remove(ceiling.getKey());
                }
              }
            }

            // Removals
            ConcurrentHashMap<Long, Boolean> removals = localPendingBiomeRemovals.get(biome);
            if (removals != null) {
              for (Long loc : removals.keySet()) {
                Map.Entry<Long, Long> floor = bMap.floorEntry(loc);
                if (floor != null && loc < floor.getKey() + floor.getValue()) {
                  long key = floor.getKey();
                  long val = floor.getValue();
                  bMap.remove(key);
                  if (loc > key) bMap.put(key, loc - key);
                  if (loc + 1 < key + val) bMap.put(loc + 1, (key + val) - (loc + 1));
                }
              }
            }

            int bSize = bMap.size();
            long[] nbKeys = new long[bSize];
            long[] nbSums = new long[bSize];
            long bRunningSum = 0;
            int bIdx = 0;
            for (Map.Entry<Long, Long> entry : bMap.entrySet()) {
              nbKeys[bIdx] = entry.getKey();
              bRunningSum += entry.getValue();
              nbSums[bIdx] = bRunningSum;
              bIdx++;
            }
            newBiomeKeysCache.put(biome, nbKeys);
            newBiomePrefixSumsCache.put(biome, nbSums);
          }
          this.biomeKeysCache = newBiomeKeysCache;
          this.biomePrefixSumsCache = newBiomePrefixSumsCache;
          this.biomeLocationsDirty = false;
        }
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

  @Override
  public MemoryShape<E> clone() {
    MemoryShape<E> shape = (MemoryShape<E>) super.clone();
    shape.badLocationSum = new AtomicLong(0);
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
