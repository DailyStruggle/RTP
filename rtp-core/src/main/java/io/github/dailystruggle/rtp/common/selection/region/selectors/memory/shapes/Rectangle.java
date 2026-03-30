package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ThreadLocalRandom;
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
    defaults.put(RectangleParams.uniquePlacements, false);

    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
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
  public double getRange() {
    long w = getNumber(RectangleParams.width, 256L).longValue();
    long h = getNumber(RectangleParams.height, 256L).longValue();
    return w * h;
  }

  @Override
  public double xzToLocation(long x, long z) {
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
  public double xzToLocation(MutableRTPCoords coords) {
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
    String mode = data.getOrDefault(RectangleParams.mode, "ACCUMULATE").toString();

    double range = getRange();

    double res = (range) * (ThreadLocalRandom.current().nextDouble());

    long location;
    if (mode.equalsIgnoreCase("ACCUMULATE")) {
      rebuildCacheIfNeeded();
      long target = (long) res;
      int index = java.util.Arrays.binarySearch(badPrefixSumsCache, target);
      if (index < 0) index = -index - 1;

      if (index > 0) target += badPrefixSumsCache[index - 1];
      location = target;
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
          ConcurrentSkipListMap<Long, Long> map = badLocations;
          Map.Entry<Long, Long> check = map.floorEntry(location);

          if ((check != null)
              && (location >= check.getKey())
              && (location < (check.getKey() + check.getValue()))) {
            Map.Entry<Long, Long> lower = map.floorEntry(check.getKey() - 1);
            Map.Entry<Long, Long> upper = map.ceilingEntry(check.getKey() + check.getValue());

            if (upper == null) {
              if (lower == null) {
                long cutout = check.getValue();
                location = ThreadLocalRandom.current().nextLong((long) (range - cutout));
                if (location >= check.getKey()) location += check.getValue();
              } else {
                long len = check.getKey() - (lower.getKey() + lower.getValue());
                location = (len <= 0) ? 0 : ThreadLocalRandom.current().nextLong(len);
                location += lower.getKey() + lower.getValue();
              }
            } else if (lower == null) {
              long len = upper.getKey() - (check.getKey() + check.getValue());
              location = (len <= 0) ? 0 : ThreadLocalRandom.current().nextLong(len);
              location += check.getKey() + check.getValue();
            } else {
              long d1 = (upper.getKey() - location);
              long d2 = location - (lower.getKey() + lower.getValue());
              if (d2 > d1) {
                long len = check.getKey() - (lower.getKey() + lower.getValue());
                location = (len <= 0) ? 0 : ThreadLocalRandom.current().nextLong(len);
                location += lower.getKey() + lower.getValue();
              } else {
                long len = upper.getKey() - (check.getKey() + check.getValue());
                location = (len <= 0) ? 0 : ThreadLocalRandom.current().nextLong(len);
                location += check.getKey() + check.getValue();
              }
            }
          }
        }
      case "REROLL":
        {
          Map.Entry<Long, Long> check = badLocations.floorEntry(location);
          if ((check != null)
              && (location > check.getKey())
              && (location < check.getKey() + check.getValue())) {
            return -1;
          }
        }
      default:
        {
        }
    }

    Object unique = data.getOrDefault(RectangleParams.uniquePlacements, false);
    boolean u;
    if (unique instanceof Boolean) u = (Boolean) unique;
    else {
      u = Boolean.parseBoolean(String.valueOf(unique));
      data.put(RectangleParams.uniquePlacements, u);
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
