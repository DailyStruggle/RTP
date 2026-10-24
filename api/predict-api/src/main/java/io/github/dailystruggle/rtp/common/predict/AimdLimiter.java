package io.github.dailystruggle.rtp.common.predict;

import java.util.concurrent.TimeUnit;

/**
 * Lag-aware Additive-Increase / Multiplicative-Decrease (AIMD) stability limiter with
 * asymmetric deadband publication and stale-signal hold semantics (ADR-087 section 4.4, ADR-123 section 6).
 *
 * <ul>
 *   <li><b>Breach cut:</b> Multiplies the current limit by {@code 0.5} (clamped to {@code floor})
 *       when a breach episode begins, then enforces a cooldown of {@code max(minCutCooldownNanos, maxSignalLagNanos)}
 *       before cutting again during a persistent breach.</li>
 *   <li><b>Additive recovery:</b> After {@code max(recoveryWaitNanos, lastBreachLagNanos)} of
 *       {@code FRESH} non-breaching evaluations, increases the limit at {@code recoveryFractionPerWindow}
 *       (default {@code +10%} of {@code [floor, ceiling]} per {@code 5 s} window).</li>
 *   <li><b>Signal state discipline (ADR-087 I6):</b> {@code NEVER_SAMPLED} leaves the limit and
 *       published value at {@code ceiling}; {@code STALE} holds both the limit and the recovery timer
 *       without raising the effective value.</li>
 *   <li><b>Asymmetric deadband:</b> Decreases and transitions reaching {@code ceiling} publish immediately;
 *       intermediate increases publish only when exceeding the last published value by more than
 *       {@code deadbandFraction} (default {@code 10%}).</li>
 * </ul>
 *
 * <p>Zero external dependencies (Tier 1 {@code common.predict}, ADR-115).
 */
public final class AimdLimiter {

    public static final double DEFAULT_CUT_FACTOR = 0.5;
    public static final double DEFAULT_RECOVERY_FRACTION_PER_WINDOW = 0.10;
    public static final double DEFAULT_DEADBAND_FRACTION = 0.10;
    public static final long DEFAULT_MIN_CUT_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(1);
    public static final long DEFAULT_RECOVERY_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);
    public static final long DEFAULT_RECOVERY_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5);

    private double floor;
    private double ceiling;
    private final double cutFactor;
    private final double recoveryFractionPerWindow;
    private final double deadbandFraction;
    private final long minCutCooldownNanos;
    private final long recoveryWaitNanos;
    private final long recoveryWindowNanos;

    private double limit;
    private double published;
    private boolean inBreachEpisode;
    private boolean hasCut;
    private long lastCutNanos = Long.MIN_VALUE;
    private long lastEvalNanos = Long.MIN_VALUE;
    private long cleanFreshNanos;
    private long activeLagNanos;
    private long atFloorSinceNanos = Long.MIN_VALUE;
    private long cutCount;

    public AimdLimiter(double floor, double ceiling) {
        this(floor, ceiling, ceiling,
                DEFAULT_CUT_FACTOR,
                DEFAULT_RECOVERY_FRACTION_PER_WINDOW,
                DEFAULT_DEADBAND_FRACTION,
                DEFAULT_MIN_CUT_COOLDOWN_NANOS,
                DEFAULT_RECOVERY_WAIT_NANOS,
                DEFAULT_RECOVERY_WINDOW_NANOS);
    }

    public AimdLimiter(double floor,
                       double ceiling,
                       double initialLimit,
                       double cutFactor,
                       double recoveryFractionPerWindow,
                       double deadbandFraction,
                       long minCutCooldownNanos,
                       long recoveryWaitNanos,
                       long recoveryWindowNanos) {
        if (Double.isNaN(floor) || Double.isNaN(ceiling) || ceiling < floor) {
            throw new IllegalArgumentException("Invalid bounds: floor=" + floor + ", ceiling=" + ceiling);
        }
        if (Double.isNaN(cutFactor) || cutFactor <= 0.0 || cutFactor >= 1.0) {
            throw new IllegalArgumentException("cutFactor must be in (0, 1), got " + cutFactor);
        }
        if (Double.isNaN(recoveryFractionPerWindow) || recoveryFractionPerWindow <= 0.0) {
            throw new IllegalArgumentException("recoveryFractionPerWindow must be > 0, got " + recoveryFractionPerWindow);
        }
        if (Double.isNaN(deadbandFraction) || deadbandFraction < 0.0) {
            throw new IllegalArgumentException("deadbandFraction must be >= 0, got " + deadbandFraction);
        }
        this.floor = floor;
        this.ceiling = ceiling;
        this.cutFactor = cutFactor;
        this.recoveryFractionPerWindow = recoveryFractionPerWindow;
        this.deadbandFraction = deadbandFraction;
        this.minCutCooldownNanos = Math.max(0L, minCutCooldownNanos);
        this.recoveryWaitNanos = Math.max(0L, recoveryWaitNanos);
        this.recoveryWindowNanos = Math.max(1L, recoveryWindowNanos);
        double clampedInit = clamp(initialLimit, floor, ceiling);
        this.limit = clampedInit;
        this.published = clampedInit;
        this.hasCut = clampedInit < ceiling;
    }

    /**
     * Evaluates one control step and returns the published effective value.
     *
     * @param modelTarget     unclamped or clamped model target for the knob
     * @param breached        {@code true} if any driving signal is currently in breach
     * @param maxLagNanos     maximum signal lag {@code L} among breaching/driving signals (nanoseconds)
     * @param anyStale        {@code true} if any driving signal is {@code STALE}
     * @param allNeverSampled {@code true} if all driving signals are {@code NEVER_SAMPLED}
     * @param nowNanos        monotonic clock reading in nanoseconds
     * @return published effective value in {@code [floor, ceiling]}
     */
    public double evaluate(double modelTarget,
                           boolean breached,
                           long maxLagNanos,
                           boolean anyStale,
                           boolean allNeverSampled,
                           long nowNanos) {
        long lag = Math.max(0L, maxLagNanos);
        if (allNeverSampled && !breached) {
            limit = ceiling;
            published = clamp(Double.isNaN(modelTarget) ? ceiling : modelTarget, floor, ceiling);
            inBreachEpisode = false;
            lastEvalNanos = nowNanos;
            updateFloorTimestamp(nowNanos);
            return published;
        }

        long dt = (lastEvalNanos == Long.MIN_VALUE) ? 0L : Math.max(0L, nowNanos - lastEvalNanos);
        lastEvalNanos = nowNanos;

        if (breached) {
            if (!inBreachEpisode) {
                activeLagNanos = lag;
            } else {
                activeLagNanos = Math.max(activeLagNanos, lag);
            }
            long cooldown = Math.max(minCutCooldownNanos, activeLagNanos);
            boolean canCut = !inBreachEpisode
                    || lastCutNanos == Long.MIN_VALUE
                    || (nowNanos - lastCutNanos >= cooldown);
            inBreachEpisode = true;
            cleanFreshNanos = 0L;
            if (canCut) {
                limit = Math.max(floor, limit * cutFactor);
                hasCut = true;
                lastCutNanos = nowNanos;
                cutCount++;
            }
        } else {
            inBreachEpisode = false;
            if (!anyStale) {
                if (lag > activeLagNanos) {
                    activeLagNanos = lag;
                }
                long waitRequired = Math.max(recoveryWaitNanos, activeLagNanos);
                long prevClean = cleanFreshNanos;
                cleanFreshNanos += dt;
                if (cleanFreshNanos > waitRequired && limit < ceiling) {
                    long effectiveRecoverNanos = cleanFreshNanos - Math.max(prevClean, waitRequired);
                    if (effectiveRecoverNanos > 0L) {
                        double range = ceiling - floor;
                        double step = range * recoveryFractionPerWindow
                                * ((double) effectiveRecoverNanos / (double) recoveryWindowNanos);
                        limit = Math.min(ceiling, limit + step);
                    }
                }
                if (limit >= ceiling - eps()) {
                    limit = ceiling;
                    hasCut = false;
                    activeLagNanos = lag;
                }
            }
            // When anyStale is true and not breached: hold limit and cleanFreshNanos unchanged (ADR-087 I6).
        }

        double sanitizedTarget = Double.isNaN(modelTarget) || Double.isInfinite(modelTarget)
                ? ceiling
                : clamp(modelTarget, floor, ceiling);
        double effectiveTarget = clamp(Math.min(sanitizedTarget, limit), floor, ceiling);

        if (anyStale && !breached && effectiveTarget > published) {
            // Stale signals never raise a published value (ADR-087 I6).
            effectiveTarget = published;
        }

        published = applyDeadband(published, effectiveTarget);
        updateFloorTimestamp(nowNanos);
        return published;
    }

    private double eps() {
        return Math.max(1e-9, (ceiling - floor) * 1e-9);
    }

    private double applyDeadband(double currentPublished, double candidate) {
        if (candidate <= currentPublished) {
            return candidate;
        }
        if (candidate >= ceiling - eps()) {
            return ceiling;
        }
        double threshold = currentPublished + Math.abs(currentPublished) * deadbandFraction;
        if (candidate > threshold) {
            return candidate;
        }
        return currentPublished;
    }

    private void updateFloorTimestamp(long nowNanos) {
        if (published <= floor + eps() && ceiling > floor) {
            if (atFloorSinceNanos == Long.MIN_VALUE) {
                atFloorSinceNanos = nowNanos;
            }
        } else {
            atFloorSinceNanos = Long.MIN_VALUE;
        }
    }

    /**
     * Updates the floor and ceiling bounds (e.g. on configuration reload) while clamping current state.
     *
     * @param newFloor   new floor bound
     * @param newCeiling new ceiling bound
     */
    public void updateBounds(double newFloor, double newCeiling) {
        if (Double.isNaN(newFloor) || Double.isNaN(newCeiling) || newCeiling < newFloor) {
            throw new IllegalArgumentException("Invalid bounds: floor=" + newFloor + ", ceiling=" + newCeiling);
        }
        this.floor = newFloor;
        this.ceiling = newCeiling;
        if (!hasCut) {
            this.limit = newCeiling;
            this.published = newCeiling;
        } else {
            this.limit = clamp(this.limit, newFloor, newCeiling);
            this.published = clamp(this.published, newFloor, newCeiling);
        }
    }

    /**
     * Forces the limiter to its floor at {@code nowNanos} (for testing or explicit reset-to-floor).
     *
     * @param lagNanos signal lag in nanoseconds
     * @param nowNanos monotonic clock reading in nanoseconds
     */
    public void forceToFloor(long lagNanos, long nowNanos) {
        this.limit = floor;
        this.published = floor;
        this.hasCut = true;
        this.inBreachEpisode = false;
        this.cleanFreshNanos = 0L;
        this.activeLagNanos = Math.max(0L, lagNanos);
        this.lastCutNanos = nowNanos;
        this.lastEvalNanos = nowNanos;
        this.atFloorSinceNanos = (ceiling > floor) ? nowNanos : Long.MIN_VALUE;
    }

    /**
     * Resets the limiter to its configured ceiling.
     */
    public void reset() {
        this.limit = ceiling;
        this.published = ceiling;
        this.inBreachEpisode = false;
        this.hasCut = false;
        this.lastCutNanos = Long.MIN_VALUE;
        this.lastEvalNanos = Long.MIN_VALUE;
        this.cleanFreshNanos = 0L;
        this.activeLagNanos = 0L;
        this.atFloorSinceNanos = Long.MIN_VALUE;
        this.cutCount = 0L;
    }

    public double floor() {
        return floor;
    }

    public double ceiling() {
        return ceiling;
    }

    public double rawLimit() {
        return limit;
    }

    public double publishedValue() {
        return published;
    }

    public boolean isLimiterBinding(double modelTarget) {
        return hasCut && limit < modelTarget - 1e-9;
    }

    public boolean hasCut() {
        return hasCut;
    }

    public long cutCount() {
        return cutCount;
    }

    /**
     * Returns duration in nanoseconds that the published value has been continuously held at {@code floor},
     * or {@code 0L} if not currently at floor.
     *
     * @param nowNanos current monotonic timestamp
     * @return duration at floor in nanoseconds
     */
    public long durationAtFloorNanos(long nowNanos) {
        if (atFloorSinceNanos == Long.MIN_VALUE || nowNanos < atFloorSinceNanos) {
            return 0L;
        }
        return nowNanos - atFloorSinceNanos;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
