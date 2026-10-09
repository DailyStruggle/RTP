package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.rtp.common.commands.prefab.MultiWorldExpander;
import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.FoliaTuned;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.HighPerformance;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.LowPerformance;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.MultiWorld;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.OneBlock;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.Skyblock;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.SurvivalDefault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiles {@link SetupSession} answers into an ordered pipeline of sparse {@link Prefab} overlays
 * (Contract 8: Recipe & Prefab Chaining).
 */
public final class SetupRecipe {

    private SetupRecipe() {
    }

    /**
     * Resolves the ordered chain of prefabs corresponding to the given setup session.
     * World names are inferred from the active runtime context if available.
     */
    public static List<Prefab> compileRecipe(SetupSession session) {
        return compileRecipe(session, SetupHandlerSupport.collectWorldNames());
    }

    /**
     * Resolves the ordered chain of prefabs corresponding to the given setup session
     * and list of active worlds.
     * Order of precedence:
     * 1. Topology (Single world default / MultiWorld expansion with dimension vert overrides)
     * 2. Gameplay Style (Survival, Skyblock, OneBlock, Arena)
     * 3. Performance Profile (Low, High, Folia)
     * 4. Addons & Effects overlay
     */
    public static List<Prefab> compileRecipe(SetupSession session, List<String> worldNames) {
        Objects.requireNonNull(session, "session");
        List<Prefab> recipe = new ArrayList<>();

        // Stage 1: World Topology
        if ("multi".equalsIgnoreCase(session.worldChoice())) {
            recipe.add(MultiWorld.createPrefab(worldNames));
        }

        // Stage 2: Gameplay Style
        switch (session.gameplayChoice().toLowerCase()) {
            case "skyblock" -> recipe.add(Skyblock.INSTANCE);
            case "oneblock" -> recipe.add(OneBlock.INSTANCE);
            case "arena" -> recipe.add(createArenaPrefab());
            case "survival" -> recipe.add(SurvivalDefault.INSTANCE);
            default -> recipe.add(SurvivalDefault.INSTANCE);
        }

        // Stage 3: Performance Profile
        switch (session.performanceChoice().toLowerCase()) {
            case "low" -> recipe.add(LowPerformance.INSTANCE);
            case "folia" -> recipe.add(FoliaTuned.INSTANCE);
            case "high" -> recipe.add(HighPerformance.INSTANCE);
            default -> recipe.add(HighPerformance.INSTANCE);
        }

        // Stage 4: Addons & Effects
        Prefab addonOverlay = createAddonsPrefab(session.addonToggles());
        if (hasAnyOverlay(addonOverlay)) {
            recipe.add(addonOverlay);
        }

        return recipe;
    }

    /**
     * Applies the entire recipe pipeline over baseline configuration trees.
     *
     * @param baselineTrees initial configuration trees keyed by file ID (e.g. "performance", "regions/default")
     * @param recipe        ordered list of prefabs to chain
     * @param worldNames    active world names (for multi-world expansion)
     * @return combined {@link PrefabApplier.Result} with final merged trees and cumulative per-file diff
     */
    public static PrefabApplier.Result applyPipeline(
            Map<String, Map<String, Object>> baselineTrees,
            List<Prefab> recipe,
            List<String> worldNames) {
        Objects.requireNonNull(baselineTrees, "baselineTrees");
        Objects.requireNonNull(recipe, "recipe");

        MultiWorldExpander.RegionOverlayAmender amender = MultiWorldExpander.defaultDimensionVertAmender();
        Map<String, Map<String, Object>> currentTrees = baselineTrees;
        for (Prefab p : recipe) {
            PrefabApplier.Result stepResult = PrefabApplier.apply(currentTrees, p, worldNames, amender);
            currentTrees = stepResult.newTrees();
        }

        // Compute cumulative diff against the original baseline
        return computeCumulativeDiff(baselineTrees, currentTrees);
    }

    private static PrefabApplier.Result computeCumulativeDiff(
            Map<String, Map<String, Object>> baseline,
            Map<String, Map<String, Object>> finalTrees) {
        Map<String, List<PrefabApplier.Change>> cumulativeDiff = new LinkedHashMap<>();

        for (Map.Entry<String, Map<String, Object>> entry : finalTrees.entrySet()) {
            String fileId = entry.getKey();
            Map<String, Object> baseMap = baseline.getOrDefault(fileId, Map.of());
            Map<String, Object> finalMap = entry.getValue();

            List<PrefabApplier.Change> changes = new ArrayList<>();
            diffMaps("", baseMap, finalMap, changes);
            if (!changes.isEmpty()) {
                cumulativeDiff.put(fileId, changes);
            }
        }

        return new PrefabApplier.Result(finalTrees, cumulativeDiff);
    }

    @SuppressWarnings("unchecked")
    private static void diffMaps(
            String prefix,
            Map<String, Object> oldMap,
            Map<String, Object> newMap,
            List<PrefabApplier.Change> changes) {
        for (Map.Entry<String, Object> e : newMap.entrySet()) {
            String key = e.getKey();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object newVal = e.getValue();
            Object oldVal = oldMap.get(key);

            if (newVal instanceof Map<?, ?> newSub) {
                Map<String, Object> oldSub = (oldVal instanceof Map<?, ?> o) ? (Map<String, Object>) o : Map.of();
                diffMaps(path, oldSub, (Map<String, Object>) newSub, changes);
            } else if (!Objects.equals(oldVal, newVal)) {
                changes.add(new PrefabApplier.Change(path, oldVal, newVal));
            }
        }
    }

    private static Prefab createArenaPrefab() {
        return new Prefab(
                "setup-arena",
                "setupArenaRow",
                "setupArenaHover",
                "Arena/Minigame configuration: smaller radius, fast respawn pacing, login cache enabled.",
                Map.of(
                        "period", 10,
                        "syncAllottedTime", 20,
                        "asyncAllottedTime", 50,
                        "loginCacheEnabled", true
                ),
                Map.of(),
                Map.of("default", Map.of(
                        "shape.radius", 500,
                        "cacheCap", 25
                )),
                false
        );
    }

    private static Prefab createAddonsPrefab(Map<String, Boolean> toggles) {
        Map<String, Object> perfOverlay = new LinkedHashMap<>();
        Map<String, Object> safetyOverlay = new LinkedHashMap<>();

        if (toggles.containsKey("claimIntegrations")) {
            boolean enableClaims = toggles.get("claimIntegrations");
            // Maps to safety / hooks toggle in config
            safetyOverlay.put("checkClaims", enableClaims);
        }

        return new Prefab(
                "setup-addons",
                "setupAddonsRow",
                "setupAddonsHover",
                "Addons & Effects overlay from setup wizard choices.",
                perfOverlay,
                safetyOverlay,
                Map.of(),
                false
        );
    }

    private static boolean hasAnyOverlay(Prefab p) {
        return !p.performanceOverlay().isEmpty()
                || !p.safetyOverlay().isEmpty()
                || !p.regionOverlays().isEmpty();
    }
}
