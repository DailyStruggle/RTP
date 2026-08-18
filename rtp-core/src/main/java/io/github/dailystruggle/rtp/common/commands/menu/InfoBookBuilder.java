package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.mapsapi.noop.NoopMapBinding;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.info.InfoCmd;
import io.github.dailystruggle.rtp.common.commands.maps.MapDispatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
/**
 * Curated book-mode builder for {@code /rtp info}. Produces a {@link MenuModel}
 * mirroring chat output line-for-line via {@link RTP#messageTap} (ThreadLocal sink).
 */
public final class InfoBookBuilder {

    /** Lines per book page. Fits vanilla book viewport with chrome headroom. */
    public static final int LINES_PER_PAGE = 13;

    /** No-arg constructor. Concrete commands emitted directly by renderer. */
    public InfoBookBuilder() {
    }

    /**
     * Builds the {@code /rtp info} book model for {@code viewer} at {@code scope}.
     */
    public MenuModel build(TreeCommand rtpRoot, UUID viewer, MenuAction.InfoScopeToken scope) {
        Objects.requireNonNull(rtpRoot, "rtpRoot");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(scope, "scope");

        // 1) Capture lines via the message tap.
        List<String> capturedLines = new ArrayList<>();
        Map<String, List<String>> parameterValues = scopeToParameters(scope);
        InfoCmd infoCmd = findInfoCmd(rtpRoot);

        // Install the tap, run the command body, remove the tap. The tap is a
        // ThreadLocal so it does not leak to other invocations on this thread:
        // the finally block clears it even if InfoCmd throws.
        RTP.messageTap.set(capturedLines::add);
        try {
            if (infoCmd != null) {
                infoCmd.onCommand(viewer, parameterValues, null);
            } else {
                // InfoCmd unregistered (defensive: a build profile without it
                // should still produce a usable book rather than a crash).
                capturedLines.add(lookupMsg(CommandMessages.menuInvalid, "&c/rtp info is unavailable"));
            }
        } finally {
            RTP.messageTap.remove();
        }

        // 2) Paginate.
        List<MenuPage> pages = paginate(capturedLines);

        // 3) Append a footer to the last page with Refresh / Switch-to-chat /
        //    auto-refresh-deferred note + mint tokens.
        pages = appendFooter(pages, viewer, scope);

        // Title: reuse infoTitle so the book header matches the chat header
        // operators already recognise from console.
        String title = lookupMsg(CommandMessages.infoTitle, "&6/rtp info");
        return new MenuModel(title == null ? "" : title, pages);
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Maps a {@link MenuAction.InfoScopeToken} into the {@code parameterValues}
     * map shape that {@link InfoCmd#onCommand} expects. {@link MenuAction.InfoScopeToken.Kind#GLOBAL}
     * yields an empty map; {@code WORLD} / {@code REGION} yield a singleton
     * {@code {"world": [name]}} / {@code {"region": [name]}}.
     */
    static Map<String, List<String>> scopeToParameters(MenuAction.InfoScopeToken scope) {
        switch (scope.kind()) {
            case GLOBAL:
                return Collections.emptyMap();
            case WORLD: {
                Map<String, List<String>> m = new HashMap<>();
                m.put("world", List.of(scope.name()));
                return m;
            }
            case REGION: {
                Map<String, List<String>> m = new HashMap<>();
                m.put("region", List.of(scope.name()));
                return m;
            }
            default:
                throw new IllegalStateException("unknown scope kind: " + scope.kind());
        }
    }

    /**
     * Splits captured lines into book pages of at most {@link #LINES_PER_PAGE}
     * lines each. A captured empty line ({@code ""}) is preserved as a blank
     * {@link MenuLine} so vertical structure matches the chat output.
     */
    static List<MenuPage> paginate(List<String> lines) {
        if (lines.isEmpty()) {
            // Render a single empty page rather than throwing: MenuModel
            // requires at least one page, and the footer pass will add the
            // Refresh / Switch-to-chat rows to it.
            return new ArrayList<>(List.of(new MenuPage(new ArrayList<>())));
        }
        List<MenuPage> pages = new ArrayList<>();
        List<MenuLine> current = new ArrayList<>();
        for (String raw : lines) {
            if (raw == null) continue;
            if (current.size() >= LINES_PER_PAGE) {
                pages.add(new MenuPage(current));
                current = new ArrayList<>();
            }
            if (raw.isEmpty()) {
                current.add(new MenuLine(List.of()));
            } else {
                current.add(MenuLine.of(new MenuFragment(raw, null, null)));
            }
        }
        if (!current.isEmpty()) {
            pages.add(new MenuPage(current));
        }
        return pages;
    }

    /**
     * Adds the trailing {@code Refresh} / {@code Switch to chat} /
     * auto-refresh-deferred note rows to the last book page (creating a fresh
     * page if the last one is already at the line cap) and mints one token per
     * clickable fragment.
     */
    private List<MenuPage> appendFooter(List<MenuPage> pages,
                                        UUID viewer,
                                        MenuAction.InfoScopeToken scope) {
        List<MenuPage> out = new ArrayList<>(pages);
        if (out.isEmpty()) {
            out.add(new MenuPage(new ArrayList<>()));
        }
        MenuPage last = out.get(out.size() - 1);
        List<MenuLine> lines = new ArrayList<>(last.lines());

        // If the last page is already at the cap, push footer to new page.
        // footerRows accounts for spacer, refresh, switch-to-chat, optional map, and note.
        int footerRows = 5;
        if (lines.size() + footerRows > LINES_PER_PAGE) {
            out.set(out.size() - 1, new MenuPage(lines));
            lines = new ArrayList<>();
        }

        // Blank spacer so the chrome stands apart from the data block.
        if (!lines.isEmpty()) {
            MenuLine prev = lines.get(lines.size() - 1);
            if (prev != null && !prev.fragments().isEmpty()) {
                lines.add(new MenuLine(List.of()));
            }
        }

        // Refresh row.
        String refreshLabel = lookupMsg(CommandMessages.infoBookRefreshRow, "&2\u21bb Refresh");
        String refreshHover = lookupMsg(
                CommandMessages.infoBookRefreshHover,
                "Re-render this page against a fresh metrics snapshot.");
        // ADR-050: renderer emits concrete /rtp menu info commands.
        MenuAction refreshAction = new MenuAction.OpenInfo(scope);
        lines.add(MenuLine.of(new MenuFragment(refreshLabel, refreshHover, refreshAction)));

        // Switch-to-chat row.
        String switchLabel = lookupMsg(
                CommandMessages.infoBookSwitchToTextRow, "&7\u2630 Switch to chat");
        String switchHover = lookupMsg(
                CommandMessages.infoBookSwitchToTextHover,
                "Re-run /rtp info in chat instead of the book.");
        MenuAction switchAction = new MenuAction.SwitchInfoToText(scope);
        lines.add(MenuLine.of(new MenuFragment(switchLabel, switchHover, switchAction)));

        // ADR-047 / REQ-RTP-MAP-006: bad-points heatmap row for REGION scope when MapBinding active.
        if (scope.kind() == MenuAction.InfoScopeToken.Kind.REGION
                && !(MapDispatch.getMapBinding() instanceof NoopMapBinding)) {
            String mapLabel = lookupMsg(
                    CommandMessages.menuInfoBadPointsLabel,
                    "&b\u2316 View bad-points map");
            if (mapLabel != null && !mapLabel.isEmpty()) {
                // ADR-050 Stage 3β: OpenMap is now (Kind, regionName); no token round-trip.
                MenuAction mapAction = new MenuAction.OpenMap(
                        ChartSpec.Kind.BAD_POINTS_HEATMAP, scope.name());
                lines.add(MenuLine.of(new MenuFragment(mapLabel, null, mapAction)));
            }
        }

        // Non-clickable note row about auto-refresh being deferred. Empty
        // template skips silently so locales without the new key keep
        // working unchanged.
        String note = lookupMsg(
                CommandMessages.infoBookAutoRefreshDeferredNote,
                "&8(auto-refresh not yet supported; click Refresh to update)");
        if (note != null && !note.isEmpty()) {
            lines.add(MenuLine.of(new MenuFragment(note, null, null)));
        }

        out.set(out.size() - 1, new MenuPage(lines));
        return out;
    }

    /**
     * Walks the {@code /rtp} command tree for the {@code info} subcommand.
     * Returns {@code null} if it is not registered (e.g. a custom build
     * profile that excludes diagnostics).
     */
    private static InfoCmd findInfoCmd(TreeCommand rtpRoot) {
        Map<String, CommandsAPICommand> lookup = rtpRoot.getCommandLookup();
        if (lookup == null) return null;
        CommandsAPICommand candidate = lookup.get("info");
        if (candidate == null) candidate = lookup.get("INFO");
        return candidate instanceof InfoCmd info ? info : null;
    }

    @SuppressWarnings("unchecked")
    private static String lookupMsg(Enum<?> key, String fallback) {
        if (RTP.configs == null) return fallback;
        Object v = RTP.configs.getConfigValue(key, fallback);
        return v == null ? fallback : v.toString();
    }

    // Locale fallback constant for the tests that exercise hasSubcommand-style
    // probes. Kept package-private so InfoBookBuilderTest can stay in sync.
    static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }
}
