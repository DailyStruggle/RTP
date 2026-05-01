package io.github.dailystruggle.commandsapi.common.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;

import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Platform-neutral floating-point parameter helper.
 * Each option is formatted with up to two fractional digits.
 */
public class FloatParameter extends CommandParameter {
    private static final DecimalFormat FORMAT = new DecimalFormat("###.##");
    private final Set<String> allValues;

    public FloatParameter(String permission, String description,
                          BiFunction<UUID, String, Boolean> isRelevant,
                          Object... options) {
        super(permission, description, isRelevant);
        this.allValues = Arrays.stream(options)
                .map(FORMAT::format)
                .collect(Collectors.toCollection(HashSet::new));
    }

    @Override
    public Set<String> values() {
        return allValues;
    }
}
