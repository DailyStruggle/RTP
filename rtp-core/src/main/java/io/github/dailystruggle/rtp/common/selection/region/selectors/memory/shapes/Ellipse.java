package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.CoordinateParameter;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.FloatParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.EllipseMemoryShapeParams;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Ellipse shape for region selection.
 *
 * <p>Carries a second axis radius ({@link EllipseMemoryShapeParams#radius2});
 * the bounding circle and selection math use the wider of {@code radius} and
 * {@code radius2}. Other selection mechanics mirror {@link Circle}'s
 * Archimedean-spiral 1D mapping, but the type is parameterised over
 * {@link EllipseMemoryShapeParams} rather than {@code GenericMemoryShapeParams}
 * so single-radius shapes (Circle, Square) are not burdened with a meaningless
 * {@code radius2} default.
 */
public class Ellipse extends MemoryShape<EllipseMemoryShapeParams> {
  /** Default parameters for Ellipse. */
  protected static final EnumMap<EllipseMemoryShapeParams, Object> defaults =
      new EnumMap<>(EllipseMemoryShapeParams.class);

  /** Sub-parameters for Ellipse commands. */
  protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();

  /** Cached list of parameter keys. */
  protected static final List<String> keys =
      Arrays.stream(EllipseMemoryShapeParams.values()).map(Enum::name).collect(Collectors.toList());

  static {
    try {
      defaults.put(EllipseMemoryShapeParams.mode, Mode.ACCUMULATE);
      defaults.put(EllipseMemoryShapeParams.radius, 256);
      defaults.put(EllipseMemoryShapeParams.radius2, 256);
      defaults.put(EllipseMemoryShapeParams.centerRadius, 64);
      defaults.put(EllipseMemoryShapeParams.centerRadius2, 64);
      defaults.put(EllipseMemoryShapeParams.rotation, 0);
      defaults.put(EllipseMemoryShapeParams.centerX, 0);
      defaults.put(EllipseMemoryShapeParams.centerZ, 0);
      defaults.put(EllipseMemoryShapeParams.weight, 1.0);
      defaults.put(EllipseMemoryShapeParams.uniquePlacements, false);
      defaults.put(EllipseMemoryShapeParams.expand, false);

      subParameters.put("mode", new EnumParameter<>(
          "rtp.params", "x-z position adjustment method", (sender, s) -> true, Mode.class));
      subParameters.put("radius", new IntegerParameter(
          "rtp.params", "first axis radius of region", (sender, s) -> true, 64, 128, 256, 512, 1024));
      subParameters.put("radius2", new IntegerParameter(
          "rtp.params", "second axis radius of region", (sender, s) -> true, 64, 128, 256, 512, 1024));
      subParameters.put("centerradius", new IntegerParameter(
          "rtp.params", "inner radius of region", (sender, s) -> true, 16, 32, 64, 128, 256));
      subParameters.put("centerradius2", new IntegerParameter(
          "rtp.params", "second axis of inner exclusion region", (sender, s) -> true, 16, 32, 64, 128, 256));
      subParameters.put("rotation", new IntegerParameter(
          "rtp.params", "rotation in degrees", (sender, s) -> true, 0, 30, 45, 60, 90));
      subParameters.put("centerx", new CoordinateParameter(
          "rtp.params", "center point x", (sender, s) -> true));
      subParameters.put("centerz", new CoordinateParameter(
          "rtp.params", "center point z", (sender, s) -> true));
      subParameters.put("weight", new FloatParameter(
          "rtp.params", "weigh towards or away from center", (sender, s) -> true, 0.1, 1.0, 10.0));
      subParameters.put("expand", new BooleanParameter(
          "rtp.params", "expand region to keep a constant amount of usable land", (sender, s) -> true));
      subParameters.put("uniqueplacements", new BooleanParameter(
          "rtp.params", "ensure each selection is unique from prior selections", (sender, s) -> true));
    } catch (Exception e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  /**
   * Default constructor.
   *
   * @throws IllegalArgumentException if default parameters are invalid
   */
  public Ellipse() throws IllegalArgumentException {
    super(EllipseMemoryShapeParams.class, "ELLIPSE", defaults);
  }

  /**
   * Constructor with a custom name (for cloning / config-derived shapes).
   *
   * @param newName the name of the shape
   * @throws IllegalArgumentException if parameters are invalid
   */
  public Ellipse(String newName) throws IllegalArgumentException {
    super(EllipseMemoryShapeParams.class, newName, defaults);
  }

  /**
   * @return the wider of {@code radius} and {@code radius2}
   */
  private long effectiveRadius() {
    long r1 = getNumber(EllipseMemoryShapeParams.radius, 256L).longValue();
    long r2 = getNumber(EllipseMemoryShapeParams.radius2, 256L).longValue();
    return Math.max(r1, r2);
  }

  /**
   * @return the wider of {@code centerRadius} and {@code centerRadius2}
   */
  private long effectiveCenterRadius() {
    long c1 = getNumber(EllipseMemoryShapeParams.centerRadius, 64L).longValue();
    long c2 = getNumber(EllipseMemoryShapeParams.centerRadius2, 64L).longValue();
    return Math.max(c1, c2);
  }

  @Override
  public long getRange() {
    long radius = effectiveRadius();
    long cr = effectiveCenterRadius();
    return (long) ((radius - cr) * (radius + cr) * Math.PI);
  }

  /** @see Circle#neighbourRingOffset(int, int) */
  @Override
  protected long neighbourRingOffset(int cx, int cz) {
    long centerX = getNumber(EllipseMemoryShapeParams.centerX, 0L).longValue();
    long centerZ = getNumber(EllipseMemoryShapeParams.centerZ, 0L).longValue();
    long dx = (long) cx - centerX;
    long dz = (long) cz - centerZ;
    long R = (long) Math.sqrt((double) (dx * dx + dz * dz));
    if (R < 0L) return 0L;
    return Math.round(Math.PI * (2.0 * R + 1.0));
  }

  @Override
  public long xzToLocation(long x, long z) {
    long cr = effectiveCenterRadius();
    long cx = getNumber(EllipseMemoryShapeParams.centerX, 0L).longValue();
    long cz = getNumber(EllipseMemoryShapeParams.centerZ, 0L).longValue();
    long degrees = getNumber(EllipseMemoryShapeParams.rotation, 0L).longValue();

    x = x - cx;
    z = z - cz;

    if (degrees != 0L) {
      int[] rotated = rotate(new int[] {(int) x, (int) z}, -degrees);
      x = rotated[0];
      z = rotated[1];
    }

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
    return xzToLocation(coords.x, coords.z);
  }

  @Override
  public int[] locationToXZ(long location) {
    MutableRTPCoords output = new MutableRTPCoords(0, 0);
    locationToXZ(location, output);
    return new int[] {output.x, output.z};
  }

  @Override
  public void locationToXZ(long location, MutableRTPCoords output) {
    long cr = effectiveCenterRadius();
    long cx = getNumber(EllipseMemoryShapeParams.centerX, 0L).longValue();
    long cz = getNumber(EllipseMemoryShapeParams.centerZ, 0L).longValue();
    long degrees = getNumber(EllipseMemoryShapeParams.rotation, 0L).longValue();

    double preciseRadius = Math.sqrt((double) location / Math.PI + cr * cr);
    long R = (long) preciseRadius;

    BigInteger bigR = BigInteger.valueOf(R);
    BigInteger bigCR = BigInteger.valueOf(cr);

    BigInteger startLoc = bigR.multiply(bigR).subtract(bigCR.multiply(bigCR));

    BigInteger currentLocation = BigInteger.valueOf((long) (location / Math.PI));
    BigInteger remainingLength = currentLocation.subtract(startLoc);
    BigInteger totalRingLength = bigR.shiftLeft(1).add(BigInteger.ONE);

    double proportion = remainingLength.doubleValue() / totalRingLength.doubleValue();
    double rotation = (proportion + 0.000069) * 2.0 * Math.PI;

    double cosRes = Math.cos(rotation);
    double sinRes = Math.sin(rotation);

    int px = (int) (R * cosRes + 0.5);
    int pz = (int) (R * sinRes + 0.5);
    if (degrees != 0L) {
      int[] rotated = rotate(new int[] {px, pz}, degrees);
      px = rotated[0];
      pz = rotated[1];
    }
    output.setXZ(px + (int) cx, pz + (int) cz);
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
    long[] sums = badPrefixSumsCache;
    long[] keysSnap = badKeysCache;
    if (keysSnap.length != sums.length) {
      int common = Math.min(keysSnap.length, sums.length);
      if (keysSnap.length != common) keysSnap = Arrays.copyOf(keysSnap, common);
      if (sums.length != common) sums = Arrays.copyOf(sums, common);
    }
    long badSum = (sums.length > 0) ? sums[sums.length - 1] : 0L;

    double range = getRange();
    boolean expand = (boolean) data.getOrDefault(EllipseMemoryShapeParams.expand, false);
    String mode =
        data.getOrDefault(EllipseMemoryShapeParams.mode, "ACCUMULATE").toString().toUpperCase();

    if ((!expand) && mode.equalsIgnoreCase("ACCUMULATE")) range -= badSum;
    else if (expand && !mode.equalsIgnoreCase("ACCUMULATE")) range += badSum;

    double weight = getNumber(EllipseMemoryShapeParams.weight, 1.0).doubleValue();
    double res = (range) * Math.pow(rng().nextDouble(), weight);

    long location;
    if (mode.equalsIgnoreCase("ACCUMULATE")) {
      long target = (long) res;
      long currentBadSum = 0;

      while (true) {
        int index = Arrays.binarySearch(keysSnap, target + currentBadSum);

        if (index < 0) {
          index = -index - 1;
        } else {
          index = index + 1;
        }

        if (index > sums.length) index = sums.length;

        long newBadSum = (index > 0) ? sums[index - 1] : 0;

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
            long[] keysArr = keysSnap;
            int idx = Arrays.binarySearch(keysArr, location);
            int floorIdx = (idx >= 0) ? idx : -(idx + 1) - 1;
            if (floorIdx < 0 || floorIdx >= sums.length) {
              return location;
            }

            long key = keysArr[floorIdx];
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

    Object unique = data.getOrDefault(EllipseMemoryShapeParams.uniquePlacements, false);
    boolean u;
    if (unique instanceof Boolean) u = (Boolean) unique;
    else {
      u = Boolean.parseBoolean(String.valueOf(unique));
      data.put(EllipseMemoryShapeParams.uniquePlacements, u);
    }
    if (u) addBadChunk(location);

    return location;
  }

  @Override
  public boolean contains(int x, int z) {
    long cx = getNumber(EllipseMemoryShapeParams.centerX, 0L).longValue();
    long cz = getNumber(EllipseMemoryShapeParams.centerZ, 0L).longValue();
    long r1 = getNumber(EllipseMemoryShapeParams.radius, 256L).longValue();
    long r2 = getNumber(EllipseMemoryShapeParams.radius2, 256L).longValue();
    long c1 = getNumber(EllipseMemoryShapeParams.centerRadius, 64L).longValue();
    long c2 = getNumber(EllipseMemoryShapeParams.centerRadius2, 64L).longValue();
    long degrees = getNumber(EllipseMemoryShapeParams.rotation, 0L).longValue();

    long dx = x - cx;
    long dz = z - cz;

    if (degrees != 0L) {
      int[] rotated = rotate(new int[] {(int) dx, (int) dz}, -degrees);
      dx = rotated[0];
      dz = rotated[1];
    }

    // outer ellipse: (dx/r1)^2 + (dz/r2)^2 < 1
    double outer = (r1 == 0 || r2 == 0)
        ? Double.POSITIVE_INFINITY
        : ((double) dx * dx) / ((double) r1 * r1) + ((double) dz * dz) / ((double) r2 * r2);
    if (outer >= 1.0) return false;

    // inner ellipse exclusion: (dx/c1)^2 + (dz/c2)^2 < 1 means inside the hole
    if (c1 <= 0 || c2 <= 0) return true;
    double inner = ((double) dx * dx) / ((double) c1 * c1) + ((double) dz * dz) / ((double) c2 * c2);
    return inner >= 1.0;
  }
}
