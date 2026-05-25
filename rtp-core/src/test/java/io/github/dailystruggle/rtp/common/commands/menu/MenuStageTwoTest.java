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
import io.github.dailystruggle.rtp.api.menu.YamlCommentLookup;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 2 of CHECKLIST-generalized-menu.md: covers the rtp-core registry, the
 * {@code MenuRedeemSubcommand}, the {@code CommandTreeMenuBuilder} reflector,
 * and the {@code ConfigMenuConsumerProfile} hover-text fallback chain.
 *
 * <p>Each block carries an explicit reference to the checklist item it
 * exercises.
 */
public class MenuStageTwoTest {

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        // Many of the rejection paths in MenuRedeemSubcommand call RTP.log and
        // RTP.serverAccessor; RTPTestSetup gives both a non-null mock.
        RTPTestSetup.install(tempDir.toFile());
    }

    // ------------------------------------------------------------------------
    // ADR-050 Stage 3beta.D.1 (2026-05-24): the six token-mechanic tests
    // (registry CAS/sweep/cap/argcheck, redeemRejectsMissingToken,
    // redeemRejectsNonRunActionAsProtocolError) were deleted with the
    // token-redeem dispatch path. They asserted invariants of the
    // MenuTokenRegistry + the `token=` redeem branch, both of which are
    // gone. Concrete-command grammar coverage lives in
    // ReqRtpMenuConcreteCommandsTest (36 cases); renderer command-string
    // emission lives in BookMenuRendererTest (34 cases).
    // ------------------------------------------------------------------------

    // ------------------------------------------------------------------------
    // 2.3 — Reflector hides inaccessible nodes; emits correct fragments
    // ------------------------------------------------------------------------

    @Test
    void reflectorHidesInaccessibleSubcommandsAndParameters() {
        TestableRoot root = new TestableRoot();
        root.getCommandLookup().put("config",
                new StubSub("rtp.config", "config desc"));
        root.getCommandLookup().put("admin",
                new StubSub("rtp.admin", "admin desc"));
        root.getParameterLookup().put("public", anonParam("", "pub"));
        root.getParameterLookup().put("secret", anonParam("rtp.secret", "sec"));

        Predicate<String> caller = perm -> perm.equals("rtp.config"); // only config visible

        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();
        MenuModel model = builder.build(root, UUID.randomUUID(), caller,
                MenuConsumerProfile.defaultProfile());

        assertEquals(1, model.pages().size());
        MenuPage page = model.pages().get(0);
        List<String> names = new ArrayList<>();
        for (MenuLine line : page.lines()) {
            for (MenuFragment frag : line.fragments()) {
                names.add(frag.text());
            }
        }
        assertTrue(names.contains("config"), "visible subcommand kept");
        assertFalse(names.contains("admin"), "permission-denied subcommand hidden");
        assertTrue(names.contains("public"), "no-perm parameter kept");
        assertFalse(names.contains("secret"), "permission-denied parameter hidden");
    }

    @Test
    void reflectorEmitsRunForSubcommandAndPickerForEnumerableParameter() {
        // Stage A.2: a parameter with non-empty suggestions (here:
        // IntegerParameter with curated values 1/2/4) opens a value-picker
        // sub-page on click. A parameter without suggestions falls back to
        // the pre-Stage-A.2 SuggestInput chat-prefill (covered separately).
        TestableRoot root = new TestableRoot();
        root.getCommandLookup().put("config",
                new StubSub("", "config desc"));
        root.getParameterLookup().put("threadCount",
                new IntegerParameter("", "thread count", (u, v) -> true, 1, 2, 4));

        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();
        MenuModel model = builder.build(root, UUID.randomUUID(), perm -> true,
                MenuConsumerProfile.defaultProfile());

        MenuAction subAction = null;
        MenuAction paramAction = null;
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                if ("config".equals(frag.text())) subAction = frag.action();
                if ("threadCount".equals(frag.text())) paramAction = frag.action();
            }
        }
        assertInstanceOf(MenuAction.RunRtpCommand.class, subAction,
                "subcommand fragment must carry RunRtpCommand");
        assertInstanceOf(MenuAction.OpenParamPicker.class, paramAction,
                "enumerable parameter fragment must carry OpenParamPicker (Stage A.2)");
        MenuAction.OpenParamPicker picker = (MenuAction.OpenParamPicker) paramAction;
        assertEquals("threadCount", picker.paramName());
        // Empty parentPath: the root call passed no assembledPath, so the
        // picker's parent is the /rtp root itself.
        assertEquals(0, picker.parentPath().length);
    }

    @Test
    void reflectorOpensParamPickerEvenWhenParameterHasNoSuggestions() {
        // Updated contract: parameters with no suggestions still emit an
        // OpenParamPicker so the player reaches a sub-page that renders the
        // "✎ type a custom value..." chat-prefill row plus any (empty)
        // suggestion list. The earlier SuggestInput-only fallback meant a
        // free-form parameter such as `regions add` was "stuck" — clicking
        // it produced no visible prompt under renderers that don't surface
        // chat suggestions inline.
        TestableRoot root = new TestableRoot();
        root.getParameterLookup().put("freeform", anonParam("", "no suggestions"));

        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();
        MenuModel model = builder.build(root, UUID.randomUUID(), perm -> true,
                MenuConsumerProfile.defaultProfile());

        MenuAction paramAction = null;
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                if ("freeform".equals(frag.text())) paramAction = frag.action();
            }
        }
        assertInstanceOf(MenuAction.OpenParamPicker.class, paramAction,
                "no-suggestion parameter must still open the picker sub-page");
    }

    // ------------------------------------------------------------------------
    // 2.4 / 2.5 — Hover-text three-step fallback
    // ------------------------------------------------------------------------

    @Test
    void hoverFallbackPrefersYamlCommentThenTypeBoundsThenNull() {
        TestableRoot root = new TestableRoot();
        // Three parameters exercising each fallback rung.
        root.getParameterLookup().put("commented",
                new BooleanParameter("", "b", (u, v) -> true));
        root.getParameterLookup().put("typedNoComment",
                new BooleanParameter("", "b", (u, v) -> true));
        root.getParameterLookup().put("unknownType", anonParam("", "anon"));

        YamlCommentLookup lookup = (file, key) ->
                "commented".equals(key) ? Optional.of("the comment from yaml") : Optional.empty();
        MenuConsumerProfile profile = new MenuConsumerProfile() {
            @Override
            public String suggestPrefix(java.util.Deque<String> path, String name) {
                return "/x " + name + ":";
            }
            @Override
            public YamlCommentLookup commentLookup() { return lookup; }
        };

        CommandTreeMenuBuilder builder = new CommandTreeMenuBuilder();
        MenuModel model = builder.build(root, UUID.randomUUID(), perm -> true, profile);

        Map<String, MenuFragment> byName = new HashMap<>();
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                byName.put(frag.text(), frag);
            }
        }
        // Rung 1: YAML comment.
        assertEquals("the comment from yaml", byName.get("commented").hover());
        // Rung 2: type (+ bounds collapsed to "boolean: true, false" when small).
        String typedHover = byName.get("typedNoComment").hover();
        assertNotNull(typedHover);
        assertTrue(typedHover.toLowerCase().contains("boolean"),
                "fallback hover must mention type, was: " + typedHover);
        // Rung 3: null when no comment and no recognised type.
        assertNull(byName.get("unknownType").hover(),
                "anonymous parameter with no comment and no recognised type has no hover");
    }

    // ------------------------------------------------------------------------
    // 2.4 — ConfigMenuConsumerProfile suggest prefix shape
    // ------------------------------------------------------------------------

    @Test
    void configProfileBuildsExpectedSuggestPrefix() {
        ConfigMenuConsumerProfile profile = new ConfigMenuConsumerProfile(name -> null);
        java.util.ArrayDeque<String> path = new java.util.ArrayDeque<>();
        path.addLast("rtp");
        path.addLast("config");
        path.addLast("performance");
        assertEquals("/rtp config performance ASYNC=",
                profile.suggestPrefix(path, "ASYNC"));
    }

    @Test
    void configProfileResolverFailureFallsBackToEmptyComment() {
        ConfigMenuConsumerProfile profile = new ConfigMenuConsumerProfile(name -> {
            throw new RuntimeException("boom");
        });
        assertTrue(profile.commentLookup().commentFor("config", "key").isEmpty(),
                "resolver exceptions must collapse to Optional.empty()");
    }

    // ------------------------------------------------------------------------
    // 4.2.a — Open-page path on token-less /rtp menu invocations
    // ------------------------------------------------------------------------

    @Test
    void openPageDispatchesToRendererWhenNoTokenAndRendererWired() {
        TestableRoot root = new TestableRoot();
        root.getCommandLookup().put("config", new StubSub("", "config desc"));

        // Capturing renderer + page builder closures (the rtp-plugin wire-up
        // supplies analogous closures around CommandTreeMenuBuilder + the live
        // permission probe + ConfigMenuConsumerProfile).
        AtomicReference<UUID> renderedFor = new AtomicReference<>();
        AtomicReference<MenuModel> renderedModel = new AtomicReference<>();
        io.github.dailystruggle.rtp.api.menu.MenuRenderer renderer = (uuid, model) -> {
            renderedFor.set(uuid);
            renderedModel.set(model);
        };
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder =
                (node, open, assembledPath) -> new CommandTreeMenuBuilder()
                        .build(node, open.viewer(), perm -> true,
                                MenuConsumerProfile.defaultProfile(), assembledPath);

        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root,
                uuid -> perm -> true, renderer, pageBuilder);

        UUID viewer = UUID.randomUUID();
        boolean ok = redeem.onCommand(viewer, new HashMap<>(), null,
                (Consumer<String>) m -> {});
        assertTrue(ok, "no-token open-page path must succeed when renderer is wired");
        assertEquals(viewer, renderedFor.get(), "renderer invoked with viewer UUID");
        assertNotNull(renderedModel.get(), "renderer received a non-null MenuModel");
        // Built from root → must contain the "config" subcommand fragment.
        boolean sawConfig = false;
        for (MenuLine line : renderedModel.get().pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                if ("config".equals(frag.text())) sawConfig = true;
            }
        }
        assertTrue(sawConfig, "reflected root page must include the 'config' subcommand");
        assertEquals(0, root.dispatchCount, "open-page must not invoke command dispatch");
    }

    @Test
    void openPageFallsBackToMenuInvalidWhenNoRendererWired() {
        // Backward-compat: the Stage 3 wire-up uses the 3-arg constructor (no
        // renderer/builder). A token-less invocation must still reject with
        // menuInvalid + WARN, unchanged from pre-Stage-4 behaviour.
        TestableRoot root = new TestableRoot();
        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root,
                uuid -> perm -> true);

        List<String> messages = new ArrayList<>();
        boolean ok = redeem.onCommand(UUID.randomUUID(), new HashMap<>(), null,
                (Consumer<String>) messages::add);
        assertFalse(ok, "without a wired renderer the no-token path stays a rejection");
        assertFalse(messages.isEmpty(),
                "rejection must surface a configurable message (S-004 / REQ-RTP-F-013)");
        assertEquals(0, root.dispatchCount);
    }

    @Test
    void openPageRejectsWithMenuInvalidWhenRendererThrows() {
        TestableRoot root = new TestableRoot();

        // Renderer that simulates S-006 (e.g. offline player) by throwing.
        io.github.dailystruggle.rtp.api.menu.MenuRenderer renderer = (uuid, model) -> {
            throw new IllegalStateException("simulated offline player");
        };
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder =
                (node, open, assembledPath) -> new MenuModel("title",
                        List.of(new MenuPage(List.of(
                                MenuLine.of(MenuFragment.plain("anything"))))));

        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root,
                uuid -> perm -> true, renderer, pageBuilder);

        List<String> messages = new ArrayList<>();
        boolean ok = redeem.onCommand(UUID.randomUUID(), new HashMap<>(), null,
                (Consumer<String>) messages::add);
        assertFalse(ok, "renderer exception must collapse to a rejection (S-004)");
        assertFalse(messages.isEmpty(),
                "renderer-failure rejection must surface a configurable message");
    }

    @Test
    void openPagePlumbsOneIndexedPageParameterAsZeroBasedToBuilder() {
        // CHECKLIST 5.3.b/g: `/rtp menu page:3` must arrive at the page builder
        // as MenuOpenRequest.pageIndex() == 2 (1-indexed wire → 0-indexed model).
        // Missing/invalid values must default to page 1 / index 0.
        TestableRoot root = new TestableRoot();

        AtomicReference<io.github.dailystruggle.rtp.api.menu.MenuOpenRequest> seen =
                new AtomicReference<>();
        io.github.dailystruggle.rtp.api.menu.MenuRenderer renderer = (uuid, model) -> {};
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder = (node, open, assembled) -> {
            seen.set(open);
            return new MenuModel("t", List.of(new MenuPage(List.of(
                    MenuLine.of(MenuFragment.plain("x"))))));
        };
        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root,
                uuid -> perm -> true, renderer, pageBuilder);

        UUID viewer = UUID.randomUUID();

        // page:3 → idx 2.
        Map<String, List<String>> params = new HashMap<>();
        params.put(MenuRedeemSubcommand.PARAM_PAGE, List.of("3"));
        assertTrue(redeem.onCommand(viewer, params, null, (Consumer<String>) m -> {}));
        assertNotNull(seen.get(), "page builder must be invoked");
        assertEquals(viewer, seen.get().viewer());
        assertEquals(2, seen.get().pageIndex(),
                "1-indexed wire value 3 must translate to 0-indexed pageIndex 2");

        // missing → idx 0.
        seen.set(null);
        assertTrue(redeem.onCommand(viewer, new HashMap<>(), null,
                (Consumer<String>) m -> {}));
        assertEquals(0, seen.get().pageIndex(),
                "missing page parameter must default to pageIndex 0");

        // non-numeric → idx 0 (defensive backstop; the param predicate
        // already filters at the commands-api parser).
        seen.set(null);
        Map<String, List<String>> bad = new HashMap<>();
        bad.put(MenuRedeemSubcommand.PARAM_PAGE, List.of("notanumber"));
        assertTrue(redeem.onCommand(viewer, bad, null, (Consumer<String>) m -> {}));
        assertEquals(0, seen.get().pageIndex(),
                "non-numeric page value must collapse to pageIndex 0");

        // 0 or negative → idx 0.
        seen.set(null);
        Map<String, List<String>> zero = new HashMap<>();
        zero.put(MenuRedeemSubcommand.PARAM_PAGE, List.of("0"));
        assertTrue(redeem.onCommand(viewer, zero, null, (Consumer<String>) m -> {}));
        assertEquals(0, seen.get().pageIndex(),
                "page:0 must collapse to pageIndex 0 (wire is 1-indexed)");
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static void assertArrayEquals(String[] expected, String[] actual) {
        assertEquals(java.util.Arrays.toString(expected), java.util.Arrays.toString(actual));
    }

    /** Concrete CommandParameter for tests (the base class is abstract). */
    private static CommandParameter anonParam(String permission, String description) {
        return new CommandParameter(permission, description, (u, v) -> true) {
            @Override
            public java.util.Set<String> values() {
                return java.util.Collections.emptySet();
            }
        };
    }

    /** Minimal TreeCommand fixture: records dispatched args, exposes lookup maps. */
    private static final class TestableRoot extends BaseRTPCmdImpl {

        int dispatchCount = 0;
        String[] lastArgs = null;

        TestableRoot() { super(null); }

        @Override public String name() { return "rtp"; }
        @Override public String permission() { return ""; }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand) {
            // Not used in these tests; the redeem path enters via the args-form
            // onCommand below.
            return true;
        }

        @Override
        public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                    Predicate<String> permissionCheckMethod,
                                                    Consumer<String> messageMethod,
                                                    String[] args,
                                                    int i,
                                                    Map<String, CommandParameter> tempParameters) {
            dispatchCount++;
            lastArgs = args == null ? null : args.clone();
            return CompletableFuture.completedFuture(true);
        }
    }

    /** Stub subcommand exposing a fixed permission + description. */
    private static final class StubSub extends BaseRTPCmdImpl {
        private final String permission;
        private final String description;

        StubSub(String permission, String description) {
            super(null);
            this.permission = permission;
            this.description = description;
        }

        @Override public String name() { return "stub"; }
        @Override public String permission() { return permission; }
        @Override public String description() { return description; }

        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand) {
            return true;
        }
    }
}
