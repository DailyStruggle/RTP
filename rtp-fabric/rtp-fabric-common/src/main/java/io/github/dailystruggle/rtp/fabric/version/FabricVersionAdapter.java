package io.github.dailystruggle.rtp.fabric.version;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Per-MC-version SPI for the Fabric platform — see ADR-027.
 *
 * <p>Each {@code rtp-fabric-vXX_YY_R1} submodule supplies exactly one
 * implementation of this interface, living under
 * {@code io.github.dailystruggle.rtp.fabric.vXX_YY_R1}. The Fabric bootstrap
 * (`RTPFabricMod`) reflectively resolves the implementation at server-start
 * based on the running MC version returned by
 * {@code SharedConstants.getCurrentVersion().getName()}.</p>
 *
 * <p><b>Scope:</b> only the genuinely version-volatile call sites identified
 * during the symbol-surface inventory belong here. Mojmap-stable types
 * ({@link ServerLevel}, {@link BlockPos}, {@code MinecraftServer},
 * {@code ServerPlayer}, {@code Component}, etc.) are referenced directly from
 * common code without going through this SPI.</p>
 *
 * <p><b>Threading:</b> implementations of this SPI are pure functions — they
 * shall not load chunks (S-005), block on tick threads, or hold locks. Calls
 * that need server-thread affinity must be dispatched by the caller (typically
 * via {@code MinecraftServer#submit}).</p>
 */
public interface FabricVersionAdapter {

    /**
     * Returns a short identifier for the MC version this adapter targets,
     * e.g. {@code "1.20.1"}, {@code "1.21.1"}, {@code "26.1.2"}. Used for
     * logging only.
     */
    String mcVersion();

    // -------------------------------------------------------------------------
    // Registry access — `BuiltInRegistries` field names and the
    // `Registries` vs. `BuiltInRegistries` split shifted across 1.20 → 26.1.
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link ResourceLocation} key registered for the given
     * {@link Block}, or {@code null} if not registered.
     */
    @Nullable ResourceLocation blockKey(Block block);

    /**
     * Returns the {@link ResourceLocation} key for the biome at the given
     * {@link BlockPos} in the given {@link ServerLevel}, or {@code null} if
     * the biome holder cannot be resolved.
     */
    @Nullable ResourceLocation biomeKeyAt(ServerLevel level, BlockPos pos);

    // -------------------------------------------------------------------------
    // Chunk access — `ServerChunkCache#getChunk` argument semantics drift
    // across versions; this normalises the "fully generated, will load if
    // missing" call.
    // -------------------------------------------------------------------------

    /**
     * Loads (synchronously, on the server thread) the chunk at {@code (cx, cz)}
     * at full status, generating if absent. Callers are responsible for
     * dispatching to the server thread before calling this method.
     *
     * <p>Returns a future for forward-compatibility — current implementations
     * complete it inline.</p>
     */
    CompletableFuture<ChunkAccess> getChunkFull(ServerLevel level, int cx, int cz);

    /**
     * Cheap, non-loading existence check. Mirrors
     * {@code ServerLevel#getChunkSource().hasChunk(cx, cz)} but lets
     * v-submodules absorb any rename of {@code hasChunk}.
     */
    boolean hasChunk(ServerLevel level, int cx, int cz);

    // -------------------------------------------------------------------------
    // Misc convenience — small stable shims that nonetheless protect callers
    // from per-version method renames.
    // -------------------------------------------------------------------------

    /**
     * Returns the air state for {@code level}'s registry. Used as a sentinel
     * by safety predicates that need to compare to "vanilla AIR" without
     * pulling in {@code Blocks.AIR} directly (the {@code Blocks} class has
     * been re-keyed across versions).
     */
    BlockState airState();
}
