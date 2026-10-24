package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.commands.menu.MenuConcreteCommandLeaves.MenuActionLeafCmd;
import io.github.dailystruggle.rtp.common.commands.menu.MenuConcreteCommandLeaves.VisualizationExportCmd;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
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

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MenuConcreteCommandLeaves export sizing, leaf dispatch and export command branches")
class MenuConcreteCommandLeavesCoverageTest {

    /** Region name unique to this class so exported chart files can be removed in teardown. */
    private static final String REGION = "mcclc_region";

    @TempDir
    File tempDir;

    private UUID caller;
    private SelectionAPI previousSelection;

    @BeforeEach
    void setUp() {
        previousSelection = ActiveSelection.get();
        RTPTestSetup.install(tempDir);
        caller = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        RTPTestSetup.cleanUp();
        ActiveSelection.set(previousSelection);
        File[] charts = new File("plugins/RTP/charts").listFiles((d, n) -> n.startsWith(REGION) || n.startsWith("mcclc_"));
        if (charts != null) {
            for (File f : charts) {
                f.delete();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Reflection helpers for private static parsers
    // ---------------------------------------------------------------------

    private static int parsePixel(String raw, int def) throws Exception {
        Method m = VisualizationExportCmd.class.getDeclaredMethod("parsePixelDimension", String.class, int.class);
        m.setAccessible(true);
        return (int) m.invoke(null, raw, def);
    }

    private static Integer[] parseDims(Map<String, List<String>> params) throws Exception {
        Method m = VisualizationExportCmd.class.getDeclaredMethod("parseDimensions", Map.class);
        m.setAccessible(true);
        return (Integer[]) m.invoke(null, params);
    }

    private static double parseZoom(Map<String, List<String>> params) throws Exception {
        Method m = VisualizationExportCmd.class.getDeclaredMethod("parseZoom", Map.class);
        m.setAccessible(true);
        return (double) m.invoke(null, params);
    }

    private static Region squareRegion(String name) {
        MockRTPWorld world = new MockRTPWorld("mcclc_world");
        RegionSettings settings = new RegionSettings(
                name, world, new Square(), new LinearAdjustor(new ArrayList<>()),
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        return new Region(name, settings);
    }

    // ---------------------------------------------------------------------
    // Pixel dimension parsing
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("parsePixelDimension falls back to default for null, blank and non-numeric input")
    void parsePixel_defaults() throws Exception {
        assertEquals(77, parsePixel(null, 77));
        assertEquals(77, parsePixel("   ", 77));
        assertEquals(77, parsePixel("abc", 77));
        assertEquals(77, parsePixel("1.2.3", 77));
        assertEquals(77, parsePixel("1..k", 77));
    }

    @Test
    @DisplayName("parsePixelDimension applies metric, binary and pixel suffixes with clamping")
    void parsePixel_suffixes() throws Exception {
        assertEquals(4000, parsePixel("4k", 1));
        assertEquals(1000, parsePixel("k", 1));
        assertEquals(2000, parsePixel("2kilo", 1));
        assertEquals(3000, parsePixel("3KPX", 1));
        assertEquals(8192, parsePixel("2m", 1));
        assertEquals(8192, parsePixel("mega", 1));
        assertEquals(8192, parsePixel("1g", 1));
        assertEquals(8192, parsePixel("5giga", 1));
        assertEquals(2048, parsePixel("2kib", 1));
        assertEquals(8192, parsePixel("1mib", 1));
        assertEquals(500, parsePixel("500px", 1));
        assertEquals(600, parsePixel("600pix", 1));
        assertEquals(16, parsePixel("px", 1));
        assertEquals(1234, parsePixel(" 1234 ", 1));
    }

    @Test
    @DisplayName("parsePixelDimension recognises every alternative unit spelling")
    void parsePixel_alternativeSpellings() throws Exception {
        for (String s : new String[]{"1gp", "1gpx", "1gpix", "1gpixels", "1mp", "1mpx", "1mpix", "1mpixels"}) {
            assertEquals(8192, parsePixel(s, 1), s);
        }
        for (String s : new String[]{"2kilos", "2kp", "2kpix", "2kpixels"}) {
            assertEquals(2000, parsePixel(s, 1), s);
        }
        assertEquals(700, parsePixel("700pixels", 1));
        assertEquals(77, parsePixel("1.2.3px", 77));
        assertEquals(77, parsePixel("1.2.3kib", 77));
        assertEquals(77, parsePixel("1.2.3mib", 77));
        assertEquals(77, parsePixel("1.2.3m", 77));
        assertEquals(77, parsePixel("1.2.3g", 77));
        assertEquals(1024, parsePixel("kib", 1));
        assertEquals(8192, parsePixel("mib", 1));
    }

    @Test
    @DisplayName("Export parent registers every chart sub-command")
    void exportParent_subcommands() {
        VisualizationExportCmd cmd = new VisualizationExportCmd();
        assertEquals("export", cmd.name());
        assertEquals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION, cmd.permission());
        assertTrue(cmd.onCommand(caller, Map.of(), null));
        for (String sub : new String[]{"all", "pipeline", "biomes", "bad-locations", "sparkline", "walk",
                "walk-path", "heatmap", "selection-heatmap", "comprehensive"}) {
            assertTrue(cmd.getCommandLookup().containsKey(sub)
                    || cmd.getCommandLookup().containsKey(sub.toUpperCase(java.util.Locale.ROOT)), sub);
        }
        assertEquals("all", new VisualizationExportCmd.ExportAllCmd().name());
        assertEquals("comprehensive", new VisualizationExportCmd.ExportComprehensiveCmd().name());
        assertEquals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION, new VisualizationExportCmd.ExportAllCmd().permission());
        assertEquals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION, new VisualizationExportCmd.ExportComprehensiveCmd().permission());
        assertEquals(MenuRedeemSubcommand.ADMIN_MENU_PERMISSION,
                new VisualizationExportCmd.ExportTypeCmd("walk", ChartSpec.Kind.REGION_WALK_PATH).permission());
    }

    @Test
    @DisplayName("parsePixelDimension clamps tiny, negative and degree-suffixed values to the minimum side")
    void parsePixel_minimum() throws Exception {
        assertEquals(16, parsePixel("3", 1));
        assertEquals(16, parsePixel("-50", 1));
        assertEquals(16, parsePixel("5deg", 1));
        assertEquals(8192, parsePixel("99999", 1));
    }

    @Test
    @DisplayName("parseDimensions handles size, WxH, width and height overrides")
    void parseDimensions_variants() throws Exception {
        assertArrayEquals(new Integer[]{null, null}, parseDims(null));
        assertArrayEquals(new Integer[]{null, null}, parseDims(Collections.emptyMap()));
        assertArrayEquals(new Integer[]{null, null}, parseDims(Map.of("size", List.of())));
        assertArrayEquals(new Integer[]{1920, 1080}, parseDims(Map.of("size", List.of("1920x1080"))));
        assertArrayEquals(new Integer[]{800, null}, parseDims(Map.of("size", List.of("800"))));
        assertArrayEquals(new Integer[]{512, 512}, parseDims(Map.of("size", List.of("x"))));

        Map<String, List<String>> p = new HashMap<>();
        p.put("width", List.of("300"));
        p.put("height", List.of("abc"));
        assertArrayEquals(new Integer[]{300, 512}, parseDims(p));

        p.put("size", List.of("100x200"));
        p.put("height", List.of("bad"));
        assertArrayEquals(new Integer[]{300, 200}, parseDims(p));

        assertArrayEquals(new Integer[]{null, null},
                parseDims(Map.of("width", List.of(), "height", List.of())));
    }

    @Test
    @DisplayName("parseZoom accepts positive factors and defaults otherwise")
    void parseZoom_variants() throws Exception {
        assertEquals(1.0, parseZoom(null));
        assertEquals(1.0, parseZoom(Collections.emptyMap()));
        assertEquals(1.0, parseZoom(Map.of("zoom", List.of())));
        assertEquals(2.5, parseZoom(Map.of("zoom", List.of(" 2.5 "))));
        assertEquals(1.0, parseZoom(Map.of("zoom", List.of("-1"))));
        assertEquals(1.0, parseZoom(Map.of("zoom", List.of("abc"))));
    }

    // ---------------------------------------------------------------------
    // MenuActionLeafCmd
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("MenuActionLeafCmd runs the plain action on both onCommand overloads")
    void leaf_plainActionBothOverloads() {
        List<Map<String, List<String>>> seen = new ArrayList<>();
        MenuActionLeafCmd leaf = MenuConcreteCommandLeaves.createLeaf(
                null, "leaf", "perm", (String) null,
                (java.util.function.BiConsumer<UUID, Map<String, List<String>>>) (u, p) -> seen.add(p));
        assertEquals("", leaf.description());
        Map<String, List<String>> params = Map.of("k", List.of("v"));
        assertTrue(leaf.onCommand(caller, params, null));
        assertTrue(leaf.onCommand(caller, params, null, msg -> { }));
        assertEquals(2, seen.size());
    }

    @Test
    @DisplayName("MenuActionLeafCmd feedback action result maps through Boolean.TRUE on both overloads")
    void leaf_feedbackActionResult() {
        MenuActionLeafCmd falseLeaf = MenuConcreteCommandLeaves.createLeaf(
                null, "f", "perm", "d",
                (java.util.function.BiFunction<UUID, java.util.function.Consumer<String>, Boolean>) (u, m) -> null);
        assertFalse(falseLeaf.onCommand(caller, Map.of(), null));
        assertFalse(falseLeaf.onCommand(caller, Map.of(), null, msg -> { }));

        List<Object> sinks = new ArrayList<>();
        MenuActionLeafCmd trueLeaf = MenuConcreteCommandLeaves.createLeaf(
                null, "t", "perm", "d",
                (java.util.function.BiFunction<UUID, java.util.function.Consumer<String>, Boolean>) (u, m) -> {
                    sinks.add(m == null ? "null" : "sink");
                    return Boolean.TRUE;
                });
        assertTrue(trueLeaf.onCommand(caller, Map.of(), null));
        assertTrue(trueLeaf.onCommand(caller, Map.of(), null, msg -> { }));
        assertEquals(List.of("null", "sink"), sinks);
    }

    // ---------------------------------------------------------------------
    // Export commands without a scheduler
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Export leaves return false when no scheduler is installed")
    void exportLeaves_noScheduler() {
        RTPRuntime.setScheduler(null);
        VisualizationExportCmd.ExportAllCmd all = new VisualizationExportCmd.ExportAllCmd();
        VisualizationExportCmd.ExportComprehensiveCmd comp = new VisualizationExportCmd.ExportComprehensiveCmd();
        VisualizationExportCmd.ExportTypeCmd type =
                new VisualizationExportCmd.ExportTypeCmd("biomes", ChartSpec.Kind.REGION_BIOMES);
        assertFalse(all.onCommand(caller, null, null));
        assertFalse(all.onCommand(caller, Map.of(), null, msg -> { }));
        assertFalse(comp.onCommand(caller, null, null));
        assertFalse(comp.onCommand(caller, Map.of(), null, msg -> { }));
        assertFalse(type.onCommand(caller, null, null));
        assertFalse(type.onCommand(caller, Map.of(), null, msg -> { }));
    }

    @Test
    @DisplayName("Export parent prints usage only as a true leaf with a message sink")
    void exportParent_usage() {
        VisualizationExportCmd cmd = new VisualizationExportCmd();
        List<String> out = new ArrayList<>();
        assertTrue(cmd.onCommand(caller, Map.of(), null, (java.util.function.Consumer<String>) null));
        assertTrue(cmd.onCommand(caller, Map.of(), cmd, out::add));
        assertTrue(out.isEmpty());
        assertTrue(cmd.onCommand(caller, Map.of(), null, out::add));
        assertEquals(1, out.size());
        assertTrue(out.get(0).startsWith("Usage: /rtp visualization export"));
    }

    // ---------------------------------------------------------------------
    // Canvas sizing against a memory-shaped region
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("resolveCanvasDimensions falls back to defaults for non-spatial charts")
    void canvas_nonSpatialDefaults() {
        Region region = squareRegion(REGION);
        assertArrayEquals(new int[]{512, 256},
                VisualizationExportCmd.resolveCanvasDimensions(region, null, null, false));
        assertArrayEquals(new int[]{300, 300},
                VisualizationExportCmd.resolveCanvasDimensions(region, 300, null, false));
        assertArrayEquals(new int[]{512, 200},
                VisualizationExportCmd.resolveCanvasDimensions(null, null, 200, false));
        assertArrayEquals(new int[]{512, 512},
                VisualizationExportCmd.resolveCanvasDimensions(null, null, null, true));
        assertArrayEquals(new int[]{640, 480},
                VisualizationExportCmd.resolveCanvasDimensions(region, 640, 480, true));
    }

    @Test
    @DisplayName("resolveCanvasDimensions follows a square region's aspect for auto and single-side sizes")
    void canvas_spatialSquareRegion() {
        Region region = squareRegion(REGION);
        int[] auto = VisualizationExportCmd.resolveCanvasDimensions(region, null, null, true);
        assertEquals(auto[0], auto[1]);
        assertTrue(auto[0] >= 512 && auto[0] <= 4096);

        int[] byWidth = VisualizationExportCmd.resolveCanvasDimensions(region, 400, null, true);
        assertEquals(400, byWidth[0]);
        assertTrue(Math.abs(byWidth[1] - 400) <= 2);

        int[] byHeight = VisualizationExportCmd.resolveCanvasDimensions(region, null, 300, true);
        assertEquals(300, byHeight[1]);
        assertTrue(Math.abs(byHeight[0] - 300) <= 2);

        int[] tinyWidth = VisualizationExportCmd.resolveCanvasDimensions(region, 2, null, true);
        assertEquals(16, tinyWidth[0]);
        int[] tinyHeight = VisualizationExportCmd.resolveCanvasDimensions(region, null, 2, true);
        assertEquals(16, tinyHeight[1]);
    }

    // ---------------------------------------------------------------------
    // Region parameter suggestions and sizing parameter values
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Region parameter suggestions track the selection API, including absent and throwing APIs")
    void regionParameterValues() {
        List<io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl> cmds = List.of(
                new VisualizationExportCmd.ExportAllCmd(),
                new VisualizationExportCmd.ExportComprehensiveCmd(),
                new VisualizationExportCmd.ExportTypeCmd("walk", ChartSpec.Kind.REGION_WALK_PATH));

        ActiveSelection.set(new SelectionAPI());
        ActiveSelection.get().permRegionLookup.put(REGION, squareRegion(REGION));
        for (var c : cmds) {
            assertTrue(c.getParameterLookup().get(MenuConcreteCommandLeaves.PARAM_REGION).values().contains(REGION));
        }

        ActiveSelection.set(null);
        for (var c : cmds) {
            assertTrue(c.getParameterLookup().get(MenuConcreteCommandLeaves.PARAM_REGION).values().isEmpty());
        }

        ActiveSelection.set(new SelectionAPI() {
            @Override
            public java.util.Set<String> regionNames() {
                throw new IllegalStateException("boom");
            }
        });
        for (var c : cmds) {
            assertTrue(c.getParameterLookup().get(MenuConcreteCommandLeaves.PARAM_REGION).values().isEmpty());
        }
    }

    @Test
    @DisplayName("Sizing parameters expose curated suggestion sets")
    void sizingParameterValues() {
        var lookup = new VisualizationExportCmd.ExportAllCmd().getParameterLookup();
        assertTrue(lookup.get("size").values().contains("4k"));
        assertTrue(lookup.get("width").values().contains("8192"));
        assertTrue(lookup.get("height").values().contains("4096"));
        assertTrue(lookup.get("zoom").values().contains("2.0"));
    }

    // ---------------------------------------------------------------------
    // Export failure messages
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Export all and comprehensive report an uninitialised selection API")
    void export_noSelectionApi() {
        ActiveSelection.set(null);
        List<String> out = new ArrayList<>();
        assertTrue(new VisualizationExportCmd.ExportAllCmd().onCommand(caller, Map.of(), null, out::add));
        assertTrue(new VisualizationExportCmd.ExportComprehensiveCmd().onCommand(caller, Map.of(), null, out::add));
        assertEquals(List.of("[RTP] Selection API not initialized", "[RTP] Selection API not initialized"), out);

        // Without a message sink the message is routed through the server accessor.
        assertTrue(new VisualizationExportCmd.ExportAllCmd().onCommand(caller, Map.of(), null));
        assertTrue(new VisualizationExportCmd.ExportComprehensiveCmd().onCommand(caller, Map.of(), null));
    }

    @Test
    @DisplayName("Export all and comprehensive report an unknown region when lookup throws")
    void export_regionLookupThrows() {
        ActiveSelection.set(new SelectionAPI());
        List<String> out = new ArrayList<>();
        Map<String, List<String>> params = Map.of("region", List.of("mcclc_missing"));
        assertTrue(new VisualizationExportCmd.ExportAllCmd().onCommand(caller, params, null, out::add));
        assertTrue(new VisualizationExportCmd.ExportComprehensiveCmd().onCommand(caller, params, null, out::add));
        assertEquals(List.of("[RTP] Region not found: mcclc_missing", "[RTP] Region not found: mcclc_missing"), out);
    }

    @Test
    @DisplayName("Export all and comprehensive report an unknown region when lookup returns null")
    void export_regionLookupNull() {
        ActiveSelection.set(new SelectionAPI() {
            @Override
            public Region getRegionOrDefault(String regionName) {
                return null;
            }
        });
        List<String> out = new ArrayList<>();
        Map<String, List<String>> params = Map.of("region", List.of());
        assertTrue(new VisualizationExportCmd.ExportAllCmd().onCommand(caller, params, null, out::add));
        assertTrue(new VisualizationExportCmd.ExportComprehensiveCmd().onCommand(caller, params, null, out::add));
        assertEquals(List.of("[RTP] Region not found: default", "[RTP] Region not found: default"), out);
        // Server accessor route for the null-region branch.
        assertTrue(new VisualizationExportCmd.ExportAllCmd().onCommand(caller, params, null));
        assertTrue(new VisualizationExportCmd.ExportComprehensiveCmd().onCommand(caller, params, null));
    }

    // ---------------------------------------------------------------------
    // Export success paths against a registered region
    // ---------------------------------------------------------------------

    private void registerRegion() {
        ActiveSelection.set(new SelectionAPI());
        ActiveSelection.get().permRegionLookup.put(REGION, squareRegion(REGION));
    }

    private static Map<String, List<String>> smallExport() {
        Map<String, List<String>> params = new HashMap<>();
        params.put("region", List.of(REGION));
        params.put("size", List.of("64"));
        params.put("zoom", List.of("1.5"));
        return params;
    }

    @Test
    @DisplayName("Export all renders each visualization for a registered region and reports a summary")
    void exportAll_success() {
        registerRegion();
        List<String> out = new ArrayList<>();
        assertTrue(new VisualizationExportCmd.ExportAllCmd().onCommand(caller, smallExport(), null, out::add));
        assertEquals(1, out.size());
        assertTrue(out.get(0).startsWith("[RTP] Exported "), out.get(0));
        assertTrue(out.get(0).contains(REGION), out.get(0));
    }

    @Test
    @DisplayName("Export comprehensive reports a single outcome message for a registered region")
    void exportComprehensive_outcome() {
        registerRegion();
        List<String> out = new ArrayList<>();
        assertTrue(new VisualizationExportCmd.ExportComprehensiveCmd().onCommand(caller, smallExport(), null, out::add));
        assertEquals(1, out.size());
        assertTrue(out.get(0).startsWith("[RTP] Export"), out.get(0));
    }

    @Test
    @DisplayName("Export type writes a framed chart for spatial and sparkline kinds")
    void exportType_outcomes() {
        registerRegion();
        String[][] types = {
                {"pipeline", "REGION_COMPOSITE"},
                {"bad-locations", "REGION_BAD_LOCATIONS_SHAPE"},
                {"sparkline", "METRIC_SPARKLINE"},
        };
        for (String[] t : types) {
            List<String> out = new ArrayList<>();
            VisualizationExportCmd.ExportTypeCmd cmd =
                    new VisualizationExportCmd.ExportTypeCmd(t[0], ChartSpec.Kind.valueOf(t[1]));
            assertEquals(t[0], cmd.name());
            assertTrue(cmd.onCommand(caller, smallExport(), null, out::add));
            assertEquals(1, out.size(), t[0]);
            assertTrue(out.get(0).startsWith("[RTP] Export"), out.get(0));
        }
        File pipeline = new File("plugins/RTP/charts", REGION + "_pipeline.png");
        assertTrue(pipeline.isFile());
    }

    @Test
    @DisplayName("Export type reports a failure when the region cannot be resolved")
    void exportType_unresolvable() {
        ActiveSelection.set(new SelectionAPI());
        List<String> out = new ArrayList<>();
        VisualizationExportCmd.ExportTypeCmd cmd =
                new VisualizationExportCmd.ExportTypeCmd("pipeline", ChartSpec.Kind.REGION_COMPOSITE);
        assertTrue(cmd.onCommand(caller, Map.of("region", List.of("mcclc_missing")), null, out::add));
        assertEquals(1, out.size());
        assertTrue(out.get(0).startsWith("[RTP] Export failed") || out.get(0).startsWith("Failed to resolve"), out.get(0));
        // Server accessor route.
        assertTrue(cmd.onCommand(caller, Map.of("region", List.of("mcclc_missing")), null));
    }
}
