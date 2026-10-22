package io.github.dailystruggle.bit;

import java.lang.System.Logger.Level;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One row of a BIT table: a stable numeric id paired with a stable string key.
 *
 * <p>Invariant: once published, neither {@code id} nor {@code key} is renamed or reused; retired
 * codes stay reserved. {@code messageKey} is the owning plugin's localisation key and may be empty
 * until the plugin renders the code to players or admins.
 */
public record BitCode(
        int id,
        String key,
        Level defaultSeverity,
        BitTier tier,
        String subsystem,
        String messageKey,
        String remediation) {

    static final Pattern KEY_PATTERN = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");

    public BitCode {
        if (id < 1) throw new IllegalArgumentException("id must be positive: " + id);
        Objects.requireNonNull(key, "key must not be null");
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("key must be UPPER_SNAKE_CASE: " + key);
        }
        BitLevels.requireConcrete(defaultSeverity, "defaultSeverity");
        Objects.requireNonNull(tier, "tier must not be null");
        subsystem = subsystem == null ? "" : subsystem;
        messageKey = messageKey == null ? "" : messageKey;
        remediation = remediation == null ? "" : remediation;
    }
}
