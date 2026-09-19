package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encapsulates a prepared group-capable subspace holding an anchor location, footprint radius,
 * verified standable candidate slot locations, and optional active {@link ChunkReservation}
 * tickets for its loaded footprint chunks (Group L1 hot queue).
 *
 * <p>Implements {@link AutoCloseable} to guarantee deterministic chunk ticket release
 * on drain, eviction, or disposal (S-002).
 */
public final class GroupSubspace implements AutoCloseable {
  private final RTPLocation anchor;
  private final int blockRadius;
  private final List<RTPLocation> slotLocations;
  private final List<ChunkReservation> reservations;
  private final AtomicBoolean closed = new AtomicBoolean(false);

  public GroupSubspace(
      RTPLocation anchor,
      int blockRadius,
      List<RTPLocation> slotLocations,
      List<ChunkReservation> reservations) {
    this.anchor = Objects.requireNonNull(anchor, "anchor cannot be null");
    this.blockRadius = blockRadius;
    this.slotLocations =
        (slotLocations == null)
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(slotLocations));
    this.reservations =
        (reservations == null) ? Collections.emptyList() : new ArrayList<>(reservations);
  }

  public RTPLocation anchor() {
    return anchor;
  }

  public int blockRadius() {
    return blockRadius;
  }

  public List<RTPLocation> slotLocations() {
    return slotLocations;
  }

  public List<ChunkReservation> reservations() {
    return reservations;
  }

  public boolean isHot() {
    return !reservations.isEmpty() && !closed.get();
  }

  /**
   * Transfers ownership of all underlying chunk reservations to the caller.
   * Clears internal references to prevent double-closing.
   */
  public synchronized List<ChunkReservation> transferReservations() {
    if (closed.get()) return Collections.emptyList();
    List<ChunkReservation> transferred = new ArrayList<>(this.reservations);
    this.reservations.clear();
    return transferred;
  }

  @Override
  public void close() {
    if (closed.compareAndSet(false, true)) {
      synchronized (this) {
        for (ChunkReservation reservation : reservations) {
          if (reservation != null) {
            try {
              reservation.close();
            } catch (Throwable ignored) {
            }
          }
        }
        reservations.clear();
      }
    }
  }
}
