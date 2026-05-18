package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuConsumerProfile;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuOpenRequest;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.menu.MenuTokenRegistry;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.jetbrains.annotations.Nullable;

/**
 * The {@code /rtp menu:<token>} redeem subcommand (ADR-035 §3,
 * CHECKLIST-generalized-menu.md item 2.2).
 *
 * <p>This subcommand registers under the {@code /rtp} root with literal name
 * {@code "menu"}; the actual redeem invocation arrives as
 * {@code /rtp menu:<token>} which the {@code commands-api} parser splits via
 * {@link TreeCommand#splitOnParamDelimiter(String)} into the {@code menu}
 * leaf + a {@code token} parameter value. Equivalent forms
 * ({@code /rtp menu <token>} or {@code /rtp menu token:<value>}) are accepted
 * by the same dispatch path so end-users and renderers can both reach it.
 *
 * <p>Behaviour on receipt (in order):
 *
 * <ol>
 *   <li>Resolve the token from the parsed parameter values.</li>
 *   <li>Atomic-consume via {@link MenuTokenRegistry#consume(UUID, String)} —
 *       this is the {@code senderUuid == storedPlayerUuid} check (the registry
 *       only returns a value when the caller owns the token).</li>
 *   <li>On failure: route the configurable {@code menu.invalid} / {@code menu.expired}
 *       message through {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#sendMessage}
 *       and log a {@link Level#WARNING} entry via {@link RTP#log} per
 *       REQ-RTP-S-004 / REQ-RTP-S-007 / REQ-RTP-F-013. Never silently swallow.</li>
 *   <li>On success: dispatch the stored {@link MenuAction}. Per ADR-035 §3 only
 *       {@link MenuAction.RunRtpCommand} reaches the redeem path
 *       (the other variants are renderer-resolved click effects); a non-Run
 *       action is treated as a protocol error and rejected through the same
 *       {@code menu.invalid} path.</li>
 * </ol>
 *
 * <p>Threading: the dispatch chains directly into the {@code /rtp} root's
 * {@code onCommand(args)} so S-005 (no main-thread chunk I/O) is preserved
 * end-to-end — the chunk-I/O happens inside the live teleport pipeline, not
 * inside redeem.
 */
public final class MenuRedeemSubcommand extends BaseRTPCmdImpl {

    /** Permission required to redeem a menu token. Reuses {@code rtp.menu}. */
    public static final String PERMISSION = "rtp.menu";

    /** Parameter name used when the user types {@code /rtp menu token:<value>}. */
    public static final String PARAM_TOKEN = "token";

    /**
     * Parameter name used when the user (or a renderer's pagination click)
     * types {@code /rtp menu page:<n>} (CHECKLIST item 5.3.b, D-005 approved
     * 2026-05-15). Values are 1-indexed on the wire and translated to a
     * zero-based {@link MenuOpenRequest#pageIndex()} before reaching the page
     * builder. Default (parameter absent or unparseable) is page 1 / index 0.
     */
    public static final String PARAM_PAGE = "page";

    private final MenuTokenRegistry tokenRegistry;
    private final TreeCommand rtpRoot;
    private final java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory;
    /** Optional renderer for the no-token open-page path (CHECKLIST item 3.2 / 4.2). */
    private final @Nullable MenuRenderer renderer;
    /**
     * Optional page builder for the open-page path. Signature:
     * {@code (node, openRequest, assembledPath) -> MenuModel}. Decoupling via a SAM
     * keeps {@code rtp-core} free of a direct {@code CommandTreeMenuBuilder}
     * field — callers supply a closure binding the live {@link Predicate} +
     * {@link MenuConsumerProfile}. The {@code assembledPath} is the list of
     * args from the {@code /rtp} root down to (and including) {@code node},
     * e.g. {@code ["config", "performance"]} for {@code /rtp config performance};
     * an empty list opens the root menu page. The builder uses the path to
     * emit Back / Execute navigation rows pointing at the correct sibling /
     * parent / self command.
     */
    private final @Nullable MenuPageBuilder pageBuilder;
    /**
     * Stage A.2 (optional): parameter-value picker builder. When present and
     * the consumed {@link MenuAction} is {@link MenuAction.OpenParamPicker},
     * dispatch routes through this SAM to render the picker sub-page. When
     * {@code null}, an inbound {@code OpenParamPicker} is treated as a
     * protocol error (S-004 reject + WARN), matching the existing
     * {@link MenuAction.OpenMenu} behaviour when {@link #pageBuilder} is
     * absent.
     */
    private final @Nullable MenuParamPickerBuilder paramPickerBuilder;
    /**
     * ADR-045 (optional): platform-side hook to open an anvil GUI for the
     * "type a custom value..." picker row. When present, an inbound
     * {@link MenuAction.PromptAnvilInput} token is dispatched here; on confirm
     * the platform synthesizes
     * {@code /rtp <parentPath...> <paramName>:<typed>} as the player. When
     * {@code null} (Spigot without Adventure, Fabric, test scaffolds), an
     * inbound {@code PromptAnvilInput} is treated as a protocol error
     * (S-004 reject + WARN) — same fallback shape as the absent
     * {@link #paramPickerBuilder}.
     */
    private final @Nullable AnvilInputOpener anvilInputOpener;

    /**
     * SAM signature for {@link #pageBuilder}; declared at the class level so
     * callers (notably {@code RTPCmdBukkit}) can implement it without
     * importing a {@code TriFunction} from a utility package.
     */
    @FunctionalInterface
    public interface MenuPageBuilder {
        /**
         * @param node          the {@link TreeCommand} node to reflect.
         * @param open          the open request, bundling viewer UUID with
         *                      the zero-based {@code pageIndex} requested by
         *                      the click (or the wire-level {@code page:<n>}
         *                      parameter). Builders that produce a single page
         *                      may ignore the index; pagination-capable
         *                      builders (chat-style renderers) should clamp
         *                      to the model's actual page count.
         * @param assembledPath args from the {@code /rtp} root down to (and
         *                      including) {@code node}; empty = root page.
         */
        MenuModel build(TreeCommand node, MenuOpenRequest open, java.util.List<String> assembledPath);
    }

    /**
     * SAM signature for {@link #paramPickerBuilder} (Stage A.2). Implementers
     * reflect {@code parent}'s parameter {@code paramName} into a value-picker
     * {@link MenuModel}. {@code parentPath} is the args from the {@code /rtp}
     * root down to (and including) {@code parent}.
     */
    @FunctionalInterface
    public interface MenuParamPickerBuilder {
        MenuModel build(TreeCommand parent,
                        UUID viewer,
                        java.util.List<String> parentPath,
                        String paramName);
    }

    /**
     * SAM signature for {@link #anvilInputOpener} (ADR-045). Implementations
     * open an anvil GUI on the {@code viewer} player, prefill the rename slot
     * with {@code prefill}, and on confirm submit
     * {@code /rtp <parentPath...> <paramName>:<typed>} as the player. On
     * cancel (inventory closed without confirm) the implementation is a no-op.
     *
     * <p>Must be S-005-safe: no synchronous chunk I/O. On Folia the
     * implementation shall dispatch player-affecting operations via the
     * player's {@code EntityScheduler}.
     *
     * @return {@code true} if the anvil GUI was opened, {@code false} if the
     *         platform refused (player offline, no inventory subsystem, etc).
     *         The caller treats {@code false} as an S-004 reject path.
     */
    @FunctionalInterface
    public interface AnvilInputOpener {
        boolean open(UUID viewer,
                     java.util.List<String> parentPath,
                     String paramName,
                     String prefill);
    }

    /**
     * @param parent        the {@code /rtp} root command (also used as the
     *                      dispatch target for {@link MenuAction.RunRtpCommand}).
     * @param tokenRegistry the registry that minted the inbound token.
     */
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry) {
        this(parent, tokenRegistry, uuid -> perm -> true, null, null, null);
    }

    /**
     * Test / wire-up constructor: callers (notably {@code rtp-plugin}) supply a
     * platform-aware {@link Predicate} factory so the dispatched {@link MenuAction.RunRtpCommand}
     * traverses the live command tree with the correct permission view.
     *
     * @param permissionProbeFactory maps the sender UUID to a permission probe.
     *                               Default behaviour ({@code _ -> _ -> true}) is
     *                               sound because every node in the {@code /rtp}
     *                               tree also gates itself at the adapter layer.
     */
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory) {
        this(parent, tokenRegistry, permissionProbeFactory, null, null, null);
    }

    /**
     * Full-scope constructor (CHECKLIST item 4.2.a): adds optional open-page support.
     *
     * <p>When {@code renderer} and {@code pageBuilder} are both non-{@code null},
     * a token-less invocation ({@code /rtp menu} or {@code /rtp menu <subtree>})
     * reflects the matching {@link TreeCommand} node via {@code pageBuilder} and
     * passes the resulting {@link MenuModel} to {@code renderer.render(...)}.
     * When either is {@code null} (e.g. no renderer registered on this platform,
     * or the operator's {@code menu.renderer} list is exhausted), the no-token
     * path falls back to the existing {@code menuInvalid} reject + WARN log
     * (backward compatible with the Stage 3 wire-up).
     *
     * @param renderer    optional renderer used for open-page dispatch; pass
     *                    {@code null} to disable open-page support.
     * @param pageBuilder optional page builder; pass {@code null} together with
     *                    {@code renderer} to disable open-page support. The
     *                    closure must apply the caller's {@link Predicate} +
     *                    {@link MenuConsumerProfile} when reflecting the node.
     */
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder) {
        this(parent, tokenRegistry, permissionProbeFactory, renderer, pageBuilder, null);
    }

    /**
     * Stage A.2 constructor: extends the renderer + page-builder wire-up with
     * an optional {@link MenuParamPickerBuilder} for the parameter-value
     * picker sub-page. Pass {@code null} for {@code paramPickerBuilder} to
     * keep the pre-Stage-A.2 behaviour (inbound {@link MenuAction.OpenParamPicker}
     * tokens reject with {@code menuInvalid} + WARN).
     */
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder,
                                @Nullable MenuParamPickerBuilder paramPickerBuilder) {
        this(parent, tokenRegistry, permissionProbeFactory,
                renderer, pageBuilder, paramPickerBuilder, null);
    }

    /**
     * ADR-045 constructor: adds an optional {@link AnvilInputOpener} for the
     * "type a custom value..." picker row. Pass {@code null} to keep the
     * pre-ADR-045 behaviour (inbound {@link MenuAction.PromptAnvilInput}
     * tokens reject with {@code menuInvalid} + WARN).
     */
    public MenuRedeemSubcommand(TreeCommand parent, MenuTokenRegistry tokenRegistry,
                                java.util.function.Function<UUID, Predicate<String>> permissionProbeFactory,
                                @Nullable MenuRenderer renderer,
                                @Nullable MenuPageBuilder pageBuilder,
                                @Nullable MenuParamPickerBuilder paramPickerBuilder,
                                @Nullable AnvilInputOpener anvilInputOpener) {
        super(parent);
        this.rtpRoot = Objects.requireNonNull(parent, "parent");
        this.tokenRegistry = Objects.requireNonNull(tokenRegistry, "tokenRegistry");
        this.permissionProbeFactory =
                Objects.requireNonNull(permissionProbeFactory, "permissionProbeFactory");
        // Either both renderer + builder are present (open-page enabled), or
        // both are null (open-page disabled, falls back to menuInvalid). A
        // half-configured state would be ambiguous, so we collapse it.
        if (renderer != null && pageBuilder != null) {
            this.renderer = renderer;
            this.pageBuilder = pageBuilder;
            // Param picker can only function when a renderer is also wired;
            // collapse the half-configured case to null for consistency.
            this.paramPickerBuilder = paramPickerBuilder;
            // Same for the anvil opener: only meaningful when a renderer is
            // wired (the action is renderer-emitted from a rendered picker).
            this.anvilInputOpener = anvilInputOpener;
        } else {
            this.renderer = null;
            this.pageBuilder = null;
            this.paramPickerBuilder = null;
            this.anvilInputOpener = null;
        }
        // Register a hidden curated parameter so commands-api recognises
        //   /rtp menu token:<v>    → subcommand "menu" + param "token=<v>"
        // This is the only valid form on the `/rtp` root: `menu:<token>` at
        // root level would be parsed as parameter "menu" (which doesn't exist)
        // and yield msgBadParameter. BookMenuRenderer emits the form above.
        getParameterLookup().put(PARAM_TOKEN, new CommandParameter(PERMISSION,
                "menu redeem token (opaque)", (uuid, value) -> true) {
            @Override
            public java.util.Set<String> values() {
                return java.util.Collections.emptySet();
            }
        });
        // Page parameter (CHECKLIST 5.3.b): `/rtp menu page:<n>` opens the
        // matching subtree at 1-indexed page n. No curated value list (the
        // valid range depends on the reflected model, not on the parameter
        // itself); the predicate accepts any positive integer string.
        getParameterLookup().put(PARAM_PAGE, new CommandParameter(PERMISSION,
                "menu page index (1-indexed)", (uuid, value) -> {
                    if (value == null) return false;
                    try {
                        return Integer.parseInt(value) >= 1;
                    } catch (NumberFormatException ignored) {
                        return false;
                    }
                }) {
            @Override
            public java.util.Set<String> values() {
                return java.util.Collections.emptySet();
            }
        });
    }

    @Override
    public String name() {
        return "menu";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public boolean onCommand(UUID senderId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand) {
        return dispatch(senderId, parameterValues, nextCommand, null);
    }

    @Override
    public boolean onCommand(UUID senderId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand,
                             Consumer<String> messageMethod) {
        return dispatch(senderId, parameterValues, nextCommand, messageMethod);
    }

    private boolean dispatch(UUID senderId,
                             Map<String, List<String>> parameterValues,
                             @Nullable CommandsAPICommand nextCommand,
                             @Nullable Consumer<String> messageMethod) {
        if (senderId == null) {
            reject(null, MessagesKeys.menuUnknownPlayer,
                    "menu redeem rejected: no sender UUID", messageMethod);
            return false;
        }
        String token = extractToken(parameterValues);
        if (token == null || token.isEmpty()) {
            // No token → open-page path (CHECKLIST item 3.2 / 4.2.a). If a
            // renderer + builder were wired, reflect the targeted node and
            // hand the resulting MenuModel to the renderer. Otherwise the
            // existing menuInvalid + WARN fallback applies (backward
            // compatible with the pre-Stage-4 wire-up).
            if (renderer != null && pageBuilder != null) {
                int pageIndex = extractPageIndex(parameterValues);
                return openPage(senderId, nextCommand, pageIndex, messageMethod);
            }
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu redeem rejected: missing token", messageMethod);
            return false;
        }
        Optional<MenuAction> consumed;
        try {
            consumed = tokenRegistry.consume(senderId, token);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu redeem failed for " + senderId + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu redeem rejected: registry failure", messageMethod);
            return false;
        }
        if (consumed.isEmpty()) {
            // Unknown / expired / wrong player — the registry collapses all three
            // into Optional.empty() per its contract. We surface the "expired" key
            // when the player still has outstanding tokens (more likely TTL),
            // otherwise "invalid" (more likely typo / replay / wrong player).
            MessagesKeys key = tokenRegistry.outstandingFor(senderId) > 0
                    ? MessagesKeys.menuExpired
                    : MessagesKeys.menuInvalid;
            reject(senderId, key,
                    "menu redeem rejected: token " + token + " not consumable",
                    messageMethod);
            return false;
        }
        MenuAction action = consumed.get();
        // RunRtpCommand → execute the assembled /rtp command tail.
        if (action instanceof MenuAction.RunRtpCommand run) {
            return dispatchRun(senderId, run, messageMethod);
        }
        // OpenMenu → server-side navigation (back / forward-descend). Resolves
        // the path against the live TreeCommand graph; never re-enters the
        // commands-api parser, which is what makes /rtp menu config /
        // /rtp menu config performance / ← back possible despite `config` not
        // being a parameter of the `menu` subcommand.
        if (action instanceof MenuAction.OpenMenu open) {
            return dispatchOpen(senderId, open, messageMethod);
        }
        // OpenParamPicker → server-side parameter-value picker page (Stage A.2).
        // Resolves the parent path against the live TreeCommand graph (same
        // pattern as OpenMenu) and hands off to paramPickerBuilder.
        if (action instanceof MenuAction.OpenParamPicker picker) {
            return dispatchOpenParamPicker(senderId, picker, messageMethod);
        }
        // PromptAnvilInput → open an anvil GUI on the clicking player and
        // let them type a free-form value (ADR-045). Hand off to the
        // platform-side AnvilInputOpener when wired; otherwise reject as a
        // protocol error (renderer should have fallen back to SuggestInput
        // before emitting this action on an unsupported platform).
        if (action instanceof MenuAction.PromptAnvilInput prompt) {
            return dispatchPromptAnvilInput(senderId, prompt, messageMethod);
        }
        // Renderer click effects (SuggestInput / ChangePage / OpenExternalUrl)
        // must never reach redeem per ADR-035 §3. If one does, the renderer
        // is buggy — refuse and log.
        RTP.log(Level.WARNING, "menu redeem received non-dispatchable action "
                + action.getClass().getSimpleName() + " for " + senderId);
        reject(senderId, MessagesKeys.menuInvalid,
                "menu redeem rejected: action kind not dispatchable",
                messageMethod);
        return false;
    }

    /**
     * Open-page branch (no-token path). Reflects the root menu page; the
     * forward-descend / back navigation lives in {@link #dispatchOpen} which
     * walks an explicit path against the live tree.
     *
     * <p>{@code nextCommand} is preserved for backward compatibility with the
     * Stage-3 wire-up (a {@code /rtp menu <sub>} bare-arg form historically
     * routed the matching sub-{@code TreeCommand} here). Stage A.1 callers
     * normally hit this through the no-token bare {@code /rtp menu} form,
     * which lands on {@code rtpRoot}.
     *
     * <p>Failure modes:
     * <ul>
     *   <li>{@code pageBuilder} throws → log WARN + reject with
     *       {@code menuInvalid} (S-004).</li>
     *   <li>{@code renderer} throws (e.g. S-006 offline-player
     *       {@link IllegalStateException}) → log WARN + reject with
     *       {@code menuInvalid} (S-004).</li>
     * </ul>
     */
    private boolean openPage(UUID senderId,
                             @Nullable CommandsAPICommand nextCommand,
                             int pageIndex,
                             @Nullable Consumer<String> messageMethod) {
        TreeCommand target = rtpRoot;
        java.util.List<String> assembledPath = java.util.Collections.emptyList();
        if (nextCommand instanceof TreeCommand tc) {
            target = tc;
            String n = tc.name();
            if (n != null && !n.isEmpty()) {
                assembledPath = java.util.List.of(n);
            }
        }
        return renderAt(senderId, target, assembledPath, pageIndex, messageMethod);
    }

    /**
     * Navigation branch (token-bearing {@link MenuAction.OpenMenu}). The
     * action's {@code path} is the args from the {@code /rtp} root down to
     * the target node; an empty path opens the root menu page. The path is
     * walked against {@link #rtpRoot} via {@link TreeCommand#getCommandLookup()}
     * — server-side resolution only, the commands-api parser is never
     * re-entered on this path, so navigation rows are independent of the
     * {@code menu} subcommand's parameter grammar (which only knows
     * {@code token}).
     *
     * <p>Unknown / unreachable segments collapse to {@code menuInvalid} +
     * WARN (S-004).
     */
    private boolean dispatchOpen(UUID senderId,
                                 MenuAction.OpenMenu open,
                                 @Nullable Consumer<String> messageMethod) {
        if (renderer == null || pageBuilder == null) {
            // OpenMenu reached redeem but no renderer/builder is wired. Treat
            // as a protocol error (renderer minted it without us having one
            // to dispatch through) and refuse rather than silently no-op.
            RTP.log(Level.WARNING,
                    "menu open-action received with open-page disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu open rejected: open-page disabled", messageMethod);
            return false;
        }
        String[] path = open.path();
        TreeCommand target = rtpRoot;
        for (String segment : path) {
            // Stage A.3: segments containing ':' are staged parameter
            // assignments (`name:value`) accumulated by the picker flow.
            // They do not advance the command-node walk — they ride along
            // in the assembled path and are surfaced by the Execute row.
            if (segment != null && segment.indexOf(':') >= 0) {
                continue;
            }
            CommandsAPICommand next = target.getCommandLookup().get(segment.toUpperCase(java.util.Locale.ROOT));
            if (!(next instanceof TreeCommand tc)) {
                RTP.log(Level.WARNING,
                        "menu open-action path segment '" + segment
                                + "' did not resolve to a TreeCommand under "
                                + target.name() + " for " + senderId);
                reject(senderId, MessagesKeys.menuInvalid,
                        "menu open rejected: unknown path segment '" + segment + "'",
                        messageMethod);
                return false;
            }
            target = tc;
        }
        return renderAt(senderId, target, java.util.List.of(path), 0, messageMethod);
    }

    /**
     * Stage A.2 dispatch for {@link MenuAction.OpenParamPicker}. Walks
     * {@code parentPath} against the live {@link TreeCommand} graph (same
     * pattern as {@link #dispatchOpen}), verifies the parameter exists on
     * the resolved node, then invokes the configured
     * {@link MenuParamPickerBuilder} and hands the resulting {@link MenuModel}
     * to the renderer.
     *
     * <p>All failure paths log WARN and reject with {@code menuInvalid}
     * (S-004): missing builder, unknown path segment, unknown parameter,
     * builder exception, null model, or renderer exception.
     */
    private boolean dispatchOpenParamPicker(UUID senderId,
                                            MenuAction.OpenParamPicker picker,
                                            @Nullable Consumer<String> messageMethod) {
        if (renderer == null || paramPickerBuilder == null) {
            RTP.log(Level.WARNING,
                    "menu param-picker received with picker-page disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu param-picker rejected: picker-page disabled", messageMethod);
            return false;
        }
        String[] parentPath = picker.parentPath();
        TreeCommand target = rtpRoot;
        for (String segment : parentPath) {
            // Stage A.3: skip staged `name:value` parameter assignments —
            // they ride along in the assembled path without advancing the
            // command-node walk (see dispatchOpen).
            if (segment != null && segment.indexOf(':') >= 0) {
                continue;
            }
            CommandsAPICommand next = target.getCommandLookup()
                    .get(segment.toUpperCase(java.util.Locale.ROOT));
            if (!(next instanceof TreeCommand tc)) {
                RTP.log(Level.WARNING,
                        "menu param-picker path segment '" + segment
                                + "' did not resolve to a TreeCommand under "
                                + target.name() + " for " + senderId);
                reject(senderId, MessagesKeys.menuInvalid,
                        "menu param-picker rejected: unknown path segment '" + segment + "'",
                        messageMethod);
                return false;
            }
            target = tc;
        }
        // Verify the parameter exists (case-insensitive on the upper-cased
        // commands-api key, then on the raw name).
        String paramName = picker.paramName();
        Map<String, CommandParameter> paramLookup = target.getParameterLookup();
        boolean known = false;
        if (paramLookup != null) {
            if (paramLookup.containsKey(paramName)) known = true;
            else if (paramLookup.containsKey(paramName.toUpperCase(java.util.Locale.ROOT))) known = true;
        }
        if (!known) {
            RTP.log(Level.WARNING,
                    "menu param-picker unknown parameter '" + paramName
                            + "' on " + target.name() + " for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu param-picker rejected: unknown parameter '" + paramName + "'",
                    messageMethod);
            return false;
        }
        MenuModel model;
        try {
            model = paramPickerBuilder.build(target, senderId,
                    java.util.List.of(parentPath), paramName);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu param-picker failed for " + senderId
                            + " node=" + target.name() + " param=" + paramName
                            + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu param-picker rejected: builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu param-picker rejected: builder returned null model",
                    messageMethod);
            return false;
        }
        try {
            renderer.render(senderId, model);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu param-picker render failed for " + senderId
                            + " node=" + target.name() + " param=" + paramName
                            + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu param-picker rejected: renderer failure", messageMethod);
            return false;
        }
    }

    /**
     * ADR-045 dispatch for {@link MenuAction.PromptAnvilInput}. Walks
     * {@code parentPath} against the live {@link TreeCommand} graph to
     * confirm the parameter exists (defensive — the renderer should never
     * mint this for an unknown parameter, but a stale token after a config
     * reload could land here), then hands off to the platform-side
     * {@link AnvilInputOpener}. The opener is responsible for opening the
     * anvil GUI on the player and, on confirm, submitting
     * {@code /rtp <parentPath...> <paramName>:<typed>} as the player.
     *
     * <p>Failure paths log WARN and reject with {@code menuInvalid} (S-004):
     * opener absent, unknown path segment, unknown parameter, or opener
     * threw / returned {@code false}.
     */
    private boolean dispatchPromptAnvilInput(UUID senderId,
                                             MenuAction.PromptAnvilInput prompt,
                                             @Nullable Consumer<String> messageMethod) {
        if (anvilInputOpener == null) {
            RTP.log(Level.WARNING,
                    "menu anvil-input received with anvil-input disabled for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu anvil-input rejected: anvil-input disabled", messageMethod);
            return false;
        }
        String[] parentPath = prompt.parentPath();
        TreeCommand target = rtpRoot;
        for (String segment : parentPath) {
            if (segment != null && segment.indexOf(':') >= 0) {
                continue;
            }
            CommandsAPICommand next = target.getCommandLookup()
                    .get(segment.toUpperCase(java.util.Locale.ROOT));
            if (!(next instanceof TreeCommand tc)) {
                RTP.log(Level.WARNING,
                        "menu anvil-input path segment '" + segment
                                + "' did not resolve to a TreeCommand under "
                                + target.name() + " for " + senderId);
                reject(senderId, MessagesKeys.menuInvalid,
                        "menu anvil-input rejected: unknown path segment '" + segment + "'",
                        messageMethod);
                return false;
            }
            target = tc;
        }
        String paramName = prompt.paramName();
        Map<String, CommandParameter> paramLookup = target.getParameterLookup();
        boolean known = false;
        if (paramLookup != null) {
            if (paramLookup.containsKey(paramName)) known = true;
            else if (paramLookup.containsKey(paramName.toUpperCase(java.util.Locale.ROOT))) known = true;
        }
        if (!known) {
            RTP.log(Level.WARNING,
                    "menu anvil-input unknown parameter '" + paramName
                            + "' on " + target.name() + " for " + senderId);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu anvil-input rejected: unknown parameter '" + paramName + "'",
                    messageMethod);
            return false;
        }
        boolean opened;
        try {
            opened = anvilInputOpener.open(senderId,
                    java.util.List.of(parentPath), paramName, prompt.prefill());
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu anvil-input opener failed for " + senderId
                            + " node=" + target.name() + " param=" + paramName
                            + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu anvil-input rejected: opener failure", messageMethod);
            return false;
        }
        if (!opened) {
            RTP.log(Level.WARNING,
                    "menu anvil-input opener refused for " + senderId
                            + " node=" + target.name() + " param=" + paramName);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu anvil-input rejected: opener refused", messageMethod);
            return false;
        }
        return true;
    }

    /**
     * Shared finalisation step for both {@link #openPage} (no-token root) and
     * {@link #dispatchOpen} (token-bearing OpenMenu): invoke the configured
     * {@link #pageBuilder} for {@code target} carrying the {@code assembledPath},
     * then hand the resulting {@link MenuModel} to the {@link #renderer}. All
     * failure paths log WARN + reject with {@code menuInvalid} (S-004); the
     * renderer is never invoked with a null model.
     */
    private boolean renderAt(UUID senderId,
                             TreeCommand target,
                             java.util.List<String> assembledPath,
                             int pageIndex,
                             @Nullable Consumer<String> messageMethod) {
        MenuModel model;
        MenuOpenRequest open = new MenuOpenRequest(senderId, Math.max(0, pageIndex));
        try {
            model = pageBuilder.build(target, open, assembledPath);
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu open-page failed for " + senderId
                            + " node=" + target.name() + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu open rejected: page builder failure", messageMethod);
            return false;
        }
        if (model == null) {
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu open rejected: builder returned null model", messageMethod);
            return false;
        }
        try {
            renderer.render(senderId, model);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu render failed for " + senderId
                            + " node=" + target.name() + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu open rejected: renderer failure", messageMethod);
            return false;
        }
    }

    private boolean dispatchRun(UUID senderId, MenuAction.RunRtpCommand run,
                                @Nullable Consumer<String> messageMethod) {
        String[] args = run.args();
        Consumer<String> sink = messageMethod != null
                ? messageMethod
                : msg -> RTP.serverAccessor.sendMessage(RTPAPI.serverId, senderId, msg, null);
        Predicate<String> permissionProbe = permissionProbeFactory.apply(senderId);
        if (permissionProbe == null) permissionProbe = perm -> true;
        try {
            rtpRoot.onCommand(senderId,
                    permissionProbe,
                    sink,
                    args,
                    0,
                    null);
            return true;
        } catch (RuntimeException e) {
            RTP.log(Level.WARNING,
                    "menu redeem dispatch failed for " + senderId
                            + " args=" + java.util.Arrays.toString(args) + ": " + e.getMessage(), e);
            reject(senderId, MessagesKeys.menuInvalid,
                    "menu redeem rejected: dispatch failure",
                    messageMethod);
            return false;
        }
    }

    /**
     * Extracts the zero-based page index from the parsed {@code page} parameter.
     * Wire format is 1-indexed ({@code page:1} = first page); we subtract one
     * before returning. Missing, empty, non-numeric, or {@code < 1} values
     * collapse to {@code 0} (first page) so a malformed click degrades
     * gracefully — the parameter-level predicate already rejects clearly
     * invalid inputs at the commands-api parser, this is a defensive backstop.
     */
    private static int extractPageIndex(Map<String, List<String>> parameterValues) {
        if (parameterValues == null) return 0;
        List<String> vs = parameterValues.get(PARAM_PAGE);
        if (vs == null || vs.isEmpty() || vs.get(0) == null) return 0;
        try {
            int oneBased = Integer.parseInt(vs.get(0));
            return oneBased < 1 ? 0 : oneBased - 1;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static @Nullable String extractToken(Map<String, List<String>> parameterValues) {
        if (parameterValues == null) return null;
        List<String> vs = parameterValues.get(PARAM_TOKEN);
        if (vs != null && !vs.isEmpty() && vs.get(0) != null && !vs.get(0).isEmpty()) {
            return vs.get(0);
        }
        // Fallback: commands-api may key the bare arg under the literal name.
        vs = parameterValues.get("menu");
        if (vs != null && !vs.isEmpty() && vs.get(0) != null && !vs.get(0).isEmpty()) {
            return vs.get(0);
        }
        return null;
    }

    private void reject(@Nullable UUID senderId,
                        MessagesKeys key,
                        String logMessage,
                        @Nullable Consumer<String> messageMethod) {
        // S-004: never silently swallow. The log is unconditional; the user
        // message goes through the standard route.
        RTP.log(Level.WARNING, logMessage);
        if (senderId == null) return;
        String userMsg = msg(key, defaultFor(key));
        if (messageMethod != null) {
            messageMethod.accept(userMsg);
        } else {
            RTP.serverAccessor.sendMessage(RTPAPI.serverId, senderId, userMsg, null);
        }
    }

    private static String defaultFor(MessagesKeys key) {
        return switch (key) {
            case menuInvalid -> "Invalid menu token.";
            case menuExpired -> "That menu has expired — please re-open it.";
            case menuUnknownPlayer -> "Menus may only be used by online players.";
            default -> key.name();
        };
    }
}
