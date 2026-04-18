package io.github.dailystruggle.rtp.fabric.world;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class FabricWorld extends RTPWorld<ServerWorld> {
    public FabricWorld(ServerWorld world) {
        super(world);
    }

    @Override
    public String name() {
        return world().getRegistryKey().getValue().toString();
    }

    @Override
    public UUID id() {
        // Fabric doesn't have a direct UUID for worlds like Bukkit, using registry key hash for now
        // or we can use the level property if available.
        return UUID.nameUUIDFromBytes(name().getBytes());
    }

    @Override
    public CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ) {
        return world().getChunkManager().getChunkFutureSyncOnMainThread(chunkX, chunkZ, net.minecraft.world.chunk.ChunkStatus.FULL, true)
                .thenApply(either -> {
                    if (either.isLeft()) return ChunkPos.toLong(chunkX, chunkZ);
                    return 0L;
                });
    }

    @Override
    public void setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
        world().setChunkForced(cx, cz, forceLoad);
    }

    @Override
    public String getBiome(int x, int y, int z) {
        return world().getBiome(new BlockPos(x, y, z)).getKey().map(key -> key.getValue().toString()).orElse("unknown");
    }

    @Override
    public int getMaxHeight() {
        return world().getTopY();
    }

    @Override
    public int getMinHeight() {
        return world().getBottomY();
    }

    @Override
    public long getSeed() {
        return world().getSeed();
    }
}
