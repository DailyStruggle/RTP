package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("VisualizationsSubmenuBuilder navigation, pagination, slotting & error flows")
final class VisualizationsSubmenuBuilderExtendedTest {

    @TempDir
    Path tempDir;

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

    @Test
    @DisplayName("buildRegionList with empty regions shows empty message and back button")
    void buildRegionList_emptyRegions() {
        VisualizationsSubmenuBuilder builder = new VisualizationsSubmenuBuilder();
        UUID viewer = UUID.randomUUID();

        MenuModel model = builder.buildRegionList(viewer, ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE);
        assertNotNull(model);
        assertFalse(model.pages().isEmpty());

        MenuPage firstPage = model.pages().get(0);
        boolean hasEmptyMsg = false;
        boolean hasBack = false;

        for (MenuLine line : firstPage.lines()) {
            for (MenuFragment frag : line.fragments()) {
                if (frag.text().contains("no regions configured")) {
                    hasEmptyMsg = true;
                }
                if (frag.action() instanceof MenuAction.OpenVisualizations) {
                    hasBack = true;
                }
            }
        }
        assertTrue(hasEmptyMsg, "must contain empty region message");
        assertTrue(hasBack, "must contain back button to visualizations");
    }

    private void seedRegion(String name) {
        io.github.dailystruggle.rtp.common.mock.MockRTPWorld world =
                new io.github.dailystruggle.rtp.common.mock.MockRTPWorld(name + "_world");
        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square shape =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square();
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor vert =
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new ArrayList<>());
        io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings =
                new io.github.dailystruggle.rtp.common.selection.region.RegionSettings(
                        name, world, shape, vert,
                        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        RTP.selectionAPI.permRegionLookup.put(name,
                new io.github.dailystruggle.rtp.common.selection.region.Region(name, settings));
    }

    @Test
    @DisplayName("buildRegionList for biomes kind sets up OpenMap actions with BIOMES kind")
    void buildRegionList_biomesKind() {
        VisualizationsSubmenuBuilder builder = new VisualizationsSubmenuBuilder();
        UUID viewer = UUID.randomUUID();

        // Seed regions
        seedRegion("region_alpha");
        seedRegion("region_beta");

        MenuModel model = builder.buildRegionList(viewer, ChartSpec.Kind.REGION_BIOMES);
        assertNotNull(model);

        int countOpenMap = 0;
        for (MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                for (MenuFragment frag : line.fragments()) {
                    if (frag.action() instanceof MenuAction.OpenMap openMap) {
                        assertEquals(ChartSpec.Kind.REGION_BIOMES, openMap.kind());
                        assertTrue(openMap.regionName().startsWith("region_"));
                        countOpenMap++;
                    }
                }
            }
        }
        assertEquals(2, countOpenMap);
    }

    @Test
    @DisplayName("buildRegionList throws on unsupported or null kind")
    void buildRegionList_invalidKindThrows() {
        VisualizationsSubmenuBuilder builder = new VisualizationsSubmenuBuilder();
        UUID viewer = UUID.randomUUID();

        assertThrows(NullPointerException.class, () -> builder.buildRegionList(null, ChartSpec.Kind.REGION_BIOMES));
        assertThrows(NullPointerException.class, () -> builder.buildRegionList(viewer, null));
        // Kind not in KIND_ROWS throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> builder.buildRegionList(viewer, ChartSpec.Kind.REGION_COMPOSITE));
    }

    @Test
    @DisplayName("paginate allocates extra pages when page capacity is exceeded")
    void paginate_pageCapacityExceeded() {
        int cap = VisualizationsSubmenuBuilder.LINES_PER_PAGE;
        List<MenuLine> lines = new ArrayList<>();
        for (int i = 0; i < cap * 2 + 5; i++) {
            lines.add(MenuLine.of(MenuFragment.plain("line " + i)));
        }
        MenuLine backRow = MenuLine.of(new MenuFragment("back", null, new MenuAction.OpenVisualizations()));

        List<MenuPage> pages = VisualizationsSubmenuBuilder.paginate(lines, backRow);
        assertTrue(pages.size() >= 3, "expected at least 3 pages");

        // Verify back button is on the last page
        MenuPage lastPage = pages.get(pages.size() - 1);
        boolean lastPageHasBack = false;
        for (MenuLine l : lastPage.lines()) {
            for (MenuFragment f : l.fragments()) {
                if (f.action() instanceof MenuAction.OpenVisualizations) {
                    lastPageHasBack = true;
                }
            }
        }
        assertTrue(lastPageHasBack);
    }

    @Test
    @DisplayName("paginate with exact capacity appends backRow on a new page")
    void paginate_exactCapacity() {
        int cap = VisualizationsSubmenuBuilder.LINES_PER_PAGE;
        List<MenuLine> lines = new ArrayList<>();
        for (int i = 0; i < cap; i++) {
            lines.add(MenuLine.of(MenuFragment.plain("item " + i)));
        }
        MenuLine backRow = MenuLine.of(new MenuFragment("back", null, new MenuAction.OpenVisualizations()));

        List<MenuPage> pages = VisualizationsSubmenuBuilder.paginate(lines, backRow);
        assertEquals(2, pages.size(), "exact cap should spill back button to second page");
    }

    @Test
    @DisplayName("paginate with empty lines returns single page with backRow")
    void paginate_emptyLines() {
        MenuLine backRow = MenuLine.of(new MenuFragment("back", null, new MenuAction.OpenVisualizations()));
        List<MenuPage> pages = VisualizationsSubmenuBuilder.paginate(Collections.emptyList(), backRow);
        assertEquals(1, pages.size());
        assertEquals(1, pages.get(0).lines().size());
    }

    @Test
    @DisplayName("collectRegionNames handles null selectionAPI or runtime exception")
    void collectRegionNames_safeException() {
        RTP.selectionAPI = null;
        VisualizationsSubmenuBuilder builder = new VisualizationsSubmenuBuilder();
        MenuModel model = builder.buildRegionList(UUID.randomUUID(), ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE);
        assertNotNull(model);
    }
}
