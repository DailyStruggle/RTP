package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class JumpAdjustor extends VerticalAdjustor<JumpAdjustorKeys> {
  protected static final Map<String, CommandParameter> subParameters = new ConcurrentHashMap<>();
  protected static final List<String> keys =
      Arrays.stream(GenericMemoryShapeParams.values()).map(Enum::name).collect(Collectors.toList());
  private static final EnumMap<JumpAdjustorKeys, Object> defaults =
      new EnumMap<>(JumpAdjustorKeys.class);
  private static final Set<String> unsafeBlocks = new ConcurrentSkipListSet<>();
  private static final AtomicLong lastUpdate = new AtomicLong();

  private static final List<List<Integer>> testCoords =
      Arrays.asList(
          Arrays.asList(7, 7),
          Arrays.asList(2, 2),
          Arrays.asList(12, 12),
          Arrays.asList(2, 12),
          Arrays.asList(12, 2));

  static {
    defaults.put(JumpAdjustorKeys.maxY, 127);
    defaults.put(JumpAdjustorKeys.minY, 32);
    defaults.put(JumpAdjustorKeys.step, 0);
    defaults.put(JumpAdjustorKeys.requireSkyLight, false);

    // subParameter removed
    // subParameter removed
    // subParameter removed
    // subParameter removed
  }

  public JumpAdjustor(List<Predicate<RTPCoords>> verifiers) {
    super(JumpAdjustorKeys.class, "jump", verifiers, defaults);
  }

  @Override
  public Collection<String> keys() {
    return Arrays.stream(JumpAdjustorKeys.values()).map(Enum::name).collect(Collectors.toList());
  }

  @Override
  public @Nullable RTPCoords adjust(@NotNull RTPChunk chunk) {
    if (chunk == null) return null;

    int maxY = getNumber(JumpAdjustorKeys.maxY, 256L).intValue();
    int minY = getNumber(JumpAdjustorKeys.minY, 0L).intValue();
    int step = getNumber(JumpAdjustorKeys.step, 0).intValue();

    maxY = Math.min(maxY, chunk.getWorld().getMaxHeight());

    boolean requireSkyLight;
    Object o = getData().getOrDefault(JumpAdjustorKeys.requireSkyLight, false);
    if (o instanceof Boolean) {
      requireSkyLight = (Boolean) o;
    } else requireSkyLight = Boolean.parseBoolean(o.toString());

    int oldY = minY;

    // enforce valid inputs
    step = Math.max(step, 1);
    step = Math.min(step, (maxY - minY) / 8);

    long t = System.currentTimeMillis();
    long dt = t - lastUpdate.get();
    if (dt > 5000 || dt < 0) {
      ConfigParser<SafetyKeys> safety =
          (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      Object value = safety.getConfigValue(SafetyKeys.unsafeBlocks, new ArrayList<>());
      unsafeBlocks.clear();
      if (value instanceof Collection) {
        unsafeBlocks.addAll(
            ((Collection<?>) value)
                .stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .collect(Collectors.toSet()));
      }
      lastUpdate.set(t);
    }

    for (List<Integer> xz : testCoords) {
      int x = xz.get(0);
      int z = xz.get(1);
      int globalX = (chunk.x() << 4) + x;
      int globalZ = (chunk.z() << 4) + z;

      for (int i = minY; i < maxY; i++) {
        if (chunk.isAir(x, i, z)) continue;
        if (!chunk.isSafe(x, i, z, unsafeBlocks)) {
          minY = i;
          break;
        }
      }

      for (int it_len = step; it_len > 2; it_len = it_len / 2) {
        for (int i = minY; i < maxY; i += it_len) {
          int skylight = 15;
          if (requireSkyLight) skylight = chunk.getSkyLight(x, i + 1, z);
          if (chunk.isAir(x, i, z)
              && chunk.isAir(x, i + 1, z)
              && skylight > 7
              && chunk.isSafe(x, i + 1, z, unsafeBlocks)) {
            minY = oldY;
            maxY = i;
            break;
          }
          if (i > maxY - it_len) return null;
          oldY = i;
        }
      }

      for (int i = minY; i < maxY; i++) {
        int skylight = 15;
        if (requireSkyLight) skylight = chunk.getSkyLight(x, i + 1, z);
        if (!chunk.isAir(x, i - 1, z)
            && chunk.isAir(x, i, z)
            && chunk.isAir(x, i + 1, z)
            && skylight > 7
            && chunk.isSafe(x, i + 1, z, unsafeBlocks)
            && chunk.isSafe(x, i, z, unsafeBlocks)
            && chunk.isSafe(x, i - 1, z, unsafeBlocks)) {
          return new RTPCoords(chunk.getWorld().name(), globalX, i, globalZ);
        }
      }
    }
    return null;
  }

  @Override
  public boolean testPlacement(@NotNull RTPCoords coords) {
    for (Predicate<RTPCoords> rtpLocationPredicate : verifiers) {
      if (!rtpLocationPredicate.test(coords)) return false;
    }
    return true;
  }

  @Override
  public Map<String, CommandParameter> getParameters() {
    return subParameters;
  }

  @Override
  public int minY() {
    return getNumber(JumpAdjustorKeys.minY, 0).intValue();
  }

  @Override
  public int maxY() {
    return getNumber(JumpAdjustorKeys.maxY, 256).intValue();
  }
}
