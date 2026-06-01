package io.github.dailystruggle.rtp.fabric.version;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
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

    /**
     * Snapshot of the runtime's {@code minecraft:block} tag bindings, in the
     * {@code namespace:path -> upper-case "namespace:path"} multimap shape
     * documented on {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#blockTagSnapshot()}.
     *
     * <p>Per-version adapters compiled against Loom-mapped MC types should walk
     * {@code BuiltInRegistries.BLOCK} directly via typed calls and invert each
     * block's {@code builtInRegistryHolder().tags()} stream. This bypasses the
     * fragile reflective fallback in {@code FabricServerAccessor} on every
     * runtime where a typed adapter is registered, eliminating Mojmap /
     * intermediary name drift across the 1.20 → 1.21.11 → 26.x window
     * (see rtp-fabric-ADR-010).
     *
     * <p>Return {@code null} to signal "adapter cannot resolve — fall back".
     * The accessor will then attempt its reflective walk. Returning an empty
     * map is a valid result that means "registry walked successfully but
     * yielded zero tags" (e.g. data-pack tag bindings not yet attached); the
     * caller will preserve {@code #tag} tokens for a later retry.
     *
     * <p>Default implementation returns {@code null}, so adapters whose MC
     * version exposes an unstable / intermediary-only registry surface (e.g.
     * the deobfuscated 26.1.x stub) inherit the reflective fallback for free
     * without forcing a half-implemented typed walk.
     *
     * @return a deeply-immutable snapshot, or {@code null} to fall back
     */
    default @Nullable Map<String, Set<String>> snapshotBlockTags() {
        return null;
    }

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

    // -------------------------------------------------------------------------
    // Player factory — per-version registration of an RTPPlayer wrapper that
    // owns the platform-typed ServerPlayer handle.
    //
    // Why this lives on the SPI: rtp-fabric-common's default FabricRTPPlayer
    // is compiled by Loom against intermediary mappings (class_3222 et al.).
    // On the deobfuscated MC 26.1.2 runtime those aliases do not exist, and
    // any class-linkage operation against FabricRTPPlayer (constructor lookup,
    // method dispatch, even MethodHandles.findConstructor) raises
    // NoClassDefFoundError before the body executes. Per-version modules
    // compiled against Mojang names (rtp-fabric-v26_1_R1 uses Loom's
    // unobfuscated plugin and skips the mappings step) can ship a player
    // wrapper whose constant pool references the correct names for that
    // runtime.
    //
    // Default: returns {@code null} so the caller falls back to the legacy
    // {@code new FabricRTPPlayer(...)} path; older adapters (1.20 / 1.21)
    // need not override and continue to work unchanged.
    // -------------------------------------------------------------------------

    /**
     * Construct an {@link RTPPlayer} wrapper around a platform-typed
     * {@code ServerPlayer} handle. The {@code serverPlayer} argument is the
     * raw NM object exactly as supplied by Fabric's connection event (or by
     * a lazy lookup against {@code MinecraftServer.getPlayerList()}).
     *
     * <p>Default implementation returns {@code null}, indicating the caller
     * should use its built-in fallback (typically {@code new FabricRTPPlayer(
     * (ServerPlayer) serverPlayer)}). Adapters compiled for runtimes where
     * the legacy fallback's bytecode cannot link (e.g. deobfuscated MC 26.1+)
     * MUST override this and return a non-null wrapper.
     */
    default @Nullable RTPPlayer createPlayer(Object serverPlayer) {
        return null;
    }

    /**
     * Refresh an existing wrapper's underlying {@code ServerPlayer} handle.
     * Mirrors {@code FabricRTPPlayer#rebind(ServerPlayer)} but Object-typed so
     * the calling site (in {@code rtp-fabric-common}) doesn't need to name
     * the platform type. Default no-op for adapters that don't need rebinding.
     */
    default void rebindPlayer(RTPPlayer existing, Object serverPlayer) {
        // no-op — adapters that own a custom RTPPlayer impl override this.
    }

    // -------------------------------------------------------------------------
    // World factory — same rationale as createPlayer above. The default
    // FabricRTPWorld in rtp-fabric-common is Loom-remapped to intermediary
    // names (class_3218 ServerLevel, class_2818 LevelChunk, ...) which do not
    // exist on the deobfuscated MC 26.1.2 runtime. Per-version modules can
    // ship a slim RTPWorld whose constant pool resolves cleanly.
    //
    // Default returns null so callers fall back to the legacy typed
    // {@code new FabricRTPWorld((ServerLevel) level)} path; adapters for
    // runtimes where that fails (26.1.2+) override and return a non-null
    // wrapper.
    // -------------------------------------------------------------------------

    /**
     * Construct an {@link RTPWorld} wrapper around a platform-typed
     * {@code ServerLevel} handle. The {@code serverLevel} argument is the
     * raw NM object as supplied by Fabric's world-load callback or by the
     * server-started bootstrap walking {@code MinecraftServer#getAllLevels}.
     *
     * <p>Default implementation returns {@code null}, indicating the caller
     * should use its built-in fallback (typically {@code new FabricRTPWorld(
     * (ServerLevel) serverLevel)}). Adapters compiled for runtimes where the
     * legacy fallback's bytecode cannot link (e.g. deobfuscated MC 26.1+)
     * MUST override this and return a non-null wrapper.
     */
    default @Nullable RTPWorld<?> createWorld(Object serverLevel) {
        return null;
    }

    // -------------------------------------------------------------------------
    // Native worldborder factory — same per-version rationale. Common's
    // {@code createNativeWorldBorder} is typed against {@code FabricRTPWorld}
    // and constructs an {@code io.github.dailystruggle.rtp.common.selection
    // .worldborder.WorldBorder} via direct {@code level.getWorldBorder()}
    // calls. On the 26.1.2 deobf runtime that bytecode cannot link, and the
    // {@code instanceof FabricRTPWorld} check fails for v26 wrappers anyway.
    // Per-version modules can supply a typed border that compiles cleanly.
    //
    // Default returns null so the caller falls back to the legacy typed
    // path; older 1.20/1.21 adapters need not override.
    // -------------------------------------------------------------------------

    /**
     * Construct a native-backed {@link io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder}
     * for the given platform-typed {@code ServerLevel} handle, or {@code null}
     * if the adapter doesn't ship a per-version implementation. The result is
     * cached by {@code FabricServerAccessor}; never called again for the same
     * {@code worldName} once non-null.
     */
    default @Nullable io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder
            createNativeWorldBorder(Object serverLevel) {
        return null;
    }

    // -------------------------------------------------------------------------
    // Server-thread / console-command typed overrides — see Phase 4 of the
    // 26.1.2 bring-up checklist. Common-module call sites (FabricScheduler,
    // FabricServerAccessor.isPrimaryThread, FabricConsoleSender) previously
    // resolved MinecraftServer#getRunningThread / #getCommands /
    // #createCommandSourceStack reflectively to dodge intermediary aliases
    // baked in by Loom + officialMojangMappings (e.g. method_3777, method_3734)
    // that don't exist on MC 26.1.2's deobfuscated runtime. Per-version
    // adapters compiled against actual runtime mappings can now provide
    // typed implementations; the common reflective path remains as a
    // fallback for any adapter that doesn't override.
    // -------------------------------------------------------------------------

    /**
     * Return the server's main tick thread (typed equivalent of
     * {@code MinecraftServer#getRunningThread}). The {@code server} argument
     * is the live {@code MinecraftServer} instance.
     *
     * <p>Default: {@code null} — caller falls through to its reflective lookup.
     */
    default @Nullable Thread getServerThread(Object server) {
        return null;
    }

    /**
     * Dispatch a console command on the server's primary command stack
     * (equivalent to {@code server.getCommands().performPrefixedCommand(
     * server.createCommandSourceStack(), command)}).
     *
     * <p>Return {@code true} if the adapter executed the command; {@code false}
     * to let the caller fall back to its reflective implementation. Default
     * returns {@code false}.
     */
    default boolean dispatchConsoleCommand(Object server, String command) {
        return false;
    }

    // -------------------------------------------------------------------------
    // Brigadier source bridge — Phase 4 item 19c. Common's
    // {@code FabricBrigadierSourceBridge.resolveSenderUuid} reflectively walks
    // {@code CommandSourceStack#getEntity()} + {@code Entity#getUUID()} +
    // simple-name match for {@code ServerPlayer}, since the typed
    // {@code instanceof CommandSourceStack} / {@code ServerPlayer} bytecode
    // baked by Loom + officialMojangMappings would name intermediary aliases
    // ({@code class_2168}, {@code class_3222}) absent on MC 26.1.2's deobf
    // runtime. Per-version adapters compiled against the live runtime
    // mappings can supply a typed implementation; the common reflective path
    // remains as a fallback for any adapter that doesn't override.
    // -------------------------------------------------------------------------

    /**
     * Resolve the {@link java.util.UUID} of the player driving a Brigadier
     * {@code CommandSourceStack}, or {@code null} when the source is not a
     * player (console / command block / unknown). The {@code src} argument is
     * the raw NM object as supplied by Brigadier's
     * {@code requires()}/{@code execute()} callbacks.
     *
     * <p>Default: {@code null} — caller falls through to the reflective path
     * in {@code FabricBrigadierSourceBridge}.
     */
    default @Nullable java.util.UUID resolveSenderUuid(Object src) {
        return null;
    }

    // -------------------------------------------------------------------------
    // Effects-api per-version typed wiring — Phase 5 of the 26.1.2 bring-up
    // checklist (item 21). The default effects-api/fabric bytecode (FabricEff-
    // ectRuntime, FabricEffectsInitializer) and the legacy v26 install path
    // carry intermediary-leaked NM types in their constant pools (class_3222
    // ServerPlayer, class_2596 Packet, class_1291 MobEffect, class_2400
    // ParticleOptions, class_7923 BuiltInRegistries) which fail to link on
    // the deobfuscated MC 26.1.2 runtime. Per-version adapters compiled
    // against the live runtime mappings can supply typed implementations;
    // the common path keeps its existing typed fallback for 1.20 / 1.21.
    //
    // All three default to {@code false}, meaning "I did not handle this —
    // caller, please fall through to your existing typed path." Older
    // 1.20 / 1.21 adapters therefore need not override.
    // -------------------------------------------------------------------------

    /**
     * Install effects-api wiring (registry walk, packet sinks, mob-effect
     * dispatch, particle dispatch) against the live {@code MinecraftServer}.
     * The {@code server} argument is the raw NM object as supplied by Fabric's
     * {@code SERVER_STARTED} lifecycle callback.
     *
     * <p>Return {@code true} if the adapter installed the wiring; {@code false}
     * to let the caller fall back to its existing typed path
     * ({@code FabricEffectsHandler.setupEffects(server)} →
     * {@code FabricEffectRuntime.bindServer}). Default returns {@code false}.
     */
    default boolean installEffectsWiring(Object server) {
        return false;
    }

    /**
     * Extract the {@code ServerPlayer} from a {@code ServerGamePacketListenerImpl}
     * (mojmap) / {@code class_3244} (intermediary) handler delivered by Fabric's
     * {@code ServerPlayConnectionEvents$Join} / {@code Disconnect} callbacks.
     *
     * <p>Returned object is the live {@code ServerPlayer}, typed as {@code Object}
     * here to keep this SPI NM-free in {@code rtp-fabric-common} (which is
     * intermediary-typed). Callers must be prepared for {@code null} (adapter
     * miss → S-006 fail-loud-but-survive in {@code FabricEventBridge}).
     *
     * <p>Default returns {@code null}. Per-version adapters compiled against
     * the live runtime mappings should provide a typed implementation:
     * <ul>
     *   <li>Pre-26 (intermediary): {@code ((ServerGamePacketListenerImpl) handler).player}
     *       — public field, stable across 1.20.x → 1.21.x.</li>
     *   <li>v26.x (mojmap): {@code ((ServerGamePacketListenerImpl) handler).getPlayer()}
     *       — typed method exists.</li>
     * </ul>
     */
    default @Nullable Object extractPlayerFromConnection(Object handler) {
        return null;
    }

    /**
     * Extract the player's {@link java.util.UUID} from a {@code ServerPlayer}
     * (mojmap) / {@code class_3222} (intermediary) instance via a typed call site.
     *
     * <p>Returning {@code null} signals "adapter cannot resolve" — callers
     * (e.g. {@code FabricServerAccessor.registerPlayerObject}) must fall through
     * with S-006 fail-loud-but-survive semantics.
     *
     * <p>Per-version overrides type-cast to their own {@code ServerPlayer} and
     * call {@code getUUID()} directly; Loom remaps the method descriptor to the
     * runtime's mapping (e.g. {@code method_5667} on intermediary 1.20/1.21.x,
     * mojmap {@code getUUID} on 26.x) at compile time. The bridge stays
     * NM-free in {@code rtp-fabric-common}.
     *
     * @param player the player object from a Fabric connection event
     * @return the player's UUID, or {@code null} if the adapter cannot resolve it
     */
    default @Nullable java.util.UUID getPlayerUUID(Object player) {
        return null;
    }

    /**
     * Dispatch a title / subtitle splash to the given player (equivalent to
     * sending {@code ClientboundSetTitleTextPacket} +
     * {@code ClientboundSetSubtitleTextPacket} +
     * {@code ClientboundSetTitlesAnimationPacket}). The {@code player}
     * argument is the raw NM {@code ServerPlayer} object.
     *
     * <p>Return {@code true} if the adapter dispatched the title; {@code false}
     * to let the caller fall back to its existing typed path. Default
     * returns {@code false}.
     */
    default boolean dispatchTitle(Object player,
                                  String title,
                                  String subtitle,
                                  int fadeIn,
                                  int stay,
                                  int fadeOut) {
        return false;
    }

    /**
     * Dispatch an action-bar message to the given player (equivalent to
     * sending {@code ClientboundSetActionBarTextPacket}). The {@code player}
     * argument is the raw NM {@code ServerPlayer} object.
     *
     * <p>Return {@code true} if the adapter dispatched the message;
     * {@code false} to let the caller fall back to its existing typed path.
     * Default returns {@code false}.
     */
    default boolean dispatchActionbar(Object player, String text) {
        return false;
    }

    /**
     * Open an interactive written-book menu modal for the given player
     * (rtp-fabric-ADR-012 §4 un-defer, MULTI_PLATFORM_PLAN Step I Session 3).
     * The {@code serverPlayer} argument is the raw NM {@code ServerPlayer}
     * object; {@code spec} is the fully-formatted, NM-free page model produced
     * by {@code FabricBookMenuRenderer}.
     *
     * <p>Per-version overrides build the version's
     * {@code WrittenBookContent} data component (one {@code Component} per
     * page, fragments translated via
     * {@code FabricLegacyText.parseInteractive(..., ClickKind.RUN)}), send a
     * transient {@code ClientboundContainerSetSlotPacket} carrying the book to
     * the player's held hotbar slot, send {@code ClientboundOpenBookPacket}
     * for the main hand, then revert the slot to the real held item so the
     * player's inventory is never mutated server-side.
     *
     * <p>Return {@code true} if the adapter opened the book; {@code false} to
     * let the caller fall back to the chat renderer. Default returns
     * {@code false} — the 1.20.x carrier (no component {@code WrittenBookContent})
     * and the deobf 1.26.x carrier stay on the chat renderer.
     *
     * @param serverPlayer the raw NM {@code ServerPlayer} for the viewer
     * @param spec         the formatted book page model
     * @return whether the written-book modal was opened
     */
    default boolean openBookMenu(Object serverPlayer,
                                 io.github.dailystruggle.rtp.fabric.menu.FabricBookSpec spec) {
        return false;
    }

    // -------------------------------------------------------------------------
    // Maps-api parity (MULTI_PLATFORM_PLAN Step K, rtp-fabric-ADR-014).
    //
    // The platform-neutral FabricMapBinding (rtp-fabric-common) renders an
    // RTP ChartModel into a 128x128 ARGB pixel buffer using the maps-api
    // logical palette, then hands that buffer to the active version adapter
    // through this NM-free seam. The adapter owns every net.minecraft.* touch,
    // allocating a MapItemSavedData / MapId, translating ARGB to vanilla
    // MapColor bytes, sending the map-data update packet, and (on first
    // delivery) placing a FILLED_MAP item referencing the map into the
    // viewer's inventory.
    //
    // Threading: implementations MUST hop to the server tick thread
    // themselves (e.g. MinecraftServer#execute) before touching map /
    // inventory / connection state; the caller may invoke these from an
    // async scheduler thread (live-chart refresh loop).
    // -------------------------------------------------------------------------

    /**
     * Render an RTP chart onto a per-viewer vanilla filled-map and deliver it.
     *
     * @param server      the raw {@code MinecraftServer} (NM-typed as Object)
     * @param viewer      the target player's UUID
     * @param chartKey    stable per-viewer-per-chart key; the adapter reuses the
     *                    same {@code MapId} across live-refresh frames keyed by
     *                    this string
     * @param argb        row-major 128x128 ARGB pixel buffer; alpha 0 means
     *                    "transparent / unpainted" (vanilla {@code MapColor.NONE})
     * @param locked      request a write-locked map (vanilla clients cannot
     *                    trigger a redraw)
     * @param deliverItem place a {@code FILLED_MAP} referencing the map into the
     *                    viewer's inventory (true on first delivery; false on
     *                    live-refresh frames that only resend pixel data)
     * @return {@code true} if the adapter handled the request; {@code false} if
     *         it does not implement map charts (caller falls back to the
     *         {@code NoopMapBinding} skip path). Note: a {@code true} return is
     *         optimistic (the NM work is dispatched onto the server thread and
     *         may still fail there; logged, never silently swallowed per S-004).
     */
    default boolean renderMapChart(Object server,
                                   java.util.UUID viewer,
                                   String chartKey,
                                   int[] argb,
                                   boolean locked,
                                   boolean deliverItem) {
        return false;
    }

    /**
     * Release any per-chart {@code MapId} state cached for {@code chartKey}.
     * Called on viewer disconnect / chart cancellation (REQ-RTP-MAP-003).
     * Default is a no-op.
     */
    default void releaseMapChart(Object server, String chartKey) {
        // no-op: adapters that implement renderMapChart override this.
    }

    /**
     * Whether this adapter implements {@link #renderMapChart}. The
     * {@code FabricMapBinding} consults this at install time so an unsupported
     * carrier (1.20.x stubs, not-yet-ported lines) leaves {@code MapDispatch}
     * on the {@code NoopMapBinding} sentinel rather than installing a binding
     * whose {@code allocate} would always fail. Default is {@code false}.
     */
    default boolean supportsMapCharts() {
        return false;
    }
}
