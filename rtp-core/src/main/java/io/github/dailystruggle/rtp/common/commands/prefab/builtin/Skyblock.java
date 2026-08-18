package io.github.dailystruggle.rtp.common.commands.prefab.builtin;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;

import java.util.Map;

/**
 * Skyblock server overlay prefab. Configures fixed-height vertical adjustor,
 * unique island placements, and extracts the bundled skyblock schematic.
 */
public final class Skyblock {

    public static final Prefab INSTANCE = new Prefab(
            "skyblock",
            "menuPrefabSkyblockRow",
            "menuPrefabSkyblockHover",
            "Skyblock: fixed-height vertical adjustor, unique island placements, and the skyblock schematic.",
            Map.of(),
            Map.of(),
            Map.of("default", Map.of(
                    "vert", Map.of(
                            "name", "fixed",
                            "y", 64
                    ),
                    "shape", Map.of(
                            "name", "SQUARE",
                            "uniquePlacements", 16,
                            "radius", 10000
                    ),
                    "schematic", "skyblock"
            )),
            false
    );

    private Skyblock() {
    }
}
