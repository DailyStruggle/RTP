package io.github.dailystruggle.rtp.api.group;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.concurrent.CompletableFuture;

/**
 * Platform-neutral entry point for multi-participant subspace group teleports.
 *
 * <p>This is the entire public surface of the feature: callers submit a {@link GroupPlacementRequest}
 * and receive a {@link GroupPlacementResult}. All orchestration - anchor selection, chunk-footprint
 * warming, capacity validation, safety verification, and per-participant dispatch - is the
 * implementation's concern and stays behind this interface.
 *
 * <p>The returned future completes off-tick and must never be blocked on the main thread (S-005).
 * Implementations fail closed: a request is always answered with a result, never silently dropped
 * (S-004).
 */
@PublicApi
public interface GroupPlacementService {

  /**
   * Places a group of participants within a localized subspace of the requested region.
   *
   * @param request the immutable placement request; must not be {@code null}
   * @return a future completing off-tick with the placement outcome; never {@code null}
   */
  CompletableFuture<GroupPlacementResult> place(GroupPlacementRequest request);
}
