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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Square_Normal extends NormalMemoryShape {
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
    defaults.put(NormalDistributionParams.uniquePlacements, 0);

    // Curated tab-completion suggestions for /rtp shape:square_normal <TAB>.
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
        "rtp.params", "chunk radius cleared around each selection ('auto', 0 = off, 1 = landing chunk)", (sender, s) -> true, "auto", 0, 1, 2, 4, 8, 16));
  }

  /**
   * Returned for a cell inside {@code centerRadius}: not part of the region, so it has no index.
   * Any negative value is refused by {@code MemoryShape.addBadLocation}; an explicit sentinel is
   * needed because {@code centerRadius == 1} would otherwise map the excluded origin onto ring
   * 1's first index.
   */
  private static final long OUT_OF_DOMAIN = -1L;

  public Square_Normal() {
    super(NormalDistributionParams.class, "SQUARE_NORMAL", defaults);
  }

  public Square_Normal(String newName) {
    super(NormalDistributionParams.class, newName, defaults);
  }

  private static void squareOct2Coords(long radius, double perimeterStep, MutableRTPCoords output) {
    io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util.SquareGeometry.squareOct2Coords(radius, perimeterStep, output);
  }

  private static long perimeterStep(long x, long z, long radius) {
    return io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util.SquareGeometry.perimeterStep(x, z, radius);
  }

  private static int[] squareOct2Coords(long radius, double perimeterStep) {
    MutableRTPCoords output = new MutableRTPCoords(0, 0);
    squareOct2Coords(radius, perimeterStep, output);
    return new int[] {output.x, output.z};
  }

  private static long ringStart(long r, long cr) {
    return io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util.SquareGeometry.ringStart(r, cr);
  }

  private static long ringOf(long location, long cr) {
    return io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util.SquareGeometry.ringOf(location, cr);
  }

  @Override
  public long getRange() {
    long radius = getNumber(NormalDistributionParams.radius, 256L).longValue();
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();
    if (radius <= cr) return 0L;
    return ringStart(radius, cr);
  }

  @Override
  public long xzToLocation(long x, long z) {
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();
    long cx = getNumber(NormalDistributionParams.centerX, 0L).longValue();
    long cz = getNumber(NormalDistributionParams.centerZ, 0L).longValue();

    x = x - cx;
    z = z - cz;

    long radius = Math.max(Math.abs(x), Math.abs(z));
    if (radius < cr) return OUT_OF_DOMAIN;
    if (radius == 0L) return 0L;

    return ringStart(radius, cr) + perimeterStep(x, z, radius);
  }

  @Override
  public long xzToLocation(MutableRTPCoords coords) {
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();
    long cx = getNumber(NormalDistributionParams.centerX, 0L).longValue();
    long cz = getNumber(NormalDistributionParams.centerZ, 0L).longValue();

    long x = coords.x - cx;
    long z = coords.z - cz;

    long radius = Math.max(Math.abs(x), Math.abs(z));
    if (radius < cr) return OUT_OF_DOMAIN;
    if (radius == 0L) return 0L;

    return ringStart(radius, cr) + perimeterStep(x, z, radius);
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

    long r = ringOf(location, cr);
    if (r == 0L) {
      output.setXZ((int) cx, (int) cz);
      return;
    }
    squareOct2Coords(r, (double) (location - ringStart(r, cr)), output);
    output.setXZ(output.x + (int) cx, output.z + (int) cz);
  }

  // Selection (rand / select) is inherited from MemoryShape; the gaussian draw is inherited
  // from NormalMemoryShape. Square_Normal contributes only square geometry and its range.

  @Override
  public Map<String, CommandParameter> getParameters() {
    return subParameters;
  }

  @Override
  public Collection<String> keys() {
    return keys;
  }
}
