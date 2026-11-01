package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.predict.DistributionSampler;
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
    double exponent = DistributionSampler.radialAreaExponent(cr, radius);

    return range
        * DistributionSampler.sampleTruncatedNormal(
            mean, deviation, exponent, rng()::nextGaussian, rng()::nextDouble);
  }
}
