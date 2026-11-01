package io.github.dailystruggle.rtp.fabric.v26_4_R1;

import io.github.dailystruggle.anvil.AnvilChunkView;
import io.github.dailystruggle.anvil.PaletteIdentifierNormalizer;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * MC 26.4 RTPChunk wrapper (dual-mode: live {@link ChunkAccess} and ADR-016 {@link AnvilChunkView}).
 *
 * <p>Lives in the V26_4_R1 module so its compiled bytecode references Mojang
 * names (e.g. {@code net.minecraft.world.level.chunk.ChunkAccess}) rather than
 * Loom-remapped intermediary aliases that don't exist on a deobfuscated 26.4
 * runtime.
 */
public final class V26_4_R1FabricRTPChunk extends RTPChunk<ChunkAccess> {

    private final ServerLevel level;
    private final UUID worldId;
    private final int cx;
    private final int cz;
    private final AnvilChunkView anvilView;
    private final Set<String> reconciledUnsafe;

    public V26_4_R1FabricRTPChunk(ChunkAccess chunk, ServerLevel level, UUID worldId) {
        super(chunk);
        this.level = level;
        this.worldId = worldId;
        ChunkPos pos = chunk.getPos();
        // ChunkPos is a record on MC 26.x; record component accessors are x()/z().
        this.cx = pos.x();
        this.cz = pos.z();
        this.anvilView = null;
        this.reconciledUnsafe = null;
    }

    public V26_4_R1FabricRTPChunk(
            AnvilChunkView view, int cx, int cz, UUID worldId, Set<String> reconciledUnsafe) {
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

    public boolean isAnvilBacked() {
        return anvilView != null;
    }

    @Override
    public boolean isSelfContained() {
        return anvilView != null;
    }

    @Override public int x() { return cx; }
    @Override public int z() { return cz; }

    @Override
    public RTPWorld<?> getWorld() {
        if (RTPRuntime.serverAccessor() == null) return null;
        return RTPRuntime.serverAccessor().getRTPWorld(worldId);
    }

    @Override
    public boolean isLoaded() {
        if (anvilView != null) return false;
        try {
            return level != null && level.getChunkSource().hasChunk(cx, cz);
        } catch (Throwable _) {
            return false;
        }
    }

    @Override
    public void keep(boolean keep) {
        if (anvilView != null) return;
        // Route via the world wrapper's ref-counted ticket map.
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
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            return id.toString().toUpperCase(Locale.ROOT);
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

    public ServerLevel level() { return level; }
}
