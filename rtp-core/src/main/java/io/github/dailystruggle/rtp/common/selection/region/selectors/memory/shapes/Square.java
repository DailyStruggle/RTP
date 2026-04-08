package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** Square shape for region selection */
public class Square extends MemoryShape<GenericMemoryShapeParams> {
  protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();
  protected static final List<String> keys =
      Arrays.stream(GenericMemoryShapeParams.values()).map(Enum::name).collect(Collectors.toList());
  protected static final EnumMap<GenericMemoryShapeParams, Object> defaults =
      new EnumMap<>(GenericMemoryShapeParams.class);

  static {
    defaults.put(GenericMemoryShapeParams.mode, Mode.ACCUMULATE);
    defaults.put(GenericMemoryShapeParams.radius, 256);
    defaults.put(GenericMemoryShapeParams.centerRadius, 64);
    defaults.put(GenericMemoryShapeParams.centerX, 0);
    defaults.put(GenericMemoryShapeParams.centerZ, 0);
    defaults.put(GenericMemoryShapeParams.weight, 1.0);
    defaults.put(GenericMemoryShapeParams.expand, false);
    defaults.put(GenericMemoryShapeParams.uniquePlacements, false);

    // subParameters moved to platform-specific code or refactored
  }

  /** Default constructor for Square */
  public Square() {
    super(GenericMemoryShapeParams.class, "SQUARE", defaults);
  }

  /**
   * Constructor for Square with a custom name
   *
   * @param newName the name of the shape
   */
  public Square(String newName) {
    super(GenericMemoryShapeParams.class, newName, defaults);
  }

  private static void squareOct2Coords(long radius, double perimeterStep, MutableRTPCoords output) {
    int x, z;
    // getFromString how far to go from a corner
    double shortStep = perimeterStep % radius;

    if (perimeterStep < radius * 4) {
      if (perimeterStep < radius * 2) {
        if (perimeterStep < radius) { // octant 1, from 0 to pi/4
          x = (int) radius;
          z = (int) shortStep;
        } else { // octant 2, from pi/4 to pi/2
          x = (int) (radius - shortStep);
          z = (int) radius;
        }
      } else {
        if (perimeterStep < radius * 3) { // octant 3
          x = (int) -shortStep;
          z = (int) radius;
        } else { // octant 4
          x = (int) -radius;
          z = (int) (radius - shortStep);
        }
      }
    } else {
      if (perimeterStep < radius * 6) {
        if (perimeterStep < radius * 5) { // octant 5
          x = (int) -radius;
          z = (int) -shortStep;
        } else { // octant 6
          x = (int) -(radius - shortStep);
          z = (int) -radius;
        }
      } else {
        if (perimeterStep < radius * 7) { // octant 7
          x = (int) shortStep;
          z = (int) -radius;
        } else { // octant 8
          x = (int) radius;
          z = (int) -(radius - shortStep);
        }
      }
    }
    output.setXZ(x, z);
  }

  private static int[] squareOct2Coords(long radius, double perimeterStep) {
    MutableRTPCoords output = new MutableRTPCoords(0, 0);
    squareOct2Coords(radius, perimeterStep, output);
    return new int[] {output.x, output.z};
  }

  @Override
  public long getRange() {
    long radius = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    return (radius - cr) * (radius + cr) * 4;
  }

  @Override
  public long xzToLocation(long x, long z) {
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long cx = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cz = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    x = x - cx;
    z = z - cz;

    double theta = ((Math.atan(((double) z) / x) / (2 * Math.PI)) + 1) % 0.25;

    if ((z < 0) && (x < 0)) {
      theta += 0.5;
    } else if (z < 0) {
      theta += 0.75;
    } else if (x < 0) {
      theta += 0.25;
    }

    long radius;
    long ax = Math.abs(x);
    long az = Math.abs(z);
    radius = Math.max(ax, az);

    long perimeterStep = 0;
    if (theta < 0.5) {
      if (theta < 0.25) {
        if (theta < 0.125) { // octant 1, from 0 to pi/4
          perimeterStep += az;
        } else { // octant 2, from pi/4 to pi/2
          perimeterStep += radius;
          perimeterStep += (radius - ax);
        }
      } else {
        if (theta < 0.375) { // octant 3
          perimeterStep += radius * 2;
          perimeterStep += ax; // x is negative in this quadrant, so fix
        } else { // octant 4
          perimeterStep += radius * 3;
          perimeterStep += (radius - az);
        }
      }
    } else {
      if (theta < 0.75) {
        if (theta < 0.625) { // octant 5
          perimeterStep += radius * 4;
          perimeterStep += az;
        } else { // octant 6
          perimeterStep += radius * 5;
          perimeterStep += (radius - ax);
        }
      } else {
        if (theta < 0.875) { // octant 7
          perimeterStep += radius * 6;
          perimeterStep += ax;
        } else { // octant 8
          perimeterStep += radius * 7;
          perimeterStep += (radius - az);
        }
      }
    }

    return ((radius * radius - cr * cr) * 4) + perimeterStep;
  }





  @Override
  public long xzToLocation(MutableRTPCoords coords) {
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long cx = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cz = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long x = coords.x - cx;
    long z = coords.z - cz;

    double theta = ((Math.atan(((double) z) / x) / (2 * Math.PI)) + 1) % 0.25;

    if ((z < 0) && (x < 0)) {
      theta += 0.5;
    } else if (z < 0) {
      theta += 0.75;
    } else if (x < 0) {
      theta += 0.25;
    }

    long radius;
    long ax = Math.abs(x);
    long az = Math.abs(z);
    radius = Math.max(ax, az);

    long perimeterStep = 0;
    if (theta < 0.5) {
      if (theta < 0.25) {
        if (theta < 0.125) { // octant 1, from 0 to pi/4
          perimeterStep += az;
        } else { // octant 2, from pi/4 to pi/2
          perimeterStep += radius;
          perimeterStep += (radius - ax);
        }
      } else {
        if (theta < 0.375) { // octant 3
          perimeterStep += radius * 2;
          perimeterStep += ax; // x is negative in this quadrant, so fix
        } else { // octant 4
          perimeterStep += radius * 3;
          perimeterStep += (radius - az);
        }
      }
    } else {
      if (theta < 0.75) {
        if (theta < 0.625) { // octant 5
          perimeterStep += radius * 4;
          perimeterStep += az;
        } else { // octant 6
          perimeterStep += radius * 5;
          perimeterStep += (radius - ax);
        }
      } else {
        if (theta < 0.875) { // octant 7
          perimeterStep += radius * 6;
          perimeterStep += ax;
        } else { // octant 8
          perimeterStep += radius * 7;
          perimeterStep += (radius - az);
        }
      }
    }

    return ((radius * radius - cr * cr) * 4) + perimeterStep;
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

    // getFromString a distance from the center
    double radius = Math.sqrt(location + cr * cr * 4) / 2;

    // getFromString how far to step around the square
    BigInteger bigLocation = BigInteger.valueOf(location);
    BigInteger bigMaxLong = BigInteger.valueOf(Long.MAX_VALUE);
    BigInteger bigRange = BigInteger.valueOf(getRange());
    long bamAngle = bigLocation.multiply(bigMaxLong).divide(bigRange).longValue();
    double theta = ((double) bamAngle / Long.MAX_VALUE);

    double perimeterStep = 8 * (radius * theta);

    long r = (long) radius;

    squareOct2Coords(r, perimeterStep, output);
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
    long[] sums = badPrefixSumsCache;
    long badSum = (sums.length > 0) ? sums[sums.length - 1] : 0L;

    boolean expand = (boolean) data.getOrDefault(GenericMemoryShapeParams.expand, false);
    String mode = data.getOrDefault(GenericMemoryShapeParams.mode, "ACCUMULATE").toString();

    double range = getRange();
    if ((!expand) && mode.equalsIgnoreCase("ACCUMULATE")) range -= badSum;
    else if (expand && !mode.equalsIgnoreCase("ACCUMULATE")) range += badSum;

    double weight = getNumber(GenericMemoryShapeParams.weight, 1.0).doubleValue();
    double res = (range) * Math.pow(ThreadLocalRandom.current().nextDouble(), weight);

    long location;
    if (mode.equalsIgnoreCase("ACCUMULATE")) {
      long target = (long) res;
      int index = java.util.Arrays.binarySearch(sums, target);
      if (index < 0) index = -index - 1;

      if (index > 0) target += sums[index - 1];
      location = target;
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

  @Override
  public Map<String, CommandParameter> getParameters() {
    return subParameters;
  }

  @Override
  public Collection<String> keys() {
    return keys;
  }
}
