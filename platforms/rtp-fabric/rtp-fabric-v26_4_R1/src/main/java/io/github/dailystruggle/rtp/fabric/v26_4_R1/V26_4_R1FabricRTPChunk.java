package io.github.dailystruggle.rtp.fabric.v26_4_R1;

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
import java.util.UUID;

/**
 * MC 26.4 RTPChunk wrapper (live mode only).
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

    public V26_4_R1FabricRTPChunk(ChunkAccess chunk, ServerLevel level, UUID worldId) {
        super(chunk);
        this.level = level;
        this.worldId = worldId;
        ChunkPos pos = chunk.getPos();
        // ChunkPos is a record on MC 26.x; record component accessors are x()/z().
        this.cx = pos.x();
        this.cz = pos.z();
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
        try {
            return level != null && level.getChunkSource().hasChunk(cx, cz);
        } catch (Throwable _) {
            return false;
        }
    }

    @Override
    public void keep(boolean keep) {
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

    public ServerLevel level() { return level; }
}
