package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.render.ChartRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;

/**
 * SPI: resolves a declarative {@link ChartSpec} into a concrete pair of
 * {@link ChartRenderer} + {@link ChartModel} that the active
 * {@link io.github.dailystruggle.mapsapi.MapBinding} can paint.
 *
 * <p>Authored as part of the ADR-047 declarative chart-composition bridge
 * (REQ-RTP-MAP-006). Each implementation handles exactly one
 * {@link ChartSpec.Kind} and is registered in {@link ChartSpecResolvers}.
 * Resolvers are pure data transforms over already-collected state
 * (e.g. {@code MemoryShape.badKeysSnapshot()},
 * {@code io.github.dailystruggle.metrics.api.MetricsSnapshot}) and shall
 * never:
 *
 * <ul>
 *   <li>perform synchronous chunk I/O (REQ-RTP-S-005),</li>
 *   <li>block on {@code CompletableFuture#get} / {@code #join}
 *       (REQ-RTP-F-008),</li>
 *   <li>import {@code org.bukkit.*} or {@code net.minecraft.*}
 *       (rtp-core platform-neutrality),</li>
 *   <li>silently swallow a failure (REQ-RTP-S-004): on any unsatisfiable
 *       request, throw {@link UnresolvableChartSpecException} so
 *       {@link MapDispatch} can surface the configurable
 *       {@code mapUnavailable} message.</li>
 * </ul>
 *
 * <p>The {@link Resolution} record is intentionally heterogeneous-safe: it
 * carries an erased {@code ChartRenderer<ChartModel>} reference so callers
 * can hand it straight to
 * {@link io.github.dailystruggle.mapsapi.MapBinding#renderEphemeral}
 * without a cast (see {@link MapDispatch#paint}).
 *
 * @see ChartSpec
 * @see ChartSpecResolvers
 * @see MapDispatch
 */
@FunctionalInterface
public interface ChartSpecResolver {

  /**
   * Composes the renderer + model pair for {@code spec}. Implementations are
   * called on the thread that invoked {@link MapDispatch#paint} (typically a
   * scheduler-async worker) and shall be reentrant.
   *
   * @param spec the declarative request; never {@code null}
   * @return a non-null {@link Resolution}
   * @throws UnresolvableChartSpecException if the underlying data source is
   *     unavailable (e.g. unknown region, world unloaded, snapshot empty in
   *     a way the resolver treats as failure)
   */
  Resolution resolve(ChartSpec spec) throws UnresolvableChartSpecException;

  /**
   * The composed pair handed back to {@link MapDispatch}. The
   * {@code renderer} type parameter is intentionally erased to
   * {@link ChartModel} so heterogeneous resolvers can be registered behind
   * the same SPI; the {@code model} is the concrete subtype the renderer
   * expects, paired by construction.
   */
  record Resolution(ChartRenderer<ChartModel> renderer, ChartModel model) {
    public Resolution {
      if (renderer == null) {
        throw new IllegalArgumentException("renderer shall not be null");
      }
      if (model == null) {
        throw new IllegalArgumentException("model shall not be null");
      }
    }

    /**
     * Convenience factory that erases the {@code <M>} type parameter so a
     * resolver may write {@code Resolution.of(HeatmapRenderer.INSTANCE,
     * heatmap)} without a manual unchecked cast at the call site.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <M extends ChartModel> Resolution of(ChartRenderer<M> renderer, M model) {
      return new Resolution((ChartRenderer) renderer, model);
    }
  }

  /** Thrown by {@link #resolve} when the underlying data is unavailable. */
  final class UnresolvableChartSpecException extends Exception {
    private static final long serialVersionUID = 1L;
    public UnresolvableChartSpecException(String message) { super(message); }
    public UnresolvableChartSpecException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
