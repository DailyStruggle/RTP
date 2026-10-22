package io.github.dailystruggle.bit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Compares a declared vocabulary to values accepted by a runtime switch. */
public final class DriftCheck {
    private DriftCheck() {}

    public static List<String> diff(Vocabulary declared, Collection<String> runtimeLabels) {
        Set<String> runtime = new LinkedHashSet<>();
        if (runtimeLabels != null) {
            for (String label : runtimeLabels) {
                if (label != null) runtime.add(label.trim().toLowerCase(Locale.ROOT));
            }
        }
        Set<String> expected = new LinkedHashSet<>();
        for (String value : declared.values()) expected.add(value.toLowerCase(Locale.ROOT));
        List<String> differences = new ArrayList<>();
        for (String value : declared.values()) {
            if (!runtime.contains(value.toLowerCase(Locale.ROOT))) {
                differences.add("declared value is not handled: " + value);
            }
        }
        for (String value : runtimeLabels == null ? List.<String>of() : runtimeLabels) {
            if (value != null && !expected.contains(value.trim().toLowerCase(Locale.ROOT))) {
                differences.add("runtime label is not declared: " + value);
            }
        }
        return List.copyOf(differences);
    }
}
