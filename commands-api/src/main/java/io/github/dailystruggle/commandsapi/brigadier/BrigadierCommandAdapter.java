package io.github.dailystruggle.commandsapi.brigadier;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.CoordinateParameter;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.FloatParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * ADR-014 — Brigadier Bridge.
 *
 * <p>Walks a {@link CommandsAPICommand} tree and emits a Brigadier
 * {@link LiteralArgumentBuilder} that platform adapters (currently
 * {@code rtp-fabric}) can register with their command dispatcher.
 *
 * <p>This adapter never invokes platform-specific code itself; all
 * source-to-UUID, permission, and message work is delegated to the
 * caller-supplied {@link BrigadierBridgeContext}. This satisfies the
 * ADR-014 boundary that Brigadier types do not leak into the rest of
 * {@code commands-api}, and the {@code rtp-core} / {@code rtp-api}
 * boundary that no platform classes appear there.
 *
 * <p>Argument-type mapping (v1):
 * <ul>
 *   <li>{@link IntegerParameter}    &rarr; {@link IntegerArgumentType}</li>
 *   <li>{@link FloatParameter}      &rarr; {@link DoubleArgumentType}</li>
 *   <li>{@link BooleanParameter}    &rarr; {@link BoolArgumentType}</li>
 *   <li>{@link CoordinateParameter} &rarr; {@code StringArgumentType.word()} (relative form preserved)</li>
 *   <li>{@link EnumParameter}       &rarr; {@code StringArgumentType.word()} + suggestions from {@code values()}</li>
 *   <li>any other / unknown        &rarr; {@code StringArgumentType.word()} + suggestions from {@code values()}</li>
 * </ul>
 *
 * <p>Player / World / OfflinePlayer parameters are intentionally not handled here
 * because their concrete classes live in the {@code bukkit} sub-package of
 * {@code commands-api} and have no platform-neutral counterpart. Fabric callers
 * wanting Brigadier's native entity selectors can post-process the returned
 * builder before registration.
 */
public final class BrigadierCommandAdapter {

    private BrigadierCommandAdapter() {
        // static utility
    }

    /**
     * Convert {@code root} into a Brigadier {@link LiteralArgumentBuilder}
     * suitable for registration with a Brigadier dispatcher.
     *
     * @param root the {@code commands-api} root command (must be a literal,
     *             i.e. its {@link CommandsAPICommand#name()} is the literal label).
     * @param ctx  platform bridge.
     * @param <S>  Brigadier command source type.
     * @return a populated {@link LiteralArgumentBuilder} with permission, executor,
     *         and child nodes (sub-commands and parameters) attached.
     */
    public static <S> @NotNull LiteralArgumentBuilder<S> toBrigadier(@NotNull CommandsAPICommand root,
                                                                    @NotNull BrigadierBridgeContext<S> ctx) {
        LiteralArgumentBuilder<S> literal = LiteralArgumentBuilder.literal(root.name());
        applyRequires(literal, root.permission(), ctx);
        literal.executes(execute(root, ctx, /*argSlots*/ List.of()));
        attachChildren(literal, root, ctx, /*pathSoFar*/ List.of());
        return literal;
    }

    // ------------------------------------------------------------------
    // Tree walk
    // ------------------------------------------------------------------

    private static <S> void attachChildren(@NotNull ArgumentBuilder<S, ?> parentBuilder,
                                           @NotNull CommandsAPICommand parent,
                                           @NotNull BrigadierBridgeContext<S> ctx,
                                           @NotNull List<ArgSlot> pathSoFar) {
        if (!(parent instanceof TreeCommand tree)) {
            return;
        }

        // Sub-commands first — each becomes a literal child node.
        Map<String, CommandsAPICommand> subCommands = tree.getCommandLookup();
        if (subCommands != null) {
            for (Map.Entry<String, CommandsAPICommand> entry : subCommands.entrySet()) {
                CommandsAPICommand sub = entry.getValue();
                if (sub == null) continue;
                // Use the command's canonical name() rather than the map key —
                // TreeCommand.addSubCommand() uppercases the key for case-insensitive
                // lookup, but Brigadier literals are case-sensitive and the
                // canonical user-facing label is sub.name().
                String literalName = sub.name();
                LiteralArgumentBuilder<S> subLiteral = LiteralArgumentBuilder.literal(literalName);
                applyRequires(subLiteral, sub.permission(), ctx);
                List<ArgSlot> subPath = append(pathSoFar, ArgSlot.literal(literalName));
                subLiteral.executes(execute(sub, ctx, subPath));
                attachChildren(subLiteral, sub, ctx, subPath);
                parentBuilder.then(subLiteral);
            }
        }

        // Parameters — chained as required argument nodes off the current node.
        Map<String, CommandParameter> params = tree.getParameterLookup();
        if (params != null && !params.isEmpty()) {
            for (Map.Entry<String, CommandParameter> entry : params.entrySet()) {
                String paramName = entry.getKey();
                CommandParameter param = entry.getValue();
                if (param == null) continue;
                ArgumentType<?> argType = mapArgumentType(param);
                RequiredArgumentBuilder<S, ?> argNode =
                        RequiredArgumentBuilder.argument(paramName, argType);
                applyRequires(argNode, param.permission(), ctx);

                // Suggestions for non-numeric / non-boolean types: surface the
                // values() set so Brigadier's client-side completion picks them up.
                if (needsSuggestions(param)) {
                    argNode.suggests(suggestionsFrom(param, ctx));
                }

                List<ArgSlot> subPath = append(pathSoFar, ArgSlot.parameter(paramName, argType));
                argNode.executes(execute(parent, ctx, subPath));
                parentBuilder.then(argNode);
            }
        }
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    private static <S> com.mojang.brigadier.Command<S> execute(@NotNull CommandsAPICommand target,
                                                               @NotNull BrigadierBridgeContext<S> ctx,
                                                               @NotNull List<ArgSlot> pathSoFar) {
        return brigadierCtx -> {
            S source = brigadierCtx.getSource();
            UUID callerId = ctx.senderToUuid().apply(source);

            String[] args = reconstructArgs(brigadierCtx, pathSoFar);

            Predicate<String> permissionCheck = perm ->
                    perm == null || perm.isEmpty() || ctx.permissionCheck().test(source, perm);

            CompletableFuture<Boolean> result = target.onCommand(
                    callerId,
                    permissionCheck,
                    msg -> ctx.sendMessage().accept(source, msg),
                    args,
                    /*i*/ 0,
                    /*tempParameters*/ null);

            // Brigadier expects an int return synchronously. The commands-api
            // pipeline is async-friendly, so we return SUCCESS optimistically
            // and rely on the messageMethod to surface failures, mirroring how
            // BukkitTreeCommand wires onCommand through the platform dispatcher.
            // Reference: ADR-014 — adapter bridges execution contexts without
            // re-implementing parsing.
            return result != null && Boolean.FALSE.equals(result.getNow(Boolean.TRUE)) ? 0 : 1;
        };
    }

    private static <S> String[] reconstructArgs(@NotNull CommandContext<S> brigadierCtx,
                                                @NotNull List<ArgSlot> pathSoFar) {
        List<String> out = new ArrayList<>(pathSoFar.size());
        for (ArgSlot slot : pathSoFar) {
            if (slot.isLiteral()) {
                out.add(slot.name);
            } else {
                Object raw = brigadierCtx.getArgument(slot.name, Object.class);
                out.add(raw == null ? "" : raw.toString());
            }
        }
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------
    // Argument-type mapping
    // ------------------------------------------------------------------

    private static @NotNull ArgumentType<?> mapArgumentType(@NotNull CommandParameter param) {
        if (param instanceof IntegerParameter) return IntegerArgumentType.integer();
        if (param instanceof FloatParameter)   return DoubleArgumentType.doubleArg();
        if (param instanceof BooleanParameter) return BoolArgumentType.bool();
        // Coordinates, enums, and unknowns: treat as words; suggestions provide UX.
        return StringArgumentType.word();
    }

    private static boolean needsSuggestions(@NotNull CommandParameter param) {
        return !(param instanceof IntegerParameter
              || param instanceof FloatParameter
              || param instanceof BooleanParameter);
    }

    private static <S> @NotNull SuggestionProvider<S> suggestionsFrom(@NotNull CommandParameter param,
                                                                     @NotNull BrigadierBridgeContext<S> ctx) {
        return (brigadierCtx, builder) -> {
            UUID callerId = ctx.senderToUuid().apply(brigadierCtx.getSource());
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            for (String value : param.relevantValues(callerId)) {
                if (value == null) continue;
                if (value.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(value);
                }
            }
            return builder.buildFuture();
        };
    }

    // ------------------------------------------------------------------
    // Permission gating
    // ------------------------------------------------------------------

    private static <S> void applyRequires(@NotNull ArgumentBuilder<S, ?> builder,
                                          String permission,
                                          @NotNull BrigadierBridgeContext<S> ctx) {
        if (permission == null || permission.isEmpty()) {
            return; // open node
        }
        builder.requires(source -> ctx.permissionCheck().test(source, permission));
    }

    // ------------------------------------------------------------------
    // Path-tracking helper (literal vs. parameter slot in the current branch)
    // ------------------------------------------------------------------

    private static List<ArgSlot> append(List<ArgSlot> base, ArgSlot slot) {
        List<ArgSlot> next = new ArrayList<>(base.size() + 1);
        next.addAll(base);
        next.add(slot);
        return next;
    }

    private static final class ArgSlot {
        final String name;
        final ArgumentType<?> type; // null when literal

        private ArgSlot(String name, ArgumentType<?> type) {
            this.name = name;
            this.type = type;
        }

        static ArgSlot literal(String name) {
            return new ArgSlot(name, null);
        }

        static ArgSlot parameter(String name, ArgumentType<?> type) {
            return new ArgSlot(name, type);
        }

        boolean isLiteral() {
            return type == null;
        }
    }

    // Suppress unused-import warnings on Suggestions/SuggestionsBuilder when
    // future refactors trim them; they remain referenced by SuggestionProvider.
    @SuppressWarnings("unused")
    private static void __referenceKeepers(Suggestions s, SuggestionsBuilder b) { }
}
