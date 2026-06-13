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
 * Curated confirmation-menu page builder rendered after a viewer clicks a
 * Setup-section prefab row in the admin panel (PROPOSAL-admin-panel-prefabs.md
 * v3.1).
 *
 * <p>The bare command surface is independent: {@code /rtp admin prefab apply
 * <id>} mints a per-caller nonce, stashes the precomputed
 * {@link PrefabApplier.Result#perFileDiff()} in the {@code PrefabNonceStore},
 * and surfaces the diff as paginated chat. The Bukkit-family entry point
 * ({@code RTPCmdBukkit}) additionally renders this
 * confirmation page so the viewer can click {@code Confirm} or {@code Cancel}
 * instead of typing the nonce by hand. Both surfaces dispatch the same
 * underlying {@code prefab confirm <id> <token>} verb.
 *
 * <p>Each clickable fragment mints one token (ADR-035 §3) so the book
 * renderer can emit {@code menu:<token>} click payloads.
 *
 * <p>All user-facing strings on this page are currently hardcoded English
 * fallbacks; a future change should mirror them through the locale TSV pipeline (new
 * {@code MessagesKeys} entries {@code menuPrefabConfirmTitle},
 * {@code menuPrefabConfirmHint}, {@code menuPrefabConfirmRow},
 * {@code menuPrefabCancelRow}, plus the 14 per-prefab row/hover keys
 * referenced by {@link Prefab#displayKey()} / {@link Prefab#hoverKey()}).
 */
public final class PrefabConfirmationMenuBuilder {

    /**
     * Hard cap on the number of diff lines surfaced per file in the
     * confirmation menu body. A noisy multi-world prefab with hundreds of
     * synthesised region keys would otherwise push the {@code Confirm} /
     * {@code Cancel} footer off page 1 of the book. Overflow lines are
     * collapsed into a single "{@code ... (+N more)}" tail row.
     */
    public static final int MAX_LINES_PER_FILE = 8;

    /**
     * ADR-050 Stage 3β.D.2b (2026-05-24): no-arg constructor. The renderer
     * emits concrete {@code /rtp menu ...} commands, so no token registry or
     * TTL is consulted any more.
     */
    public PrefabConfirmationMenuBuilder() {
    }

    /**
     * Build the confirmation page for {@code prefab}. The {@code Confirm}
     * row dispatches {@code /rtp admin prefab confirm id=<prefabId>}; the
     * {@code Cancel} row dispatches {@link MenuAction.OpenAdminPanel}. The
     * apply-side pending diff is keyed by {@code (callerId, prefabId)} in
     * {@link io.github.dailystruggle.rtp.common.commands.prefab.PrefabNonceStore};
     * no opaque token is surfaced to the click.
     *
     * @param prefab the prefab whose diff is being confirmed.
     * @param diff   the per-file diff produced by {@link PrefabApplier#apply}.
     *               Keys are file ids ({@code "performance"},
     *               {@code "regions/<id>"}); values are ordered changes.
     * @param viewer the calling player UUID.
     */
    public MenuModel build(Prefab prefab,
                           Map<String, List<PrefabApplier.Change>> diff,
                           UUID viewer) {
        Objects.requireNonNull(prefab, "prefab");
        Objects.requireNonNull(diff, "diff");
        Objects.requireNonNull(viewer, "viewer");

        List<MenuLine> lines = new ArrayList<>();

        // Title. Per-prefab `displayKey` is a forward-compatibility placeholder
        // for a future MessagesKeys entry; until then we fall through to
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
                    lines.add(MenuLine.of(new MenuFragment(
                            "&8  " + c.keyPath()
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
                "&a&l[confirm]",
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
        // ADR-050 Stage 3β.D.2b (2026-05-24): the per-fragment mint loop is
        // gone (the renderer emits concrete `/rtp menu ...` commands; no
        // token is consulted).
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
