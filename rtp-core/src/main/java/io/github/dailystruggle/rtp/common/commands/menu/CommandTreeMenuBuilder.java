package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.FloatParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuConsumerProfile;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.YamlCommentLookup;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Command-tree menu reflector (ADR-044).
 *
 * <p>Given a {@code commands-api} {@link TreeCommand} node, the caller's UUID, a
 * permission {@link Predicate}, and a {@link MenuConsumerProfile}, walks
 * {@link TreeCommand#getCommandLookup()} and {@link TreeCommand#getParameterLookup()}
 * filtered by {@link CommandsAPICommand#permission()} to produce a {@link MenuModel}.
 *
 * <p>Hover-text resolution per ADR-044 §4:
 *
 * <ol>
 *   <li>{@code commentLookup().commentFor(fileBasename, dottedKeyPath)} — the
 *       per-consumer {@link YamlCommentLookup}.</li>
 *   <li>Declared parameter type + bounds, formatted from
 *       {@code messages.yml → menu.hoverFallback.type} / {@code .bounds}.</li>
 *   <li>{@code null} (no hover) when neither path produced text.</li>
 * </ol>
 *
 * <p>Command-fragment hover always comes from
 * {@link CommandsAPICommand#description()} (resolved from {@code messages.yml}).
 *
 * <p>The reflector does not store renderer or platform types; the returned
 * {@link MenuModel} is plain-text and consumed by a {@code MenuRenderer}.
 * It mints one token per clickable fragment so the rendered click event can
 * carry the opaque {@code menu:<token>} payload per ADR-035 §3.
 */
public final class CommandTreeMenuBuilder {

    /** Default TTL for tokens minted by the reflector when the config knob is unset. */
    public static final java.time.Duration DEFAULT_TOKEN_TTL = java.time.Duration.ofSeconds(60);

    /**
     * Stage A.6 — number of suggestion value rows packed onto a single
     * parameter-value picker page before overflow rows are sliced onto
     * subsequent {@link MenuPage}s. Picked so the back + header + type +
     * value rows + optional prev/next nav rows all fit comfortably within
     * a typical Adventure {@code Book} page (~14 visible lines). Large
     * suggestion sets (e.g. biome names) span multiple pages with
     * {@link MenuAction.ChangePage} nav rows.
     */
    public static final int PICKER_VALUES_PER_PAGE = 10;

    private final io.github.dailystruggle.rtp.api.menu.MenuTokenRegistry tokenRegistry;
    private final java.time.Duration tokenTtl;

    public CommandTreeMenuBuilder(io.github.dailystruggle.rtp.api.menu.MenuTokenRegistry tokenRegistry) {
        this(tokenRegistry, DEFAULT_TOKEN_TTL);
    }

    public CommandTreeMenuBuilder(io.github.dailystruggle.rtp.api.menu.MenuTokenRegistry tokenRegistry,
                                  java.time.Duration tokenTtl) {
        this.tokenRegistry = Objects.requireNonNull(tokenRegistry, "tokenRegistry");
        this.tokenTtl = Objects.requireNonNull(tokenTtl, "tokenTtl");
    }

    /**
     * Back-compatible 4-arg form. Delegates to the 5-arg
     * {@link #build(TreeCommand, UUID, Predicate, MenuConsumerProfile, List)}
     * with an empty {@code assembledPath} (i.e. treats {@code root} as the
     * top-level {@code /rtp} menu page — no Back row, no Execute row).
     */
    public MenuModel build(TreeCommand root,
                           UUID callerId,
                           Predicate<String> permission,
                           MenuConsumerProfile profile) {
        return build(root, callerId, permission, profile, Collections.emptyList());
    }

    /**
     * Reflect {@code root} for {@code callerId} filtered by {@code permission}
     * and parameterized by {@code profile}, returning a single-page
     * {@link MenuModel}. {@code assembledPath} is the args from the {@code /rtp}
     * root down to (and including) {@code root}'s position in the live command
     * tree (e.g. {@code ["config", "performance"]} for {@code /rtp config performance});
     * an empty list means {@code root} is the top-level {@code /rtp} menu page.
     *
     * <p>Layout of the produced page (Stage A.1 — minimum viable navigation):
     * <ol>
     *   <li><b>Back row</b> ({@code OpenMenu(<assembledPath minus last>)}) —
     *       prepended only when {@code assembledPath} is non-empty.</li>
     *   <li><b>Execute row</b> ({@code RunRtpCommand(<assembledPath>)}) —
     *       prepended only when {@code assembledPath} is non-empty (i.e. the
     *       node is a runnable {@code /rtp …} tail).</li>
     *   <li><b>Subcommand rows</b> — for each visible sub of {@code root},
     *       skipping {@code help} and {@code menu}. Subs with navigable
     *       content (further subs after exclusions, or any parameters) emit
     *       {@link MenuAction.OpenMenu} so clicking descends. Pure-leaf subs
     *       emit {@link MenuAction.RunRtpCommand} so clicking executes.</li>
     *   <li><b>Parameter rows</b> — {@link MenuAction.SuggestInput} chat
     *       prefill (unchanged from pre-Stage-A behaviour; enumerable-param
     *       sub-pages are deferred to Stage A.2).</li>
     * </ol>
     *
     * <p>Inaccessible subcommands (those whose {@link CommandsAPICommand#permission()}
     * is non-empty and rejected by {@code permission.test(...)}) are
     * <em>fully hidden</em>, not greyed out (ADR-035 amendment 2026-05-15,
     * checklist scope answer A). Both {@code help} and {@code menu} are
     * additionally excluded: the menu is already the navigable rendering of
     * the tree, so a clickable {@code help} would dump plaintext, and a
     * clickable {@code menu} would loop the player back into the same page.
     */
    public MenuModel build(TreeCommand root,
                           UUID callerId,
                           Predicate<String> permission,
                           MenuConsumerProfile profile,
                           List<String> assembledPath) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(assembledPath, "assembledPath");

        Deque<String> commandPath = new ArrayDeque<>();
        commandPath.addLast(root.name() == null ? "" : root.name());

        List<MenuLine> lines = new ArrayList<>();

        // 0root. Root-page UX framing — non-clickable title + hint rows
        //    prepended only on the root /rtp menu page (assembledPath empty)
        //    so the menu has visible framing before the player descends
        //    into any subcommand. Stage A.5 — REQ-RTP-F-013.
        if (assembledPath.isEmpty()) {
            String title = lookupMsg(MessagesKeys.menuRootTitle, "&6&l⚡ RTP menu");
            if (title != null && !title.isEmpty()) {
                lines.add(MenuLine.of(new MenuFragment(title, null, null)));
            }
            String hint = lookupMsg(MessagesKeys.menuRootHint,
                    "&7click an option below to begin");
            if (hint != null && !hint.isEmpty()) {
                lines.add(MenuLine.of(new MenuFragment(hint, null, null)));
            }
        }

        // 0. Constructed-command header — non-clickable breadcrumb showing
        //    the full /rtp invocation currently being assembled, including
        //    any staged `name:value` parameter assignments riding in
        //    assembledPath. Prepended only on non-root pages (the root /rtp
        //    menu page has nothing to show). Stage A.4 — REQ-RTP-F-013.
        if (!assembledPath.isEmpty()) {
            String headerTmpl = lookupMsg(MessagesKeys.menuConstructed,
                    "building: /rtp [command]");
            String headerLabel = headerTmpl
                    .replace("[command]", String.join(" ", assembledPath));
            lines.add(MenuLine.of(new MenuFragment(headerLabel, null, null)));
        }

        // 0a. Back row — only for non-root pages.
        if (!assembledPath.isEmpty()) {
            String[] parentPath = assembledPath.subList(0, assembledPath.size() - 1)
                    .toArray(new String[0]);
            String backLabel = lookupMsg(MessagesKeys.menuBack, "« back");
            lines.add(MenuLine.of(new MenuFragment(backLabel, null,
                    new MenuAction.OpenMenu(parentPath))));
        }

        // 0b. Execute row — only for non-root pages (root /rtp menu has no
        //     useful assembled command to execute; it would just re-open the
        //     menu page we're already on).
        if (!assembledPath.isEmpty()) {
            String[] runArgs = assembledPath.toArray(new String[0]);
            String tmpl = lookupMsg(MessagesKeys.menuExecute, "▶ run /rtp [command]");
            String label = tmpl.replace("[command]", String.join(" ", assembledPath));
            lines.add(MenuLine.of(new MenuFragment(label, null,
                    new MenuAction.RunRtpCommand(runArgs))));
        }

        // 1. Subcommand rows.
        Map<String, CommandsAPICommand> subs = root.getCommandLookup();
        if (subs != null) {
            for (Map.Entry<String, CommandsAPICommand> e : subs.entrySet()) {
                CommandsAPICommand sub = e.getValue();
                if (sub == null) continue;
                String name = e.getKey();
                // `help` is a meta-command (renders text help); excluded from
                // menus because the menu itself is the navigable rendering of
                // the same tree — a clickable `help` would dispatch a plaintext
                // help dump on click. `menu` is excluded because clicking it
                // would re-open the very page the player is on.
                if (name != null
                        && (name.equalsIgnoreCase("help")
                                || name.equalsIgnoreCase("menu"))) {
                    continue;
                }
                String perm = sub.permission();
                if (perm != null && !perm.isEmpty() && !permission.test(perm)) {
                    continue; // hidden, per scope answer A
                }
                String[] args = appendArg(commandPath, name);
                String hover = sub.description();
                if (hover != null && hover.isEmpty()) hover = null;

                MenuAction action;
                if (sub instanceof TreeCommand subTree && hasNavigableContent(subTree, permission)) {
                    // Descend: clicking re-opens the menu at the subtree.
                    String[] childPath = new String[assembledPath.size() + 1];
                    for (int i = 0; i < assembledPath.size(); i++) {
                        childPath[i] = assembledPath.get(i);
                    }
                    childPath[assembledPath.size()] = name;
                    action = new MenuAction.OpenMenu(childPath);
                } else {
                    // Pure leaf: clicking executes the subcommand directly.
                    action = new MenuAction.RunRtpCommand(args);
                }
                lines.add(MenuLine.of(new MenuFragment(name, hover, action)));
            }
        }

        // 2. Parameter rows. Stage A.2: when the parameter exposes any
        //    suggestions (via relevantValues(callerId) — the same source that
        //    feeds tab-completion), clicking opens a value-picker sub-page
        //    (MenuAction.OpenParamPicker, server-resolved by
        //    MenuRedeemSubcommand). Otherwise we fall back to SuggestInput
        //    chat-prefill so the player can type a free-form value.
        Map<String, CommandParameter> params = root.getParameterLookup();
        if (params != null) {
            String[] parentPath = assembledPath.toArray(new String[0]);
            for (Map.Entry<String, CommandParameter> e : params.entrySet()) {
                CommandParameter param = e.getValue();
                if (param == null) continue;
                String perm = param.permission();
                if (perm != null && !perm.isEmpty() && !permission.test(perm)) {
                    continue;
                }
                String name = e.getKey();
                MenuAction action;
                Set<String> suggestions = safeSuggestions(param, callerId);
                if (suggestions != null && !suggestions.isEmpty()) {
                    action = new MenuAction.OpenParamPicker(parentPath, name);
                } else {
                    String prefix = profile.suggestPrefix(commandPath, name);
                    action = new MenuAction.SuggestInput(prefix);
                }
                String hover = resolveParamHover(profile, root, name, param);
                lines.add(MenuLine.of(new MenuFragment(name, hover, action)));
            }
        }

        // Mint a token per clickable fragment so the renderer's click payload can be
        // the opaque /rtp menu token:<token> form (ADR-035 §3). Token-binding is wholly
        // server-side; the builder retains no reference to the minted tokens
        // because the renderer owns the click-event materialisation.
        for (MenuLine line : lines) {
            for (MenuFragment fragment : line.fragments()) {
                MenuAction action = fragment.action();
                if (action != null) {
                    tokenRegistry.mint(callerId, action, tokenTtl);
                }
            }
        }

        String title = root.name() == null ? "" : root.name();
        return new MenuModel(title, List.of(new MenuPage(lines)));
    }

    /**
     * "Navigable content" predicate (Stage A.1): a sub-{@link TreeCommand} is
     * treated as menu-navigable (clicking it opens its own page) when, after
     * excluding {@code help} and {@code menu}, it exposes at least one visible
     * sub-command <em>or</em> at least one visible parameter under the caller's
     * permission view. Otherwise it is a pure leaf — clicking it executes the
     * subcommand directly.
     */
    private static boolean hasNavigableContent(TreeCommand sub, Predicate<String> permission) {
        Map<String, CommandsAPICommand> innerSubs = sub.getCommandLookup();
        if (innerSubs != null) {
            for (Map.Entry<String, CommandsAPICommand> e : innerSubs.entrySet()) {
                CommandsAPICommand inner = e.getValue();
                if (inner == null) continue;
                String n = e.getKey();
                if (n != null && (n.equalsIgnoreCase("help") || n.equalsIgnoreCase("menu"))) {
                    continue;
                }
                String perm = inner.permission();
                if (perm != null && !perm.isEmpty() && !permission.test(perm)) {
                    continue;
                }
                return true;
            }
        }
        Map<String, CommandParameter> innerParams = sub.getParameterLookup();
        if (innerParams != null) {
            for (CommandParameter p : innerParams.values()) {
                if (p == null) continue;
                String perm = p.permission();
                if (perm != null && !perm.isEmpty() && !permission.test(perm)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static String[] appendArg(Deque<String> commandPath, String tail) {
        // commandPath is the path from `/rtp` root down to the current node; the
        // emitted args target the same root, so we skip the leading element when
        // it equals the implicit command label.
        List<String> out = new ArrayList<>(commandPath.size());
        boolean first = true;
        for (String segment : commandPath) {
            if (first) { first = false; continue; }
            out.add(segment);
        }
        out.add(tail);
        return out.toArray(new String[0]);
    }

    private String resolveParamHover(MenuConsumerProfile profile,
                                     TreeCommand parent,
                                     String paramName,
                                     CommandParameter param) {
        // 1. YAML block comment.
        String fileBasename = parent.name() == null ? "" : parent.name();
        Optional<String> yamlComment = profile.commentLookup()
                .commentFor(fileBasename, paramName);
        if (yamlComment.isPresent() && !yamlComment.get().isBlank()) {
            return yamlComment.get();
        }

        // 2. Declared type + bounds.
        String typeName = simpleTypeName(param);
        if (typeName != null) {
            Set<String> values = safeValues(param);
            if (values != null && !values.isEmpty() && values.size() <= 16) {
                String tmpl = lookupMsg(MessagesKeys.menuHoverFallbackBounds,
                        "[type]: [values]");
                return tmpl.replace("[type]", typeName)
                        .replace("[values]", String.join(", ", values));
            }
            String tmpl = lookupMsg(MessagesKeys.menuHoverFallbackType, "[type]");
            return tmpl.replace("[type]", typeName);
        }

        // 3. No hover.
        return null;
    }

    private static String simpleTypeName(CommandParameter param) {
        if (param instanceof BooleanParameter) return "boolean";
        if (param instanceof IntegerParameter) return "integer";
        if (param instanceof FloatParameter)   return "decimal";
        if (param instanceof EnumParameter)    return "enum";
        return null;
    }

    private static Set<String> safeValues(CommandParameter param) {
        try {
            return param.values();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Stage A.2 suggestion source for the parameter-value picker. Prefers
     * {@link CommandParameter#relevantValues(UUID)} (the same set that feeds
     * tab-completion and is already filtered by
     * {@code isSuggestionRelevant(senderId, value)}); falls back to
     * {@link CommandParameter#values()} if the relevance hook throws. Returns
     * {@code null} only on a thrown exception from both calls — an empty set
     * is returned literally so the caller can branch on emptiness alone.
     */
    private static Set<String> safeSuggestions(CommandParameter param, UUID senderId) {
        try {
            Set<String> r = param.relevantValues(senderId);
            if (r != null) return r;
        } catch (RuntimeException ignored) {
            // fall through to values()
        }
        return safeValues(param);
    }

    /**
     * Stage A.2: build a parameter-value picker sub-page for {@code paramName}
     * declared on {@code parent} (the {@link TreeCommand} reached by
     * {@code parentPath} under {@code /rtp}).
     *
     * <p>Layout:
     * <ol>
     *   <li><b>Back row</b> — {@code OpenMenu(parentPath)} so the player can
     *       return to the parent command page.</li>
     *   <li><b>Header row</b> — non-clickable label from {@code messages.yml}
     *       key {@code menuPickValue} with the parameter name and assembled
     *       command interpolated in. Acts as breadcrumb / orientation.</li>
     *   <li><b>"Type a value" fallback</b> — {@link MenuAction.SuggestInput}
     *       prefill, lets the player enter values outside the suggestion list.</li>
     *   <li><b>Value rows</b> — one {@link MenuAction.RunRtpCommand} per
     *       suggestion from {@link CommandParameter#relevantValues(UUID)},
     *       each carrying the assembled command tail with
     *       {@code paramName:value} appended.</li>
     * </ol>
     *
     * <p>If the parameter doesn't exist on {@code parent} or has no
     * suggestions, the page contains just Back + a header row indicating
     * the empty state — the caller (MenuRedeemSubcommand) is expected to
     * have already validated reachability, so this branch is defensive only.
     */
    public MenuModel buildParamPicker(TreeCommand parent,
                                      UUID callerId,
                                      Predicate<String> permission,
                                      MenuConsumerProfile profile,
                                      List<String> parentPath,
                                      String paramName) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(parentPath, "parentPath");
        Objects.requireNonNull(paramName, "paramName");

        // Back row → re-open the parent command page.
        String backLabel = lookupMsg(MessagesKeys.menuBack, "« back");
        MenuLine backLine = MenuLine.of(new MenuFragment(backLabel, null,
                new MenuAction.OpenMenu(parentPath.toArray(new String[0]))));

        // Header row (non-clickable, no action — orientation only).
        String assembledStr = String.join(" ", parentPath);
        String headerTmpl = lookupMsg(MessagesKeys.menuPickValue,
                "pick a value for [param] (will run: /rtp [command] [param]:<value>)");
        String headerLabel = headerTmpl
                .replace("[param]", paramName)
                .replace("[command]", assembledStr);
        MenuLine headerLine = MenuLine.of(new MenuFragment(headerLabel, null, null));

        // Resolve the parameter on the parent node. commands-api stores the
        // parameter lookup keyed by upper-case name (see TreeCommand wiring).
        Map<String, CommandParameter> paramLookup = parent.getParameterLookup();
        CommandParameter param = null;
        if (paramLookup != null) {
            param = paramLookup.get(paramName);
            if (param == null) {
                param = paramLookup.get(paramName.toUpperCase(java.util.Locale.ROOT));
            }
        }

        // "Type a value..." fallback — chat-prefill, lets the player enter
        // free-form values (numeric ranges, custom strings) without leaving
        // the menu flow.
        Deque<String> chatPath = new ArrayDeque<>();
        // suggestPrefix expects the path *including* an implicit leading
        // command label; mirror what build() does (root.name() at the head).
        chatPath.addLast(parent.name() == null ? "" : parent.name());
        // The actual node's name is already the last segment of parentPath,
        // so suggestPrefix will produce "/rtp <parentPath...> <paramName>:".
        String typePrefix = profile.suggestPrefix(chatPath, paramName);
        // Compose with the upstream args so the prefill includes the assembled
        // command, not just the leaf node. profile.suggestPrefix is expected
        // to emit the full "/rtp ... <paramName>:" form when given the full
        // chat path; if our parent context drops upstream segments we have to
        // splice them in. We delegate to the profile but augment when needed.
        if (!assembledStr.isEmpty() && !typePrefix.contains(assembledStr)) {
            // Fallback composition: parentPath is the full path from /rtp; the
            // profile didn't carry it. Build the canonical prefill ourselves.
            typePrefix = "/rtp " + assembledStr + " " + paramName + ":";
        }
        String typeLabel = lookupMsg(MessagesKeys.menuTypeValue,
                "✎ type a custom value...");
        MenuLine typeLine = MenuLine.of(new MenuFragment(typeLabel, null,
                new MenuAction.SuggestInput(typePrefix)));

        // Build value rows — Stage A.3: clicking a suggested value *stages*
        // the assignment into the assembled path (re-opens the parent command
        // page with `paramName:value` appended) rather than executing
        // immediately. Execution is then explicit via the parent page's
        // Execute row. This allows multi-parameter compose statelessly:
        // each subsequent picker click appends another `name:value` segment.
        List<MenuLine> valueLines = new ArrayList<>();
        if (param != null) {
            Set<String> suggestions = safeSuggestions(param, callerId);
            if (suggestions != null) {
                // Sort for stable ordering (Set may be unordered).
                List<String> sorted = new ArrayList<>(suggestions);
                Collections.sort(sorted);
                for (String value : sorted) {
                    if (value == null) continue;
                    String[] openArgs = new String[parentPath.size() + 1];
                    for (int i = 0; i < parentPath.size(); i++) {
                        openArgs[i] = parentPath.get(i);
                    }
                    openArgs[parentPath.size()] = paramName + ":" + value;
                    valueLines.add(MenuLine.of(new MenuFragment(value, null,
                            new MenuAction.OpenMenu(openArgs))));
                }
            }
        }

        // Stage A.6 — paginate value rows. The Adventure Book renderer caps
        // visible lines per page (~14 typical); large suggestion sets such
        // as biome names overflow a single page and the tail would be
        // invisible. Slice into chunks of {@code PICKER_VALUES_PER_PAGE},
        // repeating back+header+type on every page so navigation works from
        // any page, and append prev/next ChangePage rows where applicable.
        // Single-page case (suggestions <= cap) preserves the original
        // 3-row scaffold + N value rows layout, so existing tests / book
        // renderings are unchanged.
        final int valuesPerPage = PICKER_VALUES_PER_PAGE;
        int totalPages = valueLines.isEmpty()
                ? 1
                : (valueLines.size() + valuesPerPage - 1) / valuesPerPage;
        List<MenuPage> pages = new ArrayList<>(totalPages);
        String prevTmpl = lookupMsg(MessagesKeys.menuPagePrev, "« previous page ([page])");
        String nextTmpl = lookupMsg(MessagesKeys.menuPageNext, "next page ([page]) »");
        for (int p = 0; p < totalPages; p++) {
            List<MenuLine> pageLines = new ArrayList<>();
            pageLines.add(backLine);
            pageLines.add(headerLine);
            pageLines.add(typeLine);
            int from = p * valuesPerPage;
            int to = Math.min(from + valuesPerPage, valueLines.size());
            for (int i = from; i < to; i++) {
                pageLines.add(valueLines.get(i));
            }
            if (p > 0) {
                String prevLabel = prevTmpl.replace("[page]", Integer.toString(p));
                pageLines.add(MenuLine.of(new MenuFragment(prevLabel, null,
                        new MenuAction.ChangePage(p - 1))));
            }
            if (p < totalPages - 1) {
                String nextLabel = nextTmpl.replace("[page]", Integer.toString(p + 2));
                pageLines.add(MenuLine.of(new MenuFragment(nextLabel, null,
                        new MenuAction.ChangePage(p + 1))));
            }
            pages.add(new MenuPage(pageLines));
        }

        // Mint tokens for every clickable fragment (same contract as build()).
        // Each page repeats back/type rows, so each repeat mints its own
        // fresh token — ChangePage clicks are renderer-resolved and don't
        // need a server token, but the renderer still mints one uniformly
        // (matches build() and the existing ChangePage handling in tests).
        for (MenuPage page : pages) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment fragment : line.fragments()) {
                    MenuAction action = fragment.action();
                    if (action != null) {
                        tokenRegistry.mint(callerId, action, tokenTtl);
                    }
                }
            }
        }

        String title = (parent.name() == null ? "" : parent.name()) + ":" + paramName;
        return new MenuModel(title, pages);
    }

    private static String lookupMsg(MessagesKeys key, String fallback) {
        if (RTP.configs == null) return fallback;
        ConfigParser<MessagesKeys> lang =
                (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        if (lang == null) return fallback;
        Object v = lang.getConfigValue(key, fallback);
        return v == null ? fallback : v.toString();
    }
}
