package io.github.dailystruggle.rtp.fabric.v26_1_R1;

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
 * MC 26.1.2 implementation of {@link FabricVersionAdapter}.
 *
 * <p><b>Status: stub (ADR-027).</b> Bodies are deferred to a follow-up
 * Phase 2.5 task. The 26.1 porting diff against the v1_21_R1 reference is
 * non-trivial and benefits from being done in one focused pass once a JDK 25
 * + Loom 1.15 build environment is verified locally:</p>
 *
 * <ul>
 *   <li>{@code ChunkStatus} moved from {@code net.minecraft.world.level.chunk}
 *       to {@code net.minecraft.world.level.chunk.status} starting in 1.21.3
 *       and remains there in 26.1.</li>
 *   <li>26.1 is the first deobfuscated MC release; some class/method names
 *       shifted as part of the deobfuscation pass. The exact rename diff
 *       affecting the SPI surface (registry access, biome holder, chunk
 *       source, Blocks accessor) needs to be catalogued at port time.</li>
 *   <li>{@code BuiltInRegistries} vs. {@code Registries} access patterns
 *       may have consolidated; verify before committing the body.</li>
 * </ul>
 *
 * <p>Methods throw {@link UnsupportedOperationException} with a
 * {@code TODO(ADR-027)} marker — fail-loud per S-006.</p>
 */
public final class V26_1_R1FabricVersionAdapter implements FabricVersionAdapter {

    @Override
    public String mcVersion() {
        return "26.1.2";
    }

    @Override
    public @Nullable ResourceLocation blockKey(Block block) {
        // TODO(ADR-027): 26.1 porting — likely BuiltInRegistries.BLOCK.getKey(block)
        // unchanged from 1.21.x, but verify under deobf naming.
        throw new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (ADR-027)");
    }

    @Override
    public @Nullable ResourceLocation biomeKeyAt(ServerLevel level, BlockPos pos) {
        // TODO(ADR-027): port from V1_21_R1FabricVersionAdapter; Holder<Biome>
        // pattern is expected to survive deobfuscation but verify.
        throw new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (ADR-027)");
    }

    @Override
    public CompletableFuture<ChunkAccess> getChunkFull(ServerLevel level, int cx, int cz) {
        // TODO(ADR-027): import ChunkStatus from
        // net.minecraft.world.level.chunk.status.ChunkStatus (post-1.21.3 path).
        throw new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (ADR-027)");
    }

    @Override
    public boolean hasChunk(ServerLevel level, int cx, int cz) {
        // TODO(ADR-027): port from V1_21_R1FabricVersionAdapter.
        throw new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (ADR-027)");
    }

    @Override
    public BlockState airState() {
        // TODO(ADR-027): Blocks.AIR.defaultBlockState() — verify Blocks class
        // has not been re-keyed under deobfuscation.
        throw new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (ADR-027)");
    }
}
