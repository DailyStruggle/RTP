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
 * Curated book-mode builder for {@code /rtp info} (PROPOSAL-info-as-book.md
 * section 4.6). Produces a {@link MenuModel} whose pages mirror, line-for-line,
 * the chat output that {@link InfoCmd} would have produced for the same
 * {@code scope} and viewer, paginated into book pages so a player can leaf
 * through the same information that the console reader sees.
 *
 * <p>How the mirror works: this builder installs a
 * {@link RTP#messageTap} {@code ThreadLocal} sink, invokes
 * {@link InfoCmd#onCommand(UUID, Map, CommandsAPICommand)} synchronously, then
 * removes the tap. The {@code InfoCmd} body routes every line that would have
 * gone to {@code RTP.serverAccessor.sendMessage(callerId, ...)} into the sink
 * instead (see {@code InfoCmd.emit}), so the book content is guaranteed to
 * stay in lockstep with the chat path. The chat output is suppressed for the
 * duration of the tap so the player is not double-served.
 *
 * <p>Dynamic refresh: the trailing {@code Refresh} row mints an
 * {@link MenuAction.OpenInfo} token, which routes through
 * {@code MenuRedeemSubcommand.dispatchOpenInfo} back into this builder with a
 * fresh metrics snapshot. Periodic (timer-driven) auto-refresh is deferred to
 * a later milestone (see {@code PROPOSAL-info-as-book.md} section 9 and the
 * matching note row at the bottom of the book).
 *
 * <p>Token minting follows the same pattern as {@link AdminPanelBuilder}: one
 * token per clickable fragment, with the supplied TTL.
 */
public final class InfoBookBuilder {

    /**
     * Lines per book page. Vanilla Minecraft books cap at ~14 lines per page;
     * we use a slightly conservative cap so chrome (page number, hover text)
     * does not overflow. Pagination breaks lines across pages; no line is
     * split mid-content.
     */
    public static final int LINES_PER_PAGE = 13;

    /**
     * ADR-050 Stage 3β.D.2b (2026-05-24): no-arg constructor. The renderer
     * emits concrete {@code /rtp menu info ...} commands, so no token
     * registry or TTL is consulted any more.
     */
    public InfoBookBuilder() {
    }

    /**
     * Build the {@code /rtp info} book for {@code viewer} at {@code scope}.
     * The caller is responsible for enforcing the {@code rtp.info} permission
     * gate upstream (the dispatch arm does this for token-driven invocations).
     *
     * @param rtpRoot the {@code /rtp} root command, forwarded to
     *                {@code InfoCmd.onCommand} as the dispatch context.
     * @param viewer  the calling player UUID.
     * @param scope   what slice of {@code /rtp info} to render (global,
     *                per-world, per-region).
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

        // If the last page is already at the cap, push the footer to a new page.
        // ADR-047 row (bad-points map) is conditional and may add one more line;
        // size for the worst case so an overflow does not push the note off-page.
        int footerRows = 5; // blank spacer + refresh + switch-to-text + (optional map) + note
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
        // ADR-050 Stage 3α: tokenRegistry.mint removed (dead side-effect; renderer emits concrete /rtp menu info commands).
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

        // ADR-047 / REQ-RTP-MAP-006 declarative chart bridge: bad-points
        // heatmap row. Only rendered when (a) a real MapBinding is installed
        // (the NoopMapBinding sentinel means the binding slot is empty, so
        // clicking would only produce the configurable mapBindingMissing
        // message) and (b) the scope is REGION (BadPointsHeatmapResolver
        // needs a region name; the GLOBAL / WORLD scopes have no canonical
        // region to project). The dispatch arm
        // (MenuRedeemSubcommand.dispatchOpenMap) enforces the rtp.menu.admin
        // permission server-side, so the row only acts as a discoverability
        // affordance: a non-admin who somehow clicks it will be denied with
        // the standard menuInvalid path.
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
