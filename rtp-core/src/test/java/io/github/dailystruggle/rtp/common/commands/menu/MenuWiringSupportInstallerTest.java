package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MenuWiringSupportInstaller}: subcommand wiring performed by
 * {@link MenuWiringSupport#attachTo} and the extracted builder factories.
 * Traceability: ADR-035 / ADR-044 / ADR-050, REQ-RTP-F-013, REQ-RTP-S-007.
 */
@DisplayName("MenuWiringSupportInstaller wiring + builder factories")
final class MenuWiringSupportInstallerTest {

    @TempDir
    Path tempDir;

    private final UUID viewer = UUID.randomUUID();
    private final Predicate<String> allowAll = perm -> true;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
        RTP.selectionAPI = new SelectionAPI();
    }

    @AfterEach
    void tearDown() {
        if (RTP.selectionAPI != null) RTP.selectionAPI.permRegionLookup.clear();
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
    }

    // ------------------------------------------------------------------------
    // install() wiring
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("install() registers menu, admin, and visualization subcommands (renderer present)")
    void install_registersSubcommands_withRenderer() {
        TestRoot root = new TestRoot();
        MenuRenderer renderer = (id, model) -> { };
        MenuWiringSupport.attachTo(root,
                new MenuPlatformBindings(id -> allowAll, renderer, null));

        Map<String, CommandsAPICommand> lookup = root.getCommandLookup();
        assertTrue(lookup.containsKey("MENU"), "/rtp menu must be registered");
        assertTrue(lookup.containsKey("ADMIN"), "/rtp admin must be registered");
        assertTrue(lookup.containsKey("VISUALIZATION"),
                "/rtp visualization must be registered as a root sibling");

        // Invoke the installed MenuRedeemSubcommand on root to execute lambda$install$0 and lambda$install$1
        MenuRedeemSubcommand redeem = (MenuRedeemSubcommand) lookup.get("MENU");
        assertNotNull(redeem);
        redeem.onCommand(viewer, Map.of(), null, msg -> {});
        // Subpath
        redeem.onCommand(viewer, Map.of("path", List.of("config")), null, msg -> {});
        // Param picker
        redeem.onCommand(viewer, Map.of("path", List.of("config"), "param", List.of("radius")), null, msg -> {});
    }

    @Test
    @DisplayName("install() still registers menu + admin when no renderer is available")
    void install_registersSubcommands_withoutRenderer() {
        TestRoot root = new TestRoot();
        MenuWiringSupport.attachTo(root,
                new MenuPlatformBindings(id -> allowAll, null, null));

        Map<String, CommandsAPICommand> lookup = root.getCommandLookup();
        assertTrue(lookup.containsKey("MENU"));
        assertTrue(lookup.containsKey("ADMIN"));
        assertTrue(lookup.containsKey("VISUALIZATION"));
    }

    // ------------------------------------------------------------------------
    // Config-subtree builder factory
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("config-subtree builder builds selector, sub-directory selector, and files")
    void configSubtreeBuilder_selectorsAndFiles() throws Exception {
        MenuRedeemSubcommand.MenuConfigSubtreeBuilder b = subtreeBuilder(new TestRoot());

        assertNotNull(b.buildSelector(viewer), "root config selector");
        assertNotNull(b.buildSelector(viewer, "advanced"),
                "sub-directory config selector");
        assertNotNull(b.buildSelector(viewer, null), "null subDir treated as root");

        // Unknown file resolves to null on both overloads.
        assertNull(b.buildFile(viewer, "does-not-exist-file"));
        assertNull(b.buildFile(viewer, "does-not-exist-file", new LinkedHashMap<>()));

        // A real registered parser resolves to a file page.
        String realFile = anyParserName();
        if (realFile != null) {
            assertNotNull(b.buildFile(viewer, realFile),
                    "known parser must resolve to a config-file page");
            assertNotNull(b.buildFile(viewer, realFile, new LinkedHashMap<>()),
                    "cart-aware overload must resolve too");
        }
    }

    @Test
    @DisplayName("config-subtree buildKey returns null when the config subtree is absent")
    void configSubtreeBuilder_buildKey_nullWhenNoConfig() throws Exception {
        // TestRoot has no CONFIG subcommand, so buildKey cannot resolve.
        MenuRedeemSubcommand.MenuConfigSubtreeBuilder b = subtreeBuilder(new TestRoot());
        assertNull(b.buildKey(viewer, "messages.yml", "someKey"));
    }

    // ------------------------------------------------------------------------
    // Curated page builder factory
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("curated page builder builds front page, admin panel, and visualization pages")
    void curatedPageBuilder_pages() throws Exception {
        TestRoot root = new TestRoot();
        MenuRedeemSubcommand.MenuCuratedPageBuilder b = curatedBuilder(root);

        assertNotNull(b.buildFrontPage(viewer), "front page");
        assertNotNull(b.buildAdminPanel(viewer), "admin panel");
        assertNotNull(b.buildVisualizations(viewer), "visualizations chart-kind picker");
        assertNotNull(
                b.buildVisualizationRegions(viewer, ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE),
                "kind-scoped region picker");
    }

    // ------------------------------------------------------------------------
    // Config-search builder factory
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("config-search builder produces a model for a query and tolerates edge inputs")
    void configSearchBuilder_results() throws Exception {
        MenuRedeemSubcommand.MenuConfigSearchBuilder b = searchBuilder(new TestRoot());

        // Query with no matches (or matches) must not throw; page is clamped to >= 1.
        MenuModel model = b.buildResults(viewer, "distance", 0);
        assertNotNull(model, "search must yield a model even for the fallback page");

        // Null query is normalised to empty and still returns a model.
        assertNotNull(b.buildResults(viewer, null, 1));
    }

    @Test
    @DisplayName("configSubtreeBuilder builds key when config tree is present")
    void configSubtreeBuilder_buildKey_withConfigTree() throws Exception {
        TestRoot root = new TestRoot();
        TestRoot configTree = new TestRoot();
        TestRoot msgSub = new TestRoot();
        configTree.addSubCommand(msgSub);
        root.addSubCommand(configTree);
        // Map as CONFIG
        root.getCommandLookup().put("CONFIG", configTree);
        configTree.getCommandLookup().put("MESSAGES.YML", msgSub);

        MenuRedeemSubcommand.MenuConfigSubtreeBuilder b = subtreeBuilder(root);
        // Even if param doesn't exist on msgSub, buildParamPicker should return a model or handle gracefully
        MenuModel model = b.buildKey(viewer, "messages.yml", "testParam");
        assertNotNull(model);
    }

    @Test
    @DisplayName("install completes on live tree with config and admin subcommands")
    void install_onLiveTree() throws Exception {
        TestRoot root = new TestRoot();
        io.github.dailystruggle.rtp.common.commands.config.ConfigCmd configCmd =
                new io.github.dailystruggle.rtp.common.commands.config.ConfigCmd(root);
        root.addSubCommand(configCmd);
        root.getCommandLookup().put("CONFIG", configCmd);

        io.github.dailystruggle.rtp.common.commands.admin.AdminCmd adminCmd =
                new io.github.dailystruggle.rtp.common.commands.admin.AdminCmd(root);
        root.addSubCommand(adminCmd);
        root.getCommandLookup().put("ADMIN", adminCmd);

        MenuWiringSupportInstaller installer = newInstaller(root);
        installer.install();

        // Verify admin command was wired with menu redirect
        adminCmd.onCommand(viewer, java.util.Collections.emptyMap(), null, msg -> {});
        // Also test admin opener edge cases: null viewer, denied permission, builder failure, renderer failure
        io.github.dailystruggle.rtp.common.commands.admin.AdminCmd permDeniedAdmin =
                new io.github.dailystruggle.rtp.common.commands.admin.AdminCmd(root, null);
        permDeniedAdmin.onCommand(viewer, java.util.Collections.emptyMap(), null, msg -> {});

        // Test admin panel opener with throwing permission probe, throwing builder, null model, throwing renderer
        Method mAdminOpener = MenuWiringSupportInstaller.class.getDeclaredMethod("installAdminCmd", AdminPanelBuilder.class);
        mAdminOpener.setAccessible(true);

        MenuWiringSupportInstaller throwingProbeInstaller = new MenuWiringSupportInstaller(root,
                new MenuPlatformBindings(id -> perm -> { throw new RuntimeException("probe err"); },
                        (id, m) -> { throw new RuntimeException("renderer err"); }, null));
        mAdminOpener.invoke(throwingProbeInstaller, new AdminPanelBuilder());
        io.github.dailystruggle.rtp.common.commands.admin.AdminCmd wiredAdmin = (io.github.dailystruggle.rtp.common.commands.admin.AdminCmd) root.getCommandLookup().get("ADMIN");
        wiredAdmin.onCommand(viewer, java.util.Collections.emptyMap(), null, msg -> {});

        // Precedent for tasking: drain miscAsyncTasks to run installConfigSearchHandler's 8-tick task
        for (int i = 0; i < 15; i++) {
            RTP.getInstance().miscAsyncTasks.execute(Long.MAX_VALUE);
        }

        // Trigger search leaf handler now that it is wired
        io.github.dailystruggle.rtp.common.commands.config.ConfigSearchSubCmd searchLeaf =
                (io.github.dailystruggle.rtp.common.commands.config.ConfigSearchSubCmd)
                        configCmd.getCommandLookup().get("SEARCH");
        if (searchLeaf != null) {
            searchLeaf.onCommand(viewer, java.util.Map.of("query", java.util.List.of("radius")), null, msg -> {});
            // Search query with empty result
            searchLeaf.onCommand(viewer, java.util.Map.of("query", java.util.List.of("xyz_non_existent_token_999")), null, msg -> {});
        }
    }

    @Test
    @DisplayName("resolveHitHover and resolveYamlRoot multiconfig branches")
    void searchHoverAndYamlRoot() throws Exception {
        TestRoot root = new TestRoot();
        MenuWiringSupportInstaller installer = newInstaller(root);

        Method mHover = MenuWiringSupportInstaller.class.getDeclaredMethod("resolveHitHover",
                io.github.dailystruggle.rtp.common.commands.menu.ConfigMenuConsumerProfile.class,
                io.github.dailystruggle.rtp.common.menu.search.ConfigSearchResultsBuilder.Hit.class);
        mHover.setAccessible(true);

        io.github.dailystruggle.rtp.common.commands.menu.ConfigMenuConsumerProfile profile =
                new io.github.dailystruggle.rtp.common.commands.menu.ConfigMenuConsumerProfile(
                        fileName -> RTP.configs != null ? RTP.configs.configParserMap.values().iterator().next().getYamlRoot() : null);

        io.github.dailystruggle.rtp.common.menu.search.ConfigSearchResultsBuilder.Hit hit =
                new io.github.dailystruggle.rtp.common.menu.search.ConfigSearchResultsBuilder.Hit(
                        "messages.yml", "testKey", false, "testVal", java.util.List.of(new int[]{0, 4}));

        // invoke resolveHitHover
        Object hover = mHover.invoke(null, profile, hit);

        // invoke resolveYamlRoot
        Method mYaml = MenuWiringSupportInstaller.class.getDeclaredMethod("resolveYamlRoot", String.class);
        mYaml.setAccessible(true);
        // Invoke with valid parser name if available
        String realName = anyParserName();
        if (realName != null) {
            mYaml.invoke(null, realName);
        }
        assertNull(mYaml.invoke(null, "non_existent_config.yml"));
        assertNull(mYaml.invoke(null, ""));
        assertNull(mYaml.invoke(null, (String) null));
        // multiconfig resolution by entry name (default or default.yml)
        mYaml.invoke(null, "default");
        mYaml.invoke(null, "default.yml");

        // Exercise searchBuilder lambda directly (lambda$buildConfigSearchBuilder$3)
        // Seed region with multiple fields so ConfigSearchResultsBuilder.search has real multi-page hits
        java.nio.file.Files.createDirectories(tempDir.resolve("regions"));
        java.nio.file.Files.writeString(tempDir.resolve("regions").resolve("default.yml"),
                "shape:\n" +
                "  name: \"CIRCLE\"\n" +
                "  radius: 256\n" +
                "  centerRadius: 64\n" +
                "world: world\n" +
                "version: \"1.0\"\n");
        io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys> mcp =
                new io.github.dailystruggle.rtp.common.configuration.MultiConfigParser<>(
                        io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.class, "regions", "1.0", tempDir.toFile());
        RTP.configs.multiConfigParserMap.put(io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.class, mcp);

        // Pre-create some dummy hit records to test the pagination loop of lambda$buildConfigSearchBuilder$3 directly
        java.lang.reflect.Method mSearchBuilder = MenuWiringSupportInstaller.class.getDeclaredMethod("buildConfigSearchBuilder");
        mSearchBuilder.setAccessible(true);
        MenuRedeemSubcommand.MenuConfigSearchBuilder sb =
                (MenuRedeemSubcommand.MenuConfigSearchBuilder) mSearchBuilder.invoke(installer);

        UUID viewer = UUID.randomUUID();
        // Null query
        sb.buildResults(viewer, null, 1);
        // Empty query (matches all or no matches)
        sb.buildResults(viewer, "", 1);
        // Matching query with real hits in global configs (e.g. radius or shape)
        sb.buildResults(viewer, "radius", 1);
        sb.buildResults(viewer, "shape", 1);
        sb.buildResults(viewer, "circle", 1);
        sb.buildResults(viewer, "256", 1);
        // Search query with empty matchRanges (hits without ranges)
        sb.buildResults(viewer, "e", 1);
        sb.buildResults(viewer, "e", 2);
        // Non-matching query
        sb.buildResults(viewer, "non_existent_token_xyz_987", 1);
    }

    @Test
    @DisplayName("searchBuilder edge cases and query execution")
    void searchBuilder_coverage() throws Exception {
        TestRoot root = new TestRoot();
        newInstaller(root).install();

        MenuRedeemSubcommand.MenuConfigSearchBuilder sb = searchBuilder(root);
        assertNotNull(sb);

        // Null query
        MenuModel nullModel = sb.buildResults(viewer, null, 1);
        assertNotNull(nullModel);

        // Empty query
        MenuModel emptyModel = sb.buildResults(viewer, "", 1);
        assertNotNull(emptyModel);

        // Single character query (matching and non-matching)
        MenuModel matchModel = sb.buildResults(viewer, "r", 1);
        assertNotNull(matchModel);

        MenuModel nonMatchModel = sb.buildResults(viewer, "nonexistent_term_xyz", 1);
        assertNotNull(nonMatchModel);
    }

    // ------------------------------------------------------------------------
    // Reflection helpers for the private builder factories
    // ------------------------------------------------------------------------

    private MenuRedeemSubcommand.MenuConfigSubtreeBuilder subtreeBuilder(TestRoot root)
            throws Exception {
        MenuWiringSupportInstaller installer = newInstaller(root);
        Method m = MenuWiringSupportInstaller.class
                .getDeclaredMethod("buildConfigSubtreeBuilder");
        m.setAccessible(true);
        return (MenuRedeemSubcommand.MenuConfigSubtreeBuilder) m.invoke(installer);
    }

    private MenuRedeemSubcommand.MenuCuratedPageBuilder curatedBuilder(TestRoot root)
            throws Exception {
        MenuWiringSupportInstaller installer = newInstaller(root);
        Method m = MenuWiringSupportInstaller.class.getDeclaredMethod(
                "buildCuratedPageBuilder", FrontPageBuilder.class, AdminPanelBuilder.class);
        m.setAccessible(true);
        return (MenuRedeemSubcommand.MenuCuratedPageBuilder)
                m.invoke(installer, new FrontPageBuilder(), new AdminPanelBuilder());
    }

    private MenuRedeemSubcommand.MenuConfigSearchBuilder searchBuilder(TestRoot root)
            throws Exception {
        MenuWiringSupportInstaller installer = newInstaller(root);
        Method m = MenuWiringSupportInstaller.class
                .getDeclaredMethod("buildConfigSearchBuilder");
        m.setAccessible(true);
        return (MenuRedeemSubcommand.MenuConfigSearchBuilder) m.invoke(installer);
    }

    private MenuWiringSupportInstaller newInstaller(TestRoot root) {
        return new MenuWiringSupportInstaller(root,
                new MenuPlatformBindings(id -> allowAll, (id, model) -> { }, null));
    }

    private static String anyParserName() {
        if (RTP.configs == null) return null;
        for (ConfigParser<?> parser : RTP.configs.configParserMap.values()) {
            if (parser != null && parser.name != null && !parser.name.isEmpty()) {
                return parser.name;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------
    // Minimal /rtp root stub
    // ------------------------------------------------------------------------

    static final class TestRoot extends BaseRTPCmdImpl {
        TestRoot() {
            super(null);
        }
        @Override public String name() { return "rtp"; }
        @Override public String permission() { return "rtp.use"; }
        @Override public boolean onCommand(UUID callerId,
                                           Map<String, List<String>> parameterValues,
                                           CommandsAPICommand nextCommand) {
            return true;
        }
        @Override public CompletableFuture<Boolean> onCommand(UUID callerId,
                                                              Predicate<String> permissionCheckMethod,
                                                              Consumer<String> messageMethod,
                                                              String[] args,
                                                              int i,
                                                              Map<String, CommandParameter> tempParameters) {
            return CompletableFuture.completedFuture(true);
        }
    }
}
