package io.github.dailystruggle.rtp.fabric.v1_20_R1;

import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * MC 1.20.1 implementation of {@link FabricVersionAdapter}.
 *
 * <p><b>Status: stub (ADR-027).</b> The reference adapter implementation
 * lives in {@code rtp-fabric-v1_21_R1}; bodies for 1.20.1 are deferred
 * to a follow-up Phase 2.5 task once the 1.21.1 path is exercised
 * end-to-end on a real server. Methods throw {@link UnsupportedOperationException}
 * with a {@code TODO(ADR-027)} marker so they fail loud per S-006 if the
 * runtime selector accidentally routes 1.20.x traffic to this adapter
 * before porting completes.</p>
 */
public final class V1_20_R1FabricVersionAdapter implements FabricVersionAdapter {

    @Override
    public String mcVersion() {
        return "1.20.1";
    }

    @Override
    public @Nullable ResourceLocation blockKey(Block block) {
        // TODO(ADR-027): port from common's BuiltInRegistries.BLOCK.getKey(block).
        // 1.20.1 has both BuiltInRegistries.BLOCK and Registries.BLOCK; verify
        // which is the canonical accessor in this version's mojmap before
        // committing the body.
        throw new UnsupportedOperationException("v1_20_R1 adapter not yet implemented (ADR-027)");
    }

    @Override
    public @Nullable ResourceLocation biomeKeyAt(ServerLevel level, BlockPos pos) {
        // TODO(ADR-027): level.getBiome(pos) -> Holder<Biome> -> unwrapKey().
        // 1.20.1 still uses Holder<Biome>; the resource-key access pattern is
        // stable from 1.20 onward.
        throw new UnsupportedOperationException("v1_20_R1 adapter not yet implemented (ADR-027)");
    }

    @Override
    public CompletableFuture<ChunkAccess> getChunkFull(ServerLevel level, int cx, int cz) {
        // TODO(ADR-027): level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, true).
        // ChunkStatus is at net.minecraft.world.level.chunk.ChunkStatus on 1.20.1
        // (the package move to .chunk.status is in 1.21.3).
        throw new UnsupportedOperationException("v1_20_R1 adapter not yet implemented (ADR-027)");
    }

    @Override
    public boolean hasChunk(ServerLevel level, int cx, int cz) {
        // TODO(ADR-027): level.getChunkSource().hasChunk(cx, cz).
        throw new UnsupportedOperationException("v1_20_R1 adapter not yet implemented (ADR-027)");
    }

    @Override
    public BlockState airState() {
        // TODO(ADR-027): Blocks.AIR.defaultBlockState().
        throw new UnsupportedOperationException("v1_20_R1 adapter not yet implemented (ADR-027)");
    }
}
