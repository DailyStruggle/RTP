package io.github.dailystruggle.rtp.paper.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuTokenRegistry;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BookMenuRenderer}. These exercise the pure
 * {@link BookMenuRenderer#buildBook(UUID, MenuModel)} path so the renderer
 * can be verified without a live Bukkit / Paper server.
 *
 * <p>Covers (Stage 4.3 of {@code CHECKLIST-generalized-menu.md}):
 * <ul>
 *   <li>Page count matches {@code model.pages().size()}.</li>
 *   <li>Each {@link MenuAction} variant produces the correct {@link ClickEvent}.</li>
 *   <li>{@link MenuAction.RunRtpCommand} mints exactly one token per fragment
 *       and the resulting click payload is {@code /rtp menu:<token>}.</li>
 *   <li>Null actions produce no click event.</li>
 *   <li>Non-null hover produces a {@link HoverEvent#showText} hover event.</li>
 *   <li>{@link BookMenuRenderer#render} throws {@link IllegalStateException}
 *       when the target player is offline (S-006).</li>
 * </ul>
 */
final class BookMenuRendererTest {

    /** Test double for {@link MenuTokenRegistry} capturing every mint call. */
    private static final class CapturingRegistry implements MenuTokenRegistry {
        final List<UUID> mintedFor = new ArrayList<>();
        final List<MenuAction> mintedActions = new ArrayList<>();
        final AtomicInteger counter = new AtomicInteger();

        @Override
        public String mint(UUID playerId, MenuAction action, Duration ttl) {
            mintedFor.add(playerId);
            mintedActions.add(action);
            return "tok-" + counter.getAndIncrement();
        }

        @Override
        public Optional<MenuAction> consume(UUID playerId, String token) {
            throw new UnsupportedOperationException("not used in renderer tests");
        }

        @Override
        public int outstandingFor(UUID playerId) {
            return 0;
        }
    }

    private static MenuModel singleLineModel(MenuFragment... fragments) {
        MenuLine line = new MenuLine(List.of(fragments));
        return new MenuModel("title", List.of(new MenuPage(List.of(line))));
    }

    @Test
    void buildBook_emitsOnePagePerMenuPage() {
        CapturingRegistry registry = new CapturingRegistry();
        BookMenuRenderer renderer = new BookMenuRenderer(registry, Duration.ofSeconds(30),
                (uuid, model) -> null);

        MenuPage p1 = new MenuPage(List.of(MenuLine.of(MenuFragment.plain("a"))));
        MenuPage p2 = new MenuPage(List.of(MenuLine.of(MenuFragment.plain("b"))));
        MenuPage p3 = new MenuPage(List.of(MenuLine.of(MenuFragment.plain("c"))));
        MenuModel model = new MenuModel("t", List.of(p1, p2, p3));

        Book book = renderer.buildBook(UUID.randomUUID(), model);
        assertEquals(3, book.pages().size(), "one Book page per MenuPage");
    }

    @Test
    void buildBook_runRtpCommand_mintsTokenAndEmitsMenuRunCommand() {
        CapturingRegistry registry = new CapturingRegistry();
        BookMenuRenderer renderer = new BookMenuRenderer(registry, Duration.ofSeconds(30),
                (uuid, model) -> null);
        UUID viewer = UUID.randomUUID();
        MenuAction.RunRtpCommand run = new MenuAction.RunRtpCommand(new String[]{"config", "biomes"});

        Book book = renderer.buildBook(viewer,
                singleLineModel(new MenuFragment("config", null, run)));

        assertEquals(1, registry.mintedFor.size(), "exactly one mint per RunRtpCommand fragment");
        assertEquals(viewer, registry.mintedFor.get(0), "token bound to the viewer UUID");
        assertEquals(run, registry.mintedActions.get(0), "stored action equals the original");

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click, "RunRtpCommand fragment carries a click event");
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        assertEquals("/rtp menu token:tok-0", click.value(),
                "click payload is the opaque /rtp menu token:<token> form, never a literal command");
    }

    @Test
    void buildBook_changePage_emitsChangePageOneBased() {
        CapturingRegistry registry = new CapturingRegistry();
        BookMenuRenderer renderer = new BookMenuRenderer(registry, Duration.ofSeconds(30),
                (uuid, model) -> null);

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("next", null, new MenuAction.ChangePage(2))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals(ClickEvent.Action.CHANGE_PAGE, click.action());
        assertEquals("3", click.value(),
                "Adventure pages are 1-based; MenuAction.ChangePage(2) becomes Adventure page 3");
        assertTrue(registry.mintedFor.isEmpty(), "non-Run actions do not mint tokens");
    }

    @Test
    void buildBook_suggestInput_emitsSuggestCommand() {
        CapturingRegistry registry = new CapturingRegistry();
        BookMenuRenderer renderer = new BookMenuRenderer(registry, Duration.ofSeconds(30),
                (uuid, model) -> null);

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("value:", null,
                        new MenuAction.SuggestInput("/rtp config performance ASYNC:"))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, click.action());
        assertEquals("/rtp config performance ASYNC:", click.value());
        assertTrue(registry.mintedFor.isEmpty());
    }

    @Test
    void buildBook_openExternalUrl_emitsOpenUrl() {
        CapturingRegistry registry = new CapturingRegistry();
        BookMenuRenderer renderer = new BookMenuRenderer(registry, Duration.ofSeconds(30),
                (uuid, model) -> null);
        URI docs = URI.create("https://example.invalid/rtp-docs");

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("docs", null,
                        new MenuAction.OpenExternalUrl(docs))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals(ClickEvent.Action.OPEN_URL, click.action());
        assertEquals(docs.toString(), click.value());
        assertTrue(registry.mintedFor.isEmpty());
    }

    @Test
    void buildBook_nullAction_producesNoClickEvent() {
        CapturingRegistry registry = new CapturingRegistry();
        BookMenuRenderer renderer = new BookMenuRenderer(registry, Duration.ofSeconds(30),
                (uuid, model) -> null);

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(MenuFragment.plain("decorative")));

        assertNull(firstClickEvent(book.pages().get(0)),
                "decorative fragments must not carry click events");
        assertTrue(registry.mintedFor.isEmpty());
    }

    @Test
    void buildBook_nonNullHover_emitsShowTextHover() {
        CapturingRegistry registry = new CapturingRegistry();
        BookMenuRenderer renderer = new BookMenuRenderer(registry, Duration.ofSeconds(30),
                (uuid, model) -> null);

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("teleport", "warps you randomly", null)));

        HoverEvent<?> hover = firstHoverEvent(book.pages().get(0));
        assertNotNull(hover, "fragment with non-null hover carries a hover event");
        assertEquals(HoverEvent.Action.SHOW_TEXT, hover.action());
        assertInstanceOf(Component.class, hover.value());
        assertEquals("warps you randomly", ((TextComponent) hover.value()).content());
    }

    @Test
    void buildBook_mintsOncePerRunRtpCommandFragment() {
        CapturingRegistry registry = new CapturingRegistry();
        BookMenuRenderer renderer = new BookMenuRenderer(registry, Duration.ofSeconds(30),
                (uuid, model) -> null);
        UUID viewer = UUID.randomUUID();

        MenuAction.RunRtpCommand a = new MenuAction.RunRtpCommand(new String[]{"goto", "spawn"});
        MenuAction.RunRtpCommand b = new MenuAction.RunRtpCommand(new String[]{"biomes"});
        MenuModel model = new MenuModel("t", List.of(new MenuPage(List.of(
                MenuLine.of(new MenuFragment("goto", null, a)),
                MenuLine.of(new MenuFragment("biomes", null, b)),
                MenuLine.of(MenuFragment.plain("decoration")),
                MenuLine.of(new MenuFragment("next page", null, new MenuAction.ChangePage(1)))
        ))));

        renderer.buildBook(viewer, model);

        assertEquals(2, registry.mintedFor.size(),
                "exactly one mint per RunRtpCommand fragment; ChangePage and plain do not mint");
        assertEquals(viewer, registry.mintedFor.get(0));
        assertEquals(viewer, registry.mintedFor.get(1));
        assertEquals(a, registry.mintedActions.get(0));
        assertEquals(b, registry.mintedActions.get(1));
    }

    @Test
    void render_throwsIllegalStateException_whenPlayerIsOffline() {
        CapturingRegistry registry = new CapturingRegistry();
        BookMenuRenderer renderer = new BookMenuRenderer(registry, Duration.ofSeconds(30),
                (uuid, model) -> null); // simulate "player not online"
        UUID viewer = UUID.randomUUID();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> renderer.render(viewer,
                        singleLineModel(MenuFragment.plain("anything"))));
        assertTrue(ex.getMessage().contains(viewer.toString()),
                "S-006 violation message must identify the missing player");
    }

    @Test
    void constructor_rejectsNonPositiveTtl() {
        CapturingRegistry registry = new CapturingRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> new BookMenuRenderer(registry, Duration.ZERO,
                        (uuid, model) -> null));
        assertThrows(IllegalArgumentException.class,
                () -> new BookMenuRenderer(registry, Duration.ofSeconds(-1),
                        (uuid, model) -> null));
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static ClickEvent firstClickEvent(Component root) {
        if (root.clickEvent() != null) return root.clickEvent();
        for (Component child : root.children()) {
            ClickEvent c = firstClickEvent(child);
            if (c != null) return c;
        }
        return null;
    }

    private static HoverEvent<?> firstHoverEvent(Component root) {
        if (root.hoverEvent() != null) return root.hoverEvent();
        for (Component child : root.children()) {
            HoverEvent<?> h = firstHoverEvent(child);
            if (h != null) return h;
        }
        return null;
    }
}
