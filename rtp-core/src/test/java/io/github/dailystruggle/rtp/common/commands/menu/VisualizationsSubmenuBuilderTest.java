package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VisualizationsSubmenuBuilder} chart-kind picker and
 * kind-scoped region picker pages.
 * Traceability: REQ-RTP-F-013 (configurable labels), REQ-RTP-MAP-006 (ADR-047).
 */
@DisplayName("VisualizationsSubmenuBuilder chart-kind + region pickers")
final class VisualizationsSubmenuBuilderTest {

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

    // ------------------------------------------------------------------------
    // Chart-kind picker (build)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("build() emits one RunRtpCommand row per chart kind plus an OpenAdminPanel back row")
    void build_emitsKindRowsAndBack() {
        MenuModel model = new VisualizationsSubmenuBuilder().build(UUID.randomUUID());
        assertNotNull(model);
        assertFalse(model.pages().isEmpty(), "expected at least one page");

        // Each supported kind has a RunRtpCommand(["visualization", verb]) row.
        assertNotNull(findRunWithArgs(model, "visualization", "bad-locations"),
                "bad-locations row must run /rtp visualization bad-locations");
        assertNotNull(findRunWithArgs(model, "visualization", "biomes"),
                "biomes row must run /rtp visualization biomes");
        assertNotNull(findRunWithArgs(model, "visualization", "sparkline"),
                "sparkline row must run /rtp visualization sparkline");

        // Back row is the last clickable fragment and opens the admin panel.
        MenuAction last = lastClickable(model);
        assertInstanceOf(MenuAction.OpenAdminPanel.class, last,
                "back row must emit OpenAdminPanel");
    }

    @Test
    @DisplayName("build() rows use book-safe (non-yellow, non-white) colour codes")
    void build_rowsAvoidPaleColours() {
        MenuModel model = new VisualizationsSubmenuBuilder().build(UUID.randomUUID());
        for (MenuFragment frag : allFragments(model)) {
            String t = frag.text();
            assertFalse(t.startsWith("&e") || t.startsWith("&6") || t.startsWith("&f"),
                    "book row must avoid pale-on-parchment colours: " + t);
        }
    }

    @Test
    @DisplayName("build() rejects a null viewer")
    void build_nullViewerThrows() {
        assertThrows(NullPointerException.class,
                () -> new VisualizationsSubmenuBuilder().build(null));
    }

    // ------------------------------------------------------------------------
    // Region picker (buildRegionList)
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("buildRegionList() emits one OpenMap row per configured region, alphabetically")
    void buildRegionList_emitsRegionRows() {
        seedRegion("zeta");
        seedRegion("alpha");

        MenuModel model = new VisualizationsSubmenuBuilder()
                .buildRegionList(UUID.randomUUID(), ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE);

        List<MenuAction.OpenMap> maps = new ArrayList<>();
        for (MenuFragment frag : allFragments(model)) {
            if (frag.action() instanceof MenuAction.OpenMap om) maps.add(om);
        }
        assertEquals(2, maps.size(), "one OpenMap row per region");
        // TreeSet ordering -> alphabetical.
        assertEquals("alpha", maps.get(0).regionName());
        assertEquals("zeta", maps.get(1).regionName());
        assertEquals(ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE, maps.get(0).kind());

        // Back row goes one level up to the chart-kind picker.
        assertInstanceOf(MenuAction.OpenVisualizations.class, lastClickable(model),
                "region-list back row must emit OpenVisualizations");
    }

    @Test
    @DisplayName("buildRegionList() shows an empty-state row when no regions are configured")
    void buildRegionList_emptyState() {
        MenuModel model = new VisualizationsSubmenuBuilder()
                .buildRegionList(UUID.randomUUID(), ChartSpec.Kind.REGION_BIOMES);

        boolean anyOpenMap = allFragments(model).stream()
                .anyMatch(f -> f.action() instanceof MenuAction.OpenMap);
        assertFalse(anyOpenMap, "no OpenMap rows when there are no regions");
        assertTrue(hasFragmentContaining(model, "no regions"),
                "empty-state copy must be shown");
        assertInstanceOf(MenuAction.OpenVisualizations.class, lastClickable(model));
    }

    @Test
    @DisplayName("buildRegionList() rejects an unsupported chart kind")
    void buildRegionList_unsupportedKindThrows() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new VisualizationsSubmenuBuilder()
                        .buildRegionList(UUID.randomUUID(), ChartSpec.Kind.REGION_COVERAGE));
        assertTrue(ex.getMessage().contains("unsupported kind"));
    }

    @Test
    @DisplayName("buildRegionList() rejects null viewer or null kind")
    void buildRegionList_nullArgsThrow() {
        VisualizationsSubmenuBuilder b = new VisualizationsSubmenuBuilder();
        assertThrows(NullPointerException.class,
                () -> b.buildRegionList(null, ChartSpec.Kind.REGION_BIOMES));
        assertThrows(NullPointerException.class,
                () -> b.buildRegionList(UUID.randomUUID(), null));
    }

    @Test
    @DisplayName("buildRegionList() paginates large region sets and places the back row on the last page")
    void buildRegionList_paginates() {
        for (int i = 0; i < 40; i++) {
            seedRegion(String.format("region%02d", i));
        }
        MenuModel model = new VisualizationsSubmenuBuilder()
                .buildRegionList(UUID.randomUUID(), ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE);

        assertTrue(model.pages().size() > 1, "40 regions must span multiple pages");
        for (MenuPage page : model.pages()) {
            assertTrue(page.lines().size() <= VisualizationsSubmenuBuilder.LINES_PER_PAGE,
                    "no page may exceed LINES_PER_PAGE");
        }
        assertInstanceOf(MenuAction.OpenVisualizations.class, lastClickable(model),
                "back row lands on the last page");
    }

    @Test
    @DisplayName("collectRegionNames tolerates a null selectionAPI (empty region list)")
    void buildRegionList_nullSelectionApi() {
        RTP.selectionAPI = null;
        MenuModel model = new VisualizationsSubmenuBuilder()
                .buildRegionList(UUID.randomUUID(), ChartSpec.Kind.REGION_BIOMES);
        boolean anyOpenMap = allFragments(model).stream()
                .anyMatch(f -> f.action() instanceof MenuAction.OpenMap);
        assertFalse(anyOpenMap, "no regions when selectionAPI is null");
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private void seedRegion(String name) {
        MockRTPWorld world = new MockRTPWorld(name + "_world");
        Square shape = new Square();
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                name, world, shape, vert,
                false, false,
                10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        RTP.selectionAPI.permRegionLookup.put(name, new Region(name, settings));
    }

    private static List<MenuFragment> allFragments(MenuModel model) {
        List<MenuFragment> out = new ArrayList<>();
        for (MenuPage page : model.pages()) {
            for (MenuLine line : page.lines()) {
                out.addAll(line.fragments());
            }
        }
        return out;
    }

    private static MenuAction findRunWithArgs(MenuModel model, String... expected) {
        for (MenuFragment frag : allFragments(model)) {
            if (frag.action() instanceof MenuAction.RunRtpCommand run
                    && argsMatch(run.args(), expected)) {
                return run;
            }
        }
        return null;
    }

    private static boolean argsMatch(String[] actual, String[] expected) {
        if (actual == null || actual.length != expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (!expected[i].equals(actual[i])) return false;
        }
        return true;
    }

    private static MenuAction lastClickable(MenuModel model) {
        MenuAction last = null;
        for (MenuFragment frag : allFragments(model)) {
            if (frag.action() != null) last = frag.action();
        }
        return last;
    }

    private static boolean hasFragmentContaining(MenuModel model, String needle) {
        for (MenuFragment frag : allFragments(model)) {
            if (frag.text() != null && frag.text().contains(needle)) return true;
        }
        return false;
    }
}
