package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
/**
 * Curated confirmation-menu page builder for Setup-section prefab applications in the admin panel.
 * Renders diff preview with {@code Confirm} and {@code Cancel} actions (ADR-050).
 */
public final class PrefabConfirmationMenuBuilder {

    /**
     * Hard cap on the number of diff lines surfaced per file in the
     * confirmation menu body. Overflow lines are collapsed into a single tail row.
     */
    public static final int MAX_LINES_PER_FILE = 8;

    /**
     * ADR-050: no-arg constructor. Concrete commands emitted without token registry.
     */
    public PrefabConfirmationMenuBuilder() {
    }

    /**
     * Builds the confirmation page displaying the pending diff for {@code prefab}.
     *
     * @param prefab prefab being applied
     * @param diff   per-file diff produced by {@link PrefabApplier#apply}
     * @param viewer calling player UUID
     */
    public MenuModel build(Prefab prefab,
                           Map<String, List<PrefabApplier.Change>> diff,
                           UUID viewer) {
        Objects.requireNonNull(prefab, "prefab");
        Objects.requireNonNull(diff, "diff");
        Objects.requireNonNull(viewer, "viewer");

        List<MenuLine> lines = new ArrayList<>();

        // Title. Per-prefab `displayKey` is a forward-compatibility placeholder
        // for a future Enum<?> entry; until then we fall through to
        // the prefab id as the displayed name.
        String displayName = prefab.id();
        // Book parchment contrast: yellow (&6) and white (&f) wash out on
        // parchment; use dark blue + black per .junie/AGENTS.md 'Book Menu
        // Color Contrast'.
        String title = "&1&l\u2699 confirm prefab: &0" + displayName;
        lines.add(MenuLine.of(new MenuFragment(title, null, null)));

        // Hint.
        lines.add(MenuLine.of(new MenuFragment(
                "&8review the changes below; confirm to write, cancel to abort",
                null, null)));
        lines.add(new MenuLine(List.of()));

        // Per-file diff body.
        if (diff.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(
                    "&8(no changes - identity overlay)", null, null)));
        } else {
            boolean first = true;
            for (Map.Entry<String, List<PrefabApplier.Change>> e : diff.entrySet()) {
                if (!first) {
                    lines.add(new MenuLine(List.of()));
                }
                first = false;
                // Avoid yellow on parchment: use bold dark blue for file headers.
                lines.add(MenuLine.of(new MenuFragment(
                        "&1&l" + e.getKey() + ".yml", null, null)));
                List<PrefabApplier.Change> changes = e.getValue();
                int shown = Math.min(changes.size(), MAX_LINES_PER_FILE);
                for (int i = 0; i < shown; i++) {
                    PrefabApplier.Change c = changes.get(i);
                    // Key path in black (parchment-readable) instead of a wall
                    // of dark-gray; only the structural arrow stays muted.
                    lines.add(MenuLine.of(new MenuFragment(
                            "&0  " + c.keyPath()
                                    + ": &c" + safe(c.oldValue())
                                    + " &8-> &2" + safe(c.newValue()),
                            null, null)));
                }
                if (changes.size() > shown) {
                    lines.add(MenuLine.of(new MenuFragment(
                            "&8  ... (+" + (changes.size() - shown) + " more)",
                            null, null)));
                }
            }
        }

        // Footer rows.
        lines.add(new MenuLine(List.of()));
        addRow(
                lines,
                "&2&l[confirm]",
                null,
                new MenuAction.RunRtpCommand(
                        new String[]{"admin", "prefab", "confirm", "id=" + prefab.id()}));
        addRow(
                lines,
                "&c&l[cancel]",
                null,
                new MenuAction.OpenAdminPanel());

        MenuPage page = new MenuPage(lines);
        List<MenuPage> pages = List.of(page);
        // ADR-050: concrete command actions emitted without tokens.
        return new MenuModel(title, pages);
    }

    // ---- helpers ----------------------------------------------------------

    private static void addRow(List<MenuLine> lines, String label, String hover, MenuAction action) {
        if (label == null || label.isEmpty()) return;
        lines.add(MenuLine.of(new MenuFragment(label, hover, action)));
    }

    private static String safe(Object value) {
        if (value == null) return "(unset)";
        String s = value.toString();
        if (s.length() > 40) {
            return s.substring(0, 37) + "...";
        }
        return s;
    }
}
