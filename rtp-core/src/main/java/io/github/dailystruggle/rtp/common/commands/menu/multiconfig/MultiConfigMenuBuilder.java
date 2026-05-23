package io.github.dailystruggle.rtp.common.commands.menu.multiconfig;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuTokenRegistry;
import io.github.dailystruggle.rtp.common.commands.menu.CommandTreeMenuBuilder;
import io.github.dailystruggle.rtp.common.commands.menu.FrontPageBuilder;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Curated submenu page builder for the generic
 * {@link MultiConfigParser MultiConfig} surface (regions, worlds, and any
 * future kind registered through {@link MultiConfigRemovalGuards}).
 *
 * <p>This builder is the page-rendering half of the design captured in
 * {@code docs/dev/scratch/PROPOSAL-multiconfig-menu.md}. It mirrors the
 * shape of {@link io.github.dailystruggle.rtp.common.commands.menu.PrefabConfirmationMenuBuilder}
 * and {@link io.github.dailystruggle.rtp.common.commands.menu.AdminPanelBuilder}:
 *
 * <ul>
 *   <li>Each clickable {@link MenuFragment} gets a token minted on the
 *       supplied {@link MenuTokenRegistry} (ADR-035 §3) so the book
 *       renderer can emit {@code menu:&lt;token&gt;} click payloads.</li>
 *   <li>Book parchment contrast: yellow ({@code &amp;e}/{@code &amp;6}) and
 *       white ({@code &amp;f}) are avoided; locked rows render in dark
 *       gray ({@code &amp;8}) per the user's session 6 directive.</li>
 *   <li>Entry names are surfaced verbatim from
 *       {@link MultiConfigParser#listParsers()} (file-name origin); they
 *       are not translated through {@code messages.yml}. Only the
 *       surrounding chrome (title, hint, add/remove labels, locked-row
 *       hover) is locale-bound.</li>
 * </ul>
 *
 * <p><strong>Implementation status:</strong> step 5 of
 * {@code CHECKLIST-multiconfig-menu.md} is partial. Only
 * {@link #buildSelector(String, MultiConfigParser, boolean, UUID)} is
 * implemented; {@code buildEntry} and {@code buildConfirmRemove} are
 * deferred to a follow-up session. The dispatcher arms in
 * {@code MenuRedeemSubcommand} (step 7) and the wiring in
 * {@code AdminPanelBuilder} (step 13) are gated on those follow-up
 * additions; do not wire this builder in production until
 * {@code buildEntry} lands.
 *
 * <p>Threading: stateless aside from the injected token registry; safe to
 * call from any platform thread that may also call the registry.
 */
public final class MultiConfigMenuBuilder {

    /** Token TTL aligned with the rest of the curated menu surface. */
    public static final Duration DEFAULT_TOKEN_TTL = FrontPageBuilder.DEFAULT_TOKEN_TTL;

    private final MenuTokenRegistry tokenRegistry;
    private final Duration tokenTtl;
    /**
     * Optional reference to the project-wide {@link CommandTreeMenuBuilder}.
     * When wired by the platform adapter (see
     * {@code RTPCmdBukkit#setupMenuRedeem}), {@link #buildEntry} delegates
     * to {@link CommandTreeMenuBuilder#buildConfigFile} so multiconfig
     * entries render with the same per-key clickable rows, pagination,
     * and staging cart parity as flat configs (e.g. {@code config.yml}).
     * When {@code null} the builder falls back to the legacy display-only
     * layout for backward-compat with surface tests that do not wire it.
     */
    private volatile @org.jetbrains.annotations.Nullable CommandTreeMenuBuilder commandTreeMenuBuilder;

    public MultiConfigMenuBuilder(MenuTokenRegistry tokenRegistry) {
        this(tokenRegistry, DEFAULT_TOKEN_TTL);
    }

    public MultiConfigMenuBuilder(MenuTokenRegistry tokenRegistry, Duration tokenTtl) {
        this.tokenRegistry = Objects.requireNonNull(tokenRegistry, "tokenRegistry");
        this.tokenTtl = Objects.requireNonNull(tokenTtl, "tokenTtl");
    }

    /**
     * Wire (or unwire) the {@link CommandTreeMenuBuilder} used by
     * {@link #buildEntry} to delegate per-entry rendering. Pass
     * {@code null} to disable delegation (legacy display-only layout).
     */
    public void setCommandTreeMenuBuilder(@org.jetbrains.annotations.Nullable CommandTreeMenuBuilder builder) {
        this.commandTreeMenuBuilder = builder;
    }

    /**
     * Build the entry-list page for a {@link MultiConfigParser} of kind
     * {@code parserKind}.
     *
     * <p>Page layout (proposal §3.3):
     * <ol>
     *   <li>Title row (dark blue + bold) - "{@code config &lt;kind&gt;}".</li>
     *   <li>Hint row (dark gray) - one-line usage prompt.</li>
     *   <li>Back row - dispatches {@link MenuAction.OpenAdminPanel}.</li>
     *   <li>Toggle row - flips remove-mode for this viewer (per-viewer
     *       state lives in {@code MenuRedeemSubcommand}; the toggle row
     *       dispatches {@link MenuAction.OpenMultiConfigSelector} again
     *       and the dispatcher arm reads/writes the flag).</li>
     *   <li>Add row - dispatches a
     *       {@link MenuAction.MultiConfigMutate} with
     *       {@link MenuAction.MultiConfigMutate.Op#ADD} and a synthesized
     *       {@code "default&lt;N&gt;"} name (anvil-prompt to override
     *       lands in step 7 alongside the dispatcher).</li>
     *   <li>One row per entry, alphabetical. In normal mode the row
     *       dispatches {@link MenuAction.OpenMultiConfigEntry}; in
     *       remove-mode it dispatches a
     *       {@link MenuAction.MultiConfigMutate} REMOVE token. Rows
     *       locked by the registered
     *       {@link MultiConfigRemovalGuard} render gray and (in
     *       remove-mode) carry no action and a hover reason.</li>
     * </ol>
     *
     * @param parserKind  the kind string (e.g. {@code "regions"},
     *                    {@code "worlds"}); used only for display and
     *                    token routing.
     * @param parser      the live {@link MultiConfigParser} whose
     *                    entries are listed.
     * @param removeMode  current per-viewer remove-mode flag; toggled by
     *                    the dispatcher when the toggle row is clicked.
     * @param viewer      the calling player UUID; token-mint scope.
     */
    public MenuModel buildSelector(String parserKind,
                                   MultiConfigParser<?> parser,
                                   boolean removeMode,
                                   UUID viewer) {
        Objects.requireNonNull(parserKind, "parserKind");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(viewer, "viewer");

        MultiConfigRemovalGuard guard = MultiConfigRemovalGuards.get(parserKind);

        List<MenuLine> lines = new ArrayList<>();

        String title = "&1&l\u2699 config " + parserKind;
        lines.add(MenuLine.of(new MenuFragment(title, null, null)));
        lines.add(MenuLine.of(new MenuFragment(
                "&8click an entry to edit; toggle remove-mode to delete",
                null, null)));
        lines.add(new MenuLine(List.of()));

        // Back row - returns to the admin panel.
        addRow(lines, "&1[back]",
                "return to the admin panel",
                new MenuAction.OpenAdminPanel());

        // Toggle row - flips remove-mode. Dispatcher reads/writes the
        // per-viewer flag and re-renders. Label reflects current state.
        String toggleLabel = removeMode
                ? "&c&l[remove-mode: on]"
                : "&2[remove-mode: off]";
        addRow(lines, toggleLabel,
                removeMode
                        ? "click an entry to remove it (locked entries are grayed)"
                        : "switch to remove-mode",
                new MenuAction.OpenMultiConfigSelector("!toggle:" + parserKind));

        // Add row - opens an anvil-input prompt prefilled with a synthesized
        // unique name so the admin can type a custom region/world name before
        // the entry is created. On confirm the anvil session submits
        //   /rtp menu multiaddKind=<parserKind> multiadd=<typedName>
        // which the MenuRedeemSubcommand dispatch arm routes through
        // dispatchMultiConfigMutate(ADD). The parentPath carries the parser
        // kind as a name=value segment so dispatchPromptAnvilInput's walker
        // skips it (it does not resolve as a TreeCommand child) while the
        // platform-side AnvilInputSession#buildCommand still appends it
        // verbatim to the synthesized /rtp menu ... command.
        String seedName = nextDefaultName(parser.listParsers());
        addRow(lines, "&2&l[+ add new]",
                "type a name (default: \"" + seedName + "\")",
                new MenuAction.PromptAnvilInput(
                        new String[]{"multiaddKind=" + parserKind},
                        "multiadd", seedName));

        lines.add(new MenuLine(List.of()));

        // Sorted entry rows.
        Set<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        sorted.addAll(parser.listParsers());

        for (String entry : sorted) {
            boolean locked = guard.isLocked(entry);

            if (removeMode) {
                if (locked) {
                    // Locked: gray, non-clickable, hover carries the
                    // registered reason. No token minted.
                    String reason = guard.reason(entry);
                    lines.add(MenuLine.of(new MenuFragment(
                            "&8" + entry + " (locked)",
                            reason == null || reason.isEmpty() ? null : reason,
                            null)));
                } else {
                    addRow(lines, "&c" + entry,
                            "click to remove \"" + entry + "\"",
                            new MenuAction.MultiConfigMutate(
                                    parserKind, entry,
                                    MenuAction.MultiConfigMutate.Op.REMOVE));
                }
            } else {
                addRow(lines, "&1" + entry,
                        "edit \"" + entry + "\"",
                        new MenuAction.OpenMultiConfigEntry(parserKind, entry));
            }
        }

        MenuPage page = new MenuPage(lines);
        List<MenuPage> pages = List.of(page);

        // Mint one token per clickable fragment (ADR-035 §3).
        for (MenuLine line : page.lines()) {
            for (MenuFragment fragment : line.fragments()) {
                MenuAction action = fragment.action();
                if (action != null) {
                    tokenRegistry.mint(viewer, action, tokenTtl);
                }
            }
        }

        return new MenuModel(title, pages);
    }

    /**
     * Build the per-entry page for {@code entryName} inside the
     * {@link MultiConfigParser} of kind {@code parserKind}.
     *
     * <p>Page layout (proposal §3.3):
     * <ol>
     *   <li>Back row to {@link MenuAction.OpenMultiConfigSelector}.</li>
     *   <li>Header row "{@code <kind> / <entryName>}" (non-clickable).</li>
     *   <li>One non-clickable row per enum key in the entry's
     *       {@link ConfigParser}, label {@code "<key>: <currentValue>"}.
     *       Per-key clickability is gated on step 7 (the dispatcher
     *       extends the staging-cart key scope to
     *       {@code (parserKind, entryName, file)}). Until then the rows
     *       render as read-only display rows so the entry contents are
     *       visible from the book.</li>
     *   <li>Remove row at the bottom. Renders gray + hover-reason and
     *       carries no action when the registered
     *       {@link MultiConfigRemovalGuard} locks the entry; otherwise
     *       dispatches {@link MenuAction.MultiConfigMutate} REMOVE which
     *       the dispatcher routes through
     *       {@link #buildConfirmRemove}.</li>
     * </ol>
     */
    public MenuModel buildEntry(String parserKind,
                                String entryName,
                                MultiConfigParser<?> parser,
                                UUID viewer) {
        return buildEntry(parserKind, entryName, parser, viewer,
                new java.util.LinkedHashMap<>());
    }

    /**
     * Cart-aware overload. When a {@link CommandTreeMenuBuilder} is wired
     * via {@link #setCommandTreeMenuBuilder}, this delegates per-entry
     * rendering to {@link CommandTreeMenuBuilder#buildConfigFile} using
     * the synthetic fileName {@code "<parserKind>/<entryName>"} so that:
     * (a) each enum key row mints a clickable
     * {@link MenuAction.OpenConfigKey} (so admins can edit
     * {@code default.yml} values like {@code radius}),
     * (b) pagination kicks in past the ~12-row budget, and
     * (c) the viewer's staged {@code (paramName -> typed value)} pairs
     * from {@code cartSnapshot} are surfaced as Pending + Apply +
     * Discard rows. The slash-aware dispatcher branches resolve the
     * synthetic fileName back into the entry parser and route Apply to
     * the {@code /rtp config <kind> <entryName> k=v ...} CLI shape.
     *
     * <p>Post-processing swaps the delegate's Back row from
     * {@code OpenConfigSelector} -> {@code OpenMultiConfigSelector} and
     * appends a Remove row (gray + non-clickable when the registered
     * {@link MultiConfigRemovalGuard} locks the entry).
     *
     * <p>When no {@link CommandTreeMenuBuilder} is wired (surface tests
     * that omit the setter), falls back to the legacy single-page
     * display-only layout.
     */
    public MenuModel buildEntry(String parserKind,
                                String entryName,
                                MultiConfigParser<?> parser,
                                UUID viewer,
                                java.util.LinkedHashMap<String, String> cartSnapshot) {
        Objects.requireNonNull(parserKind, "parserKind");
        Objects.requireNonNull(entryName, "entryName");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(cartSnapshot, "cartSnapshot");
        if (entryName.isEmpty()) {
            throw new IllegalArgumentException("entryName must not be empty");
        }

        ConfigParser<?> entryParser = resolveEntryParser(parser, entryName);
        MultiConfigRemovalGuard guard = MultiConfigRemovalGuards.get(parserKind);

        // Delegation path. When the platform adapter wired a
        // CommandTreeMenuBuilder (rtp-plugin RTPCmdBukkit), reuse the
        // flat-config page builder so every editor feature (clickable
        // keys, pagination, staging cart) applies uniformly to
        // multiconfig entries. Post-processing rewrites Back + appends
        // the Remove row.
        CommandTreeMenuBuilder delegate = this.commandTreeMenuBuilder;
        if (delegate != null && entryParser != null) {
            String syntheticFileName = parserKind + "/" + entryName;
            MenuModel inner = invokeBuildConfigFile(
                    delegate, viewer, syntheticFileName, entryParser, cartSnapshot);
            if (inner != null) {
                String title = "&1&l\u2699 " + parserKind + " / " + entryName;
                List<MenuPage> rewritten = rewritePagesForEntry(
                        inner.pages(), parserKind, entryName, guard);
                mintTokens(rewritten, viewer);
                return new MenuModel(title, rewritten);
            }
        }

        // Fallback: legacy display-only layout. Surface tests that build
        // a MultiConfigMenuBuilder without wiring CommandTreeMenuBuilder
        // exercise this branch.
        List<MenuLine> lines = new ArrayList<>();

        // Title + Back. Back returns to the selector page for this kind.
        String title = "&1&l\u2699 " + parserKind + " / " + entryName;
        lines.add(MenuLine.of(new MenuFragment(title, null, null)));
        addRow(lines, "&1[back]",
                "return to the " + parserKind + " list",
                new MenuAction.OpenMultiConfigSelector(parserKind));
        lines.add(new MenuLine(List.of()));

        // Resolve the per-entry ConfigParser. If the entry is unknown
        // (race / forged token), surface a hint row rather than crashing
        // so the dispatcher's S-004 audit hook is the single reject path.
        // (entryParser already resolved above the delegation branch.)
        if (entryParser == null) {
            lines.add(MenuLine.of(new MenuFragment(
                    "&8(entry \"" + entryName + "\" not found)", null, null)));
        } else {
            renderEntryKeys(lines, entryParser);
        }

        // Remove row. Locked entries render gray + hover reason; otherwise
        // the row dispatches a REMOVE mutate, which the dispatcher (step 7)
        // routes through buildConfirmRemove.
        lines.add(new MenuLine(List.of()));
        boolean locked = guard.isLocked(entryName);
        if (locked) {
            String reason = guard.reason(entryName);
            lines.add(MenuLine.of(new MenuFragment(
                    "&8[remove] (locked)",
                    reason == null || reason.isEmpty() ? null : reason,
                    null)));
        } else {
            addRow(lines, "&c&l[remove]",
                    "delete \"" + entryName + "\" (asks for confirmation)",
                    new MenuAction.MultiConfigMutate(
                            parserKind, entryName,
                            MenuAction.MultiConfigMutate.Op.REMOVE));
        }

        MenuPage page = new MenuPage(lines);
        List<MenuPage> pages = List.of(page);
        mintTokens(pages, viewer);
        return new MenuModel(title, pages);
    }

    /**
     * Build the confirm-remove page for {@code entryName} of kind
     * {@code parserKind}. Mirrors the shape of
     * {@link io.github.dailystruggle.rtp.common.commands.menu.PrefabConfirmationMenuBuilder}:
     *
     * <ul>
     *   <li>Title + warning hint (non-clickable).</li>
     *   <li>Confirm row dispatching {@link MenuAction.MultiConfigMutate}
     *       REMOVE.</li>
     *   <li>Cancel row dispatching
     *       {@link MenuAction.OpenMultiConfigSelector} for {@code parserKind}.
     *       </li>
     * </ul>
     *
     * <p>The guard re-check is the dispatcher's job (step 7); this builder
     * does not consult the guard so a stale confirm page rendered just
     * before a guard-state change still produces a token that the
     * dispatcher will reject server-side.
     */
    public MenuModel buildConfirmRemove(String parserKind,
                                        String entryName,
                                        UUID viewer) {
        Objects.requireNonNull(parserKind, "parserKind");
        Objects.requireNonNull(entryName, "entryName");
        Objects.requireNonNull(viewer, "viewer");
        if (entryName.isEmpty()) {
            throw new IllegalArgumentException("entryName must not be empty");
        }

        List<MenuLine> lines = new ArrayList<>();
        String title = "&1&l\u2699 remove " + parserKind + ": &0" + entryName;
        lines.add(MenuLine.of(new MenuFragment(title, null, null)));
        lines.add(MenuLine.of(new MenuFragment(
                "&8this deletes the entry's yaml; click confirm to proceed",
                null, null)));
        lines.add(new MenuLine(List.of()));

        addRow(lines, "&c&l[confirm]",
                "delete \"" + entryName + "\"",
                new MenuAction.MultiConfigMutate(
                        parserKind, entryName,
                        MenuAction.MultiConfigMutate.Op.REMOVE));
        addRow(lines, "&2[cancel]",
                "return to the " + parserKind + " list",
                new MenuAction.OpenMultiConfigSelector(parserKind));

        MenuPage page = new MenuPage(lines);
        List<MenuPage> pages = List.of(page);
        mintTokens(pages, viewer);
        return new MenuModel(title, pages);
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Reflective bridge to
     * {@link CommandTreeMenuBuilder#buildConfigFile(UUID, String, ConfigParser, java.util.LinkedHashMap)}.
     * The signature is generic in the parser's enum type {@code <E>};
     * calling it through reflection avoids forcing this class to be
     * generic just to satisfy the wildcard {@code ConfigParser<?>} we
     * obtain from {@link MultiConfigParser#getParser(String)}. Returns
     * {@code null} on any failure so the caller can fall back to the
     * legacy display-only layout.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static @org.jetbrains.annotations.Nullable MenuModel invokeBuildConfigFile(
            CommandTreeMenuBuilder delegate,
            UUID viewer,
            String syntheticFileName,
            ConfigParser<?> entryParser,
            java.util.LinkedHashMap<String, String> cartSnapshot) {
        try {
            return ((CommandTreeMenuBuilder) delegate).buildConfigFile(
                    viewer, syntheticFileName,
                    (ConfigParser) entryParser, cartSnapshot);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Post-process the pages returned by
     * {@link CommandTreeMenuBuilder#buildConfigFile} for an entry render:
     * <ol>
     *   <li>On every page, replace the Back row's
     *       {@link MenuAction.OpenConfigSelector} action with
     *       {@link MenuAction.OpenMultiConfigSelector} so the operator
     *       returns to the per-kind entry list (not the flat-config
     *       selector).</li>
     *   <li>On the last page, append a Remove row. Locked entries (per
     *       the registered {@link MultiConfigRemovalGuard}) render gray
     *       + non-clickable with the guard's hover reason; otherwise the
     *       row dispatches a REMOVE {@link MenuAction.MultiConfigMutate}
     *       which the dispatcher routes through
     *       {@link #buildConfirmRemove}.</li>
     * </ol>
     */
    private static List<MenuPage> rewritePagesForEntry(List<MenuPage> pages,
                                                       String parserKind,
                                                       String entryName,
                                                       MultiConfigRemovalGuard guard) {
        List<MenuPage> out = new ArrayList<>(pages.size());
        int lastIdx = pages.size() - 1;
        MenuAction backTarget = new MenuAction.OpenMultiConfigSelector(parserKind);
        for (int i = 0; i < pages.size(); i++) {
            MenuPage page = pages.get(i);
            List<MenuLine> rewritten = new ArrayList<>(page.lines().size() + 2);
            for (MenuLine line : page.lines()) {
                List<MenuFragment> frags = line.fragments();
                boolean changed = false;
                List<MenuFragment> newFrags = new ArrayList<>(frags.size());
                for (MenuFragment f : frags) {
                    if (f.action() instanceof MenuAction.OpenConfigSelector) {
                        newFrags.add(new MenuFragment(
                                f.text(), f.hover(), backTarget));
                        changed = true;
                    } else {
                        newFrags.add(f);
                    }
                }
                rewritten.add(changed ? new MenuLine(newFrags) : line);
            }
            if (i == lastIdx) {
                rewritten.add(new MenuLine(List.of()));
                boolean locked = guard.isLocked(entryName);
                if (locked) {
                    String reason = guard.reason(entryName);
                    rewritten.add(MenuLine.of(new MenuFragment(
                            "&8[remove] (locked)",
                            reason == null || reason.isEmpty() ? null : reason,
                            null)));
                } else {
                    rewritten.add(MenuLine.of(new MenuFragment(
                            "&c&l[remove]",
                            "delete \"" + entryName + "\" (asks for confirmation)",
                            new MenuAction.MultiConfigMutate(
                                    parserKind, entryName,
                                    MenuAction.MultiConfigMutate.Op.REMOVE))));
                }
            }
            out.add(new MenuPage(rewritten));
        }
        return out;
    }

    private static void addRow(List<MenuLine> lines,
                               String label,
                               String hover,
                               MenuAction action) {
        if (label == null || label.isEmpty()) return;
        lines.add(MenuLine.of(new MenuFragment(label, hover, action)));
    }

    /**
     * Synthesize a {@code default&lt;N&gt;} name not present in
     * {@code existing}. {@code N} is incremented from 1 until a free
     * slot is found; the literal name {@code "default"} is treated as
     * occupied even when absent so the seed always reads as a copy.
     */
    static String nextDefaultName(Set<String> existing) {
        Set<String> ci = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (existing != null) ci.addAll(existing);
        int n = 1;
        while (true) {
            String candidate = "default" + n;
            if (!ci.contains(candidate)) return candidate;
            n++;
        }
    }

    /**
     * Look up the per-entry {@link ConfigParser} case-insensitively.
     * {@link MultiConfigParser#getParser(String)} expects the on-disk
     * name; tolerate case drift from the click token because file-name
     * casing is filesystem-dependent.
     */
    private static ConfigParser<?> resolveEntryParser(MultiConfigParser<?> parser,
                                                      String entryName) {
        // listParsers() is authoritative for what is loaded; trust it and
        // tolerate case drift from the click token (filesystem-dependent).
        // Avoid calling getParser() for unknown names because the
        // MultiConfigParser.getParser fallback path queries the underlying
        // Factory which throws IllegalStateException when the factory map
        // is empty (no template registered) - we want a clean "not found"
        // sentinel here, not a thrown exception.
        String target = entryName.toLowerCase(Locale.ROOT);
        for (String candidate : parser.listParsers()) {
            if (candidate == null) continue;
            if (candidate.toLowerCase(Locale.ROOT).equals(target)) {
                try {
                    return parser.getParser(candidate);
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Render one non-clickable display row per enum key in
     * {@code entryParser}, label {@code "<key>: <currentValue>"}. Empty
     * parsers produce a single empty-state hint row. Click-to-edit is
     * deferred to step 7 (cart scope widening to
     * {@code (parserKind, entryName, file)}).
     */
    private static <E extends Enum<E>> void renderEntryKeys(List<MenuLine> lines,
                                                            ConfigParser<E> entryParser) {
        E[] enumValues = entryParser.myClass.getEnumConstants();
        EnumMap<E, Object> loaded = entryParser.getData();
        boolean any = false;
        if (enumValues != null) {
            for (E key : enumValues) {
                if (key == null) continue;
                if (!loaded.containsKey(key)) continue;
                if ("VERSION".equals(key.name().toUpperCase(Locale.ROOT))) continue;
                Object current = loaded.get(key);
                String currentStr = current == null
                        ? "&8(unset)"
                        : String.valueOf(current);
                String label = "&2" + key.name() + "&7: &0" + currentStr;
                lines.add(MenuLine.of(new MenuFragment(label, null, null)));
                any = true;
            }
        }
        if (!any) {
            lines.add(MenuLine.of(new MenuFragment(
                    "&8(no editable keys in this entry)", null, null)));
        }
    }

    /**
     * Mint one token per clickable fragment in {@code pages} (ADR-035 §3).
     * Centralised so all three page builders use the same mint loop.
     */
    private void mintTokens(List<MenuPage> pages, UUID viewer) {
        for (MenuPage page : pages) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment fragment : line.fragments()) {
                    MenuAction action = fragment.action();
                    if (action != null) {
                        tokenRegistry.mint(viewer, action, tokenTtl);
                    }
                }
            }
        }
    }
}
