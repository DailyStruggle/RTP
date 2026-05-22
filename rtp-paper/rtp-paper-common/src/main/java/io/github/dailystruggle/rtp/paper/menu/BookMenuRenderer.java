package io.github.dailystruggle.rtp.paper.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.menu.MenuTokenRegistry;
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
    public static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(6);

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
                yield ClickEvent.runCommand("/rtp menu token=" + token);
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
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.OpenParamPicker picker -> {
                // Stage A.2 parameter-value picker click. Same /rtp menu
                // token:<token> redeem payload as the other server-resolved
                // variants; MenuRedeemSubcommand walks parentPath and renders
                // the picker page server-side.
                String token = tokenRegistry.mint(playerId, picker, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.PromptAnvilInput prompt -> {
                // ADR-045 anvil-input click. Adventure has no client-driven
                // "open inventory" click event, so the click round-trips
                // through the same /rtp menu token:<token> redeem path the
                // other server-resolved variants use; MenuRedeemSubcommand
                // dispatches to the platform-side AnvilInputOpener which
                // opens the anvil GUI on the player and submits the typed
                // value back through the /rtp pipeline on confirm.
                String token = tokenRegistry.mint(playerId, prompt, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.ChangePage change ->
                    // Adventure pages are 1-based; MenuPage indices are 0-based.
                    ClickEvent.changePage(change.pageIndex() + 1);
            case MenuAction.SuggestInput suggest ->
                    ClickEvent.suggestCommand(suggest.prefix());
            case MenuAction.OpenExternalUrl url ->
                    ClickEvent.openUrl(safeUrlString(url.uri()));
            case MenuAction.OpenConfigSelector selector -> {
                // Curated config-subtree page 1 (PROPOSAL-config-view-as-book.md v3.7).
                // Server-resolved through the same /rtp menu token:<token> redeem
                // path the other navigation variants use; MenuRedeemSubcommand
                // dispatches to dispatchOpenConfigSelector.
                String token = tokenRegistry.mint(playerId, selector, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.OpenConfigFile fileAction -> {
                // Curated config-subtree page 2 (PROPOSAL-config-view-as-book.md v3.7).
                // Server-resolved through the /rtp menu token:<token> redeem path;
                // MenuRedeemSubcommand dispatches to dispatchOpenConfigFile.
                String token = tokenRegistry.mint(playerId, fileAction, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.OpenConfigKey keyAction -> {
                // Curated config-subtree page 3 (PROPOSAL-config-view-as-book.md v3.7).
                // Server-resolved through the /rtp menu token:<token> redeem path;
                // MenuRedeemSubcommand dispatches to dispatchOpenConfigKey, which
                // delegates to the existing buildParamPicker flow.
                String token = tokenRegistry.mint(playerId, keyAction, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.OpenConfigSubParamPage subParamAction -> {
                // Curated config-subtree page 3b for shape/vert sub-parameters
                // (PROPOSAL-config-view-as-book.md v3.7.5). Server-resolved through
                // the /rtp menu token:<token> redeem path; MenuRedeemSubcommand
                // dispatches to dispatchOpenConfigSubParamPage, which renders the
                // activated type's name + sub-parameter rows.
                String token = tokenRegistry.mint(playerId, subParamAction, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.OpenConfigSearchPrompt searchPrompt -> {
                // Cross-config search anvil-input prompt (PROPOSAL-rtp-menu-config-search.md
                // §6 Q4 Decision (A)). Server-resolved through the same /rtp menu
                // token:<token> redeem path; MenuRedeemSubcommand translates the
                // prompt to a PromptAnvilInput(["menu","config","search"], "query", "")
                // which the platform-side AnvilInputOpener opens.
                String token = tokenRegistry.mint(playerId, searchPrompt, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.OpenConfigSearchResults searchResults -> {
                // Cross-config search results page (PROPOSAL-rtp-menu-config-search.md).
                // Server-resolved through the /rtp menu token:<token> redeem path;
                // MenuRedeemSubcommand dispatches to dispatchOpenConfigSearchResults,
                // which walks every loaded config parser and renders raw-literal
                // value text with off-blue highlight on the matched substring.
                String token = tokenRegistry.mint(playerId, searchResults, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.OpenAdminPanel adminPanel -> {
                // Curated admin-panel page (PROPOSAL-admin-panel.md v2).
                // Server-resolved through the /rtp menu token:<token> redeem path;
                // MenuRedeemSubcommand dispatches to dispatchOpenAdminPanel, which
                // permission-gates on rtp.menu.admin before rendering.
                String token = tokenRegistry.mint(playerId, adminPanel, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.OpenFrontPage frontPage -> {
                // Curated front page (PROPOSAL-admin-panel.md v2). Used by the
                // admin panel's back row. Distinct from OpenMenu([]) which would
                // target the reflected TreeCommand root rather than the curated
                // FrontPageBuilder output. Server-resolved through the same
                // /rtp menu token:<token> redeem path; MenuRedeemSubcommand
                // dispatches to dispatchOpenFrontPage.
                String token = tokenRegistry.mint(playerId, frontPage, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.OpenInfo openInfo -> {
                // /rtp info book (PROPOSAL-info-as-book.md section 4.6).
                // Server-resolved through the /rtp menu token:<token> redeem
                // path; MenuRedeemSubcommand dispatches to dispatchOpenInfo,
                // which gates on rtp.info before re-rendering the book at the
                // supplied scope (global / world / region).
                String token = tokenRegistry.mint(playerId, openInfo, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.SwitchInfoToText switchInfo -> {
                // /rtp info chat fallback (PROPOSAL-info-as-book.md section
                // 4.6). Server-resolved through the /rtp menu token:<token>
                // redeem path; MenuRedeemSubcommand dispatches to
                // dispatchSwitchInfoToText, which re-enters the chat path
                // (no book) for the supplied scope.
                String token = tokenRegistry.mint(playerId, switchInfo, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            // Config-menu staging-cart redesign (CHECKLIST-config-staging-cart.md).
            // All four cart-affecting actions are server-resolved exactly like the
            // other curated config actions: mint a token and round-trip through
            // /rtp menu token:<token>. MenuRedeemSubcommand dispatches to the
            // matching dispatchStage/Unstage/Apply/DiscardStagedConfig arm.
            case MenuAction.StageConfigValue stageAction -> {
                String token = tokenRegistry.mint(playerId, stageAction, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.UnstageConfigValue unstageAction -> {
                String token = tokenRegistry.mint(playerId, unstageAction, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.ApplyStagedConfig applyAction -> {
                String token = tokenRegistry.mint(playerId, applyAction, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.DiscardStagedConfig discardAction -> {
                String token = tokenRegistry.mint(playerId, discardAction, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
            case MenuAction.OpenMap openMap -> {
                // ADR-047 / REQ-RTP-MAP-006 declarative chart bridge. The
                // OpenMap action's payload is a UUID into the rtp-core
                // ChartSpecTokens registry; that is the only authority over
                // which chart the click paints. Server-resolved through the
                // same /rtp menu token:<token> redeem path the other curated
                // actions use; MenuRedeemSubcommand.dispatchOpenMap consumes
                // the ChartSpec token, looks up the resolver, and calls
                // MapDispatch.paint(spec, viewer) to deliver the map item.
                String token = tokenRegistry.mint(playerId, openMap, tokenTtl);
                yield ClickEvent.runCommand("/rtp menu token=" + token);
            }
        };
    }

    private static String safeUrlString(URI uri) {
        // Adventure validates the URL string at construction; pass through as-is.
        return uri.toString();
    }
}
