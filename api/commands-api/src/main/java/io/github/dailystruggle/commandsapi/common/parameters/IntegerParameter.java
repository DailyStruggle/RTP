package io.github.dailystruggle.commandsapi.common.parameters;

import io.github.dailystruggle.commandsapi.common.CommandParameter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Platform-neutral integer parameter helper.
 *
 * <p>Mirrors the Bukkit-side {@code IntegerParameter} but accepts a
 * {@code BiFunction<UUID,String,Boolean>} directly so it can be used from
 * platform-agnostic modules (e.g. {@code rtp-core}) without introducing
 * an {@code org.bukkit.*} dependency.
 */
public class IntegerParameter extends CommandParameter {
    private final Set<String> allValues;

    /**
     * Curated-list constructor: each option is offered verbatim as a
     * tab-completion suggestion. This is the form used by shapes / vertical
     * adjustors to give the user a hint at the expected scale (e.g.
     * {@code 64, 128, 256, 512, 1024}).
     */
    public IntegerParameter(String permission, String description,
                            BiFunction<UUID, String, Boolean> isRelevant,
                            Object... options) {
        super(permission, description, isRelevant);
        this.allValues = Arrays.stream(options).map(String::valueOf).collect(Collectors.toCollection(HashSet::new));
    }

    @Override
    public Set<String> values() {
        return allValues;
    }
}
