package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.render.ChartRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;

/**
 * SPI: resolves a declarative {@link ChartSpec} into a concrete pair of
 * {@link ChartRenderer} + {@link ChartModel} (REQ-RTP-MAP-006, ADR-047).
 *
 * @see ChartSpec
 * @see ChartSpecResolvers
 * @see MapDispatch
 */
@FunctionalInterface
public interface ChartSpecResolver {

  /**
   * Composes the renderer + model pair for {@code spec}.
   *
   * @param spec the declarative request; never {@code null}
   * @return a non-null {@link Resolution}
   * @throws UnresolvableChartSpecException if data is unavailable
   */
  Resolution resolve(ChartSpec spec) throws UnresolvableChartSpecException;

  /**
   * Composed renderer and model pair handed to {@link MapDispatch}.
   *
   * @param renderer the chart renderer; never {@code null}
   * @param model    the matched chart model; never {@code null}
   */
  record Resolution(ChartRenderer<ChartModel> renderer, ChartModel model) {
    /** Validates that neither component is null. */
    public Resolution {
      if (renderer == null) {
        throw new IllegalArgumentException("renderer shall not be null");
      }
      if (model == null) {
        throw new IllegalArgumentException("model shall not be null");
      }
    }

    /**
     * Erases the {@code <M>} type parameter for convenient resolution factory.
     *
     * @param <M>      the concrete chart model type
     * @param renderer the chart renderer; never {@code null}
     * @param model    the chart model; never {@code null}
     * @return a new {@link Resolution} with the erased renderer type
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <M extends ChartModel> Resolution of(ChartRenderer<M> renderer, M model) {
      return new Resolution((ChartRenderer) renderer, model);
    }
  }

  /** Thrown by {@link #resolve} when the underlying data is unavailable. */
  final class UnresolvableChartSpecException extends Exception {
    private static final long serialVersionUID = 1L;

    /**
     * Constructs the exception with a detail message.
     *
     * @param message description of why the chart spec could not be resolved
     */
    public UnresolvableChartSpecException(String message) { super(message); }

    /**
     * Constructs the exception with a detail message and cause.
     *
     * @param message description of why the chart spec could not be resolved
     * @param cause   the underlying cause
     */
    public UnresolvableChartSpecException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
