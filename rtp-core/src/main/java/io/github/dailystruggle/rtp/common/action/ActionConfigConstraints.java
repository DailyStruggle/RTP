package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.bit.config.ConfigConstraint;
import io.github.dailystruggle.rtp.common.selection.region.util.SpatialUnit;
import java.lang.System.Logger.Level;
import java.util.List;

/**
 * Semantic constraints on the keys {@link ActionConfigLoader} reads from {@code
 * definitions/actions/*.yml} (ADR-121 section 6). Action distances default to blocks, as in {@code
 * ActionConfigLoader.parseRadius}. Declarative only: the loader's behaviour is unchanged.
 */
public final class ActionConfigConstraints {
    static final String ACTIONS_DIRECTORY = "definitions/actions/";

    public static final BitCode RADIUS_NOT_ABOVE_CENTER = RtpBitCodes.RTP.register(1402,
            "RTP_ACTION_RADIUS_NOT_ABOVE_CENTER", Level.ERROR, BitTier.PBIT, "config", "",
            "Set radius greater than centerRadius in the action's placement or confinement block.");
    public static final BitCode SHRINK_ABOVE_INITIAL = RtpBitCodes.RTP.register(1403,
            "RTP_ACTION_SHRINK_ABOVE_INITIAL", Level.ERROR, BitTier.PBIT, "config", "",
            "Set confinement.shrinkTo at most confinement.initialSize; the border only shrinks.");

    public static final List<ConfigConstraint> CONSTRAINTS = List.of(
            new ConfigConstraint(ACTIONS_DIRECTORY, "placement.shape", "centerRadius", "radius", false,
                    SpatialUnit.BLOCK, RADIUS_NOT_ABOVE_CENTER),
            new ConfigConstraint(ACTIONS_DIRECTORY, "confinement.shape", "centerRadius", "radius", false,
                    SpatialUnit.BLOCK, RADIUS_NOT_ABOVE_CENTER),
            new ConfigConstraint(ACTIONS_DIRECTORY, "confinement", "centerRadius", "radius", false,
                    SpatialUnit.BLOCK, RADIUS_NOT_ABOVE_CENTER),
            new ConfigConstraint(ACTIONS_DIRECTORY, "confinement", "shrinkTo", "initialSize", true,
                    SpatialUnit.BLOCK, SHRINK_ABOVE_INITIAL));

    private ActionConfigConstraints() {}
}
