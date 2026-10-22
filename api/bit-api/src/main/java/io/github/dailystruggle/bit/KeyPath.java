package io.github.dailystruggle.bit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable dotted-key and list-index path for locating a config finding. */
public final class KeyPath {
    private final List<Object> segments;

    private KeyPath(List<Object> segments) {
        this.segments = List.copyOf(segments);
    }

    public static KeyPath root(String key) {
        return new KeyPath(List.of(requiredKey(key)));
    }

    public KeyPath key(String key) {
        List<Object> next = new ArrayList<>(segments);
        next.add(requiredKey(key));
        return new KeyPath(next);
    }

    public KeyPath index(int index) {
        if (index < 0) throw new IllegalArgumentException("index must not be negative");
        List<Object> next = new ArrayList<>(segments);
        next.add(index);
        return new KeyPath(next);
    }

    @Override
    public String toString() {
        StringBuilder path = new StringBuilder();
        for (Object segment : segments) {
            if (segment instanceof Integer index) {
                path.append('[').append(index).append(']');
            } else {
                if (!path.isEmpty()) path.append('.');
                path.append(segment);
            }
        }
        return path.toString();
    }

    private static String requiredKey(String key) {
        String value = Objects.requireNonNull(key, "key must not be null").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("key must not be blank");
        return value;
    }
}
