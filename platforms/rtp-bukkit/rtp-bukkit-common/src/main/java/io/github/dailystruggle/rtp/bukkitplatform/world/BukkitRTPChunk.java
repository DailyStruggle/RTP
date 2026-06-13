package io.github.dailystruggle.rtp.bukkitplatform.world;

import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.bukkitplatform.anvil.PaletteNormalizer;
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
import org.bukkit.HeightMap;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

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

  /**
   * 5-second-throttled reconciled snapshot of {@code SafetyKeys.airBlocks}. Built
   * from {@code RTP.configs} via {@link PaletteNormalizer#reconcileAll(Collection)} so the
   * canonical, tag-expanded forms (per {@code JumpAdjustor.refreshSafetySets}'s
   * mutation of the parser) line up with both the live {@code Material.name()}
   * comparison and the {@link AnvilChunkView#isAir(int,int,int,Set)} comparison
   * against {@code AnvilPrefilter.DEFAULT_RECONCILER}'d palette ids. Refreshed
   * lazily on the next {@link #isAir(int,int,int)} call after the throttle
   * window expires; the same instance is shared across all {@link BukkitRTPChunk}
   * instances since the configured air list is process-global.
   */
  private static final AtomicReference<Set<String>> AIR_BLOCKS_CACHE =
      new AtomicReference<>(Collections.emptySet());
  private static final AtomicLong AIR_BLOCKS_LAST_UPDATE = new AtomicLong(0);
  private static final long AIR_BLOCKS_REFRESH_MS = 5_000L;

  private static Set<String> reconciledAirBlocks() {
    long now = System.currentTimeMillis();
    long last = AIR_BLOCKS_LAST_UPDATE.get();
    long dt = now - last;
    Set<String> cached = AIR_BLOCKS_CACHE.get();
    if (dt >= 0 && dt < AIR_BLOCKS_REFRESH_MS) return cached;
    if (!AIR_BLOCKS_LAST_UPDATE.compareAndSet(last, now)) {
      return AIR_BLOCKS_CACHE.get();
    }
    try {
      ConfigParser<SafetyKeys> safety =
          (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      if (safety == null) return cached;
      Object value = safety.getConfigValue(SafetyKeys.airBlocks, new ArrayList<>());
      if (!(value instanceof Collection<?> coll)) return cached;
      // Tag expansion: #namespace:tag tokens (e.g. "#minecraft:leaves") are NOT
      // resolved by Material.matchMaterial, so PaletteNormalizer.reconcileAll
      // would coerce them to the literal "#MINECRAFT:LEAVES" string which never
      // matches a palette/Material id. Consult the server-supplied block-tag
      // snapshot (RTPServerAccessor.blockTagSnapshot, kept up to date by
      // /rtp reload via rebuildBlockTagSnapshot) and replace each tag token
      // with its member material names BEFORE reconciliation. Bare materials
      // and state-predicated MATERIAL[prop=val] tokens pass through unchanged.
      java.util.Map<String, java.util.Set<String>> tagSnapshot =
          java.util.Collections.emptyMap();
      if (RTP.serverAccessor != null) {
        try {
          java.util.Map<String, java.util.Set<String>> s =
              RTP.serverAccessor.blockTagSnapshot();
          if (s != null) tagSnapshot = s;
        } catch (Throwable ignoredTag) {
          // best-effort
        }
      }
      Set<String> raw = new java.util.HashSet<>();
      for (Object o : coll) {
        if (o == null) continue;
        String token = o.toString().trim();
        if (token.isEmpty()) continue;
        if (token.charAt(0) == '#') {
          String tagId = token.substring(1);
          if (tagId.indexOf(':') < 0) tagId = "minecraft:" + tagId;
          tagId = tagId.toLowerCase(Locale.ROOT);
          java.util.Set<String> members = tagSnapshot.get(tagId);
          if (members != null && !members.isEmpty()) {
            raw.addAll(members);
            continue;
          }
          // Snapshot empty / tag missing: preserve original token so a later
          // refresh can resolve it once the snapshot is populated.
          raw.add(token);
        } else {
          raw.add(token);
        }
      }
      Set<String> reconciled = PaletteNormalizer.reconcileAll(raw);
      AIR_BLOCKS_CACHE.set(reconciled);
      return reconciled;
    } catch (Throwable ignored) {
      return cached;
    }
  }

  @Override
  public boolean isAir(int x, int y, int z) {
    Set<String> airSet = reconciledAirBlocks();
    if (anvilView != null) {
      return anvilView.isAir(x & 0xF, y, z & 0xF, airSet);
    }
    org.bukkit.Material type = chunk.getBlock(x & 0xF, y, z & 0xF).getType();
    if (type.isAir()) return true;
    if (airSet.isEmpty()) return false;
    return PaletteNormalizer.matches(type.name(), airSet);
  }

  /**
   * ADR-016 §13.1 post-load biome read, chunk-local. Anvil-backed: consult the
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

  /**
   * Compiled-form safety check (ADR-017). Extracts the candidate block's material name
   * and — only when the {@link CompiledUnsafeSet} has any state predicate that could
   * apply — its {@link BlockData#getAsString()} property map, then delegates to
   * {@link CompiledUnsafeSet#isUnsafe(String, java.util.Collection, Map)}.
   *
   * <p>Hot-path fast exits per ADR-017 &sect;4:</p>
   * <ul>
   *   <li>{@link CompiledUnsafeSet#isEmpty()} → always safe, zero allocations.</li>
   *   <li>Anvil-backed chunks fall back to the legacy {@code Set<String>} path using
   *       {@link CompiledUnsafeSet#plainMaterials()} — state-predicate evaluation on
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

    if (anvilView != null) {
      // Anvil-backed snapshots evaluate only the plain-material bucket of the
      // compiled set. State and tag predicates against Anvil palette data are not
      // yet supported. The live re-check at teleport-commit time remains
      // authoritative (ADR-016 §4), so any predicate this path misses is
      // re-evaluated by the live BukkitRTPChunk before teleport.
      Set<String> plain = (reconciledUnsafe != null) ? reconciledUnsafe : unsafeBlocks.plainMaterials();
      return anvilView.isSafe(x & 0xF, y, z & 0xF, plain);
    }

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
      if (instance != null) instance.chunksToUnload.offer(this);
    }
  }
}
