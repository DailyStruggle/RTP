package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Tier 1 hook (ADR-115 section 1) letting {@code tools.MemoryTracker} read the number of
 * deferred arrival chunk holds and the active arrival hold chunk keys without importing
 * {@code tasks.teleport}. The suppliers are installed by the owning pipeline; they default
 * to empty/zero when no pipeline has registered.
 *
 * @see TrackableTask
 */
public final class ChunkTicketHolder {
  /** Returns the count of arrival chunk holds awaiting release. Replaced by the owning pipeline. */
  public static volatile IntSupplier heldChunkTicketCounter = () -> 0;

  /** Returns active arrival hold chunk keys by world. Replaced by the owning pipeline. */
  public static volatile Supplier<Map<RTPWorld<?>, Set<Long>>> arrivalHoldKeysSupplier = Collections::emptyMap;

  private ChunkTicketHolder() {
  }

  /**
   * Returns the count of deferred arrival chunk holds awaiting release.
   *
   * @return pending hold count, or zero when no counter is registered
   */
  public static int heldChunkTicketCount() {
    return heldChunkTicketCounter.getAsInt();
  }

  /**
   * Returns a snapshot mapping of active post-arrival hold chunk keys keyed by world.
   *
   * @return active hold chunk keys by world
   */
  public static Map<RTPWorld<?>, Set<Long>> arrivalHoldChunkKeys() {
    return arrivalHoldKeysSupplier.get();
  }
}
