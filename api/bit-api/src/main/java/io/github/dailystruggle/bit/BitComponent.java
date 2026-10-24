package io.github.dailystruggle.bit;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;

/** Registered BIT check and the execution constraints required by its caller. */
public record BitComponent(
        String id,
        String subsystem,
        BitTier tier,
        BitSideEffect sideEffect,
        long timeoutMillis,
        Callable<BitReport> check) {

    public BitComponent {
        id = required(id, "id").toLowerCase(Locale.ROOT);
        subsystem = subsystem == null ? "" : subsystem.trim().toLowerCase(Locale.ROOT);
        Objects.requireNonNull(tier, "tier must not be null");
        Objects.requireNonNull(sideEffect, "sideEffect must not be null");
        if (timeoutMillis < 1) throw new IllegalArgumentException("timeoutMillis must be positive");
        Objects.requireNonNull(check, "check must not be null");
    }

    /** Returns the same registered metadata with a caller-specific check implementation. */
    public BitComponent withCheck(Callable<BitReport> replacement) {
        return new BitComponent(id, subsystem, tier, sideEffect, timeoutMillis, replacement);
    }

    private static String required(String value, String field) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return result;
    }
}
