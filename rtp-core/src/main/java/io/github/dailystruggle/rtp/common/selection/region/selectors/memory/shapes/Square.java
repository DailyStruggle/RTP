package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.FloatParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.commands.parameters.DistanceParameter;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;

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
    subParameters.put("radius", new DistanceParameter(
        "rtp.params", "outer radius of region", (sender, s) -> true, 64, 128, 256, 512, 1024));
    subParameters.put("centerradius", new DistanceParameter(
        "rtp.params", "inner radius of region", (sender, s) -> true, 16, 32, 64, 128, 256));
    subParameters.put("centerx", new DistanceParameter(
        "rtp.params", "center point x", (sender, s) -> true, "~", "-~", "0"));
    subParameters.put("centerz", new DistanceParameter(
        "rtp.params", "center point z", (sender, s) -> true, "~", "-~", "0"));
    subParameters.put("weight", new FloatParameter(
        "rtp.params", "weigh towards or away from center", (sender, s) -> true, 0.1, 1.0, 10.0));
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

  /** Default constructor for Square */
  public Square() {
    super(GenericMemoryShapeParams.class, "SQUARE_DEPRECATED_PURE_SPIRAL", defaults);
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
    long radius = getNumber(GenericMemoryShapeParams.radius, 256L).longValue();
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    if (radius <= cr) return 0L;
    return ringStart(radius, cr);
  }

  /**
   * Exact 1D offset between two cells at the same angle on adjacent rings, for the square-spiral
   * parameterisation: ring {@code R} spans exactly its own {@code 8R} cells (see
   * {@link #ringStart(long, long)}).
   */
  @Override
  protected long neighbourRingOffset(int cx, int cz) {
    long centerX = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long centerZ = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();
    long dx = Math.abs((long) cx - centerX);
    long dz = Math.abs((long) cz - centerZ);
    long R = Math.max(dx, dz);
    if (R <= 0L) return 1L;
    return 8L * R;
  }

  @Override
  public long xzToLocation(long x, long z) {
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long cx = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cz = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    x = x - cx;
    z = z - cz;

    long radius = Math.max(Math.abs(x), Math.abs(z));
    if (radius < cr) return OUT_OF_DOMAIN;
    if (radius == 0L) return 0L;

    return ringStart(radius, cr) + perimeterStep(x, z, radius);
  }

  @Override
  public long xzToLocation(MutableRTPCoords coords) {
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long cx = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cz = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

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
    long cr = getNumber(GenericMemoryShapeParams.centerRadius, 64L).longValue();
    long cx = getNumber(GenericMemoryShapeParams.centerX, 0L).longValue();
    long cz = getNumber(GenericMemoryShapeParams.centerZ, 0L).longValue();

    long r = ringOf(location, cr);
    if (r == 0L) {
      output.setXZ((int) cx, (int) cz);
      return;
    }
    squareOct2Coords(r, (double) (location - ringStart(r, cr)), output);
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
