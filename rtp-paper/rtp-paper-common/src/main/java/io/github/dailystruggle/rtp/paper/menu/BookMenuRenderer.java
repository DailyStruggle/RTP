package io.github.dailystruggle.rtp.paper.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Adventure-{@code Book} renderer for {@link MenuModel} (ADR-035 §Renderers
 * + ADR-044). Shared between Paper and Folia — Adventure's {@code Book} API
 * is identical on both platforms; Folia inherits {@link Player#openBook(Book)}
 * unchanged.
 *
 * <p><b>Click round-trip (ADR-050).</b> Each {@link MenuAction} variant
 * maps to a concrete, self-documenting {@code /rtp ...} command emitted
 * verbatim into the click event. Permission gating remains the security
 * boundary (enforced server-side in the matching {@code dispatch*} helper
 * on {@code MenuRedeemSubcommand}). {@link MenuAction.ChangePage} maps to
 * {@link ClickEvent#changePage(int)} (Adventure pages are 1-based; the
 * model is 0-based, so we add 1). {@link MenuAction.SuggestInput} maps to
 * {@link ClickEvent#suggestCommand(String)}. {@link MenuAction.OpenExternalUrl}
 * maps to {@link ClickEvent#openUrl(String)}.
 *
 * <p><b>S-006.</b> Calling {@link #render} when the target player is offline
 * (i.e. {@code Bukkit.getPlayer(uuid) == null}) throws
 * {@link IllegalStateException} rather than silently no-opping, as required
 * for any API entry point that can be invoked before/around core lifecycle
 * events.
 *
 * <p><b>Threading.</b> Adventure {@code Component} construction is thread-safe
 * and pure. {@link Player#openBook(Book)} must be called on a thread that owns
 * the target entity; this renderer assumes the caller routes through the
 * command pipeline (already on the correct region thread under Folia). A
 * Stage-5 follow-up may add a Folia entity-scheduler hop if non-command
 * callers need it.
 */
public final class BookMenuRenderer implements MenuRenderer {

    /** Looks up an online {@link Player} by UUID. Indirected for testability. */
    private final BiFunction<UUID, MenuModel, Player> playerLookup;

    /**
     * ADR-050 Stage 3β.D.2b (2026-05-24): no-arg constructor. The renderer
     * emits concrete {@code /rtp menu ...} commands; no token registry or
     * TTL is consulted any more.
     */
    public BookMenuRenderer() {
        this((uuid, model) -> Bukkit.getPlayer(uuid));
    }

    /**
     * Test / wire-up constructor.
     *
     * @param playerLookup resolves the target {@link Player} from a UUID; the
     *                     {@link MenuModel} parameter is passed through unused
     *                     so tests can stub without spinning up MockBukkit.
     */
    public BookMenuRenderer(BiFunction<UUID, MenuModel, Player> playerLookup) {
        this.playerLookup = Objects.requireNonNull(playerLookup, "playerLookup");
    }

    @Override
    public void render(UUID playerId, MenuModel model) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(model, "model");

        Book book = buildBook(playerId, model);

        Player player = playerLookup.apply(playerId, model);
        if (player == null) {
            // S-006: never silent no-op for a missing player. The caller is
            // expected to translate this into a configurable failure message
            // (menuUnknownPlayer / menuInvalid), not the renderer itself —
            // the renderer is platform-only.
            throw new IllegalStateException(
                    "BookMenuRenderer.render: no online player for UUID " + playerId);
        }
        player.openBook(book);
    }

    /**
     * Pure conversion: {@link MenuModel} → Adventure {@link Book}. Visible for
     * unit testing.
     *
     * @param playerId viewer UUID; bound into every minted redeem token.
     * @param model    menu to translate.
     * @return a fully-populated {@link Book} ready for {@link Player#openBook}.
     */
    public Book buildBook(UUID playerId, MenuModel model) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(model, "model");

        List<Component> pages = new ArrayList<>(model.pages().size());
        for (MenuPage page : model.pages()) {
            pages.add(renderPage(playerId, page));
        }
        OfflinePlayer viewer = resolveViewer(playerId);
        return Book.book(
                toComponent(viewer, model.title()),
                Component.text("RTP"),
                pages);
    }

    private Component renderPage(UUID playerId, MenuPage page) {
        List<MenuLine> lines = page.lines();
        if (lines.isEmpty()) {
            return Component.empty();
        }
        Component out = renderLine(playerId, lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            out = out.append(Component.newline())
                    .append(renderLine(playerId, lines.get(i)));
        }
        return out;
    }

    private Component renderLine(UUID playerId, MenuLine line) {
        List<MenuFragment> fragments = line.fragments();
        if (fragments.isEmpty()) {
            return Component.empty();
        }
        Component out = renderFragment(playerId, fragments.get(0));
        for (int i = 1; i < fragments.size(); i++) {
            out = out.append(renderFragment(playerId, fragments.get(i)));
        }
        return out;
    }

    private Component renderFragment(UUID playerId, MenuFragment fragment) {
        OfflinePlayer viewer = resolveViewer(playerId);
        Component text = toComponent(viewer, fragment.text());
        String hover = fragment.hover();
        if (hover != null && !hover.isEmpty()) {
            text = text.hoverEvent(HoverEvent.showText(toComponent(viewer, hover)));
        }
        ClickEvent click = toClickEvent(playerId, fragment.action());
        if (click != null) {
            text = text.clickEvent(click);
        }
        return text;
    }

    /**
     * Runs {@code raw} through the project's standard {@link SendMessage#format}
     * pipeline (placeholders, PAPI, legacy {@code &} color codes, hex) and then
     * deserializes the resulting {@code §}-coded string into an Adventure
     * {@link Component}. Mirrors how {@link SendMessage} renders chat output so
     * menu text obeys the same color/placeholder conventions as everywhere else
     * in the plugin.
     */
    private static Component toComponent(@Nullable OfflinePlayer viewer, @Nullable String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        String formatted;
        try {
            formatted = SendMessage.format(viewer, raw);
        } catch (Throwable t) {
            // Defensive: in unit-test contexts where Bukkit.getServer() is not
            // wired up, SendMessage.format may NPE on placeholder lookups. Fall
            // back to the raw legacy-ampersand string so colors still render.
            formatted = raw;
        }
        // `SendMessage.format` converts '&' to '§'; on the test-bypass path
        // the string may still contain '&' codes. Prefer the section-style
        // deserializer (it's the normal production path), then fall through
        // to the ampersand-style if the input still has '&' markers.
        if (formatted.indexOf('\u00a7') >= 0) {
            return LegacyComponentSerializer.legacySection().deserialize(formatted);
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(formatted);
    }

    private static @Nullable OfflinePlayer resolveViewer(UUID playerId) {
        try {
            if (Bukkit.getServer() == null) return null;
            return Bukkit.getOfflinePlayer(playerId);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * ADR-050 Stage 2 (2026-05-24): per-variant emission of concrete
     * self-documenting {@code /rtp menu ...} commands. No token registry
     * is consulted; clicks now carry the literal command syntax an operator
     * would type. Permission gating remains the security boundary (enforced
     * server-side in the matching {@code dispatch*} helper on
     * {@code MenuRedeemSubcommand}).
     *
     * <p>The renderer-only variants ({@link MenuAction.ChangePage},
     * {@link MenuAction.SuggestInput}, {@link MenuAction.OpenExternalUrl})
     * continue to map directly to Adventure {@link ClickEvent} types.
     */
    @SuppressWarnings("unused")
    private @Nullable ClickEvent toClickEvent(UUID playerId, @Nullable MenuAction action) {
        if (action == null) return null;
        return switch (action) {
            case MenuAction.RunRtpCommand run ->
                    ClickEvent.runCommand(buildRunCommand(run));
            case MenuAction.OpenMenu open ->
                    // Empty path = root; omit `path=` entirely because
                    // commands-api TreeCommand rejects any arg ending in
                    // `=` (empty value) before the leaf's onCommand runs.
                    ClickEvent.runCommand("/rtp menu open" + pathArg(open.path()));
            case MenuAction.OpenParamPicker picker ->
                    ClickEvent.runCommand("/rtp menu picker"
                            + pathArg(picker.parentPath())
                            + " param=" + picker.paramName());
            case MenuAction.PromptAnvilInput prompt ->
                    ClickEvent.runCommand(buildAnvilCommand(prompt));
            case MenuAction.ChangePage change ->
                    // Adventure pages are 1-based; MenuPage indices are 0-based.
                    ClickEvent.changePage(change.pageIndex() + 1);
            case MenuAction.SuggestInput suggest ->
                    ClickEvent.suggestCommand(suggest.prefix());
            case MenuAction.OpenExternalUrl url ->
                    ClickEvent.openUrl(safeUrlString(url.uri()));
            case MenuAction.OpenConfigSelector ignored ->
                    ClickEvent.runCommand("/rtp menu config");
            case MenuAction.OpenConfigFile f ->
                    ClickEvent.runCommand("/rtp menu config file=" + f.fileName());
            case MenuAction.OpenConfigKey k ->
                    ClickEvent.runCommand("/rtp menu config"
                            + " file=" + k.fileName()
                            + " key=" + k.paramName());
            case MenuAction.OpenConfigSubParamPage sp ->
                    // No dedicated leaf in core today; ConfigCmd's parameter
                    // grammar discards an unknown `type=` token and falls
                    // through to the key page, which is the existing
                    // dispatch behaviour pre-ADR-050.
                    ClickEvent.runCommand("/rtp menu config"
                            + " file=" + sp.fileName()
                            + " key=" + sp.paramName()
                            + " type=" + sp.typeName());
            case MenuAction.OpenConfigSearchPrompt ignored ->
                    ClickEvent.runCommand("/rtp menu config search");
            case MenuAction.OpenConfigSearchResults sr ->
                    // ConfigSearchCmd accepts 1-indexed `page=`; record stores
                    // 0-indexed page, so +1 on emit.
                    ClickEvent.runCommand("/rtp menu config search"
                            + " query=" + sr.query()
                            + " page=" + (sr.page() + 1));
            case MenuAction.OpenAdminPanel ignored ->
                    ClickEvent.runCommand("/rtp menu admin");
            case MenuAction.OpenVisualizations ignored ->
                    ClickEvent.runCommand("/rtp menu visualizations");
            case MenuAction.OpenFrontPage ignored ->
                    ClickEvent.runCommand("/rtp menu front");
            case MenuAction.OpenInfo info ->
                    ClickEvent.runCommand("/rtp menu info scope=" + scopeWire(info.scope()));
            case MenuAction.SwitchInfoToText sw ->
                    ClickEvent.runCommand("/rtp menu info"
                            + " scope=" + scopeWire(sw.scope())
                            + " text=true");
            case MenuAction.StageConfigValue st ->
                    ClickEvent.runCommand("/rtp menu stage"
                            + " file=" + st.fileName()
                            + " key=" + st.paramName()
                            + " value=" + st.value());
            case MenuAction.UnstageConfigValue u ->
                    ClickEvent.runCommand("/rtp menu unstage"
                            + " file=" + u.fileName()
                            + " key=" + u.paramName());
            case MenuAction.ApplyStagedConfig ap ->
                    ClickEvent.runCommand("/rtp menu apply file=" + ap.fileName());
            case MenuAction.DiscardStagedConfig d ->
                    ClickEvent.runCommand("/rtp menu discard file=" + d.fileName());
            case MenuAction.OpenMultiConfigSelector ms ->
                    ClickEvent.runCommand("/rtp menu multi kind=" + ms.parserKind());
            case MenuAction.OpenMultiConfigEntry me ->
                    ClickEvent.runCommand("/rtp menu multi"
                            + " kind=" + me.parserKind()
                            + " entry=" + me.entryName());
            case MenuAction.MultiConfigMutate mm ->
                    ClickEvent.runCommand("/rtp menu multi"
                            + " kind=" + mm.parserKind()
                            + " entry=" + mm.entryName()
                            + " op=" + mm.op().name().toLowerCase(java.util.Locale.ROOT));
            case MenuAction.OpenMap openMap ->
                    // ADR-050 Stage 3β: OpenMap carries (kind, regionName).
                    // Emit `/rtp visualization x=<kind>:<regionName>`; the
                    // dispatcher parses on the first `:` and builds the
                    // ChartSpec inline.
                    ClickEvent.runCommand("/rtp visualization x="
                            + openMap.kind().name() + ":" + openMap.regionName());
        };
    }

    /**
     * Encodes a {@link MenuAction.RunRtpCommand} as a literal {@code /rtp}
     * invocation. The args are joined with single spaces; empty args degrade
     * to bare {@code /rtp}.
     */
    private static String buildRunCommand(MenuAction.RunRtpCommand run) {
        String[] args = run.args();
        if (args.length == 0) return "/rtp";
        StringBuilder sb = new StringBuilder("/rtp");
        for (String arg : args) {
            sb.append(' ').append(arg);
        }
        return sb.toString();
    }

    /**
     * Encodes a {@link MenuAction.PromptAnvilInput} as the concrete
     * {@code /rtp menu anvil ...} command consumed by
     * {@code MenuConcreteCommandLeavesB.AnvilCmd}.
     */
    private static String buildAnvilCommand(MenuAction.PromptAnvilInput prompt) {
        StringBuilder sb = new StringBuilder("/rtp menu anvil")
                .append(pathArg(prompt.parentPath()))
                .append(" param=").append(prompt.paramName());
        String prefill = prompt.prefill();
        if (prefill != null && !prefill.isEmpty()) {
            sb.append(" prefill=").append(prefill);
        }
        sb.append(" mode=")
                .append(prompt.mode().name().toLowerCase(java.util.Locale.ROOT));
        return sb.toString();
    }

    /**
     * Joins a path array as a dot-separated string for the {@code path=}
     * parameter of the open / picker / anvil leaves. Empty arrays return
     * the empty string (the leaves treat an empty path as root).
     */
    private static String dotted(String[] path) {
        if (path == null || path.length == 0) return "";
        StringBuilder sb = new StringBuilder(path[0]);
        for (int i = 1; i < path.length; i++) {
            sb.append('.').append(path[i]);
        }
        return sb.toString();
    }

    /**
     * Returns {@code " path=<dotted>"} when the path is non-empty, or the
     * empty string when it is null/empty. The commands-api parameter parser
     * rejects any argument that ends in {@code =} (empty value) before the
     * leaf's onCommand runs (TreeCommand.java:224), so an empty path must
     * NOT be emitted as {@code path=}; instead the leaf treats a missing
     * {@code path=} as root.
     */
    private static String pathArg(String[] path) {
        String d = dotted(path);
        return d.isEmpty() ? "" : " path=" + d;
    }

    /**
     * Encodes an {@link MenuAction.InfoScopeToken} for the {@code scope=}
     * parameter of {@code /rtp menu info}. Format matches
     * {@code InfoCmd.parseScope}: {@code global}, {@code world:<name>},
     * {@code region:<name>}.
     */
    private static String scopeWire(MenuAction.InfoScopeToken scope) {
        return switch (scope.kind()) {
            case GLOBAL -> "global";
            case WORLD -> "world:" + scope.name();
            case REGION -> "region:" + scope.name();
        };
    }

    private static String safeUrlString(URI uri) {
        // Adventure validates the URL string at construction; pass through as-is.
        return uri.toString();
    }
}
