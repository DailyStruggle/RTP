package io.github.dailystruggle.rtp.api.event;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

/**
 * A single config-key change carried by a {@link PrefabAppliedEvent}.
 *
 * @param keyPath  dot-delimited key path from file root (e.g. {@code "queue.maxSize"}); non-null
 * @param oldValue previous value, or {@code null} if absent
 * @param newValue new value after overlay merge
 * @since 3.1.4
 */
@PublicApi
public record PrefabChange(String keyPath, Object oldValue, Object newValue) {
}
