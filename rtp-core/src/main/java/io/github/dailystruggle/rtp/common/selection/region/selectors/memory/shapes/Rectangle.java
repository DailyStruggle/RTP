package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Rectangle shape for region selection */
public class Rectangle extends MemoryShape<RectangleParams> {
  protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();
  protected static final List<String> keys =
      Arrays.stream(RectangleParams.values()).map(Enum::name).collect(Collectors.toList());
  protected static final EnumMap<RectangleParams, Object> defaults =
      new EnumMap<>(RectangleParams.class);

  static {
    defaults.put(RectangleParams.mode, Mode.ACCUMULATE);
    defaults.put(RectangleParams.width, 256);
    defaults.put(RectangleParams.height, 256);
    defaults.put(RectangleParams.centerX, 0);
    defaults.put(RectangleParams.centerZ, 0);
    defaults.put(RectangleParams.rotation, 0);
    defaults.put(RectangleParams.uniquePlacements, false);

    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
  }

  /** Default constructor for Rectangle */
  public Rectangle() {
    super(RectangleParams.class, "RECTANGLE", defaults);
  }

  /**
   * Constructor for Rectangle with a custom name
   *
   * @param newName the name of the shape
   */
  public Rectangle(String newName) {
    super(RectangleParams.class, newName, defaults);
  }

  @Override
  public long getRange() {
    long w = getNumber(RectangleParams.width, 256L).longValue();
    long h = getNumber(RectangleParams.height, 256L).longValue();
    return w * h;
  }

  @Override
  public long xzToLocation(long x, long z) {
    long degrees = getNumber(RectangleParams.rotation, 0L).longValue();
    long cx = getNumber(RectangleParams.centerX, 0L).longValue();
    long cz = getNumber(RectangleParams.centerZ, 0L).longValue();
    long width = getNumber(RectangleParams.width, 256L).longValue();

    // shift point back to origin:
    x -= cx;
    z -= cz;

    int[] input = new int[] {(int) x, (int) z};

    input = rotate(input, -degrees);

    // translate to position
    return input[1] * width + input[0];
  }

  @Override
  public boolean contains(int x, int z) {
    long cx = getNumber(RectangleParams.centerX, 0L).longValue() >> 4;
    long cz = getNumber(RectangleParams.centerZ, 0L).longValue() >> 4;
    long width = getNumber(RectangleParams.width, 256L).longValue() >> 4;
    long height = getNumber(RectangleParams.height, 256L).longValue() >> 4;
    long degrees = getNumber(RectangleParams.rotation, 0L).longValue();

    if (width == 0 || height == 0) return false;

    if (degrees == 0) {
      return Math.abs(x - cx) <= width / 2 && Math.abs(z - cz) <= height / 2;
    }

    // shift point back to origin:
    int dx = (int) (x - cx);
    int dz = (int) (z - cz);

    int[] input = new int[] {dx, dz};
    input = rotate(input, -degrees);

    return Math.abs(input[0]) <= width / 2 && Math.abs(input[1]) <= height / 2;
  }



  @Override
  public long xzToLocation(MutableRTPCoords coords) {
    long degrees = getNumber(RectangleParams.rotation, 0L).longValue();
    long cx = getNumber(RectangleParams.centerX, 0L).longValue();
    long cz = getNumber(RectangleParams.centerZ, 0L).longValue();
    long width = getNumber(RectangleParams.width, 256L).longValue();

    // shift point back to origin:
    long x = coords.x - cx;
    long z = coords.z - cz;

    int[] input = new int[] {(int) x, (int) z};

    input = rotate(input, -degrees);

    // translate to position
    return input[1] * width + input[0];
  }

  @Override
  public int[] locationToXZ(long location) {
    MutableRTPCoords output = new MutableRTPCoords(0, 0);
    locationToXZ(location, output);
    return new int[] {output.x, output.z};
  }

  @Override
  public void locationToXZ(long location, MutableRTPCoords output) {
    long degrees = getNumber(RectangleParams.rotation, 0L).longValue();
    long cx = getNumber(RectangleParams.centerX, 0L).longValue();
    long cz = getNumber(RectangleParams.centerZ, 0L).longValue();
    long width = getNumber(RectangleParams.width, 256L).longValue();
    long height = getNumber(RectangleParams.height, 256L).longValue();

    // compute initial xz
    output.setXZ((int) (location % width), (int) (location / width));

    // center
    output.setXZ(output.x - (int) (width / 2), output.z - (int) (height / 2));

    // rotate around origin
    rotate(output, degrees);

    // shift
    output.setXZ(output.x + (int) cx, output.z + (int) cz);
  }

  @Override
  public int[] select() {
    long location = rand();
    return locationToXZ(location);
  }

  @Override
  public long rand() {
    flushAndRebuild(spatialResolution);
    String mode = data.getOrDefault(RectangleParams.mode, "ACCUMULATE").toString();

    double range = getRange();

    double res = (range) * (rng().nextDouble());

    long location;
    if (mode.equalsIgnoreCase("ACCUMULATE")) {
      long target = (long) res;
      long currentBadSum = 0;

      // We iterate until the number of bad spots preceding our physical guess stabilizes.
      while (true) {
        // Search Physical Keys using a Physical Guess (Target + Current Shift)
        int index = java.util.Arrays.binarySearch(badKeysCache, target + currentBadSum);

        if (index < 0) {
          // Point is between keys (or after all keys). Invert insertion point.
          index = -index - 1;
        } else {
          // Exact match: the coordinate sits exactly on the start of a bad interval.
          // Force the index forward to include this interval's bad sum.
          index = index + 1;
        }

        // Find the total bad area before this physical point
        long newBadSum = (index > 0) ? badPrefixSumsCache[index - 1] : 0;

        // If the bad count is stable, we have found the correct Physical Coordinate
        if (newBadSum == currentBadSum) break;
        currentBadSum = newBadSum;
      }
      location = target + currentBadSum;
    } else {
      location = (long) res;
    }

    switch (mode) {
      case "ACCUMULATE":
        {
          break;
        }
      case "NEAREST":
        {
          if (isKnownBad(location)) {
            long[] keys = badKeysCache;
            long[] sums = badPrefixSumsCache;
            int idx = Arrays.binarySearch(keys, location);
            int floorIdx = (idx >= 0) ? idx : -(idx + 1) - 1;

            long key = keys[floorIdx];
            long sum = sums[floorIdx];
            long prevSum = (floorIdx > 0) ? sums[floorIdx - 1] : 0L;
            long val = sum - prevSum;

            long lowerGood = key - 1;
            long upperGood = key + val;

            if (lowerGood < 0) location = upperGood;
            else if (upperGood >= range) location = lowerGood;
            else {
              if (location - lowerGood < upperGood - location) location = lowerGood;
              else location = upperGood;
            }
          }
        }
      case "REROLL":
        {
          if (isKnownBad(location)) {
            return -1;
          }
        }
      default:
        {
        }
    }

    Object unique = data.getOrDefault(RectangleParams.uniquePlacements, false);
    boolean u;
    if (unique instanceof Boolean) u = (Boolean) unique;
    else {
      u = Boolean.parseBoolean(String.valueOf(unique));
      data.put(RectangleParams.uniquePlacements, u);
    }
    if (u) addBadLocation(location);

    return location;
  }

  @Override
  public Map<String, CommandParameter> getParameters() {
    return subParameters;
  }

  @Override
  public Collection<String> keys() {
    return keys;
  }
}
