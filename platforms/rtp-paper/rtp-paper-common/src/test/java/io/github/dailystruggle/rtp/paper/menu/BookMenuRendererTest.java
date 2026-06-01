package io.github.dailystruggle.rtp.paper.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BookMenuRenderer}. ADR-050 Stage 3β.D.2b
 * (2026-05-24): the {@code MenuTokenRegistry} ctor parameter is gone; the
 * renderer is no-arg / playerLookup-only and emits concrete
 * self-documenting {@code /rtp menu ...} (or {@code /rtp}) commands.
 *
 * <p>Renderer-only variants ({@link MenuAction.ChangePage},
 * {@link MenuAction.SuggestInput}, {@link MenuAction.OpenExternalUrl}) keep
 * mapping directly to Adventure {@link ClickEvent} types.
 */
final class BookMenuRendererTest {

    private static BookMenuRenderer newRenderer() {
        return new BookMenuRenderer((uuid, model) -> null);
    }

    private static MenuModel singleLineModel(MenuFragment... fragments) {
        MenuLine line = new MenuLine(List.of(fragments));
        return new MenuModel("title", List.of(new MenuPage(List.of(line))));
    }

    @Test
    void buildBook_emitsOnePagePerMenuPage() {
        BookMenuRenderer renderer = newRenderer();

        MenuPage p1 = new MenuPage(List.of(MenuLine.of(MenuFragment.plain("a"))));
        MenuPage p2 = new MenuPage(List.of(MenuLine.of(MenuFragment.plain("b"))));
        MenuPage p3 = new MenuPage(List.of(MenuLine.of(MenuFragment.plain("c"))));
        MenuModel model = new MenuModel("t", List.of(p1, p2, p3));

        Book book = renderer.buildBook(UUID.randomUUID(), model);
        assertEquals(3, book.pages().size(), "one Book page per MenuPage");
    }

    @Test
    void runRtpCommand_emitsLiteralRtpInvocation() {
        BookMenuRenderer renderer = newRenderer();
        MenuAction.RunRtpCommand run = new MenuAction.RunRtpCommand(new String[]{"config", "biomes"});

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("config", null, run)));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        assertEquals("/rtp config biomes", click.value(),
                "RunRtpCommand emits a literal /rtp invocation");
    }

    @Test
    void runRtpCommand_emptyArgs_emitsBareRtp() {
        BookMenuRenderer renderer = newRenderer();
        MenuAction.RunRtpCommand run = new MenuAction.RunRtpCommand(new String[0]);

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("rtp", null, run)));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp", click.value());
    }

    @Test
    void openMenu_emitsConcreteOpenCommand() {
        BookMenuRenderer renderer = newRenderer();
        MenuAction.OpenMenu open = new MenuAction.OpenMenu(new String[]{"config", "performance"});

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("perf", null, open)));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu open path=config.performance", click.value());
    }

    @Test
    void openMenu_emptyPath_emitsRootOpenCommand() {
        BookMenuRenderer renderer = newRenderer();
        MenuAction.OpenMenu open = new MenuAction.OpenMenu(new String[0]);

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("home", null, open)));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        // Empty path = root; renderer omits `path=` entirely because
        // commands-api TreeCommand rejects any arg ending in `=`.
        assertEquals("/rtp menu open", click.value());
    }

    @Test
    void openParamPicker_emitsPickerCommand() {
        BookMenuRenderer renderer = newRenderer();
        MenuAction.OpenParamPicker picker = new MenuAction.OpenParamPicker(
                new String[]{"config", "regions"}, "shape");

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("pick", null, picker)));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu picker path=config.regions param=shape", click.value());
    }

    @Test
    void changePage_emitsChangePageOneBased() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("next", null, new MenuAction.ChangePage(2))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals(ClickEvent.Action.CHANGE_PAGE, click.action());
        assertEquals("3", click.value(),
                "Adventure pages are 1-based; MenuAction.ChangePage(2) becomes Adventure page 3");
    }

    @Test
    void suggestInput_emitsSuggestCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("value:", null,
                        new MenuAction.SuggestInput("/rtp config performance ASYNC:"))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals(ClickEvent.Action.SUGGEST_COMMAND, click.action());
        assertEquals("/rtp config performance ASYNC:", click.value());
    }

    @Test
    void promptAnvilInput_emitsAnvilCommand() {
        BookMenuRenderer renderer = newRenderer();
        MenuAction.PromptAnvilInput prompt = new MenuAction.PromptAnvilInput(
                new String[]{"regions"}, "add", "");

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("type a value", null, prompt)));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        assertEquals("/rtp menu anvil path=regions param=add mode=run", click.value());
    }

    @Test
    void promptAnvilInput_withPrefillAndStageMode() {
        BookMenuRenderer renderer = newRenderer();
        MenuAction.PromptAnvilInput prompt = new MenuAction.PromptAnvilInput(
                new String[]{"config", "performance.yml"}, "maxAttempts", "8",
                MenuAction.Mode.STAGE);

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("edit", null, prompt)));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu anvil path=config.performance.yml param=maxAttempts"
                        + " prefill=8 mode=stage",
                click.value());
    }

    @Test
    void openAdminPanel_emitsConcreteAdminCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("Admin panel", null,
                        new MenuAction.OpenAdminPanel())));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        assertEquals("/rtp menu admin", click.value());
    }

    @Test
    void openFrontPage_emitsConcreteFrontCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("Back", null,
                        new MenuAction.OpenFrontPage())));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu front", click.value());
    }

    @Test
    void openVisualizations_emitsConcreteVisualizationsCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("viz", null,
                        new MenuAction.OpenVisualizations())));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu visualizations", click.value());
    }

    @Test
    void openConfigSelector_emitsConcreteConfigCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("config", null,
                        new MenuAction.OpenConfigSelector())));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu config", click.value());
    }

    @Test
    void openConfigFile_emitsConcreteConfigFileCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("perf", null,
                        new MenuAction.OpenConfigFile("performance.yml"))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu config file=performance.yml", click.value());
    }

    @Test
    void openConfigKey_emitsConcreteConfigKeyCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("k", null,
                        new MenuAction.OpenConfigKey("config.yml", "language"))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu config file=config.yml key=language", click.value());
    }

    @Test
    void openConfigSearchPrompt_emitsConcreteSearchCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("search", null,
                        new MenuAction.OpenConfigSearchPrompt())));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu config search", click.value());
    }

    @Test
    void openConfigSearchResults_emitsConcreteSearchResultsCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("hits", null,
                        new MenuAction.OpenConfigSearchResults("teleport", 1))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        // Record stores 0-indexed page; emit as 1-indexed for ConfigSearchCmd.
        assertEquals("/rtp menu config search query=teleport page=2", click.value());
    }

    @Test
    void openInfo_emitsConcreteInfoCommand_globalScope() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("i", null,
                        new MenuAction.OpenInfo(MenuAction.InfoScopeToken.global()))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu info scope=global", click.value());
    }

    @Test
    void openInfo_emitsConcreteInfoCommand_worldScope() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("i", null,
                        new MenuAction.OpenInfo(MenuAction.InfoScopeToken.world("world_nether")))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu info scope=world:world_nether", click.value());
    }

    @Test
    void switchInfoToText_emitsConcreteInfoTextCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("chat", null,
                        new MenuAction.SwitchInfoToText(MenuAction.InfoScopeToken.region("R1")))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu info scope=region:R1 text=true", click.value());
    }

    @Test
    void stageConfigValue_emitsConcreteStageCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("stage", null,
                        new MenuAction.StageConfigValue(
                                "performance.yml", "maxAttempts", "8"))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu stage file=performance.yml key=maxAttempts value=8",
                click.value());
    }

    @Test
    void unstageConfigValue_emitsConcreteUnstageCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("unstage", null,
                        new MenuAction.UnstageConfigValue("performance.yml", "maxAttempts"))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu unstage file=performance.yml key=maxAttempts",
                click.value());
    }

    @Test
    void applyStagedConfig_emitsConcreteApplyCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("apply", null,
                        new MenuAction.ApplyStagedConfig("performance.yml"))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu apply file=performance.yml", click.value());
    }

    @Test
    void discardStagedConfig_emitsConcreteDiscardCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("discard", null,
                        new MenuAction.DiscardStagedConfig("performance.yml"))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu discard file=performance.yml", click.value());
    }

    @Test
    void multiConfigSelector_emitsConcreteMultiCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("m", null,
                        new MenuAction.OpenMultiConfigSelector("regions"))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu multi kind=regions", click.value());
    }

    @Test
    void multiConfigEntry_emitsConcreteMultiEntryCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("m", null,
                        new MenuAction.OpenMultiConfigEntry("regions", "default"))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu multi kind=regions entry=default", click.value());
    }

    @Test
    void multiConfigMutate_emitsConcreteMultiMutateCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("rm", null,
                        new MenuAction.MultiConfigMutate("regions", "default",
                                MenuAction.MultiConfigMutate.Op.REMOVE))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals("/rtp menu multi kind=regions entry=default op=remove",
                click.value());
    }

    @Test
    void openMap_emitsConcreteVisualizationCommand() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("map", null,
                        new MenuAction.OpenMap(
                                io.github.dailystruggle.rtp.api.maps.ChartSpec.Kind.BAD_POINTS_HEATMAP,
                                "myregion"))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        // Bad-locations leaf wiring (2026-05-26): OpenMap dispatches to the
        // per-Kind typed sub-command, `/rtp visualization <literal> region=<name>`.
        // The legacy root-level `x=<kind>:<region>` form is gone.
        assertEquals("/rtp visualization bad-points-heatmap region=myregion", click.value());
    }

    @Test
    void openExternalUrl_emitsOpenUrl() {
        BookMenuRenderer renderer = newRenderer();
        URI docs = URI.create("https://example.invalid/rtp-docs");

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("docs", null,
                        new MenuAction.OpenExternalUrl(docs))));

        ClickEvent click = firstClickEvent(book.pages().get(0));
        assertNotNull(click);
        assertEquals(ClickEvent.Action.OPEN_URL, click.action());
        assertEquals(docs.toString(), click.value());
    }

    @Test
    void nullAction_producesNoClickEvent() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(MenuFragment.plain("decorative")));

        assertNull(firstClickEvent(book.pages().get(0)),
                "decorative fragments must not carry click events");
    }

    @Test
    void nonNullHover_emitsShowTextHover() {
        BookMenuRenderer renderer = newRenderer();

        Book book = renderer.buildBook(UUID.randomUUID(),
                singleLineModel(new MenuFragment("teleport", "warps you randomly", null)));

        HoverEvent<?> hover = firstHoverEvent(book.pages().get(0));
        assertNotNull(hover, "fragment with non-null hover carries a hover event");
        assertEquals(HoverEvent.Action.SHOW_TEXT, hover.action());
        assertInstanceOf(Component.class, hover.value());
        assertEquals("warps you randomly", ((TextComponent) hover.value()).content());
    }

    @Test
    void render_throwsIllegalStateException_whenPlayerIsOffline() {
        BookMenuRenderer renderer = newRenderer();
        UUID viewer = UUID.randomUUID();

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> renderer.render(viewer,
                        singleLineModel(MenuFragment.plain("anything"))));
        assertTrue(ex.getMessage().contains(viewer.toString()),
                "S-006 violation message must identify the missing player");
    }

    // ADR-050 Stage 3β.D.2b (2026-05-24): deleted `constructor_rejectsNonPositiveTtl` -
    // the TTL ctor parameter and the MenuTokenRegistry ctor parameter are
    // both gone; the renderer is no-arg / playerLookup-only.

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
