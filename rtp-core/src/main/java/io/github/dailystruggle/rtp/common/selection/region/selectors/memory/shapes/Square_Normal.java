package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class Square_Normal extends MemoryShape<NormalDistributionParams> {
  protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();
  protected static final List<String> keys =
      Arrays.stream(NormalDistributionParams.values()).map(Enum::name).collect(Collectors.toList());
  protected static final EnumMap<NormalDistributionParams, Object> defaults =
      new EnumMap<>(NormalDistributionParams.class);

  static {
    defaults.put(NormalDistributionParams.mode, "REROLL");
    defaults.put(NormalDistributionParams.radius, 256);
    defaults.put(NormalDistributionParams.centerRadius, 64);
    defaults.put(NormalDistributionParams.centerX, 0);
    defaults.put(NormalDistributionParams.centerZ, 0);
    defaults.put(NormalDistributionParams.mean, 0.5);
    defaults.put(NormalDistributionParams.deviation, 1.0);
    defaults.put(NormalDistributionParams.expand, false);
    defaults.put(NormalDistributionParams.uniquePlacements, false);

    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
  }

  public Square_Normal() {
    super(NormalDistributionParams.class, "SQUARE_NORMAL", defaults);
  }

  public Square_Normal(String newName) {
    super(NormalDistributionParams.class, newName, defaults);
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
  public double getRange() {
    long radius = getNumber(NormalDistributionParams.radius, 256L).longValue();
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();
    return (radius - cr) * (radius + cr) * 4;
  }

  @Override
  public double xzToLocation(long x, long z) {
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();
    long cx = getNumber(NormalDistributionParams.centerX, 0L).longValue();
    long cz = getNumber(NormalDistributionParams.centerZ, 0L).longValue();

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
  public double xzToLocation(MutableRTPCoords coords) {
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();
    long cx = getNumber(NormalDistributionParams.centerX, 0L).longValue();
    long cz = getNumber(NormalDistributionParams.centerZ, 0L).longValue();

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
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();
    long cx = getNumber(NormalDistributionParams.centerX, 0L).longValue();
    long cz = getNumber(NormalDistributionParams.centerZ, 0L).longValue();

    // getFromString a distance from the center
    double radius = Math.sqrt(location + cr * cr * 4) / 2;

    // getFromString how far to step around the square
    double theta = radius - (long) radius;
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
    flushAndRebuild();
    long[] sums = badPrefixSumsCache;
    long badSum = (sums.length > 0) ? sums[sums.length - 1] : 0L;

    long radius = getNumber(NormalDistributionParams.radius, 256L).longValue();
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();
    double mean = getNumber(NormalDistributionParams.mean, 0.5).doubleValue();
    double deviation = getNumber(NormalDistributionParams.deviation, 1.0).doubleValue();
    double range = (radius - cr) * (radius + cr) * 4;

    boolean expand =
        Boolean.parseBoolean(data.getOrDefault(NormalDistributionParams.expand, false).toString());
    if (!expand) range -= badSum;

    mean = Math.abs(mean) % 1.0; // ensure mean 0.0-1.0
    deviation = Math.abs(deviation); // ensure deviation>0

    // get a valid number between 0 and 1
    // apply corrective deviation to get , apply requested deviation, shift over to mean
    // todo: find a way to approximate this without rejection sampling
    double gaussian;
    do {
      // approximately -4 to 4
      gaussian = ThreadLocalRandom.current().nextGaussian();

      // correct to -0.5 to 0.5
      gaussian = gaussian / 8;

      // apply requested deviation
      gaussian = gaussian * deviation;

      // shift over to requested mean
      gaussian = gaussian + mean;

      if (mean < 0.05 && gaussian < 0) gaussian = -gaussian;
      else if (mean > 0.95 && gaussian > 1) gaussian = 1 - gaussian;
    } while (gaussian < 0 || gaussian > 1); // reject values outside distribution

    // an approximation of the necessary exponent for 1d to 2d mapping
    // 0.5-1.0 depending on cr, so it shouldn't escape bounds
    double exponent = (1 + ((double) cr) / ((double) radius)) * 0.5;
    gaussian = Math.pow(gaussian, 1.0 / exponent);

    // expand to fit
    double res = (range) * (gaussian);

    String mode =
        data.getOrDefault(NormalDistributionParams.mode, "ACCUMULATE").toString().toUpperCase();

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

    Object unique = data.getOrDefault(NormalDistributionParams.uniquePlacements, false);
    boolean u;
    if (unique instanceof Boolean) u = (Boolean) unique;
    else {
      u = Boolean.parseBoolean(String.valueOf(unique));
      data.put(NormalDistributionParams.uniquePlacements, u);
    }
    if (u) addBadLocation(location, spatialResolution);

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
