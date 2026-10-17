package io.github.dailystruggle.rtp.neoforge.world;

import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import io.github.dailystruggle.rtp.anvil.PaletteIdentifierNormalizer;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * NeoForge {@link RTPChunk} (the NeoForge analogue of {@code FabricRTPChunk}).
 * Dual-mode: a live {@link ChunkAccess} wrapper (callers performed an off-tick
 * load - S-005-safe) or an {@link AnvilChunkView} snapshot read from a
 * persisted {@code r.X.Z.mca} (ADR-016).
 *
 * <p>Mojmap-at-runtime: compiles directly against {@code net.minecraft} block
 * APIs with no obf/intermediary split. No {@code org.bukkit.*} imports; queries
 * do not trigger loads (S-005).</p>
 */
public final class NeoForgeRTPChunk extends RTPChunk<ChunkAccess> {

    /** Live mode: the underlying server level for biome / sky-light lookups. {@code null} in anvil mode. */
    private final @Nullable ServerLevel level;
    private final UUID worldId;
    private final int cx;
    private final int cz;

    /** Anvil mode only: the decoded chunk snapshot. {@code null} in live mode. */
    private final @Nullable AnvilChunkView anvilView;
    /** Anvil mode only: a pre-reconciled unsafe-block set. {@code null} forces per-call reconciliation. */
    private final @Nullable Set<String> reconciledUnsafe;

    /**
     * Live-chunk constructor. The caller passes the chunk coordinates
     * explicitly rather than having this constructor read them from
     * {@link ChunkAccess#getPos()}: {@code ChunkPos} has no coordinate accessor
     * shape that is portable across the MC lines this single module is loaded on
     * (1.21.1 exposes public fields {@code x}/{@code z} but no record accessors;
     * 26.1 is a record whose {@code x}/{@code z} components are private, exposed
     * only via the {@code x()}/{@code z()} accessors). Every caller already
     * knows the coordinates (they drive the load), so taking them as parameters
     * removes the reflection / cross-runtime fragility entirely.
     *
     * <p>Exposed as a static factory (backed by a 4-arg private constructor
     * that packs the coordinates into a long) rather than a second 5-arg
     * constructor: a 5-arg live constructor would collide in arity with the
     * anvil-backed 5-arg constructor, forcing javac to resolve against the
     * NeoMinecraft {@code ChunkAccess} / {@code ServerLevel} types even at
     * anvil-only call sites in tests, where those classes are absent from the
     * classpath.</p>
     */
    public static NeoForgeRTPChunk forLiveChunk(
            ChunkAccess chunk, ServerLevel level, UUID worldId, int cx, int cz) {
        // Pack the coordinates into a single long so the private constructor is
        // 4-arg. A 5-arg (ChunkAccess, ServerLevel, UUID, int, int) constructor
        // would collide in arity with the anvil-backed 5-arg constructor, and
        // javac would then have to resolve the NeoMinecraft ChunkAccess /
        // ServerLevel parameter types even for anvil-only call sites in tests,
        // where those classes are absent from the classpath ("cannot access
        // ChunkAccess"). x in the low 32 bits, z in the high 32 bits.
        long packed = ((long) cx & 0xffffffffL) | ((long) cz << 32);
        return new NeoForgeRTPChunk(chunk, level, worldId, packed);
    }

    private NeoForgeRTPChunk(ChunkAccess chunk, ServerLevel level, UUID worldId, long packedCoord) {
        super(chunk);
        this.level = level;
        this.worldId = worldId;
        this.cx = (int) (packedCoord & 0xffffffffL);
        this.cz = (int) (packedCoord >> 32);
        this.anvilView = null;
        this.reconciledUnsafe = null;
    }

    /** Anvil-backed constructor (ADR-016). */
    public NeoForgeRTPChunk(
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
        if (w == null) return;
        if (keep) w.keepChunkAt(cx, cz);
        else w.forgetChunkAt(cx, cz);
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
            String id = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds
                    .registryKeyString(BuiltInRegistries.BLOCK, block);
            return (id == null) ? "" : id.toUpperCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

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
     * NeoForge-internal accessor for the underlying {@link ServerLevel}; not
     * part of the platform-neutral API. Returns {@code null} for Anvil-backed
     * instances.
     */
    public @Nullable ServerLevel level() {
        return level;
    }
}
