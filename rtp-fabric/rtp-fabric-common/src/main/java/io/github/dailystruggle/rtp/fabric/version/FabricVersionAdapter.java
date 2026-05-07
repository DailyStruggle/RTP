package io.github.dailystruggle.rtp.fabric.version;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Per-MC-version SPI for the Fabric platform — see rtp-fabric-ADR-001 and
 * {@code rtp-fabric-ADR-007-mojmap-name-decoupling.md}.
 *
 * <p>Each {@code rtp-fabric-vXX_YY_RN} submodule supplies exactly one
 * implementation of this interface, living under
 * {@code io.github.dailystruggle.rtp.fabric.vXX_YY_RN}. The Fabric bootstrap
 * ({@code RTPFabricMod}) reflectively resolves the implementation at server
 * start based on the running MC version returned by
 * {@code SharedConstants.getCurrentVersion().getName()}.</p>
 *
 * <p><b>Mojmap-name decoupling (ADR-007).</b> The SPI no longer mentions any
 * {@code net.minecraft.*} type whose Mojmap name has been observed to drift
 * across point releases. All MC objects cross the seam wrapped in the
 * {@code RTPxxxHandle} records in this package; coordinates pass as
 * primitives. Per-version adapters cast the wrapped payload back to the
 * Mojmap type via {@code handle.as(MojmapType.class)} on method entry.</p>
 *
 * <p><b>Threading:</b> implementations of this SPI are pure functions —
 * they shall not load chunks (S-005), block on tick threads, or hold locks.
 * Calls that need server-thread affinity must be dispatched by the caller
 * (typically via {@code MinecraftServer#submit}).</p>
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
     * Returns the registry key registered for the given block, or
     * {@code null} if not registered. Common-side callers consume the
     * {@code namespace:path} form via {@link RTPRegistryKey#key()}.
     */
    @Nullable RTPRegistryKey blockKey(RTPBlockHandle block);

    /**
     * Returns the registry key for the biome at the given block coordinates
     * in the given level, or {@code null} if the biome holder cannot be
     * resolved. Coordinates pass as primitives so the SPI stays
     * Mojmap-name-stable even if {@code BlockPos} ever renames.
     */
    @Nullable RTPRegistryKey biomeKeyAt(RTPLevelHandle level, int x, int y, int z);

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
    CompletableFuture<RTPChunkHandle> getChunkFull(RTPLevelHandle level, int cx, int cz);

    /**
     * Cheap, non-loading existence check. Mirrors
     * {@code ServerLevel#getChunkSource().hasChunk(cx, cz)} but lets
     * v-submodules absorb any rename of {@code hasChunk}.
     */
    boolean hasChunk(RTPLevelHandle level, int cx, int cz);

    // -------------------------------------------------------------------------
    // Misc convenience — small stable shims that nonetheless protect callers
    // from per-version method renames.
    // -------------------------------------------------------------------------

    /**
     * Returns the air state for the runtime's registry. Used as a sentinel
     * by safety predicates that need to compare to "vanilla AIR" without
     * pulling in {@code Blocks.AIR} directly (the {@code Blocks} class has
     * been re-keyed across versions).
     */
    RTPBlockStateHandle airState();

    // -------------------------------------------------------------------------
    // Non-persistent chunk tickets — see rtp-fabric-ADR-003 / -004 / -006.
    //
    // Vanilla {@code setChunkForced(cx, cz, true)} writes through to
    // {@code level.dat#ForcedChunks}, which means a server crash mid-pipeline
    // (or any unclean shutdown) leaves RTP-owned forced chunks persisted to
    // disk. That is an S-002 hazard on Fabric specifically — Bukkit's
    // {@code addPluginChunkTicket} is non-persistent and Folia inherits the
    // Bukkit semantics.
    //
    // Threading: callers MUST dispatch to the server tick thread before
    // invoking these methods. The returned future completes when the native
    // call has executed; on resolution failure (reflection mismatch, torn-down
    // server) the future completes exceptionally and the caller is expected
    // to surface the failure (REQ-RTP-S-004, no silent discards).
    // -------------------------------------------------------------------------

    /**
     * Apply a non-persistent RTP-owned chunk ticket at {@code (cx, cz)}.
     * Equivalent in spirit to Bukkit's {@code addPluginChunkTicket}: keeps the
     * chunk loaded for the lifetime of the JVM only, never written to
     * {@code level.dat}. Replaces vanilla {@code setChunkForced(cx, cz, true)}
     * which would persist (S-002 hazard).
     *
     * <p>Default implementation is a defensive failure — every concrete
     * adapter is expected to override. Returning a failed future rather than
     * silently no-op'ing is required by S-006 (no silent no-ops on early-API
     * misuse).
     */
    default CompletableFuture<Void> applyTicket(RTPLevelHandle level, int cx, int cz) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "applyTicket not implemented for adapter mcVersion=" + mcVersion()));
    }

    /**
     * Release a previously-applied non-persistent RTP ticket at {@code (cx, cz)}.
     * No-op semantics if the ticket is not present (matches vanilla
     * {@code DistanceManager#removeRegionTicket}). Replaces vanilla
     * {@code setChunkForced(cx, cz, false)}.
     */
    default CompletableFuture<Void> releaseTicket(RTPLevelHandle level, int cx, int cz) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "releaseTicket not implemented for adapter mcVersion=" + mcVersion()));
    }

    /**
     * Periodic refresh hook. Called by the Fabric bootstrap on a fixed-rate
     * scheduler (see {@code RTPFabricMod.onInitialize}'s ticket-refresh timer).
     *
     * <p>Default implementation is a no-op — only adapters that use
     * auto-expiring tickets (e.g. the 1.21.5+ {@code DistanceManager#addTicket(long,
     * Ticket)} API where {@code Ticket} carries its own {@code ticksLeft})
     * need to override and re-issue tickets for chunks still held active. See
     * {@code rtp-fabric-ADR-004} for the rationale.</p>
     */
    default void tickRefresh() {
        // no-op — adapters with auto-expiring tickets override this.
    }
}
