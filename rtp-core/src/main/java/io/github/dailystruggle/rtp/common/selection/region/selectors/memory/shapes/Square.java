package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.CoordinateParameter;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.FloatParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    defaults.put(GenericMemoryShapeParams.uniquePlacements, 0);

    // Curated tab-completion suggestions for /rtp shape:square <TAB>.
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
    subParameters.put("weight", new FloatParameter(
        "rtp.params", "weigh towards or away from center", (sender, s) -> true, 0.1, 1.0, 10.0));
    subParameters.put("expand", new BooleanParameter(
        "rtp.params", "expand region to keep a constant amount of usable land", (sender, s) -> true));
    subParameters.put("uniqueplacements", new IntegerParameter(
        "rtp.params", "chunk radius cleared around each selection (0 = off, 1 = landing chunk)", (sender, s) -> true, 0, 1, 2, 4, 8));
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

  /**
   * Exact 1D offset between two cells at the same angle on adjacent rings,
   * for the square-spiral parameterisation: the {@code R}-th ring spans
   * {@code 8R + 4} indices ({@code 4·((R+1)² − R²)} from
   * {@link #xzToLocation(long, long)}).
   */
  @Override
  protected long neighbourRingOffset(int cx, int cz) {
    long centerX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long centerZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();
    long dx = Math.abs((long) cx - centerX);
    long dz = Math.abs((long) cz - centerZ);
    long R = Math.max(dx, dz);
    if (R < 0L) return 0L;
    return 8L * R + 4L;
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

  // Selection (rand / select) is inherited from MemoryShape: Square contributes only its
  // range and the default weighted power curve, which is the base sampling model.

  @Override
  public Map<String, CommandParameter> getParameters() {
    return subParameters;
  }

  @Override
  public Collection<String> keys() {
    return keys;
  }
}
