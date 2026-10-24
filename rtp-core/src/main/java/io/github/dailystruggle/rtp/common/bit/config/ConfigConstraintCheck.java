package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.rtp.common.action.ActionConfigConstraints;
import io.github.dailystruggle.rtp.common.selection.region.RegionConfigConstraints;
import java.util.ArrayList;
import java.util.List;

/**
 * Owner {@code constraints}: evaluates the semantic constraint tables declared next to the region
 * and action loaders (ADR-121 section 6). Violations are real ERRORs, not staged: a reload carrying
 * one is rejected (ADR-121 section 7).
 */
public final class ConfigConstraintCheck implements ConfigOwnerCheck {
    public static final ConfigConstraintCheck INSTANCE = new ConfigConstraintCheck();

    private ConfigConstraintCheck() {}

    /** Every declared constraint, region tables first. */
    public static List<ConfigConstraint> constraints() {
        List<ConfigConstraint> all = new ArrayList<>(RegionConfigConstraints.CONSTRAINTS);
        all.addAll(ActionConfigConstraints.CONSTRAINTS);
        return List.copyOf(all);
    }

    @Override
    public String id() {
        return "constraints";
    }

    @Override
    public List<BitFinding> check(ConfigSnapshot snapshot, ConfigEnvironment environment) {
        return GenericConstraintCheck.evaluate(snapshot, constraints());
    }
}
