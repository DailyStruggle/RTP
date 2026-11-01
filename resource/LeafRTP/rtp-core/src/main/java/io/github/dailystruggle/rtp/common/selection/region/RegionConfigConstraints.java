package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.bit.config.ConfigConstraint;
import io.github.dailystruggle.rtp.common.selection.region.util.SpatialUnit;
import java.lang.System.Logger.Level;
import java.util.List;

/**
 * Semantic constraints on the keys {@link RegionConfigLoader} reads (ADR-121 section 6). Region
 * shape distances default to chunks. Declarative only: the loader's behaviour is unchanged.
 */
public final class RegionConfigConstraints {
    public static final BitCode SHAPE_RADIUS_NOT_ABOVE_CENTER = RtpBitCodes.RTP.register(1401,
            "RTP_SHAPE_RADIUS_NOT_ABOVE_CENTER", Level.ERROR, BitTier.PBIT, "config", "",
            "Set shape.radius greater than shape.centerRadius; an equal or larger inner hole leaves no landing area.");

    public static final List<ConfigConstraint> CONSTRAINTS = List.of(
            new ConfigConstraint("definitions/regions/", "shape", "centerRadius", "radius", false,
                    SpatialUnit.CHUNK, SHAPE_RADIUS_NOT_ABOVE_CENTER),
            new ConfigConstraint("config.yml", "defaults.shape", "centerRadius", "radius", false,
                    SpatialUnit.CHUNK, SHAPE_RADIUS_NOT_ABOVE_CENTER));

    private RegionConfigConstraints() {}
}
