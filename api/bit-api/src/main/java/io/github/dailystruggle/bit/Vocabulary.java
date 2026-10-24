package io.github.dailystruggle.bit;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Named set of allowed values with case-insensitive matching and typo suggestions. */
public final class Vocabulary {
    private final String name;
    private final Map<String, String> values;

    public Vocabulary(String name, Collection<String> values) {
        this.name = name == null ? "values" : name;
        Map<String, String> indexed = new LinkedHashMap<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) continue;
                indexed.putIfAbsent(normalize(value), value);
            }
        }
        this.values = Collections.unmodifiableMap(indexed);
    }

    public String name() {
        return name;
    }

    public List<String> values() {
        return List.copyOf(values.values());
    }

    public boolean contains(String value) {
        return value != null && values.containsKey(normalize(value));
    }

    public Optional<String> suggest(String value) {
        if (value == null || value.isBlank() || values.isEmpty()) return Optional.empty();
        String candidate = normalize(value);
        int limit = Math.max(1, Math.min(3, candidate.length() / 3 + 1));
        return suggest(value, limit);
    }

    /** Returns the nearest allowed value only when its edit distance is at most {@code maxDistance}. */
    public Optional<String> suggest(String value, int maxDistance) {
        if (value == null || value.isBlank() || values.isEmpty() || maxDistance < 0) return Optional.empty();
        String candidate = normalize(value);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            int distance = editDistance(candidate, entry.getKey());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getValue();
            }
        }
        return bestDistance <= maxDistance ? Optional.ofNullable(best) : Optional.empty();
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            previous = current;
        }
        return previous[right.length()];
    }
}
