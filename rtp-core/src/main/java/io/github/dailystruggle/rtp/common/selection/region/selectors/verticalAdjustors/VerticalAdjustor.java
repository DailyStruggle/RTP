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
