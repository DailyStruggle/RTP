package io.github.dailystruggle.rtp.common.selection.region.cache;

import java.util.function.Consumer;

/**
 * Pluggable ring-buffer storage delegate wrapped by {@link RingCacheStage}.
 *
 * <p>Decouples {@code selection.region.cache} from concrete buffer and location types in
 * {@code selection.region}.
 *
 * @governedBy ADR-115 (Package DAG Disentanglement and Finite Decision-Preservation Hypergraph)
 */
public interface RingBufferDelegate {

  /**
   * Installs persistence-visible ingress and egress callbacks on the underlying buffer.
   *
   * @param onAdd    ingress callback, or {@code null} for none
   * @param onRemove egress callback, or {@code null} for none
   */
  void setRawCallbacks(Consumer<Object> onAdd, Consumer<Object> onRemove);

  /**
   * Offers an item to the buffer, firing the ingress callback on success.
   *
   * @param item the candidate item
   * @return {@code true} if accepted and stored; {@code false} if rejected or full
   */
  boolean offerItem(Object item);

  /**
   * Offers an item to the buffer without firing the ingress callback.
   *
   * @param item the candidate item
   * @return {@code true} if accepted and stored; {@code false} if rejected or full
   */
  boolean offerItemSilently(Object item);

  /**
   * Polls the next item from the buffer, firing the egress callback when non-null.
   *
   * @return the polled item, or {@code null} if empty
   */
  Object pollItem();

  /**
   * Polls the next item from the buffer without firing the egress callback.
   *
   * @return the polled item, or {@code null} if empty
   */
  Object pollItemSilently();

  /**
   * @return current approximate occupancy
   */
  int size();

  /**
   * @return allocated slot capacity
   */
  int capacity();
}
