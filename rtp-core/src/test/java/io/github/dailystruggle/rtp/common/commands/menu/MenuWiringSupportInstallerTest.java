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
