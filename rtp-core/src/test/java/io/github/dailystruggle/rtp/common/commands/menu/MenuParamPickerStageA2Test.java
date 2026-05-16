package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuConsumerProfile;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage A.2 — parameter-value picker behaviour.
 *
 * <p>Pins the user-visible contract introduced in
 * {@code CHECKLIST-menu-navigation.md} Stage A.2:
 * <ul>
 *   <li>Parameter rows with non-empty suggestions emit
 *       {@link MenuAction.OpenParamPicker} instead of {@link MenuAction.SuggestInput}.</li>
 *   <li>{@code CommandTreeMenuBuilder.buildParamPicker} produces a page with
 *       Back, header, "type a value" fallback, and one value row per
 *       suggestion (carrying {@link MenuAction.OpenMenu} as of Stage A.3 —
 *       clicks stage the assignment into the assembled path instead of
 *       executing, enabling multi-parameter compose statelessly).</li>
 *   <li>{@code MenuRedeemSubcommand.dispatchOpenParamPicker} walks the parent
 *       path, validates the parameter exists, and routes through the picker
 *       builder + renderer. Unknown parameter / segment paths reject with
 *       {@code menuInvalid} + WARN (S-004).</li>
 * </ul>
 */
@DisplayName("ADR-035 / ADR-044 § menu parameter picker (Stage A.2)")
class MenuParamPickerStageA2Test {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setupRTP() {
        RTPTestSetup.install(tempDir.toFile());
    }

    // ------------------------------------------------------------------------
    // Builder: buildParamPicker page layout
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("buildParamPicker emits Back + header + type-a-value + one row per suggestion")
    void buildParamPicker_layout() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        // BooleanParameter has values() = {true, false}.
        root.getParameterLookup().put("ASYNC",
                new BooleanParameter("", "async toggle", (u, v) -> true));

        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder(registry);
        MenuModel model = builder.buildParamPicker(
                root, UUID.randomUUID(), perm -> true,
                MenuConsumerProfile.defaultProfile(),
                List.of("config", "performance"),
                "ASYNC");

        assertEquals(1, model.pages().size());
        List<MenuLine> lines = model.pages().get(0).lines();

        // Row 0 = Back, Row 1 = header (no action), Row 2 = type-a-value
        // (SuggestInput), Row 3+ = value rows. Stage A.3: value rows carry
        // MenuAction.OpenMenu(parentPath..., paramName:value) so clicking
        // *stages* the assignment into the assembled path (re-opens the
        // parent command page) rather than executing immediately. The
        // explicit Execute row on the parent page runs the assembled
        // command, enabling multi-parameter compose statelessly.
        assertTrue(lines.size() >= 4,
                "picker page must have at least Back + header + type + 1 value");

        // Back row → OpenMenu(parentPath)
        MenuFragment back = lines.get(0).fragments().get(0);
        assertInstanceOf(MenuAction.OpenMenu.class, back.action(),
                "first row must be the Back navigation row");
        MenuAction.OpenMenu backAction = (MenuAction.OpenMenu) back.action();
        assertArrayEqualsLocal(new String[]{"config", "performance"}, backAction.path(),
                "Back must target the parent command page");

        // Header row → no action (orientation only)
        MenuFragment header = lines.get(1).fragments().get(0);
        assertEquals(null, header.action(),
                "header row is non-clickable");
        assertTrue(header.text().contains("ASYNC"),
                "header text must reference the parameter name");

        // "Type a value" row → SuggestInput
        MenuFragment type = lines.get(2).fragments().get(0);
        assertInstanceOf(MenuAction.SuggestInput.class, type.action(),
                "type-a-value row must carry SuggestInput");
        String prefix = ((MenuAction.SuggestInput) type.action()).prefix();
        assertTrue(prefix.endsWith("ASYNC:"),
                "SuggestInput prefix must end with '<param>:' for free-form entry; was: " + prefix);

        // Value rows — Stage A.3: OpenMenu carrying parentPath + paramName:value
        // tail. The click re-opens the parent command page with the value
        // staged into the assembled path; the parent page's Execute row then
        // runs the assembled command on explicit confirm.
        Map<String, MenuAction.OpenMenu> valueRows = new HashMap<>();
        for (int i = 3; i < lines.size(); i++) {
            MenuFragment vf = lines.get(i).fragments().get(0);
            assertInstanceOf(MenuAction.OpenMenu.class, vf.action(),
                    "row " + i + " must be a value row (OpenMenu, staging the assignment)");
            valueRows.put(vf.text(), (MenuAction.OpenMenu) vf.action());
        }
        assertTrue(valueRows.containsKey("true"), "boolean 'true' row present");
        assertTrue(valueRows.containsKey("false"), "boolean 'false' row present");
        // Each value row's path is <parentPath..., ASYNC:value>.
        String[] truePath = valueRows.get("true").path();
        assertArrayEqualsLocal(new String[]{"config", "performance", "ASYNC:true"}, truePath, "true row path");
        String[] falsePath = valueRows.get("false").path();
        assertArrayEqualsLocal(new String[]{"config", "performance", "ASYNC:false"}, falsePath, "false row path");
    }

    @Test
    @DisplayName("buildParamPicker still emits Back+header+type rows when parameter has no suggestions")
    void buildParamPicker_emptySuggestions_stillProducesScaffold() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        root.getParameterLookup().put("freeform", new CommandParameter("", "no values", (u, v) -> true) {
            @Override
            public java.util.Set<String> values() { return java.util.Collections.emptySet(); }
        });

        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder(registry);
        MenuModel model = builder.buildParamPicker(
                root, UUID.randomUUID(), perm -> true,
                MenuConsumerProfile.defaultProfile(),
                List.of("config"),
                "freeform");

        List<MenuLine> lines = model.pages().get(0).lines();
        // Back + header + type-a-value, no value rows.
        assertEquals(3, lines.size(),
                "no-suggestion picker page must contain just Back + header + type fallback");
        assertInstanceOf(MenuAction.OpenMenu.class,
                lines.get(0).fragments().get(0).action(),
                "Back row still present");
        assertInstanceOf(MenuAction.SuggestInput.class,
                lines.get(2).fragments().get(0).action(),
                "type-a-value fallback still present so the player can enter free-form");
    }

    @Test
    @DisplayName("buildParamPicker paginates large suggestion sets with prev/next ChangePage rows (Stage A.6)")
    void buildParamPicker_paginatesLargeSuggestionSet() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        // Construct a parameter exposing 25 suggested values — well past
        // the picker per-page cap (10) so the builder must produce 3 pages
        // (10 + 10 + 5). Sorted output places "v00".."v24" in lexical order.
        final java.util.Set<String> bigSet = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 25; i++) {
            bigSet.add(String.format("v%02d", i));
        }
        root.getParameterLookup().put("biome",
                new CommandParameter("", "many values", (u, v) -> true) {
                    @Override
                    public java.util.Set<String> values() { return bigSet; }
                });

        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder(registry);
        MenuModel model = builder.buildParamPicker(
                root, UUID.randomUUID(), perm -> true,
                MenuConsumerProfile.defaultProfile(),
                List.of("tpworld"),
                "biome");

        // 25 values @ 10 per page → 3 pages.
        assertEquals(3, model.pages().size(),
                "25 suggestions @ cap 10 must yield 3 pages");

        // Each page begins with the same back+header+type scaffold so
        // navigation works from any page.
        for (int p = 0; p < model.pages().size(); p++) {
            List<MenuLine> pageLines = model.pages().get(p).lines();
            assertInstanceOf(MenuAction.OpenMenu.class,
                    pageLines.get(0).fragments().get(0).action(),
                    "page " + p + ": row 0 must be Back (OpenMenu)");
            assertEquals(null,
                    pageLines.get(1).fragments().get(0).action(),
                    "page " + p + ": row 1 must be header (non-clickable)");
            assertInstanceOf(MenuAction.SuggestInput.class,
                    pageLines.get(2).fragments().get(0).action(),
                    "page " + p + ": row 2 must be type-a-value (SuggestInput)");
        }

        // Page 0: 3 scaffold rows + 10 values + 1 next nav row = 14 rows;
        // last row must be ChangePage(1).
        List<MenuLine> p0 = model.pages().get(0).lines();
        assertEquals(14, p0.size(), "page 0 row count (3 scaffold + 10 values + 1 next)");
        MenuAction lastP0 = p0.get(p0.size() - 1).fragments().get(0).action();
        assertInstanceOf(MenuAction.ChangePage.class, lastP0,
                "page 0 must end with a next-page ChangePage nav row");
        assertEquals(1, ((MenuAction.ChangePage) lastP0).pageIndex(),
                "page 0 next nav must target page 1");

        // Page 1: scaffold + 10 values + prev + next = 15 rows.
        List<MenuLine> p1 = model.pages().get(1).lines();
        assertEquals(15, p1.size(), "page 1 row count (3 scaffold + 10 values + prev + next)");
        MenuAction prevP1 = p1.get(p1.size() - 2).fragments().get(0).action();
        MenuAction nextP1 = p1.get(p1.size() - 1).fragments().get(0).action();
        assertInstanceOf(MenuAction.ChangePage.class, prevP1, "page 1 prev nav present");
        assertInstanceOf(MenuAction.ChangePage.class, nextP1, "page 1 next nav present");
        assertEquals(0, ((MenuAction.ChangePage) prevP1).pageIndex());
        assertEquals(2, ((MenuAction.ChangePage) nextP1).pageIndex());

        // Page 2: scaffold + 5 values + prev = 9 rows; no next.
        List<MenuLine> p2 = model.pages().get(2).lines();
        assertEquals(9, p2.size(), "page 2 row count (3 scaffold + 5 values + prev)");
        MenuAction lastP2 = p2.get(p2.size() - 1).fragments().get(0).action();
        assertInstanceOf(MenuAction.ChangePage.class, lastP2,
                "page 2 must end with a prev-page ChangePage nav row");
        assertEquals(1, ((MenuAction.ChangePage) lastP2).pageIndex(),
                "page 2 prev nav must target page 1");

        // Value rows distribute in sorted order across pages: page 0 has
        // v00..v09, page 2 starts at v20.
        assertEquals("v00", p0.get(3).fragments().get(0).text());
        assertEquals("v09", p0.get(12).fragments().get(0).text());
        assertEquals("v20", p2.get(3).fragments().get(0).text());
        assertEquals("v24", p2.get(7).fragments().get(0).text());
    }

    // ------------------------------------------------------------------------
    // Redeem dispatch: OpenParamPicker → buildParamPicker + renderer
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("OpenParamPicker token redeem walks parentPath, resolves param, renders picker page")
    void openParamPickerToken_happyPath() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        TreeSub config = new TreeSub("config", "config tree");
        config.getParameterLookup().put("threadCount",
                new IntegerParameter("", "tc", (u, v) -> true, 1, 2, 4));
        root.getCommandLookup().put("CONFIG", config);

        AtomicReference<MenuModel> rendered = new AtomicReference<>();
        AtomicReference<String> seenParam = new AtomicReference<>();
        AtomicReference<List<String>> seenParentPath = new AtomicReference<>();
        io.github.dailystruggle.rtp.api.menu.MenuRenderer renderer = (uuid, m) -> rendered.set(m);
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder =
                (node, open, assembled) -> new MenuModel("page",
                        List.of(new MenuPage(List.of())));
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder(registry);
        MenuRedeemSubcommand.MenuParamPickerBuilder pickerBuilder =
                (parent, viewer, parentPath, paramName) -> {
                    seenParentPath.set(parentPath);
                    seenParam.set(paramName);
                    return builder.buildParamPicker(parent, viewer, perm -> true,
                            MenuConsumerProfile.defaultProfile(), parentPath, paramName);
                };
        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root, registry,
                uuid -> perm -> true, renderer, pageBuilder, pickerBuilder);

        UUID viewer = UUID.randomUUID();
        String token = registry.mint(viewer,
                new MenuAction.OpenParamPicker(new String[]{"config"}, "threadCount"),
                java.time.Duration.ofSeconds(30));
        Map<String, List<String>> params = new HashMap<>();
        params.put(MenuRedeemSubcommand.PARAM_TOKEN, List.of(token));

        boolean ok = redeem.onCommand(viewer, params, null, (Consumer<String>) m -> {});
        assertTrue(ok, "valid OpenParamPicker redeem must succeed");
        assertNotNull(rendered.get(), "renderer must receive the picker page");
        assertEquals("threadCount", seenParam.get());
        assertEquals(List.of("config"), seenParentPath.get());
    }

    @Test
    @DisplayName("OpenParamPicker rejects with menuInvalid when parameter does not exist on target")
    void openParamPickerToken_unknownParameter_rejects() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        TreeSub config = new TreeSub("config", "config tree");
        // Note: no "ghostParam" registered on config.
        root.getCommandLookup().put("CONFIG", config);

        io.github.dailystruggle.rtp.api.menu.MenuRenderer renderer = (uuid, m) -> { };
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder =
                (node, open, assembled) -> new MenuModel("p",
                        List.of(new MenuPage(List.of())));
        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder(registry);
        MenuRedeemSubcommand.MenuParamPickerBuilder pickerBuilder =
                (parent, viewer, parentPath, paramName) -> builder.buildParamPicker(
                        parent, viewer, perm -> true,
                        MenuConsumerProfile.defaultProfile(), parentPath, paramName);
        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root, registry,
                uuid -> perm -> true, renderer, pageBuilder, pickerBuilder);

        UUID viewer = UUID.randomUUID();
        String token = registry.mint(viewer,
                new MenuAction.OpenParamPicker(new String[]{"config"}, "ghostParam"),
                java.time.Duration.ofSeconds(30));
        Map<String, List<String>> params = new HashMap<>();
        params.put(MenuRedeemSubcommand.PARAM_TOKEN, List.of(token));

        List<String> messages = new ArrayList<>();
        boolean ok = redeem.onCommand(viewer, params, null, (Consumer<String>) messages::add);
        assertFalse(ok, "unknown parameter must be rejected (S-004)");
        assertFalse(messages.isEmpty(),
                "rejection must surface a configurable message (REQ-RTP-F-013)");
    }

    @Test
    @DisplayName("OpenParamPicker reaches redeem with picker-builder disabled → reject (S-004)")
    void openParamPickerToken_pickerDisabled_rejects() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();

        io.github.dailystruggle.rtp.api.menu.MenuRenderer renderer = (uuid, m) -> { };
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder =
                (node, open, assembled) -> new MenuModel("p",
                        List.of(new MenuPage(List.of())));
        // Stage-A.1 5-arg constructor: paramPickerBuilder defaults to null.
        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root, registry,
                uuid -> perm -> true, renderer, pageBuilder);

        UUID viewer = UUID.randomUUID();
        String token = registry.mint(viewer,
                new MenuAction.OpenParamPicker(new String[0], "x"),
                java.time.Duration.ofSeconds(30));
        Map<String, List<String>> params = new HashMap<>();
        params.put(MenuRedeemSubcommand.PARAM_TOKEN, List.of(token));

        List<String> messages = new ArrayList<>();
        boolean ok = redeem.onCommand(viewer, params, null, (Consumer<String>) messages::add);
        assertFalse(ok, "OpenParamPicker must reject when picker-builder is unwired");
        assertFalse(messages.isEmpty(),
                "rejection must surface a configurable message");
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static void assertArrayEqualsLocal(String[] expected, String[] actual, String msg) {
        assertEquals(Arrays.toString(expected), Arrays.toString(actual), msg);
    }

    /** Minimal root fixture (mirrors {@code MenuStageTwoTest.TestableRoot}). */
    private static final class TestableRoot extends BaseRTPCmdImpl {
        TestableRoot() { super(null); }
        @Override public String name() { return "rtp"; }
        @Override public String permission() { return ""; }
        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand) { return true; }
        @Override
        public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                    Predicate<String> permissionCheckMethod,
                                                    Consumer<String> messageMethod,
                                                    String[] args,
                                                    int i,
                                                    Map<String, CommandParameter> tempParameters) {
            return CompletableFuture.completedFuture(true);
        }
    }

    /** Sub that is itself a TreeCommand (can hold nested params). */
    private static final class TreeSub extends BaseRTPCmdImpl {
        private final String name;
        private final String description;
        TreeSub(String name, String description) {
            super(null);
            this.name = name;
            this.description = description;
        }
        @Override public String name() { return name; }
        @Override public String permission() { return ""; }
        @Override public String description() { return description; }
        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand) { return true; }
    }
}
