package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** Normal circle shape for region selection */
public class Circle_Normal extends MemoryShape<NormalDistributionParams> {
  /** Sub-parameters for Circle_Normal commands */
  protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();

  /** List of keys for Circle_Normal parameters */
  protected static final List<String> keys =
      Arrays.stream(NormalDistributionParams.values()).map(Enum::name).collect(Collectors.toList());

  /** Default parameters for Circle_Normal */
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

  /** Default constructor for Circle_Normal */
  public Circle_Normal() {
    super(NormalDistributionParams.class, "CIRCLE_NORMAL", defaults);
  }

  /**
   * Constructor for Circle_Normal with a custom name
   *
   * @param newName the name of the shape
   */
  public Circle_Normal(String newName) {
    super(NormalDistributionParams.class, newName, defaults);
  }

  @Override
  public long getRange() {
    long radius = getNumber(NormalDistributionParams.radius, 256L).longValue();
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();
    return (long) ((radius - cr) * (radius + cr) * Math.PI);
  }

  @Override
  public long xzToLocation(long x, long z) {
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();
    long cx = getNumber(NormalDistributionParams.centerX, 0L).longValue();
    long cz = getNumber(NormalDistributionParams.centerZ, 0L).longValue();

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
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();
    long cx = getNumber(NormalDistributionParams.centerX, 0L).longValue();
    long cz = getNumber(NormalDistributionParams.centerZ, 0L).longValue();

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

    return (long)(((radius * radius - cr * cr) * Math.PI) + rotation * (2 * radius * Math.PI));
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
    output.setXZ((int) (preciseRadius * cosRes + cx + 0.5), (int) (preciseRadius * sinRes + cz + 0.5));
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

  /**
   * Get a random location value within the shape using normal distribution
   *
   * @return the random location value
   */
  @Override
  public long rand() {
    flushAndRebuild(spatialResolution);
    long[] sums = badPrefixSumsCache;
    long badSum = (sums.length > 0) ? sums[sums.length - 1] : 0L;

    long radius = getNumber(NormalDistributionParams.radius, 256L).longValue();
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();
    double mean = getNumber(NormalDistributionParams.mean, 0.5).doubleValue();
    double deviation = getNumber(NormalDistributionParams.deviation, 1.0).doubleValue();
    double range = (radius - cr) * (radius + cr) * Math.PI;

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
    double originalGaussian = gaussian;
    gaussian = Math.pow(gaussian, 1.0 / exponent);

    // expand to fit
    double res = (range) * (gaussian);

    String mode =
        data.getOrDefault(NormalDistributionParams.mode, "ACCUMULATE").toString().toUpperCase();

    RTP.log(
        Level.INFO,
        "[RTP] Circle_Normal.rand() - name:"
            + name
            + ", radius:"
            + radius
            + ", centerRadius:"
            + cr
            + ", mean:"
            + mean
            + ", deviation:"
            + deviation
            + ", range:"
            + range
            + ", badSum:"
            + badSum
            + ", expand:"
            + expand
            + ", mode:"
            + mode
            + ", gaussian(before pow):"
            + originalGaussian
            + ", gaussian(after pow):"
            + gaussian
            + ", res:"
            + res);

    long location;
    if (mode.equalsIgnoreCase("ACCUMULATE")) {
      long target = (long) res;
      int index = java.util.Arrays.binarySearch(badKeysCache, target);
      if (index < 0) index = -index - 1;

      if (index > 0) target += sums[index - 1];
      location = target;
      RTP.log(Level.INFO, "[RTP] Circle_Normal.rand() ACCUMULATE - target:" + target);
    } else {
      location = (long) res;
    }

    RTP.log(Level.INFO, "[RTP] Circle_Normal.rand() - final location:" + location);

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
            RTP.log(Level.INFO, "[RTP] Circle_Normal.rand() NEAREST - new location:" + location);
          }
        }
      case "REROLL":
        {
          if (isKnownBad(location)) {
            RTP.log(Level.INFO, "[RTP] Circle_Normal.rand() REROLL - bad location, returning -1");
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
}
