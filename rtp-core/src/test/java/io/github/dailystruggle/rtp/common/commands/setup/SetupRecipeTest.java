package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SetupRecipeTest {

    @Test
    @DisplayName("compileRecipe produces ordered prefabs matching user choices")
    void testCompileRecipe() {
        SetupSession session = new SetupSession(UUID.randomUUID());
        session.setWorldChoice("multi");
        session.setGameplayChoice("arena");
        session.setPerformanceChoice("low");
        session.setToggle("claimIntegrations", true);

        List<Prefab> recipe = SetupRecipe.compileRecipe(session);
        assertNotNull(recipe);
        assertFalse(recipe.isEmpty());

        // First is MultiWorld
        assertEquals("multi-world", recipe.get(0).id());
        // Second is Arena
        assertEquals("setup-arena", recipe.get(1).id());
        // Third is LowPerformance
        assertEquals("low-performance", recipe.get(2).id());
        // Fourth is Addons
        assertEquals("setup-addons", recipe.get(3).id());
    }

    @Test
    @DisplayName("applyPipeline produces cumulative diff against baseline")
    void testApplyPipeline() {
        SetupSession session = new SetupSession(UUID.randomUUID());
        session.setWorldChoice("single");
        session.setGameplayChoice("survival");
        session.setPerformanceChoice("low");

        Map<String, Map<String, Object>> baseline = new LinkedHashMap<>();
        Map<String, Object> perf = new LinkedHashMap<>();
        perf.put("period", 20);
        perf.put("syncAllottedTime", 10);
        baseline.put("advanced/performance", perf);

        List<Prefab> recipe = SetupRecipe.compileRecipe(session);
        PrefabApplier.Result result = SetupRecipe.applyPipeline(baseline, recipe, List.of("world"));

        assertNotNull(result);
        assertNotNull(result.newTrees());
        Map<String, List<PrefabApplier.Change>> diff = result.perFileDiff();
        assertTrue(diff.containsKey("advanced/performance"));

        List<PrefabApplier.Change> changes = diff.get("advanced/performance");
        boolean periodChanged = changes.stream().anyMatch(c -> c.keyPath().equals("period") && ((Number) c.newValue()).intValue() == 60);
        assertTrue(periodChanged, "LowPerformance should set period=60");
    }

    @Test
    @DisplayName("multiWorld option fixes vert for non-overworld worlds in setup recipe pipeline")
    void testMultiWorldRepairsVertForNonOverworldWorlds() {
        SetupSession session = new SetupSession(UUID.randomUUID());
        session.setWorldChoice("multi");
        session.setGameplayChoice("survival");
        session.setPerformanceChoice("high");

        Map<String, Map<String, Object>> baseline = new LinkedHashMap<>();
        Map<String, Object> defRegion = new LinkedHashMap<>();
        defRegion.put("world", "world");
        defRegion.put("shape", Map.of("name", "CIRCLE", "radius", 1000));
        Map<String, Object> defaultVert = new LinkedHashMap<>();
        defaultVert.put("name", "LINEAR");
        defaultVert.put("minY", 32);
        defaultVert.put("maxY", 255);
        defaultVert.put("direction", 2);
        defaultVert.put("requireSkyLight", true);
        defRegion.put("vert", defaultVert);
        baseline.put("definitions/regions/default", defRegion);

        List<String> worlds = List.of("world", "world_nether", "world_the_end");
        List<Prefab> recipe = SetupRecipe.compileRecipe(session, worlds);

        // Verify MultiWorld prefab itself carries per-world regionOverlays for detected non-overworld worlds
        Prefab multiWorldPrefab = recipe.get(0);
        assertEquals("multi-world", multiWorldPrefab.id());
        assertTrue(multiWorldPrefab.regionOverlays().containsKey("world_nether"),
                "MultiWorld prefab must contain regionOverlay for detected nether world");
        assertTrue(multiWorldPrefab.regionOverlays().containsKey("world_the_end"),
                "MultiWorld prefab must contain regionOverlay for detected end world");

        Map<String, Object> netherOverlay = multiWorldPrefab.regionOverlays().get("world_nether");
        Map<?, ?> netherOverlayVert = (Map<?, ?>) netherOverlay.get("vert");
        assertNotNull(netherOverlayVert);
        assertEquals(false, netherOverlayVert.get("requireSkyLight"));
        assertEquals("LINEAR", netherOverlayVert.get("name"));
        assertTrue(((Number) netherOverlayVert.get("maxY")).intValue() <= 128);

        // Verify applyPipeline applies dimension vert fixes to synthesised newTrees and diff
        PrefabApplier.Result result = SetupRecipe.applyPipeline(baseline, recipe, worlds);
        assertNotNull(result);

        Map<String, Object> netherRegion = result.newTrees().get("definitions/regions/world_nether");
        assertNotNull(netherRegion, "synthesised nether region must exist in newTrees");
        assertEquals("world_nether", netherRegion.get("world"));
        Map<?, ?> netherVert = (Map<?, ?>) netherRegion.get("vert");
        assertNotNull(netherVert, "nether region must carry vert block");
        assertEquals(false, netherVert.get("requireSkyLight"), "nether requireSkyLight must be false");
        assertEquals("LINEAR", netherVert.get("name"), "nether vert name must be LINEAR");
        assertTrue(((Number) netherVert.get("maxY")).intValue() <= 128, "nether maxY must be <= 128");

        Map<String, Object> endRegion = result.newTrees().get("definitions/regions/world_the_end");
        assertNotNull(endRegion, "synthesised end region must exist in newTrees");
        assertEquals("world_the_end", endRegion.get("world"));
        assertNotNull(endRegion.get("shape"), "synthesised end region must carry shape block");
        Map<?, ?> endVert = (Map<?, ?>) endRegion.get("vert");
        assertNotNull(endVert, "end region must carry vert block");
        assertEquals(false, endVert.get("requireSkyLight"), "end requireSkyLight must be false");
        assertEquals("LINEAR", endVert.get("name"), "end vert name must be LINEAR");
    }

    @Test
    @DisplayName("multiWorld synthesises full regions even when baseline contains empty snapshot placeholders")
    void testMultiWorldSynthesisesFullRegionWithEmptyBaselinePlaceholders() {
        SetupSession session = new SetupSession(UUID.randomUUID());
        session.setWorldChoice("multi");
        session.setGameplayChoice("survival");
        session.setPerformanceChoice("high");

        Map<String, Map<String, Object>> baseline = new LinkedHashMap<>();
        Map<String, Object> defRegion = new LinkedHashMap<>();
        defRegion.put("world", "[0]");
        defRegion.put("shape", "@config");
        defRegion.put("vert", "@config");
        baseline.put("definitions/regions/default", defRegion);

        // Simulate snapshotLive putting empty maps for uncreated files in baseline
        baseline.put("definitions/regions/world_nether", new LinkedHashMap<>());
        baseline.put("definitions/regions/world_the_end", new LinkedHashMap<>());

        List<String> worlds = List.of("world", "world_nether", "world_the_end");
        List<Prefab> recipe = SetupRecipe.compileRecipe(session, worlds);

        PrefabApplier.Result result = SetupRecipe.applyPipeline(baseline, recipe, worlds);
        assertNotNull(result);

        Map<String, Object> endRegion = result.newTrees().get("definitions/regions/world_the_end");
        assertNotNull(endRegion, "end region must exist in newTrees");
        assertEquals("world_the_end", endRegion.get("world"), "world key must be set");
        assertEquals("@config", endRegion.get("shape"), "shape key must be cloned from default template");
        assertNotNull(endRegion.get("vert"), "vert block must be present");

        Map<String, Object> netherRegion = result.newTrees().get("definitions/regions/world_nether");
        assertNotNull(netherRegion, "nether region must exist in newTrees");
        assertEquals("world_nether", netherRegion.get("world"), "world key must be set");
        assertEquals("@config", netherRegion.get("shape"), "shape key must be cloned from default template");
        assertNotNull(netherRegion.get("vert"), "vert block must be present");
    }
}
