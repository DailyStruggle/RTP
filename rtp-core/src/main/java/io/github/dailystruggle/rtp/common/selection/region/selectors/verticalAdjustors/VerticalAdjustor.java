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

  public String name;

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

  public abstract @Nullable RTPCoords adjust(@NotNull RTPChunk input);

  public abstract boolean adjust(@NotNull RTPChunk input, @NotNull MutableRTPCoords output);

  public abstract boolean testPlacement(@NotNull RTPCoords coords);

  public abstract Map<String, CommandParameter> getParameters();

  public abstract int minY();

  public abstract int maxY();

  /**
   * Probe-backed fast path for Y selection, used by the biome-lookup pipeline
   * (see {@code docs/dev/BIOME_LOOKUP_PERF_PLAN.md}).
   *
   * <p>Implementations inspect the supplied {@link ChunkColumnProbe}'s center
   * column over {@code [probe.minY(), probe.maxY()]} and return the absolute
   * world coordinates of an acceptable Y, or {@code null} if:
   * <ul>
   *   <li>no acceptable Y was found on the center column, or</li>
   *   <li>the adjustor's policy relies on data the probe cannot answer
   *       (e.g. sky-light, non-center columns) — the caller shall fall back
   *       to the authoritative {@link #adjust(RTPChunk)} path.</li>
   * </ul>
   *
   * <p>The default implementation returns {@code null} (UNKNOWN). Concrete
   * subclasses override to provide a probe-backed scan; the authoritative
   * {@link #adjust(RTPChunk)} path remains the source of truth and is still
   * invoked for every candidate this method returns, unless the caller
   * opts into a probe-only pipeline.</p>
   *
   * <p>This method is called on the same async thread as {@code biomeAt}
   * lookups; implementations must not perform synchronous chunk I/O
   * (<strong>S-005</strong>).</p>
   *
   * @param probe     lean center-column view of a single chunk.
   * @param worldName name of the world the probe belongs to, used to
   *     populate the returned {@link RTPCoords}.
   * @return world-space coordinates of an acceptable Y, or {@code null}
   *     for UNKNOWN / NO-MATCH — callers treat these two the same.
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
   */
  public @NotNull AdjustResult adjustFromProbeWithReason(
      @NotNull ChunkColumnProbe probe, @NotNull String worldName) {
    RTPCoords c = adjustFromProbe(probe, worldName);
    return c == null ? AdjustResult.SCAN_MISS_REJECT : AdjustResult.ok(c);
  }

  /**
   * Reports whether this adjustor's Y-acceptance policy consults sky-light at the
   * column (i.e. whether it needs authoritative {@code SkyLight} + {@code isLightOn}
   * data retained by a {@link ChunkColumnProbe}). Callers use this flag to set
   * {@code includeSkyLight} when requesting a probe from
   * {@link io.github.dailystruggle.rtp.api.world.RTPWorld#probeChunkColumn(int, int, int, int, boolean)},
   * avoiding the ~2 KiB / retained-section cost of the {@code SkyLight} tag when
   * the adjustor would ignore it anyway.
   *
   * <p>The default is {@code false} (no sky-light consultation). Concrete adjustors
   * with a configurable {@code requireSkyLight} option override to reflect the
   * current config value.</p>
   *
   * @return {@code true} iff this adjustor currently requires sky-light data.
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
