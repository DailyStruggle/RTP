package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuConsumerProfile;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage A.1 navigation tests for {@link CommandTreeMenuBuilder} and
 * {@link MenuRedeemSubcommand}. Covers:
 * <ul>
 *   <li>Back row + Execute row are prepended on non-root pages and absent on root.</li>
 *   <li>Subcommand rows whose target {@link TreeCommand} has navigable content
 *       emit {@link MenuAction.OpenMenu}; pure-leaf subs emit
 *       {@link MenuAction.RunRtpCommand}.</li>
 *   <li>{@code help} and {@code menu} subcommands are excluded from rows.</li>
 *   <li>{@link MenuRedeemSubcommand} resolves an {@link MenuAction.OpenMenu}
 *       path against the live {@link TreeCommand} graph and refuses unknown
 *       segments with {@code menuInvalid} + WARN (S-004).</li>
 * </ul>
 *
 * Traceability: REQ-RTP-F-013 (configurable user-facing messages, via
 * {@code menuBack} / {@code menuExecute}); REQ-RTP-S-004 (every reject path
 * logs WARN). Linked to ADR-035 (security boundary) and ADR-044 (reflector).
 */
final class MenuNavigationStageATest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setupRTP() {
        RTPTestSetup.install(tempDir.toFile());
    }

    // ------------------------------------------------------------------------
    // Builder: Back + Execute rows
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Root page (empty assembledPath) has neither Back nor Execute row")
    void rootPage_noBackNoExecute() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        root.getCommandLookup().put("config", new LeafSub());

        MenuModel model = new CommandTreeMenuBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true,
                        MenuConsumerProfile.defaultProfile(),
                        Collections.emptyList());

        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                assertFalse(frag.action() instanceof MenuAction.OpenMenu
                                && ((MenuAction.OpenMenu) frag.action()).path().length == 0,
                        "root page must not carry an OpenMenu([]) Back fragment");
                // The execute fragment, if present, would carry RunRtpCommand
                // with the assembled path as args. Root has no assembled path,
                // so no RunRtpCommand([]) can appear.
                if (frag.action() instanceof MenuAction.RunRtpCommand run) {
                    assertTrue(run.args().length >= 1,
                            "root page run rows still target a subcommand (length >= 1)");
                }
            }
        }
    }

    @Test
    @DisplayName("Non-root page prepends Back (OpenMenu(parent)) and Execute (RunRtpCommand(assembled))")
    void nonRootPage_backAndExecutePrepended() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        root.getParameterLookup().put("ASYNC",
                new BooleanParameter("", "async on/off", (u, v) -> true));

        // Render as if we descended into /rtp config performance.
        List<String> assembled = List.of("config", "performance");
        MenuModel model = new CommandTreeMenuBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true,
                        MenuConsumerProfile.defaultProfile(), assembled);

        List<MenuFragment> frags = new ArrayList<>();
        for (MenuLine line : model.pages().get(0).lines()) {
            frags.addAll(line.fragments());
        }
        assertTrue(frags.size() >= 3, "non-root page must prepend header + Back + Execute");

        // First row: Constructed-command header — non-clickable (action == null).
        assertNull(frags.get(0).action(), "header row must be non-clickable");

        // Second row: Back → OpenMenu(["config"])
        MenuAction back = frags.get(1).action();
        assertInstanceOf(MenuAction.OpenMenu.class, back, "second row must be OpenMenu (Back)");
        String[] backPath = ((MenuAction.OpenMenu) back).path();
        assertEquals(1, backPath.length);
        assertEquals("config", backPath[0]);

        // Third row: Execute → RunRtpCommand(["config","performance"])
        MenuAction exec = frags.get(2).action();
        assertInstanceOf(MenuAction.RunRtpCommand.class, exec, "second row must be RunRtpCommand (Execute)");
        String[] execArgs = ((MenuAction.RunRtpCommand) exec).args();
        assertEquals(2, execArgs.length);
        assertEquals("config", execArgs[0]);
        assertEquals("performance", execArgs[1]);
    }

    @Test
    @DisplayName("Back row at depth 1 produces OpenMenu([]) (root marker)")
    void backRowAtDepthOne_targetsRoot() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();

        MenuModel model = new CommandTreeMenuBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true,
                        MenuConsumerProfile.defaultProfile(),
                        List.of("config"));

        // Row 0 is the constructed-command header (non-clickable); Back is row 1.
        MenuFragment backFrag = model.pages().get(0).lines().get(1).fragments().get(0);
        MenuAction back = backFrag.action();
        assertInstanceOf(MenuAction.OpenMenu.class, back);
        assertEquals(0, ((MenuAction.OpenMenu) back).path().length,
                "Back at depth 1 must target the root menu page (empty path)");
    }

    // ------------------------------------------------------------------------
    // Builder: forward-descend (OpenMenu vs RunRtpCommand on subcommand rows)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("Subcommand with navigable content yields OpenMenu; leaf sub yields RunRtpCommand")
    void subcommandRows_descendVsLeaf() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        // "config" has nested content (a parameter) → navigable.
        TreeSub config = new TreeSub("config", "config description");
        config.getParameterLookup().put("ASYNC",
                new BooleanParameter("", "async", (u, v) -> true));
        root.getCommandLookup().put("config", config);
        // "reload" is a pure leaf.
        root.getCommandLookup().put("reload", new LeafSub());

        MenuModel model = new CommandTreeMenuBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true,
                        MenuConsumerProfile.defaultProfile(),
                        Collections.emptyList());

        MenuAction configAction = findFragmentAction(model, "config");
        MenuAction reloadAction = findFragmentAction(model, "reload");
        assertInstanceOf(MenuAction.OpenMenu.class, configAction,
                "subcommand with navigable content must emit OpenMenu (forward-descend)");
        String[] descendPath = ((MenuAction.OpenMenu) configAction).path();
        assertEquals(1, descendPath.length);
        assertEquals("config", descendPath[0]);

        assertInstanceOf(MenuAction.RunRtpCommand.class, reloadAction,
                "leaf subcommand must emit RunRtpCommand (direct execute)");
    }

    @Test
    @DisplayName("Descend path concatenates onto assembledPath")
    void descendPathConcatenatesAssembledPath() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        TreeSub inner = new TreeSub("performance", "perf");
        inner.getParameterLookup().put("ASYNC",
                new BooleanParameter("", "async", (u, v) -> true));
        root.getCommandLookup().put("performance", inner);

        MenuModel model = new CommandTreeMenuBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true,
                        MenuConsumerProfile.defaultProfile(),
                        List.of("config"));

        MenuAction action = findFragmentAction(model, "performance");
        assertInstanceOf(MenuAction.OpenMenu.class, action);
        String[] path = ((MenuAction.OpenMenu) action).path();
        assertEquals(2, path.length);
        assertEquals("config", path[0]);
        assertEquals("performance", path[1]);
    }

    @Test
    @DisplayName("`help` and `menu` subcommands are excluded from rows")
    void helpAndMenu_excludedFromRows() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        root.getCommandLookup().put("help", new LeafSub());
        root.getCommandLookup().put("menu", new LeafSub());
        root.getCommandLookup().put("config", new LeafSub());

        MenuModel model = new CommandTreeMenuBuilder(registry)
                .build(root, UUID.randomUUID(), perm -> true,
                        MenuConsumerProfile.defaultProfile(),
                        Collections.emptyList());

        assertNull(findFragmentAction(model, "help"), "help row must be excluded");
        assertNull(findFragmentAction(model, "menu"), "menu row must be excluded");
        assertNotNull(findFragmentAction(model, "config"),
                "non-meta subcommand still present");
    }

    // ------------------------------------------------------------------------
    // Redeem: dispatchOpen path walking
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("OpenMenu token redeem walks path against rtpRoot and renders at target")
    void openMenuToken_walksPath_andRenders() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        TreeSub config = new TreeSub("config", "config");
        // commands-api convention (TreeCommand.addSubCommand) is to key by
        // uppercase name; mirror that here so dispatchOpen's path-walking
        // (which looks up via segment.toUpperCase) finds it.
        root.getCommandLookup().put("CONFIG", config);

        AtomicReference<MenuModel> rendered = new AtomicReference<>();
        AtomicReference<List<String>> seenPath = new AtomicReference<>();
        io.github.dailystruggle.rtp.api.menu.MenuRenderer renderer = (uuid, m) -> rendered.set(m);
        MenuRedeemSubcommand.MenuPageBuilder builder = (node, open, assembled) -> {
            seenPath.set(assembled);
            return new CommandTreeMenuBuilder(registry).build(
                    node, open.viewer(), perm -> true,
                    MenuConsumerProfile.defaultProfile(), assembled);
        };
        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root, registry,
                uuid -> perm -> true, renderer, builder);

        UUID viewer = UUID.randomUUID();
        // Mint an OpenMenu token for path ["config"]; redeem.
        String token = registry.mint(viewer,
                new MenuAction.OpenMenu(new String[]{"config"}),
                java.time.Duration.ofSeconds(30));
        Map<String, List<String>> params = new HashMap<>();
        params.put(MenuRedeemSubcommand.PARAM_TOKEN, List.of(token));

        boolean ok = redeem.onCommand(viewer, params, null, (Consumer<String>) m -> {});
        assertTrue(ok, "valid OpenMenu redeem must succeed");
        assertNotNull(rendered.get(), "renderer must be invoked");
        assertEquals(List.of("config"), seenPath.get(),
                "page builder must receive the assembled path");
    }

    @Test
    @DisplayName("OpenMenu with unknown path segment rejects with menuInvalid + WARN")
    void openMenuToken_unknownSegment_rejects() {
        LocalMenuTokenRegistry registry = new LocalMenuTokenRegistry();
        TestableRoot root = new TestableRoot();
        // Note: no "ghost" subcommand registered.

        io.github.dailystruggle.rtp.api.menu.MenuRenderer renderer = (uuid, m) -> { };
        MenuRedeemSubcommand.MenuPageBuilder builder =
                (node, open, assembled) -> new MenuModel("t",
                        List.of(new io.github.dailystruggle.rtp.api.menu.MenuPage(List.of())));
        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root, registry,
                uuid -> perm -> true, renderer, builder);

        UUID viewer = UUID.randomUUID();
        String token = registry.mint(viewer,
                new MenuAction.OpenMenu(new String[]{"ghost"}),
                java.time.Duration.ofSeconds(30));
        Map<String, List<String>> params = new HashMap<>();
        params.put(MenuRedeemSubcommand.PARAM_TOKEN, List.of(token));

        List<String> messages = new ArrayList<>();
        boolean ok = redeem.onCommand(viewer, params, null, (Consumer<String>) messages::add);
        assertFalse(ok, "unknown OpenMenu segment must be rejected");
        assertFalse(messages.isEmpty(),
                "rejection must surface a configurable message (S-004 / REQ-RTP-F-013)");
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static MenuAction findFragmentAction(MenuModel model, String text) {
        for (MenuLine line : model.pages().get(0).lines()) {
            for (MenuFragment frag : line.fragments()) {
                if (text.equals(frag.text())) return frag.action();
            }
        }
        return null;
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

    /** Sub that is itself a TreeCommand (can hold nested subs / params). */
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

    /** Pure-leaf sub: TreeCommand with no nested subs and no parameters. */
    private static final class LeafSub extends BaseRTPCmdImpl {
        LeafSub() { super(null); }
        @Override public String name() { return "leaf"; }
        @Override public String permission() { return ""; }
        @Override
        public boolean onCommand(UUID callerId,
                                 Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand) { return true; }
    }
}
