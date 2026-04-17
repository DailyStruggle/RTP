package io.github.dailystruggle.commandsapi.common;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public abstract class CommandParameter {
    public Map<String,Map<String,CommandParameter>> subParamMap = new ConcurrentHashMap<>();
    /**
     * function to validate enum values, using the player id
     */
    public BiFunction<UUID,String,Boolean> isRelevant;
    public int priority = 0;
    private final String permission;
    private final String description;

    public CommandParameter(String permission, String description, BiFunction<UUID, String, Boolean> isRelevant) {
        this.isRelevant = isRelevant;
        this.permission = permission;
        this.description = description;
    }

    public abstract Set<String> values();

    public String permission() {
        return permission;
    }

    public String description() {
        return description;
    }

    /**
     * @param senderId id of command sender. The id is less specific than a player class.
     * @return subset of all values, based on the isRelevant function
     */
    public Set<String> relevantValues(UUID senderId) {
        return values().stream().filter(s -> this.isRelevant.apply(senderId,s)).collect(Collectors.toSet());
    }

    public Map<String,CommandParameter> subParams(String parameter) {
        return subParamMap.get(parameter);
    }
}
