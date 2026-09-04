package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.FloatParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.commands.parameters.DistanceParameter;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;

import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Normal circle shape for region selection */
public class Circle_Normal extends NormalMemoryShape {
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
    defaults.put(NormalDistributionParams.uniquePlacements, 0);

    // Curated tab-completion suggestions for /rtp shape:circle_normal <TAB>.
    // Mirrors V2 sub-parameter UX so users see the format and scale.
    subParameters.put("mode", new EnumParameter<>(
        "rtp.params", "x-z position adjustment method", (sender, s) -> true, Mode.class));
    subParameters.put("radius", new DistanceParameter(
        "rtp.params", "outer radius of region", (sender, s) -> true, 64, 128, 256, 512, 1024));
    subParameters.put("centerradius", new DistanceParameter(
        "rtp.params", "inner radius of region", (sender, s) -> true, 16, 32, 64, 128, 256));
    subParameters.put("centerx", new DistanceParameter(
        "rtp.params", "center point x", (sender, s) -> true, "~", "-~", "0"));
    subParameters.put("centerz", new DistanceParameter(
        "rtp.params", "center point z", (sender, s) -> true, "~", "-~", "0"));
    subParameters.put("mean", new FloatParameter(
        "rtp.params", "distribution mean (0.0 = centerRadius, 1.0 = radius)", (sender, s) -> true, 0.0, 0.25, 0.5, 0.75, 1.0));
    subParameters.put("deviation", new FloatParameter(
        "rtp.params", "distribution standard deviation", (sender, s) -> true, 0.1, 0.5, 1.0, 2.0));
    subParameters.put("expand", new BooleanParameter(
        "rtp.params", "expand region to keep a constant amount of usable land", (sender, s) -> true));
    subParameters.put("uniqueplacements", new IntegerParameter(
        "rtp.params", "chunk radius cleared around each selection (0 = off, 1 = landing chunk)", (sender, s) -> true, 0, 1, 2, 4, 8));
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

  // Selection (rand / select) is inherited from MemoryShape; the gaussian draw is inherited
  // from NormalMemoryShape. Circle_Normal contributes only circle geometry and its range.
}
