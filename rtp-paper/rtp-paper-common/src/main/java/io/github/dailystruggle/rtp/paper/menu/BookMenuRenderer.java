package io.github.dailystruggle.rtp.paper.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.menu.MenuTokenRegistry;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.time.Duration;
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
 * <p><b>Click round-trip.</b> Each {@link MenuAction.RunRtpCommand} fragment
 * causes a fresh single-use token to be minted at render time through the
 * injected {@link MenuTokenRegistry}; the resulting click event always
 * dispatches {@code /rtp menu token:<token>}, never a literal command — preserving
 * the security boundary from ADR-035 §3. {@link MenuAction.ChangePage} maps
 * to {@link ClickEvent#changePage(int)} (Adventure pages are 1-based; the
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
 * <p><b>S-004.</b> Token-mint exceptions surface unchanged so the caller can
 * log via {@code RTP.log}; the renderer never swallows them.
 *
 * <p><b>Threading.</b> Adventure {@code Component} construction is thread-safe
 * and pure. {@link Player#openBook(Book)} must be called on a thread that owns
 * the target entity; this renderer assumes the caller routes through the
 * command pipeline (already on the correct region thread under Folia). A
 * Stage-5 follow-up may add a Folia entity-scheduler hop if non-command
 * callers need it.
 */
public final class BookMenuRenderer implements MenuRenderer {

    /**
     * Default TTL applied to each freshly-minted redeem token. Matches the
     * {@code menu.tokenTtlSeconds} default in ADR-035 §Migration / Rollout.
     */
    public static final Duration DEFAULT_TOKEN_TTL = Duration.ofSeconds(60);

    private final MenuTokenRegistry tokenRegistry;
    private final Duration tokenTtl;
    /** Looks up an online {@link Player} by UUID. Indirected for testability. */
    private final BiFunction<UUID, MenuModel, Player> playerLookup;

    /**
     * @param tokenRegistry registry used to mint a fresh single-use token per
     *                      {@link MenuAction.RunRtpCommand} fragment at render
     *                      time.
     */
    public BookMenuRenderer(MenuTokenRegistry tokenRegistry) {
        this(tokenRegistry, DEFAULT_TOKEN_TTL, (uuid, model) -> Bukkit.getPlayer(uuid));
    }

    /**
     * Test / wire-up constructor.
     *
     * @param tokenRegistry registry used to mint redeem tokens.
     * @param tokenTtl      TTL applied to every minted token. Must be positive.
     * @param playerLookup  resolves the target {@link Player} from a UUID; the
     *                      {@link MenuModel} parameter is passed through unused
     *                      so tests can stub without spinning up MockBukkit.
     */
    public BookMenuRenderer(MenuTokenRegistry tokenRegistry,
                            Duration tokenTtl,
                            BiFunction<UUID, MenuModel, Player> playerLookup) {
        this.tokenRegistry = Objects.requireNonNull(tokenRegistry, "tokenRegistry");
        this.tokenTtl = Objects.requireNonNull(tokenTtl, "tokenTtl");
        if (tokenTtl.isZero() || tokenTtl.isNegative()) {
            throw new IllegalArgumentException("tokenTtl must be positive, got " + tokenTtl);
        }
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
        return Book.book(
                Component.text(model.title()),
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
        Component text = Component.text(fragment.text());
        String hover = fragment.hover();
        if (hover != null && !hover.isEmpty()) {
            text = text.hoverEvent(HoverEvent.showText(Component.text(hover)));
        }
        ClickEvent click = toClickEvent(playerId, fragment.action());
        if (click != null) {
            text = text.clickEvent(click);
        }
        return text;
    }

    private @Nullable ClickEvent toClickEvent(UUID playerId, @Nullable MenuAction action) {
        if (action == null) return null;
        return switch (action) {
            case MenuAction.RunRtpCommand run -> {
                // Mint a fresh token at render time so the click payload is the
                // opaque /rtp menu token:<token> form (ADR-035 §3 / §Security boundary).
                // The `token` parameter is registered on MenuRedeemSubcommand,
                // so commands-api parses this as subcommand `menu` + param
                // `token=<value>` — the `menu:<token>` short form is not a
                // valid commands-api parse on the `/rtp` root.
                String token = tokenRegistry.mint(playerId, run, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token:" + token);
            }
            case MenuAction.OpenMenu open -> {
                // Navigation click (back / forward-descend). At the click level
                // OpenMenu is indistinguishable from RunRtpCommand: both round-
                // trip through the same /rtp menu token:<token> redeem path.
                // MenuRedeemSubcommand distinguishes them on the server side
                // by the action variant stored against the token and resolves
                // the OpenMenu path against the live TreeCommand graph — no
                // commands-api re-entry on the navigation tail (ADR-035 §3).
                String token = tokenRegistry.mint(playerId, open, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token:" + token);
            }
            case MenuAction.OpenParamPicker picker -> {
                // Stage A.2 parameter-value picker click. Same /rtp menu
                // token:<token> redeem payload as the other server-resolved
                // variants; MenuRedeemSubcommand walks parentPath and renders
                // the picker page server-side.
                String token = tokenRegistry.mint(playerId, picker, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token:" + token);
            }
            case MenuAction.ChangePage change ->
                    // Adventure pages are 1-based; MenuPage indices are 0-based.
                    ClickEvent.changePage(change.pageIndex() + 1);
            case MenuAction.SuggestInput suggest ->
                    ClickEvent.suggestCommand(suggest.prefix());
            case MenuAction.OpenExternalUrl url ->
                    ClickEvent.openUrl(safeUrlString(url.uri()));
        };
    }

    private static String safeUrlString(URI uri) {
        // Adventure validates the URL string at construction; pass through as-is.
        return uri.toString();
    }
}
