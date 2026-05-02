package io.github.dailystruggle.rtp.fabric.world;

import io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Fabric-side {@link RTPChunk} implementation. Live-chunk only — no Anvil-backed
 * variant on Fabric (per ADR-016 §13.2 the on-disk Anvil pre-filter is Bukkit-family
 * only). Wraps a {@link ChunkAccess} (typically a {@code LevelChunk} produced by
 * {@code ServerChunkCache#getChunk(...)} at {@code ChunkStatus.FULL}).
 *
 * <p><b>Architectural invariants (ADR-022 §4):</b></p>
 * <ul>
 *   <li>No {@code org.bukkit.*} imports.</li>
 *   <li>All block-data queries go through Mojang mappings on the resident
 *       {@link ChunkAccess} — they do not trigger chunk loads (S-005). Queries
 *       on a chunk that has since been unloaded are answered from whatever
 *       state the chunk object still carries; the stale-chunk guard
 *       (ADR-015 / {@code FabricRTPWorld#isChunkLoaded}) is the caller's
 *       responsibility.</li>
 *   <li>Material identifiers are upper-cased {@code namespace:path} strings
 *       (e.g. {@code MINECRAFT:STONE}) to match the canonical form
 *       {@code SafetyKeys.unsafeBlocks} is reconciled into by
 *       {@code rtp-core} before being passed to {@link #isSafe}.</li>
 * </ul>
 *
 * <p><b>State predicates / tags.</b> The {@link CompiledUnsafeSet} overload of
 * {@link #isSafe} currently delegates to the plain-material bucket only; full
 * state-predicate parity with the Bukkit live path requires extracting
 * {@code BlockState} property → string maps for Mojang property keys, which
 * is straightforward but deferred to a follow-up to keep this slice focused
 * on the cached-safety unblock. The teleport pipeline still re-checks the
 * landing block at commit time, so no unsafe placement can leak through.</p>
 */
public final class FabricRTPChunk extends RTPChunk<ChunkAccess> {

    private final ServerLevel level;
    private final UUID worldId;
    private final int cx;
    private final int cz;

    public FabricRTPChunk(ChunkAccess chunk, ServerLevel level, UUID worldId) {
        super(chunk);
        this.level = level;
        this.worldId = worldId;
        ChunkPos pos = chunk.getPos();
        this.cx = pos.x;
        this.cz = pos.z;
    }

    @Override
    public int x() {
        return cx;
    }

    @Override
    public int z() {
        return cz;
    }

    @Override
    public RTPWorld<?> getWorld() {
        if (RTP.serverAccessor == null) return null;
        return RTP.serverAccessor.getRTPWorld(worldId);
    }

    @Override
    public boolean isGenerated() {
        // A constructed FabricRTPChunk wraps a ChunkAccess obtained at
        // ChunkStatus.FULL — by definition generated. Defensive on null.
        return chunk != null;
    }

    @Override
    public boolean isLoaded() {
        // Cheap, non-loading lookup; matches the FabricRTPWorld#isChunkLoaded
        // path used by the ADR-015 stale-chunk guard.
        try {
            return level != null && level.getChunkSource().hasChunk(cx, cz);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void keep(boolean keep) {
        // Route through the world's ref-counted ticket map so multi-caller
        // hand-offs are safe; FabricRTPWorld.setForceLoadedImpl performs the
        // server-thread hop.
        RTPWorld<?> w = getWorld();
        if (w instanceof FabricRTPWorld fw) {
            if (keep) {
                fw.keepChunkAt(cx, cz);
            } else {
                fw.forgetChunkAt(cx, cz);
            }
        }
    }

    @Override
    public void unload() {
        // Vanilla Fabric has no analogue to Bukkit's Chunk#unload(false) —
        // chunk eviction is driven by the ticket system. Releasing our keep()
        // ticket is the closest approximation; if no caller is keeping this
        // chunk, the chunk system will evict it on its own schedule.
        keep(false);
    }

    // ---------------------------------------------------------------------------
    // Block-data queries
    // ---------------------------------------------------------------------------

    /**
     * Returns the upper-cased {@code namespace:path} block id for the block at
     * the given chunk-local coords (Y is absolute world Y). Returns {@code ""}
     * on registry lookup failure.
     */
    private String materialNameAt(int x, int y, int z) {
        if (chunk == null) return "";
        try {
            BlockState state = chunk.getBlockState(new BlockPos(
                    (cx << 4) + (x & 0xF), y, (cz << 4) + (z & 0xF)));
            Block block = state.getBlock();
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
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
        if (chunk == null) return 0;
        x = Math.max(0, Math.min(15, x));
        z = Math.max(0, Math.min(15, z));
        try {
            // Mojang's MOTION_BLOCKING_NO_LEAVES maps directly to Bukkit's
            // HeightMap.MOTION_BLOCKING_NO_LEAVES — the same height map used
            // by BukkitRTPChunk#getSurfaceHeight.
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
        // Match either the bare path (e.g. "STONE") or the full id ("MINECRAFT:STONE").
        if (unsafeBlocks.contains(name)) return false;
        int colon = name.indexOf(':');
        if (colon > 0 && unsafeBlocks.contains(name.substring(colon + 1))) return false;
        return true;
    }

    /**
     * Compiled-form safety check (ADR-017). Plain-material bucket only on
     * Fabric for now — see class-level note. Hot-path fast exits mirror the
     * Bukkit implementation: empty compiled set short-circuits to safe with
     * zero allocations.
     */
    @Override
    public boolean isSafe(int x, int y, int z, CompiledUnsafeSet unsafeBlocks) {
        if (unsafeBlocks == null || unsafeBlocks.isEmpty()) return true;
        return isSafe(x, y, z, unsafeBlocks.plainMaterials());
    }

    /**
     * Fabric-internal accessor for the underlying {@link ServerLevel}; useful
     * for adapters that want to do follow-up queries against world state
     * without re-resolving via {@code RTP.serverAccessor}. Not part of the
     * platform-neutral API.
     */
    public @Nullable ServerLevel level() {
        return level;
    }
}
