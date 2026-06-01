package io.github.dailystruggle.rtp.fabric.v26_1_R1;

import io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * MC 26.1.2 RTPChunk wrapper (live mode only).
 *
 * <p>Lives in the v26_1_R1 module so its compiled bytecode references Mojang
 * names (e.g. {@code net.minecraft.world.level.chunk.ChunkAccess}) rather than
 * Loom-remapped intermediary aliases that don't exist on a deobfuscated 26.1.2
 * runtime. This is the live-chunk subset of common's {@code FabricRTPChunk};
 * the anvil-mode constructor is omitted because v26 anvil-prefilter integration
 * is a follow-up phase (see bring-up checklist).
 */
public final class V26_1_R1FabricRTPChunk extends RTPChunk<ChunkAccess> {

    private final ServerLevel level;
    private final UUID worldId;
    private final int cx;
    private final int cz;

    public V26_1_R1FabricRTPChunk(ChunkAccess chunk, ServerLevel level, UUID worldId) {
        super(chunk);
        this.level = level;
        this.worldId = worldId;
        ChunkPos pos = chunk.getPos();
        // ChunkPos is a record on MC 26.1.2; record component accessors are x()/z().
        this.cx = pos.x();
        this.cz = pos.z();
    }

    @Override public int x() { return cx; }
    @Override public int z() { return cz; }

    @Override
    public RTPWorld<?> getWorld() {
        if (RTP.serverAccessor == null) return null;
        return RTP.serverAccessor.getRTPWorld(worldId);
    }

    @Override
    public boolean isGenerated() {
        return chunk != null;
    }

    @Override
    public boolean isLoaded() {
        try {
            return level != null && level.getChunkSource().hasChunk(cx, cz);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void keep(boolean keep) {
        // Route via the world wrapper's ref-counted ticket map.
        RTPWorld<?> w = getWorld();
        if (w instanceof V26_1_R1FabricRTPWorld fw) {
            if (keep) fw.keepChunkAt(cx, cz);
            else fw.forgetChunkAt(cx, cz);
        }
    }

    @Override
    public void unload() {
        // No analogue to Bukkit's Chunk#unload(false) on Fabric — eviction is
        // ticket-driven. Releasing our keep-ticket is the closest approximation.
        keep(false);
    }

    private String materialNameAt(int x, int y, int z) {
        if (chunk == null) return "";
        try {
            BlockState state = chunk.getBlockState(new BlockPos(
                    (cx << 4) + (x & 0xF), y, (cz << 4) + (z & 0xF)));
            Block block = state.getBlock();
            // MC 26.1.2: ResourceLocation was renamed to Identifier.
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            return (id == null) ? "" : id.toString().toUpperCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        if (chunk == null) return true;
        try {
            BlockState state = chunk.getBlockState(new BlockPos(
                    (cx << 4) + (x & 0xF), y, (cz << 4) + (z & 0xF)));
            return state.isAir();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
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
        if (chunk == null) return 0;
        try {
            return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
        if (unsafeBlocks == null || unsafeBlocks.isEmpty()) return true;
        String name = materialNameAt(x, y, z);
        if (name.isEmpty()) return true;
        if (unsafeBlocks.contains(name)) return false;
        int colon = name.indexOf(':');
        if (colon > 0 && unsafeBlocks.contains(name.substring(colon + 1))) return false;
        return true;
    }

    @Override
    public boolean isSafe(int x, int y, int z, CompiledUnsafeSet unsafeBlocks) {
        if (unsafeBlocks == null || unsafeBlocks.isEmpty()) return true;
        return isSafe(x, y, z, unsafeBlocks.plainMaterials());
    }

    public ServerLevel level() { return level; }
}
