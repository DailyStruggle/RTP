package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.rtp.common.commands.prefab.builtin.FastPaced;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.FoliaTuned;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.HighPerformance;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.Lightweight;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.LowPerformance;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.MultiWorld;
import io.github.dailystruggle.rtp.common.commands.prefab.builtin.SurvivalDefault;

import java.util.List;
import java.util.Optional;

/**
 * Sealed in-code registry of the prefabs shipped in v1. The set is fixed at
 * compile time; changing it requires a code edit and a release. See
 * {@code docs/dev/scratch/PROPOSAL-admin-panel-prefabs.md} v3.1.
 *
 * <p>Listing order is the curated panel order from the locked decisions
 * (2026-05-20): identity, then the two performance axes, then the Folia
 * tuning, then the lightweight/fast-paced opposite-axis pair, then the
 * one-region-per-world expander.
 */
public final class PrefabRegistry {

    private static final List<Prefab> PREFABS = List.of(
            SurvivalDefault.INSTANCE,
            LowPerformance.INSTANCE,
            HighPerformance.INSTANCE,
            FoliaTuned.INSTANCE,
            Lightweight.INSTANCE,
            FastPaced.INSTANCE,
            MultiWorld.INSTANCE
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
