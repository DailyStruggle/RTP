package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Curated submenu reached from the admin panel "Visualizations" row. Lists
 * every configured region one-per-row; clicking a row dispatches an
 * {@link MenuAction.OpenMap} carrying
 * {@link ChartSpec.Kind#REGION_BAD_LOCATIONS_SHAPE} + the region name
 * (ADR-050 Stage 3β: no ChartSpec token round-trip), which the
 * {@code dispatchOpenMap} arm in {@code MenuRedeemSubcommand} resolves
 * through {@code RegionBadLocationsShapeResolver} into a cartography
 * map item painted by {@code RegionBadLocationsRenderer}.
 *
 * <p>The admin-permission gate ({@code rtp.menu.admin}) is enforced
 * upstream by the dispatch arm; this builder trusts that contract and
 * does not re-probe it. The back row returns to the admin panel via
 * {@link MenuAction.OpenAdminPanel}.
 */
public final class VisualizationsSubmenuBuilder {

    /** Maximum book lines per page (matches {@link AdminPanelBuilder#LINES_PER_PAGE}). */
    static final int LINES_PER_PAGE = AdminPanelBuilder.LINES_PER_PAGE;

    /**
     * ADR-050 Stage 3β.D.2b (2026-05-24): no-arg constructor. The renderer
     * emits concrete {@code /rtp menu ...} commands, so no token registry or
     * TTL is consulted any more.
     */
    public VisualizationsSubmenuBuilder() {
    }

    /**
     * Build the curated Visualizations submenu {@link MenuModel} for
     * {@code viewer}. Reads the live region list from
     * {@code RTP.selectionAPI.regionNames()} on every call (post-write
     * rebuild contract).
     */
    public MenuModel build(UUID viewer) {
        Objects.requireNonNull(viewer, "viewer");

        List<MenuLine> lines = new ArrayList<>();

        String title = lookupMsg(MessagesKeys.menuVisualizationsTitle, "&5&l\u2316 Visualizations");
        if (title != null && !title.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(title, null, null)));
        }
        String hint = lookupMsg(
                MessagesKeys.menuVisualizationsHint,
                "&7pick a region to view its bad-locations map");
        if (hint != null && !hint.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(hint, null, null)));
        }
        // blank spacer
        lines.add(new MenuLine(List.of()));

        // Per-region rows. Sort the live set so book pagination is stable
        // across calls (matches the operator's expectation that regions
        // listed alphabetically reappear in the same order on refresh).
        List<String> regionNames = collectRegionNames();
        if (regionNames.isEmpty()) {
            String empty = lookupMsg(
                    MessagesKeys.menuVisualizationsEmpty, "&7(no regions configured)");
            if (empty != null && !empty.isEmpty()) {
                lines.add(MenuLine.of(new MenuFragment(empty, null, null)));
            }
        } else {
            String rowTemplate = lookupMsg(
                    MessagesKeys.menuVisualizationsRowRegion,
                    "&b\u25b6 [region] &7- region shape map");
            String hoverTemplate = lookupMsg(
                    MessagesKeys.menuVisualizationsHoverRegion,
                    "&7open a map showing bad locations in &b[region]");
            for (String regionName : regionNames) {
                // ADR-050 Stage 3β: OpenMap is now (Kind, regionName); no token round-trip.
                MenuAction action = new MenuAction.OpenMap(
                        ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE, regionName);
                String label = rowTemplate.replace("[region]", regionName);
                String hover = hoverTemplate == null || hoverTemplate.isEmpty()
                        ? null
                        : hoverTemplate.replace("[region]", regionName);
                lines.add(MenuLine.of(new MenuFragment(label, hover, action)));
            }
        }

        // Back row -> admin panel.
        String backLabel = lookupMsg(MessagesKeys.menuVisualizationsRowBack, "&7\u21a9 Back");
        MenuLine backRow = null;
        if (backLabel != null && !backLabel.isEmpty()) {
            backRow = MenuLine.of(new MenuFragment(
                    backLabel, null, new MenuAction.OpenAdminPanel()));
        }

        List<MenuPage> pages = paginate(lines, backRow);
        // ADR-050 Stage 3β.D.2b (2026-05-24): the per-fragment mint loop is
        // gone (the renderer emits concrete `/rtp menu ...` commands).
        return new MenuModel(title == null ? "" : title, pages);
    }

    private static List<String> collectRegionNames() {
        if (RTP.selectionAPI == null) return List.of();
        try {
            java.util.Set<String> names = RTP.selectionAPI.regionNames();
            if (names == null || names.isEmpty()) return List.of();
            return new ArrayList<>(new TreeSet<>(names));
        } catch (RuntimeException e) {
            RTP.log(java.util.logging.Level.WARNING,
                    "VisualizationsSubmenuBuilder: regionNames lookup failed: " + e.getMessage(),
                    e);
            return List.of();
        }
    }

    /**
     * Split {@code body} into pages of at most {@link #LINES_PER_PAGE}
     * lines and append {@code backRow} to the last page (allocating an
     * extra page if the last body page is already full). Mirrors the
     * shape of {@code AdminPanelBuilder#paginate} but without the
     * orphaned-divider guard since this submenu has at most one section.
     */
    static List<MenuPage> paginate(List<MenuLine> body, MenuLine backRow) {
        List<MenuPage> pages = new ArrayList<>();
        if (body == null) body = List.of();
        int cap = LINES_PER_PAGE;
        List<MenuLine> current = new ArrayList<>();
        for (MenuLine line : body) {
            if (current.size() >= cap) {
                pages.add(new MenuPage(current));
                current = new ArrayList<>();
            }
            current.add(line);
        }
        if (!current.isEmpty()) pages.add(new MenuPage(current));
        if (pages.isEmpty()) pages.add(new MenuPage(new ArrayList<>()));

        if (backRow != null) {
            MenuPage last = pages.get(pages.size() - 1);
            if (last.lines().size() >= cap) {
                pages.add(new MenuPage(List.of(backRow)));
            } else {
                List<MenuLine> updated = new ArrayList<>(last.lines());
                // blank spacer if the previous line is not already blank
                if (!updated.isEmpty()) {
                    MenuLine prev = updated.get(updated.size() - 1);
                    if (prev != null && !prev.fragments().isEmpty()
                            && updated.size() < cap - 1) {
                        updated.add(new MenuLine(List.of()));
                    }
                }
                if (updated.size() < cap) {
                    updated.add(backRow);
                    pages.set(pages.size() - 1, new MenuPage(updated));
                } else {
                    pages.add(new MenuPage(List.of(backRow)));
                }
            }
        }
        return pages;
    }

    @SuppressWarnings({"unchecked", "unused"})
    private static String lookupMsg(MessagesKeys key, String fallback) {
        if (RTP.configs == null) return fallback;
        ConfigParser<MessagesKeys> lang =
                (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        if (lang == null) return fallback;
        Object v = lang.getConfigValue(key, fallback);
        String s = v == null ? fallback : v.toString();
        // Mirror lookupMsg conventions elsewhere: trim and accept the
        // fallback when the live value is blank.
        return s;
    }
}
