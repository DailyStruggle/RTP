package io.github.dailystruggle.rtp.common.commands.setup;

import io.github.dailystruggle.rtp.common.commands.prefab.MultiWorldExpander;
import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;
import io.github.dailystruggle.rtp.common.commands.prefab.PrefabApplier;
import io.github.dailystruggle.rtp.common.commands.prefab.FoliaTuned;
import io.github.dailystruggle.rtp.common.commands.prefab.HighPerformance;
import io.github.dailystruggle.rtp.common.commands.prefab.LowPerformance;
import io.github.dailystruggle.rtp.common.commands.prefab.MultiWorld;
import io.github.dailystruggle.rtp.common.commands.prefab.OneBlock;
import io.github.dailystruggle.rtp.common.commands.prefab.Skyblock;
import io.github.dailystruggle.rtp.common.commands.prefab.SurvivalDefault;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiles {@link SetupSession} answers into an ordered pipeline of sparse {@link Prefab} overlays
 * (Contract 8: Recipe and Prefab Chaining).
 */
public final class SetupRecipe {

    private SetupRecipe() {
    }

    /**
     * Sparse configuration overlay step targeting a specific configuration file ID.
     *
     * @param targetFileId target config file identifier (e.g. "definitions/regions/default", "addons/guimenu")
     * @param overlay      key-value overlay map
     */
    public record PrefabStep(String targetFileId, Map<String, Object> overlay) {
        public PrefabStep {
            Objects.requireNonNull(targetFileId, "targetFileId");
            Objects.requireNonNull(overlay, "overlay");
        }
    }

    /**
     * Resolves the ordered chain of prefabs corresponding to the given setup session
     * using the active runtime world list.
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
     * 3. Region Radius overlay (sets shape.radius on default and per-world regions in multi mode)
     * 4. Performance Profile (Low, High, Folia)
     * 5. Addons and Effects overlay (including addons/guimenu when openGuiOnRtp is false)
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

        // Region Radius overlay
        recipe.add(createRadiusPrefab(session, worldNames));

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
        if (!Boolean.TRUE.equals(session.addonToggles().getOrDefault("openGuiOnRtp", true))) {
            recipe.add(createGuiMenuPrefab());
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

        boolean isMultiWorld = recipe.stream().anyMatch(p -> "multi-world".equals(p.id()));
        MultiWorldExpander.RegionOverlayAmender amender = MultiWorldExpander.defaultDimensionVertAmender();
        Map<String, Map<String, Object>> currentTrees = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : baselineTrees.entrySet()) {
            currentTrees.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }

        for (Prefab p : recipe) {
            Prefab effectivePrefab = prepareStepPrefab(p, currentTrees, worldNames, isMultiWorld);
            PrefabApplier.Result stepResult = PrefabApplier.apply(currentTrees, effectivePrefab, worldNames, amender);
            currentTrees = stepResult.newTrees();
        }

        // Compute cumulative diff against the original baseline
        return computeCumulativeDiff(baselineTrees, currentTrees);
    }

    private static Prefab prepareStepPrefab(
            Prefab p,
            Map<String, Map<String, Object>> currentTrees,
            List<String> worldNames,
            boolean isMultiWorld) {
        Map<String, Map<String, Object>> filteredRegionOverlays = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : p.regionOverlays().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("addons/")) {
                Map<String, Object> targetTree = currentTrees.computeIfAbsent(key, k -> new LinkedHashMap<>());
                targetTree.putAll(entry.getValue());
            } else if (key.startsWith("definitions/regions/")) {
                filteredRegionOverlays.put(key.substring("definitions/regions/".length()), entry.getValue());
            } else {
                filteredRegionOverlays.put(key, entry.getValue());
            }
        }

        if ("setup-radius".equals(p.id()) && isMultiWorld && worldNames != null) {
            Map<String, Object> defOverlay = filteredRegionOverlays.get("default");
            if (defOverlay != null) {
                for (String world : worldNames) {
                    if (world != null && !world.isEmpty()) {
                        filteredRegionOverlays.putIfAbsent(world, new LinkedHashMap<>(defOverlay));
                    }
                }
            }
        }

        return new Prefab(
                p.id(),
                p.displayKey(),
                p.hoverKey(),
                p.description(),
                p.performanceOverlay(),
                p.safetyOverlay(),
                filteredRegionOverlays,
                p.expandPerWorld()
        );
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
                        "syncAllottedTime", 10,
                        "asyncAllottedTime", 25,
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

    private static Prefab createRadiusPrefab(SetupSession session, List<String> worldNames) {
        String radius = session.radiusChoice();
        Map<String, Object> radiusOverlay = Map.of("shape.radius", radius);
        Map<String, Map<String, Object>> regionOverlays = new LinkedHashMap<>();
        regionOverlays.put("default", radiusOverlay);

        if ("multi".equalsIgnoreCase(session.worldChoice()) && worldNames != null) {
            for (String world : worldNames) {
                if (world != null && !world.isEmpty()) {
                    regionOverlays.put(world, radiusOverlay);
                }
            }
        }

        return new Prefab(
                "setup-radius",
                "setupRadiusRow",
                "setupRadiusHover",
                "Region radius overlay from setup wizard choice (" + radius + ").",
                Map.of(),
                Map.of(),
                regionOverlays,
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

    private static Prefab createGuiMenuPrefab() {
        return new Prefab(
                "addons/guimenu",
                "setupGuiMenuRow",
                "setupGuiMenuHover",
                "Configure bare /rtp GUI menu behavior.",
                Map.of(),
                Map.of(),
                Map.of("addons/guimenu", Map.of("openOnBareRtp", false)),
                false
        );
    }

    private static boolean hasAnyOverlay(Prefab p) {
        return !p.performanceOverlay().isEmpty()
                || !p.safetyOverlay().isEmpty()
                || !p.regionOverlays().isEmpty();
    }
}
