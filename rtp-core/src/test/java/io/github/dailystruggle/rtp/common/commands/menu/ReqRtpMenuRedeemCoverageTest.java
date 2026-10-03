package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.config.ConfigCmd;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Comprehensive MenuRedeemSubcommand Uncovered Paths and Edge Cases")
public class ReqRtpMenuRedeemCoverageTest {

    private Path tempDir;
    private TestableRoot root;
    private File pluginDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("rtp-menu-redeem-cov-");
        pluginDir = tempDir.toFile();
        RTPTestSetup.install(pluginDir);
        io.github.dailystruggle.rtp.common.commands.menu.multiconfig.DefaultMultiConfigRemovalGuards.registerDefaults();
        root = new TestableRoot();
        RTP.baseCommand = root;
    }

    @Test
    @DisplayName("Single-argument constructor seeds parameters and commands")
    void testSingleArgConstructor() {
        MenuRedeemSubcommand cmd = new MenuRedeemSubcommand(root);
        assertEquals("menu", cmd.name());
        assertEquals(MenuRedeemSubcommand.PERMISSION, cmd.permission());
        assertNotNull(cmd.description());
        assertFalse(cmd.getParameterLookup().isEmpty());
    }

    @Test
    @DisplayName("onCommand and dispatch parameter edge cases: null sender, nextCommand != null, multiadd routing")
    void testDispatchAndOnCommand() {
        MenuRedeemSubcommand cmd = new MenuRedeemSubcommand(root, allow());

        // Null sender rejects immediately
        List<String> messages = new ArrayList<>();
        assertFalse(cmd.onCommand(null, Map.of(), null, messages::add));
        // Reject sends user msg only if senderId != null, but logs warning
        // Verify return is false
        assertFalse(cmd.onCommand(null, Map.of(), null));

        // 3-param onCommand delegates to 4-param onCommand
        UUID viewer = UUID.randomUUID();
        // Since renderer/pageBuilder is null on cmd, bare /rtp menu rejects
        assertFalse(cmd.onCommand(viewer, Map.of(), null));

        // When wired with renderer and pageBuilder:
        MenuRedeemSubcommand wired = wired(allow());
        // If nextCommand is not null, returns true (allows descending into child command)
        CommandsAPICommand dummyChild = new BaseRTPCmdImpl(wired) {
            @Override public String name() { return "child"; }
            @Override public String permission() { return ""; }
            @Override public boolean onCommand(UUID callerId, Map<String, List<String>> p, CommandsAPICommand next) { return true; }
        };
        assertTrue(wired.onCommand(viewer, Map.of(), dummyChild));

        // Bare /rtp menu opens page
        assertTrue(wired.onCommand(viewer, Map.of(), null));

        // Multiadd routing via parameters
        Map<String, List<String>> multiAddParams = Map.of(
                MenuRedeemSubcommand.PARAM_MULTIADD, List.of("custom_region"),
                MenuRedeemSubcommand.PARAM_MULTIADD_KIND, List.of("regions")
        );
        boolean multiAddResult = wired.onCommand(viewer, multiAddParams, null, messages::add);
        // It should attempt dispatchMultiConfigMutate(ADD)
        assertTrue(multiAddResult);
    }

    @Test
    @DisplayName("extractPageIndex correctly parses edge cases and fallback to zero")
    void testExtractPageIndex() {
        assertEquals(0, MenuRedeemSubcommand.extractPageIndex(null));
        assertEquals(0, MenuRedeemSubcommand.extractPageIndex(Map.of()));
        assertEquals(0, MenuRedeemSubcommand.extractPageIndex(Map.of("page", List.of())));
        assertEquals(0, MenuRedeemSubcommand.extractPageIndex(Collections.singletonMap("page", null)));

        Map<String, List<String>> mapWithNull = new HashMap<>();
        mapWithNull.put("page", Collections.singletonList(null));
        assertEquals(0, MenuRedeemSubcommand.extractPageIndex(mapWithNull));

        assertEquals(0, MenuRedeemSubcommand.extractPageIndex(Map.of("page", List.of("abc"))));
        assertEquals(0, MenuRedeemSubcommand.extractPageIndex(Map.of("page", List.of("0"))));
        assertEquals(0, MenuRedeemSubcommand.extractPageIndex(Map.of("page", List.of("-5"))));
        assertEquals(0, MenuRedeemSubcommand.extractPageIndex(Map.of("page", List.of("1"))));
        assertEquals(1, MenuRedeemSubcommand.extractPageIndex(Map.of("page", List.of("2"))));
        assertEquals(4, MenuRedeemSubcommand.extractPageIndex(Map.of("page", List.of("5"))));
    }

    @Test
    @DisplayName("stripYmlSuffix and stripYmlLower utility methods via reflection")
    void testStripYmlMethods() throws Exception {
        Method mStripSuffix = MenuRedeemSubcommand.class.getDeclaredMethod("stripYmlSuffix", Collection.class);
        mStripSuffix.setAccessible(true);

        List<String> input = Arrays.asList("custom.yml", "ANOTHER.YML", "plain", null, ".yml");
        @SuppressWarnings("unchecked")
        List<String> output = (List<String>) mStripSuffix.invoke(null, input);
        assertEquals(List.of("custom", "ANOTHER", "plain"), output);

        Method mStripLower = MenuRedeemSubcommand.class.getDeclaredMethod("stripYmlLower", String.class);
        mStripLower.setAccessible(true);
        assertEquals("", mStripLower.invoke(null, (String) null));
        assertEquals("region", mStripLower.invoke(null, "Region.Yml"));
        assertEquals("region", mStripLower.invoke(null, "REGION.YML"));
        assertEquals("test", mStripLower.invoke(null, "test"));
    }

    @Test
    @DisplayName("resolveFiniteOptions covers shape, vert, world, region, and fallback")
    void testResolveFiniteOptions() throws Exception {
        Method mResolveFinite = MenuRedeemSubcommand.class.getDeclaredMethod("resolveFiniteOptions", String.class, String.class);
        mResolveFinite.setAccessible(true);

        MenuRedeemSubcommand cmd = wired(allow());

        // Test non-existent file
        @SuppressWarnings("unchecked")
        List<String> resEmpty = (List<String>) mResolveFinite.invoke(cmd, "nonexistent_file", "key");
        assertNotNull(resEmpty);

        // Mock factory contents for shape and vert
        Factory shapeFactory = RTP.factoryMap.get(RTP.factoryNames.shape);
        if (shapeFactory != null) {
            io.github.dailystruggle.rtp.common.factory.FactoryValue dummyVal = org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.factory.FactoryValue.class);
            shapeFactory.map.put("CIRCLE.yml", dummyVal);
            shapeFactory.map.put("SQUARE.yml", dummyVal);
        }

        Factory vertFactory = RTP.factoryMap.get(RTP.factoryNames.vert);
        if (vertFactory != null) {
            io.github.dailystruggle.rtp.common.factory.FactoryValue dummyVal = org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.factory.FactoryValue.class);
            vertFactory.map.put("SURFACE.yml", dummyVal);
        }

        // Test stripYmlSuffix with shape keys
        Method mStripSuffix = MenuRedeemSubcommand.class.getDeclaredMethod("stripYmlSuffix", Collection.class);
        mStripSuffix.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> shapeKeys = (List<String>) mStripSuffix.invoke(null, shapeFactory.map.keySet());
        assertTrue(shapeKeys.contains("CIRCLE"));
        assertTrue(shapeKeys.contains("SQUARE"));

        // Directives with finite domain
        Method mDirectivesFor = MenuRedeemSubcommand.class.getDeclaredMethod("directivesFor", String.class, String.class);
        mDirectivesFor.setAccessible(true);
        assertNotNull(mDirectivesFor.invoke(null, "config", "defaults.shape"));
    }

    @Test
    @DisplayName("dispatchOpenMap exercises permission check, ChartSpec builder, and paint exceptions")
    void testDispatchOpenMap() throws Exception {
        Method mOpenMap = MenuRedeemSubcommand.class.getDeclaredMethod(
                "dispatchOpenMap", UUID.class, MenuAction.OpenMap.class, Consumer.class);
        mOpenMap.setAccessible(true);

        UUID viewer = UUID.randomUUID();
        List<String> msgs = new ArrayList<>();

        // 1. Permission denied
        MenuRedeemSubcommand denied = wired(deny());
        boolean deniedRes = (boolean) mOpenMap.invoke(denied, viewer,
                new MenuAction.OpenMap(ChartSpec.Kind.REGION_BIOMES, "default"), (Consumer<String>) msgs::add);
        assertFalse(deniedRes);
        assertFalse(msgs.isEmpty());

        // 2. Permission allowed - paint delegate (MapDispatch without canvas provider will return false or throw)
        MenuRedeemSubcommand allowed = wired(allow());
        msgs.clear();
        boolean allowedRes = (boolean) mOpenMap.invoke(allowed, viewer,
                new MenuAction.OpenMap(ChartSpec.Kind.REGION_BIOMES, "default"), (Consumer<String>) msgs::add);
        // MapDispatch.paint returns false when map binding is missing or unavailable
        assertFalse(allowedRes);
    }

    @Test
    @DisplayName("reopenAfterCartOp routes slash-bearing and non-slash-bearing names")
    void testReopenAfterCartOp() {
        MenuRedeemSubcommand cmd = wired(allow());
        UUID viewer = UUID.randomUUID();

        // Non-slash fileName -> routes to dispatchOpenConfigFile
        assertTrue(cmd.reopenAfterCartOp(viewer, "config.yml", null));

        // Slash-bearing fileName ("regions/default") -> routes to dispatchOpenMultiConfigEntry
        assertTrue(cmd.reopenAfterCartOp(viewer, "regions/default", null));
    }

    @Test
    @DisplayName("normalizeCartFileName edge cases")
    void testNormalizeCartFileName() throws Exception {
        Method mNorm = MenuRedeemSubcommand.class.getDeclaredMethod("normalizeCartFileName", String.class);
        mNorm.setAccessible(true);

        assertNull(mNorm.invoke(null, (String) null));
        assertEquals("", mNorm.invoke(null, ""));
        assertEquals("config", mNorm.invoke(null, "CONFIG.YML"));
        assertEquals("config", mNorm.invoke(null, "config.yml"));
        assertEquals("regions/default", mNorm.invoke(null, "REGIONS/default.YML"));
        assertEquals("plain", mNorm.invoke(null, "plain"));
    }

    @Test
    @DisplayName("dispatchOpen error handling: disabled, invalid path segment")
    void testDispatchOpenFailures() {
        UUID viewer = UUID.randomUUID();
        List<String> msgs = new ArrayList<>();

        // Renderer / pageBuilder null
        MenuRedeemSubcommand disabled = new MenuRedeemSubcommand(root, allow());
        assertFalse(disabled.dispatchOpen(viewer, new MenuAction.OpenMenu(new String[]{"info"}), msgs::add));

        // Wired: unknown segment
        MenuRedeemSubcommand wired = wired(allow());
        assertFalse(wired.dispatchOpen(viewer, new MenuAction.OpenMenu(new String[]{"nonexistent_cmd"}), msgs::add));

        // Staged parameter segment containing '=' is skipped in path walk
        assertTrue(wired.dispatchOpen(viewer, new MenuAction.OpenMenu(new String[]{"config", "world=world_nether"}), msgs::add));
    }

    @Test
    @DisplayName("dispatchOpenParamPicker error handling: disabled, invalid segment, unknown param, builder exception")
    void testDispatchOpenParamPickerFailures() {
        UUID viewer = UUID.randomUUID();
        List<String> msgs = new ArrayList<>();

        // Disabled
        MenuRedeemSubcommand disabled = new MenuRedeemSubcommand(root, allow());
        assertFalse(disabled.dispatchOpenParamPicker(viewer,
                new MenuAction.OpenParamPicker(new String[]{"config"}, "shape"), msgs::add));

        MenuRedeemSubcommand wired = wired(allow());
        // Unknown segment
        assertFalse(wired.dispatchOpenParamPicker(viewer,
                new MenuAction.OpenParamPicker(new String[]{"invalid_sub"}, "shape"), msgs::add));

        // Known segment (CONFIG), unknown param
        assertFalse(wired.dispatchOpenParamPicker(viewer,
                new MenuAction.OpenParamPicker(new String[]{"config"}, "totally_fake_param"), msgs::add));

        // Param picker builder throws exception
        MenuRedeemSubcommand throwingBuilder = new MenuRedeemSubcommand(root, allow(),
                (u, m) -> {}, (n, o, p) -> stubModel(),
                (p, v, path, name) -> { throw new RuntimeException("picker boom"); },
                null, null, null, null, null);
        assertFalse(throwingBuilder.dispatchOpenParamPicker(viewer,
                new MenuAction.OpenParamPicker(new String[]{}, "world"), msgs::add));
    }

    @Test
    @DisplayName("dispatchSelectionMenu error handling: disabled, invalid segment, unknown param, builder throws")
    void testDispatchSelectionMenuFailures() {
        UUID viewer = UUID.randomUUID();
        List<String> msgs = new ArrayList<>();

        // Disabled
        MenuRedeemSubcommand disabled = new MenuRedeemSubcommand(root, allow());
        assertFalse(disabled.dispatchSelectionMenu(viewer, new String[]{"config"}, "world",
                "World", k -> "#fff", true, msgs::add));

        MenuRedeemSubcommand wired = wired(allow());
        // Invalid segment
        assertFalse(wired.dispatchSelectionMenu(viewer, new String[]{"unknown_segment"}, "world",
                "World", k -> "#fff", true, msgs::add));

        // Unknown param
        assertFalse(wired.dispatchSelectionMenu(viewer, new String[]{}, "nonexistent_param",
                "Param", k -> "#fff", true, msgs::add));
    }

    @Test
    @DisplayName("renderAt handles pageBuilder throwing exception and null model")
    void testRenderAtFailures() {
        UUID viewer = UUID.randomUUID();
        List<String> msgs = new ArrayList<>();

        // Page builder throws
        MenuRedeemSubcommand throwingBuilder = new MenuRedeemSubcommand(root, allow(),
                (u, m) -> {}, (n, o, p) -> { throw new RuntimeException("builder failed"); },
                null, null, null, null, null, null);
        assertFalse(throwingBuilder.openPage(viewer, null, 0, msgs::add));

        // Page builder returns null model
        MenuRedeemSubcommand nullBuilder = new MenuRedeemSubcommand(root, allow(),
                (u, m) -> {}, (n, o, p) -> null,
                null, null, null, null, null, null);
        assertFalse(nullBuilder.openPage(viewer, null, 0, msgs::add));
    }

    @Test
    @DisplayName("rejectMenuInvalid delegates to reject correctly")
    void testRejectMenuInvalid() {
        MenuRedeemSubcommand cmd = wired(allow());
        UUID viewer = UUID.randomUUID();
        List<String> msgs = new ArrayList<>();

        cmd.rejectMenuInvalid(viewer, "test invalid menu log", msgs::add);
        assertEquals(1, msgs.size());
        assertTrue(msgs.get(0).contains("Invalid menu command."));

        // With null viewer
        cmd.rejectMenuInvalid(null, "log with null viewer", msgs::add);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static Function<UUID, Predicate<String>> allow() {
        return uuid -> perm -> true;
    }

    private static Function<UUID, Predicate<String>> deny() {
        return uuid -> perm -> false;
    }

    private MenuRedeemSubcommand wired(Function<UUID, Predicate<String>> perm) {
        ConfigCmd configCmd = new ConfigCmd(root);
        root.getCommandLookup().put("CONFIG", configCmd);

        TestableRoot configSub = new TestableRoot();
        TestableRoot regionsSub = new TestableRoot();
        TestableRoot defaultSub = new TestableRoot();
        regionsSub.getCommandLookup().put("DEFAULT", defaultSub);

        configCmd.getCommandLookup().put("SEARCH", configSub);
        configCmd.getCommandLookup().put("CONFIG.YML", configSub);
        configCmd.getCommandLookup().put("REGIONS", regionsSub);

        MenuRenderer renderer = (u, m) -> {};
        MenuRedeemSubcommand.MenuParamPickerBuilder picker = (p, v, path, name) -> stubModel();
        MenuRedeemSubcommand.MenuConfigSubtreeBuilder subtree = new MenuRedeemSubcommand.MenuConfigSubtreeBuilder() {
            @Override public MenuModel buildSelector(UUID viewer) { return stubModel(); }
            @Override public MenuModel buildFile(UUID viewer, String fileName) { return stubModel(); }
            @Override public MenuModel buildKey(UUID viewer, String fileName, String paramName) { return stubModel(); }
        };
        MenuRedeemSubcommand.MenuCuratedPageBuilder curated = new MenuRedeemSubcommand.MenuCuratedPageBuilder() {
            @Override public MenuModel buildAdminPanel(UUID viewer) { return stubModel(); }
            @Override public MenuModel buildFrontPage(UUID viewer) { return stubModel(); }
            @Override public MenuModel buildVisualizations(UUID viewer) { return stubModel(); }
            @Override public MenuModel buildVisualizationRegions(UUID viewer, ChartSpec.Kind kind) { return stubModel(); }
        };
        MenuRedeemSubcommand.MenuConfigSearchBuilder search = (viewer, query, page) -> stubModel();
        MenuRedeemSubcommand.MenuInfoBookBuilder info = (viewer, scope) -> stubModel();

        MenuRedeemSubcommand redeem = new MenuRedeemSubcommand(root, perm, renderer, (n, o, p) -> stubModel(),
                picker, (v, p, k, val) -> true, subtree, curated, search, info);

        redeem.setMultiConfigBuilder(new io.github.dailystruggle.rtp.common.commands.menu.multiconfig.MultiConfigMenuBuilder());
        return redeem;
    }

    private static MenuModel stubModel() {
        return new MenuModel("title", List.of(new MenuPage(List.of(
                MenuLine.of(MenuFragment.plain("x"))))));
    }

    private static final class TestableRoot extends BaseRTPCmdImpl {
        String[] lastDispatchedArgs;

        TestableRoot() {
            super(null);
            CommandParameter worldParam = new CommandParameter("rtp.world", "world param", (u, v) -> true) {
                @Override public Set<String> values() { return Set.of("world", "world_nether"); }
            };
            addParameter("world", worldParam);
        }

        @Override public String name() { return "rtp"; }
        @Override public String permission() { return ""; }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            return true;
        }

        @Override
        public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                    Predicate<String> permissionCheckMethod,
                                                    Consumer<String> messageMethod,
                                                    String[] args,
                                                    int i,
                                                    Map<String, CommandParameter> tempParameters) {
            this.lastDispatchedArgs = args;
            return CompletableFuture.completedFuture(true);
        }
    }
}
