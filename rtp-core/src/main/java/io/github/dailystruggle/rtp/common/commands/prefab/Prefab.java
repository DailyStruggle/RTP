package io.github.dailystruggle.rtp.common.commands.prefab;

import java.util.Map;
import java.util.Objects;

/**
 * Curated sparse configuration overlay for performance, safety, and region settings.
 *
 * @param id                 canonical identifier unique within {@link PrefabRegistry}
 * @param displayKey         message key for panel row label
 * @param hoverKey           message key for panel tooltip
 * @param description        fallback description
 * @param performanceOverlay sparse overlay for {@code performance.yml}
 * @param safetyOverlay      sparse overlay for {@code safety.yml}
 * @param regionOverlays     per-region sparse overlays for {@code regions/<id>.yml}
 * @param expandPerWorld     whether per-world region overlays are synthesized across active worlds
 */
public record Prefab(
        String id,
        String displayKey,
        String hoverKey,
        String description,
        Map<String, Object> performanceOverlay,
        Map<String, Object> safetyOverlay,
        Map<String, Map<String, Object>> regionOverlays,
        boolean expandPerWorld
) {
    public Prefab {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayKey, "displayKey");
        Objects.requireNonNull(hoverKey, "hoverKey");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(performanceOverlay, "performanceOverlay");
        Objects.requireNonNull(safetyOverlay, "safetyOverlay");
        Objects.requireNonNull(regionOverlays, "regionOverlays");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Prefab id must not be empty");
        }
    }
}
