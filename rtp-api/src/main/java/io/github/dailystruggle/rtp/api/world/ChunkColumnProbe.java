package io.github.dailystruggle.rtp.api.world;

import java.util.OptionalInt;

/**
 * Lean point-query view of a single chunk's column data.
 *
 * <p>Implements {@link io.github.dailystruggle.rtp.anvil.ChunkColumnProbe} from {@code anvil-api}
 * for seamless compatibility across platform-neutral anvil readers and RTP world APIs.
 */
public interface ChunkColumnProbe extends io.github.dailystruggle.rtp.anvil.ChunkColumnProbe {

  /**
   * Adapts any {@link io.github.dailystruggle.rtp.anvil.ChunkColumnProbe} to this interface.
   *
   * @param delegate base probe instance
   * @return ChunkColumnProbe view
   */
  static ChunkColumnProbe of(io.github.dailystruggle.rtp.anvil.ChunkColumnProbe delegate) {
    if (delegate == null) return null;
    if (delegate instanceof ChunkColumnProbe c) return c;
    return new ChunkColumnProbe() {
      @Override public int chunkX() { return delegate.chunkX(); }
      @Override public int chunkZ() { return delegate.chunkZ(); }
      @Override public int minY() { return delegate.minY(); }
      @Override public int maxY() { return delegate.maxY(); }
      @Override public OptionalInt heightmapTopY() { return delegate.heightmapTopY(); }
      @Override public String blockAt(int y) { return delegate.blockAt(y); }
      @Override public String blockAt(int lx, int lz, int y) { return delegate.blockAt(lx, lz, y); }
      @Override public String biomeAt(int y) { return delegate.biomeAt(y); }
      @Override public boolean isAirAt(int y) { return delegate.isAirAt(y); }
      @Override public boolean isAirAt(int lx, int lz, int y) { return delegate.isAirAt(lx, lz, y); }
    };
  }
}
