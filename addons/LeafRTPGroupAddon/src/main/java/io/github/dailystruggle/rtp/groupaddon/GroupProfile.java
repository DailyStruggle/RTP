package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import java.util.Objects;

/**
 * Declarative profile descriptor for group subspace placement, instantiated dynamically from configuration.
 *
 * <p><b>Units.</b> {@code subspaceChunkRadius} is the chunk-granularity Stage 1 footprint;
 * {@code minSeparation} and {@code elevationTolerance} are in blocks. The Stage 2 block sampling
 * stride is derived internally from {@code minSeparation} (see {@code SubspaceShape}) rather than
 * being a separate config knob, since a distinct stride only duplicates the separation constraint.
 * Keeping chunk and block units distinct is deliberate - see {@code SubspaceShape}.
 *
 * @param name profile name (filename without .yml)
 * @param distribution geometric distribution pattern
 * @param subspaceChunkRadius footprint half-width in chunks (Stage 1 pre-filter bound)
 * @param minSeparation minimum distance in blocks between placed participants (also drives the
 *     internal Stage 2 sampling stride)
 * @param elevationTolerance maximum allowable Y delta in blocks between participants
 * @param maxGroupSize maximum participant count supported by this profile
 */
public record GroupProfile(
    String name,
    GroupDistribution distribution,
    int subspaceChunkRadius,
    int minSeparation,
    int elevationTolerance,
    int maxGroupSize) {

  public GroupProfile {
    Objects.requireNonNull(name, "name cannot be null");
    Objects.requireNonNull(distribution, "distribution cannot be null");
    if (subspaceChunkRadius < 0) subspaceChunkRadius = 0;
    if (minSeparation < 1) minSeparation = 1;
    if (elevationTolerance < 0) elevationTolerance = 0;
    if (maxGroupSize < 1) maxGroupSize = 1;
  }

  /**
   * Instantiates a {@link GroupProfile} dynamically from a {@link ConfigParser<GroupKeys>}.
   *
   * @param name profile name
   * @param parser loaded configuration parser
   * @return dynamic GroupProfile
   */
  public static GroupProfile fromConfig(String name, ConfigParser<GroupKeys> parser) {
    Objects.requireNonNull(parser, "parser cannot be null");
    String distStr = (String) parser.getConfigValue(GroupKeys.distribution, "CLUSTER");
    GroupDistribution dist;
    try {
      dist = GroupDistribution.valueOf(distStr.toUpperCase());
    } catch (Exception e) {
      dist = GroupDistribution.CLUSTER;
    }

    int chunkRadius = ((Number) parser.getNumber(GroupKeys.subspaceChunkRadius, 1)).intValue();
    int minSep = ((Number) parser.getNumber(GroupKeys.minSeparation, 3)).intValue();
    int elevTol = ((Number) parser.getNumber(GroupKeys.elevationTolerance, 4)).intValue();
    int maxGroup = ((Number) parser.getNumber(GroupKeys.maxGroupSize, 8)).intValue();

    return new GroupProfile(name, dist, chunkRadius, minSep, elevTol, maxGroup);
  }
}
