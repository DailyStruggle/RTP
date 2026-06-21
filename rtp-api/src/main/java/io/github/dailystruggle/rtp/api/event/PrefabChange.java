package io.github.dailystruggle.rtp.api.event;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;

/**
 * A single config-key change carried by a {@link PrefabAppliedEvent}.
 *
 * <p>This is the stable, {@code rtp-api}-side view of an internal core change
 * record; it intentionally exposes only the dot-delimited key path and the
 * before/after values so that addons can react to a prefab apply without
 * depending on {@code rtp-core} internals.
 *
 * @param keyPath  dot-delimited key path from the file root (e.g.
 *                 {@code "queue.maxSize"}); never {@code null}.
 * @param oldValue the previous value, or {@code null} if the key was absent.
 * @param newValue the new value after the overlay was merged in.
 *
 * @since 3.1.4
 */
@PublicApi
public record PrefabChange(String keyPath, Object oldValue, Object newValue) {
}
