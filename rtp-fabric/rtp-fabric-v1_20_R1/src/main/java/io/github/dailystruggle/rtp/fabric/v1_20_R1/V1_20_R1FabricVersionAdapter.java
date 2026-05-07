package io.github.dailystruggle.rtp.fabric.v1_20_R1;

import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.RTPBlockHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPBlockStateHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPChunkHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPRegistryKey;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * MC 1.20.1 implementation of {@link FabricVersionAdapter}.
 *
 * <p><b>Status: stub (rtp-fabric-ADR-001).</b> The reference adapter implementation
 * lives in {@code rtp-fabric-v1_21_R1}; bodies for 1.20.1 are deferred
 * to a follow-up Phase 2.5 task once the 1.21.1 path is exercised
 * end-to-end on a real server. Methods throw {@link UnsupportedOperationException}
 * with a {@code TODO(rtp-fabric-ADR-001)} marker so they fail loud per S-006 if the
 * runtime selector accidentally routes 1.20.x traffic to this adapter
 * before porting completes.</p>
 *
 * <p>SPI shape per rtp-fabric-ADR-007 (Mojmap-name decoupling): all MC types
 * cross the seam wrapped in {@code RTPxxxHandle} records. Adapters cast
 * via {@code handle.as(MojmapType.class)} on entry; this stub doesn't yet.</p>
 */
public final class V1_20_R1FabricVersionAdapter implements FabricVersionAdapter {

    @Override
    public String mcVersion() {
        return "1.20.1";
    }

    @Override
    public @Nullable RTPRegistryKey blockKey(RTPBlockHandle block) {
        // TODO(rtp-fabric-ADR-001): port from V1_21_R1; cast via
        // block.as(net.minecraft.world.level.block.Block.class), look up via
        // BuiltInRegistries.BLOCK.getKey(b), then return new RTPRegistryKey(rl.getNamespace(), rl.getPath()).
        throw new UnsupportedOperationException("v1_20_R1 adapter not yet implemented (rtp-fabric-ADR-001)");
    }

    @Override
    public @Nullable RTPRegistryKey biomeKeyAt(RTPLevelHandle level, int x, int y, int z) {
        // TODO(rtp-fabric-ADR-001): cast level.as(ServerLevel.class).getBiome(new BlockPos(x, y, z))
        // → Holder<Biome> → unwrapKey() → RTPRegistryKey.
        throw new UnsupportedOperationException("v1_20_R1 adapter not yet implemented (rtp-fabric-ADR-001)");
    }

    @Override
    public CompletableFuture<RTPChunkHandle> getChunkFull(RTPLevelHandle level, int cx, int cz) {
        // TODO(rtp-fabric-ADR-001): level.as(ServerLevel.class).getChunkSource()
        // .getChunk(cx, cz, ChunkStatus.FULL, true) → RTPChunkHandle.of(...).
        // ChunkStatus is at net.minecraft.world.level.chunk.ChunkStatus on 1.20.1
        // (the package move to .chunk.status is in 1.21.3).
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("v1_20_R1 adapter not yet implemented (rtp-fabric-ADR-001)"));
    }

    @Override
    public boolean hasChunk(RTPLevelHandle level, int cx, int cz) {
        // TODO(rtp-fabric-ADR-001): level.as(ServerLevel.class).getChunkSource().hasChunk(cx, cz).
        throw new UnsupportedOperationException("v1_20_R1 adapter not yet implemented (rtp-fabric-ADR-001)");
    }

    @Override
    public RTPBlockStateHandle airState() {
        // TODO(rtp-fabric-ADR-001): RTPBlockStateHandle.of(Blocks.AIR.defaultBlockState()).
        throw new UnsupportedOperationException("v1_20_R1 adapter not yet implemented (rtp-fabric-ADR-001)");
    }
}
