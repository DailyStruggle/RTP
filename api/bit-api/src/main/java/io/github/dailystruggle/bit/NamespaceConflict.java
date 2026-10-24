package io.github.dailystruggle.bit;

import java.util.Objects;

/** A BIT code prefix reserved by two different owners in one registry. */
public record NamespaceConflict(String prefix, String owner, String claimant) {
    public NamespaceConflict {
        Objects.requireNonNull(prefix, "prefix must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(claimant, "claimant must not be null");
    }
}
