package io.github.dailystruggle.bit;

import java.lang.System.Logger.Level;
import java.util.Objects;

/**
 * A reserved, contiguous id range and key prefix inside a {@link BitCodeRegistry}.
 *
 * <p>Every code registered through a namespace has an id in {@code [firstId, lastId]} and a key of
 * the form {@code PREFIX_...}, so independently developed plugins cannot collide.
 */
public final class BitCodeNamespace {
    private final BitCodeRegistry registry;
    private final String prefix;
    private final int firstId;
    private final int lastId;

    BitCodeNamespace(BitCodeRegistry registry, String prefix, int firstId, int lastId) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.prefix = prefix;
        this.firstId = firstId;
        this.lastId = lastId;
    }

    public String prefix() {
        return prefix;
    }

    public int firstId() {
        return firstId;
    }

    public int lastId() {
        return lastId;
    }

    public boolean containsId(int id) {
        return id >= firstId && id <= lastId;
    }

    public boolean ownsKey(String key) {
        return key != null && key.startsWith(prefix + "_");
    }

    /** Registers a code in this namespace; see {@link BitCodeRegistry#register(BitCode)}. */
    public BitCode register(int id, String key, Level defaultSeverity, BitTier tier,
                            String subsystem, String messageKey, String remediation) {
        if (!containsId(id) || !ownsKey(key)) {
            throw new IllegalArgumentException(key + " (" + id + ") is outside namespace " + this);
        }
        return registry.register(new BitCode(id, key, defaultSeverity, tier, subsystem, messageKey, remediation));
    }

    boolean overlaps(int otherFirst, int otherLast) {
        return otherFirst <= lastId && otherLast >= firstId;
    }

    @Override
    public String toString() {
        return prefix + "[" + firstId + ".." + lastId + "]";
    }
}
