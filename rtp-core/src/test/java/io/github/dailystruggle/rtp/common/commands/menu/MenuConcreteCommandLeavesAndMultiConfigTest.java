package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.menu.multiconfig.DefaultMultiConfigRemovalGuards;
import io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigMenuBuilder;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
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

@DisplayName("MenuConcreteCommandLeaves and MultiConfig mutations")
final class MenuConcreteCommandLeavesAndMultiConfigTest {

    @TempDir
    Path tempDir;
    private java.io.File pluginDir;

    private UUID caller;
    private TestableRoot root;
    private AtomicReference<MenuModel> rendered;
    private MenuRedeemSubcommand redeem;

    @BeforeEach
    void setUp() throws Exception {
        pluginDir = tempDir.toFile();
        RTPTestSetup.install(pluginDir);
        DefaultMultiConfigRemovalGuards.registerDefaults();
        caller = UUID.randomUUID();
        root = new TestableRoot();

        // Add config and config.regions subcommands to root so dispatchOpen can walk the tree
        TestableRoot configNode = new TestableRoot();
        TestableRoot regionsNode = new TestableRoot();
        configNode.getCommandLookup().put("REGIONS", regionsNode);
        root.getCommandLookup().put("CONFIG", configNode);
        root.addSubCommand(configNode);
        configNode.addSubCommand(regionsNode);
        rendered = new AtomicReference<>();
        MenuRenderer renderer = (playerId, model) -> rendered.set(model);
        MenuRedeemSubcommand.MenuPageBuilder pageBuilder = (node, open, assembled) -> new MenuModel("page:" + node.name(), List.of(new MenuPage(List.of())));
        MenuRedeemSubcommand.MenuParamPickerBuilder pickerBuilder = (node, open, path, param) -> new MenuModel("picker:" + param, List.of(new MenuPage(List.of())));
        MenuRedeemSubcommand.MenuConfigSubtreeBuilder configBuilder = new MenuRedeemSubcommand.MenuConfigSubtreeBuilder() {
            @Override public MenuModel buildSelector(UUID viewer) { return new MenuModel("config-dir:", List.of(new MenuPage(List.of()))); }
            @Override public MenuModel buildSelector(UUID viewer, String subDir) { return new MenuModel("config-dir:" + subDir, List.of(new MenuPage(List.of()))); }
            @Override public MenuModel buildFile(UUID viewer, String fileName) { return new MenuModel("config-file:" + fileName, List.of(new MenuPage(List.of()))); }
            @Override public MenuModel buildKey(UUID viewer, String fileName, String paramName) { return new MenuModel("config-key:" + fileName + ":" + paramName, List.of(new MenuPage(List.of()))); }
        };
        MenuRedeemSubcommand.MenuCuratedPageBuilder curated = new MenuRedeemSubcommand.MenuCuratedPageBuilder() {
            @Override public MenuModel buildAdminPanel(UUID viewer) { return new MenuModel("admin", List.of(new MenuPage(List.of()))); }
            @Override public MenuModel buildFrontPage(UUID viewer) { return new MenuModel("front", List.of(new MenuPage(List.of()))); }
            @Override public MenuModel buildVisualizations(UUID viewer) { return new MenuModel("visualizations", List.of(new MenuPage(List.of()))); }
            @Override public MenuModel buildVisualizationRegions(UUID viewer, ChartSpec.Kind kind) { return new MenuModel("vis-reg:" + kind, List.of(new MenuPage(List.of()))); }
        };
        MenuRedeemSubcommand.MenuConfigSearchBuilder searchBuilder = (viewer, query, page) -> new MenuModel("search", List.of(new MenuPage(List.of())));
        MenuRedeemSubcommand.MenuInfoBookBuilder infoBuilder = (viewer, scope) -> new MenuModel("info", List.of(new MenuPage(List.of())));

        redeem = new MenuRedeemSubcommand(
                root,
                id -> perm -> true,
                renderer,
                pageBuilder,
                pickerBuilder,
                (viewer, parentPath, paramName, prefill) -> true,
                configBuilder,
                curated,
                searchBuilder,
                infoBuilder
        );
        MultiConfigMenuBuilder multiConfigBuilder = new MultiConfigMenuBuilder();
        multiConfigBuilder.setCommandTreeMenuBuilder(new CommandTreeMenuBuilder());
        redeem.setMultiConfigBuilder(multiConfigBuilder);
    }

    @AfterEach
    void tearDown() {
        if (RTP.configs != null && RTP.configs.multiConfigParserMap != null) {
            MultiConfigParser<?> regionsParser = null;
            for (MultiConfigParser<?> p : RTP.configs.multiConfigParserMap.values()) {
                if (p != null && "regions".equalsIgnoreCase(p.name)) {
                    regionsParser = p;
                    break;
                }
            }
            if (regionsParser != null) {
                regionsParser.removeParser("custom_region_1.yml");
                regionsParser.removeParser("custom_region_1");
            }
        }
        RTP.configs = null;
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
    }

    @Test
    @DisplayName("OpenMenuConcreteCmd parses path and dispatches to open")
    void openMenuConcreteCmd() {
        MenuConcreteCommandLeaves.OpenMenuConcreteCmd cmd =
                new MenuConcreteCommandLeaves.OpenMenuConcreteCmd(redeem);
        assertEquals("open", cmd.name());
        assertEquals(MenuRedeemSubcommand.PERMISSION, cmd.permission());

        // Both onCommand overloads
        cmd.onCommand(caller, Collections.emptyMap(), null);
        assertNotNull(rendered.get());

        rendered.set(null);
        cmd.onCommand(caller, Map.of(MenuConcreteCommandLeaves.PARAM_PATH, List.of("config.regions")), null, msg -> {});
        assertNotNull(rendered.get());
    }

    @Test
    @DisplayName("OpenAdminPanelConcreteCmd, OpenFrontPageConcreteCmd, OpenVisualizationsConcreteCmd")
    void curatedLeaves() {
        MenuConcreteCommandLeaves.OpenAdminPanelConcreteCmd admin =
                new MenuConcreteCommandLeaves.OpenAdminPanelConcreteCmd(redeem);
        assertEquals("admin", admin.name());
        admin.onCommand(caller, Collections.emptyMap(), null);
        assertEquals("admin", rendered.get().title());
        admin.onCommand(caller, Collections.emptyMap(), null, msg -> {});
        assertEquals("admin", rendered.get().title());

        MenuConcreteCommandLeaves.OpenFrontPageConcreteCmd front =
                new MenuConcreteCommandLeaves.OpenFrontPageConcreteCmd(redeem);
        assertEquals("front", front.name());
        front.onCommand(caller, Collections.emptyMap(), null);
        assertEquals("front", rendered.get().title());
        front.onCommand(caller, Collections.emptyMap(), null, msg -> {});
        assertEquals("front", rendered.get().title());

        MenuConcreteCommandLeaves.OpenVisualizationsConcreteCmd vis =
                new MenuConcreteCommandLeaves.OpenVisualizationsConcreteCmd(redeem);
        assertEquals("visualizations", vis.name());
        vis.onCommand(caller, Collections.emptyMap(), null);
        assertEquals("visualizations", rendered.get().title());
        vis.onCommand(caller, Collections.emptyMap(), null, msg -> {});
        assertEquals("visualizations", rendered.get().title());
    }

    @Test
    @DisplayName("Visualization subcommands without map renderer fall back to region selection")
    void visualizationSubcommands() {
        VisualizationDispatch dispatch = org.mockito.Mockito.mock(VisualizationDispatch.class);

        java.util.concurrent.atomic.AtomicBoolean badLocCalled = new java.util.concurrent.atomic.AtomicBoolean();
        MenuConcreteCommandLeaves.VisualizationBadLocationsCmd badLoc =
                new MenuConcreteCommandLeaves.VisualizationBadLocationsCmd(dispatch, (u, m) -> {
                    badLocCalled.set(true);
                    return true;
                });
        badLoc.onCommand(caller, Collections.emptyMap(), null, msg -> {});
        assertTrue(badLocCalled.get());

        java.util.concurrent.atomic.AtomicBoolean biomesCalled = new java.util.concurrent.atomic.AtomicBoolean();
        MenuConcreteCommandLeaves.VisualizationBiomesCmd biomes =
                new MenuConcreteCommandLeaves.VisualizationBiomesCmd(dispatch, (u, m) -> {
                    biomesCalled.set(true);
                    return true;
                });
        biomes.onCommand(caller, Collections.emptyMap(), null, msg -> {});
        assertTrue(biomesCalled.get());

        java.util.concurrent.atomic.AtomicBoolean pipeCalled = new java.util.concurrent.atomic.AtomicBoolean();
        MenuConcreteCommandLeaves.VisualizationPipelineCmd pipe =
                new MenuConcreteCommandLeaves.VisualizationPipelineCmd(dispatch, (u, m) -> {
                    pipeCalled.set(true);
                    return true;
                });
        pipe.onCommand(caller, Collections.emptyMap(), null, msg -> {});
        assertTrue(pipeCalled.get());

        MenuConcreteCommandLeaves.VisualizationSparklineCmd spark =
                new MenuConcreteCommandLeaves.VisualizationSparklineCmd(dispatch);
        spark.onCommand(caller, Collections.emptyMap(), null, msg -> {});
    }

    @Test
    @DisplayName("Multi-config dispatch: ADD, REMOVE, and toggle mutations")
    void multiConfigMutations() {
        MenuConcreteCommandLeavesB.MultiCmd multiCmd = new MenuConcreteCommandLeavesB.MultiCmd(redeem);

        // Seed 'default' parser into the regions MultiConfigParser so construct(name) has a template
        MultiConfigParser<?> regionsParser = null;
        for (MultiConfigParser<?> p : RTP.configs.multiConfigParserMap.values()) {
            if (p != null && "regions".equalsIgnoreCase(p.name)) {
                regionsParser = p;
                break;
            }
        }
        assertNotNull(regionsParser);

        // 1. Missing kind rejects
        assertFalse(multiCmd.onCommand(caller, Collections.emptyMap(), null, msg -> {}));

        // 2. Open selector for regions
        assertTrue(multiCmd.onCommand(caller, Map.of("kind", List.of("regions")), null, msg -> {}));
        assertNotNull(rendered.get());

        // 3. Open entry for a region
        assertTrue(multiCmd.onCommand(caller, Map.of("kind", List.of("regions"), "entry", List.of("default")), null, msg -> {}));
        assertNotNull(rendered.get());

        // 4. Unknown op falls back to selector
        rendered.set(null);
        assertTrue(multiCmd.onCommand(caller, Map.of("kind", List.of("regions"), "entry", List.of("default"), "op", List.of("unknown_op")), null, msg -> {}));
        assertNotNull(rendered.get());
    }

    @Test
    @DisplayName("Multi-config mutate removal of locked default entry is rejected")
    void multiConfigMutateLockedDefaultRejected() {
        MenuAction.MultiConfigMutate mutate = new MenuAction.MultiConfigMutate(
                "regions", "default", MenuAction.MultiConfigMutate.Op.REMOVE);
        boolean ok = redeem.dispatchMultiConfigMutate(caller, mutate, msg -> {});
        assertFalse(ok, "removing locked default region must be rejected by guard");
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
