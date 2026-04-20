package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.RTPCoords;

/**
 * Represents a potential teleportation location within a region. This record
 * holds the coordinates, the number of attempts it took to find this location,
 * and an optional chunk reservation to keep the area loaded.
 *
 * @param coords      The block coordinates of the location.
 * @param attempts    The number of attempts made to find a safe location.
 * @param reservation An optional {@link ChunkReservation} to keep the location's chunks loaded.
 */
public record RTPLocation(RTPCoords coords, long attempts, ChunkReservation reservation) {
  /**
   * Constructs an RTPLocation without a chunk reservation.
   *
   * @param coords   The block coordinates of the location.
   * @param attempts The number of attempts made to find a safe location.
   */
  public RTPLocation(RTPCoords coords, long attempts) {
    this(coords, attempts, null);
  }
}
