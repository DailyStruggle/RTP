package io.github.dailystruggle.rtp.common.commands.prefab;

import java.util.List;
import java.util.Optional;

/**
 * Registry of bundled configuration prefabs.
 *
 * <p>Preserves curated menu order across performance profiles, platform tunings,
 * and game-mode overlays.
 *
 * @governedBy ADR-064
 * @governedBy ADR-115
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
