package io.github.dailystruggle.rtp.api.event;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable post-apply event fired after an admin-panel prefab has been applied.
 *
 * @param prefabId canonical id of the applied prefab
 * @param callerId UUID of the command caller
 * @param writtenFiles file ids written to disk
 * @param changes per-file list of changes keyed by file id
 * @param reloadSucceeded {@code true} iff config reload completed successfully
 * @since 3.1.4
 */
@PublicApi
public record PrefabAppliedEvent(
        String prefabId,
        UUID callerId,
        List<String> writtenFiles,
        Map<String, List<PrefabChange>> changes,
        boolean reloadSucceeded
) {
    public PrefabAppliedEvent {
        Objects.requireNonNull(prefabId, "prefabId");
        Objects.requireNonNull(callerId, "callerId");
        Objects.requireNonNull(writtenFiles, "writtenFiles");
        Objects.requireNonNull(changes, "changes");
        writtenFiles = List.copyOf(writtenFiles);
        Map<String, List<PrefabChange>> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<PrefabChange>> e : changes.entrySet()) {
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        }
        changes = Collections.unmodifiableMap(copy);
    }
}
