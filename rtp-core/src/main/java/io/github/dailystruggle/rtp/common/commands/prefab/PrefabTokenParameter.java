package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.commandsapi.common.CommandParameter;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Free-form-string {@link CommandParameter} used for the {@code token=}
 * argument on {@code /rtp admin prefab confirm}. The token is a server-minted
 * UUID-shaped string and not enumerable, so {@link #values()} returns an empty
 * set (tab-complete offers nothing) but the parser still accepts whatever the
 * caller types because {@code isRelevant} runs against the value itself.
 */
public class PrefabTokenParameter extends CommandParameter {
    public PrefabTokenParameter(String permission,
                                String description,
                                BiFunction<UUID, String, Boolean> isRelevant) {
        super(permission, description, isRelevant);
    }

    @Override
    public Set<String> values() {
        return Collections.emptySet();
    }
}
