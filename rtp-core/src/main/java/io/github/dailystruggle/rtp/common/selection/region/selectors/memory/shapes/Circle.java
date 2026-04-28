package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** Circle shape for region selection */
public class Circle extends MemoryShape<GenericMemoryShapeParams> {
  /** Default parameters for Circle */
  protected static final EnumMap<GenericMemoryShapeParams, Object> defaults =
      new EnumMap<>(GenericMemoryShapeParams.class);

  /** Sub-parameters for Circle commands */
  protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();

  /** List of keys for Circle parameters */
  protected static final List<String> keys =
      Arrays.stream(GenericMemoryShapeParams.values()).map(Enum::name).collect(Collectors.toList());

  static {
    try {
      defaults.put(GenericMemoryShapeParams.mode, Mode.ACCUMULATE);
      defaults.put(GenericMemoryShapeParams.radius, 256);
      defaults.put(GenericMemoryShapeParams.centerRadius, 64);
      defaults.put(GenericMemoryShapeParams.centerX, 0);
      defaults.put(GenericMemoryShapeParams.centerZ, 0);
      defaults.put(GenericMemoryShapeParams.weight, 1.0);
      defaults.put(GenericMemoryShapeParams.uniquePlacements, false);
      defaults.put(GenericMemoryShapeParams.expand, false);

      // subParameter removed
      // subParameter removed
      // subParameter removed
      // subParameter removed
      // subParameter removed
      // subParameter removed
      // subParameter removed
      // subParameter removed
    } catch (Exception e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  /**
   * Default constructor for Circle
   *
   * @throws IllegalArgumentException if default parameters are invalid
   */
  public Circle() throws IllegalArgumentException {
    super(GenericMemoryShapeParams.class, "CIRCLE", defaults);
  }

  /**
   * Constructor for Circle with a custom name
   *
   * @param newName the name of the shape
   * @throws IllegalArgumentException if parameters are invalid
   */
  public Circle(String newName) throws IllegalArgumentException {
    super(GenericMemoryShapeParams.class, newName, defaults);
  }

  @Override
  public long getRange() {
    long radius = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();

    return (long) ((radius - cr) * (radius + cr) * Math.PI);
  }



  @Override
  public long xzToLocation(long x, long z) {
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long cx = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cz = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    x = x - cx;
    z = z - cz;

    double rotation = ((Math.atan(((double) z) / x) / (2 * Math.PI)) + 1) % 0.25;

    if ((z < 0) && (x < 0)) {
      rotation += 0.5;
    } else if (z < 0) {
      rotation += 0.75;
    } else if (x < 0) {
      rotation += 0.25;
    }

    double radius = ((long) (Math.sqrt(x * x + z * z)));

    return (long) ((radius * radius - cr * cr) * Math.PI + rotation * (2 * radius * Math.PI));
  }

  @Override
  public long xzToLocation(MutableRTPCoords coords) {
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long cx = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cz = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long x = coords.x - cx;
    long z = coords.z - cz;

    double rotation = ((Math.atan(((double) z) / x) / (2 * Math.PI)) + 1) % 0.25;

    if ((z < 0) && (x < 0)) {
      rotation += 0.5;
    } else if (z < 0) {
      rotation += 0.75;
    } else if (x < 0) {
      rotation += 0.25;
    }

    double radius = ((long) (Math.sqrt(x * x + z * z)));

    return (long) ((radius * radius - cr * cr) * Math.PI + rotation * (2 * radius * Math.PI));
  }

  @Override
  public int[] locationToXZ(long location) {
    MutableRTPCoords output = new MutableRTPCoords(0, 0);
    locationToXZ(location, output);
    return new int[] {output.x, output.z};
  }

  @Override
  public void locationToXZ(long location, MutableRTPCoords output) {
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long cx = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cz = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    // 1. Determine the current integer "Ring" (Radius R)
    double preciseRadius = Math.sqrt((double) location / Math.PI + cr * cr);
    long R = (long) preciseRadius;

    // 2. Calculate the "Start Location" of this ring (where x=R, z=0)
    // We use BigInteger to maintain precision at the world border.
    BigInteger bigR = BigInteger.valueOf(R);
    BigInteger bigCR = BigInteger.valueOf(cr);

    // StartLoc = PI * (R^2 - CR^2)
    // Since location is already area-scaled, we use the raw squared units.
    BigInteger startLoc = bigR.multiply(bigR).subtract(bigCR.multiply(bigCR));

    // 3. Get the Remaining Length and Total Ring Circumference
    BigInteger currentLocation = BigInteger.valueOf((long)(location / Math.PI));
    BigInteger remainingLength = currentLocation.subtract(startLoc);
    BigInteger totalRingLength = bigR.shiftLeft(1).add(BigInteger.ONE); // (R+1)^2 - R^2 = 2R + 1

    // 4. Proportion of the step to the circumference
    // This double has the full 53-bit mantissa dedicated to the rotation.
    double proportion = remainingLength.doubleValue() / totalRingLength.doubleValue();
    double rotation = (proportion + 0.000069) * 2.0 * Math.PI;

    double cosRes = Math.cos(rotation);
    double sinRes = Math.sin(rotation);

    // 5. Polar to Cartesian
    output.setXZ((int) (R * cosRes + cx + 0.5), (int) (R * sinRes + cz + 0.5));
  }

  @Override
  public Map<String, CommandParameter> getParameters() {
    return subParameters;
  }

  @Override
  public Collection<String> keys() {
    return keys;
  }

  @Override
  public int[] select() {
    return locationToXZ(rand());
  }

  @Override
  public long rand() {
    flushAndRebuild(spatialResolution);
    // Snapshot both arrays together to avoid races with concurrent rebuilds where
    // badKeysCache and badPrefixSumsCache may be observed at different lengths.
    long[] sums = badPrefixSumsCache;
    long[] keysSnap = badKeysCache;
    if (keysSnap.length != sums.length) {
      // Length mismatch indicates a concurrent rebuild was observed mid-update.
      // Clamp to the shorter common length to keep indexing safe.
      int common = Math.min(keysSnap.length, sums.length);
      if (keysSnap.length != common) keysSnap = Arrays.copyOf(keysSnap, common);
      if (sums.length != common) sums = Arrays.copyOf(sums, common);
    }
    long badSum = (sums.length > 0) ? sums[sums.length - 1] : 0L;

    double range = getRange();
    boolean expand = (boolean) data.getOrDefault(GenericMemoryShapeParams.expand, false);
    String mode =
        data.getOrDefault(GenericMemoryShapeParams.mode, "ACCUMULATE").toString().toUpperCase();

    if ((!expand) && mode.equalsIgnoreCase("ACCUMULATE")) range -= badSum;
    else if (expand && !mode.equalsIgnoreCase("ACCUMULATE")) range += badSum;

    double weight = getNumber(GenericMemoryShapeParams.weight, 1.0).doubleValue();
    double res = (range) * Math.pow(rng().nextDouble(), weight);

    long location;
    if (mode.equalsIgnoreCase("ACCUMULATE")) {
      long target = (long) res;
      long currentBadSum = 0;

      // We iterate until the number of bad spots preceding our physical guess stabilizes.
      while (true) {
        // Search Physical Keys using a Physical Guess (Target + Current Shift)
        int index = java.util.Arrays.binarySearch(keysSnap, target + currentBadSum);

        if (index < 0) {
          // Point is between keys (or after all keys). Invert insertion point.
          index = -index - 1;
        } else {
          // Exact match: the coordinate sits exactly on the start of a bad interval.
          // Force the index forward to include this interval's bad sum.
          index = index + 1;
        }

        // Clamp index defensively against the prefix-sums length to avoid AIOOBE
        // if a concurrent rebuild slipped a longer keys snapshot past us.
        if (index > sums.length) index = sums.length;

        // Find the total bad area before this physical point
        long newBadSum = (index > 0) ? sums[index - 1] : 0;

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
            long[] keys = keysSnap;
            int idx = Arrays.binarySearch(keys, location);
            int floorIdx = (idx >= 0) ? idx : -(idx + 1) - 1;
            if (floorIdx < 0 || floorIdx >= sums.length) {
              return location;
            }

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
          return location;
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

    Object unique = data.getOrDefault(GenericMemoryShapeParams.uniquePlacements, false);
    boolean u;
    if (unique instanceof Boolean) u = (Boolean) unique;
    else {
      u = Boolean.parseBoolean(String.valueOf(unique));
      data.put(GenericMemoryShapeParams.uniquePlacements, u);
    }
    if (u) addBadLocation(location);

    return location;
  }
}
