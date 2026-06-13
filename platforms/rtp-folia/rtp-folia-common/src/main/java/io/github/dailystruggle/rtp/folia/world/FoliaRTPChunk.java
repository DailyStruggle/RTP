package io.github.dailystruggle.rtp.folia.world;

import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.folia.thread.GlobalRegionThread;
import io.github.dailystruggle.rtp.folia.thread.RegionThread;
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
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

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

  /**
   * 5-second-throttled reconciled snapshot of {@code SafetyKeys.airBlocks}.
   * Mirror of the cache in {@code BukkitRTPChunk} — see that class for rationale.
   * Process-global, refreshed lazily on the next {@link #isAir(int,int,int)}
   * call after the throttle window expires.
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
      // Tag expansion: see BukkitRTPChunk#reconciledAirBlocks for rationale.
      // #namespace:tag tokens (e.g. "#minecraft:leaves") are NOT resolved by
      // Material.matchMaterial, so we expand them via the server-supplied
      // block-tag snapshot before reconciliation.
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
  @RegionThread
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
  @RegionThread
  public String getBiome(int x, int y, int z) {
    if (anvilView != null) {
      String id = anvilView.getBiomeAt(x & 0xF, y, z & 0xF);
      if (id != null) {
        // Normalize the on-disk biome identifier (e.g. "minecraft:plains") to
        // the RTP-config-comparable form ("PLAINS"). `PaletteNormalizer` is
        // for blocks (Material registry); biomes use the pure-string
        // namespace-strip + upper-case helper from `rtp-api`.
        String normalized = io.github.dailystruggle.rtp.api.configuration
            .PaletteIdentifierNormalizer.normalize(id);
        return (normalized != null && !normalized.isEmpty()) ? normalized : id;
      }
      // Anvil view had no biome container at this Y; fall back to the world
      // getter as a last resort (matches the §13.1 precedence chain tail).
      return super.getBiome(x, y, z);
    }
    if (chunk != null) {
      try {
        return chunk.getBlock(x & 0xF, y, z & 0xF).getBiome().name();
      } catch (Throwable ignored) {
        // Fall through to the world getter if the live path throws (e.g.
        // post-unload race).
      }
    }
    return super.getBiome(x, y, z);
  }

  @Override
  @RegionThread
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
    // Callers are required to invoke this on the chunk's owning region thread
    // (enforced upstream in QueueTask.evaluateSafety / Region.execute, which
    // dispatch via RTP.scheduler.runTask(world, cx, cz, ...)). No defensive
    // region-ownership guard here — it would fail-closed on legitimate
    // candidates and pollute the "bad chunk" signal.
    String materialName = chunk.getBlock(x & 0xF, y, z & 0xF).getType().name();
    return !unsafeBlocks.contains(materialName);
  }

  /**
   * Compiled-form safety check (ADR-017). Mirror of {@code BukkitRTPChunk} override —
   * see that class's Javadoc for the evaluation contract, hot-path fast exits, and
   * boundary caveats (tag membership stubbed to empty collection; Anvil-backed
   * snapshots evaluate only the plain-material bucket; state / tag predicates
   * are not yet supported).
   *
   * <p>Runs under {@link RegionThread} because the live-backed path reads from the
   * chunk and the caller already holds region-thread context in {@code LocationGenerator};
   * the Anvil-backed branch is thread-agnostic (self-contained snapshot, ADR-015 §interop).</p>
   */
  @Override
  @RegionThread
  public boolean isSafe(int x, int y, int z, CompiledUnsafeSet unsafeBlocks) {
    if (unsafeBlocks == null || unsafeBlocks.isEmpty()) return true;

    if (anvilView != null) {
      Set<String> plain = (reconciledUnsafe != null) ? reconciledUnsafe : unsafeBlocks.plainMaterials();
      return anvilView.isSafe(x & 0xF, y, z & 0xF, plain);
    }
    // Callers are required to invoke this on the chunk's owning region thread
    // (see Set<String> overload above).
    Block block = chunk.getBlock(x & 0xF, y, z & 0xF);
    String materialName = block.getType().name();

    boolean needsProperties =
        unsafeBlocks.hasWildcardStatePredicate()
            || unsafeBlocks.materialStatePredicates().containsKey(materialName);
    Map<String, String> props = needsProperties
        ? extractProperties(block.getBlockData())
        : Collections.emptyMap();

    return !unsafeBlocks.isUnsafe(materialName, Collections.emptyList(), props);
  }

  /**
   * Parse a {@link BlockData#getAsString()} into a lowercase property map. See the
   * equivalent helper in {@code BukkitRTPChunk} for the format contract; the
   * implementation is duplicated deliberately so the Folia adapter retains zero
   * compile-time coupling to {@code rtp-spigot-common}'s internal helpers (keeps the
   * platform boundary clean per Architecture Boundaries rule §4).
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
  @RegionThread
  public void unload() {
  }
}
