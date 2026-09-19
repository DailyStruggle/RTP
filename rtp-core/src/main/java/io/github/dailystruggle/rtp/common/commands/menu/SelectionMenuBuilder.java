package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
/**
 * Reusable "pick one of these" selection page builder for curated choice menus.
 * Renders a header, back row, and color-coded rows staging {@code paramName=value} on click.
 */
public final class SelectionMenuBuilder {

    /**
     * Build the selection page.
     *
     * @param parentPath     assembled path from {@code /rtp} to parent page
     * @param paramName      parameter the selected value is staged under
     * @param displayName    label for header
     * @param entries        values to list
     * @param colorSupplier  maps entry to color prefix (null defaults to &2)
     * @param executeOnClick when true, entry row dispatches command immediately
     * @return assembled {@link MenuModel}
     */
    public MenuModel build(List<String> parentPath,
                           String paramName,
                           String displayName,
                           Collection<String> entries,
                           Function<String, String> colorSupplier,
                           boolean executeOnClick) {
        return build(parentPath, paramName, displayName, entries, colorSupplier,
                executeOnClick, null);
    }

    /**
     * Build selection page with explicit Back-row destination.
     *
     * @param backAction action Back row dispatches (defaults to re-opening parentPath when null)
     */
    public MenuModel build(List<String> parentPath,
                           String paramName,
                           String displayName,
                           Collection<String> entries,
                           Function<String, String> colorSupplier,
                           boolean executeOnClick,
                           MenuAction backAction) {
        String[] parentPathArr = parentPath.toArray(new String[0]);

        MenuAction back = (backAction != null)
                ? backAction
                : new MenuAction.OpenMenu(parentPathArr);
        MenuLine backLine = MenuLine.of(new MenuFragment(
                lookupMsg(CommandMessages.menuBack, "&7« back"), null,
                back));

        String name = (displayName == null || displayName.isEmpty()) ? paramName : displayName;
        // Bold + uppercased + a leading marker glyph so the header reads as a
        // heading rather than just the first (same-cased) list entry.
        String header = "&1&l\u25B6 PICK A " + name.toUpperCase(java.util.Locale.ROOT);
        MenuLine headerLine = MenuLine.of(new MenuFragment(header, null, null));
        // Blank separator so the header reads as a heading rather than the
        // first list entry (parchment book contrast: avoid &e/&f/&a).
        MenuLine spacerLine = MenuLine.of(new MenuFragment(" ", null, null));

        List<MenuLine> valueLines = new ArrayList<>();
        if (entries != null) {
            List<String> sorted = new ArrayList<>(entries);
            Collections.sort(sorted);
            for (String value : sorted) {
                if (value == null) continue;
                String[] openArgs = new String[parentPath.size() + 1];
                for (int i = 0; i < parentPath.size(); i++) {
                    openArgs[i] = parentPath.get(i);
                }
                openArgs[parentPath.size()] = paramName + "=" + value;
                String prefix = "&2";
                if (colorSupplier != null) {
                    try {
                        String c = colorSupplier.apply(value);
                        if (c != null && !c.isEmpty()) prefix = c;
                    } catch (RuntimeException ignored) {
                        // Cold map/biome data or mid-reload: keep the neutral
                        // parchment-safe green rather than failing the page.
                    }
                }
                MenuAction action = executeOnClick
                        ? new MenuAction.RunRtpCommand(openArgs)
                        : new MenuAction.OpenMenu(openArgs);
                valueLines.add(MenuLine.of(new MenuFragment(prefix + value, null,
                        action)));
            }
        }

        final int valuesPerPage = CommandTreeMenuBuilder.PICKER_VALUES_PER_PAGE;
        int totalPages = valueLines.isEmpty()
                ? 1
                : (valueLines.size() + valuesPerPage - 1) / valuesPerPage;
        String prevTmpl = lookupMsg(CommandMessages.menuPagePrev, "&7« previous page ([page])");
        String nextTmpl = lookupMsg(CommandMessages.menuPageNext, "&7next page ([page]) »");
        List<MenuPage> pages = new ArrayList<>(totalPages);
        for (int p = 0; p < totalPages; p++) {
            List<MenuLine> pageLines = new ArrayList<>();
            pageLines.add(backLine);
            pageLines.add(headerLine);
            pageLines.add(spacerLine);
            int from = p * valuesPerPage;
            int to = Math.min(from + valuesPerPage, valueLines.size());
            for (int i = from; i < to; i++) {
                pageLines.add(valueLines.get(i));
            }
            if (p > 0) {
                pageLines.add(MenuLine.of(new MenuFragment(
                        prevTmpl.replace("[page]", Integer.toString(p)), null,
                        new MenuAction.ChangePage(p - 1))));
            }
            if (p < totalPages - 1) {
                pageLines.add(MenuLine.of(new MenuFragment(
                        nextTmpl.replace("[page]", Integer.toString(p + 2)), null,
                        new MenuAction.ChangePage(p + 1))));
            }
            pages.add(new MenuPage(pageLines));
        }

        String title = String.join(" ", parentPath);
        title = (title.isEmpty() ? "" : title + " ") + paramName;
        return new MenuModel(title, pages);
    }

    @SuppressWarnings("unchecked")
    private static String lookupMsg(Enum<?> key, String fallback) {
        if (RTP.configs == null) return fallback;
        Object v = RTP.configs.getConfigValue(key, fallback);
        return v == null ? fallback : v.toString();
    }
}
