package io.github.dailystruggle.rtp.spigot.world;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.spigot.anvil.PaletteNormalizer;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;

/**
 * Bukkit-side {@link RTPChunk} implementation. Carries an internal source union
 * (ADR-016) of either a live {@link Chunk} or an {@link AnvilChunkView} read from a
 * persisted region file off the tick thread. Dispatch on every query is a single
 * branch on the populated source field.
 *
 * <p>Anvil-backed instances are constructed by {@code BukkitRTPWorld.getChunkAt}
 * when the Phase 3a pre-filter returns {@code ACCEPT} and the caller opted into
 * the Phase 3b {@code probeDetailed} path. They carry the decoded snapshot plus
 * the world identity + chunk coordinates needed to answer RTPChunk queries without
 * touching any tick-thread state. The authoritative live
 * {@code chunk.isSafe(...)} re-check at teleport-commit time (ADR-016 §4) is
 * preserved — the Anvil-backed instance is never promoted to a live chunk
 * in-place; it is replaced by the live-loaded {@code BukkitRTPChunk} once the
 * teleport pipeline commits.
 */
public final class BukkitRTPChunk extends RTPChunk<Chunk> {

  /** Non-null iff this chunk is Anvil-backed (ADR-016). */
  private final AnvilChunkView anvilView;

  /** Anvil-backed path only: chunk X, because {@link #anvilView} carries no coords. */
  private final int anvilCx;
  /** Anvil-backed path only: chunk Z. */
  private final int anvilCz;
  /** Anvil-backed path only: the world UUID so {@link #getWorld()} stays O(1). */
  private final UUID anvilWorldId;
  /**
   * Anvil-backed path only: a reconciled unsafe-block set derived from the caller's
   * raw config set. {@code null} when the chunk was built without a caller-supplied
   * unsafe list (in which case {@link #isSafe(int, int, int, Set)} re-reconciles the
   * per-call set, which is the back-compat path for ad-hoc callers).
   */
  private final Set<String> reconciledUnsafe;

  public BukkitRTPChunk(Chunk chunk) {
    super(chunk);
    this.anvilView = null;
    this.anvilCx = 0;
    this.anvilCz = 0;
    this.anvilWorldId = null;
    this.reconciledUnsafe = null;
  }

  /**
   * Anvil-backed constructor (ADR-016). {@code chunk} is {@code null} because no live
   * chunk was loaded — this instance answers every query from {@code view}. The
   * {@code reconciledUnsafe} set, when non-null, short-circuits
   * {@link #isSafe(int, int, int, Set)} to skip per-call reconciliation; pass
   * {@code null} to force per-call reconciliation of the caller-supplied set.
   */
  public BukkitRTPChunk(
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
   * ADR-015 stale-chunk-guard interop: an Anvil-backed instance owns its
   * own decoded snapshot and answers every block-data query without touching live
   * world state, so the guard's "is the live chunk still loaded?" check is moot
   * for this instance. Live-chunk path keeps the default {@code false}.
   */
  @Override
  public boolean isSelfContained() {
    return anvilView != null;
  }

  @Override
  public int x() {
    return (anvilView != null) ? anvilCx : chunk.getX();
  }

  @Override
  public int z() {
    return (anvilView != null) ? anvilCz : chunk.getZ();
  }

  @Override
  public RTPWorld<?> getWorld() {
    if (anvilView != null) {
      return RTP.serverAccessor.getRTPWorld(anvilWorldId);
    }
    return RTP.serverAccessor.getRTPWorld(chunk.getWorld().getUID());
  }

  @Override
  public boolean isGenerated() {
    // An Anvil view exists only because the region file entry for the chunk existed,
    // which is the definition of "generated and persisted". Live-chunk path delegates
    // to the world state.
    if (anvilView != null) return true;
    return chunk.getWorld().isChunkGenerated(chunk.getX(), chunk.getZ());
  }

  @Override
  public boolean isLoaded() {
    // An Anvil-backed chunk is by construction NOT loaded — that's the whole point.
    // If a downstream caller needs a loaded chunk (e.g. teleport commit), they must
    // re-fetch via BukkitRTPWorld.getChunkAt to trigger a live load.
    if (anvilView != null) return false;
    return chunk.isLoaded();
  }

  @Override
  public void keep(boolean keep) {
    if (anvilView != null) {
      // No-op: Anvil-backed chunks own no chunk ticket. When the pipeline decides to
      // keep a candidate, it re-fetches the live chunk at commit time, which creates
      // the BukkitRTPChunk over the live Chunk and then keep(true) applies the
      // plugin ticket. Preserves REQ-RTP-S-002 (nothing to release here).
      return;
    }
    org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
    if (plugin == null || !plugin.isEnabled()) return;

    if (keep) {
      if (!chunk.getPluginChunkTickets().contains(plugin)) {
        chunk.addPluginChunkTicket(plugin);
      }
    } else {
      chunk.removePluginChunkTicket(plugin);
    }
  }

  @Override
  public boolean isAir(int x, int y, int z) {
    if (anvilView != null) {
      return anvilView.isAir(x & 0xF, y, z & 0xF);
    }
    return chunk.getBlock(x & 0xF, y, z & 0xF).getType().isAir();
  }

  @Override
  public int getSkyLight(int x, int y, int z) {
    if (anvilView != null) {
      return anvilView.getSkyLight(x & 0xF, y, z & 0xF);
    }
    return chunk.getBlock(x & 0xF, y, z & 0xF).getLightFromSky();
  }

  @Override
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
  public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
    if (anvilView != null) {
      // Prefer the pre-reconciled set we were constructed with; if absent, reconcile
      // per-call to stay in sync with the raw config form (back-compat for ad-hoc
      // callers that construct an Anvil-backed chunk without supplying a reconciled set).
      Set<String> set =
          (reconciledUnsafe != null) ? reconciledUnsafe : PaletteNormalizer.reconcileAll(unsafeBlocks);
      return anvilView.isSafe(x & 0xF, y, z & 0xF, set);
    }
    // Live chunk path: reconcile both the block's material name and the raw unsafe
    // set to ensure a canonical comparison, matching the Anvil path's logic.
    String materialName = chunk.getBlock(x & 0xF, y, z & 0xF).getType().name();
    return !PaletteNormalizer.matches(materialName, PaletteNormalizer.reconcileAll(unsafeBlocks));
  }

  @Override
  public void unload() {
    if (anvilView != null) {
      // No live chunk to unload. GC will collect the view when the last reference drops.
      return;
    }
    if (Bukkit.isPrimaryThread()) chunk.unload(false);
    else {
      RTP instance = RTP.getInstance();
      if (instance != null) instance.chunksToUnload.offer(this);
    }
  }
}
