package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Abstract class for shapes that store data in memory
 *
 * @param <E> enum for configuration values
 */
public abstract class MemoryShape<E extends Enum<E>> extends Shape<E> {
  public ConcurrentSkipListMap<Long, Long> badLocations = new ConcurrentSkipListMap<>();
  public AtomicLong badLocationSum = new AtomicLong(0L);
  public ConcurrentHashMap<String, ConcurrentSkipListMap<Long, Long>> biomeLocations =
      new ConcurrentHashMap<>();
  public ConcurrentSkipListMap<Long, Long> biomeMapped = new ConcurrentSkipListMap<>();
  public AtomicLong fillIter = new AtomicLong(0L);

  protected volatile long[] badKeysCache = new long[0];
  protected volatile long[] badPrefixSumsCache = new long[0];
  protected volatile ConcurrentHashMap<String, long[]> biomeKeysCache = new ConcurrentHashMap<>();
  protected volatile ConcurrentHashMap<String, long[]> biomePrefixSumsCache =
      new ConcurrentHashMap<>();
  protected volatile boolean badLocationsDirty = true;
  protected volatile boolean biomeLocationsDirty = true;
  protected final java.util.concurrent.atomic.AtomicBoolean isRebuilding =
      new java.util.concurrent.atomic.AtomicBoolean(false);

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

      out.writeInt(badLocations.size());
      for (Map.Entry<Long, Long> entry : badLocations.entrySet()) {
        out.writeLong(entry.getKey());
        out.writeLong(entry.getValue());
      }

      out.writeInt(biomeLocations.size());
      for (Map.Entry<String, ConcurrentSkipListMap<Long, Long>> entry : biomeLocations.entrySet()) {
        out.writeUTF(entry.getKey());
        Map<Long, Long> innerMap = entry.getValue();
        out.writeInt(innerMap.size());
        for (Map.Entry<Long, Long> innerEntry : innerMap.entrySet()) {
          out.writeLong(innerEntry.getKey());
          out.writeLong(innerEntry.getValue());
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

      this.badLocations.clear();
      this.badLocationSum.set(0);
      this.biomeLocations.clear();

      fillIter.set(in.readLong());

      int badSize = in.readInt();
      for (int i = 0; i < badSize; i++) {
        long k = in.readLong();
        long v = in.readLong();
        this.badLocations.put(k, v);
        this.badLocationSum.addAndGet(v);
      }

      int biomeSize = in.readInt();
      for (int i = 0; i < biomeSize; i++) {
        String biome = in.readUTF();
        int innerSize = in.readInt();
        ConcurrentSkipListMap<Long, Long> innerMap = new ConcurrentSkipListMap<>();
        for (int j = 0; j < innerSize; j++) {
          long k = in.readLong();
          long v = in.readLong();
          innerMap.put(k, v);
        }
        this.biomeLocations.put(biome, innerMap);
      }
      this.badLocationsDirty = true;
      this.biomeLocationsDirty = true;
    } catch (IOException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  public void addBadLocation(Long location) {
    if (location < 0) return;
    if (isKnownBad(location)) return;

    Map.Entry<Long, Long> lower = badLocations.floorEntry(location);
    Map.Entry<Long, Long> upper = badLocations.ceilingEntry(location);

    // goal: merge adjacent values
    // if within bounds of lower entry, do nothing
    // if lower start+length meets position, add 1 to its length and use that
    if ((lower != null) && (location < lower.getKey() + lower.getValue())) {
      return;
    } else if ((lower != null) && (location == lower.getKey() + lower.getValue())) {
      badLocations.put(lower.getKey(), lower.getValue() + 1);
    } else {
      badLocations.put(location, 1L);
    }

    lower = badLocations.floorEntry(location);

    // if upper start meets position + length, merge its length and delete upper entry
    if ((upper != null) && (lower.getKey() + lower.getValue() >= upper.getKey())) {
      badLocations.put(lower.getKey(), lower.getValue() + upper.getValue());
      badLocations.remove(upper.getKey());
    }

    for (String biome : biomeLocations.keySet()) {
      removeBiomeLocation(location, biome);
    }

    badLocationSum.incrementAndGet();
    badLocationsDirty = true;
  }

  /**
   * Add a biome-specific valid location
   *
   * @param location the location value
   * @param biome the biome name
   */
  public void addBiomeLocation(Long location, String biome) {
    biomeLocations.putIfAbsent(biome, new ConcurrentSkipListMap<>());
    ConcurrentSkipListMap<Long, Long> map = biomeLocations.get(biome);

    Map.Entry<Long, Long> lower = map.floorEntry(location);
    Map.Entry<Long, Long> upper = map.ceilingEntry(location);

    // goal: merge adjacent values
    // if within bounds of lower entry, do nothing
    // if lower start+length meets position, add 1 to its length and use that
    if ((lower != null) && (location < lower.getKey() + lower.getValue())) {
      return;
    } else if ((lower != null) && (location == lower.getKey() + lower.getValue())) {
      map.put(lower.getKey(), lower.getValue() + 1);
    } else {
      map.put(location, 1L);
    }

    lower = map.floorEntry(location);

    // if upper start meets position + length, merge its length and delete upper entry
    if ((upper != null) && (lower.getKey() + lower.getValue() >= upper.getKey())) {
      map.put(lower.getKey(), lower.getValue() + upper.getValue());
      map.remove(upper.getKey());
    }

    map = biomeMapped;

    if ((lower != null) && (location < lower.getKey() + lower.getValue())) {
      return;
    } else if ((lower != null) && (location == lower.getKey() + lower.getValue())) {
      map.put(lower.getKey(), lower.getValue() + 1);
    } else {
      map.put(location, 1L);
    }

    lower = map.floorEntry(location);

    // if upper start meets position + length, merge its length and delete upper entry
    if ((upper != null) && (lower.getKey() + lower.getValue() >= upper.getKey())) {
      map.put(lower.getKey(), lower.getValue() + upper.getValue());
      map.remove(upper.getKey());
    }
    biomeLocationsDirty = true;
  }

  /**
   * Remove a location from biome-specific valid locations
   *
   * @param location the location value
   * @param biome the biome name
   */
  public void removeBiomeLocation(Long location, String biome) {
    biomeLocations.putIfAbsent(biome, new ConcurrentSkipListMap<>());
    ConcurrentSkipListMap<Long, Long> map = biomeLocations.get(biome);

    Map.Entry<Long, Long> lower = map.floorEntry(location);
    if ((lower != null) && (location < lower.getKey() + lower.getValue())) {
      long key = lower.getKey();
      long val = lower.getValue();
      if (location < key + val) { // if within bounds, slice the bounds to remove the location
        map.remove(lower.getKey());
        if (location > key) {
          map.put(key, location - key);
        }
        if (location + 1 < key + val) {
          map.put(location + 1, (key + val) - (location + 1));
        }
      }
    }

    map = biomeMapped;
    lower = map.floorEntry(location);
    if ((lower != null) && (location < lower.getKey() + lower.getValue())) {
      long key = lower.getKey();
      long val = lower.getValue();
      if (location < key + val) { // if within bounds, slice the bounds to remove the location
        map.remove(lower.getKey());
        if (location > key) {
          map.put(key, location - key);
        }
        if (location + 1 < key + val) {
          map.put(location + 1, (key + val) - (location + 1));
        }
      }
    }
    biomeLocationsDirty = true;
  }

  protected void rebuildCacheIfNeeded() {
    if (!badLocationsDirty && !biomeLocationsDirty) return;
    if (isRebuilding.compareAndSet(false, true)) {
      try {
        if (badLocationsDirty) {
          int size = badLocations.size();
          long[] newKeys = new long[size];
          long[] newSums = new long[size];
          long currentSum = 0;
          int i = 0;
          for (Map.Entry<Long, Long> entry : badLocations.entrySet()) {
            newKeys[i] = entry.getKey();
            currentSum += entry.getValue();
            newSums[i] = currentSum;
            i++;
          }
          badKeysCache = newKeys;
          badPrefixSumsCache = newSums;
          badLocationsDirty = false;
        }

        if (biomeLocationsDirty) {
          ConcurrentHashMap<String, long[]> newBiomeKeysCache = new ConcurrentHashMap<>();
          ConcurrentHashMap<String, long[]> newBiomePrefixSumsCache = new ConcurrentHashMap<>();
          for (Map.Entry<String, ConcurrentSkipListMap<Long, Long>> biomeEntry : biomeLocations.entrySet()) {
            String biome = biomeEntry.getKey();
            ConcurrentSkipListMap<Long, Long> map = biomeEntry.getValue();
            int size = map.size();
            long[] newKeys = new long[size];
            long[] newSums = new long[size];
            long currentSum = 0;
            int i = 0;
            for (Map.Entry<Long, Long> entry : map.entrySet()) {
              newKeys[i] = entry.getKey();
              currentSum += entry.getValue();
              newSums[i] = currentSum;
              i++;
            }
            newBiomeKeysCache.put(biome, newKeys);
            newBiomePrefixSumsCache.put(biome, newSums);
          }
          biomeKeysCache = newBiomeKeysCache;
          biomePrefixSumsCache = newBiomePrefixSumsCache;
          biomeLocationsDirty = false;
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
    shape.badLocations = new ConcurrentSkipListMap<>();
    shape.biomeLocations = new ConcurrentHashMap<>();
    shape.fillIter = new AtomicLong(0);
    shape.badKeysCache = new long[0];
    shape.badPrefixSumsCache = new long[0];
    shape.biomeKeysCache = new ConcurrentHashMap<>();
    shape.biomePrefixSumsCache = new ConcurrentHashMap<>();
    shape.badLocationsDirty = true;
    shape.biomeLocationsDirty = true;
    return shape;
  }
}
