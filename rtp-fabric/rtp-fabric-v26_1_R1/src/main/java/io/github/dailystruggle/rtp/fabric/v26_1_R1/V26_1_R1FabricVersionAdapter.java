package io.github.dailystruggle.rtp.fabric.v26_1_R1;

import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter;
import io.github.dailystruggle.rtp.fabric.version.RTPBlockHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPBlockStateHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPChunkHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle;
import io.github.dailystruggle.rtp.fabric.version.RTPRegistryKey;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * MC 26.1.2 implementation of {@link FabricVersionAdapter}.
 *
 * <p><b>Status: stub (rtp-fabric-ADR-001).</b> Bodies are deferred to a follow-up
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
 *       source, Blocks accessor) needs to be catalogued at port time. The
 *       SPI surface itself is now Mojmap-name-stable per
 *       {@code rtp-fabric-ADR-007}; only adapter-internal types are
 *       affected by the deobf pass.</li>
 * </ul>
 *
 * <p>Methods throw {@link UnsupportedOperationException} with a
 * {@code TODO(rtp-fabric-ADR-001)} marker — fail-loud per S-006.</p>
 */
public final class V26_1_R1FabricVersionAdapter implements FabricVersionAdapter {

    @Override
    public String mcVersion() {
        return "26.1.2";
    }

    @Override
    public @Nullable RTPRegistryKey blockKey(RTPBlockHandle block) {
        // TODO(rtp-fabric-ADR-001): cast block.as(Block.class), then
        // BuiltInRegistries.BLOCK.getKey(b) → RTPRegistryKey.
        throw new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (rtp-fabric-ADR-001)");
    }

    @Override
    public @Nullable RTPRegistryKey biomeKeyAt(RTPLevelHandle level, int x, int y, int z) {
        // TODO(rtp-fabric-ADR-001): port from V1_21_R1FabricVersionAdapter; Holder<Biome>
        // pattern is expected to survive deobfuscation but verify.
        throw new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (rtp-fabric-ADR-001)");
    }

    @Override
    public CompletableFuture<RTPChunkHandle> getChunkFull(RTPLevelHandle level, int cx, int cz) {
        // TODO(rtp-fabric-ADR-001): import ChunkStatus from
        // net.minecraft.world.level.chunk.status.ChunkStatus (post-1.21.3 path).
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (rtp-fabric-ADR-001)"));
    }

    @Override
    public boolean hasChunk(RTPLevelHandle level, int cx, int cz) {
        // TODO(rtp-fabric-ADR-001): port from V1_21_R1FabricVersionAdapter.
        throw new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (rtp-fabric-ADR-001)");
    }

    @Override
    public RTPBlockStateHandle airState() {
        // TODO(rtp-fabric-ADR-001): RTPBlockStateHandle.of(Blocks.AIR.defaultBlockState()).
        throw new UnsupportedOperationException("v26_1_R1 adapter not yet implemented (rtp-fabric-ADR-001)");
    }

    @Override
    public void installEffectsDispatchers() {
        // Independent of the unimplemented stubs above — see V26_1_R1FabricEffectDispatchers.
        V26_1_R1FabricEffectDispatchers.install();
    }
}
