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

    /**
     * <b>Non-blocking</b> dispatch of a FULL chunk generation request.
     *
     * <p>Unlike {@link #getChunkFull(RTPLevelHandle, int, int)} — which calls
     * the synchronous-blocking {@code ServerChunkCache#getChunk(cx, cz, FULL,
     * /*load=*&#47;true)} variant — this method calls
     * {@code ServerChunkCache#getChunkFuture(cx, cz, FULL, /*create=*&#47;true)}
     * (1.20.1: {@code class_3215.method_17298} / {@code getChunkFutureMainThread})
     * which returns immediately. Vanilla then schedules generation across its
     * own internal {@code Worker-Main} pool / mailbox graph and completes the
     * returned future on whichever thread finishes the last stage.</p>
     *
     * <p><b>Why this exists.</b> The blocking {@code getChunk(...,true)} variant
     * <i>parks the calling thread on the server's own task queue</i> while
     * waiting for generation. When the calling thread <i>is</i> the server
     * tick thread (which is exactly what happens when a caller does
     * {@code MinecraftServer.submit(() -> getChunkFull(...))}), the tick
     * thread ends up driving its own queue from inside one of its tasks —
     * which deadlocks against any other queued tick task that participates
     * in the chunk-generation dependency graph. Crash report 2026-05-08
     * captured this exact shape: tick thread parked on
     * {@code ServerChunkCache.getChunk}, all 14 common-pool workers parked
     * on {@code FabricRTPWorld.liveLoadPipe}, two never-released permits.</p>
     *
     * <p><b>Threading contract.</b> Callers MUST dispatch this method onto
     * the server tick thread (typically via {@code MinecraftServer#submit}).
     * The dispatch itself returns in microseconds; the wrapped future
     * completes asynchronously when vanilla finishes generation.</p>
     *
     * <p><b>Failure semantics.</b> The returned future completes with
     * {@code null} on a {@code ChunkLoadingFailure} (vanilla's own "couldn't
     * generate" sentinel) so callers can attribute the failure through
     * standard {@code FailTypes.nullChunk} routing (REQ-RTP-S-004 — no
     * silent discards). It completes exceptionally only on a genuine
     * reflection / mapping mismatch.</p>
     *
     * <p>Default implementation falls back to {@link #getChunkFull} and
     * logs a one-time warning — adapters that don't override will still
     * function but remain vulnerable to the deadlock described above.
     * Every concrete adapter shipped with RTP overrides this.</p>
     *
     * @since rtp-fabric-ADR-008
     */
    default CompletableFuture<RTPChunkHandle> requestFullChunkAsync(RTPLevelHandle level, int cx, int cz) {
        // Forward-compatible fallback: behaves like the legacy blocking path so
        // an out-of-tree adapter that hasn't been updated still works (just
        // without the deadlock fix). RTP-shipped adapters all override.
        return getChunkFull(level, cx, cz);
    }

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

    // -------------------------------------------------------------------------
    // Effect dispatchers — see effects-api FabricEffectRuntime + the
    // SoundDispatcher / ParticleDispatcher functional registration hooks.
    //
    // Why this lives on the version adapter SPI: effects-api is non-Loom and
    // platform-agnostic, so it can only reach ServerPlayer#playNotifySound
    // and ServerLevel#sendParticles by reflection. That reflection is fragile
    // across the 1.20 → 1.21.11 drift (Holder<SoundEvent> vs raw SoundEvent
    // ctor arg, ClientboundSoundPacket arity 7 vs 8 with seed, sendParticles
    // boolean-prefix shape). Per-version adapters compile against
    // Yarn/intermediary mappings via Loom and don't have that ambiguity, so
    // they can register a Loom-compiled lambda that uses the mapped vanilla
    // API directly — bypassing the resolver entirely on the hot path.
    //
    // Default: no-op. Adapters that want to opt out (or aren't ready yet)
    // simply don't override, and FabricSoundEffect / FabricParticleEffect
    // fall through to their existing reflective resolvers — preserving
    // bug-for-bug behavior on un-adapted runtimes.
    //
    // Threading: called once from RTPFabricMod.onInitialize, after
    // installVersionAdapter() and before FabricEffectsHandler.setupEffects.
    // Implementations must NOT load chunks, block on tick threads, or hold
    // server-thread affinity — they only register lambdas; the lambdas
    // themselves run on whatever thread the effect's run() is invoked from
    // (currently MinecraftServer#submit-dispatched in FabricEffectRuntime).
    // -------------------------------------------------------------------------

    /**
     * Register Loom-compiled {@code SoundDispatcher} / {@code ParticleDispatcher}
     * lambdas with {@code FabricEffectRuntime} so effects-api skips its
     * reflective resolvers on this MC version. Default implementation is a
     * no-op (effects-api falls through to its reflective fallback).
     *
     * <p>Implementations should reference {@code FabricEffectRuntime} only —
     * not {@code FabricSoundEffect} or {@code FabricParticleEffect} — and
     * must tolerate {@code effects-api} not being on the runtime classpath
     * (the rtp-lite assembly path) by catching {@link NoClassDefFoundError}
     * and logging at {@code FINE}.
     */
    default void installEffectsDispatchers() {
        // no-op — adapters that ship Loom-compiled effect dispatchers override this.
    }
}
