package io.github.dailystruggle.rtp.common.anvil;

import io.github.dailystruggle.rtp.anvil.ColumnProbe;
import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;

import java.util.OptionalInt;

/**
 * Platform-neutral adapter exposing an {@code rtp-anvil} {@link ColumnProbe} as
 * {@link ChunkColumnProbe} for off-tick pre-filtering (ADR-016).
 *
 * @deprecated Replaced by {@link io.github.dailystruggle.rtp.anvil.AnvilColumnProbeAdapter} in {@code anvil-api}.
 */
@Deprecated
public final class AnvilColumnProbeAdapter implements ChunkColumnProbe {

  private final io.github.dailystruggle.rtp.anvil.AnvilColumnProbeAdapter delegate;

  public AnvilColumnProbeAdapter(ColumnProbe probe, int chunkX, int chunkZ) {
    this.delegate = new io.github.dailystruggle.rtp.anvil.AnvilColumnProbeAdapter(probe, chunkX, chunkZ);
  }

  @Override public int chunkX() { return delegate.chunkX(); }
  @Override public int chunkZ() { return delegate.chunkZ(); }
  @Override public int minY()   { return delegate.minY(); }
  @Override public int maxY()   { return delegate.maxY(); }

  @Override
  public OptionalInt heightmapTopY() {
    return delegate.heightmapTopY();
  }

  @Override
  public String blockAt(int y) {
    return delegate.blockAt(y);
  }

  @Override
  public String blockAt(int localX, int localZ, int y) {
    return delegate.blockAt(localX, localZ, y);
  }

  @Override
  public String biomeAt(int y) {
    return delegate.biomeAt(y);
  }

  @Override
  public boolean isAirAt(int y) {
    return delegate.isAirAt(y);
  }

  @Override
  public boolean isAirAt(int localX, int localZ, int y) {
    return delegate.isAirAt(localX, localZ, y);
  }
}
