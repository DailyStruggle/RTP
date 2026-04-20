package io.github.dailystruggle.rtp.folia.world;

import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.folia.thread.GlobalRegionThread;
import io.github.dailystruggle.rtp.folia.thread.RegionThread;
import io.github.dailystruggle.rtp.spigot.anvil.PaletteNormalizer;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;

/**
 * Folia-side {@link RTPChunk} implementation. Carries an internal source union
 * (ADR-016 §11) of either a live {@link Chunk} or an {@link AnvilChunkView} read
 * from a persisted region file off the tick thread. Dispatch on every query is
 * a single branch on the populated source field.
 *
 * <p>Anvil-backed instances are constructed by
 * {@link FoliaRTPWorld#getCachedChunk(long)} when the pre-filter previously
 * produced a decoded view for this chunk key. They carry the decoded snapshot
 * plus the world identity + chunk coordinates needed to answer RTPChunk queries
 * without ever hopping to the Region Thread. The authoritative live
 * {@code chunk.isSafe(...)} re-check at teleport-commit time (ADR-016 §4)
 * remains the final arbiter — the Anvil-backed instance is replaced by a
 * live-loaded {@code FoliaRTPChunk} once the teleport pipeline commits.</p>
 *
 * <p>The Anvil-backed queries do <b>not</b> require the Region-Thread
 * annotation, because they touch no Folia region state — but we keep the
 * {@link RegionThread} annotation on the overrides for consistency with the
 * live-backed path, and because the caller still owns a region-thread context
 * when it enters the candidate loop.</p>
 */
public final class FoliaRTPChunk extends RTPChunk<Chunk> {

  /** Non-null iff this chunk is Anvil-backed (ADR-016 §11). */
  private final AnvilChunkView anvilView;

  /** Anvil-backed path only: chunk X, because {@link #anvilView} carries no coords. */
  private final int anvilCx;
  /** Anvil-backed path only: chunk Z. */
  private final int anvilCz;
  /** Anvil-backed path only: world UUID so {@link #getWorld()} stays O(1). */
  private final UUID anvilWorldId;
  /**
   * Anvil-backed path only: a reconciled unsafe-block set derived from the caller's
   * raw config set. {@code null} forces per-call reconciliation in
   * {@link #isSafe(int, int, int, Set)}.
   */
  private final Set<String> reconciledUnsafe;

  @RegionThread
  public FoliaRTPChunk(Chunk chunk) {
    super(chunk);
    this.anvilView = null;
    this.anvilCx = 0;
    this.anvilCz = 0;
    this.anvilWorldId = null;
    this.reconciledUnsafe = null;
  }

  /**
   * Anvil-backed constructor (ADR-016 §11). {@code chunk} is {@code null} because no live
   * chunk was loaded — this instance answers every query from {@code view}. The
   * {@code reconciledUnsafe} set, when non-null, short-circuits
   * {@link #isSafe(int, int, int, Set)} to skip per-call reconciliation; pass
   * {@code null} to force per-call reconciliation of the caller-supplied set.
   */
  public FoliaRTPChunk(
      AnvilChunkView view, int cx, int cz, UUID worldId, Set<String> reconciledUnsafe) {
    super(null);
    if (view == null) {
      throw new IllegalArgumentException("AnvilChunkView must be non-null for Anvil-backed chunks");
    }
    this.anvilView = view;
    this.anvilCx = cx;
    this.anvilCz = cz;
    this.anvilWorldId = worldId;
    this.reconciledUnsafe = reconciledUnsafe;
  }

  /** True iff this chunk instance is backed by an Anvil read-only snapshot. */
  public boolean isAnvilBacked() {
    return anvilView != null;
  }

  /**
   * ADR-015 stale-chunk-guard interop: an Anvil-backed instance owns its own
   * decoded snapshot and answers every block-data query without touching live
   * world state, so the guard's "is the live chunk still loaded?" check is moot
   * for this instance. Live-chunk path keeps the default {@code false}.
   */
  @Override
  public boolean isSelfContained() {
    return anvilView != null;
  }

  @Override
  @RegionThread
  public int x() {
    return (anvilView != null) ? anvilCx : chunk.getX();
  }

  @Override
  @RegionThread
  public int z() {
    return (anvilView != null) ? anvilCz : chunk.getZ();
  }

  @Override
  @GlobalRegionThread
  public RTPWorld<?> getWorld() {
    if (anvilView != null) {
      return RTP.serverAccessor.getRTPWorld(anvilWorldId);
    }
    return RTP.serverAccessor.getRTPWorld(chunk.getWorld().getUID());
  }

  @Override
  @RegionThread
  public boolean isGenerated() {
    if (anvilView != null) return true; // Anvil-backed == persisted == generated.
    return chunk.getWorld().isChunkGenerated(chunk.getX(), chunk.getZ());
  }

  @Override
  @RegionThread
  public boolean isLoaded() {
    if (anvilView != null) return false; // Snapshot is not a live chunk.
    return chunk.isLoaded();
  }

  @Override
  @GlobalRegionThread
  public void keep(boolean keep) {
    if (anvilView != null) return; // No live ticket to acquire on a snapshot.
    org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
    if (plugin == null || !plugin.isEnabled()) return;
    RTP.serverAccessor.getScheduler().runTask(() -> {
      if (keep) {
        if (!chunk.getPluginChunkTickets().contains(plugin)) {
          chunk.addPluginChunkTicket(plugin);
        }
      } else {
        chunk.removePluginChunkTicket(plugin);
      }
    });
  }

  @Override
  @RegionThread
  public boolean isAir(int x, int y, int z) {
    if (anvilView != null) {
      return anvilView.isAir(x & 0xF, y, z & 0xF);
    }
    return chunk.getBlock(x & 0xF, y, z & 0xF).getType().isAir();
  }

  @Override
  @RegionThread
  public int getSkyLight(int x, int y, int z) {
    if (anvilView != null) {
      // Anvil snapshots do not carry computed skylight; return a pessimistic 0
      // so downstream "prefer bright spots" heuristics treat the candidate as
      // ambiguous. The live re-check at teleport-commit time is authoritative.
      return 0;
    }
    return chunk.getBlock(x & 0xF, y, z & 0xF).getLightFromSky();
  }

  @Override
  @RegionThread
  public int getSurfaceHeight(int x, int z) {
    x = Math.max(0, Math.min(15, x));
    z = Math.max(0, Math.min(15, z));
    if (anvilView != null) {
      return anvilView.getSurfaceHeight(x, z);
    }
    int globalX = (chunk.getX() << 4) + x;
    int globalZ = (chunk.getZ() << 4) + z;
    return chunk.getWorld().getHighestBlockYAt(globalX, globalZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
  }

  @Override
  @RegionThread
  public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
    if (anvilView != null) {
      Set<String> effective = (reconciledUnsafe != null)
          ? reconciledUnsafe
          : PaletteNormalizer.reconcileAll(unsafeBlocks);
      return anvilView.isSafe(x & 0xF, y, z & 0xF, effective);
    }
    String materialName = chunk.getBlock(x & 0xF, y, z & 0xF).getType().name();
    return !unsafeBlocks.contains(materialName);
  }

  @Override
  @RegionThread
  public void unload() {
  }
}
