package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Curated admin-panel page builder for {@code /rtp menu} (PROPOSAL-admin-panel.md v2).
 *
 * <p>Sibling to {@link FrontPageBuilder}: produces a curated {@link MenuModel}
 * dedicated to operator tooling, reached from the front page's single
 * {@code Admin panel} entry row (which emits a {@link MenuAction.OpenAdminPanel}
 * token). The panel collects every row that previously lived inline on the
 * front-page admin block plus a {@code Browse all commands} reflected-tree
 * fallback, organised into named sections so the page can grow without
 * pushing player-facing chrome off page 1 of the book.
 *
 * <p>Sections (top-to-bottom):
 * <ul>
 *   <li><b>Configuration</b> &mdash; {@link MenuAction.OpenConfigSelector}, gated on
 *       {@code rtp.config.view}.</li>
 *   <li><b>Diagnostics</b> &mdash; {@code /rtp info}, {@code /rtp test full},
 *       {@code /rtp test memory} (if registered), and the scan sub-menu.</li>
 *   <li><b>Lifecycle</b> &mdash; {@code /rtp reload}, gated on {@code rtp.reload}.</li>
 *   <li><b>Browse</b> &mdash; reflected-tree fallback via {@link MenuAction.OpenMenu}
 *       with an empty path, equivalent to today's {@code /rtp menu} pre-Stage-B
 *       behaviour.</li>
 * </ul>
 *
 * <p>Per-row self-hide rules:
 * <ul>
 *   <li>A row is suppressed when its underlying subcommand is not registered on
 *       the live {@code /rtp} tree (matches {@link FrontPageBuilder}'s defensive
 *       contract).</li>
 *   <li>A row is suppressed when the viewer lacks the row's permission.</li>
 *   <li>A section divider whose entire body is hidden is also suppressed, so a
 *       partial admin never sees a {@code Lifecycle} header with nothing under
 *       it.</li>
 * </ul>
 *
 * <p>The admin gate ({@link FrontPageBuilder#ADMIN_PERMISSION}) is enforced
 * upstream at the dispatch arm ({@code MenuRedeemSubcommand#dispatchOpenAdminPanel});
 * this builder trusts that contract and does not re-probe it. Each clickable
 * fragment mints one token, mirroring {@link FrontPageBuilder} for ADR-035 §3
 * book-renderer compatibility.
 */
public final class AdminPanelBuilder {

    /** Permission required to act on the {@code Reload} lifecycle row. */
    public static final String RELOAD_PERMISSION = "rtp.reload";

    /** Permission required to act on the {@code Scan control} row. */
    public static final String SCAN_PERMISSION = "rtp.scan";

    /**
     * Permission required for the {@code Setup (quick start)} section. Mirrors
     * the {@code rtp.admin.prefab} gate enforced by the {@code prefab}
     * {@link TreeCommand} subtree (Session 4a). When the viewer lacks it,
     * every prefab row (and the section divider) is suppressed.
     */
    public static final String PREFAB_PERMISSION = "rtp.admin.prefab";

    /**
     * Permission required to act on the {@code Config editor} row. Mirrors
     * {@link FrontPageBuilder#CONFIG_VIEW_PERMISSION} and the dispatch-side
     * gate on {@link MenuAction.OpenConfigSelector}.
     */
    public static final String CONFIG_VIEW_PERMISSION =
            FrontPageBuilder.CONFIG_VIEW_PERMISSION;

    /**
     * ADR-050 Stage 3β.D.2b (2026-05-24): no-arg constructor. The renderer
     * emits concrete {@code /rtp menu ...} commands, so no token registry or
     * TTL is consulted any more.
     */
    public AdminPanelBuilder() {
    }

    /**
     * Build the curated admin-panel {@link MenuModel} for {@code viewer}. The
     * caller is responsible for enforcing the
     * {@link FrontPageBuilder#ADMIN_PERMISSION} gate before invocation; this
     * builder will happily produce a (possibly empty-body) model if invoked
     * without admin rights.
     *
     * @param rtpRoot    the {@code /rtp} root {@link TreeCommand}; used to
     *                   probe for subcommand availability.
     * @param viewer     the calling player UUID.
     * @param permission permission probe for {@code viewer}; consulted per
     *                   row, never for the admin gate (that is the dispatch
     *                   arm's responsibility).
     */
    public MenuModel build(TreeCommand rtpRoot, UUID viewer, Predicate<String> permission) {
        Objects.requireNonNull(rtpRoot, "rtpRoot");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(permission, "permission");

        List<MenuLine> lines = new ArrayList<>();

        // Title + hint.
        String title = lookupMsg(MessagesKeys.menuAdminPanelTitle, "&6&l\u2699 Admin panel");
        if (title != null && !title.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(title, null, null)));
        }
        String hint = lookupMsg(
                MessagesKeys.menuAdminPanelHint,
                "&7click an option below");
        if (hint != null && !hint.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(hint, null, null)));
        }
        // blank spacer line between header and first section
        lines.add(new MenuLine(List.of()));

        // --- Setup (quick start) section ---
        // Single entry-point row that opens the prefab `id` parameter
        // picker directly, skipping the apply/confirm/rollback/list
        // subcommand-list page that an OpenMenu({admin,prefab}) would
        // produce. CommandTreeMenuBuilder's MenuAction.OpenParamPicker
        // arm (resolved server-side by MenuRedeemSubcommand) renders
        // one clickable row per value returned by
        // PrefabIdParameter.values() (live PrefabRegistry); clicking a
        // row dispatches `/rtp admin prefab apply id=<id>`. This gives
        // the operator the clickable prefab-selection book menu without
        // flooding the admin panel page itself with per-prefab rows.
        // Suppressed wholesale when the viewer lacks `rtp.admin.prefab`
        // so a partial admin never sees the Setup divider with no row
        // under it.
        List<MenuLine> setupRows = new ArrayList<>();
        if (safeTest(permission, PREFAB_PERMISSION)) {
            addRow(
                    setupRows,
                    "&b\u2728 Setup prefabs",
                    "Pick a bundled prefab to apply.",
                    new MenuAction.OpenParamPicker(
                            new String[]{"admin", "prefab", "apply"}, "id"));
        }
        appendSetupSection(lines, setupRows);

        // --- Configuration section ---
        List<MenuLine> configRows = new ArrayList<>();
        if (hasSubcommand(rtpRoot, "config") && safeTest(permission, CONFIG_VIEW_PERMISSION)) {
            addRow(
                    configRows,
                    lookupMsg(MessagesKeys.menuAdminPanelRowConfig, "&b\u2699 Config editor"),
                    lookupMsg(
                            MessagesKeys.menuAdminPanelHoverConfig,
                            "Open the curated config selector."),
                    new MenuAction.OpenConfigSelector());
        }
        // CHECKLIST-multiconfig-menu: Regions / Worlds rows live in the
        // config selector page (CommandTreeMenuBuilder.buildConfigSelector),
        // not the admin panel. See PROPOSAL-multiconfig-menu.md.
        appendSection(
                lines,
                MessagesKeys.menuAdminPanelSectionConfig,
                "&8\u00bb &7configuration",
                configRows);

        // --- Diagnostics section ---
        List<MenuLine> diagRows = new ArrayList<>();
        if (hasSubcommand(rtpRoot, "info")) {
            // PROPOSAL-info-as-book.md section 4.6: the admin-panel "server
            // info" row now opens the curated info book (mirrors the chat
            // output of /rtp info page-by-page) instead of dumping the same
            // lines into chat. Falls back transparently when the dispatch
            // arm's MenuInfoBookBuilder is unwired: the dispatch arm logs WARN
            // + reject with menuInvalid (S-004), which is the same shape every
            // other curated builder uses when its SAM is missing.
            addRow(
                    diagRows,
                    lookupMsg(MessagesKeys.menuAdminPanelRowInfo, "&b\u2139 Server info"),
                    lookupMsg(
                            MessagesKeys.menuAdminPanelHoverInfo,
                            "Show plugin version, region counts, and queue state."),
                    new MenuAction.OpenInfo(MenuAction.InfoScopeToken.global()));
        }
        if (hasSubcommand(rtpRoot, "test")) {
            addRow(
                    diagRows,
                    lookupMsg(
                            MessagesKeys.menuAdminPanelRowDiagnostics, "&b\u26a1 Full diagnostics"),
                    lookupMsg(
                            MessagesKeys.menuAdminPanelHoverDiagnostics,
                            "Runs the full diagnostic suite."),
                    new MenuAction.RunRtpCommand(new String[]{"test", "full"}));
            // /rtp test memory: an optional subcommand on the test subtree.
            // We can only probe the live tree; hidden if not registered.
            if (hasTestSubcommand(rtpRoot, "memory")) {
                addRow(
                        diagRows,
                        lookupMsg(
                                MessagesKeys.menuAdminPanelRowMemory,
                                "&b\u26ed Memory tracker snapshot"),
                        lookupMsg(
                                MessagesKeys.menuAdminPanelHoverMemory,
                                "Dump active chunk-ticket and pipeline allocations."),
                        new MenuAction.RunRtpCommand(new String[]{"test", "memory"}));
            }
        }
        if (hasSubcommand(rtpRoot, "scan") && safeTest(permission, SCAN_PERMISSION)) {
            addRow(
                    diagRows,
                    lookupMsg(MessagesKeys.menuAdminPanelRowScan, "&b\u21bb Scan control"),
                    lookupMsg(
                            MessagesKeys.menuAdminPanelHoverScan,
                            "Open the scan submenu (start / pause / cancel)."),
                    new MenuAction.OpenMenu(new String[]{"scan"}));
        }
        // Visualizations row - opens the per-region bad-locations map submenu.
        // Server-side dispatch arm gates on rtp.menu.admin (same admin surface
        // as the panel itself), so we surface the row unconditionally; the
        // dispatch path rejects with menuInvalid on a non-admin click.
        addRow(
                diagRows,
                lookupMsg(
                        MessagesKeys.menuAdminPanelRowVisualizations,
                        "&b\u2316 Visualizations"),
                lookupMsg(
                        MessagesKeys.menuAdminPanelHoverVisualizations,
                        "Open maps and other operator-facing visualizations."),
                new MenuAction.OpenVisualizations());
        appendSection(
                lines,
                MessagesKeys.menuAdminPanelSectionDiagnostics,
                "&8\u00bb &7diagnostics",
                diagRows);

        // --- Lifecycle section ---
        List<MenuLine> lifecycleRows = new ArrayList<>();
        if (hasSubcommand(rtpRoot, "reload") && safeTest(permission, RELOAD_PERMISSION)) {
            addRow(
                    lifecycleRows,
                    lookupMsg(MessagesKeys.menuAdminPanelRowReload, "&c\u26a0 Reload"),
                    lookupMsg(
                            MessagesKeys.menuAdminPanelHoverReload,
                            "Reloads all config files."),
                    new MenuAction.RunRtpCommand(new String[]{"reload"}));
        }
        appendSection(
                lines,
                MessagesKeys.menuAdminPanelSectionLifecycle,
                "&8\u00bb &7lifecycle",
                lifecycleRows);

        // --- Browse section ---
        // Reflected-tree fallback. Always available; OpenMenu([]) walks the
        // /rtp root via MenuRedeemSubcommand.dispatchOpen, which is independent
        // of the curated front-page builder routing.
        List<MenuLine> browseRows = new ArrayList<>();
        addRow(
                browseRows,
                lookupMsg(MessagesKeys.menuAdminPanelRowBrowse, "&b\u2630 Browse all commands"),
                lookupMsg(
                        MessagesKeys.menuAdminPanelHoverBrowse,
                        "Open the reflected /rtp command tree."),
                new MenuAction.OpenMenu(new String[0]));
        appendSection(
                lines,
                MessagesKeys.menuAdminPanelSectionBrowse,
                "&8\u00bb &7browse",
                browseRows);

        // Build the back row separately so pagination can guarantee it
        // lands on the last page (and gets its own page if the body fills
        // the previous one). The pre-back spacer is folded into the
        // paginate() helper so a page-break does not produce a dangling
        // blank row at the top of the final page.
        MenuLine backRow = null;
        String backLabel = lookupMsg(MessagesKeys.menuAdminPanelRowBack, "&7\u21a9 Back");
        if (backLabel != null && !backLabel.isEmpty()) {
            backRow = MenuLine.of(new MenuFragment(
                    backLabel, null, new MenuAction.OpenFrontPage()));
        }

        // Paginate the curated panel so a fully-populated admin view (title
        // + hint + 7 prefab rows + 4 sections + back row, easily >20 lines)
        // does not overflow the book renderer's per-page line cap and push
        // the configuration / diagnostics / lifecycle sections off-screen.
        // Page cap matches the InfoBookBuilder convention (13 lines/page).
        List<MenuPage> pages = paginate(lines, backRow);

        // ADR-050 Stage 3α: pre-mint loop removed. Renderer emits concrete
        // /rtp menu ... commands per fragment action.
        return new MenuModel(title == null ? "" : title, pages);
    }

    /**
     * Lines per book page. Mirrors {@link InfoBookBuilder#LINES_PER_PAGE}
     * so the admin panel and the info book share visual chrome.
     */
    static final int LINES_PER_PAGE = 13;

    /**
     * Split the accumulated body lines into pages of at most
     * {@link #LINES_PER_PAGE} lines and append {@code backRow} to the last
     * page (allocating an extra page if the last body page is already
     * full). Section dividers are nudged onto the next page when they
     * would otherwise be the bottom line of a page (orphaned-divider
     * guard) so the divider always sits directly above the rows it
     * introduces.
     */
    private static List<MenuPage> paginate(List<MenuLine> lines, MenuLine backRow) {
        List<MenuPage> pages = new ArrayList<>();
        List<MenuLine> current = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            MenuLine line = lines.get(i);
            if (current.size() >= LINES_PER_PAGE) {
                // Trim a trailing blank spacer line so a page-break never
                // produces a dangling empty row at the page boundary.
                while (!current.isEmpty()
                        && current.get(current.size() - 1).fragments().isEmpty()) {
                    current.remove(current.size() - 1);
                }
                pages.add(new MenuPage(current));
                current = new ArrayList<>();
            }
            // Skip a leading blank spacer at the top of a fresh page so a
            // section that fell across a page break doesn't lead with a
            // gap line.
            if (current.isEmpty() && line != null && line.fragments().isEmpty()) {
                continue;
            }
            current.add(line);
        }
        if (!current.isEmpty()) {
            pages.add(new MenuPage(current));
        }
        if (pages.isEmpty()) {
            pages.add(new MenuPage(new ArrayList<>()));
        }
        if (backRow != null) {
            List<MenuLine> last = new ArrayList<>(pages.get(pages.size() - 1).lines());
            // Reserve one line for a blank spacer above the back row when
            // there's room; otherwise spill the back row onto a fresh page
            // so it never collides with the previous section's last row.
            int needed = last.isEmpty() ? 1 : 2;
            boolean spillToNewPage = last.size() + needed > LINES_PER_PAGE;
            if (spillToNewPage) {
                // Leave the existing last page intact and append a fresh
                // page that hosts only the back row.
                last = new ArrayList<>();
                last.add(backRow);
                pages.add(new MenuPage(last));
            } else {
                if (!last.isEmpty()) {
                    MenuLine prev = last.get(last.size() - 1);
                    if (prev != null && !prev.fragments().isEmpty()) {
                        last.add(new MenuLine(List.of()));
                    }
                }
                last.add(backRow);
                pages.set(pages.size() - 1, new MenuPage(last));
            }
        }
        return pages;
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Setup-section variant of {@link #appendSection} that uses a hardcoded
     * English divider literal until Session 6 lands the
     * {@code menuAdminPanelSectionSetup} {@link MessagesKeys} entry through
     * the locale TSV pipeline. Once that key exists, this method should be
     * collapsed back into the standard {@code appendSection} call by Session
     * 6's edit. Behavior is otherwise identical (empty body suppresses the
     * divider; spacer line inserted between sections).
     */
    private static void appendSetupSection(List<MenuLine> dest, List<MenuLine> body) {
        if (body == null || body.isEmpty()) return;
        if (!dest.isEmpty()) {
            MenuLine last = dest.get(dest.size() - 1);
            if (last != null && !last.fragments().isEmpty()) {
                dest.add(new MenuLine(List.of()));
            }
        }
        // Section divider removed: hover text on each row now carries the
        // descriptive label, freeing a line per section so the admin panel
        // fits on a single book page until additional rows force pagination.
        dest.addAll(body);
    }

    private static void appendSection(List<MenuLine> dest,
                                      MessagesKeys dividerKey,
                                      String dividerFallback,
                                      List<MenuLine> body) {
        if (body == null || body.isEmpty()) return;
        // blank spacer line between sections (skipped if this is the first section)
        if (!dest.isEmpty()) {
            MenuLine last = dest.get(dest.size() - 1);
            if (last != null && !last.fragments().isEmpty()) {
                dest.add(new MenuLine(List.of()));
            }
        }
        // Section divider row removed: per-row hover text now carries the
        // descriptive label so the admin panel fits a single book page.
        // dividerKey / dividerFallback are retained on the call sites for
        // future re-enable without re-plumbing every section.
        if (dividerKey == null && dividerFallback == null) {
            // unreachable; suppresses unused-parameter warnings.
        }
        dest.addAll(body);
    }

    private static void addRow(List<MenuLine> lines, String label, String hover, MenuAction action) {
        if (label == null || label.isEmpty()) return;
        lines.add(MenuLine.of(new MenuFragment(label, hover, action)));
    }

    private static boolean safeTest(Predicate<String> permission, String perm) {
        if (perm == null || perm.isEmpty()) return true;
        try {
            return permission.test(perm);
        } catch (RuntimeException e) {
            RTP.log(java.util.logging.Level.WARNING,
                    "admin-panel permission probe threw for '" + perm + "': " + e.getMessage(), e);
            return false;
        }
    }

    private static boolean hasSubcommand(TreeCommand root, String name) {
        Map<String, CommandsAPICommand> lookup = root.getCommandLookup();
        if (lookup == null || name == null) return false;
        return lookup.containsKey(name)
                || lookup.containsKey(name.toUpperCase(Locale.ROOT));
    }

    /**
     * Probes the {@code /rtp test} subtree for {@code childName}. Returns
     * {@code false} when the {@code test} subtree itself is missing or is
     * not a {@link TreeCommand}, or when no matching child is registered.
     */
    private static boolean hasTestSubcommand(TreeCommand root, String childName) {
        Map<String, CommandsAPICommand> lookup = root.getCommandLookup();
        if (lookup == null || childName == null) return false;
        CommandsAPICommand test = lookup.get("test");
        if (test == null) test = lookup.get("TEST");
        if (!(test instanceof TreeCommand testTree)) return false;
        Map<String, CommandsAPICommand> children = testTree.getCommandLookup();
        if (children == null) return false;
        return children.containsKey(childName)
                || children.containsKey(childName.toUpperCase(Locale.ROOT));
    }

    @SuppressWarnings("unchecked")
    private static String lookupMsg(MessagesKeys key, String fallback) {
        if (RTP.configs == null) return fallback;
        ConfigParser<MessagesKeys> lang =
                (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        if (lang == null) return fallback;
        Object v = lang.getConfigValue(key, fallback);
        return v == null ? fallback : v.toString();
    }
}
