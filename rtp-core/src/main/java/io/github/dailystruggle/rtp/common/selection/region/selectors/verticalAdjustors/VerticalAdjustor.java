package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class VerticalAdjustor<E extends Enum<E>> extends FactoryValue<E> {
  protected final List<Predicate<RTPCoords>> verifiers;

  /** The name of this adjustor (typically the config file name). */
  public String name;

  /**
   * Constructs a vertical adjustor.
   *
   * @param eClass    the enum class for configuration keys
   * @param name      the adjustor name
   * @param verifiers list of placement verifiers
   * @param def       default data values
   */
  protected VerticalAdjustor(
      Class<E> eClass,
      String name,
      List<Predicate<RTPCoords>> verifiers,
      EnumMap<E, Object> def) {
    super(eClass, name);
    this.verifiers = verifiers;
    setData(def);
    Factory<VerticalAdjustor<?>> vertAdjustorFactory =
        (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
    this.name = name;
    if (!vertAdjustorFactory.contains(name)) vertAdjustorFactory.add(name, this);
    try {
      loadLangFile("definitions/regions/.vert");
    } catch (IOException e) {
      RTP.log(Level.WARNING, e.getMessage(), e);
    }
  }

  /**
   * Adjusts the Y coordinate for the given chunk.
   *
   * @param input the chunk to adjust within
   * @return the selected coordinates, or {@code null} if no valid Y was found
   */
  public abstract @Nullable RTPCoords adjust(@NotNull RTPChunk input);

  /**
   * Adjusts the Y coordinate into the given mutable output.
   *
   * @param input  the chunk to adjust within
   * @param output the mutable coords to write the result into
   * @return {@code true} if a valid Y was found
   */
  public abstract boolean adjust(@NotNull RTPChunk input, @NotNull MutableRTPCoords output);

  /**
   * Tests whether the given coordinates are a valid placement.
   *
   * @param coords the coordinates to test
   * @return {@code true} if placement is valid
   */
  public abstract boolean testPlacement(@NotNull RTPCoords coords);

  /**
   * Returns the command parameters for this adjustor.
   *
   * @return map of parameter name to {@link CommandParameter}
   */
  public abstract Map<String, CommandParameter> getParameters();

  /**
   * Returns the minimum Y coordinate this adjustor will select.
   *
   * @return minimum Y
   */
  public abstract int minY();

  /**
   * Returns the maximum Y coordinate this adjustor will select.
   *
   * @return maximum Y
   */
  public abstract int maxY();

  /**
   * Re-resolves safe standing Y on a specific in-chunk column {@code [0..15]}.
   * @param input  loaded chunk to validate within
   * @param localX in-chunk X in {@code [0..15]}
   * @param localZ in-chunk Z in {@code [0..15]}
   * @return selected coordinates on that column, or {@code null}
   */
  public @Nullable RTPCoords adjustColumn(
      @NotNull RTPChunk input, int localX, int localZ) {
    return null;
  }

  /**
   * Probe-backed fast path for Y selection without chunk I/O (S-005).
   *
   * @param probe     chunk column probe to scan
   * @param worldName world name for context
   * @return selected coordinates, or {@code null} on miss/unknown
   */
  public @Nullable RTPCoords adjustFromProbe(
      @NotNull ChunkColumnProbe probe, @NotNull String worldName) {
    return null;
  }

  /**
   * Why a probe-path attempt returned no coords. Used by {@code ScanTask} to
   * attribute the single {@code adjustNull} outcome into {@code light /
   * window / scan / threw} sub-buckets for platform-asymmetry diagnosis. See
   * {@link #adjustFromProbeWithReason(ChunkColumnProbe, String)}. Diagnostic
   * only - does not change behavior.
   */
  public enum ProbeRejectReason {
    /** The probe succeeded; {@code AdjustResult.picked()} is non-null. */
    NONE,
    /** Probe Y window did not cover the adjustor's {@code [minY, maxY]}. */
    WINDOW,
    /**
     * Sky-light gate closed: {@code requireSkyLight} is true, probe
     * {@code isLightOn=false}, and the heightmap proxy could not be verified
     * (no heightmap, or a non-air block contradicts the reported top).
     */
    LIGHT_GATE,
    /** Center-column scan found no acceptable Y. */
    SCAN_MISS,
    /** {@code adjustFromProbeWithReason} threw (attributed by the caller). */
    THREW
  }

  /**
   * Typed result of {@link #adjustFromProbeWithReason(ChunkColumnProbe, String)}.
   * Either {@code picked} is non-null and {@code reason == NONE}, or
   * {@code picked} is null and {@code reason} names which gate fired.
   *
   * @param picked the selected coordinates, or {@code null} when no Y was found
   * @param reason the rejection reason; {@link ProbeRejectReason#NONE} when {@code picked} is non-null
   */
  public record AdjustResult(@Nullable RTPCoords picked, @NotNull ProbeRejectReason reason) {
    public static final AdjustResult WINDOW_REJECT =
        new AdjustResult(null, ProbeRejectReason.WINDOW);
    public static final AdjustResult LIGHT_GATE_REJECT =
        new AdjustResult(null, ProbeRejectReason.LIGHT_GATE);
    public static final AdjustResult SCAN_MISS_REJECT =
        new AdjustResult(null, ProbeRejectReason.SCAN_MISS);

    public static AdjustResult ok(@NotNull RTPCoords picked) {
      return new AdjustResult(picked, ProbeRejectReason.NONE);
    }
  }

  /**
   * Probe-backed fast path returning a typed rejection reason on failure.
   *
   * @param probe     chunk column probe to scan
   * @param worldName world name for context
   * @return adjustment result with {@link ProbeRejectReason}
   */
  public @NotNull AdjustResult adjustFromProbeWithReason(
      @NotNull ChunkColumnProbe probe, @NotNull String worldName) {
    RTPCoords c = adjustFromProbe(probe, worldName);
    return c == null ? AdjustResult.SCAN_MISS_REJECT : AdjustResult.ok(c);
  }

  /**
   * Whether this adjustor consults sky-light. Callers use this to set
   * {@code includeSkyLight} on probe requests, saving ~2 KiB per retained section
   * when false. Default false; override when {@code requireSkyLight} is configurable.
   *
   * @return {@code true} if this adjustor requires sky-light data
   */
  public boolean requiresSkyLight() {
    return false;
  }

  @Override
  public boolean equals(Object o) {
    if (!o.getClass().equals(getClass()) || !((VerticalAdjustor<?>) o).myClass.equals(myClass))
      return false;
    EnumMap<E, Object> data1 = getData();
    EnumMap<E, Object> data2 = ((VerticalAdjustor<E>) o).getData();
    for (Map.Entry<E, Object> entry : data1.entrySet()) {
      E key = entry.getKey();
      Object value = entry.getValue();
      try {
        Number number1 = getNumber(key, 0);
        try {
          Number number2 = ((VerticalAdjustor<E>) o).getNumber(key, 0);
          if (number1.doubleValue() != number2.doubleValue()) return false;
        } catch (IllegalArgumentException ignored) {
          return false;
        }
      } catch (IllegalArgumentException ignored) {
        if (!value.toString().equalsIgnoreCase(data2.get(key).toString())) return false;
      }
    }
    return true;
  }
}
