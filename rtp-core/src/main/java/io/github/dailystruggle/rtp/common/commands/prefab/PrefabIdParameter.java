package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.commandsapi.common.CommandParameter;

import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * {@link CommandParameter} whose value set is the live id list from
 * {@link PrefabRegistry}. Mirrors {@code WorldParameter} / {@code RegionParameter}
 * in shape - registered via {@code addParameter("id", new PrefabIdParameter(...))}
 * on the {@code apply} / {@code confirm} / {@code rollback} verbs so the
 * CommandsAPI parser stores the typed id under {@code parameterValues.get("id")},
 * with no per-id sub-command registration and no args-form override required.
 */
public class PrefabIdParameter extends CommandParameter {
    public PrefabIdParameter(String permission,
                             String description,
                             BiFunction<UUID, String, Boolean> isRelevant) {
        super(permission, description, isRelevant);
    }

    @Override
    public Set<String> values() {
        return PrefabRegistry.list().stream()
                .map(Prefab::id)
                .collect(Collectors.toSet());
    }
}
