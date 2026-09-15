package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MenuRedeemSubcommand Anvil response, Book ingestion, and Command response dispatch")
class MenuRedeemAnvilAndBookResponseTest {

    @TempDir
    Path tempDir;

    private TestRoot root;
    private UUID viewer;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        root = new TestRoot();
        viewer = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
    }

    @Test
    @DisplayName("dispatchPromptAnvilInput: skips staged parameter segments (name=value)")
    void anvil_skipsStagedKeyValueSegments() {
        TestSubTree sub = new TestSubTree("sub");
        root.addSubCommand(sub);

        AtomicReference<List<String>> openedPath = new AtomicReference<>();
        MenuRedeemSubcommand.AnvilInputOpener opener = new MenuRedeemSubcommand.AnvilInputOpener() {
            @Override
            public boolean open(UUID viewer, List<String> parentPath, String paramName, String prefill) {
                openedPath.set(parentPath);
                return true;
            }

            @Override
            public boolean open(UUID viewer, List<String> parentPath, String paramName, String prefill, MenuAction.Mode mode, MenuRedeemSubcommand.CartSink cartSink) {
                openedPath.set(parentPath);
                return true;
            }
        };

        MenuRenderer renderer = (u, m) -> {};
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder = (node, open, pathList) -> null;

        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(
                root,
                id -> perm -> true,
                renderer,
                pageBuilder,
                null,
                opener,
                null,
                null,
                null,
                null
        );

        String[] path = new String[]{"sub", "world=world_nether", "shape=circle"};
        MenuAction.PromptAnvilInput prompt = new MenuAction.PromptAnvilInput(path, "radius", "100", MenuAction.Mode.RUN);

        List<String> messages = new ArrayList<>();
        boolean result = false;
        try {
            result = redeem.dispatchPromptAnvilInput(viewer, prompt, msg -> messages.add(msg));
        } catch (Throwable t) {
            messages.add("EXCEPTION: " + t);
        }
        assertTrue(result, () -> "dispatchPromptAnvilInput rejected with messages: " + messages);
        assertNotNull(openedPath.get());
        assertEquals(List.of("sub", "world=world_nether", "shape=circle"), openedPath.get());
    }

    @Test
    @DisplayName("dispatchPromptAnvilInput: resolves suffixed .YML when bare segment lookup misses")
    void anvil_resolvesSuffixedYmlSegment() {
        TestSubTree configTree = new TestSubTree("CONFIG.YML");
        root.addSubCommand(configTree);

        AtomicBoolean opened = new AtomicBoolean(false);
        MenuRedeemSubcommand.AnvilInputOpener opener = (u, parentPath, paramName, prefill) -> {
            opened.set(true);
            return true;
        };

        MenuRenderer renderer = (u, m) -> {};
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder = (node, open, pathList) -> null;

        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(
                root,
                id -> perm -> true,
                renderer,
                pageBuilder,
                null,
                opener,
                null,
                null,
                null,
                null
        );

        String[] path = new String[]{"config"};
        MenuAction.PromptAnvilInput prompt = new MenuAction.PromptAnvilInput(path, "param", "", MenuAction.Mode.RUN);

        List<String> messages = new ArrayList<>();
        boolean result = redeem.dispatchPromptAnvilInput(viewer, prompt, messages::add);
        assertTrue(result, () -> "dispatchPromptAnvilInput rejected with messages: " + messages);
        assertTrue(opened.get());
    }

    @Test
    @DisplayName("dispatchPromptAnvilInput: rejects and emits S-004 error when segment is not a TreeCommand")
    void anvil_rejectsWhenSegmentNotTreeCommand() {
        // Add a non-tree command
        NonTreeCmd nonTree = new NonTreeCmd("leaf");
        root.addSubCommand(nonTree);

        MenuRedeemSubcommand.AnvilInputOpener opener = (u, parentPath, paramName, prefill) -> true;

        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(
                root,
                id -> perm -> true,
                (MenuRenderer) null,
                null,
                null,
                opener,
                null,
                null,
                null,
                null
        );
        List<String> messages = new ArrayList<>();
        String[] path = new String[]{"leaf", "child"};
        MenuAction.PromptAnvilInput prompt = new MenuAction.PromptAnvilInput(path, "param", "", MenuAction.Mode.RUN);

        boolean result = redeem.dispatchPromptAnvilInput(viewer, prompt, messages::add);
        assertFalse(result);
        assertFalse(messages.isEmpty(), "Must emit S-004 rejection message");
    }

    @Test
    @DisplayName("dispatchPromptAnvilInput: handles opener throwing RuntimeException (fails closed S-004)")
    void anvil_handlesOpenerException() {
        MenuRedeemSubcommand.AnvilInputOpener throwingOpener = (u, parentPath, paramName, prefill) -> {
            throw new RuntimeException("Simulated anvil opening error");
        };

        MenuRenderer renderer = (u, m) -> {};
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder = (node, open, pathList) -> null;

        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(
                root,
                id -> perm -> true,
                renderer,
                pageBuilder,
                null,
                throwingOpener,
                null,
                null,
                null,
                null
        );

        List<String> messages = new ArrayList<>();
        MenuAction.PromptAnvilInput prompt = new MenuAction.PromptAnvilInput(new String[0], "param", "", MenuAction.Mode.RUN);

        boolean result = redeem.dispatchPromptAnvilInput(viewer, prompt, messages::add);
        assertFalse(result);
        assertFalse(messages.isEmpty());
    }

    @Test
    @DisplayName("dispatchPromptAnvilInput: handles opener returning false (refused)")
    void anvil_handlesOpenerRefusal() {
        MenuRedeemSubcommand.AnvilInputOpener refusingOpener = (u, parentPath, paramName, prefill) -> false;

        MenuRenderer renderer = (u, m) -> {};
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder = (node, open, pathList) -> null;

        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(
                root,
                id -> perm -> true,
                renderer,
                pageBuilder,
                null,
                refusingOpener,
                null,
                null,
                null,
                null
        );

        List<String> messages = new ArrayList<>();
        MenuAction.PromptAnvilInput prompt = new MenuAction.PromptAnvilInput(new String[0], "param", "", MenuAction.Mode.RUN);

        boolean result = redeem.dispatchPromptAnvilInput(viewer, prompt, messages::add);
        assertFalse(result);
        assertFalse(messages.isEmpty());
    }

    @Test
    @DisplayName("Book response: command response tap integration renders book model")
    void book_commandResponseTapRendering() {
        AtomicReference<MenuModel> renderedModel = new AtomicReference<>();
        MenuRenderer renderer = (u, model) -> renderedModel.set(model);
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder = (node, open, pathList) -> null;

        InfoBookBuilder bookBuilder = new InfoBookBuilder();
        MenuRedeemSubcommand.MenuInfoBookBuilder infoBook = (v, scope) -> bookBuilder.build(root, v, scope);

        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(
                root,
                id -> perm -> true,
                renderer,
                pageBuilder,
                null,
                null,
                null,
                null,
                null,
                infoBook
        );

        List<String> messages = new ArrayList<>();
        boolean result = redeem.dispatchOpenInfo(viewer, new MenuAction.OpenInfo(MenuAction.InfoScopeToken.global()), messages::add);
        assertTrue(result, () -> "dispatchOpenInfo rejected with messages: " + messages);
        assertNotNull(renderedModel.get());
        assertTrue(renderedModel.get().title().contains("info") || !renderedModel.get().pages().isEmpty());
    }

    @Test
    @DisplayName("Command response: MenuWiringSupportInstaller admin cmd executes renderer on click response")
    void commandResponse_adminPanelExecution() {
        AtomicReference<MenuModel> adminRendered = new AtomicReference<>();
        MenuRenderer renderer = (u, model) -> adminRendered.set(model);

        MenuPlatformBindings bindings = new MenuPlatformBindings(
                id -> perm -> true,
                renderer,
                null
        );

        MenuWiringSupport.attachTo(root, bindings);

        CommandsAPICommand adminCmd = root.getCommandLookup().get("ADMIN");
        assertNotNull(adminCmd);

        // Execute bare /rtp admin command
        boolean cmdResult = adminCmd.onCommand(viewer, Map.of(), null);
        assertTrue(cmdResult);
        assertNotNull(adminRendered.get(), "Admin command execution must render the admin panel book model");
    }

    @Test
    @DisplayName("Anvil cart sink: STAGE mode places input into cart and triggers file re-render")
    void anvil_cartSinkStageFlow() {
        AtomicReference<MenuModel> fileRendered = new AtomicReference<>();
        MenuRenderer renderer = (u, model) -> fileRendered.set(model);
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder = (node, open, pathList) -> null;

        AtomicReference<MenuRedeemSubcommand.CartSink> capturedSink = new AtomicReference<>();
        MenuRedeemSubcommand.AnvilInputOpener opener = new MenuRedeemSubcommand.AnvilInputOpener() {
            @Override
            public boolean open(UUID viewer, List<String> parentPath, String paramName, String prefill) {
                return true;
            }

            @Override
            public boolean open(UUID u, List<String> parentPath, String paramName, String prefill, MenuAction.Mode mode, MenuRedeemSubcommand.CartSink cartSink) {
                capturedSink.set(cartSink);
                // Simulate player typing "1500" into anvil and submitting
                cartSink.stage(u, "config", paramName, "1500");
                return true;
            }
        };

        MenuRedeemSubcommand.MenuConfigSubtreeBuilder subtreeBuilder = new MenuRedeemSubcommand.MenuConfigSubtreeBuilder() {
            @Override public MenuModel buildSelector(UUID viewer) { return null; }
            @Override public MenuModel buildFile(UUID viewer, String fileName, java.util.LinkedHashMap<String, String> staged) {
                return new MenuModel("Config: " + fileName + " staged=" + staged.size(), List.of(new MenuPage(List.of())));
            }
            @Override public MenuModel buildFile(UUID viewer, String fileName) { return null; }
            @Override public MenuModel buildKey(UUID viewer, String fileName, String paramName) { return null; }
        };

        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(
                root,
                id -> perm -> true,
                renderer,
                pageBuilder,
                null,
                opener,
                subtreeBuilder,
                null,
                null,
                null
        );

        TestSubTree configTree = new TestSubTree("CONFIG.YML");
        root.addSubCommand(configTree);

        String[] path = new String[]{"config"};
        MenuAction.PromptAnvilInput prompt = new MenuAction.PromptAnvilInput(path, "radius", "100", MenuAction.Mode.STAGE);

        List<String> messages = new ArrayList<>();
        boolean result = redeem.dispatchPromptAnvilInput(viewer, prompt, messages::add);
        assertTrue(result, () -> "Rejected with messages: " + messages);
        assertNotNull(capturedSink.get());

        // Verify cart has staged value
        Map<String, String> cart = redeem.snapshotCart(viewer, "config");
        assertEquals("1500", cart.get("radius"));
    }

    @Test
    @DisplayName("ConfigSearch book response: executes search builder and renders book results")
    void book_configSearchExecution() {
        AtomicReference<MenuModel> searchRendered = new AtomicReference<>();
        MenuRenderer renderer = (u, model) -> searchRendered.set(model);
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder = (node, open, pathList) -> null;

        MenuRedeemSubcommand.MenuConfigSearchBuilder searchBuilder = (v, query, page) ->
                new MenuModel("Search: " + query + " page=" + page, List.of(new MenuPage(List.of())));

        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(
                root,
                id -> perm -> true,
                renderer,
                pageBuilder,
                null,
                null,
                null,
                null,
                searchBuilder,
                null
        );

        List<String> messages2 = new ArrayList<>();
        boolean result = redeem.dispatchOpenConfigSearchResults(
                viewer,
                new MenuAction.OpenConfigSearchResults("radius", 0),
                messages2::add
        );
        assertTrue(result, () -> "Rejected with messages: " + messages2);
        assertNotNull(searchRendered.get());
        assertTrue(searchRendered.get().title().contains("radius"));
    }

    private static final class TestRoot extends BaseRTPCmdImpl implements TreeCommand {
        TestRoot() { super(null); }
        @Override public String name() { return "rtp"; }
        @Override public String permission() { return "rtp.use"; }
        @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) { return true; }
    }

    private static final class TestSubTree extends BaseRTPCmdImpl implements TreeCommand {
        private final String name;
        TestSubTree(String name) {
            super(null);
            this.name = name;
        }
        @Override public String name() { return name; }
        @Override public String permission() { return "rtp.use"; }
        @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) { return true; }
    }

    private static final class NonTreeCmd extends BaseRTPCmdImpl {
        private final String name;
        NonTreeCmd(String name) {
            super(null);
            this.name = name;
        }
        @Override public String name() { return name; }
        @Override public String permission() { return "rtp.use"; }
        @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) { return true; }
    }
}
