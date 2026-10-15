package io.github.dailystruggle.rtp.fabric.world;

import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.anvil.PaletteIdentifierNormalizer;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

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
     * {@code RTPServerAccessor#reconcilePaletteIdentifiers}. {@code null} when the chunk
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
    public boolean isLoaded() {
        if (anvilView != null) return false;
        try {
            return level != null && level.getChunkSource().hasChunk(cx, cz);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void keep(boolean keep) {
        if (anvilView != null) return;
        RTPWorld<?> w = getWorld();
        if (w instanceof FabricRTPWorld fw) {
            if (keep) fw.keepChunkAt(cx, cz);
            else fw.forgetChunkAt(cx, cz);
        }
    }

    @Override
    public void unload() {
        keep(false);
    }

    @Override
    public String materialNameAt(int x, int y, int z) {
        if (anvilView != null) {
            String id = anvilView.blockIdAt(x & 0xF, y, z & 0xF);
            return (id != null) ? id.toUpperCase(Locale.ROOT) : "AIR";
        }
        if (chunk == null) return "";
        try {
            BlockState state = chunk.getBlockState(new BlockPos(
                    (cx << 4) + (x & 0xF), y, (cz << 4) + (z & 0xF)));
            Block block = state.getBlock();
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            return id.toString().toUpperCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
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
