package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * A clean, reusable "pick one of these" selection page.
 *
 * <p>Unlike {@link CommandTreeMenuBuilder#buildParamPicker}, which is the
 * generic command-navigation value picker (it shows the assembled command and
 * a "type a custom value" anvil row), this builder renders a single curated
 * list: a clean colored header, a back row, and one color-coded row per entry.
 *
 * <p>The builder is intentionally <em>agnostic</em> of <em>what</em> it
 * displays. The caller (a dedicated menu leaf command such as
 * {@code /rtp menu world} / {@code region} / {@code biome} / {@code prefab})
 * supplies everything that varies:
 * <ul>
 *   <li>the {@code displayName} for the {@code "pick a <displayName>"} header,</li>
 *   <li>the {@code entries} to list (resolved from the owning command's
 *       {@code relevantValues} - the menu never computes suggestions itself),</li>
 *   <li>the {@code colorSupplier} that tints each row.</li>
 * </ul>
 * This keeps the page reusable for any future "list of choices" submenu.
 *
 * <p>The per-entry click stages the choice exactly like the generic picker:
 * it re-opens the parent menu page with {@code paramName=value} appended to
 * the assembled path, so a subsequent Execute row runs the assembled command.
 */
public final class SelectionMenuBuilder {

    /**
     * Build the selection page.
     *
     * @param parentPath    assembled path from {@code /rtp} to the parent page;
     *                      the Back row reopens it and each entry stages onto it
     *                      (an empty path means the {@code /rtp} root / front page)
     * @param paramName     parameter the selected value is staged under
     * @param displayName   human label for the {@code "pick a <displayName>"} header
     * @param entries       the values to list (already the caller's relevant set)
     * @param colorSupplier maps an entry to its {@code &x}/{@code #rrggbb} prefix;
     *                      may be {@code null} (rows default to dark green)
     * @param executeOnClick when {@code true} each entry row <em>runs</em> the
     *                      assembled {@code /rtp <path> paramName=value} command
     *                      directly on click (e.g. a destination selector that
     *                      teleports immediately); when {@code false} the row
     *                      instead re-opens the parent menu page with the choice
     *                      staged, so a subsequent Execute row dispatches it
     *                      (e.g. the prefab apply/confirm flow)
     * @return the assembled {@link MenuModel}
     */
    public MenuModel build(List<String> parentPath,
                           String paramName,
                           String displayName,
                           Collection<String> entries,
                           Function<String, String> colorSupplier,
                           boolean executeOnClick) {
        String[] parentPathArr = parentPath.toArray(new String[0]);

        MenuLine backLine = MenuLine.of(new MenuFragment(
                lookupMsg(MessagesKeys.menuBack, "&7« back"), null,
                new MenuAction.OpenMenu(parentPathArr)));

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
        String prevTmpl = lookupMsg(MessagesKeys.menuPagePrev, "&7« previous page ([page])");
        String nextTmpl = lookupMsg(MessagesKeys.menuPageNext, "&7next page ([page]) »");
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
    private static String lookupMsg(MessagesKeys key, String fallback) {
        if (RTP.configs == null) return fallback;
        ConfigParser<MessagesKeys> lang =
                (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        if (lang == null) return fallback;
        Object v = lang.getConfigValue(key, fallback);
        return v == null ? fallback : v.toString();
    }
}
