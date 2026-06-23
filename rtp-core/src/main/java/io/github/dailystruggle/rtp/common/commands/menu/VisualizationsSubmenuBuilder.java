package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;
/**
 * Curated submenu reached from the admin panel "Visualizations" row.
 *
 * <p>Two pages are produced by this builder:
 * <ul>
 *   <li>{@link #build(UUID)} - the top-level <b>chart-kind picker</b>: one
 *       row per supported visualization kind (bad-locations, biomes, ...).
 *       Clicking a row runs {@code /rtp visualization &lt;verb&gt;} with no
 *       {@code region=} argument; the visualization leaf's no-region
 *       selector-fallback then lands on {@link #buildRegionList(UUID,
 *       ChartSpec.Kind)} via
 *       {@code MenuRedeemSubcommand.dispatchOpenVisualizationRegions}.</li>
 *   <li>{@link #buildRegionList(UUID, ChartSpec.Kind)} - the kind-scoped
 *       <b>region picker</b>: one row per configured region, each emitting
 *       {@link MenuAction.OpenMap} carrying the kind + region name. Same
 *       per-row shape as the legacy flat submenu, only kind-parameterised.</li>
 * </ul>
 *
 * <p>The admin-permission gate ({@code rtp.menu.admin}) is enforced
 * upstream by the dispatch arm; this builder trusts that contract and
 * does not re-probe it. The back row of the chart-kind picker returns to
 * the admin panel via {@link MenuAction.OpenAdminPanel}; the back row of
 * a kind-scoped region picker returns to the chart-kind picker via
 * {@link MenuAction.OpenVisualizations}.
 */
public final class VisualizationsSubmenuBuilder {

    /** Maximum book lines per page (matches {@link AdminPanelBuilder#LINES_PER_PAGE}). */
    static final int LINES_PER_PAGE = AdminPanelBuilder.LINES_PER_PAGE;

    /**
     * Ordered display table for the chart-kind picker. The {@code verb} is
     * the {@code /rtp visualization &lt;verb&gt;} sub-command name, and the
     * {@code label} / {@code hover} are the human-readable strings shown on
     * the row (book-menu colour contrast: avoid yellow/white per project
     * guidelines).
     */
    private static final Map<ChartSpec.Kind, KindRow> KIND_ROWS;
    static {
        Map<ChartSpec.Kind, KindRow> m = new LinkedHashMap<>();
        m.put(ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE,
                new KindRow("bad-locations",
                        "&1\u25b6 Bad locations &7- per-region shape map",
                        "&7open a map of bad-locations samples per region"));
        m.put(ChartSpec.Kind.REGION_BIOMES,
                new KindRow("biomes",
                        "&2\u25b6 Biomes &7- per-region biome map",
                        "&7open a map of sampled biomes per region"));
        m.put(ChartSpec.Kind.METRIC_SPARKLINE,
                new KindRow("sparkline",
                        "&4\u25b6 Pipeline health &7- MSPT + heap sparkline",
                        "&7open a server-global MSPT and heap sparkline"));
        KIND_ROWS = java.util.Collections.unmodifiableMap(m);
    }

    /**
     * ADR-050 Stage 3β.D.2b (2026-05-24): no-arg constructor. The renderer
     * emits concrete {@code /rtp menu ...} commands, so no token registry or
     * TTL is consulted any more.
     */
    public VisualizationsSubmenuBuilder() {
    }

    /**
     * Build the top-level Visualizations <b>chart-kind picker</b> for
     * {@code viewer}: one row per supported chart kind, each running the
     * corresponding {@code /rtp visualization &lt;verb&gt;} command which
     * lands on the per-kind region picker via the leaf's selector fallback.
     * The back row returns to the admin panel.
     */
    public MenuModel build(UUID viewer) {
        Objects.requireNonNull(viewer, "viewer");

        List<MenuLine> lines = new ArrayList<>();

        String title = lookupMsg(CommandMessages.menuVisualizationsTitle, "&5&l\u2316 Visualizations");
        if (title != null && !title.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(title, null, null)));
        }
        // Hint text deliberately kept generic ("pick a visualization") rather
        // than reading the locale's bad-locations-flavoured menuVisualizationsHint,
        // which still ships the legacy "pick a region to view its bad-locations
        // map" copy; routing that key away from the chart-kind picker would
        // force a locale-TSV pass on every shipped locale (see project
        // guidelines, Locale Config TSV Pipeline section). Hardcoded English
        // fallback is acceptable for an admin diagnostic surface.
        String hint = "&7pick a visualization";
        lines.add(MenuLine.of(new MenuFragment(hint, null, null)));
        // blank spacer
        lines.add(new MenuLine(List.of()));

        for (Map.Entry<ChartSpec.Kind, KindRow> e : KIND_ROWS.entrySet()) {
            KindRow row = e.getValue();
            MenuAction action = new MenuAction.RunRtpCommand(
                    new String[] {"visualization", row.verb});
            lines.add(MenuLine.of(new MenuFragment(row.label, row.hover, action)));
        }

        String backLabel = lookupMsg(CommandMessages.menuVisualizationsRowBack, "&7\u21a9 Back");
        MenuLine backRow = null;
        if (backLabel != null && !backLabel.isEmpty()) {
            backRow = MenuLine.of(new MenuFragment(
                    backLabel, null, new MenuAction.OpenAdminPanel()));
        }

        List<MenuPage> pages = paginate(lines, backRow);
        return new MenuModel(title == null ? "" : title, pages);
    }

    /**
     * Build the kind-scoped Visualizations <b>region picker</b> for
     * {@code viewer}: one row per configured region, each emitting
     * {@link MenuAction.OpenMap} carrying {@code (kind, regionName)}. Reads
     * the live region list from {@code RTP.selectionAPI.regionNames()} on
     * every call (post-write rebuild contract). The back row returns to the
     * top-level chart-kind picker via {@link MenuAction.OpenVisualizations}.
     *
     * @param viewer the clicking player's UUID.
     * @param kind   the chart-kind scope; the only valid values today are
     *               {@link ChartSpec.Kind#REGION_BAD_LOCATIONS_SHAPE} and
     *               {@link ChartSpec.Kind#REGION_BIOMES}. Other kinds throw
     *               {@link IllegalArgumentException} (caught by the dispatch
     *               arm as a builder failure -> S-004 reject).
     */
    public MenuModel buildRegionList(UUID viewer, ChartSpec.Kind kind) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(kind, "kind");
        KindRow kindRow = KIND_ROWS.get(kind);
        if (kindRow == null) {
            throw new IllegalArgumentException(
                    "VisualizationsSubmenuBuilder.buildRegionList: unsupported kind " + kind);
        }

        List<MenuLine> lines = new ArrayList<>();

        String title = lookupMsg(CommandMessages.menuVisualizationsTitle, "&5&l\u2316 Visualizations");
        if (title != null && !title.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(title, null, null)));
        }
        // Kind-scoped subhead (hardcoded copy; see hint comment in build()).
        String subhead = "&7" + kindRow.shortName() + " &8- pick a region";
        lines.add(MenuLine.of(new MenuFragment(subhead, null, null)));
        lines.add(new MenuLine(List.of()));

        List<String> regionNames = collectRegionNames();
        if (regionNames.isEmpty()) {
            String empty = lookupMsg(
                    CommandMessages.menuVisualizationsEmpty, "&7(no regions configured)");
            if (empty != null && !empty.isEmpty()) {
                lines.add(MenuLine.of(new MenuFragment(empty, null, null)));
            }
        } else {
            String rowTemplate = lookupMsg(
                    CommandMessages.menuVisualizationsRowRegion,
                    "&b\u25b6 [region] &7- region shape map");
            String hoverTemplate = lookupMsg(
                    CommandMessages.menuVisualizationsHoverRegion,
                    "&7open a map showing bad locations in &b[region]");
            for (String regionName : regionNames) {
                MenuAction action = new MenuAction.OpenMap(kind, regionName);
                String label = rowTemplate.replace("[region]", regionName);
                String hover = hoverTemplate == null || hoverTemplate.isEmpty()
                        ? null
                        : hoverTemplate.replace("[region]", regionName);
                lines.add(MenuLine.of(new MenuFragment(label, hover, action)));
            }
        }

        // Back row -> top-level chart-kind picker (one level up).
        String backLabel = lookupMsg(CommandMessages.menuVisualizationsRowBack, "&7\u21a9 Back");
        MenuLine backRow = null;
        if (backLabel != null && !backLabel.isEmpty()) {
            backRow = MenuLine.of(new MenuFragment(
                    backLabel, null, new MenuAction.OpenVisualizations()));
        }

        List<MenuPage> pages = paginate(lines, backRow);
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
    private static String lookupMsg(Enum<?> key, String fallback) {
        if (RTP.configs == null) return fallback;
        Object v = RTP.configs.getConfigValue(key, fallback);
        String s = v == null ? fallback : v.toString();
        return s;
    }

    /**
     * Per-kind chart-kind picker row metadata. {@code verb} is the
     * {@code /rtp visualization &lt;verb&gt;} sub-command name; the label
     * and hover use book-safe (non-yellow, non-white) colours per project
     * guidelines.
     */
    private record KindRow(String verb, String label, String hover) {
        /**
         * Short human-readable name used for the kind-scoped subhead on the
         * region picker. Derived from {@link #label} by stripping the
         * leading colour code and arrow, and truncating at the dash.
         */
        String shortName() {
            String s = label;
            // strip leading "&X" or "&l" / "&l" pairs and the bullet
            while (s.length() >= 2 && s.charAt(0) == '&') {
                s = s.substring(2);
            }
            int dash = s.indexOf(" &");
            if (dash > 0) s = s.substring(0, dash);
            int arrow = s.indexOf('\u25b6');
            if (arrow >= 0) s = s.substring(arrow + 1);
            return s.trim();
        }
    }
}
