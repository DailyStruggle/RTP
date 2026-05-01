package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.CoordinateParameter;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.FloatParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    // Curated tab-completion suggestions for /rtp shape:square_normal <TAB>.
    // Mirrors V2 sub-parameter UX so users see the format and scale.
    subParameters.put("mode", new EnumParameter<>(
        "rtp.params", "x-z position adjustment method", (sender, s) -> true, Mode.class));
    subParameters.put("radius", new IntegerParameter(
        "rtp.params", "outer radius of region", (sender, s) -> true, 64, 128, 256, 512, 1024));
    subParameters.put("centerradius", new IntegerParameter(
        "rtp.params", "inner radius of region", (sender, s) -> true, 16, 32, 64, 128, 256));
    subParameters.put("centerx", new CoordinateParameter(
        "rtp.params", "center point x", (sender, s) -> true));
    subParameters.put("centerz", new CoordinateParameter(
        "rtp.params", "center point z", (sender, s) -> true));
    subParameters.put("mean", new FloatParameter(
        "rtp.params", "distribution mean (0.0 = centerRadius, 1.0 = radius)", (sender, s) -> true, 0.0, 0.25, 0.5, 0.75, 1.0));
    subParameters.put("deviation", new FloatParameter(
        "rtp.params", "distribution standard deviation", (sender, s) -> true, 0.1, 0.5, 1.0, 2.0));
    subParameters.put("expand", new BooleanParameter(
        "rtp.params", "expand region to keep a constant amount of usable land", (sender, s) -> true));
    subParameters.put("uniqueplacements", new BooleanParameter(
        "rtp.params", "ensure each selection is unique from prior selections", (sender, s) -> true));
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
  public long getRange() {
    long radius = getNumber(NormalDistributionParams.radius, 256L).longValue();
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();
    return (radius - cr) * (radius + cr) * 4;
  }

  @Override
  public long xzToLocation(long x, long z) {
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
  public long xzToLocation(MutableRTPCoords coords) {
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

    // 1. Determine the integer Radius R
    double preciseRadius = Math.sqrt(location + cr * cr * 4) / 2.0;
    long R = (long) preciseRadius;

    // 2. Calculate Start Location (x=R, z=0 -> perimeterStep = 0)
    BigInteger bigR = BigInteger.valueOf(R);
    BigInteger bigCR = BigInteger.valueOf(cr);

    // StartLoc = (R^2 - CR^2) * 4
    BigInteger startLoc = bigR.multiply(bigR).subtract(bigCR.multiply(bigCR)).shiftLeft(2);

    // 3. Remaining Length and Ring Width (Perimeter growth in location units)
    BigInteger currentLoc = BigInteger.valueOf(location);
    BigInteger remainingLength = currentLoc.subtract(startLoc);
    // Width = ((R+1)^2 - R^2) * 4 = 4 * (2R + 1)
    BigInteger ringWidth = bigR.shiftLeft(1).add(BigInteger.ONE).shiftLeft(2);

    // 4. Proportion around the current square ring
    double theta = (remainingLength.doubleValue() / ringWidth.doubleValue()) + 0.000069;

    // 5. Perimeter Step
    double perimeterStep = 8.0 * (preciseRadius * (theta % 1.0));

    // 6. Map to Cartesian
    squareOct2Coords(R, perimeterStep, output);
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
    // Snapshot both arrays together to avoid races with concurrent rebuilds where
    // badKeysCache and badPrefixSumsCache may be observed at different lengths.
    long[] sums = badPrefixSumsCache;
    long[] keysSnap = badKeysCache;
    if (keysSnap.length != sums.length) {
      int common = Math.min(keysSnap.length, sums.length);
      if (keysSnap.length != common) keysSnap = Arrays.copyOf(keysSnap, common);
      if (sums.length != common) sums = Arrays.copyOf(sums, common);
    }
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
      gaussian = rng().nextGaussian();

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
              break;
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
