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
        attachGreedyArgsSlot(literal, root, root, ctx, /*argSlots*/ List.of());
        // Build-marker log line: makes it trivial to confirm in-game that
        // the freshly built jar actually contains commands-api-ADR-001
        // addendum 2026-05-06e (vanilla-client-safe greedy args slot —
        // Option A in the Fabric custom-ArgumentType kick fix). If the
        // user's `latest.log` lacks this banner immediately after the
        // existing "Registering /rtp Brigadier root with dispatcher" line,
        // the running mod jar is stale.
        java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.INFO,
                "[RTP] Brigadier bridge build-marker: commands-api-ADR-001 addendum 2026-05-06e "
                        + "(vanilla-only greedy 'args' slot, no custom ArgumentType) for root='"
                        + root.name() + "'");
        return literal;
    }

    /**
     * Attach the unified greedy {@code args} slot under the given literal builder.
     *
     * <p>Vanilla-client-safe replacement for the previous custom
     * {@code WhitespaceTerminatedArgumentType} per-parameter chain (commands-api-ADR-001
     * addendum 2026-05-06e — "Option A"). The slot is a single
     * {@code RequiredArgument("args", StringArgumentType.greedyString())} carrying:
     * <ul>
     *   <li>a {@link SuggestionProvider} that emits, in two stages, both subcommand
     *       names and {@code paramName=}/{@code paramName=value} hints for the
     *       last whitespace-separated token of {@code builder.getRemaining()};</li>
     *   <li>an executor that captures the entire greedy string and feeds the
     *       whitespace-tokenized {@code name=value} run into the root's
     *       {@link TreeCommand#onCommand} via {@link #reconstructArgs}.</li>
     * </ul>
     *
     * <p>The greedy slot is the ONLY parameter-carrying Brigadier node in the tree —
     * sibling chaining, nested {@code subParams} chaining, and the previous
     * {@code WhitespaceTerminatedArgumentType} are all collapsed into this single node.
     * That is what keeps the server-built command tree using purely vanilla
     * Brigadier types, so vanilla clients can join without the
     * "This server requires Fabric Loader and Fabric API installed on your client!"
     * kick that the prior custom-type approach caused on Fabric (2026-05-06).
     *
     * <p>Sub-command literals are still attached as their own Brigadier nodes by
     * {@link #attachChildren} so command-completion at the literal head behaves
     * identically to vanilla Brigadier; each subcommand literal in turn gets its
     * own greedy {@code args} slot via this method.
     */
    private static <S> void attachGreedyArgsSlot(@NotNull LiteralArgumentBuilder<S> literal,
                                                 @NotNull CommandsAPICommand root,
                                                 @NotNull CommandsAPICommand node,
                                                 @NotNull BrigadierBridgeContext<S> ctx,
                                                 @NotNull List<ArgSlot> pathSoFar) {
        try {
            RequiredArgumentBuilder<S, ?> argsNode =
                    RequiredArgumentBuilder.argument("args", StringArgumentType.greedyString());
            // Permissive: never strip via requires() at this level. Per-parameter
            // permission filtering is enforced server-side by TreeCommand.onCommand
            // (the same place Bukkit performs it), so the greedy slot itself stays
            // open for tab-completion gathering.
            argsNode.requires(s -> true);
            argsNode.suggests(argsSuggestionsFor(node, ctx));
            // The greedy slot is added to the path as a single parameter slot named
            // "args" with a null delimiter marker; reconstructArgs detects it and
            // splits the captured greedy string on whitespace into individual
            // name=value tokens (Bukkit-parity wire format).
            List<ArgSlot> argsPath = append(pathSoFar, ArgSlot.greedyArgs());
            argsNode.executes(execute(root, ctx, argsPath));
            literal.then(argsNode);
        } catch (Throwable t) {
            // Best-effort: the literal still executes via its own .executes() target.
            // Without the greedy slot, only the bare /literal form works (no params),
            // which mirrors the pre-bridge state and is strictly safer than aborting.
            java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.WARNING,
                    "[RTP] Brigadier greedy args-slot attach failed for literal='"
                            + node.name() + "'; only the bare literal will execute. cause="
                            + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
        }
    }

    // ------------------------------------------------------------------
    // Bukkit-parity flat suggestion provider
    // ------------------------------------------------------------------

    /**
     * Build the {@link SuggestionProvider} for the unified greedy {@code args} slot.
     *
     * <p>Two-stage Bukkit-parity wire-format suggestions, operating on the LAST
     * whitespace-separated token of the greedy {@code builder.getRemaining()}
     * string (so that {@code /rtp player=leaf26 reg<TAB>} correctly suggests
     * {@code region=}, not {@code player=}):
     *
     * <ol>
     *   <li><b>Stage 1</b> — token has no {@code =} or {@code :} yet: emit every
     *       subcommand name (filtered to the prefix) AND every {@code paramName=}
     *       wire-format key for parameters of {@code node}. Subcommand names are
     *       only suggested when the greedy slot is empty / at the start (no prior
     *       whitespace-separated tokens), since subcommands are positional at the
     *       head of args[] in Bukkit semantics.</li>
     *   <li><b>Stage 2</b> — token contains a delimiter (the user has typed
     *       {@code paramName=}): walk {@link CommandParameter#relevantValues(UUID)}
     *       for the matching parameter and emit {@code paramName=value} for each
     *       value matching the typed value-prefix. Per the user's explicit
     *       constraint, the value list does not surface until the {@code =} has
     *       been typed.</li>
     * </ol>
     *
     * <p>The replacement string passed to {@link SuggestionsBuilder#suggest(String)}
     * is computed against {@code builder.createOffset(start)} so that Brigadier
     * only replaces the last token, not the whole greedy capture — this is what
     * lets multi-parameter input like {@code /rtp player=leaf26 region=<TAB>}
     * tab-complete cleanly.
     *
     * <p>Stage-1 suggestions (subcommand names and {@code paramName=} keys) are
     * filtered by the caller's permission via {@link BrigadierBridgeContext#permissionCheck()},
     * mirroring the Bukkit {@link TreeCommand#onTabComplete} contract so that
     * Fabric/NeoForge callers are not shown subcommands or parameters they lack
     * permission to use. A null/empty permission is treated as open.
     */
    private static <S> @NotNull SuggestionProvider<S> argsSuggestionsFor(@NotNull CommandsAPICommand node,
                                                                         @NotNull BrigadierBridgeContext<S> ctx) {
        return (brigadierCtx, builder) -> {
            // Suggestion-time isolation: any throw inside the lookup maps must NOT
            // propagate out — Brigadier swallows it into the suggestion future and
            // the player sees an empty list with no log. Catch, log, return what
            // we already built.
            try {
                final S source = brigadierCtx.getSource();
                // Permission gate mirroring the Bukkit TreeCommand.onTabComplete
                // contract: a null/empty permission is open, otherwise the
                // suggestion is surfaced only when the caller holds it. A
                // throwing predicate denies the suggestion (and logs), matching
                // applyRequires' "deny on throw" semantics so we never leak a
                // node the caller cannot use.
                Predicate<String> allows = permission -> {
                    if (permission == null || permission.isEmpty()) return true;
                    try {
                        return ctx.permissionCheck().test(source, permission);
                    } catch (Throwable t) {
                        java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.WARNING,
                                "[RTP] Brigadier suggestion permission predicate threw for permission='"
                                        + permission + "'; denying. cause="
                                        + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
                        return false;
                    }
                };
                String remainingRaw = builder.getRemaining();
                // Locate the start of the LAST whitespace-separated token in the
                // greedy capture; everything before it ("prior" tokens) has
                // already been typed and accepted, and only the last token is
                // what tab-completion is currently editing.
                int lastWs = lastWhitespaceIndex(remainingRaw);
                int tokenStart = (lastWs < 0) ? 0 : lastWs + 1;
                boolean atHead = (tokenStart == 0); // no prior tokens — subcommand head position
                String token = remainingRaw.substring(tokenStart);
                String tokenLc = token.toLowerCase(Locale.ROOT);
                // Build a SuggestionsBuilder offset to the start of the last token,
                // so suggest(...) replaces only that token in the client UI.
                SuggestionsBuilder tokenBuilder =
                        builder.createOffset(builder.getStart() + tokenStart);

                char delim = io.github.dailystruggle.commandsapi.common.CommandsAPI.parameterDelimiter;
                int delimIdx = token.indexOf(delim);

                if (!(node instanceof TreeCommand tree)) {
                    return tokenBuilder.buildFuture();
                }

                if (delimIdx < 0) {
                    // Stage 1: no delimiter typed yet in the current token.
                    // Surface subcommand literal names (only at the head — subcommands
                    // are positional in Bukkit semantics) AND every `paramName=` key.
                    if (atHead) {
                        Map<String, CommandsAPICommand> subs = tree.getCommandLookup();
                        if (subs != null) {
                            for (CommandsAPICommand sub : subs.values()) {
                                if (sub == null) continue;
                                String name = sub.name();
                                if (name == null || name.isEmpty()) continue;
                                if (!allows.test(sub.permission())) continue;
                                if (name.toLowerCase(Locale.ROOT).startsWith(tokenLc)) {
                                    tokenBuilder.suggest(name);
                                }
                            }
                        }
                    }
                    Map<String, CommandParameter> params = tree.getParameterLookup();
                    if (params != null) {
                        for (Map.Entry<String, CommandParameter> paramEntry : params.entrySet()) {
                            String paramName = paramEntry.getKey();
                            if (paramName == null || paramName.isEmpty()) continue;
                            CommandParameter param = paramEntry.getValue();
                            if (param != null && !allows.test(param.permission())) continue;
                            String suggestion = paramName + delim;
                            String suggestionLc = suggestion.toLowerCase(Locale.ROOT);
                            if (suggestionLc.startsWith(tokenLc)
                                    || paramName.toLowerCase(Locale.ROOT).startsWith(tokenLc)) {
                                tokenBuilder.suggest(suggestion);
                            }
                        }
                    }
                } else {
                    // Stage 2: token contains `=`. Match the typed key to a
                    // parameter and offer its value list, filtered by the typed
                    // value-prefix. If no parameter matches the typed key, return
                    // empty (do not leak unrelated value lists).
                    String typedKey = token.substring(0, delimIdx);
                    String typedValue = token.substring(delimIdx + 1);
                    String typedValueLc = typedValue.toLowerCase(Locale.ROOT);
                    Map<String, CommandParameter> params = tree.getParameterLookup();
                    if (params != null) {
                        CommandParameter matched = null;
                        String matchedName = null;
                        for (Map.Entry<String, CommandParameter> entry : params.entrySet()) {
                            String pn = entry.getKey();
                            if (pn != null && pn.equalsIgnoreCase(typedKey)) {
                                matched = entry.getValue();
                                matchedName = pn;
                                break;
                            }
                        }
                        if (matched != null && matchedName != null) {
                            String prefix = matchedName + delim;
                            UUID callerId = ctx.senderToUuid().apply(brigadierCtx.getSource());
                            for (String value : matched.relevantValues(callerId)) {
                                if (value == null) continue;
                                if (value.toLowerCase(Locale.ROOT).startsWith(typedValueLc)) {
                                    tokenBuilder.suggest(prefix + value);
                                }
                            }
                        }
                    }
                }
                return tokenBuilder.buildFuture();
            } catch (Throwable t) {
                java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.WARNING,
                        "[RTP] Brigadier args suggestion provider threw for node='"
                                + node.name() + "'; returning partial. cause="
                                + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            }
            return builder.buildFuture();
        };
    }

    /** Index of the last whitespace character in {@code s}, or {@code -1} if none. */
    private static int lastWhitespaceIndex(@NotNull String s) {
        for (int i = s.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
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
                    // Vanilla-client-safe greedy `args` slot for this sub-command,
                    // so `/rtp scan region=default biome=plains ...` etc. can be
                    // typed and tab-completed against `sub`'s parameters.
                    // commands-api-ADR-001 addendum 2026-05-06e (Option A).
                    attachGreedyArgsSlot(subLiteral, root, sub, ctx, subPath);
                    parentBuilder.then(subLiteral);
                } catch (Throwable t) {
                    java.util.logging.Logger.getLogger("RTP").log(java.util.logging.Level.WARNING,
                            "[RTP] Brigadier subcommand attach failed for key='" + entry.getKey()
                                    + "' under parent='" + parent.name() + "'; skipping. cause="
                                    + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
                }
            }
        }

        // Parameters: NOT attached as a per-parameter Brigadier chain.
        //
        // commands-api-ADR-001 addendum 2026-05-06e ("Option A", vanilla-only
        // greedy slot): the previous design attached one RequiredArgumentBuilder
        // per parameter and chained siblings/nested children. That required a
        // custom whitespace-terminated ArgumentType (so `name=value` would parse
        // as a single token), which Fabric serialised under namespace
        // `rtp:wsword` and which vanilla clients reject on join — they kick with
        // "This server requires Fabric Loader and Fabric API installed on your
        // client!". To keep the server-built tree using ONLY vanilla Brigadier
        // types, all per-parameter wire-format handling is collapsed into a
        // single greedy `args` slot per literal level (root and each
        // sub-command literal), attached at the call sites of attachChildren.
        // The greedy slot's SuggestionProvider emits the same `paramName=` /
        // `paramName=value` two-stage hints the per-parameter chain previously
        // produced; reconstructArgs whitespace-tokenises the captured greedy
        // string back into the args[] form TreeCommand.onCommand expects, so
        // sibling chaining (`/rtp player=leaf26 region=default`), nested
        // subParams, and the cycle guard are all subsumed by a single node.
        // The `paramsSeen` parameter is preserved on the method signature for
        // future use but is currently unused on this code path.
        @SuppressWarnings("unused")
        Set<String> _unusedParamsSeen = paramsSeen;
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
            // The greedy `args` slot (commands-api-ADR-001 addendum 2026-05-06e,
            // "Option A"): a single Brigadier RequiredArgument("args",
            // greedyString()) captures the whole tail of the command line as one
            // String. We split it on whitespace into individual tokens here, so
            // TreeCommand.onCommand sees args=["player=leaf26", "region=default",
            // ...] exactly as the Bukkit dispatcher would deliver them. This is
            // what restores Bukkit-parity wire format (`/rtp player=leaf26
            // region=default`) without requiring a custom Brigadier ArgumentType
            // (which kicks vanilla clients on Fabric).
            String raw;
            try {
                raw = brigadierCtx.getArgument(slot.name, String.class);
            } catch (Throwable t) {
                // Defensive: if Brigadier returns a non-String (future-proofing)
                // or the slot was somehow not bound, fall through to empty so the
                // bare /literal form still executes.
                try {
                    Object o = brigadierCtx.getArgument(slot.name, Object.class);
                    raw = (o == null) ? "" : o.toString();
                } catch (Throwable ignored) {
                    raw = "";
                }
            }
            String rawStr = (raw == null) ? "" : raw;
            if (rawStr.isEmpty()) continue;
            // Whitespace-tokenise the greedy capture. We use a regex split on
            // \\s+ rather than String.split(" ") so any whitespace run (tabs,
            // multiple spaces) is treated uniformly, matching Brigadier's own
            // command-line splitting.
            for (String tok : rawStr.trim().split("\\s+")) {
                if (tok != null && !tok.isEmpty()) {
                    out.add(tok);
                }
            }
        }
        return out.toArray(new String[0]);
    }

    // ------------------------------------------------------------------
    // Argument-type mapping / per-parameter suggestion providers / custom
    // WhitespaceTerminatedArgumentType: REMOVED. commands-api-ADR-001
    // addendum 2026-05-06e collapsed the per-parameter Brigadier chain
    // into a single greedy `args` slot per literal level so the
    // server-built command tree uses ONLY vanilla Brigadier types,
    // letting vanilla clients join Fabric servers without the
    // "This server requires Fabric Loader and Fabric API installed on
    // your client!" kick. See `attachGreedyArgsSlot` and
    // `argsSuggestionsFor` above.
    // ------------------------------------------------------------------

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

    /**
     * Path-slot record describing one Brigadier node visited en route to the
     * executor. There are two kinds in the Option-A tree shape:
     * <ul>
     *   <li>{@link #literal(String)} — a sub-command literal node; emitted
     *       verbatim into args[] by {@link #reconstructArgs}.</li>
     *   <li>{@link #greedyArgs()} — the single greedy {@code args}
     *       {@link StringArgumentType#greedyString()} slot; its captured value
     *       is whitespace-tokenised by {@link #reconstructArgs} into individual
     *       {@code name=value} tokens.</li>
     * </ul>
     * Per-parameter slots no longer exist (commands-api-ADR-001 addendum
     * 2026-05-06e — "Option A" / vanilla-client-safe greedy args slot).
     */
    private static final class ArgSlot {
        /** Brigadier argument name for a non-literal slot, or the literal label. */
        final String name;
        /** {@code true} when this is a Brigadier literal sub-command node. */
        private final boolean literal;

        private ArgSlot(String name, boolean literal) {
            this.name = name;
            this.literal = literal;
        }

        static ArgSlot literal(String name) {
            return new ArgSlot(name, true);
        }

        /** The unified greedy `args` slot (one per literal level). */
        static ArgSlot greedyArgs() {
            return new ArgSlot("args", false);
        }

        boolean isLiteral() {
            return literal;
        }
    }

    // Suppress unused-import warnings on Suggestions/SuggestionsBuilder when
    // future refactors trim them; they remain referenced by SuggestionProvider.
    @SuppressWarnings("unused")
    private static void __referenceKeepers(Suggestions s, SuggestionsBuilder b) { }

    // ------------------------------------------------------------------
    // (The custom WhitespaceTerminatedArgumentType has been removed.
    // commands-api-ADR-001 addendum 2026-05-06e: vanilla clients reject any
    // non-vanilla ArgumentType in the server's command-tree packet, so the
    // Brigadier bridge now uses only vanilla types. The full `name=value`
    // wire-format token, including `=`, is now captured by a single
    // greedyString() slot named "args" and split on whitespace server-side
    // by reconstructArgs.)
    // ------------------------------------------------------------------
}
