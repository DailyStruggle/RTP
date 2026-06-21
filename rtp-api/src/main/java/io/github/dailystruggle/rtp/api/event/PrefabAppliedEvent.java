package io.github.dailystruggle.rtp.api.event;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, post-apply notification fired after an admin-panel prefab has been
 * written to disk and the affected configs have (best-effort) been reloaded.
 *
 * <p>This is a fire-and-forget notification: subscribers cannot veto or mutate
 * the apply, which has already happened by the time the event is delivered.
 * Register a subscriber through
 * {@code RTPAPI.onPrefabApplied(java.util.function.Consumer)}.
 *
 * <p><b>Threading:</b> the event is delivered on whatever thread executes the
 * prefab confirm command. Subscribers that need to touch the world must
 * re-schedule onto the RTP scheduler themselves rather than working inline.
 *
 * @param prefabId        the canonical id of the applied prefab; never {@code null}.
 * @param callerId        the UUID of the command caller that applied the prefab;
 *                        never {@code null}.
 * @param writtenFiles    the file ids written to disk (e.g. {@code "performance"},
 *                        {@code "regions/default"}); never {@code null}, may be
 *                        empty. Returned as an unmodifiable list.
 * @param changes         per-file list of {@link PrefabChange}s keyed by file id,
 *                        in stable iteration order; never {@code null}, may be
 *                        empty. Returned as an unmodifiable map.
 * @param reloadSucceeded {@code true} iff the best-effort config reload completed
 *                        successfully after the write.
 *
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
