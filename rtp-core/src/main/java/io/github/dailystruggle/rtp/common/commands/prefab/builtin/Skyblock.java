package io.github.dailystruggle.rtp.common.commands.prefab.builtin;

import io.github.dailystruggle.rtp.common.commands.prefab.Prefab;

import java.util.Map;

/**
 * Skyblock-style server overlay. Reshapes the {@code default} region so that
 * teleports land on a pasted island instead of generated terrain:
 *
 * <ul>
 *   <li><strong>Vertical adjustor</strong> ({@code vert}): the
 *       {@code fixed} adjustor pinned to a single island height so every
 *       destination sits at the same Y (mid-air placement, no terrain scan
 *       and no sky-light requirement - a void world has no terrain to dig
 *       out of, and the platform tool builds the foothold on arrival).</li>
 *   <li><strong>Unique-placements radius</strong> ({@code shape}): turns on
 *       {@code uniquePlacements} so each island spot is consumed once, and
 *       widens {@code radius} so islands are spread far apart.</li>
 *   <li><strong>Skyblock schematic</strong> ({@code schematic}): names the
 *       per-region schematic knob (ADR-058) that the paster resolves to an
 *       on-disk {@code .schem}; {@code "skyblock"} is the bundled island.</li>
 * </ul>
 *
 * <p>The overlay is sparse and targets the existing {@code default} region
 * (mirroring {@link LowPerformance} / {@link MultiWorld}); only the keys above
 * are touched, everything else in {@code regions/default.yml} is preserved.
 * Does not touch {@code backlogCacheCap} (pro-vs-lite assembly-time knob; see
 * {@link Prefab} class javadoc).
 */
public final class Skyblock {

    public static final Prefab INSTANCE = new Prefab(
            "skyblock",
            "menuPrefabSkyblockRow",
            "menuPrefabSkyblockHover",
            "Skyblock: fixed-height vertical adjustor, unique island placements, and the skyblock schematic.",
            Map.of(),
            Map.of("default", Map.of(
                    "vert", Map.of(
                            "name", "fixed",
                            "y", 64
                    ),
                    "shape", Map.of(
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
