package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
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
 * Curated front-page builder for {@code /rtp menu} (no args).
 * Produces permission-gated landing page with entry points for players and admins.
 */
public final class FrontPageBuilder {

    /** Admin-view gate permission. {@code rtp.admin} should imply this in {@code plugin.yml}. */
    public static final String ADMIN_PERMISSION = "rtp.menu.admin";

    /**
     * Permission gating the curated "config editor" row on the admin
     * front page. When the viewer holds this permission, the row dispatches
     * an {@link MenuAction.OpenConfigSelector} that opens the curated
     * config selector page; when missing, the row is hidden entirely
     * (not greyed) so the page stays clean.
     */
    public static final String CONFIG_VIEW_PERMISSION = "rtp.config.view";

    /**
     * ADR-050: no-arg constructor. The renderer emits concrete
     * {@code /rtp menu ...} commands.
     */
    public FrontPageBuilder() {
    }

    /**
     * Build the curated front-page {@link MenuModel} for {@code viewer}.
     *
     * @param rtpRoot {@code /rtp} root {@link TreeCommand} to probe for available subcommands
     * @param viewer calling player UUID
     * @param permission permission check predicate for {@code viewer}
     */
    public MenuModel build(TreeCommand rtpRoot, UUID viewer, Predicate<String> permission) {
        Objects.requireNonNull(rtpRoot, "rtpRoot");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(permission, "permission");

        boolean adminView = safeTest(permission, ADMIN_PERMISSION);

        List<MenuLine> lines = new ArrayList<>();

        // Title + hint (same as the reflector's root page so the two pages
        // share visual chrome).
        String title = lookupMsg(CommandMessages.menuRootTitle, "&6&l⚡ RTP menu");
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
                lookupMsg(CommandMessages.menuFrontPageSectionTeleport, "&8» #3E6B45 teleport");
        if (teleportSection != null && !teleportSection.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(teleportSection, null, null)));
        }

        // Row 1 - instant teleport (always visible; /rtp itself decides
        // whether the caller can teleport).
        addParsedRow(
                lines,
                lookupMsg(CommandMessages.menuFrontPageRowTeleport, "#1B7A33🎲 teleport &7- /rtp"),
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
        // Help footer - always last, always visible.
        addParsedRow(
                lines,
                lookupMsg(CommandMessages.menuFrontPageRowHelp, "#7A4FB8❓ list commands &7- /rtp help"),
                new MenuAction.RunRtpCommand(new String[]{"help"}));

        MenuPage page = new MenuPage(lines);
        List<MenuPage> pages = List.of(page);

        // ADR-050: return MenuModel.
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
                    lookupMsg(CommandMessages.menuFrontPageRowRegion, "#1F6FB2🌍 pick a region &7- /rtp region:..."),
                    new MenuAction.RunRtpCommand(new String[]{"menu", "region"}));
        }
        CommandParameter worldParam = findParameter(rtpRoot, "world");
        if (worldParam != null
                && parameterPermissionOk(worldParam, permission)
                && parameterHasSuggestions(worldParam, viewer)) {
            addParsedRow(
                    lines,
                    lookupMsg(CommandMessages.menuFrontPageRowWorld, "#1F6FB2🌐 pick a world &7- /rtp world:..."),
                    new MenuAction.RunRtpCommand(new String[]{"menu", "world"}));
        }
        CommandParameter biomeParam = findParameter(rtpRoot, "biome");
        if (biomeParam != null
                && parameterPermissionOk(biomeParam, permission)
                && parameterHasSuggestions(biomeParam, viewer)) {
            addParsedRow(
                    lines,
                    lookupMsg(CommandMessages.menuFrontPageRowBiome, "#1F8A40🌳 pick a biome &7- /rtp biome:..."),
                    new MenuAction.RunRtpCommand(new String[]{"menu", "biome"}));
        }
    }

    /**
     * Admin-view rows after the Teleport row.
     * Appends a single {@code Admin panel} row emitting {@link MenuAction.OpenAdminPanel}.
     */
    private void appendAdminRows(List<MenuLine> lines) {
        String[] labelHover = splitRowLabel(
                lookupMsg(CommandMessages.menuFrontPageRowAdmin, "&6⚙ Admin panel"));
        // Prefer the dedicated admin hover message; fall back to the
        // description parsed out of the row label.
        String hover = lookupMsg(
                CommandMessages.menuFrontPageHoverAdmin,
                "Operator tools: config, scans, diagnostics, reload.");
        if (hover == null || hover.isEmpty()) hover = labelHover[1];
        addRow(lines, labelHover[0], hover, new MenuAction.OpenAdminPanel());
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Splits row message into a clean clickable label and hover tooltip.
     * Separates trailing description at {@code "- "} into hover tooltip.
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
     * Adds a curated front-page row whose {@code raw} message carries visible label
     * and optional command tooltip separated by {@code "- "}.
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
    private static String lookupMsg(Enum<?> key, String fallback) {
        if (RTP.configs == null) return fallback;
        Object v = RTP.configs.getConfigValue(key, fallback);
        return v == null ? fallback : v.toString();
    }
}
