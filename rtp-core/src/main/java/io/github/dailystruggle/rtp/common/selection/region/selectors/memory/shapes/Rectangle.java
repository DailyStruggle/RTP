package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.CoordinateParameter;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
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
    subParameters.put("width", new IntegerParameter(
        "rtp.params", "region width", (sender, s) -> true, 64, 128, 256, 512, 1024));
    subParameters.put("height", new IntegerParameter(
        "rtp.params", "region height", (sender, s) -> true, 64, 128, 256, 512, 1024));
    subParameters.put("rotation", new IntegerParameter(
        "rtp.params", "rotation in degrees", (sender, s) -> true, 0, 30, 45, 60, 90));
    subParameters.put("centerx", new CoordinateParameter(
        "rtp.params", "center point x", (sender, s) -> true));
    subParameters.put("centerz", new CoordinateParameter(
        "rtp.params", "center point z", (sender, s) -> true));
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
    long cx = getNumber(RectangleParams.centerX, 0L).longValue() >> 4;
    long cz = getNumber(RectangleParams.centerZ, 0L).longValue() >> 4;
    long width = getNumber(RectangleParams.width, 256L).longValue() >> 4;
    long height = getNumber(RectangleParams.height, 256L).longValue() >> 4;
    long degrees = getNumber(RectangleParams.rotation, 0L).longValue();

    if (width == 0 || height == 0) return false;

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
   * Rectangle-specific inverse of {@link #xzToLocation(long, long)}.
   *
   * <p>The row-major curve makes the unrotated case a strict bijection: every
   * chunk in the rectangle has exactly one 1D index decode to it (so the
   * preimage is a 1-element array). When {@code rotation} is non-zero the
   * floating-point rotation matrix lets adjacent row indices round to the
   * same integer chunk, capped at 2 by the unit-chunk √2-diagonal argument
   * (the bound also held for the spiral shapes; the same upper bound applies
   * to any 1-chunk-spaced curve).
   *
   * <p>This override bypasses the default {@code MemoryShape.chunkToLocations}
   * angular-walk-with-{@code contains}-gate because {@link #contains(int, int)}
   * here is parameterised in <em>blocks</em> (its {@code >>4} conversion is
   * authored for the block-coordinate call sites), while the spiral curve and
   * the inverse contract are in <em>chunk</em> units. We re-implement the
   * gate using the row-major bounds directly.
   *
   * @param cx chunk x in the rectangle's chunk-unit coordinate system
   * @param cz chunk z in the rectangle's chunk-unit coordinate system
   * @return 0-, 1- or 2-element array of 1D indices; never {@code null}.
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

    // Invert {@link #locationToXZ(long, long)} directly. The forward map is:
    //   row = n / width, col = n % width
    //   x' = col - width/2, z' = row - height/2
    //   (x, z) = rotate((x', z'), degrees) + (centerX, centerZ)
    // So:
    //   (x', z') = rotateInverse((cx - centerX, cz - centerZ), degrees)
    //   col = x' + width/2, row = z' + height/2
    //   n = row * width + col
    // {@link #xzToLocation(long, long)} omits the {@code width/2}/{@code height/2}
    // shifts that {@code locationToXZ} subtracts, so it is *not* the true
    // inverse of {@code locationToXZ}; we cannot use it here.
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
    long[] keysSnap = badKeysCache;
    long[] sums = badPrefixSumsCache;
    if (keysSnap.length != sums.length) {
      int common = Math.min(keysSnap.length, sums.length);
      if (keysSnap.length != common) keysSnap = Arrays.copyOf(keysSnap, common);
      if (sums.length != common) sums = Arrays.copyOf(sums, common);
    }
    String mode = data.getOrDefault(RectangleParams.mode, "ACCUMULATE").toString();

    double range = getRange();

    double res = (range) * (rng().nextDouble());

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

    int uniqueRadius =
        uniquePlacementsRadius(data.getOrDefault(RectangleParams.uniquePlacements, 0));
    // addBadChunkRadius: chunk-uniform (uniqueplacements knob) — within a chunk the per-column
    // selection order is deterministic, so re-rolling onto the same chunk produces the
    // same effective placement. Marking the landing chunk (radius 1) prevents that chunk-level
    // re-roll; a larger radius additionally clears the surrounding chunks so placements spread out.
    if (uniqueRadius > 0) addBadChunkRadius(location, uniqueRadius);

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
