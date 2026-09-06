package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.FloatParameter;
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
    defaults.put(NormalDistributionParams.uniquePlacements, false);

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
    subParameters.put("uniqueplacements", new BooleanParameter(
        "rtp.params", "ensure each selection is unique from prior selections", (sender, s) -> true));
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

  /**
   * Perimeter index of a ring cell, counted counter-clockwise from {@code (R,0)}.
   *
   * <p>Integer edge tests, not a floating-point angle: {@code atan(z/x)} folded into a quarter
   * turn reflects quadrants 2 and 4 and collapses the axis cases, so the two cells meeting at
   * every octant seam shared one index (e.g. {@code (-R,0)} and {@code (-R,R)}). Exact inverse of
   * {@link #squareOct2Coords(long, double, MutableRTPCoords)}: the {@code 8R} cells of ring
   * {@code R} map bijectively onto {@code [0, 8R)}. Corner ownership follows the test order -
   * top edge, then left, then bottom, leaving the right edge with the wrap at {@code z < 0}.
   *
   * @param x      ring-relative x, with {@code max(|x|,|z|) == radius}
   * @param z      ring-relative z
   * @param radius Chebyshev radius of the ring
   * @return perimeter index in {@code [0, 8 * radius)}, or {@code 0} for the centre cell
   */
  private static long perimeterStep(long x, long z, long radius) {
    if (radius == 0L) return 0L;
    if (z == radius) return (radius * 2L) - x;
    if (x == -radius) return (radius * 4L) - z;
    if (z == -radius) return (radius * 6L) + x;
    return (z >= 0L) ? z : ((radius * 8L) + z);
  }

  private static int[] squareOct2Coords(long radius, double perimeterStep) {
    MutableRTPCoords output = new MutableRTPCoords(0, 0);
    squareOct2Coords(radius, perimeterStep, output);
    return new int[] {output.x, output.z};
  }

  /**
   * First index of ring {@code r}, i.e. the index of {@code (r, 0)}.
   *
   * <p>Ring {@code r} holds exactly {@code 8r} cells, so allotting it exactly {@code 8r} indices
   * makes the index space the cell count and the map a bijection. Summing that over
   * {@code [cr, r)} telescopes to {@code 4(r(r-1) - cr(cr-1))}. The earlier allotment,
   * {@code 4(r^2 - cr^2)}, gave each ring {@code 8r + 4} indices - 4 more than it has cells - so
   * the reverse map had to alias 4 cells per ring.
   *
   * <p>With {@code cr == 0} the origin is a one-cell ring that owns index {@code 0}, hence the
   * {@code +1} shift on every other ring.
   */
  private static long ringStart(long r, long cr) {
    long base = ((r * (r - 1L)) - (cr * (cr - 1L))) * 4L;
    return (cr == 0L) ? base + 1L : base;
  }

  /**
   * Ring holding {@code location}, i.e. the largest {@code r} with {@code ringStart(r) <= location}.
   *
   * <p>{@code floor(location / 4) + cr(cr-1)} lies in {@code [r(r-1), r(r+1))} exactly, so the
   * {@code sqrt} only seeds the answer; the correction steps remove double rounding at large radii,
   * where a half-ulp error would otherwise place a cell on the wrong ring.
   */
  private static long ringOf(long location, long cr) {
    if (cr == 0L) {
      if (location <= 0L) return 0L;
      location -= 1L;
    }
    long target = (location / 4L) + (cr * (cr - 1L));
    long r = (long) ((1.0 + Math.sqrt(1.0 + (4.0 * (double) target))) / 2.0);
    if (r < 1L) r = 1L;
    while (r > 1L && (r * (r - 1L)) > target) r--;
    while (((r + 1L) * r) <= target) r++;
    return r;
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
