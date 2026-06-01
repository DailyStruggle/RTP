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
     * @return subset of all values, based on the {@link #isSuggestionRelevant(UUID, String)} hook
     *
     * <p>commands-api-ADR-001 (Brigadier Bridge) addendum 2026-05-06: suggestion relevance is
     * <em>decoupled</em> from execute-time validation ({@link #isRelevant}). The default
     * implementation of {@link #isSuggestionRelevant(UUID, String)} is permissive (returns
     * {@code true} for every value), so platforms that lack a reliable permission backend
     * (notably Fabric pre-{@code fabric-permissions-api}) still surface tab-completion to
     * non-ops. Subclasses that genuinely want permission-filtered suggestions on Bukkit
     * should override {@link #isSuggestionRelevant(UUID, String)} to delegate to
     * {@link #isRelevant}. Validation on execute is unchanged: {@code TreeCommand.onCommand}
     * still runs every supplied value through {@link #isRelevant} regardless of what
     * suggestions advertised.
     */
    public Set<String> relevantValues(UUID senderId) {
        return values().stream().filter(s -> this.isSuggestionRelevant(senderId, s)).collect(Collectors.toSet());
    }

    /**
     * Whether {@code value} should appear in tab-completion / suggestion output for the
     * sender identified by {@code senderId}. Default implementation is permissive
     * (returns {@code true}). Subclasses may override to delegate to {@link #isRelevant}
     * or to apply a different policy (e.g., always-permissive on a per-platform basis).
     *
     * <p>This hook MUST NOT be relied on as a security boundary; execute-time validation
     * goes through {@link #isRelevant} via the dispatcher's parameter-value gate.
     */
    protected boolean isSuggestionRelevant(UUID senderId, String value) {
        return true;
    }

    public Map<String,CommandParameter> subParams(String parameter) {
        return subParamMap.get(parameter);
    }
}
