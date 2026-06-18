package io.github.dailystruggle.rtp.common.commands.prefab.builtin;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;

import java.util.Map;

/**
 * One-block-style server overlay. The void-world sibling of {@link Skyblock}:
 * it reshapes the {@code default} region so teleports land on a single
 * generated foothold instead of generated terrain, but - unlike
 * {@code Skyblock} - it relies on the older platform-builder settings rather
 * than pasting a bundled {@code .schem}:
 *
 * <ul>
 *   <li><strong>Vertical adjustor</strong> ({@code vert}): the
 *       {@code fixed} adjustor pinned to a single height so every destination
 *       sits at the same Y (mid-air placement, no terrain scan and no
 *       sky-light requirement - a void world has no terrain to dig out of).</li>
 *   <li><strong>Unique-placements radius</strong> ({@code shape}): pins the
 *       shape to {@code SQUARE} so both knobs below always apply, sets
 *       {@code uniquePlacements} to {@code 16} (the boolean {@code true} form
 *       is deprecated) so each spot is consumed once, and widens
 *       {@code radius} so footholds are spread far apart.</li>
 *   <li><strong>No schematic; platform foothold</strong>: instead of the
 *       per-region {@code schematic} knob (ADR-058), the foothold is created
 *       by the platform tool (see the Bukkit / Folia world adapters'
 *       {@code platform} method) using the {@code safety.yml} platform
 *       settings. Because those settings ship disabled by default
 *       ({@code platformRadius: -1}), this prefab carries a sparse
 *       <strong>safety overlay</strong> that turns the platform on and shapes
 *       it as a single grass block (the standard one-block starting point):
 *       {@code platformRadius: 0} (a single column), {@code platformMaterial:
 *       GRASS_BLOCK}, {@code platformDepth: 1}, {@code platformAirHeight: 2}
 *       (clear head space). All other {@code safety.yml} keys are preserved.</li>
 * </ul>
 *
 * <p>The overlays are sparse and target the existing {@code default} region
 * (mirroring {@link LowPerformance} / {@link MultiWorld}) plus the global
 * {@code safety.yml}; only the keys above are touched, everything else in
 * {@code regions/default.yml} and {@code safety.yml} is preserved. Does not
 * touch {@code backlogCacheCap} (operator-owned L3 knob; see
 * {@link Prefab} class javadoc).
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
