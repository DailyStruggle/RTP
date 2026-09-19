package io.github.dailystruggle.rtp.fabric.world;

import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer;
import io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.anvil.PaletteNormalizer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fabric {@link RTPChunk}. Dual-mode:
 * <ol>
 *   <li><b>Live mode</b> - wraps a {@link ChunkAccess} at
 *       {@link net.minecraft.world.level.chunk.status.ChunkStatus#FULL}.
 *       Block-data queries hit the live world state (S-005-safe: no synchronous
 *       loads - the {@code ChunkAccess} is handed in by callers that already
 *       performed an off-tick load).</li>
 *   <li><b>Anvil mode</b> (ADR-016 / rtp-fabric-ADR-005 follow-up) - wraps an
 *       {@link AnvilChunkView} produced by {@code rtp-anvil} from the persisted
 *       {@code r.X.Z.mca} region file. The instance answers every block-data
 *       query from the decoded snapshot, never touching live world state, so
 *       candidate evaluation runs without forcing a chunk load on the tick
 *       thread. Mirrors {@code BukkitRTPChunk}'s anvil-backed constructor.</li>
 * </ol>
 *
 * <p>ADR-022 section 4: no {@code org.bukkit.*}; queries do not trigger loads (S-005);
 * stale-chunk guard is caller-side (ADR-015) for live mode and moot for anvil
 * mode (the snapshot is self-contained - see {@link #isSelfContained()}).
 * Material IDs are upper-cased {@code NAMESPACE:PATH} on the live path; the
 * anvil path delegates to {@link AnvilChunkView}, which already speaks the
 * canonical reconciled form. The {@link CompiledUnsafeSet} overload of
 * {@link #isSafe} delegates to the plain-material bucket only - state-predicate
 * parity with Bukkit is deferred (mirrors Spigot's anvil-mode behaviour);
 * commit-time recheck still guards landing.</p>
 */
public final class FabricRTPChunk extends RTPChunk<ChunkAccess> {

    /** Live mode: the underlying server level for biome / sky-light lookups. {@code null} in anvil mode. */
    private final @Nullable ServerLevel level;
    private final UUID worldId;
    private final int cx;
    private final int cz;

    /** Anvil mode only: the decoded chunk snapshot. {@code null} in live mode. */
    private final @Nullable AnvilChunkView anvilView;
    /**
     * Anvil mode only: a pre-reconciled unsafe-block set produced by
     * {@link PaletteNormalizer#reconcileAll}. {@code null} when the chunk
     * was built without a caller-supplied unsafe list - in which case
     * {@link #isSafe(int, int, int, Set)} reconciles the per-call set.
     */
    private final @Nullable Set<String> reconciledUnsafe;

    /** Live-chunk constructor (legacy / commit-time path). */
    public FabricRTPChunk(ChunkAccess chunk, ServerLevel level, UUID worldId) {
        super(chunk);
        this.level = level;
        this.worldId = worldId;
        ChunkPos pos = chunk.getPos();
        this.cx = pos.x;
        this.cz = pos.z;
        this.anvilView = null;
        this.reconciledUnsafe = null;
    }

    /**
     * Anvil-backed constructor (rtp-fabric-ADR-005 follow-up). {@code chunk}
     * is {@code null} because no live chunk was loaded - this instance answers
     * every query from {@code view}. The {@code reconciledUnsafe} set, when
     * non-null, short-circuits {@link #isSafe(int, int, int, Set)} to skip
     * per-call reconciliation; pass {@code null} to force per-call
     * reconciliation of the caller-supplied set.
     */
    public FabricRTPChunk(
            AnvilChunkView view, int cx, int cz, UUID worldId, @Nullable Set<String> reconciledUnsafe) {
        super(null);
        if (view == null) {
            throw new IllegalArgumentException(
                    "AnvilChunkView must be non-null for Anvil-backed chunks");
        }
        this.level = null;
        this.worldId = worldId;
        this.cx = cx;
        this.cz = cz;
        this.anvilView = view;
        this.reconciledUnsafe = reconciledUnsafe;
    }

    /** True iff this chunk instance is backed by an Anvil read-only snapshot. */
    public boolean isAnvilBacked() {
        return anvilView != null;
    }

    /**
     * ADR-015 stale-chunk-guard interop: an Anvil-backed instance owns its own
     * decoded snapshot and answers every block-data query without touching live
     * world state, so the guard's "is the live chunk still loaded?" check is
     * moot for this instance. Live-chunk path keeps the default {@code false}.
     */
    @Override
    public boolean isSelfContained() {
        return anvilView != null;
    }

    @Override
    public int x() {
        return cx;
    }

    @Override
    public int z() {
        return cz;
    }

    @Override
    public RTPWorld<?> getWorld() {
        if (RTP.serverAccessor == null) return null;
        return RTP.serverAccessor.getRTPWorld(worldId);
    }

    @Override
    public boolean isGenerated() {
        // An Anvil view exists only because the region file entry for the chunk
        // existed, which is the definition of "generated and persisted". Live
        // path: a constructed FabricRTPChunk wraps a ChunkAccess obtained at
        // ChunkStatus.FULL - by definition generated. Defensive on null.
        if (anvilView != null) return true;
        return chunk != null;
    }

    @Override
    public boolean isLoaded() {
        // An Anvil-backed chunk is by construction NOT loaded - that's the whole
        // point. Downstream callers needing a loaded chunk (e.g. teleport commit)
        // re-fetch via FabricRTPWorld.getChunkAt to trigger a live load.
        if (anvilView != null) return false;
        // Cheap, non-loading lookup; matches the FabricRTPWorld#isChunkLoaded
        // path used by the ADR-015 stale-chunk guard.
        try {
            return level != null && level.getChunkSource().hasChunk(cx, cz);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void keep(boolean keep) {
        if (anvilView != null) {
            // No-op: Anvil-backed chunks own no chunk ticket. When the pipeline
            // decides to keep a candidate, it re-fetches the live chunk at commit
            // time, which creates the FabricRTPChunk over the live ChunkAccess
            // and then keep(true) applies the plugin ticket. Preserves
            // REQ-RTP-S-002 (nothing to release here).
            return;
        }
        // Route through the world's ref-counted ticket map so multi-caller
        // hand-offs are safe; FabricRTPWorld.setForceLoadedImpl performs the
        // server-thread hop.
        RTPWorld<?> w = getWorld();
        if (w instanceof FabricRTPWorld fw) {
            if (keep) {
                fw.keepChunkAt(cx, cz);
            } else {
                fw.forgetChunkAt(cx, cz);
            }
        }
    }

    @Override
    public void unload() {
        if (anvilView != null) return; // Nothing to unload - see keep().
        // Vanilla Fabric has no analogue to Bukkit's Chunk#unload(false) -
        // chunk eviction is driven by the ticket system. Releasing our keep()
        // ticket is the closest approximation; if no caller is keeping this
        // chunk, the chunk system will evict it on its own schedule.
        keep(false);
    }

    // ---------------------------------------------------------------------------
    // Block-data queries
    // ---------------------------------------------------------------------------

    /**
     * Returns the upper-cased {@code namespace:path} block id for the block at
     * the given chunk-local coords (Y is absolute world Y). Live-mode only -
     * anvil-mode queries route through {@link AnvilChunkView} which speaks the
     * already-reconciled form. Returns {@code ""} on registry lookup failure.
     */
    private String materialNameAt(int x, int y, int z) {
        if (chunk == null) return "";
        try {
            BlockState state = chunk.getBlockState(new BlockPos(
                    (cx << 4) + (x & 0xF), y, (cz << 4) + (z & 0xF)));
            Block block = state.getBlock();
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            return (id == null) ? "" : id.toString().toUpperCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Process-wide cache of the reconciled {@code safety.airBlocks} set, mirroring
     * {@code BukkitRTPChunk.AIR_BLOCKS_CACHE}. Invalidated by
     * {@link RTPWorld#forgetChunks()} / {@code /rtp reload} via the same
     * {@code rebuildBlockTagSnapshot()} hook used by the Spigot side
     * (a fresh {@code reconciledAirBlocks()} call rebuilds eagerly because the
     * config source is consulted on every miss).
     *
     * <p>Ports the Spigot fix that landed when air-block flattening was added -
     * without this set the JumpAdjustor scan treats leaves, snow_layer,
     * flowers, etc. as solid ground and lands the player on top of them
     * (config-listed under {@code airBlocks} but invisible to vanilla
     * {@code BlockState.isAir()}).</p>
     */
    private static final AtomicReference<Set<String>> AIR_BLOCKS_CACHE =
            new AtomicReference<>(Collections.emptySet());

    /**
     * Resolve the {@code safety.airBlocks} config to a reconciled
     * {@code namespace-stripped + UPPER_CASE} set with {@code #namespace:tag}
     * tokens flattened through the server-supplied
     * {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#blockTagSnapshot()
     * block-tag snapshot}. Mirrors {@code BukkitRTPChunk.reconciledAirBlocks()};
     * see the comment block there for the rationale on tag-token preservation
     * when the snapshot is empty.
     *
     * <p>Cheap: cached in {@link #AIR_BLOCKS_CACHE}; rebuilt on every miss
     * (configurable lists change rarely; the cache absorbs the steady-state
     * cost). On any failure the previous cached value is returned.</p>
     */
    @SuppressWarnings("unchecked")
    private static Set<String> reconciledAirBlocks() {
        Set<String> cached = AIR_BLOCKS_CACHE.get();
        try {
            if (RTP.configs == null) return cached;
            Object value = RTP.configs.getConfigValue(BlocksKeys.airBlocks, new ArrayList<>());
            if (!(value instanceof Collection<?> coll)) return cached;
            Map<String, Set<String>> tagSnapshot = Collections.emptyMap();
            if (RTP.serverAccessor != null) {
                try {
                    Map<String, Set<String>> s = RTP.serverAccessor.blockTagSnapshot();
                    if (s != null) tagSnapshot = s;
                } catch (Throwable ignoredTag) {
                    // best-effort
                }
            }
            Set<String> raw = new HashSet<>();
            for (Object o : coll) {
                if (o == null) continue;
                String token = o.toString().trim();
                if (token.isEmpty()) continue;
                if (token.charAt(0) == '#') {
                    String tagId = token.substring(1);
                    if (tagId.indexOf(':') < 0) tagId = "minecraft:" + tagId;
                    tagId = tagId.toLowerCase(Locale.ROOT);
                    Set<String> members = tagSnapshot.get(tagId);
                    if (members != null && !members.isEmpty()) {
                        raw.addAll(members);
                        continue;
                    }
                    // Snapshot empty / tag missing: preserve original token so
                    // a later refresh can resolve it once the snapshot is populated.
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
            // Anvil-backed view: AnvilChunkView#isAir(x,y,z, reconciledAir)
            // mirrors BukkitRTPChunk's anvil path - consults the reconciled
            // air set in addition to the vanilla AIR/CAVE_AIR/VOID_AIR check.
            return anvilView.isAir(x & 0xF, y, z & 0xF, airSet);
        }
        if (chunk == null) return true;
        try {
            BlockState state = chunk.getBlockState(new BlockPos(
                    (cx << 4) + (x & 0xF), y, (cz << 4) + (z & 0xF)));
            if (state.isAir()) return true;
            if (airSet.isEmpty()) return false;
            Block block = state.getBlock();
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) return false;
            return PaletteNormalizer.matches(id.toString(), airSet);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * ADR-016 section 13.1 post-load biome read, chunk-local. Anvil-backed: consult
     * the decoded {@link AnvilChunkView#getBiomeAt(int, int, int)} (chunk-local
     * x/z, absolute Y). Live-backed: delegate to the parent's world-level
     * getter (the live block-biome accessor is too version-volatile across
     * 1.20→1.21 to invoke directly here; the world getter goes through the
     * version adapter).
     */
    @Override
    public String getBiome(int x, int y, int z) {
        if (anvilView != null) {
            String id = anvilView.getBiomeAt(x & 0xF, y, z & 0xF);
            if (id != null) {
                String normalized = PaletteIdentifierNormalizer.normalize(id);
                return (normalized != null && !normalized.isEmpty()) ? normalized : id;
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
        if (level == null) return 0;
        try {
            return level.getBrightness(LightLayer.SKY, new BlockPos(
                    (cx << 4) + (x & 0xF), y, (cz << 4) + (z & 0xF)));
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public int getSurfaceHeight(int x, int z) {
        x = Math.max(0, Math.min(15, x));
        z = Math.max(0, Math.min(15, z));
        if (anvilView != null) {
            return anvilView.getSurfaceHeight(x, z);
        }
        if (chunk == null) return 0;
        try {
            // Mojang's MOTION_BLOCKING_NO_LEAVES maps directly to Bukkit's
            // HeightMap.MOTION_BLOCKING_NO_LEAVES - the same height map used
            // by BukkitRTPChunk#getSurfaceHeight.
            return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
        if (anvilView != null) {
            // Prefer the pre-reconciled set we were constructed with; if absent,
            // reconcile per-call to stay in sync with the raw config form
            // (back-compat for ad-hoc callers that construct an Anvil-backed
            // chunk without supplying a reconciled set).
            Set<String> set = (reconciledUnsafe != null)
                    ? reconciledUnsafe
                    : PaletteNormalizer.reconcileAll(unsafeBlocks);
            return anvilView.isSafe(x & 0xF, y, z & 0xF, set);
        }
        if (unsafeBlocks == null || unsafeBlocks.isEmpty()) return true;
        String name = materialNameAt(x, y, z);
        if (name.isEmpty()) return true;
        // Match either the bare path (e.g. "STONE") or the full id ("MINECRAFT:STONE").
        if (unsafeBlocks.contains(name)) return false;
        int colon = name.indexOf(':');
        if (colon > 0 && unsafeBlocks.contains(name.substring(colon + 1))) return false;
        return true;
    }

    /**
     * Compiled-form safety check (ADR-017). Plain-material bucket only on
     * Fabric for now - see class-level note. Hot-path fast exits mirror the
     * Bukkit implementation: empty compiled set short-circuits to safe with
     * zero allocations. Anvil-backed instances also delegate to the plain
     * bucket via {@link #isSafe(int, int, int, Set)}
     * (state predicates against Anvil palette data are not yet supported).
     */
    @Override
    public boolean isSafe(int x, int y, int z, CompiledUnsafeSet unsafeBlocks) {
        if (unsafeBlocks == null || unsafeBlocks.isEmpty()) return true;
        return isSafe(x, y, z, unsafeBlocks.plainMaterials());
    }

    /**
     * Fabric-internal accessor for the underlying {@link ServerLevel}; useful
     * for adapters that want to do follow-up queries against world state
     * without re-resolving via {@code RTP.serverAccessor}. Not part of the
     * platform-neutral API. Returns {@code null} for Anvil-backed instances.
     */
    public @Nullable ServerLevel level() {
        return level;
    }
}
