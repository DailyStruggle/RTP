package io.github.dailystruggle.rtp.common.commands.prefab.builtin;

import io.github.dailystruggle.rtp.common.commands.menu.multiconfig.NetherEndConfigAmender;
import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One region per world. The only prefab that performs runtime enumeration:
 * sets {@code expandPerWorld = true}, so {@code PrefabApplier} (session 2)
 * delegates to {@code MultiWorldExpander} (session 3) to synthesise
 * {@code regions.<worldName>} entries cloning {@code regions/default.yml}
 * with {@code world: "<worldName>"}. Existing per-world regions are left
 * untouched (idempotent merge). Does not touch {@code backlogCacheCap}.
 * For detected non-overworld worlds (nether, the_end), provides vertical
 * adjustment overrides (linear search, clamped maxY, disabled requireSkyLight).
 */
public final class MultiWorld {

    public static final Prefab INSTANCE = new Prefab(
            "multi-world",
            "menuPrefabMultiWorldRow",
            "menuPrefabMultiWorldHover",
            "Synthesise one region per world from the current default.",
            Map.of(),
            Map.of(),
            Map.of(),
            true
    );

    private MultiWorld() {
    }

    /**
     * Builds a MultiWorld prefab customized for the given list of detected worlds.
     * Non-overworld worlds (Nether, The End) are populated with dimension-appropriate
     * vertical adjustor overlays so that vert settings are repaired upon expansion.
     *
     * @param worldNames list of active world names
     * @return a {@link Prefab} instance with per-world region overlays
     */
    public static Prefab createPrefab(List<String> worldNames) {
        if (worldNames == null || worldNames.isEmpty()) {
            return INSTANCE;
        }
        Map<String, Map<String, Object>> overlays = new LinkedHashMap<>();
        for (String world : worldNames) {
            if (world == null || world.isEmpty()) continue;
            Map<String, Object> vert = NetherEndConfigAmender.createDimensionVert(world);
            if (vert != null) {
                Map<String, Object> regionOverlay = new LinkedHashMap<>();
                regionOverlay.put("vert", vert);
                overlays.put(world, regionOverlay);
            }
        }
        if (overlays.isEmpty()) {
            return INSTANCE;
        }
        return new Prefab(
                INSTANCE.id(),
                INSTANCE.displayKey(),
                INSTANCE.hoverKey(),
                INSTANCE.description(),
                INSTANCE.performanceOverlay(),
                INSTANCE.safetyOverlay(),
                overlays,
                true
        );
    }
}
