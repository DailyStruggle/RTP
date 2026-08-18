package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
/**
 * Curated admin-panel page builder for {@code /rtp menu}.
 *
 * <p>Produces a curated {@link MenuModel} dedicated to operator tooling (config, diagnostics,
 * lifecycle, and reflected-tree fallback), reached from the front page's admin panel entry row.
 */
public final class AdminPanelBuilder {

    /** Permission required to act on the {@code Reload} lifecycle row. */
    public static final String RELOAD_PERMISSION = "rtp.reload";

    /** Permission required to act on the {@code Scan control} row. */
    public static final String SCAN_PERMISSION = "rtp.scan";

    /**
     * Permission required for the {@code Setup (quick start)} section. Mirrors
     * the {@code rtp.admin.prefab} gate enforced by the {@code prefab}
     * {@link TreeCommand} subtree. When the viewer lacks it, every prefab row
     * (and the section divider) is suppressed.
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
     * No-arg constructor. The renderer emits concrete {@code /rtp menu ...}
     * commands, so no token registry or TTL is consulted.
     */
    public AdminPanelBuilder() {
    }

    /**
     * Builds curated admin-panel {@link MenuModel} for {@code viewer}.
     *
     * @param rtpRoot    {@code /rtp} root command to probe subcommand availability
     * @param viewer     calling player UUID
     * @param permission per-row permission probe
     * @return assembled {@link MenuModel}
     */
    public MenuModel build(TreeCommand rtpRoot, UUID viewer, Predicate<String> permission) {
        Objects.requireNonNull(rtpRoot, "rtpRoot");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(permission, "permission");

        List<MenuLine> lines = new ArrayList<>();

        // Title + hint.
        String title = lookupMsg(CommandMessages.menuAdminPanelTitle, "&6&l\u2699 Admin panel");
        if (title != null && !title.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(title, null, null)));
        }
        String hint = lookupMsg(
                CommandMessages.menuAdminPanelHint,
                "&7click an option below");
        if (hint != null && !hint.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(hint, null, null)));
        }
        // blank spacer line between header and first section
        lines.add(new MenuLine(List.of()));

        // --- Setup (quick start) section ---
        // Single entry row opening curated prefab menu (`/rtp menu prefab`), gated on rtp.admin.prefab.
        List<MenuLine> setupRows = new ArrayList<>();
        if (safeTest(permission, PREFAB_PERMISSION)) {
            addRow(
                    setupRows,
                    "&b\u2728 Setup prefabs",
                    "Pick a bundled prefab to apply.",
                    new MenuAction.RunRtpCommand(new String[]{"menu", "prefab"}));
        }
        appendSetupSection(lines, setupRows);

        // --- Configuration section ---
        List<MenuLine> configRows = new ArrayList<>();
        if (hasSubcommand(rtpRoot, "config") && safeTest(permission, CONFIG_VIEW_PERMISSION)) {
            addRow(
                    configRows,
                    lookupMsg(CommandMessages.menuAdminPanelRowConfig, "&b\u2699 Config editor"),
                    lookupMsg(
                            CommandMessages.menuAdminPanelHoverConfig,
                            "Open the curated config selector."),
                    new MenuAction.OpenConfigSelector());
        }
        // Regions / Worlds rows live in the config selector page
        // (CommandTreeMenuBuilder.buildConfigSelector), not the admin panel.
        appendSection(
                lines,
                CommandMessages.menuAdminPanelSectionConfig,
                "&8\u00bb &7configuration",
                configRows);

        // --- Diagnostics section ---
        List<MenuLine> diagRows = new ArrayList<>();
        if (hasSubcommand(rtpRoot, "info")) {
            // The admin-panel "server info" row opens the curated info book
            // (mirrors the chat output of /rtp info page-by-page) instead of
            // dumping the same lines into chat. Falls back transparently when the dispatch
            // arm's MenuInfoBookBuilder is unwired: the dispatch arm logs WARN
            // + reject with menuInvalid (S-004), which is the same shape every
            // other curated builder uses when its SAM is missing.
            addRow(
                    diagRows,
                    lookupMsg(CommandMessages.menuAdminPanelRowInfo, "&b\u2139 Server info"),
                    lookupMsg(
                            CommandMessages.menuAdminPanelHoverInfo,
                            "Show plugin version, region counts, and queue state."),
                    new MenuAction.OpenInfo(MenuAction.InfoScopeToken.global()));
        }
        if (hasSubcommand(rtpRoot, "test")) {
            addRow(
                    diagRows,
                    lookupMsg(
                            CommandMessages.menuAdminPanelRowDiagnostics, "&b\u26a1 Full diagnostics"),
                    lookupMsg(
                            CommandMessages.menuAdminPanelHoverDiagnostics,
                            "Runs the full diagnostic suite."),
                    new MenuAction.RunRtpCommand(new String[]{"test", "full"}));
            // /rtp test memory: an optional subcommand on the test subtree.
            // We can only probe the live tree; hidden if not registered.
            if (hasTestSubcommand(rtpRoot, "memory")) {
                addRow(
                        diagRows,
                        lookupMsg(
                                CommandMessages.menuAdminPanelRowMemory,
                                "&b\u26ed Memory tracker snapshot"),
                        lookupMsg(
                                CommandMessages.menuAdminPanelHoverMemory,
                                "Dump active chunk-ticket and pipeline allocations."),
                        new MenuAction.RunRtpCommand(new String[]{"test", "memory"}));
            }
        }
        if (hasSubcommand(rtpRoot, "scan") && safeTest(permission, SCAN_PERMISSION)) {
            addRow(
                    diagRows,
                    lookupMsg(CommandMessages.menuAdminPanelRowScan, "&b\u21bb Scan control"),
                    lookupMsg(
                            CommandMessages.menuAdminPanelHoverScan,
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
                        CommandMessages.menuAdminPanelRowVisualizations,
                        "&b\u2316 Visualizations"),
                lookupMsg(
                        CommandMessages.menuAdminPanelHoverVisualizations,
                        "Open maps and other operator-facing visualizations."),
                new MenuAction.OpenVisualizations());
        appendSection(
                lines,
                CommandMessages.menuAdminPanelSectionDiagnostics,
                "&8\u00bb &7diagnostics",
                diagRows);

        // --- Lifecycle section ---
        List<MenuLine> lifecycleRows = new ArrayList<>();
        if (hasSubcommand(rtpRoot, "reload") && safeTest(permission, RELOAD_PERMISSION)) {
            addRow(
                    lifecycleRows,
                    lookupMsg(CommandMessages.menuAdminPanelRowReload, "&c\u26a0 Reload"),
                    lookupMsg(
                            CommandMessages.menuAdminPanelHoverReload,
                            "Reloads all config files."),
                    new MenuAction.RunRtpCommand(new String[]{"reload"}));
        }
        appendSection(
                lines,
                CommandMessages.menuAdminPanelSectionLifecycle,
                "&8\u00bb &7lifecycle",
                lifecycleRows);

        // --- Browse section ---
        // Reflected-tree fallback. Always available; OpenMenu([]) walks the
        // /rtp root via MenuRedeemSubcommand.dispatchOpen, which is independent
        // of the curated front-page builder routing.
        List<MenuLine> browseRows = new ArrayList<>();
        addRow(
                browseRows,
                lookupMsg(CommandMessages.menuAdminPanelRowBrowse, "&b\u2630 Browse all commands"),
                lookupMsg(
                        CommandMessages.menuAdminPanelHoverBrowse,
                        "Open the reflected /rtp command tree."),
                new MenuAction.OpenMenu(new String[0]));
        appendSection(
                lines,
                CommandMessages.menuAdminPanelSectionBrowse,
                "&8\u00bb &7browse",
                browseRows);

        // Build the back row separately so pagination can guarantee it
        // lands on the last page (and gets its own page if the body fills
        // the previous one). The pre-back spacer is folded into the
        // paginate() helper so a page-break does not produce a dangling
        // blank row at the top of the final page.
        MenuLine backRow = null;
        String backLabel = lookupMsg(CommandMessages.menuAdminPanelRowBack, "&7\u21a9 Back");
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
     * Splits body lines into pages of at most {@link #LINES_PER_PAGE} lines and appends {@code backRow}.
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
     * Setup-section variant of {@link #appendSection}.
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
                                      Enum<?> dividerKey,
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
    private static String lookupMsg(Enum<?> key, String fallback) {
        if (RTP.configs == null) return fallback;
        Object v = RTP.configs.getConfigValue(key, fallback);
        return v == null ? fallback : v.toString();
    }
}
