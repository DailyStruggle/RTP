package io.github.dailystruggle.commandsapi.common.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Platform-neutral coordinate parameter helper. Offers the canonical
 * relative ({@code ~}, {@code -~}) and zero suggestions used for
 * world-space inputs.
 */
public class CoordinateParameter extends CommandParameter {
    private static final Set<String> VALUES =
            new HashSet<>(Arrays.asList("~", "-~", "0"));

    public CoordinateParameter(String permission, String description,
                               BiFunction<UUID, String, Boolean> isRelevant) {
        super(permission, description, isRelevant);
    }

    @Override
    public Set<String> values() {
        return VALUES;
    }
}
