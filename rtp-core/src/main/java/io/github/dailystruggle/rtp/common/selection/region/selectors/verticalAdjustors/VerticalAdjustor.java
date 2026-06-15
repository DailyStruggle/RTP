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
      loadLangFile("vert");
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
   * Re-resolves a safe standing Y on one specific column of {@code input},
   * identified by its in-chunk local coordinates {@code [0..15]}.
   *
   * <p>Unlike {@link #adjust(RTPChunk)} - which samples a small, fixed set of
   * sub-columns and returns the first that yields a safe column - this entry
   * point validates exactly the column the caller asks for. The cold-&gt;hot
   * (L2-&gt;L1) promotion path uses it to re-verify the column where a candidate
   * was originally selected (by the spiral, or by the L3 anvil backlog
   * prefilter), instead of discarding that column and re-sampling the fixed
   * sub-columns - which silently dropped good land locations whose safe column
   * was not one of the sampled few.
   *
   * <p>The default returns {@code null}; the caller then falls back to the
   * full {@link #adjust(RTPChunk)} sweep, so adjustors that do not override
   * this keep their previous behavior. Async-safe with respect to chunk I/O
   * only when {@code input} is already loaded (the promotion path guarantees
   * this); no synchronous chunk load is triggered (S-005).
   *
   * @param input  the (already loaded) chunk to validate within
   * @param localX in-chunk X in {@code [0..15]}
   * @param localZ in-chunk Z in {@code [0..15]}
   * @return the selected coordinates on that column, or {@code null} if no
   *     valid Y was found there
   */
  public @Nullable RTPCoords adjustColumn(
      @NotNull RTPChunk input, int localX, int localZ) {
    return null;
  }

  /**
   * Probe-backed fast path for Y selection. Default returns
   * null (UNKNOWN); subclasses override to scan the probe's center column over
   * {@code [probe.minY(), probe.maxY()]}. Returning null means UNKNOWN or NO-MATCH
   * - caller falls back to {@link #adjust(RTPChunk)}. Async-safe; no chunk I/O (S-005).
   *
   * @param probe     the chunk column probe to scan
   * @param worldName the world name for context
   * @return the selected coordinates, or {@code null} if no valid Y was found
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
   * only — does not change behavior.
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
   * Probe-backed fast path with typed rejection reason. The default delegates
   * to {@link #adjustFromProbe(ChunkColumnProbe, String)} and maps a null
   * result to {@link ProbeRejectReason#SCAN_MISS} (preserves back-compat for
   * third-party adjustors that only override the nullable-return entry point).
   *
   * <p>Concrete adjustors with distinct rejection branches (e.g.
   * {@code LinearAdjustor}, {@code JumpAdjustor}) override this to return the
   * specific reason; their {@link #adjustFromProbe} then delegates here and
   * unwraps {@code picked()} to keep a single source of truth.
   *
   * @param probe     the chunk column probe to scan
   * @param worldName the world name for context
   * @return the typed result; never {@code null}
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
