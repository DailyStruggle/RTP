package io.github.dailystruggle.commandsapi.common.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Platform-neutral enum parameter helper. Suggestions are the names of the
 * provided enum's constants.
 */
public class EnumParameter<T extends Enum<T>> extends CommandParameter {
    private final Set<String> allValues;

    public EnumParameter(String permission, String description,
                         BiFunction<UUID, String, Boolean> isRelevant,
                         Class<T> enumClass) {
        super(permission, description, isRelevant);
        this.allValues = Arrays.stream(enumClass.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<String> values() {
        return allValues;
    }
}
