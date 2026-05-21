package io.github.dailystruggle.rtp.common.commands.prefab;

import java.util.Map;
import java.util.Objects;

/**
 * A curated overlay applied on top of the current on-disk values of
 * {@code performance.yml} and {@code regions/<id>.yml}. Prefabs are sealed
 * Java constants in {@link PrefabRegistry}; there is no on-disk prefab file
 * format and no admin-authored extensibility in v1 (see
 * {@code docs/dev/scratch/PROPOSAL-admin-panel-prefabs.md} v3.1).
 *
 * <p>Overlays are <strong>sparse</strong>: only keys explicitly present in
 * {@code performanceOverlay} / {@code regionOverlays} are touched on apply;
 * everything else is preserved. Nested maps recurse; lists are replaced
 * wholesale (documented contract for {@code PrefabApplier} in session 2).
 *
 * <p>Per the locked design decision (2026-05-20), <strong>no</strong> prefab
 * overlay touches {@code backlogCacheCap}; that is a pro-vs-lite
 * assembly-time knob (the lite assembly hardcodes {@code backlogCacheCap: 0}
 * to disable the L3 backlog cache) and runtime prefabs must remain
 * assembly-agnostic. {@code PrefabRegistryTest} guards this invariant.
 *
 * @param id                 canonical id, e.g. {@code "low-performance"}; unique within {@link PrefabRegistry}.
 * @param displayKey         {@code MessagesKeys} entry for the panel row label.
 * @param hoverKey           {@code MessagesKeys} entry for the panel row hover tooltip.
 * @param description        English fallback description (locale-mirrored via the TSV pipeline in session 6).
 * @param performanceOverlay sparse overlay applied on top of {@code performance.yml}; never {@code null}, may be empty.
 * @param regionOverlays     {@code regionId -> sparse overlay}; applied on top of {@code regions/<regionId>.yml}.
 *                           Never {@code null}, may be empty. New-region overlays must carry the complete
 *                           required shape (validated by {@code PrefabApplier}).
 * @param expandPerWorld     when {@code true}, {@code PrefabApplier} synthesises per-world region overlays
 *                           cloning {@code regions/default.yml}; only {@code MultiWorld.INSTANCE} sets this.
 */
public record Prefab(
        String id,
        String displayKey,
        String hoverKey,
        String description,
        Map<String, Object> performanceOverlay,
        Map<String, Map<String, Object>> regionOverlays,
        boolean expandPerWorld
) {
    public Prefab {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayKey, "displayKey");
        Objects.requireNonNull(hoverKey, "hoverKey");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(performanceOverlay, "performanceOverlay");
        Objects.requireNonNull(regionOverlays, "regionOverlays");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Prefab id must not be empty");
        }
    }
}
