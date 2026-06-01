package io.github.dailystruggle.commandsapi.brigadier;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Platform-supplied bridge between a Brigadier command source ({@code S}) and
 * the platform-agnostic {@code commands-api} execution model.
 *
 * <p>Carrying lambdas in this context keeps Brigadier's source type out of
 * {@link io.github.dailystruggle.commandsapi.common.CommandsAPICommand} (per
 * commands-api-ADR-001: "the adapter bridges them without leaking Brigadier types into
 * commands-api core interfaces").
 *
 * @param <S> Brigadier command source type (e.g. {@code ServerCommandSource} on Fabric).
 */
public final class BrigadierBridgeContext<S> {

    private final Function<S, UUID> senderToUuid;
    private final BiPredicate<S, String> permissionCheck;
    private final BiConsumer<S, String> sendMessage;

    public BrigadierBridgeContext(@NotNull Function<S, UUID> senderToUuid,
                                  @NotNull BiPredicate<S, String> permissionCheck,
                                  @NotNull BiConsumer<S, String> sendMessage) {
        this.senderToUuid = senderToUuid;
        this.permissionCheck = permissionCheck;
        this.sendMessage = sendMessage;
    }

    /**
     * @return UUID identifying the caller. For console / non-player sources, callers
     *         typically return a stable sentinel UUID (e.g. {@code new UUID(0L,0L)}).
     */
    public @NotNull Function<S, UUID> senderToUuid() {
        return senderToUuid;
    }

    /**
     * @return predicate evaluating whether {@code source} holds {@code permission}.
     *         The empty string conventionally means "no permission required".
     */
    public @NotNull BiPredicate<S, String> permissionCheck() {
        return permissionCheck;
    }

    /**
     * @return sink for user-facing feedback messages produced by command execution.
     */
    public @NotNull BiConsumer<S, String> sendMessage() {
        return sendMessage;
    }
}
