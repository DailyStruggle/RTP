package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.rtp.common.commands.prefab.builtin.FoliaTuned;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.HighPerformance;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.LowPerformance;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.MultiWorld;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.OneBlock;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.Skyblock;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.SurvivalDefault;

import java.util.List;
import java.util.Optional;

/**
 * Sealed in-code registry of the prefabs shipped in v1. The set is fixed at
 * compile time; changing it requires a code edit and a release. See
 * {@code docs/dev/scratch/PROPOSAL-admin-panel-prefabs.md} v3.1 (original
 * seven-prefab lock) and {@code docs/dev/scratch/PROPOSAL-prefab-cleanup.md}
 * (2026-05-22 trim to five).
 *
 * <p>Listing order is the curated panel order: identity, the two
 * performance axes, the Folia tuning, the one-region-per-world expander,
 * then the {@code Skyblock} and {@code OneBlock} game-mode overlays. The
 * earlier {@code Lightweight} / {@code FastPaced} pair was dropped on 2026-05-22 as
 * redundant with {@code LowPerformance} on the low-end-server axis.
 */
public final class PrefabRegistry {

    private static final List<Prefab> PREFABS = List.of(
            SurvivalDefault.INSTANCE,
            LowPerformance.INSTANCE,
            HighPerformance.INSTANCE,
            FoliaTuned.INSTANCE,
            MultiWorld.INSTANCE,
            Skyblock.INSTANCE,
            OneBlock.INSTANCE
    );

    private PrefabRegistry() {
    }

    /**
     * @return the curated panel-order list of bundled prefabs (immutable).
     */
    public static List<Prefab> list() {
        return PREFABS;
    }

    /**
     * Lookup by canonical id (case-sensitive).
     *
     * @param id the canonical prefab id, e.g. {@code "low-performance"}.
     * @return the matching prefab, or {@link Optional#empty()} if no such id is registered.
     */
    public static Optional<Prefab> byId(String id) {
        if (id == null) return Optional.empty();
        for (Prefab p : PREFABS) {
            if (p.id().equals(id)) return Optional.of(p);
        }
        return Optional.empty();
    }
}
