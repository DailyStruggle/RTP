package io.github.dailystruggle.rtp.api.world;

public record RTPCoords(String worldName, int x, int y, int z) {
  public long getChunkKey() {
    return ((long) (x >> 4) & 0xFFFFFFFFL) | (((long) (z >> 4) & 0xFFFFFFFFL) << 32);
  }
}
