package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.NormalDistributionParams;

import java.util.EnumMap;

/**
 * Shared sampling model for the {@code _NORMAL} shape variants.
 *
 * <p>A normal variant differs from its uniform counterpart only in distribution model, so the
 * gaussian draw lives here once and subclasses contribute geometry alone ({@link #getRange()},
 * {@code xzToLocation}, {@code locationToXZ}).
 *
 * @see Circle_Normal
 * @see Square_Normal
 */
public abstract class NormalMemoryShape extends MemoryShape<NormalDistributionParams> {

  /**
   * @param eClass parameter enum class; always {@link NormalDistributionParams}
   * @param name unique name of the shape
   * @param data default data
   */
  public NormalMemoryShape(
      Class<NormalDistributionParams> eClass,
      String name,
      EnumMap<NormalDistributionParams, Object> data) {
    super(eClass, name, data);
  }

  /**
   * Draw from a truncated normal distribution over {@code [0, range)}.
   *
   * <p>Rejection-samples a gaussian into {@code [0, 1]}, then applies a corrective exponent that
   * approximates the 1D-to-2D area mapping so {@code mean} lands where the operator expects
   * ({@code 0.0} = {@code centerRadius}, {@code 1.0} = {@code radius}).
   *
   * @param range the adjusted range
   * @return the sampled scalar
   */
  @Override
  protected double sample(double range) {
    long radius = getNumber(NormalDistributionParams.radius, 256L).longValue();
    long cr = getNumber(NormalDistributionParams.centerRadius, 64L).longValue();

    // ensure mean 0.0-1.0 and deviation > 0
    double mean = Math.abs(getNumber(NormalDistributionParams.mean, 0.5).doubleValue()) % 1.0;
    double deviation = Math.abs(getNumber(NormalDistributionParams.deviation, 1.0).doubleValue());

    // get a valid number between 0 and 1
    // apply corrective deviation, apply requested deviation, shift over to mean
    // todo: find a way to approximate this without rejection sampling
    double gaussian;
    do {
      // approximately -4 to 4
      gaussian = rng().nextGaussian();

      // correct to -0.5 to 0.5
      gaussian = gaussian / 8;

      // apply requested deviation
      gaussian = gaussian * deviation;

      // shift over to requested mean
      gaussian = gaussian + mean;

      if (mean < 0.05 && gaussian < 0) gaussian = -gaussian;
      else if (mean > 0.95 && gaussian > 1) gaussian = 1 - gaussian;
    } while (gaussian < 0 || gaussian > 1); // reject values outside distribution

    // an approximation of the necessary exponent for 1d to 2d mapping
    // 0.5-1.0 depending on cr, so it shouldn't escape bounds
    double exponent = (1 + ((double) cr) / ((double) radius)) * 0.5;
    gaussian = Math.pow(gaussian, 1.0 / exponent);

    // expand to fit
    return range * gaussian;
  }
}
