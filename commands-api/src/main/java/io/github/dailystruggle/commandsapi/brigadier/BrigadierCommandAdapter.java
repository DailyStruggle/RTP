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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * commands-api-ADR-001: Brigadier Bridge.
 *
 * <p>Walks a {@link CommandsAPICommand} tree, emitting a Brigadier {@link LiteralArgumentBuilder}.
 * Platform adapters (e.g., Fabric) register this with their dispatcher.
 *
 * <p>Delegates platform-specific tasks (permission, messages) to {@link BrigadierBridgeContext},
 * ensuring no platform leak into {@code commands-api} or {@code rtp-core}.
 *
 * <p>Maps {@code commands-api} parameters to Brigadier types. Excludes platform-specific
 * entity selectors (Player, World), which Fabric callers must handle manually.
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
        // The root is the single execution target on the Brigadier side.
        // commands-api's TreeCommand.onCommand performs its own recursive
        // dispatch (parent runs first, queues a CommandExecutor for any
        // pre-processing/effects, then calls subCommand.onCommand). The
        // bridge MUST preserve that contract so that root-level work
        // (permission gates, parameter accumulation, queued executors) is
        // not skipped on the Brigadier path -- otherwise platform parity is
        // lost between Bukkit and Fabric/Velocity. We therefore route every
        // node's executor to the root with the literal+parameter path it
        // walked through reconstructed as args[], identical to what the
        // Bukkit dispatcher would have produced.
        literal.executes(execute(root, ctx, /*argSlots*/ List.of()));
        attachChildren(literal, root, root, ctx, /*pathSoFar*/ List.of(), /*paramsSeen*/ new HashSet<>());

        // ----------------------------------------------------------------
        // Bukkit-parity flat-suggestion fallback (commands-api-ADR-001
        // addendum 2026-05-06b). Approved by user 2026-05-06.
        //
        // Symptom on Fabric: `/rtp scan` executes (server-side tree is
        // correct, confirmed via `/help rtp`) but `/rtp <TAB>` and
        // `/rtp s<TAB>` show NOTHING — the client's cached command tree
        // never offered the children of the root literal as suggestions.
        // The Bukkit `TabCompleter` historically always returned a flat
        // List<String> of accepted next tokens at every position; we
        // replicate that wire-format here by attaching a permissive
        // RequiredArgument("_", greedyString) sibling to the root with
        // its own SuggestionProvider that emits every subcommand name
        // and every parameter prefix in `name:` form.
        //
        // The shadow node is always-on (user-confirmed parity choice 2);
        // Brigadier prefers literal-child matches over required-argument
        // matches when both apply, so the existing `scan`/`info`/...
        // literal children continue to win for execution paths whose
        // first token matches them. The shadow node only catches
        // anything else, which then routes to the root's onCommand and
        // the configurable `msgInvalidCommand` (REQ-RTP-S-007).
        // ----------------------------------------------------------------
        attachFlatFallback(literal, root, root, ctx, /*argSlots*/ List.of());
        // Build-marker log line: makes it trivial to confirm in-game that
        // the freshly built jar actually contains commands-api-ADR-001
        // addendum 2026-05-06c (recursive flat-suggestion fallback). If
        // the user's `latest.log` lacks this banner immediately after the
        // existing "Registering /rtp Brigadier root with dispatcher" line,
        // the running mod jar is stale.
        java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.INFO,
                "[RTP] Brigadier bridge build-marker: commands-api-ADR-001 addendum 2026-05-06c "
                        + "(flat fallback at every literal) for root='" + root.name() + "'");
        return literal;
    }

    /**
     * Attach the Bukkit-parity flat-suggestion fallback as a permissive
     * {@code RequiredArgument("_", greedyString)} sibling under the given
     * literal builder. The provider emits every subcommand literal name
     * and every parameter prefix in {@code name:} wire format for the
     * supplied {@code node} (the TreeCommand whose level we are at).
     *
     * <p>Always-on at every literal level (root and every subcommand
     * literal): on Fabric the user reported that {@code /rtp scan <TAB>}
     * surfaces a region-name (e.g., {@code default}) instead of the
     * Bukkit-parity {@code region:} prefix, because the per-parameter
     * {@code suggests(...)} provider on the {@code region} RequiredArgument
     * fires before any literal-name hint. Attaching the same flat
     * fallback at every level restores the historic Bukkit
     * {@code TabCompleter} UX (a flat list of "what the next token can
     * be") at every depth, not just the root. See
     * commands-api-ADR-001 addendum 2026-05-06c.
     */
    private static <S> void attachFlatFallback(@NotNull LiteralArgumentBuilder<S> literal,
                                               @NotNull CommandsAPICommand root,
                                               @NotNull CommandsAPICommand node,
                                               @NotNull BrigadierBridgeContext<S> ctx,
                                               @NotNull List<ArgSlot> pathSoFar) {
        try {
            RequiredArgumentBuilder<S, ?> shadow =
                    RequiredArgumentBuilder.argument("_", StringArgumentType.greedyString());
            // Permissive: never strip via requires(), so suggestion gathering
            // always reaches our flat provider regardless of the source's perms.
            shadow.requires(s -> true);
            shadow.suggests(flatSuggestionsFor(node));
            shadow.executes(execute(root, ctx, pathSoFar));
            literal.then(shadow);
        } catch (Throwable t) {
            // Best-effort: the existing literal+children tree still works
            // on its own; the flat fallback is purely additive UX.
            java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.WARNING,
                    "[RTP] Brigadier flat-suggestion fallback attach failed for literal='"
                            + node.name() + "'; tab-complete will fall back to per-child providers. cause="
                            + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    // ------------------------------------------------------------------
    // Bukkit-parity flat suggestion provider
    // ------------------------------------------------------------------

    /**
     * Build a flat {@link SuggestionProvider} that emits every subcommand
     * literal name and every parameter prefix (in {@code name:} form) of
     * {@code root}, filtered by the builder's remaining prefix
     * (case-insensitive).
     *
     * <p>Mirrors the Bukkit {@code TabCompleter} contract: at the root
     * boundary, hand the player every accepted next token as a flat list
     * — Brigadier-side parsing/validation still happens on dispatch, so
     * suggestions are intentionally unfiltered by permission (parity
     * with the historic Bukkit behaviour; permission filtering is
     * deferred to Step F / fabric-permissions-api).
     */
    private static <S> @NotNull SuggestionProvider<S> flatSuggestionsFor(@NotNull CommandsAPICommand root) {
        return (brigadierCtx, builder) -> {
            // Suggestion-time isolation: a throw inside the lookup maps
            // (e.g., RTP.serverAccessor not yet bound on a very early
            // tab-complete) must not propagate out — Brigadier swallows
            // it into the suggestion future and the player sees an
            // empty list with no log. Catch, log, and return whatever
            // was already built. Mirrors `suggestionsFrom`.
            try {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                if (root instanceof TreeCommand tree) {
                    Map<String, CommandsAPICommand> subs = tree.getCommandLookup();
                    if (subs != null) {
                        for (CommandsAPICommand sub : subs.values()) {
                            if (sub == null) continue;
                            String name = sub.name();
                            if (name == null || name.isEmpty()) continue;
                            if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                                builder.suggest(name);
                            }
                        }
                    }
                    Map<String, CommandParameter> params = tree.getParameterLookup();
                    if (params != null) {
                        for (String paramName : params.keySet()) {
                            if (paramName == null || paramName.isEmpty()) continue;
                            // Bukkit wire format: `region:`, `biome:`, etc.
                            String suggestion = paramName + ":";
                            if (suggestion.toLowerCase(Locale.ROOT).startsWith(remaining)
                                    || paramName.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                                builder.suggest(suggestion);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.WARNING,
                        "[RTP] Brigadier flat suggestion provider threw for root='"
                                + root.name() + "'; returning partial. cause="
                                + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            }
            return builder.buildFuture();
        };
    }

    // ------------------------------------------------------------------
    // Tree walk
    // ------------------------------------------------------------------

    private static <S> void attachChildren(@NotNull ArgumentBuilder<S, ?> parentBuilder,
                                           @NotNull CommandsAPICommand root,
                                           @NotNull CommandsAPICommand parent,
                                           @NotNull BrigadierBridgeContext<S> ctx,
                                           @NotNull List<ArgSlot> pathSoFar,
                                           @NotNull Set<String> paramsSeen) {
        if (!(parent instanceof TreeCommand tree)) {
            return;
        }

        // Sub-commands first — each becomes a literal child node. The
        // executor target is always the ROOT (captured via the recursion),
        // and the literal token is appended to pathSoFar so that the root's
        // TreeCommand.onCommand sees args=["info", ...] exactly as the
        // Bukkit dispatcher would deliver them. The root then runs its own
        // pre-processing for that level, queues a CommandExecutor on the
        // commands-api pipeline, and recurses into the sub-command.
        //
        // Sub-commands are attached only at the literal/sub-command depth
        // (i.e., when no parameter has been consumed yet at this level).
        // Bukkit semantics require sub-commands to be positional literals
        // at the head of args[]; we mirror that by skipping sub-commands
        // when we're already inside a parameter chain (paramsSeen non-empty
        // at this depth). See commands-api-ADR-001 addendum (2026-05-06).
        Map<String, CommandsAPICommand> subCommands = tree.getCommandLookup();
        if (subCommands != null && paramsSeen.isEmpty()) {
            for (Map.Entry<String, CommandsAPICommand> entry : subCommands.entrySet()) {
                // Per-subcommand isolation: a single misbehaving subcommand
                // (NPE in name(), permission(), nested parameter lookup, etc.)
                // must not abort the whole Brigadier tree-build. Without this
                // guard, a thrown exception silently propagates out of
                // toBrigadier(), the dispatcher rejects the partially-built
                // literal, and the *base* /rtp loses tab-completion entirely
                // (the symptom the user reported on Fabric 2026-05-06).
                // commands-api-ADR-001 addendum 2026-05-06 §"Silent failure
                // isolation" pins this contract.
                try {
                    CommandsAPICommand sub = entry.getValue();
                    if (sub == null) continue;
                    // Use the command's canonical name() rather than the map key —
                    // TreeCommand.addSubCommand() uppercases the key for case-insensitive
                    // lookup, but Brigadier literals are case-sensitive and the
                    // canonical user-facing label is sub.name().
                    String literalName = sub.name();
                    if (literalName == null || literalName.isEmpty()) continue;
                    LiteralArgumentBuilder<S> subLiteral = LiteralArgumentBuilder.literal(literalName);
                    applyRequires(subLiteral, sub.permission(), ctx);
                    List<ArgSlot> subPath = append(pathSoFar, ArgSlot.literal(literalName));
                    // Always target ROOT: TreeCommand.onCommand will recursively
                    // dispatch into `sub` itself, queueing the parent's
                    // CommandExecutor on the commands-api pipeline along the way
                    // (see TreeCommand.onCommand sub-command branch).
                    subLiteral.executes(execute(root, ctx, subPath));
                    // Sub-command opens a fresh paramsSeen scope: each branch
                    // tracks its own visited set, so siblings of one branch
                    // do not bleed into another.
                    attachChildren(subLiteral, root, sub, ctx, subPath, new HashSet<>());
                    parentBuilder.then(subLiteral);
                } catch (Throwable t) {
                    java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.WARNING,
                            "[RTP] Brigadier subcommand attach failed for key='" + entry.getKey()
                                    + "' under parent='" + parent.name() + "'; skipping. cause="
                                    + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
                }
            }
        }

        // Parameters — chained as required argument nodes off the current node.
        //
        // Per commands-api-ADR-001 addendum (2026-05-06):
        //  (1) Each parameter node may be followed by *sibling* parameters of
        //      the same TreeCommand, because Bukkit's free-token wire format
        //      accepts /rtp region:R biome:B world:W in any order. Brigadier
        //      requires explicit graph edges, so we enumerate them here.
        //  (2) Each parameter node may be followed by *nested* parameters
        //      registered via CommandParameter.subParams(name) (e.g.,
        //      region.world / region.shape / region.vert). These continue
        //      to refine the *same* level of the TreeCommand, so they are
        //      attached as further required-argument children of argNode.
        //  (3) A cycle guard (paramsSeen) prevents infinite expansion of the
        //      sibling chain (region -> world -> region -> ...). Once a
        //      parameter name appears in the path it is not re-attached as
        //      a sibling further down the same branch.
        Map<String, CommandParameter> params = tree.getParameterLookup();
        if (params != null && !params.isEmpty()) {
            for (Map.Entry<String, CommandParameter> entry : params.entrySet()) {
                String paramName = entry.getKey();
                // Per-parameter isolation: see the equivalent guard around
                // sub-command attach above. A throw inside any one parameter's
                // setup must not strip the whole node tree.
                try {
                    CommandParameter param = entry.getValue();
                    if (param == null) continue;
                    if (paramsSeen.contains(paramName)) {
                        // Cycle guard: do not re-attach a parameter we've already
                        // walked through on this branch. Brigadier would otherwise
                        // expand the tree factorially.
                        continue;
                    }
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
                    // Always target ROOT: parameter values appear in args[] as
                    // "name=value" tokens after any leading literals, so the root's
                    // onCommand can recurse through the same path the Bukkit
                    // dispatcher would have walked.
                    argNode.executes(execute(root, ctx, subPath));

                    // Build the visited set for this branch (path-local).
                    Set<String> nextSeen = new HashSet<>(paramsSeen);
                    nextSeen.add(paramName);

                    // (2) Nested params under this parameter (e.g., region.world).
                    Map<String, CommandParameter> nested = param.subParams(paramName);
                    if (nested != null && !nested.isEmpty()) {
                        attachParameterChildren(argNode, root, ctx, subPath, nested, nextSeen);
                    }

                    // (1) Sibling parameters of the current TreeCommand.
                    attachParameterChildren(argNode, root, ctx, subPath, params, nextSeen);

                    parentBuilder.then(argNode);
                } catch (Throwable t) {
                    java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.WARNING,
                            "[RTP] Brigadier parameter attach failed for name='" + paramName
                                    + "' under parent='" + parent.name() + "'; skipping. cause="
                                    + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
                }
            }
        }
    }

    /**
     * Attach each parameter in {@code params} as a {@link RequiredArgumentBuilder} child of
     * {@code parentBuilder}, skipping any parameter whose name is already in {@code paramsSeen}
     * (cycle guard). Each child further recurses for nested + sibling chaining.
     *
     * <p>commands-api-ADR-001 addendum 2026-05-06: this is the recursion entry point used by
     * both nested-param ({@code subParams}) and sibling-param chaining; centralising it keeps
     * the cycle-guard contract uniform.
     */
    private static <S> void attachParameterChildren(@NotNull ArgumentBuilder<S, ?> parentBuilder,
                                                    @NotNull CommandsAPICommand root,
                                                    @NotNull BrigadierBridgeContext<S> ctx,
                                                    @NotNull List<ArgSlot> pathSoFar,
                                                    @NotNull Map<String, CommandParameter> params,
                                                    @NotNull Set<String> paramsSeen) {
        for (Map.Entry<String, CommandParameter> entry : params.entrySet()) {
            String childName = entry.getKey();
            // Per-parameter isolation in the recursive helper too —
            // see attachChildren for rationale.
            try {
                CommandParameter childParam = entry.getValue();
                if (childParam == null) continue;
                if (paramsSeen.contains(childName)) continue;

                ArgumentType<?> childType = mapArgumentType(childParam);
                RequiredArgumentBuilder<S, ?> childNode =
                        RequiredArgumentBuilder.argument(childName, childType);
                applyRequires(childNode, childParam.permission(), ctx);
                if (needsSuggestions(childParam)) {
                    childNode.suggests(suggestionsFrom(childParam, ctx));
                }

                List<ArgSlot> childPath = append(pathSoFar, ArgSlot.parameter(childName, childType));
                childNode.executes(execute(root, ctx, childPath));

                Set<String> nextSeen = new HashSet<>(paramsSeen);
                nextSeen.add(childName);

                // Nested params under this child (rare for sibling chaining, common for subParams).
                Map<String, CommandParameter> childNested = childParam.subParams(childName);
                if (childNested != null && !childNested.isEmpty()) {
                    attachParameterChildren(childNode, root, ctx, childPath, childNested, nextSeen);
                }

                // Continue the sibling chain on the same level.
                attachParameterChildren(childNode, root, ctx, childPath, params, nextSeen);

                parentBuilder.then(childNode);
            } catch (Throwable t) {
                java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.WARNING,
                        "[RTP] Brigadier nested-parameter attach failed for name='" + childName
                                + "'; skipping. cause="
                                + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
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

            // [trace] Confirms the Brigadier-side execute callback fires when the
            // player runs the command. If this line never appears in latest.log
            // the dispatcher never resolved to this node (registration / requires
            // / argument mismatch upstream).
            try {
                java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.FINER,
                        "[RTP][trace] BrigadierCommandAdapter.execute fired: target=" + target.name()
                                + " callerId=" + callerId + " args=" + java.util.Arrays.toString(args));
            } catch (Throwable ignored) { /* best-effort logging */ }

            Predicate<String> permissionCheck = perm ->
                    perm == null || perm.isEmpty() || ctx.permissionCheck().test(source, perm);

            CompletableFuture<Boolean> result;
            try {
                result = target.onCommand(
                        callerId,
                        permissionCheck,
                        msg -> {
                            try {
                                java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.FINE,
                                        "[RTP][trace] commands-api messageMethod -> " + msg);
                            } catch (Throwable ignored) { /* best-effort */ }
                            ctx.sendMessage().accept(source, msg);
                        },
                        args,
                        /*i*/ 0,
                        /*tempParameters*/ null);
            } catch (Throwable t) {
                java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.WARNING,
                        "[RTP][trace] BrigadierCommandAdapter.execute target.onCommand threw", t);
                throw t;
            }

            // Brigadier expects an int return synchronously. The commands-api
            // pipeline is async-friendly, so we return SUCCESS optimistically
            // and rely on the messageMethod to surface failures, mirroring how
            // BukkitTreeCommand wires onCommand through the platform dispatcher.
            // Reference: commands-api-ADR-001 — adapter bridges execution contexts without
            // re-implementing parsing.
            return result != null && Boolean.FALSE.equals(result.getNow(Boolean.TRUE)) ? 0 : 1;
        };
    }

    private static <S> String[] reconstructArgs(@NotNull CommandContext<S> brigadierCtx,
                                                @NotNull List<ArgSlot> pathSoFar) {
        List<String> out = new ArrayList<>(pathSoFar.size());
        for (ArgSlot slot : pathSoFar) {
            if (slot.isLiteral()) {
                // Literal slots are sub-command tokens. We DO emit them
                // back into args[] so that the root TreeCommand.onCommand
                // can recursively descend exactly as the Bukkit dispatcher
                // does -- queueing the parent's CommandExecutor for any
                // independent functionality before invoking the sub-command.
                out.add(slot.name);
                continue;
            }
            Object raw = brigadierCtx.getArgument(slot.name, Object.class);
            // Reconstruct the commands-api wire format: "<paramName>=<value>".
            // TreeCommand.onCommand parses this with splitOnParamDelimiter().
            out.add(slot.name + io.github.dailystruggle.commandsapi.common.CommandsAPI.parameterDelimiter
                    + (raw == null ? "" : raw.toString()));
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
            // Suggestion-time isolation: any throw inside `values()` /
            // `relevantValues()` (e.g., NPE because RTP.serverAccessor is
            // not yet bound, or a Bukkit-only call sneaking into a
            // platform-neutral CommandParameter override) must NOT propagate
            // out of the SuggestionProvider — Brigadier swallows the
            // resulting Throwable into the suggestion future and the player
            // sees an empty / missing tab-completion with no log.
            // Catch, log, and return whatever was already built.
            try {
                UUID callerId = ctx.senderToUuid().apply(brigadierCtx.getSource());
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (String value : param.relevantValues(callerId)) {
                    if (value == null) continue;
                    if (value.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(value);
                    }
                }
            } catch (Throwable t) {
                java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.WARNING,
                        "[RTP] Brigadier suggestion provider threw for parameter; returning empty. cause="
                                + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
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
        // Wrap the user-supplied permissionCheck in a try/catch so a
        // throwing predicate (e.g., NPE on a non-player command source,
        // or a fabric-permissions-api lookup that fails on the integrated
        // server) does not silently strip the node from the dispatcher
        // tree. On any throw we deny the node (return false) and log,
        // matching Brigadier's "requires fail = node hidden" semantics
        // without making the failure invisible.
        builder.requires(source -> {
            try {
                return ctx.permissionCheck().test(source, permission);
            } catch (Throwable t) {
                java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.WARNING,
                        "[RTP] Brigadier requires() predicate threw for permission='" + permission
                                + "'; denying node. cause="
                                + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
                return false;
            }
        });
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
