package io.github.dailystruggle.rtp.bukkitplatform.world;

import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.tick.MiscTasks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Bukkit-side {@link RTPChunk} implementation. Carries an internal source union
 * (ADR-016) of either a live {@link Chunk} or an {@link AnvilChunkView} read from a
 * persisted region file off the tick thread. Dispatch on every query is a single
 * branch on the populated source field.
 *
 * <p>Anvil-backed instances are constructed by {@code BukkitRTPWorld.getChunkAt}
 * when the pre-filter returns {@code ACCEPT} and the caller opted into
 * the detailed probe path. They carry the decoded snapshot plus
 * the world identity + chunk coordinates needed to answer RTPChunk queries without
 * touching any tick-thread state. The authoritative live
 * {@code chunk.isSafe(...)} re-check at teleport-commit time (ADR-016 section 4) is
 * preserved - the Anvil-backed instance is never promoted to a live chunk
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
   * chunk was loaded - this instance answers every query from {@code view}. The
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
      return (RTPRuntime.serverAccessor() != null) ? RTPRuntime.serverAccessor().getRTPWorld(anvilWorldId) : null;
    }
    if (chunk == null) return null;
    return (RTPRuntime.serverAccessor() != null) ? RTPRuntime.serverAccessor().getRTPWorld(chunk.getWorld().getUID()) : null;
  }

  @Override
  public boolean isLoaded() {
    // An Anvil-backed chunk is by construction NOT loaded - that's the whole point.
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
  public String materialNameAt(int x, int y, int z) {
    if (anvilView != null) {
      String id = anvilView.blockIdAt(x & 0xF, y, z & 0xF);
      return (id != null) ? id.toUpperCase(Locale.ROOT) : "AIR";
    }
    if (chunk == null) return "";
    org.bukkit.World w = chunk.getWorld();
    if (y < w.getMinHeight() || y >= w.getMaxHeight()) return "";
    return chunk.getBlock(x & 0xF, y, z & 0xF).getType().name();
  }

  /**
   * ADR-016 section 13.1 post-load biome read, chunk-local. Anvil-backed: consult the
   * decoded {@link AnvilChunkView#getBiomeAt(int,int,int)} (chunk-local x/z,
   * absolute Y). Live-backed: delegate to the loaded block's biome. Neither
   * path consults {@code anvilProbeSupport}, so an evicted cache entry cannot
   * cause a fall-through to the seed-synth getter when the caller already
   * holds a valid chunk handle (the 2026-04-20 `no-view-cached` regression).
   */
  @Override
  public String getBiome(int x, int y, int z) {
    if (anvilView != null) {
      String id = anvilView.getBiomeAt(x & 0xF, y, z & 0xF);
      if (id != null) {
        String normalized = io.github.dailystruggle.rtp.api.configuration
            .PaletteIdentifierNormalizer.normalize(id);
        return (normalized != null && !normalized.isEmpty()) ? normalized : id;
      }
      return super.getBiome(x, y, z);
    }
    if (chunk != null) {
      try {
        return chunk.getBlock(x & 0xF, y, z & 0xF).getBiome().name();
      } catch (Throwable ignored) {
        // Fall through to the world getter on live-path failure.
      }
    }
    return super.getBiome(x, y, z);
  }

  @Override
  public int getSkyLight(int x, int y, int z) {
    if (anvilView != null) {
      // Anvil-backed snapshots do not parse SkyLight nibbles. Report the
      // vanilla "absent tag → fully lit" default; the live re-check at
      // teleport-commit time remains authoritative for any sky-light gating.
      return 15;
    }
    if (chunk != null) {
      org.bukkit.World w = chunk.getWorld();
      if (y < w.getMinHeight() || y >= w.getMaxHeight()) return 0;
      return chunk.getBlock(x & 0xF, y, z & 0xF).getLightFromSky();
    }
    return 15;
  }

  @Override
  public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
    if (chunk != null) {
      org.bukkit.World w = chunk.getWorld();
      if (y < w.getMinHeight() || y >= w.getMaxHeight()) return false;
    }
    Set<String> reconciledUnsafeSet = (RTPRuntime.serverAccessor() != null)
        ? RTPRuntime.serverAccessor().reconcilePaletteIdentifiers(unsafeBlocks)
        : io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer.normalizeAll(unsafeBlocks);
    if (anvilView != null) {
      // Prefer the pre-reconciled set we were constructed with; if absent, reconcile
      // per-call to stay in sync with the raw config form (back-compat for ad-hoc
      // callers that construct an Anvil-backed chunk without supplying a reconciled set).
      Set<String> set = (reconciledUnsafe != null) ? reconciledUnsafe : reconciledUnsafeSet;
      return anvilView.isSafe(x & 0xF, y, z & 0xF, set);
    }
    // S-001: no live chunk and no snapshot means the block is unknown, which is unsafe.
    if (chunk == null) return false;
    // Live chunk path: reconcile both the block's material name and the raw unsafe
    // set to ensure a canonical comparison, matching the Anvil path's logic.
    String materialName = chunk.getBlock(x & 0xF, y, z & 0xF).getType().name();
    return (RTPRuntime.serverAccessor() != null)
        ? !RTPRuntime.serverAccessor().matchesPaletteIdentifier(materialName, reconciledUnsafeSet)
        : !reconciledUnsafeSet.contains(io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer.normalize(materialName));
  }

  /**
   * Compiled-form safety check (ADR-017). Extracts the candidate block's material name
   * and - only when the {@link CompiledUnsafeSet} has any state predicate that could
   * apply - its {@link BlockData#getAsString()} property map, then delegates to
   * {@link CompiledUnsafeSet#isUnsafe(String, java.util.Collection, Map)}.
   *
   * <p>Hot-path fast exits per ADR-017 &sect;4:</p>
   * <ul>
   *   <li>{@link CompiledUnsafeSet#isEmpty()} → always safe, zero allocations.</li>
   *   <li>Anvil-backed chunks fall back to the legacy {@code Set<String>} path using
   *       {@link CompiledUnsafeSet#plainMaterials()} - state-predicate evaluation on
   *       off-tick Anvil data is not yet supported.</li>
   *   <li>No state predicate configured for this material / tag / wildcard →
   *       {@link BlockData} is never materialised.</li>
   * </ul>
   *
   * <p>Live tag membership is passed as an empty collection; tag-scoped
   * predicates are therefore effectively inert on the live path.</p>
   */
  @Override
  public boolean isSafe(int x, int y, int z, CompiledUnsafeSet unsafeBlocks) {
    if (unsafeBlocks == null || unsafeBlocks.isEmpty()) return true;

    if (chunk != null) {
      org.bukkit.World w = chunk.getWorld();
      if (y < w.getMinHeight() || y >= w.getMaxHeight()) return false;
    }

    if (anvilView != null) {
      // Anvil-backed snapshots evaluate only the plain-material bucket of the
      // compiled set. State and tag predicates against Anvil palette data are not
      // yet supported. The live re-check at teleport-commit time remains
      // authoritative (ADR-016 section 4), so any predicate this path misses is
      // re-evaluated by the live BukkitRTPChunk before teleport.
      Set<String> plain = (reconciledUnsafe != null) ? reconciledUnsafe : unsafeBlocks.plainMaterials();
      return anvilView.isSafe(x & 0xF, y, z & 0xF, plain);
    }
    if (chunk == null) return false;

    Block block = chunk.getBlock(x & 0xF, y, z & 0xF);
    String materialName = block.getType().name();

    // Does any state predicate bucket apply to this material?
    boolean needsProperties =
        unsafeBlocks.hasWildcardStatePredicate()
            || unsafeBlocks.materialStatePredicates().containsKey(materialName);
    Map<String, String> props = needsProperties
        ? extractProperties(block.getBlockData())
        : Collections.emptyMap();

    // Live tag membership is not yet populated.
    return !unsafeBlocks.isUnsafe(materialName, Collections.emptyList(), props);
  }

  /**
   * Parse a {@link BlockData#getAsString()} into a lowercase property map. Handles the
   * canonical Bukkit serialization format {@code minecraft:oak_slab[type=bottom,waterlogged=false]};
   * returns an empty map when no bracketed property block is present (block has no
   * properties). All keys and values are lower-cased under {@link Locale#ROOT} so they
   * match the predicate-side canonical form produced by {@link CompiledUnsafeSet}.
   */
  private static Map<String, String> extractProperties(BlockData data) {
    if (data == null) return Collections.emptyMap();
    String s;
    try {
      s = data.getAsString();
    } catch (Throwable t) {
      return Collections.emptyMap();
    }
    if (s == null) return Collections.emptyMap();
    int open = s.indexOf('[');
    if (open < 0) return Collections.emptyMap();
    int close = s.lastIndexOf(']');
    if (close <= open + 1) return Collections.emptyMap();
    String body = s.substring(open + 1, close);
    if (body.isEmpty()) return Collections.emptyMap();

    Map<String, String> out = new LinkedHashMap<>(4);
    int start = 0;
    int len = body.length();
    for (int i = 0; i <= len; i++) {
      if (i == len || body.charAt(i) == ',') {
        String pair = body.substring(start, i).trim();
        start = i + 1;
        if (pair.isEmpty()) continue;
        int eq = pair.indexOf('=');
        if (eq <= 0 || eq == pair.length() - 1) continue;
        String k = pair.substring(0, eq).trim().toLowerCase(Locale.ROOT);
        String v = pair.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
        if (!k.isEmpty()) out.put(k, v);
      }
    }
    return out;
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
      if (instance != null) MiscTasks.chunksToUnload().offer(this);
    }
  }
}
