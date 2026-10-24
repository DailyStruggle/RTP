package io.github.dailystruggle.bit;

import java.util.Locale;
import java.util.Objects;

/** Parsed component, tier, subsystem, or safe-component selector. */
public record BitSelector(Kind kind, String value) {
    public enum Kind {
        ALL,
        TIER,
        SUBSYSTEM,
        COMPONENT
    }

    public BitSelector {
        Objects.requireNonNull(kind, "kind must not be null");
        value = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (kind == Kind.ALL) value = "all";
        if (kind == Kind.TIER) {
            value = BitTier.valueOf(value.toUpperCase(Locale.ROOT)).name().toLowerCase(Locale.ROOT);
        }
        if (value.isEmpty()) throw new IllegalArgumentException("selector value must not be blank");
    }

    public static BitSelector parse(String selector) {
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("selector must not be blank");
        }
        String value = selector.trim();
        if (value.equalsIgnoreCase("all")) return new BitSelector(Kind.ALL, "all");
        if (value.regionMatches(true, 0, "tier:", 0, 5)) {
            String tierName = value.substring(5).trim();
            try {
                return new BitSelector(Kind.TIER, BitTier.valueOf(tierName.toUpperCase(Locale.ROOT)).name());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("unknown BIT tier: " + tierName, exception);
            }
        }
        if (value.regionMatches(true, 0, "subsystem:", 0, 10)) {
            return new BitSelector(Kind.SUBSYSTEM, value.substring(10));
        }
        return new BitSelector(Kind.COMPONENT, value);
    }

    /** Returns the requested tier, or {@code null} for a non-tier selector. */
    public BitTier tier() {
        return kind == Kind.TIER ? BitTier.valueOf(value.toUpperCase(Locale.ROOT)) : null;
    }
}
