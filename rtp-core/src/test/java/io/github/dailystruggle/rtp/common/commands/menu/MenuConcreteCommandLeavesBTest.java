package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MenuConcreteCommandLeavesB leaf dispatch unit tests")
class MenuConcreteCommandLeavesBTest {

    @TempDir
    Path tempDir;

    private UUID caller;
    private TestableRoot root;
    private AtomicReference<MenuModel> rendered;
    private MenuRedeemSubcommand redeem;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        caller = UUID.randomUUID();
        root = new TestableRoot();
        rendered = new AtomicReference<>();
        MenuRenderer renderer = (playerId, model) -> rendered.set(model);
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder = (node, open, assembled) -> new MenuModel("page:" + node.name(), List.of(new MenuPage(List.of())));
        MenuRedeemSubcommand.MenuParamPickerBuilder pickerBuilder = (node, open, path, param) -> new MenuModel("picker:" + param, List.of(new MenuPage(List.of())));
        MenuRedeemSubcommand.MenuConfigSubtreeBuilder configBuilder = new MenuRedeemSubcommand.MenuConfigSubtreeBuilder() {
            @Override
            public MenuModel buildSelector(UUID viewer) {
                return new MenuModel("config-dir:", List.of(new MenuPage(List.of())));
            }

            @Override
            public MenuModel buildSelector(UUID viewer, String subDir) {
                return new MenuModel("config-dir:" + subDir, List.of(new MenuPage(List.of())));
            }

            @Override
            public MenuModel buildFile(UUID viewer, String fileName) {
                return new MenuModel("config-file:" + fileName, List.of(new MenuPage(List.of())));
            }

            @Override
            public MenuModel buildKey(UUID viewer, String fileName, String paramName) {
                return new MenuModel("config-key:" + fileName + ":" + paramName, List.of(new MenuPage(List.of())));
            }
        };
        MenuRedeemSubcommand.MenuConfigSearchBuilder searchBuilder = (viewer, query, page) ->
                new MenuModel("config-search-results:" + query + ":" + page, List.of(new MenuPage(List.of())));
        MenuRedeemSubcommand.MenuInfoBookBuilder infoBuilder = (viewer, scope) ->
                new MenuModel("info-book:" + scope.kind(), List.of(new MenuPage(List.of())));

        redeem = new MenuRedeemSubcommand(
                root,
                id -> perm -> true,
                renderer,
                pageBuilder,
                pickerBuilder,
                (viewer, parentPath, paramName, prefill) -> true,
                configBuilder,
                null,
                searchBuilder,
                infoBuilder
        );
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
    }

    @Test
    @DisplayName("PickerCmd dispatches or rejects missing param")
    void pickerCmd_lifecycle() {
        MenuConcreteCommandLeavesB.PickerCmd cmd = new MenuConcreteCommandLeavesB.PickerCmd(redeem);
        assertEquals("picker", cmd.name());

        // Missing param rejects
        boolean rejected = cmd.onCommand(caller, Collections.emptyMap(), null, msg -> {});
        assertFalse(rejected);

        // With param succeeds
        Map<String, List<String>> params = new HashMap<>();
        params.put("param", List.of("radius"));
        params.put("path", List.of("regions.default"));
        boolean ok = cmd.onCommand(caller, params, null, msg -> {});
        assertTrue(ok);
        assertNotNull(rendered.get());
        assertEquals("picker:radius", rendered.get().title());
    }

    @Test
    @DisplayName("Curated selection leaves (WorldCmd, RegionCmd, BiomeCmd, PrefabCmd) dispatch")
    void selectionLeaves_lifecycle() {
        new MenuConcreteCommandLeavesB.WorldCmd(redeem).onCommand(caller, Collections.emptyMap(), null, msg -> {});
        assertNotNull(rendered.get());

        new MenuConcreteCommandLeavesB.RegionCmd(redeem).onCommand(caller, Collections.emptyMap(), null, msg -> {});
        assertNotNull(rendered.get());

        new MenuConcreteCommandLeavesB.BiomeCmd(redeem).onCommand(caller, Collections.emptyMap(), null, msg -> {});
        assertNotNull(rendered.get());

        new MenuConcreteCommandLeavesB.PrefabCmd(redeem).onCommand(caller, Collections.emptyMap(), null, msg -> {});
        assertNotNull(rendered.get());
    }

    @Test
    @DisplayName("PageCmd parses 1-indexed n parameter")
    void pageCmd_lifecycle() {
        MenuConcreteCommandLeavesB.PageCmd cmd = new MenuConcreteCommandLeavesB.PageCmd(redeem);
        boolean ok = cmd.onCommand(caller, Map.of("n", List.of("3")), null, msg -> {});
        assertTrue(ok);
        assertNotNull(rendered.get());

        boolean ok2 = cmd.onCommand(caller, Collections.emptyMap(), null, msg -> {});
        assertTrue(ok2);
    }

    @Test
    @DisplayName("ConfigCmd dispatches directory, file, and key")
    void configCmd_lifecycle() {
        MenuConcreteCommandLeavesB.ConfigCmd cmd = new MenuConcreteCommandLeavesB.ConfigCmd(redeem);

        // Bare
        cmd.onCommand(caller, Collections.emptyMap(), null, msg -> {});
        assertNotNull(rendered.get());
        assertEquals("config-dir:", rendered.get().title());

        // With dir
        cmd.onCommand(caller, Map.of("dir", List.of("worlds")), null, msg -> {});
        assertNotNull(rendered.get());
        assertEquals("config-dir:worlds", rendered.get().title());

        // With file
        cmd.onCommand(caller, Map.of("file", List.of("perf.yml")), null, msg -> {});
        assertNotNull(rendered.get());
        assertEquals("config-file:perf.yml", rendered.get().title());

        // With file and key
        cmd.onCommand(caller, Map.of("file", List.of("perf.yml"), "key", List.of("threads")), null, msg -> {});
        assertNotNull(rendered.get());
        assertEquals("config-key:perf.yml:threads", rendered.get().title());
    }

    @Test
    @DisplayName("ConfigSearchCmd dispatches search prompt and results")
    void configSearchCmd_lifecycle() {
        MenuConcreteCommandLeavesB.ConfigSearchCmd cmd = new MenuConcreteCommandLeavesB.ConfigSearchCmd(redeem, root);

        // Prompt
        cmd.onCommand(caller, Collections.emptyMap(), null, msg -> {});

        // Results with 1-based page
        cmd.onCommand(caller, Map.of("query", List.of("foo"), "page", List.of("2")), null, msg -> {});
        assertNotNull(rendered.get());
        assertEquals("config-search-results:foo:2", rendered.get().title());
    }

    @Test
    @DisplayName("StageCmd, UnstageCmd, ApplyCmd, DiscardCmd parameter validation")
    void stagingLeaves_validation() {
        MenuConcreteCommandLeavesB.StageCmd stage = new MenuConcreteCommandLeavesB.StageCmd(redeem);
        assertFalse(stage.onCommand(caller, Collections.emptyMap(), null, msg -> {}));

        MenuConcreteCommandLeavesB.UnstageCmd unstage = new MenuConcreteCommandLeavesB.UnstageCmd(redeem);
        assertFalse(unstage.onCommand(caller, Collections.emptyMap(), null, msg -> {}));

        MenuConcreteCommandLeavesB.ApplyCmd apply = new MenuConcreteCommandLeavesB.ApplyCmd(redeem);
        assertFalse(apply.onCommand(caller, Collections.emptyMap(), null, msg -> {}));

        MenuConcreteCommandLeavesB.DiscardCmd discard = new MenuConcreteCommandLeavesB.DiscardCmd(redeem);
        assertFalse(discard.onCommand(caller, Collections.emptyMap(), null, msg -> {}));
    }

    @Test
    @DisplayName("InfoCmd parses scope and text flag")
    void infoCmd_lifecycle() {
        MenuConcreteCommandLeavesB.InfoCmd cmd = new MenuConcreteCommandLeavesB.InfoCmd(redeem);

        // World scope
        cmd.onCommand(caller, Map.of("scope", List.of("world:nether")), null, msg -> {});
        assertNotNull(rendered.get());
        assertEquals("info-book:WORLD", rendered.get().title());

        // Region scope with text=true
        cmd.onCommand(caller, Map.of("scope", List.of("region:plains"), "text", List.of("true")), null, msg -> {});
    }

    @Test
    @DisplayName("AnvilCmd parses path, param, prefill, and mode")
    void anvilCmd_lifecycle() {
        MenuConcreteCommandLeavesB.AnvilCmd cmd = new MenuConcreteCommandLeavesB.AnvilCmd(redeem);

        // Missing param rejects
        assertFalse(cmd.onCommand(caller, Collections.emptyMap(), null, msg -> {}));

        // Valid param
        cmd.onCommand(caller, Map.of("param", List.of("radius"), "prefill", List.of("500"), "mode", List.of("stage")), null, msg -> {});
        cmd.onCommand(caller, Map.of("param", List.of("radius")), null, msg -> {});
    }

    @Test
    @DisplayName("MultiCmd parses kind, entry, and mutation op")
    void multiCmd_lifecycle() {
        MenuConcreteCommandLeavesB.MultiCmd cmd = new MenuConcreteCommandLeavesB.MultiCmd(redeem);

        // Missing kind rejects
        assertFalse(cmd.onCommand(caller, Collections.emptyMap(), null, msg -> {}));

        // Selector
        cmd.onCommand(caller, Map.of("kind", List.of("region")), null, msg -> {});

        // Entry
        cmd.onCommand(caller, Map.of("kind", List.of("region"), "entry", List.of("nether")), null, msg -> {});

        // Mutate add
        cmd.onCommand(caller, Map.of("kind", List.of("region"), "entry", List.of("nether"), "op", List.of("add")), null, msg -> {});

        // Mutate remove
        cmd.onCommand(caller, Map.of("kind", List.of("region"), "entry", List.of("nether"), "op", List.of("remove")), null, msg -> {});
    }

    private static final class TestableRoot extends BaseRTPCmdImpl implements TreeCommand {
        private final Map<String, CommandsAPICommand> commands = new HashMap<>();
        private final Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> params = new HashMap<>();

        TestableRoot() { super(null); }
        @Override public String name() { return "rtp"; }
        @Override public String permission() { return "rtp.use"; }
        @Override public Map<String, CommandsAPICommand> getCommandLookup() { return commands; }
        @Override public Map<String, io.github.dailystruggle.commandsapi.common.CommandParameter> getParameterLookup() { return params; }
        @Override public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            return true;
        }
    }
}
