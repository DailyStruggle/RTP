package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitFinding;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Reusable evaluator over a declarative table of {@link ConfigConstraint}s (ADR-121 section 6).
 */
public final class GenericConstraintCheck {
    private final List<ConfigConstraint> constraints;

    public GenericConstraintCheck(Collection<ConfigConstraint> constraints) {
        Objects.requireNonNull(constraints, "constraints");
        this.constraints = List.copyOf(constraints);
    }

    public static GenericConstraintCheck of(Collection<ConfigConstraint> constraints) {
        return new GenericConstraintCheck(constraints);
    }

    public static GenericConstraintCheck of(ConfigConstraint... constraints) {
        Objects.requireNonNull(constraints, "constraints");
        return new GenericConstraintCheck(List.of(constraints));
    }

    public List<ConfigConstraint> constraints() {
        return constraints;
    }

    public List<BitFinding> evaluate(ConfigSnapshot snapshot) {
        return evaluate(snapshot, constraints);
    }

    public static List<BitFinding> evaluate(ConfigSnapshot snapshot, Collection<ConfigConstraint> constraints) {
        Objects.requireNonNull(constraints, "constraints");
        List<BitFinding> findings = new ArrayList<>();
        if (snapshot == null) return findings;
        for (ConfigConstraint constraint : constraints) {
            if (constraint != null) findings.addAll(constraint.evaluate(snapshot));
        }
        return findings;
    }
}
