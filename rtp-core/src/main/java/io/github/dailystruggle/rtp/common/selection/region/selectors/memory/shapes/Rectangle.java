package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.commands.parameters.DistanceParameter;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Rectangle shape for region selection */
public class Rectangle extends MemoryShape<RectangleParams> {
  protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();
  protected static final List<String> keys =
      Arrays.stream(RectangleParams.values()).map(Enum::name).collect(Collectors.toList());
  protected static final EnumMap<RectangleParams, Object> defaults =
      new EnumMap<>(RectangleParams.class);

  static {
    defaults.put(RectangleParams.mode, Mode.ACCUMULATE);
    defaults.put(RectangleParams.width, 256);
    defaults.put(RectangleParams.height, 256);
    defaults.put(RectangleParams.centerX, 0);
    defaults.put(RectangleParams.centerZ, 0);
    defaults.put(RectangleParams.rotation, 0);
    defaults.put(RectangleParams.uniquePlacements, 0);

    // Curated tab-completion suggestions for /rtp shape:rectangle <TAB>.
    // Mirrors V2 sub-parameter UX so users see the format and scale.
    subParameters.put("mode", new EnumParameter<>(
        "rtp.params", "x-z position adjustment method", (sender, s) -> true, Mode.class));
    subParameters.put("width", new DistanceParameter(
        "rtp.params", "region width", (sender, s) -> true, 64, 128, 256, 512, 1024));
    subParameters.put("height", new DistanceParameter(
        "rtp.params", "region height", (sender, s) -> true, 64, 128, 256, 512, 1024));
    subParameters.put("length", new DistanceParameter(
        "rtp.params", "region length", (sender, s) -> true, 64, 128, 256, 512, 1024));
    subParameters.put("rotation", new IntegerParameter(
        "rtp.params", "rotation in degrees", (sender, s) -> true, 0, 30, 45, 60, 90));
    subParameters.put("centerx", new DistanceParameter(
        "rtp.params", "center point x", (sender, s) -> true, "~", "-~", "0"));
    subParameters.put("centerz", new DistanceParameter(
        "rtp.params", "center point z", (sender, s) -> true, "~", "-~", "0"));
    subParameters.put("uniqueplacements", new IntegerParameter(
        "rtp.params", "chunk radius cleared around each selection (0 = off, 1 = landing chunk)", (sender, s) -> true, 0, 1, 2, 4, 8));
  }

  /** Default constructor for Rectangle */
  public Rectangle() {
    super(RectangleParams.class, "RECTANGLE", defaults);
  }

  /**
   * Constructor for Rectangle with a custom name
   *
   * @param newName the name of the shape
   */
  public Rectangle(String newName) {
    super(RectangleParams.class, newName, defaults);
  }

  @Override
  public long getRange() {
    long w = getNumber(RectangleParams.width, 256L).longValue();
    long h = getNumber(RectangleParams.height, 256L).longValue();
    return w * h;
  }

  @Override
  public long xzToLocation(long x, long z) {
    long degrees = getNumber(RectangleParams.rotation, 0L).longValue();
    long cx = getNumber(RectangleParams.centerX, 0L).longValue();
    long cz = getNumber(RectangleParams.centerZ, 0L).longValue();
    long width = getNumber(RectangleParams.width, 256L).longValue();

    // shift point back to origin:
    x -= cx;
    z -= cz;

    int[] input = new int[] {(int) x, (int) z};

    input = rotate(input, -degrees);

    // translate to position
    return input[1] * width + input[0];
  }

  @Override
  public boolean contains(int x, int z) {
    long cx = getNumber(RectangleParams.centerX, 0L).longValue();
    long cz = getNumber(RectangleParams.centerZ, 0L).longValue();
    long width = getNumber(RectangleParams.width, 256L).longValue();
    long height = getNumber(RectangleParams.height, 256L).longValue();
    long degrees = getNumber(RectangleParams.rotation, 0L).longValue();

    if (width <= 0 || height <= 0) return false;

    if (degrees == 0) {
      return Math.abs(x - cx) <= width / 2 && Math.abs(z - cz) <= height / 2;
    }

    // shift point back to origin:
    int dx = (int) (x - cx);
    int dz = (int) (z - cz);

    int[] input = new int[] {dx, dz};
    input = rotate(input, -degrees);

    return Math.abs(input[0]) <= width / 2 && Math.abs(input[1]) <= height / 2;
  }



  @Override
  public long xzToLocation(MutableRTPCoords coords) {
    long degrees = getNumber(RectangleParams.rotation, 0L).longValue();
    long cx = getNumber(RectangleParams.centerX, 0L).longValue();
    long cz = getNumber(RectangleParams.centerZ, 0L).longValue();
    long width = getNumber(RectangleParams.width, 256L).longValue();

    // shift point back to origin:
    long x = coords.x - cx;
    long z = coords.z - cz;

    int[] input = new int[] {(int) x, (int) z};

    input = rotate(input, -degrees);

    // translate to position
    return input[1] * width + input[0];
  }

  /**
   * Rectangle inverse mapping for {@code chunkToLocations(cx, cz)}.
   * Unrotated case is a strict bijection (1 preimage); rotated case bounded by <= 2 preimages.
   *
   * @param cx chunk x in rectangle chunk-units
   * @param cz chunk z in rectangle chunk-units
   * @return 0-, 1-, or 2-element array of 1D indices; never {@code null}
   */
  @Override
  public long[] chunkToLocations(int cx, int cz) {
    final long range = getRange();
    if (range <= 0L) return EMPTY_LONG_ARRAY;

    final long width = getNumber(RectangleParams.width, 256L).longValue();
    final long height = getNumber(RectangleParams.height, 256L).longValue();
    final long degrees = getNumber(RectangleParams.rotation, 0L).longValue();
    final long ccx = getNumber(RectangleParams.centerX, 0L).longValue();
    final long ccz = getNumber(RectangleParams.centerZ, 0L).longValue();

    // Invert locationToXZ: centered -> unrotated col/row -> representative 1D index
    int[] centered = rotate(new int[]{(int) (cx - ccx), (int) (cz - ccz)}, -degrees);
    long col = centered[0] + (width / 2);
    long row = centered[1] + (height / 2);
    long representative = -1L;
    if (col >= 0 && col < width && row >= 0 && row < height) {
      representative = row * width + col;
    }

    long first = -1L;
    long second = -1L;
    if (representative >= 0L && representative < range) {
      int[] decoded = locationToXZ(representative);
      if (decoded[0] == cx && decoded[1] == cz) first = representative;
    }

    if (degrees == 0L) {
      // Unrotated: bijection. Either the representative is right, or the
      // chunk is outside the rectangle. No second preimage by design.
      return (first >= 0L) ? new long[]{first} : EMPTY_LONG_ARRAY;
    }

    // Rotated: probe row/column neighbours (±1, ±width). The floating-point
    // rotation in {@link #locationToXZ(long, long)} can round two adjacent
    // grid cells to the same integer chunk; ≤ 2 by the unit-chunk √2 bound.
    long base = (first >= 0L) ? first : representative;
    if (base >= 0L) {
      for (long off : new long[]{1L, -1L, width, -width}) {
        long cand = base + off;
        if (cand < 0L || cand >= range) continue;
        int[] decoded = locationToXZ(cand);
        if (decoded[0] == cx && decoded[1] == cz) {
          if (first < 0L) first = cand;
          else if (cand != first) { second = cand; break; }
        }
      }
    }
    if (first < 0L) return EMPTY_LONG_ARRAY;
    if (second < 0L) return new long[]{first};
    return (first <= second) ? new long[]{first, second} : new long[]{second, first};
  }

  @Override
  public int[] locationToXZ(long location) {
    MutableRTPCoords output = new MutableRTPCoords(0, 0);
    locationToXZ(location, output);
    return new int[] {output.x, output.z};
  }

  @Override
  public void locationToXZ(long location, MutableRTPCoords output) {
    long degrees = getNumber(RectangleParams.rotation, 0L).longValue();
    long cx = getNumber(RectangleParams.centerX, 0L).longValue();
    long cz = getNumber(RectangleParams.centerZ, 0L).longValue();
    long width = getNumber(RectangleParams.width, 256L).longValue();
    long height = getNumber(RectangleParams.height, 256L).longValue();

    // compute initial xz
    output.setXZ((int) (location % width), (int) (location / width));

    // center
    output.setXZ(output.x - (int) (width / 2), output.z - (int) (height / 2));

    // rotate around origin
    rotate(output, degrees);

    // shift
    output.setXZ(output.x + (int) cx, output.z + (int) cz);
  }

  // Selection (rand / select) is inherited from MemoryShape. Rectangle exposes neither a
  // 'weight' nor an 'expand' knob, so the base hooks resolve to exactly its historical
  // behaviour: a plain uniform draw over the unadjusted range.

  @Override
  public Map<String, CommandParameter> getParameters() {
    return subParameters;
  }

  @Override
  public Collection<String> keys() {
    return keys;
  }
}
