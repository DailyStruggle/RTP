package io.github.dailystruggle.rtp.fabric.v1_21_R1;

import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * MC 1.21.1 implementation of {@link FabricVersionAdapter} — the reference
 * implementation per ADR-027.
 *
 * <p>v1_20_R1 and v26_1_R1 will port from this class. Notable per-version
 * concerns this implementation captures:</p>
 * <ul>
 *   <li>{@link ChunkStatus} is at {@code net.minecraft.world.level.chunk.ChunkStatus}
 *       on 1.21.1; the package move to {@code .chunk.status} happens in 1.21.3.</li>
 *   <li>Biome registry access uses {@link BuiltInRegistries#BIOME} via the
 *       {@link Holder} produced by {@code level.getBiome(pos)}.</li>
 *   <li>Block-id lookup goes through {@link BuiltInRegistries#BLOCK}; the
 *       {@code Registries} vs. {@code BuiltInRegistries} split is stable on
 *       1.21.x.</li>
 * </ul>
 */
public final class V1_21_R1FabricVersionAdapter implements FabricVersionAdapter {

    @Override
    public String mcVersion() {
        return "1.21.1";
    }

    @Override
    public @Nullable ResourceLocation blockKey(Block block) {
        if (block == null) return null;
        return BuiltInRegistries.BLOCK.getKey(block);
    }

    @Override
    public @Nullable ResourceLocation biomeKeyAt(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return null;
        try {
            Holder<Biome> holder = level.getBiome(pos);
            return holder.unwrapKey().map(ResourceKey::location).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public CompletableFuture<ChunkAccess> getChunkFull(ServerLevel level, int cx, int cz) {
        if (level == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("null ServerLevel"));
        }
        try {
            ChunkAccess chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true);
            return CompletableFuture.completedFuture(chunk);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    @Override
    public boolean hasChunk(ServerLevel level, int cx, int cz) {
        if (level == null) return false;
        try {
            return level.getChunkSource().hasChunk(cx, cz);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public BlockState airState() {
        return Blocks.AIR.defaultBlockState();
    }
}
