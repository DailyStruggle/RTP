package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuTokenRegistry;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Stage B — curated front-page builder for {@code /rtp menu} (no args).
 *
 * <p>Unlike {@link CommandTreeMenuBuilder}, which reflects the live
 * {@code commands-api} tree into a flat list of subcommand rows, this builder
 * produces a curated landing page with permission-gated, status-aware rows
 * representing the most common entry points for players and administrators.
 * See {@code docs/dev/scratch/CHECKLIST-menu-navigation.md} Stage B for the
 * approved row catalogue.
 *
 * <p>The builder selects between two row catalogues based on whether the
 * viewer has the {@code rtp.menu.admin} permission (the admin gate). Rows
 * are dropped silently when their underlying subtree or parameter is not
 * registered on the live {@code /rtp} tree, so the page stays clean rather
 * than rendering broken {@code OpenMenu} / {@code OpenParamPicker} actions.
 *
 * <p>Architecture notes:
 * <ul>
 *   <li>This builder lives in {@code rtp-core}; the curated catalogue is
 *       platform-agnostic. Platform modules wire it via the existing
 *       {@code MenuRedeemSubcommand.MenuPageBuilder} SAM by branching on
 *       {@code node == rtpRoot && assembledPath.isEmpty()}.</li>
 *   <li>One token is minted per clickable fragment, mirroring
 *       {@link CommandTreeMenuBuilder}'s contract so renderers can carry
 *       opaque {@code menu:<token>} payloads (ADR-035 §3).</li>
 *   <li>Title and hint rows reuse {@link MessagesKeys#menuRootTitle} /
 *       {@link MessagesKeys#menuRootHint} from Stage A.5, keeping visual
 *       consistency with the reflector page when an admin disables Stage B.</li>
 * </ul>
 */
public final class FrontPageBuilder {

    /** Admin-view gate permission. {@code rtp.admin} should imply this in {@code plugin.yml}. */
    public static final String ADMIN_PERMISSION = "rtp.menu.admin";

    /** Token TTL aligned with {@link CommandTreeMenuBuilder#DEFAULT_TOKEN_TTL}. */
    public static final Duration DEFAULT_TOKEN_TTL = CommandTreeMenuBuilder.DEFAULT_TOKEN_TTL;

    private final MenuTokenRegistry tokenRegistry;
    private final Duration tokenTtl;

    public FrontPageBuilder(MenuTokenRegistry tokenRegistry) {
        this(tokenRegistry, DEFAULT_TOKEN_TTL);
    }

    public FrontPageBuilder(MenuTokenRegistry tokenRegistry, Duration tokenTtl) {
        this.tokenRegistry = Objects.requireNonNull(tokenRegistry, "tokenRegistry");
        this.tokenTtl = Objects.requireNonNull(tokenTtl, "tokenTtl");
    }

    /**
     * Build the curated front-page {@link MenuModel} for {@code viewer}.
     *
     * @param rtpRoot    the {@code /rtp} root {@link TreeCommand}; used to
     *                   probe for subcommand and parameter availability.
     * @param viewer     the calling player UUID.
     * @param permission permission probe for {@code viewer}. The admin row
     *                   set is selected when {@code permission.test("rtp.menu.admin")}
     *                   returns {@code true}.
     */
    public MenuModel build(TreeCommand rtpRoot, UUID viewer, Predicate<String> permission) {
        Objects.requireNonNull(rtpRoot, "rtpRoot");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(permission, "permission");

        boolean adminView = safeTest(permission, ADMIN_PERMISSION);

        List<MenuLine> lines = new ArrayList<>();

        // Title + hint (same as the reflector's root page so the two pages
        // share visual chrome).
        String title = lookupMsg(MessagesKeys.menuRootTitle, "&6&l⚡ RTP menu");
        if (title != null && !title.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(title, null, null)));
        }
        String hint = lookupMsg(MessagesKeys.menuRootHint, "&7click an option below to begin");
        if (hint != null && !hint.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(hint, null, null)));
        }

        // Section divider before the teleport block (always present).
        String teleportSection =
                lookupMsg(MessagesKeys.menuFrontPageSectionTeleport, "── Teleport ──");
        if (teleportSection != null && !teleportSection.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(teleportSection, null, null)));
        }

        // Row 1 — instant teleport (always visible; /rtp itself decides
        // whether the caller can teleport).
        addRow(
                lines,
                lookupMsg(MessagesKeys.menuFrontPageRowTeleport, "🎲 Teleport me now"),
                null,
                new MenuAction.RunRtpCommand(new String[0]));

        if (adminView) {
            appendAdminRows(rtpRoot, lines);
        } else {
            appendPlayerRows(rtpRoot, viewer, permission, lines);
        }

        // Help footer — always last, always visible.
        addRow(
                lines,
                lookupMsg(MessagesKeys.menuFrontPageRowHelp, "❓ Help"),
                null,
                new MenuAction.RunRtpCommand(new String[]{"help"}));

        MenuPage page = new MenuPage(lines);
        List<MenuPage> pages = List.of(page);

        // Mint a token per clickable fragment so the renderer can emit
        // menu:<token> click payloads (ADR-035 §3).
        for (MenuLine line : page.lines()) {
            for (MenuFragment fragment : line.fragments()) {
                MenuAction action = fragment.action();
                if (action != null) {
                    tokenRegistry.mint(viewer, action, tokenTtl);
                }
            }
        }

        return new MenuModel(title == null ? "" : title, pages);
    }

    /**
     * Player-view rows after the always-on Teleport row. Region / biome
     * picker rows appear only when the {@code region} / {@code biome}
     * parameter exists on the root and the viewer has at least one
     * suggestable value (so a permission-less viewer doesn't see empty
     * picker pages).
     */
    private void appendPlayerRows(TreeCommand rtpRoot,
                                  UUID viewer,
                                  Predicate<String> permission,
                                  List<MenuLine> lines) {
        CommandParameter regionParam = findParameter(rtpRoot, "region");
        if (regionParam != null
                && parameterPermissionOk(regionParam, permission)
                && parameterHasSuggestions(regionParam, viewer)) {
            addRow(
                    lines,
                    lookupMsg(MessagesKeys.menuFrontPageRowRegion, "🌍 Pick a region"),
                    null,
                    new MenuAction.OpenParamPicker(new String[0], "region"));
        }
        CommandParameter biomeParam = findParameter(rtpRoot, "biome");
        if (biomeParam != null
                && parameterPermissionOk(biomeParam, permission)
                && parameterHasSuggestions(biomeParam, viewer)) {
            addRow(
                    lines,
                    lookupMsg(MessagesKeys.menuFrontPageRowBiome, "🌳 Pick a biome"),
                    null,
                    new MenuAction.OpenParamPicker(new String[0], "biome"));
        }
    }

    /**
     * Admin-view rows after the always-on Teleport row. Each row drops
     * silently if its target subtree is not registered on the live tree
     * (the {@code OpenMenu} would otherwise navigate to a missing node).
     */
    private void appendAdminRows(TreeCommand rtpRoot, List<MenuLine> lines) {
        String adminSection =
                lookupMsg(MessagesKeys.menuFrontPageSectionAdmin, "── Administration ──");
        if (adminSection != null && !adminSection.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(adminSection, null, null)));
        }

        // Info — always shipped on /rtp today; safe to assume RunRtpCommand
        // dispatches cleanly.
        if (hasSubcommand(rtpRoot, "info")) {
            addRow(
                    lines,
                    lookupMsg(MessagesKeys.menuFrontPageRowInfo, "📋 Server info"),
                    null,
                    new MenuAction.RunRtpCommand(new String[]{"info"}));
        }

        // Config — descend into config sub-tree (paginated reflector).
        if (hasSubcommand(rtpRoot, "config")) {
            addRow(
                    lines,
                    lookupMsg(MessagesKeys.menuFrontPageRowConfig, "⚙ Config editor"),
                    null,
                    new MenuAction.OpenMenu(new String[]{"config"}));
        }

        // Scan — descend.
        if (hasSubcommand(rtpRoot, "scan")) {
            addRow(
                    lines,
                    lookupMsg(MessagesKeys.menuFrontPageRowScan, "🔄 Scan control"),
                    null,
                    new MenuAction.OpenMenu(new String[]{"scan"}));
        }

        // Full diagnostics — direct run with a warning hover.
        if (hasSubcommand(rtpRoot, "test")) {
            addRow(
                    lines,
                    lookupMsg(
                            MessagesKeys.menuFrontPageRowDiagnostics, "🔍 Full diagnostics"),
                    lookupMsg(
                            MessagesKeys.menuFrontPageHoverDiagnostics,
                            "Runs the full diagnostic suite."),
                    new MenuAction.RunRtpCommand(new String[]{"test", "full"}));
        }

        // Reload — destructive, perm-gated by rtp.reload already; hover warns.
        if (hasSubcommand(rtpRoot, "reload")) {
            addRow(
                    lines,
                    lookupMsg(MessagesKeys.menuFrontPageRowReload, "⚠ Reload"),
                    lookupMsg(
                            MessagesKeys.menuFrontPageHoverReload,
                            "Reloads all config files."),
                    new MenuAction.RunRtpCommand(new String[]{"reload"}));
        }
    }

    // ---- helpers ----------------------------------------------------------

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
                    "front-page permission probe threw for '" + perm + "': " + e.getMessage(), e);
            return false;
        }
    }

    private static boolean hasSubcommand(TreeCommand root, String name) {
        Map<String, CommandsAPICommand> lookup = root.getCommandLookup();
        if (lookup == null || name == null) return false;
        return lookup.containsKey(name)
                || lookup.containsKey(name.toUpperCase(Locale.ROOT));
    }

    private static CommandParameter findParameter(TreeCommand root, String name) {
        Map<String, CommandParameter> lookup = root.getParameterLookup();
        if (lookup == null || name == null) return null;
        CommandParameter direct = lookup.get(name);
        if (direct != null) return direct;
        return lookup.get(name.toUpperCase(Locale.ROOT));
    }

    private static boolean parameterPermissionOk(CommandParameter param,
                                                 Predicate<String> permission) {
        String perm = param.permission();
        if (perm == null || perm.isEmpty()) return true;
        return safeTest(permission, perm);
    }

    /**
     * Whether {@code param} would surface at least one row to {@code viewer}
     * in the picker. Used as a visibility predicate so the front page hides
     * picker entry rows that would open onto an empty picker page. Isolated
     * in try/catch because parameter implementations may throw (e.g. when
     * upstream state is mid-reload).
     */
    private static boolean parameterHasSuggestions(CommandParameter param, UUID viewer) {
        try {
            java.util.Set<String> rel = param.relevantValues(viewer);
            return rel != null && !rel.isEmpty();
        } catch (RuntimeException e) {
            RTP.log(java.util.logging.Level.WARNING,
                    "front-page suggestion probe threw: " + e.getMessage(), e);
            return false;
        }
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
