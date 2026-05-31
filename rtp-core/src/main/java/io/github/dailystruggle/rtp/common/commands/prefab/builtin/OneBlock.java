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
 *   <li><strong>Unique-placements radius</strong> ({@code shape}): turns on
 *       {@code uniquePlacements} so each spot is consumed once, and widens
 *       {@code radius} so footholds are spread far apart.</li>
 *   <li><strong>No schematic</strong>: instead of the per-region
 *       {@code schematic} knob (ADR-058), the foothold is created by the
 *       platform tool (see the Bukkit / Folia world adapters' {@code platform}
 *       method) using the existing {@code safety.yml} platform settings
 *       ({@code platformMaterial}, {@code platformRadius},
 *       {@code platformAirHeight}, {@code platformDepth}). Those are global
 *       knobs and are intentionally left at operator-controlled defaults.</li>
 * </ul>
 *
 * <p>The overlay is sparse and targets the existing {@code default} region
 * (mirroring {@link LowPerformance} / {@link MultiWorld}); only the keys above
 * are touched, everything else in {@code regions/default.yml} is preserved.
 * Does not touch {@code backlogCacheCap} (pro-vs-lite assembly-time knob; see
 * {@link Prefab} class javadoc).
 */
public final class OneBlock {

    public static final Prefab INSTANCE = new Prefab(
            "oneblock",
            "menuPrefabOneBlockRow",
            "menuPrefabOneBlockHover",
            "One-block: fixed-height vertical adjustor and unique placements, foothold built by the platform tool (no schematic).",
            Map.of(),
            Map.of("default", Map.of(
                    "vert", Map.of(
                            "name", "fixed",
                            "y", 64
                    ),
                    "shape", Map.of(
                            "uniquePlacements", true,
                            "radius", 10000
                    )
            )),
            false
    );

    private OneBlock() {
    }
}
