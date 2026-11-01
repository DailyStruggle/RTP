package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.Set;

/**
 * Test-only live {@link RTPChunk} reading the same per-column blocks as a {@link ChunkColumnProbe},
 * so one block layout drives both the live and probe adjust paths (ADR-016 parity).
 */
public final class ProbeBackedMockChunk extends RTPChunk<Object> {
  private final ChunkColumnProbe probe;
  private final RTPWorld<?> world;

  public ProbeBackedMockChunk(ChunkColumnProbe probe, RTPWorld<?> world) {
    super(new Object());
    this.probe = probe;
    this.world = world;
  }

  @Override
  public int x() {
    return probe.chunkX();
  }

  @Override
  public int z() {
    return probe.chunkZ();
  }

  @Override
  public String materialNameAt(int x, int y, int z) {
    String b = probe.blockAt(x, z, y);
    return b == null ? "" : AbstractVerticalAdjustor.canon(b);
  }

  @Override
  public boolean isAir(int x, int y, int z) {
    return probe.isAirAt(x, z, y);
  }

  @Override
  public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
    String name = materialNameAt(x, y, z);
    if (name.isEmpty()) return false;
    return unsafeBlocks == null || !unsafeBlocks.contains(name);
  }

  @Override
  public RTPWorld<?> getWorld() {
    return world;
  }

  @Override
  public boolean isGenerated() {
    return true;
  }

  @Override
  public boolean isLoaded() {
    return true;
  }

  @Override
  public void keep(boolean keep) {}

  @Override
  public void unload() {}
}
