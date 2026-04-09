package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;

public record GenerationResult(RTPCoords coords, long attempts, ChunkSet verifiedChunks, ChunkReservation reservation) {
  public GenerationResult(RTPCoords coords, long attempts, ChunkSet verifiedChunks) {
    this(coords, attempts, verifiedChunks, null);
  }
}
