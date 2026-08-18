package io.github.dailystruggle.rtp.common.commands.prefab.builtin;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;

import java.util.Map;

/**
 * One-block server overlay: configures {@code default} region with fixed-height adjustor,
 * square unique placements (radius 16), and safety platform overlays (single GRASS_BLOCK column).
 */
public final class OneBlock {

    public static final Prefab INSTANCE = new Prefab(
            "oneblock",
            "menuPrefabOneBlockRow",
            "menuPrefabOneBlockHover",
            "One-block: fixed-height vertical adjustor and unique placements; foothold is a single grass block built by the platform tool (no schematic).",
            Map.of(),
            Map.of(
                    "platformRadius", 0,
                    "platformMaterial", "GRASS_BLOCK",
                    "platformDepth", 1,
                    "platformAirHeight", 2
            ),
            Map.of("default", Map.of(
                    "vert", Map.of(
                            "name", "fixed",
                            "y", 64
                    ),
                    "shape", Map.of(
                            "name", "SQUARE",
                            "uniquePlacements", 16,
                            "radius", 10000
                    )
            )),
            false
    );

    private OneBlock() {
    }
}
