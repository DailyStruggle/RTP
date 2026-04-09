package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.RTPCoords;

public record RTPLocation(RTPCoords coords, long attempts, ChunkReservation reservation) {
  public RTPLocation(RTPCoords coords, long attempts) {
    this(coords, attempts, null);
  }
}
