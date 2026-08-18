package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.EnumParameter;
import io.github.dailystruggle.commandsapi.common.parameters.FloatParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
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
 * Reflects {@link TreeCommand} nodes into plain-text {@link MenuModel}s for
 * rendering by {@code MenuRenderer}. Resolves hover text from {@link YamlCommentLookup},
 * parameter types/bounds, or command descriptions.
 */
public final class CommandTreeMenuBuilder {

    /**
     * Number of suggestion value rows per parameter-value picker page before
     * overflow rows are sliced onto subsequent {@link MenuPage}s. Fits within ~14 lines.
     */
    public static final int PICKER_VALUES_PER_PAGE = 10;

    /**
     * No-arg constructor. The renderer emits concrete {@code /rtp menu ...}
     * commands, so no token registry or TTL is consulted.
     */
    public CommandTreeMenuBuilder() {
    }

    /**
     * Back-compatible 4-arg overload. Delegates to 5-arg build with empty path.
     */
    public MenuModel build(TreeCommand root,
                           UUID callerId,
                           Predicate<String> permission,
                           MenuConsumerProfile profile) {
        return build(root, callerId, permission, profile, Collections.emptyList());
    }

    /**
     * Reflects {@code root} for {@code callerId} into a single-page {@link MenuModel}.
     * Emits back/execute rows if {@code assembledPath} is non-empty.
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

        List<MenuLine> lines = new ArrayList<>();

        // 0root. Root-page UX framing - non-clickable title + hint rows
        //    prepended only on the root /rtp menu page (assembledPath empty)
        //    so the menu has visible framing before the player descends
        //    into any subcommand. REQ-RTP-F-013.
        if (assembledPath.isEmpty()) {
            String title = lookupMsg(CommandMessages.menuRootTitle, "&6&l⚡ RTP menu");
            if (title != null && !title.isEmpty()) {
                lines.add(MenuLine.of(new MenuFragment(title, null, null)));
            }
            String hint = lookupMsg(CommandMessages.menuRootHint,
                    "&7click an option below to begin");
            if (hint != null && !hint.isEmpty()) {
                lines.add(MenuLine.of(new MenuFragment(hint, null, null)));
            }
        }

        // 0. Constructed-command header - non-clickable breadcrumb showing
        //    the full /rtp invocation currently being assembled, including
        //    any staged `name:value` parameter assignments riding in
        //    assembledPath. Prepended only on non-root pages (the root /rtp
        //    menu page has nothing to show). REQ-RTP-F-013.
        if (!assembledPath.isEmpty()) {
            String headerTmpl = lookupMsg(CommandMessages.menuConstructed,
                    "building: /rtp [command]");
            String headerLabel = headerTmpl
                    .replace("[command]", String.join(" ", assembledPath));
            lines.add(MenuLine.of(new MenuFragment(headerLabel, null, null)));
        }

        // 0a. Back row - only for non-root pages.
        if (!assembledPath.isEmpty()) {
            String[] parentPath = assembledPath.subList(0, assembledPath.size() - 1)
                    .toArray(new String[0]);
            String backLabel = lookupMsg(CommandMessages.menuBack, "« back");
            lines.add(MenuLine.of(new MenuFragment(backLabel, null,
                    new MenuAction.OpenMenu(parentPath))));
        }

        // 0b. Execute row - only for non-root pages (root /rtp menu has no
        //     useful assembled command to execute; it would just re-open the
        //     menu page we're already on).
        if (!assembledPath.isEmpty()) {
            String[] runArgs = assembledPath.toArray(new String[0]);
            String tmpl = lookupMsg(CommandMessages.menuExecute, "▶ run /rtp [command]");
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
                // the same tree - a clickable `help` would dispatch a plaintext
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
                String hover = sub.description();
                if (hover != null && hover.isEmpty()) hover = null;

                // Args for the click action target the full /rtp invocation:
                // assembledPath (e.g. ["config", "regions", "default.yml"]) plus
                // this sub's name (e.g. "view"). `commandPath` excludes the
                // assembledPath prefix, so using it for RunRtpCommand would
                // drop everything above the current page (issue: clicking
                // `view` under `config regions default.yml` dispatched
                // `/rtp view` and produced "invalid command: view").
                String[] childPath = new String[assembledPath.size() + 1];
                for (int i = 0; i < assembledPath.size(); i++) {
                    childPath[i] = assembledPath.get(i);
                }
                childPath[assembledPath.size()] = name;

                MenuAction action;
                if (sub instanceof TreeCommand subTree && hasNavigableContent(subTree, permission)) {
                    // Descend: clicking re-opens the menu at the subtree.
                    action = new MenuAction.OpenMenu(childPath);
                } else {
                    // Pure leaf: clicking executes the subcommand directly.
                    action = new MenuAction.RunRtpCommand(childPath);
                }
                lines.add(MenuLine.of(new MenuFragment(name, hover, action)));
            }
        }

        // 2. Parameter rows. When the parameter exposes any
        //    suggestions (via relevantValues(callerId) - the same source that
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
                // Always open the picker sub-page: it carries both a
                // "✎ type a custom value..." chat-prefill row and any
                // suggestion rows. Parameters with no suggestions (free-form
                // string inputs like `regions add`) still reach a clear
                // prompt rather than a silent SuggestInput click.
                action = new MenuAction.OpenParamPicker(parentPath, name);
                String hover = resolveParamHover(profile, root, name, param);
                lines.add(MenuLine.of(new MenuFragment(name, hover, action)));
            }
        }

        // Renderer emits concrete /rtp menu ... commands; no token is consulted.

        String title = root.name() == null ? "" : root.name();
        return new MenuModel(title, List.of(new MenuPage(lines)));
    }

    /**
     * "Navigable content" predicate: a sub-{@link TreeCommand} is
     * treated as menu-navigable (clicking it opens its own page) when, after
     * excluding {@code help} and {@code menu}, it exposes at least one visible
     * sub-command <em>or</em> at least one visible parameter under the caller's
     * permission view. Otherwise it is a pure leaf - clicking it executes the
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
                String tmpl = lookupMsg(CommandMessages.menuHoverFallbackBounds,
                        "[type]: [values]");
                return tmpl.replace("[type]", typeName)
                        .replace("[values]", String.join(", ", values));
            }
            String tmpl = lookupMsg(CommandMessages.menuHoverFallbackType, "[type]");
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
     * Suggestion source for parameter-value picker. Prefers
     * {@link CommandParameter#relevantValues(UUID)}, falling back to
     * {@link CommandParameter#values()} if relevance check throws.
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
     * Builds parameter-value picker sub-page for {@code paramName} on {@code parent}.
     * Emits back, header, optional anvil/type prompt, and paginated suggestion rows.
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
        String backLabel = lookupMsg(CommandMessages.menuBack, "« back");
        MenuLine backLine = MenuLine.of(new MenuFragment(backLabel, null,
                new MenuAction.OpenMenu(parentPath.toArray(new String[0]))));

        // Header row (non-clickable, no action - orientation only).
        String assembledStr = String.join(" ", parentPath);
        String headerTmpl = lookupMsg(CommandMessages.menuPickValue,
                "pick a value for [param] (will run: /rtp [command] [param]:<value>)");
        String headerLabel = headerTmpl
                .replace("[param]", paramName)
                .replace("[command]", assembledStr);
        MenuLine headerLine = MenuLine.of(new MenuFragment(headerLabel, null, null));

        // Resolve the parameter on the parent node. commands-api's
        // TreeCommand#addParameter stores under the lowercased name (see
        // localCommands/TreeCommand.java line 26), but historic callers
        // have used both raw and upper-case keys; try all three so a
        // mixed-case enum-derived paramName (e.g. "teleportDelay" from
        // ConfigKeys.teleportDelay.name()) resolves correctly.
        Map<String, CommandParameter> paramLookup = parent.getParameterLookup();
        CommandParameter param = null;
        if (paramLookup != null) {
            param = paramLookup.get(paramName);
            if (param == null) {
                param = paramLookup.get(paramName.toLowerCase(java.util.Locale.ROOT));
            }
            if (param == null) {
                param = paramLookup.get(paramName.toUpperCase(java.util.Locale.ROOT));
            }
        }

        // "Type a value..." fallback - chat-prefill, lets the player enter
        // free-form values (numeric ranges, custom strings) without leaving
        // the menu flow.
        Deque<String> chatPath = new ArrayDeque<>();
        // suggestPrefix expects the path *including* an implicit leading
        // command label; mirror what build() does (root.name() at the head).
        chatPath.addLast(parent.name() == null ? "" : parent.name());
        // The actual node's name is already the last segment of parentPath,
        // so suggestPrefix will produce "/rtp <parentPath...> <paramName>=".
        String typePrefix = profile.suggestPrefix(chatPath, paramName);
        // Compose with the upstream args so the prefill includes the assembled
        // command, not just the leaf node. profile.suggestPrefix is expected
        // to emit the full "/rtp ... <paramName>=" form when given the full
        // chat path; if our parent context drops upstream segments we have to
        // splice them in. We delegate to the profile but augment when needed.
        if (!assembledStr.isEmpty() && !typePrefix.contains(assembledStr)) {
            // Fallback composition: parentPath is the full path from /rtp; the
            // profile didn't carry it. Build the canonical prefill ourselves.
            // '=' separator matches commands-api parameter parsing.
            typePrefix = "/rtp " + assembledStr + " " + paramName + "=";
        }
        String typeLabel = lookupMsg(CommandMessages.menuTypeValue,
                "✎ type a custom value...");
        // ADR-045 - on renderers that support it (Paper/Folia BookMenuRenderer),
        // this row opens an anvil GUI; the renderer mints a token bound to a
        // sibling subcommand which opens the anvil server-side and submits
        // `/rtp <parentPath...> <paramName>=<typed>` on confirm. Renderers
        // that don't support anvil input fall back to chat-prefill semantics
        // by re-rendering this action as a `SuggestInput(typePrefix)` click.
        String[] parentPathArr = parentPath.toArray(new String[0]);
        // When the picker is rendered from a /rtp config <file> context, mint
        // the anvil prompt in STAGE mode so anvil-confirm pushes (file, key,
        // value) into the per-player staging cart and reopens /rtp config
        // <file> instead of running the single assignment immediately.
        // Non-config contexts (regular command param pickers) keep the RUN
        // behavior via the 3-arg constructor.
        MenuAction.Mode promptMode =
                (!parentPath.isEmpty()
                        && "config".equalsIgnoreCase(parentPath.get(0)))
                        ? MenuAction.Mode.STAGE
                        : MenuAction.Mode.RUN;
        MenuLine typeLine = MenuLine.of(new MenuFragment(typeLabel, null,
                new MenuAction.PromptAnvilInput(parentPathArr, paramName, "", promptMode)));

        // Omit custom "type a value..." row for closed enumerable sets (regions/worlds/prefabs).
        boolean prefabIdPicker =
                "id".equalsIgnoreCase(paramName)
                        && parentPath.stream().anyMatch("prefab"::equalsIgnoreCase);
        boolean enumerableDestinationPicker =
                "region".equalsIgnoreCase(paramName)
                        || "world".equalsIgnoreCase(paramName)
                        || prefabIdPicker;

        // Build value rows: clicking a suggested value *stages*
        // the assignment into the assembled path (re-opens the parent command
        // page with `paramName=value` appended) rather than executing
        // immediately. Execution is then explicit via the parent page's
        // Execute row. This allows multi-parameter compose statelessly:
        // each subsequent picker click appends another `name=value` segment.
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
                    openArgs[parentPath.size()] = paramName + "=" + value;
                    // Contrast & tint (ADR-063): use parchment-safe color prefixes (&2 or tinted).
                    String colorPrefix;
                    if ("biome".equalsIgnoreCase(paramName)) {
                        colorPrefix = MenuColor.biomeColorPrefix(value);
                    } else if ("world".equalsIgnoreCase(paramName)) {
                        colorPrefix = MenuColor.worldColorPrefix(value);
                    } else if ("region".equalsIgnoreCase(paramName)) {
                        colorPrefix = MenuColor.regionColorPrefix(value);
                    } else {
                        colorPrefix = "&2";
                    }
                    valueLines.add(MenuLine.of(new MenuFragment(colorPrefix + value, null,
                            new MenuAction.OpenMenu(openArgs))));
                }
            }
        }

        // Paginate value rows across pages with prev/next navigation (fits ~14 lines/page).
        final int valuesPerPage = PICKER_VALUES_PER_PAGE;
        int totalPages = valueLines.isEmpty()
                ? 1
                : (valueLines.size() + valuesPerPage - 1) / valuesPerPage;
        List<MenuPage> pages = new ArrayList<>(totalPages);
        String prevTmpl = lookupMsg(CommandMessages.menuPagePrev, "« previous page ([page])");
        String nextTmpl = lookupMsg(CommandMessages.menuPageNext, "next page ([page]) »");
        for (int p = 0; p < totalPages; p++) {
            List<MenuLine> pageLines = new ArrayList<>();
            pageLines.add(backLine);
            pageLines.add(headerLine);
            if (!enumerableDestinationPicker) {
                pageLines.add(typeLine);
            }
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
        // fresh token - ChangePage clicks are renderer-resolved and don't
        // need a server token, but the renderer still mints one uniformly
        // (matches build() and the existing ChangePage handling in tests).

        String title = (parent.name() == null ? "" : parent.name()) + ":" + paramName;
        return new MenuModel(title, pages);
    }

    /**
     * Builds curated config-selector page listing config files in encounter order.
     *
     * @param callerId  UUID of the viewing player
     * @param fileNames ordered list of config file names to display
     * @return the assembled {@link MenuModel}
     */
    public MenuModel buildConfigSelector(UUID callerId, List<String> fileNames) {
        return buildConfigSelector(callerId, "", Collections.emptyList(), fileNames);
    }

    /**
     * Builds recursive config-directory selector page (ADR-071 rule 7).
     * Lists child directories and member files with Back navigation.
     */
    public MenuModel buildConfigSelector(UUID callerId, String subDir,
                                         List<String> childDirs, List<String> fileNames) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(fileNames, "fileNames");
        if (subDir == null) subDir = "";
        if (childDirs == null) childDirs = Collections.emptyList();
        boolean root = subDir.isEmpty();

        List<MenuLine> lines = new ArrayList<>();

        // Back row. At root → /rtp menu root page (empty path); in a nested
        // directory → up one level to the parent directory selector.
        String backLabel = lookupMsg(CommandMessages.menuBack, "« back");
        MenuAction backAction = root
                ? new MenuAction.OpenMenu(new String[0])
                : new MenuAction.OpenConfigSelector(parentDir(subDir));
        lines.add(MenuLine.of(new MenuFragment(backLabel, null, backAction)));

        if (root) {
            // Search row - opens an anvil-input prompt for a cross-config
            // substring search. Only meaningful at the root since search spans all configs.
            lines.add(MenuLine.of(new MenuFragment("&b&l⚲ search configs", null,
                    new MenuAction.OpenConfigSearchPrompt())));
        }

        // Header - non-clickable orientation row.
        String headerText = root ? "&1&lconfig files" : "&1&l" + subDir + "/";
        lines.add(MenuLine.of(new MenuFragment(headerText, null, null)));

        if (root) {
            // Regions / Worlds / Effects submenu entry points.
            // These per-kind MultiConfigParser selectors are
            // directory nodes in the recursive walk (ADR-071 rule 7) and are
            // handled by MultiConfigMenuBuilder.
            String regionsLabel = lookupMsg(
                    CommandMessages.menuAdminPanelRowRegions, "&b\u2699 Regions");
            String regionsHover = lookupMsg(
                    CommandMessages.menuAdminPanelHoverRegions,
                    "Add, remove, or edit per-region configs.");
            lines.add(MenuLine.of(new MenuFragment(regionsLabel, regionsHover,
                    new MenuAction.OpenMultiConfigSelector("regions"))));
            String worldsLabel = lookupMsg(
                    CommandMessages.menuAdminPanelRowWorlds, "&b\u2699 Worlds");
            String worldsHover = lookupMsg(
                    CommandMessages.menuAdminPanelHoverWorlds,
                    "Add, remove, or edit per-world configs.");
            lines.add(MenuLine.of(new MenuFragment(worldsLabel, worldsHover,
                    new MenuAction.OpenMultiConfigSelector("worlds"))));
            String effectsLabel = lookupMsg(
                    CommandMessages.menuAdminPanelRowEffects, "&b\u2699 Effects");
            String effectsHover = lookupMsg(
                    CommandMessages.menuAdminPanelHoverEffects,
                    "Add, remove, or edit per-group teleport effects.");
            lines.add(MenuLine.of(new MenuFragment(effectsLabel, effectsHover,
                    new MenuAction.OpenMultiConfigSelector("effects"))));
        }

        // Child-directory folder nodes (e.g. advanced/, messages/). Selecting
        // one recurses into that directory via a deeper OpenConfigSelector.
        for (String child : childDirs) {
            if (child == null || child.isEmpty()) continue;
            String childPath = root ? child : subDir + "/" + child;
            String hover = "open " + child + "/";
            lines.add(MenuLine.of(new MenuFragment("&3\u2699 " + child + "/", hover,
                    new MenuAction.OpenConfigSelector(childPath))));
        }

        // One row per config file directly in this directory. Hover surfaces an
        // "edit <file>" affordance hint.
        for (String fileName : fileNames) {
            if (fileName == null || fileName.isEmpty()) continue;
            String hover = "edit " + fileName;
            lines.add(MenuLine.of(new MenuFragment("&2" + fileName, hover,
                    new MenuAction.OpenConfigFile(fileName))));
        }

        MenuPage page = new MenuPage(lines);
        return new MenuModel(root ? "config" : subDir, List.of(page));
    }

    /**
     * Parent directory of a forward-slashed relative path, or {@code ""} when
     * {@code subDir} has no parent (a top-level directory under the config root).
     */
    private static String parentDir(String subDir) {
        if (subDir == null) return "";
        int slash = subDir.lastIndexOf('/');
        return slash <= 0 ? "" : subDir.substring(0, slash);
    }

    /**
     * Builds per-file config page for {@code parser}'s enum keys.
     */
    public <E extends Enum<E>> MenuModel buildConfigFile(UUID callerId,
                                                         String fileName,
                                                         ConfigParser<E> parser) {
        return buildConfigFile(callerId, fileName, parser, new java.util.LinkedHashMap<>());
    }

    /**
     * Cart-aware overload. Surfaces staged changes under pending section.
     */
    public <E extends Enum<E>> MenuModel buildConfigFile(UUID callerId,
                                                         String fileName,
                                                         ConfigParser<E> parser,
                                                         java.util.LinkedHashMap<String, String> cartSnapshot) {
        return buildConfigFile(callerId, fileName, parser, cartSnapshot, "");
    }

    /**
     * Directory-aware overload. Scopes Back row to {@code backSubDir}.
     */
    public <E extends Enum<E>> MenuModel buildConfigFile(UUID callerId,
                                                         String fileName,
                                                         ConfigParser<E> parser,
                                                         java.util.LinkedHashMap<String, String> cartSnapshot,
                                                         String backSubDir) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(cartSnapshot, "cartSnapshot");
        if (fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName must not be empty");
        }

        // Back row → selector page. Encoded as an OpenConfigSelector action
        // (not OpenMenu) so the redeem path re-renders the curated selector
        // rather than reflecting an arbitrary command tree node. The selector
        // is scoped to the file's own directory so Back returns to the folder
        // listing the file was opened from, not the root config menu.
        String backLabel = lookupMsg(CommandMessages.menuBack, "« back");
        MenuAction backAction = (backSubDir == null || backSubDir.isEmpty())
                ? new MenuAction.OpenConfigSelector()
                : new MenuAction.OpenConfigSelector(backSubDir);
        MenuLine backRow = MenuLine.of(new MenuFragment(backLabel, null,
                backAction));
        // Header - English fallback only.
        MenuLine headerRow = MenuLine.of(new MenuFragment("&1&l" + fileName, null, null));

        // Source of truth for visible keys is the loaded parser data, NOT the
        // raw enum declaration. The two diverge whenever a packaging variant
        // omits a key from its shipped YAML on purpose - the in-code default
        // still works at runtime, but admins are not meant to discover or edit
        // an omitted knob through the menu. Iterating `myClass.getEnumConstants()` would re-expose every
        // such key just because it exists in the Java enum, which is the bug
        // this branch fixes. Enum declaration order is preserved by walking
        // the constants and gating on `data.containsKey`.
        E[] enumValues = parser.myClass.getEnumConstants();
        java.util.EnumMap<E, Object> loaded = parser.getData();
        // Keys present in cartSnapshot
        // move from the Changeable list into the Pending list, so the same
        // key never renders twice on the page. cartSnapshot keys are matched
        // case-insensitively against enum names (the cart stores raw param
        // names as typed/dispatched, which the AnvilInputSession lifts from
        // the live CommandParameter -- they match enum names exactly in
        // practice, but tolerate case drift defensively).
        java.util.Set<String> stagedKeys = new java.util.HashSet<>();
        for (String k : cartSnapshot.keySet()) {
            if (k != null) stagedKeys.add(k.toUpperCase(java.util.Locale.ROOT));
        }
        List<E> visibleKeys = new ArrayList<>();
        if (enumValues != null) {
            for (E key : enumValues) {
                if (key == null) continue;
                if (!loaded.containsKey(key)) continue;
                if (stagedKeys.contains(key.name().toUpperCase(java.util.Locale.ROOT))) continue;
                // `version` is a config-file schema marker (the value the
                // YAML loader stamps so future migrations can detect old
                // layouts). It is not a user-editable knob: surfacing it in
                // the staging cart would let an operator stage a bogus
                // version string and silently break the next reload. Filter
                // it out of the Changeable list (case-insensitive to cover
                // both `version` and `VERSION` enum spellings across files).
                if ("VERSION".equals(key.name().toUpperCase(java.util.Locale.ROOT))) continue;
                visibleKeys.add(key);
            }
        }
        boolean hasCart = !cartSnapshot.isEmpty();
        List<MenuPage> pages = new ArrayList<>();
        if (visibleKeys.isEmpty() && !hasCart) {
            List<MenuLine> lines = new ArrayList<>();
            lines.add(backRow);
            lines.add(headerRow);
            // v3.7.4 empty-file handling: header + hint + back already present.
            // Same hint applies whether the enum itself is empty or the YAML
            // simply did not configure any of the declared keys.
            lines.add(MenuLine.of(new MenuFragment(
                    "&7(no editable keys in this file)", null, null)));
            pages.add(new MenuPage(lines));
        } else {
            // Paginate by visual line budget (~14 lines/page) to prevent overflowing book pages.
            final int visualLinesPerPage = 14;
            // Back + header consume ~1 visual line each (short labels).
            int visualLinesUsed = predictVisualLines(backLabel)
                    + predictVisualLines(fileName);
            List<MenuLine> lines = new ArrayList<>();
            lines.add(backRow);
            lines.add(headerRow);
            // Render pending changes and apply row before changeable list.
            if (hasCart) {
                // Book parchment contrast: avoid yellow (&e/&6) and white
                // (&f) per .junie/AGENTS.md 'Book Menu Color Contrast'.
                // Header shortened to "pending" so the &l-bolded label fits
                // on a single book line (the prior "-- pending changes --"
                // wrapped to two lines on the parchment width).
                String pendingHeader = lookupMsg(CommandMessages.configPendingHeader,
                        "&1&l-- pending --");
                String pendingRowTmpl = lookupMsg(CommandMessages.configPendingRowFormat,
                        "&9[key]&8 = &0[value]");
                String pendingRowHover = lookupMsg(CommandMessages.configPendingRowHover,
                        "click to unstage");
                String applyLabel = lookupMsg(CommandMessages.configApplyRow, "&2&l[apply]");
                // Discard row intentionally removed (2026-05-22 per user
                // request): clicking any pending row already unstages that
                // entry, and the Back row leaves the page without applying -
                // a dedicated Discard button is redundant and noisy.
                lines.add(MenuLine.of(new MenuFragment(pendingHeader, null, null)));
                // Iterate in reverse insertion order so the freshest entry
                // is the first row under the Pending header. cartSnapshot
                // is a LinkedHashMap; capture its entries into a list and
                // walk it tail-first.
                List<Map.Entry<String, String>> entries =
                        new ArrayList<>(cartSnapshot.entrySet());
                for (int i = entries.size() - 1; i >= 0; i--) {
                    Map.Entry<String, String> e = entries.get(i);
                    String label = pendingRowTmpl
                            .replace("[key]", e.getKey())
                            .replace("[value]", e.getValue());
                    // Hover carries the "click to unstage" hint so the row
                    // label stays compact (single-line) and clean.
                    lines.add(MenuLine.of(new MenuFragment(label, pendingRowHover,
                            new MenuAction.UnstageConfigValue(fileName, e.getKey()))));
                    visualLinesUsed += predictVisualLines(label);
                    if (visualLinesUsed >= visualLinesPerPage) {
                        pages.add(new MenuPage(lines));
                        lines = new ArrayList<>();
                        lines.add(backRow);
                        lines.add(headerRow);
                        visualLinesUsed = predictVisualLines(backLabel)
                                + predictVisualLines(fileName);
                    }
                }
                lines.add(MenuLine.of(new MenuFragment(
                        applyLabel, null, new MenuAction.ApplyStagedConfig(fileName))));
                visualLinesUsed += predictVisualLines(applyLabel);
                if (visualLinesUsed >= visualLinesPerPage) {
                    pages.add(new MenuPage(lines));
                    lines = new ArrayList<>();
                    lines.add(backRow);
                    lines.add(headerRow);
                    visualLinesUsed = predictVisualLines(backLabel)
                            + predictVisualLines(fileName);
                }
            }
            // Hover text source: each key's YAML block comment (the
            // operator-facing documentation written above the key in the
            // shipped .yml). Resolved once from the parser's loaded YAML root
            // and attached to every editable row so hovering a config entry
            // surfaces its description. May be null when the file has not been
            // cached or the key carries no comment.
            io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection yamlRoot =
                    parser.getYamlRoot();
            for (E key : visibleKeys) {
                Object current = loaded.get(key);
                // ADR-073: resolve @<file> inheritance token to effective value for display.
                boolean inheritedRef = io.github.dailystruggle.rtp.common.configuration
                        .ConfigDefaultResolver.isReference(current);
                if (inheritedRef) {
                    current = io.github.dailystruggle.rtp.common.configuration
                            .ConfigDefaultResolver.resolve(current, key.name(), current);
                }
                // Flatten nested RtpYamlSection, Map, or FactoryValue into dotted leaf rows.
                java.util.List<String[]> flattened = flattenNestedConfigValue(current);
                if (flattened != null) {
                    for (String[] kv : flattened) {
                        String dottedKey = key.name() + "." + kv[0];
                        String nestedLabel = "&2" + dottedKey
                                + "&7: &0" + kv[1];
                        String nestedHover = resolveConfigHover(yamlRoot, dottedKey);
                        lines.add(MenuLine.of(new MenuFragment(nestedLabel, nestedHover,
                                new MenuAction.OpenConfigKey(fileName, dottedKey))));
                        visualLinesUsed += predictVisualLines(nestedLabel);
                        if (visualLinesUsed >= visualLinesPerPage) {
                            pages.add(new MenuPage(lines));
                            lines = new ArrayList<>();
                            lines.add(backRow);
                            lines.add(headerRow);
                            visualLinesUsed = predictVisualLines(backLabel)
                                    + predictVisualLines(fileName);
                        }
                    }
                    continue;
                }
                String currentStr = current == null
                        ? "&8(unset)"
                        : String.valueOf(current);
                // Mark a still-inherited scalar so the operator understands
                // the shown value comes from the global default rather than
                // being written literally in this file.
                if (inheritedRef && current != null) currentStr = currentStr + " &8(inherited)";
                String label = "&2" + key.name() + "&7: &0" + currentStr;
                String hover = resolveConfigHover(yamlRoot, key.name());
                lines.add(MenuLine.of(new MenuFragment(label, hover,
                        new MenuAction.OpenConfigKey(fileName, key.name()))));
                visualLinesUsed += predictVisualLines(label);
                if (visualLinesUsed >= visualLinesPerPage) {
                    pages.add(new MenuPage(lines));
                    lines = new ArrayList<>();
                    lines.add(backRow);
                    lines.add(headerRow);
                    visualLinesUsed = predictVisualLines(backLabel)
                            + predictVisualLines(fileName);
                }
            }
            if (lines.size() > 2) {
                pages.add(new MenuPage(lines));
            }
        }

        return new MenuModel("config:" + fileName, pages);
    }

    /**
     * Builds finite-value picker page for declared options or source directives (ADR-064).
     */
    public MenuModel buildOptionsPicker(UUID callerId,
                                        String fileName,
                                        String paramName,
                                        String currentValue,
                                        List<String> options) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(paramName, "paramName");
        Objects.requireNonNull(options, "options");
        if (fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName must not be empty");
        }
        if (paramName.isEmpty()) {
            throw new IllegalArgumentException("paramName must not be empty");
        }
        if (options.isEmpty()) {
            throw new IllegalArgumentException("options must not be empty");
        }

        final int visualLinesPerPage = 14;
        String backLabel = lookupMsg(CommandMessages.menuBack, "« back");
        MenuLine backRow = MenuLine.of(new MenuFragment(backLabel, null,
                new MenuAction.OpenConfigFile(fileName)));
        String currentLabel = (currentValue == null || currentValue.isEmpty())
                ? "&8(unset)" : "&0" + currentValue;
        MenuLine headerRow = MenuLine.of(new MenuFragment(
                "&1&l" + paramName + " &7(current: " + currentLabel + "&7)", null, null));

        List<MenuPage> pages = new ArrayList<>();
        List<MenuLine> lines = new ArrayList<>();
        lines.add(backRow);
        lines.add(headerRow);
        int visualLinesUsed = predictVisualLines(backLabel) + predictVisualLines(paramName);
        for (String option : options) {
            if (option == null || option.isEmpty()) continue;
            boolean isCurrent = currentValue != null && option.equalsIgnoreCase(currentValue);
            String marker = isCurrent ? "&2&l* " : "&2";
            String label = marker + option;
            lines.add(MenuLine.of(new MenuFragment(label, null,
                    new MenuAction.StageConfigValue(fileName, paramName, option))));
            visualLinesUsed += predictVisualLines(label);
            if (visualLinesUsed >= visualLinesPerPage) {
                pages.add(new MenuPage(lines));
                lines = new ArrayList<>();
                lines.add(backRow);
                lines.add(headerRow);
                visualLinesUsed = predictVisualLines(backLabel) + predictVisualLines(paramName);
            }
        }
        if (lines.size() > 2 || pages.isEmpty()) {
            pages.add(new MenuPage(lines));
        }
        return new MenuModel("config:" + fileName + ":" + paramName + ":options", pages);
    }

    /**
     * Builds shape/vert type-picker page (page 3a).
     */
    public MenuModel buildShapeVertTypePicker(UUID callerId,
                                              String fileName,
                                              String paramName,
                                              String currentTypeName,
                                              List<String> typeNames,
                                              List<String> writeCommandPath) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(paramName, "paramName");
        Objects.requireNonNull(typeNames, "typeNames");
        Objects.requireNonNull(writeCommandPath, "writeCommandPath");
        if (fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName must not be empty");
        }
        if (paramName.isEmpty()) {
            throw new IllegalArgumentException("paramName must not be empty");
        }

        List<MenuLine> lines = new ArrayList<>();

        // Back row → per-file config page.
        String backLabel = lookupMsg(CommandMessages.menuBack, "« back");
        lines.add(MenuLine.of(new MenuFragment(backLabel, null,
                new MenuAction.OpenConfigFile(fileName))));

        // Header - English fallback only.
        String currentLabel = currentTypeName == null ? "&8(unset)" : "&0" + currentTypeName;
        lines.add(MenuLine.of(new MenuFragment(
                "&1&l" + paramName + " type &7(current: " + currentLabel + "&7)",
                null, null)));

        // One row per known type. Clicking writes name:<typeName> through the
        // reflected command tree (OpenMenu redeem path). `name` is
        // the canonical discriminator key for both shape and vert in the
        // existing SubConfigCmd grammar.
        for (String typeName : typeNames) {
            if (typeName == null || typeName.isEmpty()) continue;
            String[] writeArgs = new String[writeCommandPath.size() + 1];
            for (int i = 0; i < writeCommandPath.size(); i++) {
                writeArgs[i] = writeCommandPath.get(i);
            }
            writeArgs[writeCommandPath.size()] = "name:" + typeName;
            String marker = typeName.equalsIgnoreCase(currentTypeName) ? "&2&l* " : "&2";
            lines.add(MenuLine.of(new MenuFragment(marker + typeName, null,
                    new MenuAction.OpenMenu(writeArgs))));
        }

        MenuPage page = new MenuPage(lines);
        return new MenuModel("config:" + fileName + ":" + paramName + ":type", List.of(page));
    }

    /**
     * Builds shape/vert sub-parameter page (page 3b).
     */
    public MenuModel buildShapeVertSubParamPage(UUID callerId,
                                                String fileName,
                                                String paramName,
                                                String typeName,
                                                Map<String, ?> subParamValues,
                                                List<String> writeCommandPath) {
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(paramName, "paramName");
        Objects.requireNonNull(typeName, "typeName");
        Objects.requireNonNull(subParamValues, "subParamValues");
        Objects.requireNonNull(writeCommandPath, "writeCommandPath");
        if (fileName.isEmpty()) {
            throw new IllegalArgumentException("fileName must not be empty");
        }
        if (paramName.isEmpty()) {
            throw new IllegalArgumentException("paramName must not be empty");
        }
        if (typeName.isEmpty()) {
            throw new IllegalArgumentException("typeName must not be empty");
        }

        List<MenuLine> lines = new ArrayList<>();

        // Back row → re-open type picker (page 3a) via the OpenConfigKey
        // redeem, which dispatches back to the shape/vert subtree entry.
        String backLabel = lookupMsg(CommandMessages.menuBack, "« back");
        lines.add(MenuLine.of(new MenuFragment(backLabel, null,
                new MenuAction.OpenConfigKey(fileName, paramName))));

        // Header - English fallback only.
        lines.add(MenuLine.of(new MenuFragment(
                "&1&l" + paramName + " &7/ &0" + typeName, null, null)));

        if (subParamValues.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(
                    "&7(no sub-parameters for this type)", null, null)));
        } else {
            String[] parentPathArr = writeCommandPath.toArray(new String[0]);
            for (Map.Entry<String, ?> entry : subParamValues.entrySet()) {
                String subParamName = entry.getKey();
                if (subParamName == null || subParamName.isEmpty()) continue;
                Object current = entry.getValue();
                String currentStr = current == null
                        ? "&8(unset)"
                        : String.valueOf(current);
                String label = "&2" + subParamName + "&7: &0" + currentStr;
                lines.add(MenuLine.of(new MenuFragment(label, null,
                        new MenuAction.OpenParamPicker(parentPathArr, subParamName))));
            }
        }

        MenuPage page = new MenuPage(lines);
        return new MenuModel(
                "config:" + fileName + ":" + paramName + ":" + typeName,
                List.of(page));
    }

    /**
     * Predicts visual line count of a label on a book page (~19 displayable chars per line).
     * Strips legacy color codes and rounds up.
     */
    static int predictVisualLines(String raw) {
        if (raw == null || raw.isEmpty()) return 1;
        int visibleChars = 0;
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if ((c == '&' || c == '\u00a7') && i + 1 < n) {
                i += 2;
                continue;
            }
            visibleChars++;
            i++;
        }
        if (visibleChars == 0) return 1;
        final int charsPerLine = 19;
        return (visibleChars + charsPerLine - 1) / charsPerLine;
    }

    /**
     * Flattens nested {@link io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection},
     * {@link Map}, or {@link io.github.dailystruggle.rtp.common.factory.FactoryValue} into
     * dotted {@code [path, value]} leaf pairs.
     */
    private static java.util.List<String[]> flattenNestedConfigValue(Object value) {
        if (value == null) return null;
        boolean isSection = value instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
        boolean isMap = value instanceof Map;
        boolean isFactory = value instanceof io.github.dailystruggle.rtp.common.factory.FactoryValue;
        if (!isSection && !isMap && !isFactory) return null;
        java.util.List<String[]> out = new ArrayList<>();
        Map<String, Object> level = isSection
                ? ((io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection) value).getValues(false)
                : isFactory
                        ? factoryValueToMap((io.github.dailystruggle.rtp.common.factory.FactoryValue<?>) value)
                        : coerceMapKeysToString((Map<?, ?>) value);
        if (level == null || level.isEmpty()) return out;
        Deque<Object[]> stack = new ArrayDeque<>();
        java.util.List<Map.Entry<String, Object>> entries = new ArrayList<>(level.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            stack.push(new Object[]{entries.get(i).getKey(), entries.get(i).getValue()});
        }
        while (!stack.isEmpty()) {
            Object[] frame = stack.pop();
            String path = (String) frame[0];
            Object node = frame[1];
            if (node instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection) {
                Map<String, Object> children = ((io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection) node).getValues(false);
                if (children == null || children.isEmpty()) {
                    out.add(new String[]{path, "&8(empty)"});
                    continue;
                }
                java.util.List<Map.Entry<String, Object>> kids = new ArrayList<>(children.entrySet());
                for (int i = kids.size() - 1; i >= 0; i--) {
                    stack.push(new Object[]{path + "." + kids.get(i).getKey(), kids.get(i).getValue()});
                }
            } else if (node instanceof Map) {
                Map<String, Object> children = coerceMapKeysToString((Map<?, ?>) node);
                if (children.isEmpty()) {
                    out.add(new String[]{path, "&8(empty)"});
                    continue;
                }
                java.util.List<Map.Entry<String, Object>> kids = new ArrayList<>(children.entrySet());
                for (int i = kids.size() - 1; i >= 0; i--) {
                    stack.push(new Object[]{path + "." + kids.get(i).getKey(), kids.get(i).getValue()});
                }
            } else if (node instanceof io.github.dailystruggle.rtp.common.factory.FactoryValue) {
                Map<String, Object> children = factoryValueToMap(
                        (io.github.dailystruggle.rtp.common.factory.FactoryValue<?>) node);
                if (children.isEmpty()) {
                    out.add(new String[]{path, "&8(empty)"});
                    continue;
                }
                java.util.List<Map.Entry<String, Object>> kids = new ArrayList<>(children.entrySet());
                for (int i = kids.size() - 1; i >= 0; i--) {
                    stack.push(new Object[]{path + "." + kids.get(i).getKey(), kids.get(i).getValue()});
                }
            } else {
                out.add(new String[]{path, node == null ? "&8(unset)" : String.valueOf(node)});
            }
        }
        return out;
    }

    private static Map<String, Object> coerceMapKeysToString(Map<?, ?> in) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> e : in.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    /**
     * Converts {@link io.github.dailystruggle.rtp.common.factory.FactoryValue} into an ordered map
     * with leading {@code name} discriminator followed by enum data entries.
     */
    private static Map<String, Object> factoryValueToMap(
            io.github.dailystruggle.rtp.common.factory.FactoryValue<?> fv) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        // Discriminator first so the type row sits at the top of the flattened view.
        out.put("name", fv.name);
        java.util.EnumMap<?, Object> data = fv.getData();
        if (data != null) {
            for (Map.Entry<?, Object> e : data.entrySet()) {
                Object k = e.getKey();
                if (k == null) continue;
                out.put(String.valueOf(k), e.getValue());
            }
        }
        return out;
    }

    /**
     * Resolves hover text for a config key from its YAML block comment.
     * Strips leading {@code #} markers and escapes color codes.
     *
     * @param yamlRoot loaded YAML document root, or {@code null}
     * @param key      config key
     * @return cleaned comment text, or {@code null} when unavailable
     */
    private static String resolveConfigHover(
            io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection yamlRoot,
            String key) {
        if (yamlRoot == null || key == null || key.isEmpty()) return null;
        String raw;
        try {
            raw = yamlRoot.getComment(key);
        } catch (RuntimeException ignored) {
            return null;
        }
        if (raw == null || raw.isEmpty()) return null;
        String[] commentLines = raw.split("\\R", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < commentLines.length; i++) {
            String trimmed = commentLines[i].stripLeading();
            if (trimmed.startsWith("#")) {
                trimmed = trimmed.substring(1);
                if (trimmed.startsWith(" ")) trimmed = trimmed.substring(1);
            }
            if (i > 0) sb.append('\n');
            sb.append(trimmed);
        }
        String cleaned = sb.toString();
        if (cleaned.isBlank()) return null;
        // Comments routinely document color codes (e.g. "use &e/&6") as examples.
        // The menu renderers run hover text through the standard &-to-§ color
        // translation, which would otherwise paint the description or swallow
        // the example codes. Escape them so they render as literal text.
        return io.github.dailystruggle.rtp.common.text.LegacyColorStrip.escape(cleaned);
    }

    private static String lookupMsg(Enum<?> key, String fallback) {
        if (RTP.configs == null) return fallback;
        Object v = RTP.configs.getConfigValue(key, fallback);
        return v == null ? fallback : v.toString();
    }
}
