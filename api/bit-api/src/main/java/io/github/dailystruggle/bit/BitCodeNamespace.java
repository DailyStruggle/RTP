package io.github.dailystruggle.bit;

import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * A reserved key prefix inside a {@link BitCodeRegistry}.
 *
 * <p>Every code registered through a namespace has a namespace-local id and a key of the form
 * {@code PREFIX_...}, so independently developed plugins cannot collide.
 */
public final class BitCodeNamespace {
    private final BitCodeRegistry registry;
    private final String prefix;

    BitCodeNamespace(BitCodeRegistry registry, String prefix) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    public boolean ownsKey(String key) {
        return key != null && key.startsWith(prefix + "_");
    }

    /** Registers a code in this namespace; see {@link BitCodeRegistry#register(BitCode)}. */
    public BitCode register(int id, String key, Level defaultSeverity, BitTier tier,
                            String subsystem, String messageKey, String remediation) {
        if (id < 1 || !ownsKey(key)) {
            throw new IllegalArgumentException(key + " (" + id + ") is outside namespace " + prefix);
        }
        return registry.register(new BitCode(id, key, defaultSeverity, tier, subsystem, messageKey, remediation));
    }

    /** Retains a removed code's identity so its namespace-local id and key cannot be reused. */
    public RetiredBitCode retire(int id, String key, String since) {
        if (id < 1 || !ownsKey(key)) {
            throw new IllegalArgumentException(key + " (" + id + ") is outside namespace " + prefix);
        }
        return registry.retire(new RetiredBitCode(id, key, since));
    }

    @Override
    public String toString() {
        return prefix;
    }
}
