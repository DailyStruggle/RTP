package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
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
 * Stage B — curated front-page builder for {@code /rtp menu} (no args).
 *
 * <p>Unlike {@link CommandTreeMenuBuilder}, which reflects the live
 * {@code commands-api} tree into a flat list of subcommand rows, this builder
 * produces a curated landing page with permission-gated, status-aware rows
 * representing the most common entry points for players and administrators.
 * See {@code docs/dev/scratch/CHECKLIST-menu-navigation.md} Stage B for the
 * approved row catalogue.
 *
 * <p>Every viewer sees the player picker rows (region / world / biome); a
 * viewer holding the {@code rtp.menu.admin} permission additionally gets a
 * single {@code Admin panel} entry row that descends into the dedicated
 * admin submenu. Rows are dropped silently when their underlying subtree or
 * parameter is not registered on the live {@code /rtp} tree, so the page
 * stays clean rather than rendering broken {@code OpenMenu} /
 * {@code OpenParamPicker} actions.
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

    /**
     * Permission gating the curated "config editor" row on the admin
     * front page (proposal v3.7.1, checklist step 7). When the viewer
     * holds this permission, the row dispatches an
     * {@link MenuAction.OpenConfigSelector} that opens the curated
     * config selector page; when missing, the row is hidden entirely
     * (not greyed) so the page stays clean.
     */
    public static final String CONFIG_VIEW_PERMISSION = "rtp.config.view";

    /**
     * ADR-050 Stage 3β.D.2b (2026-05-24): no-arg constructor. The renderer
     * emits concrete {@code /rtp menu ...} commands, so no token registry or
     * TTL is consulted any more.
     */
    public FrontPageBuilder() {
    }

    /**
     * Build the curated front-page {@link MenuModel} for {@code viewer}.
     *
     * @param rtpRoot    the {@code /rtp} root {@link TreeCommand}; used to
     *                   probe for subcommand and parameter availability.
     * @param viewer     the calling player UUID.
     * @param permission permission probe for {@code viewer}. The admin entry
     *                   row is appended in addition to the player picker rows
     *                   when {@code permission.test("rtp.menu.admin")}
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
        // The orientation hint ("click an option below") is intentionally
        // omitted from the curated front page: the per-row labels and the
        // section dividers already make the page self-explanatory, and the
        // extra line only adds clutter. Spacer lines below provide the
        // visual separation it used to imply.

        // Blank spacer between the title and the first section.
        addSpacer(lines);

        // Section divider before the teleport block (always present).
        String teleportSection =
                lookupMsg(MessagesKeys.menuFrontPageSectionTeleport, "&8» #3E6B45 teleport");
        if (teleportSection != null && !teleportSection.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(teleportSection, null, null)));
        }

        // Row 1 — instant teleport (always visible; /rtp itself decides
        // whether the caller can teleport).
        addParsedRow(
                lines,
                lookupMsg(MessagesKeys.menuFrontPageRowTeleport, "#1B7A33🎲 teleport &7- /rtp"),
                new MenuAction.RunRtpCommand(new String[0]));

        // Blank spacer separating the teleport action from the pickers.
        addSpacer(lines);

        // The player picker rows (region / world / biome) are shown to every
        // viewer, including admins: the admin panel is a self-contained submenu
        // reached via a single entry row, not an inline block that competes for
        // the same space, so there is no reason to suppress the pickers for
        // operators. The admin entry row is therefore additive: appended after
        // the player rows when the viewer holds rtp.menu.admin.
        appendPlayerRows(rtpRoot, viewer, permission, lines);
        if (adminView) {
            addSpacer(lines);
            appendAdminRows(lines);
        }

        // Blank spacer before the always-on help footer.
        addSpacer(lines);
        // Help footer — always last, always visible.
        addParsedRow(
                lines,
                lookupMsg(MessagesKeys.menuFrontPageRowHelp, "#7A4FB8❓ list commands &7- /rtp help"),
                new MenuAction.RunRtpCommand(new String[]{"help"}));

        MenuPage page = new MenuPage(lines);
        List<MenuPage> pages = List.of(page);

        // ADR-050 Stage 3α (2026-05-24): pre-mint loop removed. The renderer
        // emits concrete /rtp menu ... commands per fragment action; tokens
        // are no longer consulted. `tokenRegistry` / `tokenTtl` ctor params
        // remain for source/binary compat; Stage 3β drops them outright.
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
            addParsedRow(
                    lines,
                    lookupMsg(MessagesKeys.menuFrontPageRowRegion, "#1F6FB2🌍 pick a region &7- /rtp region:..."),
                    new MenuAction.RunRtpCommand(new String[]{"menu", "region"}));
        }
        CommandParameter worldParam = findParameter(rtpRoot, "world");
        if (worldParam != null
                && parameterPermissionOk(worldParam, permission)
                && parameterHasSuggestions(worldParam, viewer)) {
            addParsedRow(
                    lines,
                    lookupMsg(MessagesKeys.menuFrontPageRowWorld, "#1F6FB2🌐 pick a world &7- /rtp world:..."),
                    new MenuAction.RunRtpCommand(new String[]{"menu", "world"}));
        }
        CommandParameter biomeParam = findParameter(rtpRoot, "biome");
        if (biomeParam != null
                && parameterPermissionOk(biomeParam, permission)
                && parameterHasSuggestions(biomeParam, viewer)) {
            addParsedRow(
                    lines,
                    lookupMsg(MessagesKeys.menuFrontPageRowBiome, "#1F8A40🌳 pick a biome &7- /rtp biome:..."),
                    new MenuAction.RunRtpCommand(new String[]{"menu", "biome"}));
        }
    }

    /**
     * Admin-view rows after the always-on Teleport row
     * (PROPOSAL-admin-panel.md v2). Collapses the previous inline admin block
     * to a single {@code Admin panel} entry row that emits a
     * {@link MenuAction.OpenAdminPanel}. The actual operator-facing rows
     * (Config editor, Server info, Full diagnostics, Memory tracker, Scan
     * control, Reload, Browse) now live on the dedicated admin-panel page
     * built by {@link AdminPanelBuilder}.
     *
     * <p>The previous {@code menuFrontPageSectionAdmin} divider is gone: a
     * single entry row needs no section header, and dropping the divider
     * keeps the front page uniform between admin and non-admin viewers.
     */
    private void appendAdminRows(List<MenuLine> lines) {
        String[] labelHover = splitRowLabel(
                lookupMsg(MessagesKeys.menuFrontPageRowAdmin, "&6⚙ Admin panel"));
        // Prefer the dedicated admin hover message; fall back to the
        // description parsed out of the row label.
        String hover = lookupMsg(
                MessagesKeys.menuFrontPageHoverAdmin,
                "Operator tools: config, scans, diagnostics, reload.");
        if (hover == null || hover.isEmpty()) hover = labelHover[1];
        addRow(lines, labelHover[0], hover, new MenuAction.OpenAdminPanel());
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Splits a curated front-page row message into a clean clickable label
     * and a hover tooltip. The shipped {@code menuFrontPageRow*} messages
     * embed the human explanation as a trailing gray {@code "- <description>"}
     * run and, for the picker rows, a {@code "command:&7..."} placeholder echo
     * (e.g. {@code "&2▶ /rtp region:&7... &7- pick a region"}). Rendering both
     * inline turns the row text into gray clutter; the explanation belongs in
     * the hover tooltip instead. Returns {@code {label, hover}}; {@code hover}
     * is {@code null} when the message carries no {@code "- "} description
     * separator (cleaner translations pass through with the whole message as
     * the label).
     */
    static String[] splitRowLabel(String raw) {
        if (raw == null) return new String[]{null, null};
        String label = raw;
        String hover = null;
        int dashIdx = raw.lastIndexOf("- ");
        if (dashIdx >= 0 && dashIdx + 2 <= raw.length()) {
            String desc = raw.substring(dashIdx + 2).trim();
            if (!desc.isEmpty()) {
                hover = desc;
                label = raw.substring(0, dashIdx);
            }
        }
        // Drop the "command:&7..." placeholder echo from the picker labels.
        label = label.replaceAll(":(?:&[0-9A-Fa-fK-Ok-or]|#[0-9A-Fa-f]{6})*\\.\\.\\.", "");
        // Strip any color code / whitespace left dangling at the end by the split.
        label = stripTrailingFormatting(label);
        if (label.isEmpty()) label = raw;
        return new String[]{label, hover};
    }

    /**
     * Removes trailing whitespace and any trailing legacy ({@code &x}) or hex
     * ({@code #rrggbb}) color codes from {@code s}, repeating until neither is
     * present. Used to tidy a label after its description suffix is split off.
     */
    private static String stripTrailingFormatting(String s) {
        String t = s == null ? "" : s;
        while (true) {
            String trimmed = t.stripTrailing();
            if (trimmed.length() >= 2
                    && trimmed.charAt(trimmed.length() - 2) == '&') {
                t = trimmed.substring(0, trimmed.length() - 2);
                continue;
            }
            if (trimmed.length() >= 7
                    && trimmed.charAt(trimmed.length() - 7) == '#') {
                String tail = trimmed.substring(trimmed.length() - 6);
                boolean hex = tail.chars().allMatch(c -> Character.digit(c, 16) >= 0);
                if (hex) {
                    t = trimmed.substring(0, trimmed.length() - 7);
                    continue;
                }
            }
            return trimmed;
        }
    }

    /**
     * Adds a curated front-page row whose {@code raw} message carries the
     * visible label (topical emoji + human description) first and the
     * {@code /rtp ...} command after a trailing {@code "- "} separator. The
     * label is rendered as the clickable text and the command becomes the
     * hover tooltip, via {@link #splitRowLabel}. When a translation carries no
     * {@code "- "} separator, the whole message becomes the label and there is
     * no command hover.
     */
    private static void addParsedRow(List<MenuLine> lines, String raw, MenuAction action) {
        String[] labelHover = splitRowLabel(raw);
        addRow(lines, labelHover[0], labelHover[1], action);
    }

    private static void addRow(List<MenuLine> lines, String label, String hover, MenuAction action) {
        if (label == null || label.isEmpty()) return;
        lines.add(MenuLine.of(new MenuFragment(label, hover, action)));
    }

    /**
     * Appends a blank spacer line (a {@link MenuLine} with no fragments) used
     * by renderers to introduce vertical separation between logical groups of
     * rows. Skipped when {@code lines} is empty or already ends with a blank
     * spacer, so consecutive calls never stack into double gaps.
     */
    private static void addSpacer(List<MenuLine> lines) {
        if (lines.isEmpty()) return;
        if (lines.get(lines.size() - 1).fragments().isEmpty()) return;
        lines.add(new MenuLine(List.of()));
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
