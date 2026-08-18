package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.common.RTP;
/**
 * Resolves {@code /rtp info} health colour bands (green, yellow, red).
 * Looks up thresholds from {@link CommandMessages} with documented fallbacks.
 */
public final class ColourBands {

    /** Default lower bound for the green TPS band (1m/5m/15m). */
    public static final double DEFAULT_TPS_GREEN = 19.5;
    /** Default lower bound for the yellow TPS band (1m/5m/15m). */
    public static final double DEFAULT_TPS_YELLOW = 18.0;
    /** Default upper bound (inclusive) for the green MSPT band, ms. */
    public static final double DEFAULT_MSPT_GREEN = 30.0;
    /** Default upper bound (inclusive) for the yellow MSPT band, ms. */
    public static final double DEFAULT_MSPT_YELLOW = 45.0;
    /** Default lower bound for the green L1 cache-fill band (fraction). */
    public static final double DEFAULT_CACHE_FILL_GREEN = 0.50;
    /** Default lower bound for the yellow L1 cache-fill band (fraction). */
    public static final double DEFAULT_CACHE_FILL_YELLOW = 0.25;
    /** Default lower bound (inclusive) for the yellow network-age band, seconds. */
    public static final double DEFAULT_NETWORK_AGE_YELLOW = 5.0;

    /** Legacy colour code emitted for the green band. */
    public static final String GREEN = "&a";
    /** Legacy colour code emitted for the yellow band. */
    public static final String YELLOW = "&e";
    /** Legacy colour code emitted for the red band. */
    public static final String RED = "&c";
    /** Legacy colour code emitted for an unavailable / NaN reading. */
    public static final String UNKNOWN = "&7";

    private ColourBands() {}

    /**
     * Pick the {@code &}-code prefix for a TPS reading (higher-is-better).
     *
     * @param tps the raw TPS value; {@link Double#NaN} renders as {@link #UNKNOWN}
     * @return one of {@link #GREEN}, {@link #YELLOW}, {@link #RED}, {@link #UNKNOWN}
     */
    public static String forTps(double tps) {
        if (Double.isNaN(tps)) return UNKNOWN;
        double green = resolveDouble(CommandMessages.infoThresholdTpsGreen, DEFAULT_TPS_GREEN);
        double yellow = resolveDouble(CommandMessages.infoThresholdTpsYellow, DEFAULT_TPS_YELLOW);
        if (tps >= green) return GREEN;
        if (tps >= yellow) return YELLOW;
        return RED;
    }

    /**
     * Pick the {@code &}-code prefix for an MSPT reading in milliseconds
     * (lower-is-better).
     *
     * @param msptMs the raw MSPT value in milliseconds; {@link Double#NaN} renders
     *               as {@link #UNKNOWN}
     * @return one of {@link #GREEN}, {@link #YELLOW}, {@link #RED}, {@link #UNKNOWN}
     */
    public static String forMspt(double msptMs) {
        if (Double.isNaN(msptMs)) return UNKNOWN;
        double green = resolveDouble(CommandMessages.infoThresholdMsptGreen, DEFAULT_MSPT_GREEN);
        double yellow = resolveDouble(CommandMessages.infoThresholdMsptYellow, DEFAULT_MSPT_YELLOW);
        if (msptMs <= green) return GREEN;
        if (msptMs <= yellow) return YELLOW;
        return RED;
    }

    /**
     * Pick the {@code &}-code prefix for a tick-budget utilisation reading (fraction in [0.0, 1.0]).
     */
    public static String forTickBudgetUtilisation(double utilisation) {
        if (Double.isNaN(utilisation)) return UNKNOWN;
        return forMspt(utilisation * 50.0);
    }

    /**
     * Pick the {@code &}-code prefix for an L1 cache-fill ratio (higher-is-better).
     *
     * @param fillRatio the raw fill value in {@code [0.0, 1.0]}; {@link Double#NaN}
     *                  renders as {@link #UNKNOWN}
     * @return one of {@link #GREEN}, {@link #YELLOW}, {@link #RED}, {@link #UNKNOWN}
     */
    public static String forCacheFill(double fillRatio) {
        if (Double.isNaN(fillRatio)) return UNKNOWN;
        double green = resolveDouble(CommandMessages.infoThresholdCacheFillGreen, DEFAULT_CACHE_FILL_GREEN);
        double yellow = resolveDouble(CommandMessages.infoThresholdCacheFillYellow, DEFAULT_CACHE_FILL_YELLOW);
        if (fillRatio >= green) return GREEN;
        if (fillRatio >= yellow) return YELLOW;
        return RED;
    }

    /**
     * Pick the {@code &}-code prefix for a network-transport last-success age in
     * seconds (lower-is-better). Two-band only (green / yellow) per the plan.
     *
     * @param ageSeconds the raw age in seconds; {@link Double#NaN} renders as
     *                   {@link #UNKNOWN}
     * @return {@link #GREEN}, {@link #YELLOW}, or {@link #UNKNOWN}
     */
    public static String forNetworkAge(double ageSeconds) {
        if (Double.isNaN(ageSeconds)) return UNKNOWN;
        double yellow =
                resolveDouble(CommandMessages.infoThresholdNetworkAgeYellow, DEFAULT_NETWORK_AGE_YELLOW);
        return (ageSeconds < yellow) ? GREEN : YELLOW;
    }

    /**
     * Resolve a numeric threshold from configuration, falling back to {@code fallback} if absent/invalid.
     */
    @SuppressWarnings("unchecked")
    static double resolveDouble(Enum<?> key, double fallback) {
        try {
            RTP rtp = RTP.getInstance();
            if (rtp == null || RTP.configs == null) return fallback;
            Object raw = RTP.configs.getConfigValue(key, null);
            return parseDouble(raw, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Parse a YAML scalar (Integer, Long, Double, Float, String) to a double,
     * returning {@code fallback} for any other type, {@code null}, or a string
     * that does not parse as a finite double.
     */
    static double parseDouble(Object raw, double fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        if (raw instanceof CharSequence) {
            String s = raw.toString().trim();
            if (s.isEmpty()) return fallback;
            try {
                double d = Double.parseDouble(s);
                if (Double.isNaN(d) || Double.isInfinite(d)) return fallback;
                return d;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
