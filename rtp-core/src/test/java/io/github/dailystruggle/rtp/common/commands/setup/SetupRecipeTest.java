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
}
