package io.github.dailystruggle.bit;

import java.util.Objects;

/** Stable identity retained after a BIT code is removed from active use. */
public record RetiredBitCode(int id, String key, String since) {
    public RetiredBitCode {
        if (id < 1) throw new IllegalArgumentException("id must be positive: " + id);
        Objects.requireNonNull(key, "key must not be null");
        if (!BitCode.KEY_PATTERN.matcher(key).matches() || key.indexOf('_') < 1) {
            throw new IllegalArgumentException("key must be UPPER_SNAKE_CASE with a namespace: " + key);
        }
        since = Objects.requireNonNull(since, "since must not be null").trim();
        if (since.isEmpty()) throw new IllegalArgumentException("since must not be blank");
    }

    /** Namespace prefix derived from this code's key. */
    public String namespace() {
        return key.substring(0, key.indexOf('_'));
    }
}
