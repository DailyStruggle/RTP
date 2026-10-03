package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.menu.search.ConfigSearchResultsBuilder;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Comprehensive MenuModel, Page Builders, and Handlers Coverage")
public class ReqRtpMenuBuildersAndHandlersTest {

    private Path tempDir;
    private File pluginDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("rtp-menu-builders-cov-");
        pluginDir = tempDir.toFile();
        RTPTestSetup.install(pluginDir);
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();
    }

    @Test
    @DisplayName("ConfigSearchResultsBuilder search across configs and hit record structure")
    void testConfigSearchResultsBuilder() {
        // 1. Short or null queries return empty
        assertTrue(ConfigSearchResultsBuilder.search(null).isEmpty());
        assertTrue(ConfigSearchResultsBuilder.search("").isEmpty());
        assertTrue(ConfigSearchResultsBuilder.search("a").isEmpty());

        // 2. Query with matches across loaded configs
        List<ConfigSearchResultsBuilder.Hit> hits = ConfigSearchResultsBuilder.search("radius");
        assertNotNull(hits);

        // 3. Hit record testing
        ConfigSearchResultsBuilder.Hit hit = new ConfigSearchResultsBuilder.Hit(
                "performance.yml", "viewDistanceSelect", true, "5", List.of(new int[]{0, 1}));
        assertEquals("performance.yml", hit.fileName());
        assertEquals("viewDistanceSelect", hit.keyName());
        assertTrue(hit.keyMatched());
        assertEquals("5", hit.rawValue());
        assertEquals(1, hit.matchRanges().size());
        assertEquals(0, hit.matchRanges().get(0)[0]);
        assertEquals(1, hit.matchRanges().get(0)[1]);
    }

    @Test
    @DisplayName("SelectionMenuBuilder with custom backAction and without backAction")
    void testSelectionMenuBuilder() {
        SelectionMenuBuilder builder = new SelectionMenuBuilder();

        // 1. Without backAction
        MenuModel model1 = builder.build(
                List.of("config", "regions"),
                "shape",
                "Shape Selection",
                Set.of("CIRCLE", "SQUARE"),
                name -> "#00ffff",
                true,
                null
        );
        assertNotNull(model1);
        assertFalse(model1.pages().isEmpty());

        // 2. With custom backAction
        MenuAction customBack = new MenuAction.OpenMenu(new String[]{"admin"});
        MenuModel model2 = builder.build(
                List.of("admin", "prefab", "apply"),
                "prefab",
                "Apply Prefab",
                Set.of("SURVIVAL", "NETHER"),
                name -> "#ffaa00",
                false,
                customBack
        );
        assertNotNull(model2);
        assertFalse(model2.pages().isEmpty());

        // 3. Empty entries
        MenuModel modelEmpty = builder.build(
                List.of(),
                "empty",
                "Empty Param",
                Collections.emptySet(),
                name -> "#fff",
                false,
                null
        );
        assertNotNull(modelEmpty);
    }

    @Test
    @DisplayName("VisualizationsSubmenuBuilder build and buildRegionList for all ChartSpec kinds")
    void testVisualizationsSubmenuBuilder() {
        VisualizationsSubmenuBuilder builder = new VisualizationsSubmenuBuilder();

        // Top-level chart-kind picker
        MenuModel overview = builder.build(UUID.randomUUID());
        assertNotNull(overview);
        assertFalse(overview.pages().isEmpty());

        // Regions list for supported kinds
        MenuModel regModel1 = builder.buildRegionList(UUID.randomUUID(), ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE);
        assertNotNull(regModel1);
        assertFalse(regModel1.pages().isEmpty());

        MenuModel regModel2 = builder.buildRegionList(UUID.randomUUID(), ChartSpec.Kind.REGION_BIOMES);
        assertNotNull(regModel2);
        assertFalse(regModel2.pages().isEmpty());
    }

    @Test
    @DisplayName("MenuDrawer handles rendering with null consumer vs provided consumer")
    void testMenuDrawer() {
        MenuModel model = new MenuModel("Test Menu", List.of(new MenuPage(List.of(
                MenuLine.of(MenuFragment.plain("Line 1"))))));

        UUID viewer = UUID.randomUUID();
        List<String> messages = new ArrayList<>();

        // 1. Successful draw with null messageMethod
        MenuRenderer okRenderer = (u, m) -> {};
        boolean ok1 = MenuDrawer.draw(okRenderer, viewer, model, null,
                (u, k, log, c) -> {}, "context", "op");
        assertTrue(ok1);

        // 2. Successful draw with consumer
        boolean ok2 = MenuDrawer.draw(okRenderer, viewer, model, messages::add,
                (u, k, log, c) -> {}, "context", "op");
        assertTrue(ok2);

        // 3. Renderer throws exception -> MenuDrawer catches and delegates to rejector
        MenuRenderer failRenderer = (u, m) -> { throw new RuntimeException("render fail"); };
        List<String> rejections = new ArrayList<>();
        boolean failRes = MenuDrawer.draw(failRenderer, viewer, model, messages::add,
                (u, k, log, c) -> rejections.add(log), "test-context", "test-op");
        assertFalse(failRes);
        assertFalse(rejections.isEmpty());
    }
}
