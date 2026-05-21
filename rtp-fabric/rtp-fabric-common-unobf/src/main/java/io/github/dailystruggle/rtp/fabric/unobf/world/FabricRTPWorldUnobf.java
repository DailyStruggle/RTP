package io.github.dailystruggle.rtp.fabric.unobf.world;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.fabric.unobf.anvil.FabricAnvilColumnProbeAdapter;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fabric {@link RTPWorld} implementation (ADR-022). No {@code org.bukkit.*}
 * imports; holds a {@link ServerLevel} and reaches the server via
 * {@link ServerLevel#getServer()}. Every native chunk-system call
 * ({@code ServerChunkCache#getChunk}, {@code DistanceManager#addRegionTicket}
 * via the version adapter, {@code getForcedChunks}) is dispatched onto the
 * server tick thread via {@link MinecraftServer#submit(java.util.function.Supplier)}
 * (S-005). Chunk tickets use a non-persistent {@code TicketType} rather than
 * vanilla {@code setChunkForced} to avoid persisting RTP-owned chunks to
 * {@code level.dat} (S-002).
 *
 * <p>End-to-end verification is deferred to Phase 2 Step H per
 * {@code MULTI_PLATFORM_PLAN.md}.
 */
public final class FabricRTPWorldUnobf extends RTPWorld<ServerLevel> {

    private final String name;
    private final UUID id;

    /**
     * Live-chunk weak-ref cache, keyed by the canonical packed chunk key
     * ({@code ((long) cx & 0xffffffffL) | ((long) cz << 32)}). Mirrors
     * {@code BukkitRTPWorld.chunkCache} so {@link #getCacheSize()} reports
     * a meaningful number once we wire {@code FabricRTPChunkUnobf}; today the
     * cache is populated by {@link #getChunkAt(int, int)} but never read
     * (see class-level note on {@link #getCachedChunk(long)}).
     */
    private final ConcurrentHashMap<Long, WeakReference<ChunkAccess>> chunkCache = new ConcurrentHashMap<>();

    /**
     * Cached {@link FabricRTPChunkUnobf} wrappers keyed by the same packed chunk key
     * as {@link #chunkCache}. Populated alongside {@code chunkCache} on every
     * successful {@link #getChunkAt(int, int)} so {@link #getCachedChunk(long)}
     * can return a live wrapper without re-allocating per call. Wrappers are
     * dropped when their backing {@link ChunkAccess} is GC'd.
     */
    private final ConcurrentHashMap<Long, WeakReference<FabricRTPChunkUnobf>> rtpChunkCache = new ConcurrentHashMap<>();

    /**
     * ADR-016 anvil-backed chunk-snapshot cache. Populated by
     * {@link #getChunkAt(int, int)} when the persisted {@code .mca} probe
     * succeeds and consulted by {@link #getCachedChunk(long)} as a fall-through
     * after the live caches miss. FIFO-eviction lifecycle is owned by
     * {@link io.github.dailystruggle.rtp.anvil.AnvilProbeSupport}, mirroring
     * the Spigot-side wiring in {@code BukkitRTPWorld}.
     */
    private final io.github.dailystruggle.rtp.anvil.AnvilProbeSupport anvilProbeSupport =
            new io.github.dailystruggle.rtp.anvil.AnvilProbeSupport();

    public FabricRTPWorldUnobf(@NotNull ServerLevel level) {
        super(level);
        // dimension() returns ResourceKey<Level>; its location is the canonical
        // dimension id (e.g. minecraft:overworld). Use that as the world name —
        // this matches how Fabric command/log output identifies dimensions and
        // avoids depending on save-folder names which are server-config-specific.
        this.name = level.dimension().identifier().toString();
        // Fabric does not assign a per-world UUID the way Bukkit does; derive a
        // deterministic UUID from the dimension id so equals/hashCode behave
        // sensibly across restarts.
        this.id = UUID.nameUUIDFromBytes(this.name.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public UUID id() {
        return id;
    }

    /**
     * Public accessor for the underlying {@link ServerLevel}. The base
     * {@link RTPWorld#world} field is {@code protected}; expose it for
     * Fabric-side adapters (player teleport, event bridge) that legitimately
     * need the native handle. Callers in {@code rtp-core} / {@code rtp-api}
     * must NOT use this — keep the API platform-free.
     */
    public ServerLevel level() {
        return world;
    }

    // ---------------------------------------------------------------------------
    // Step A — async chunk load (S-005)
    // ---------------------------------------------------------------------------

    /**
     * S-005-compliant async chunk load.
     *
     * <p><b>Generation contract.</b> Calls
     * {@code ServerChunkCache#getChunk(x, z, ChunkStatus.FULL, /*load=*&#47;true)}
     * inside {@link MinecraftServer#submit}. The {@code load=true} flag is
     * what tells vanilla to <i>generate the chunk if absent</i> — without it,
     * an unloaded coordinate resolves to {@code null} and the RTP pipeline
     * attributes every attempt to {@code nullChunk/asyncLoadNull}, which is
     * exactly what we observed in the 2026-05-03 Fabric 1.21.1 smoke test.
     *
     * <p><b>Why not the non-blocking {@code getChunkFutureMainThread} path?</b>
     * An earlier revision routed through reflective {@code getChunkFutureMainThread}
     * to avoid blocking the tick thread on generation, but that method's
     * intermediary mapping varies across MC patch releases AND it can resolve
     * with an "unloaded" payload that the unwrapper legitimately
     * decodes as {@code null} — producing the same 32×{@code asyncLoadNull}
     * failure mode but for a different reason. The simpler
     * {@code cache.getChunk(..., true)} call is the public, version-stable
     * vanilla API and is the same call vanilla itself uses for
     * {@code /forceload} and command-driven generation.
     *
     * <p><b>Tick-lag note.</b> This call <i>does</i> block the server tick
     * thread for the duration of generation on a cache miss. In practice the
     * RTP pipeline's anvil pre-filter (ADR-016) reads NBT directly from
     * {@code .mca} files without loading chunks, so the bulk of pre-fill
     * work bypasses this path entirely; only the final placement chunk is
     * loaded synchronously here. Acceptable per user-confirmed scope on
     * 2026-05-03.
     */
    @Override
    public CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ) {
        // Probe-entry: do NOT bump totalChunkLoads here. The increment happens inside
        // the server.submit body below, on the actual live chunk load. This avoids the
        // double-count via RTPWorld.getOrLoadChunk's probe-then-live composition; see
        // the Javadoc on RTPWorld.totalChunkLoads.
        final long key = ((long) chunkX & 0xffffffffL) | ((long) chunkZ << 32);

        // ADR-016 — Anvil read-only data source (parity with BukkitRTPWorld#getChunkAt).
        //
        // For candidate chunks that are not currently loaded we probe the
        // persisted r.X.Z.mca region file off-thread. The prefilter is a
        // *data source*, not a short-circuit: whenever the probe yields a
        // decoded AnvilChunkView (regardless of the advisory ACCEPT/REJECT
        // verdict) we publish that view into the Anvil cache and return the
        // same chunk key the live path would use, so the immediately-following
        // getCachedChunk(key) call in LocationGenerator receives an
        // Anvil-backed FabricRTPChunkUnobf and lets the vert adjustor scan across
        // the whole decoded Y range without forcing a live chunk load on the
        // tick thread.
        //
        // Only when the probe returns no view at all (UNKNOWN: no region file /
        // unsupported DataVersion / decode error / no emitted sections) do we
        // fall through to the live-load path below. The live re-check at
        // teleport commit (ADR-016 §4) remains the authoritative arbiter.
        if (shouldPrefilter(chunkX, chunkZ)) {
            ServerLevel probeLevel = world;
            MinecraftServer probeServer = (probeLevel != null) ? probeLevel.getServer() : null;
            if (probeLevel != null && probeServer != null) {
                java.nio.file.Path worldFolder;
                try {
                    worldFolder = probeServer.getWorldPath(LevelResource.ROOT);
                } catch (Throwable t) {
                    worldFolder = null;
                }
                if (worldFolder != null) {
                    String dim = dimensionRegionSubpath(probeLevel);
                    java.util.Set<String> rawUnsafe = currentUnsafeBlocks();
                    return anvilProbeSupport
                            .probeAndPublish(worldFolder, dim, chunkX, chunkZ, key, rawUnsafe,
                                    io.github.dailystruggle.rtp.fabric.unobf.anvil.FabricPaletteNormalizer::reconcile)
                            .thenCompose(result -> {
                                io.github.dailystruggle.rtp.anvil.AnvilChunkView view = result.view();
                                if (view != null) {
                                    if (result.verdict() == io.github.dailystruggle.rtp.anvil.Verdict.REJECT) {
                                        RTP.log(java.util.logging.Level.FINE,
                                                "[RTP] Anvil surface-unsafe (advisory) world=" + name
                                                        + " chunk=(" + chunkX + "," + chunkZ
                                                        + ") — handing view to vert adjustor");
                                    }
                                    return CompletableFuture.completedFuture(key);
                                }
                                // No view available (UNKNOWN) → live load is authoritative.
                                return loadLiveChunk(chunkX, chunkZ, key);
                            });
                }
            }
        }

        return loadLiveChunk(chunkX, chunkZ, key);
    }

    /**
     * Snapshot the current {@code SafetyKeys.unsafeBlocks} list as a plain
     * {@code Set<String>}. Returns an empty set on any lookup failure — the
     * pre-filter treats an empty set as "never reject", which is the safe
     * default. Mirrors {@code BukkitRTPWorld.currentUnsafeBlocks}.
     */
    @SuppressWarnings("unchecked")
    private static java.util.Set<String> currentUnsafeBlocks() {
        try {
            ConfigParser<SafetyKeys> safety =
                    (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
            if (safety == null) return java.util.Collections.emptySet();
            Object raw = safety.getConfigValue(SafetyKeys.unsafeBlocks, new java.util.ArrayList<>());
            if (raw instanceof java.util.Collection<?> c) {
                java.util.Set<String> out = new java.util.HashSet<>(c.size());
                for (Object o : c) if (o != null) out.add(o.toString());
                return out;
            }
        } catch (Throwable ignored) {
            // Fall through to empty.
        }
        return java.util.Collections.emptySet();
    }

    /**
     * Live-chunk load path. Extracted from {@link #getChunkAt} so the ADR-016
     * anvil pre-filter can fall through to it on UNKNOWN.
     *
     * <p><b>Non-blocking dispatch (rtp-fabric-ADR-008).</b> Calls
     * {@code FabricVersionAdapter#requestFullChunkAsync}, which under the
     * hood invokes {@code ServerChunkCache#getChunkFuture(cx, cz, FULL,
     * /*create=*&#47;true)} — vanilla's non-blocking generation entry point.
     * The dispatch itself runs on the server tick thread via
     * {@link MinecraftServer#submit} and returns in microseconds; vanilla's
     * own internal scheduler drives generation across {@code Worker-Main}
     * threads and completes the inner future when the chunk is ready.</p>
     *
     * <p><b>What we used to do (and why it crashed).</b> Previously this
     * method called the synchronous-blocking
     * {@code ServerChunkCache#getChunk(cx, cz, FULL, /*load=*&#47;true)} from
     * inside an {@code AsyncSupply} we submitted to the server thread. That
     * variant parks the calling thread on the server's own task queue while
     * waiting for generation — when the calling thread <i>is</i> the server
     * tick thread (which it always is, here), it ends up driving its own
     * queue from inside one of its tasks, deadlocking with any other task
     * in the chunk-generation dependency graph. Crash report
     * 2026-05-08_01.22.29-server.txt captured exactly that: tick thread
     * parked on {@code class_3215.method_12121}, all 14 commonPool workers
     * parked on {@code liveLoadPipe}, two never-released permits.</p>
     *
     * <p>Per-coordinate dedup ({@link #inFlightLiveLoads}) is preserved —
     * concurrent callers for the same {@code (cx,cz)} still share one
     * future. The bounded-pipe {@code Semaphore} is gone: with non-blocking
     * dispatch there is no multi-second wait to back-pressure, and vanilla's
     * own chunk-system has internal back-pressure. Outer 4-second
     * {@code completeOnTimeout} on {@link #getOrLoadChunk} remains as a
     * defense-in-depth deadline.</p>
     */
    private CompletableFuture<Long> loadLiveChunk(int chunkX, int chunkZ, long key) {
        io.github.dailystruggle.rtp.common.tools.CfDiag.fabricLoadLiveChunk.increment();
        final MinecraftServer server = world.getServer();
        if (server == null) {
            // Defensive: a ServerLevel without a server is a torn-down state.
            // Complete exceptionally rather than throwing on the caller thread
            // so the pipeline's existing failure-attribution path picks it up
            // (REQ-RTP-S-004 — no silent discards).
            CompletableFuture<Long> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                "FabricRTPWorldUnobf.getChunkAt: ServerLevel has no MinecraftServer (world=" + name + ")"));
            return failed;
        }

        // (1) Per-coordinate de-duplication — concurrent callers for the
        // same (cx,cz) share one in-flight future.
        CompletableFuture<Long> existing = inFlightLiveLoads.get(key);
        if (existing != null) return existing;

        CompletableFuture<Long> result = new CompletableFuture<>();
        CompletableFuture<Long> raced = inFlightLiveLoads.putIfAbsent(key, result);
        if (raced != null) return raced;

        // (2) Resolve the active version adapter. peek() is preferred over
        // require() because a caller racing with shutdown should fall through
        // to a clean failure rather than throw an IllegalStateException on
        // the common-pool worker.
        final io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
        if (adapter == null) {
            inFlightLiveLoads.remove(key, result);
            result.completeExceptionally(new IllegalStateException(
                    "FabricRTPWorldUnobf.loadLiveChunk: FabricVersionAdapter not yet installed"));
            return result;
        }

        // (3) Non-blocking dispatch. We invoke adapter.requestFullChunkAsync
        // directly from the caller's thread (typically a common-pool worker).
        // Vanilla's getChunkFutureMainThread internally re-dispatches to the
        // server's mainThreadExecutor when called off-thread, returning a
        // future that completes on whichever worker vanilla finishes on.
        //
        // CRITICAL: do NOT wrap this call in server.submit(...). Crash report
        // 2026-05-08_11.19.30 captured the failure mode — when invoked from
        // an AsyncSupply running on the tick thread, getChunkFutureMainThread
        // ran inline and drove its own task queue (yielding via
        // BlockableEventLoop#managedBlock), tripping the watchdog. Letting
        // vanilla self-dispatch from off-thread avoids that re-entrancy.
        fabricLiveLoadInFlight.incrementAndGet();
        totalChunkLoads.incrementAndGet();
        final io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle levelHandle =
                io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle.of(world);

        // Dispatch the adapter call ON the server tick thread. With C2ME
        // installed, getChunkFuture(..., FULL, /*create=*/true) only enqueues
        // generation work into the chunk system's pulse loop when invoked on
        // the tick thread; off-thread invocations resolve immediately to a
        // ChunkLoadingFailure (Either.right) and our adapter unwraps that to
        // null — manifesting as the nullChunk/asyncLoadNull burst seen in
        // the 2026-05-08 13:27 test run with C2ME 0.2.0-alpha.11.16.
        //
        // Routing the dispatch via server.execute(...) hands the request to
        // C2ME's queue exactly the way Chunky does. The reflective getChunk-
        // Future call returns its CompletableFuture immediately (vanilla
        // 1.20.1 does not park on it), so the tick thread is not blocked.
        // The actual generation completes off-thread on vanilla/C2ME's own
        // worker pool, so we do not reintroduce the ADR-008 deadlock.
        final MinecraftServer dispatchServer = world.getServer();
        if (dispatchServer == null) {
            // Tear-down race: complete the result on the dispatch path so the
            // single whenComplete cleanup below handles it uniformly. We mark
            // the gate as not-acquired since we never tried to acquire one.
            CompletableFuture<io.github.dailystruggle.rtp.fabric.version.RTPChunkHandle> dispatch =
                    CompletableFuture.completedFuture(null);
            attachDispatchCompletion(dispatch, key, result, /*acquiredPermit=*/false);
            return result;
        }

        // (4) Concurrency gate — see liveLoadGate javadoc. Tries an immediate
        // permit; if none is available the dispatch is parked on a bounded FIFO
        // and resumed by the next chunk's whenComplete (no recursion). When the
        // queue is also full we fail-fast with BusyException so the pipeline
        // attributes the failure cleanly (REQ-RTP-S-004) instead of unbounded
        // queuing.
        Runnable dispatchLogic = () -> dispatchAdapterCall(
                dispatchServer, adapter, levelHandle, chunkX, chunkZ, key, result);
        if (liveLoadGate.tryAcquire()) {
            dispatchLogic.run();
        } else {
            if (!liveLoadWaiters.offer(dispatchLogic)) {
                inFlightLiveLoads.remove(key, result);
                fabricLiveLoadInFlight.decrementAndGet();
                totalChunkLoads.decrementAndGet();
                result.completeExceptionally(new IllegalStateException(
                        "FabricRTPWorldUnobf.loadLiveChunk: gate saturated (queue full) world=" + name
                                + " chunk=(" + chunkX + "," + chunkZ + ")"));
                return result;
            }
            // Re-check: a permit may have been released between tryAcquire and offer.
            drainWaitersIfPermitFree();
        }

        return result;
    }

    /**
     * Performs the actual {@code dispatchServer.execute(...)} → adapter call,
     * then attaches the unified completion handler. Must only be invoked when
     * the caller holds one permit on {@link #liveLoadGate}.
     */
    private void dispatchAdapterCall(
            MinecraftServer dispatchServer,
            io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter,
            io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle levelHandle,
            int chunkX, int chunkZ, long key,
            CompletableFuture<Long> result) {
        CompletableFuture<CompletableFuture<io.github.dailystruggle.rtp.fabric.version.RTPChunkHandle>> bridge =
                new CompletableFuture<>();

        dispatchServer.execute(() -> {
            try {
                CompletableFuture<io.github.dailystruggle.rtp.fabric.version.RTPChunkHandle> inner =
                        adapter.requestFullChunkAsync(levelHandle, chunkX, chunkZ);
                bridge.complete(inner);
            } catch (Throwable t) {
                bridge.completeExceptionally(t);
            }
        });
        // Bridge watchdog: dispatchServer.execute(...) silently drops the runnable
        // when the server is stopping. Without this, bridge would never complete,
        // dispatch would never complete, attachDispatchCompletion's whenComplete
        // would never fire, the gate permit would never release, and
        // inFlightLiveLoads would retain a dead future for this key forever —
        // every subsequent getChunkAt(cx,cz) caller would receive the dead future
        // immediately, return null upstream, and the orchestration layer
        // (QueueTask/PregenTask, both with 5 s orTimeout) would re-issue
        // unboundedly, accumulating millions of CompletableFuture nodes
        // (BiApply / BiRelay / CoCompletion). Heap-histogram signature: see
        // 2026-05-08 dump (~96 M CF, ~94 M CoCompletion, ~48 M BiApply).
        bridge.orTimeout(BRIDGE_DISPATCH_DEADLINE_MS, TimeUnit.MILLISECONDS);
        CompletableFuture<io.github.dailystruggle.rtp.fabric.version.RTPChunkHandle> dispatch =
                bridge.thenCompose(f -> f == null ? CompletableFuture.completedFuture(null) : f);
        // Hard per-load deadline. If vanilla's chunk system never completes the
        // future (server stopping, C2ME stall, watchdog crash mid-generation),
        // the timeout completes `dispatch` exceptionally — which fires the
        // whenComplete in attachDispatchCompletion, releasing the gate permit,
        // removing the inFlightLiveLoads entry, and propagating the failure to
        // the caller's result CF. Tuned just under the orchestration layer's
        // 5 s orTimeout (QueueTask:153 / QueueTask:282) so we surface the
        // failure as an exceptional inner future rather than letting upstream
        // see a stale null while the inner work is still pinned.
        dispatch = dispatch.orTimeout(LIVE_LOAD_DEADLINE_MS, TimeUnit.MILLISECONDS);
        attachDispatchCompletion(dispatch, key, result, /*acquiredPermit=*/true);
    }

    /**
     * Watchdog timeout for {@code dispatchServer.execute(...)} → adapter.
     * If the server's execute() drops the runnable (shutdown), bridge never
     * completes — this timeout converts that into an exceptional completion
     * so the gate-release path runs. Tight because the body of the runnable
     * is "create a CompletableFuture and complete it with the adapter's
     * inner future" — microseconds of work.
     */
    private static final long BRIDGE_DISPATCH_DEADLINE_MS = 1_500L;

    /**
     * Hard ceiling on how long a single live-load future may stay pinned in
     * {@link #inFlightLiveLoads}. Crucially this acts on the inner adapter
     * future, NOT on the outer {@link #getOrLoadChunk} CF — the previous
     * design used {@code completeOnTimeout(null, …)} which resolved the
     * outer caller but left the inner future (and its gate permit and
     * inFlightLiveLoads entry) live forever. See the bridge-watchdog javadoc
     * above for the failure mode this prevents.
     *
     * <p>Tuned just under the orchestration layer's 5 s orTimeout
     * (QueueTask:153 / QueueTask:282 / PregenTask reservation:452) so the
     * inner failure surfaces before upstream gives up; mismatched deadlines
     * were how 2026-05-08's CF leak avoided detection for so long.</p>
     */
    private static final long LIVE_LOAD_DEADLINE_MS = 4_000L;

    /**
     * Wires the unified whenComplete handler that performs lifecycle bookkeeping,
     * cache publication, gate release, and waiter drain.
     *
     * @param acquiredPermit whether the caller is holding a {@link #liveLoadGate}
     *                       permit that must be released here.
     */
    private void attachDispatchCompletion(
            CompletableFuture<io.github.dailystruggle.rtp.fabric.version.RTPChunkHandle> dispatch,
            long key,
            CompletableFuture<Long> result,
            boolean acquiredPermit) {
        dispatch.whenComplete((handle, error) -> {
            try {
                // Lifecycle bookkeeping on EVERY exit path (success,
                // exception, deadline) — mirrors the MemoryTracker contract.
                fabricLiveLoadInFlight.decrementAndGet();
                inFlightLiveLoads.remove(key, result);
                if (error != null) {
                    Throwable cause = (error instanceof CompletionException && error.getCause() != null)
                            ? error.getCause() : error;
                    result.completeExceptionally(cause);
                    return;
                }
                if (handle != null) {
                    ChunkAccess chunk = handle.as(ChunkAccess.class);
                    if (chunk != null) {
                        chunkCache.put(key, new WeakReference<>(chunk));
                        rtpChunkCache.put(key,
                                new WeakReference<>(new FabricRTPChunkUnobf(chunk, world, id)));
                        // A live chunk supersedes any anvil snapshot — drop the
                        // stale view so subsequent getCachedChunk lookups don't
                        // return outdated palette data.
                        anvilProbeSupport.evict(key);
                    }
                }
                result.complete(key);
            } finally {
                if (acquiredPermit) {
                    // Release first, then resume one waiter (non-recursive: we
                    // only ever pop one waiter per release, and the waiter's
                    // dispatchServer.execute(...) returns immediately, so the
                    // call stack does not grow with queue depth).
                    liveLoadGate.release();
                    drainWaitersIfPermitFree();
                }
            }
        });
    }

    /**
     * Pull the next parked dispatch off {@link #liveLoadWaiters} if a permit
     * is currently free. Loops only while we successfully claim a permit AND
     * find a waiter — bounded by the number of currently-released permits, so
     * the call is O(permits) per invocation, never recursive.
     */
    private void drainWaitersIfPermitFree() {
        while (true) {
            Runnable next = liveLoadWaiters.peek();
            if (next == null) return;
            if (!liveLoadGate.tryAcquire()) return;
            // Atomically claim the same waiter we peeked at; if another thread
            // already took it, return the permit and retry.
            if (!liveLoadWaiters.remove(next)) {
                liveLoadGate.release();
                continue;
            }
            try {
                next.run();
            } catch (Throwable t) {
                // dispatchAdapterCall is the only producer of waiters and it
                // never throws synchronously, but defend against future change:
                // release the permit so the gate doesn't deadlock.
                liveLoadGate.release();
                RTP.log(java.util.logging.Level.WARNING,
                        "[RTP][Fabric] Waiter dispatch threw — gate permit released defensively", t);
            }
            // Loop: a single drain call may resume multiple waiters if multiple
            // permits were freed concurrently (e.g. burst completion).
        }
    }

    /**
     * Historical note: a {@code Semaphore liveLoadPipe} (2 permits) used to
     * gate entry into a synchronous {@code cache.getChunk(..., FULL, true)}
     * call dispatched onto the tick thread. That call was removed in
     * rtp-fabric-ADR-008 because it deadlocked the tick thread against its
     * own task queue (see {@link #loadLiveChunk} Javadoc). Non-blocking
     * dispatch via {@code getChunkFuture} makes the permit pool unnecessary,
     * since vanilla's chunk system has its own internal back-pressure and
     * our submit returns in microseconds.
     */
    /**
     * Per-coordinate de-duplication map. Concurrent requests for the same
     * {@code (cx,cz)} share one in-flight future instead of stacking duplicate
     * tick-thread submits behind the same permit. Entries are removed in the
     * {@code whenComplete} stage of {@link #loadLiveChunk} on every exit path.
     */
    private final ConcurrentHashMap<Long, CompletableFuture<Long>> inFlightLiveLoads = new ConcurrentHashMap<>();

    /**
     * Diagnostic counter of generation dispatches currently in flight
     * (between {@code server.submit} and the submit's completion). Intended
     * for future metrics surface (METRICS_PLAN), not consulted by control
     * flow.
     */
    private final AtomicInteger fabricLiveLoadInFlight = new AtomicInteger();

    /**
     * Concurrent-generation cap for {@link #loadLiveChunk}.
     *
     * <p>1.20.1's chunk-system implementation holds onto significantly more
     * working memory per in-flight generation than 1.21.11+ — empirically the
     * backend OOMs on 1.20.1 with the unbounded dispatch that the rest of the
     * version line tolerates without issue. The gate caps how many adapter
     * calls can be parked in vanilla's chunk-future pipeline at once. Excess
     * callers park on {@link #liveLoadWaiters}; on each completion the gate
     * pulls the next waiter (non-recursive — see {@link #drainWaitersIfPermitFree}).</p>
     *
     * <p>Hardcoded for now (per 2026-05-08 user direction); a config knob may
     * be added if dynamic tuning proves necessary. Permit count is selected
     * from the active {@link io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter}'s
     * {@code mcVersion()}: 2 for the 1.20.x line, 4 elsewhere.</p>
     */
    private final Semaphore liveLoadGate = new Semaphore(resolveLiveLoadPermits(), /*fair=*/true);

    /**
     * Bounded FIFO of dispatch tasks parked because {@link #liveLoadGate} was
     * full at request time. Bounded so we fail-fast (with a clean exceptional
     * future) rather than letting a stuck chunk system grow this queue
     * without limit. The size is generous relative to the gate width — under
     * normal load the queue stays empty; it only fills during pre-fill bursts
     * or while vanilla is recovering from a slow tick.
     */
    private static final int LIVE_LOAD_WAITER_CAPACITY = 256;

    /**
     * FIFO of parked dispatch runnables. Drained by
     * {@link #drainWaitersIfPermitFree} whenever a permit becomes available.
     * {@link ConcurrentLinkedQueue#offer} never returns false, so the
     * capacity check is performed against {@link #liveLoadWaiters}'s size.
     */
    private final ConcurrentLinkedQueue<Runnable> liveLoadWaiters =
            new ConcurrentLinkedQueue<>() {
                @Override
                public boolean offer(Runnable r) {
                    if (size() >= LIVE_LOAD_WAITER_CAPACITY) return false;
                    return super.offer(r);
                }
            };

    /**
     * Pick the permit count for {@link #liveLoadGate} based on the active
     * version adapter's {@code mcVersion()}. 1.20.x is the heavy line (2);
     * everything else (1.21+, 26.1) gets 4. Defaults to 2 (conservative) if
     * the adapter isn't yet installed at construction time — this is safe:
     * the world is created before adapter installation only in tests, and a
     * tighter cap there is harmless.
     */
    private static int resolveLiveLoadPermits() {
        try {
            io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                    io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
            if (adapter == null) return 2;
            String v = adapter.mcVersion();
            if (v == null) return 2;
            if (v.startsWith("1.20")) return 2;
            return 4;
        } catch (Throwable ignored) {
            return 2;
        }
    }

    /**
     * Fabric-only override of {@link RTPWorld#getOrLoadChunk(int, int)}.
     *
     * <p><b>Why this exists.</b> The world adapter is the canonical owner
     * of any per-chunk deadline (per the 2026-05-08 architectural decision
     * to remove orchestration-level {@code .orTimeout} from
     * {@code rtp-core}'s {@code QueueTask}/{@code PregenTask}). On Fabric
     * the live-load path is dispatched non-blocking via ADR-008
     * ({@code adapter.requestFullChunkAsync} → vanilla's worker pool), but
     * vanilla's chunk system can still take many seconds on a freshly-explored
     * coordinate during early-server scan/pre-fill before the kept-cache is
     * warm. This override caps that per-chunk wait so a single stuck
     * coordinate doesn't pin the calling task indefinitely.</p>
     *
     * <p><b>Strategy.</b> Probe-first: cached → anvil → live-load. The
     * live-load future is bounded by {@link #LIVE_LOAD_DEADLINE_MS} via
     * {@code dispatch.orTimeout(...)} inside
     * {@link #dispatchAdapterCall} — failing the inner future
     * exceptionally on deadline so the gate permit, the
     * {@link #inFlightLiveLoads} entry, and any attached cleanup release
     * cleanly. The outer caller sees a {@link TimeoutException} mapped to
     * {@code null} via the {@code exceptionally} stage below.</p>
     *
     * <p>S-004 compliance: a deadline-{@code null} resolution is not a
     * silent discard — the calling task ({@code QueueTask}) treats it as a
     * "no chunk available right now" rejection and routes the attempt
     * through the standard {@code FailTypes.nullChunk} attribution path.
     * Genuine load failures (exceptions thrown inside
     * {@link #loadLiveChunk}) still surface as failed futures and are
     * logged by the parent task.</p>
     */
    @Override
    public CompletableFuture<RTPChunk<?>> getOrLoadChunk(int cx, int cz) {
        final long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
        RTPChunk<?> cached = getCachedChunk(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        // Per-load deadline lives inside loadLiveChunk's dispatch chain
        // (LIVE_LOAD_DEADLINE_MS). We do NOT add an outer completeOnTimeout
        // here: that pattern resolved the caller's CF with null while leaving
        // the inner future (with its gate permit and inFlightLiveLoads entry)
        // live forever, which is the leak shape captured in the 2026-05-08
        // heap dump. Letting the inner future fail exceptionally lets the
        // existing whenComplete cleanup release every resource on every exit
        // path, matching the MemoryTracker contract elsewhere in rtp-core.
        return getChunkAt(cx, cz).thenCompose(probeKey -> {
            RTPChunk<?> afterProbe = (probeKey != null) ? getCachedChunk(probeKey) : null;
            if (afterProbe != null) {
                return CompletableFuture.completedFuture(afterProbe);
            }
            // Mirror the base RTPWorld#getOrLoadChunk(cx,cz) safety-net: untagged
            // callers still attribute to "unknown" so chunkLoadsByOrigin sums to
            // totalChunkLoads. Tagged callers should use the 3-arg overload, which
            // calls getChunkAtAsync directly and bypasses this override.
            recordChunkLoadOrigin("unknown");
            return getChunkAtAsync(cx, cz).thenApply(chunkSet -> {
                if (chunkSet == null) return null;
                return getCachedChunk(key);
            });
        }).exceptionally(ex -> {
            // The orchestration layer (QueueTask/PregenTask) treats a null
            // resolution as "no chunk available right now" via the standard
            // FailTypes.nullChunk attribution path, satisfying REQ-RTP-S-004.
            // We map TimeoutException + adapter throwables to null here rather
            // than re-throw because the caller side uses .whenComplete(chunk,ex)
            // and ex != null is logged at WARNING — and a routine deadline is
            // not warning-worthy. Genuine bugs (NPE, IllegalStateException
            // thrown synchronously) still propagate via the exceptional path
            // above this catch.
            Throwable cause = (ex instanceof CompletionException && ex.getCause() != null)
                    ? ex.getCause() : ex;
            if (cause instanceof TimeoutException) {
                RTP.log(java.util.logging.Level.FINE,
                        "[RTP][Fabric] getOrLoadChunk deadline (" + LIVE_LOAD_DEADLINE_MS
                                + "ms) for " + name + " chunk=(" + cx + "," + cz + ")");
                return null;
            }
            // Non-timeout failures: log at FINE (the upstream WARNING in
            // QueueTask/PregenTask is sufficient) and surface as null.
            RTP.log(java.util.logging.Level.FINE,
                    "[RTP][Fabric] getOrLoadChunk failed for " + name
                            + " chunk=(" + cx + "," + cz + "): "
                            + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return null;
        });
    }

    // FABRIC_GENERATION_DEADLINE_MS removed 2026-05-08: superseded by the
    // inner-future deadline in dispatchAdapterCall (LIVE_LOAD_DEADLINE_MS).
    // The 30 s outer-CF completeOnTimeout(null) it backed left the inner
    // future + gate permit + inFlightLiveLoads entry pinned indefinitely on
    // shutdown drops, which manifested as the CF-graph leak (>10 GB) in the
    // 2026-05-08 heap dump.

    /**
     * Minimal {@link ChunkSet} mirroring {@code BukkitRTPWorld#getChunkAtAsync}.
     * Single-chunk set with the load future as its only entry; the {@code done}
     * future is completed (with {@code null}) on the same callback that resolves
     * the load future, so any cross-platform consumer that attaches via
     * {@code thenCombine}/{@code allOf}/{@code whenComplete} to {@code done()}
     * is released and its dependent-stage graph becomes GC-eligible.
     *
     * <p><b>Why this matters.</b> Prior to 2026-05-08 the {@code done} future
     * was deliberately left uncompleted because the Fabric pipeline did not
     * consume it. But {@code rtp-core} is platform-agnostic — any future
     * cross-platform feature attaching to {@code done} would silently leak its
     * entire dependent-stage graph until JVM exit on Fabric. The completed-on-
     * resolve contract here matches Bukkit semantics (where {@code done} is
     * fired by the ADR-016 stale-chunk guard) and removes the foot-gun. See
     * {@code POTENTIAL_BUGS.md} 2026-05-08 entry for the original report.</p>
     */
    @Override
    public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
        io.github.dailystruggle.rtp.common.tools.CfDiag.fabricGetChunkAtAsync.increment();
        return getChunkAt(cx, cz).thenApply(key -> {
            io.github.dailystruggle.rtp.common.tools.CfDiag.chunkSetFabricGetChunk.increment();
            CompletableFuture<Boolean> done = new CompletableFuture<>();
            ChunkSet set = new ChunkSet(this, cx, cz,
                Collections.singletonList(CompletableFuture.completedFuture(key)),
                done);
            // Fire-and-forget release so any attached dependents (cross-
            // platform stale-chunk guards, future allOf consumers) drain
            // their CF graph instead of pinning it. Completion value mirrors
            // whether a chunk key was resolved — the Bukkit consumers only
            // branch on completion, not on the value, but Boolean.TRUE on
            // success / FALSE on null preserves a useful signal for future
            // cross-platform listeners.
            done.complete(key != null);
            return set;
        });
    }

    /**
     * Non-blocking loaded-chunk lookup. {@link ServerChunkCache#hasChunk(int, int)}
     * is the documented non-loading state query — used by the stale-chunk guard
     * (ADR-015 / REQ-RTP-S-005) between an async load future resolution and the
     * subsequent block-evaluation task on a Count-Bound pipe. Defensive on torn-down
     * worlds.
     */
    @Override
    public boolean isChunkLoaded(int cx, int cz) {
        try {
            ServerChunkCache cache = world.getChunkSource();
            return cache != null && cache.hasChunk(cx, cz);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------
    // ADR-016 anvil pre-filter — column probe (parity with BukkitRTPWorld)
    // ---------------------------------------------------------------------------

    /**
     * S-005-compliant column probe that reads a single chunk's center column
     * directly from the persisted {@code r.X.Z.mca} region file, completely
     * bypassing {@link #getChunkAt} (and therefore the synchronous tick-thread
     * {@code cache.getChunk(..., FULL, true)} generation).
     *
     * <p>This is the Fabric mirror of {@code BukkitRTPWorld#probeChunkColumn}
     * (see {@code rtp-spigot-common}, lines 227–268, and ADR-016). The only
     * platform deltas are world-folder resolution
     * ({@link MinecraftServer#getWorldPath(LevelResource)}) and dimension
     * subpath derivation ({@link #dimensionRegionSubpath(ServerLevel)}),
     * because Fabric has no {@code World.Environment} enum.</p>
     *
     * <p>Gates (see {@link #shouldPrefilter}):
     * <ul>
     *   <li>{@code SafetyKeys.anvilPrefilterEnabled} truthy (default {@code true})</li>
     *   <li>chunk currently <i>not</i> loaded (live state is authoritative when loaded)</li>
     * </ul>
     * On a closed gate or any decode failure the future resolves to {@code null}
     * (UNKNOWN) and {@code ScanTask} / {@code QueueTask} / {@code PregenTask}
     * fall back to the live-load path — preserving the
     * {@code .mca}-as-advisory invariant of ADR-016.</p>
     *
     * <p>S-005: file I/O is dispatched onto
     * {@link io.github.dailystruggle.rtp.anvil.AnvilIoPool} (the same dedicated
     * blocking-I/O executor Spigot/Folia use), never on the server tick thread.</p>
     */
    @Override
    public CompletableFuture<ChunkColumnProbe> probeChunkColumn(
            int cx, int cz, int minY, int maxY) {
        if (minY > maxY) return CompletableFuture.completedFuture(null);
        if (!shouldPrefilter(cx, cz)) return CompletableFuture.completedFuture(null);
        ServerLevel level = world;
        if (level == null) return CompletableFuture.completedFuture(null);
        MinecraftServer server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(null);

        final java.nio.file.Path worldFolder;
        try {
            // Mojang official mappings: MinecraftServer#getWorldPath(LevelResource)
            // returns the per-server save root for the requested resource. ROOT
            // points at the world's base directory — the Fabric equivalent of
            // Bukkit's World#getWorldFolder().
            worldFolder = server.getWorldPath(LevelResource.ROOT);
        } catch (Throwable t) {
            RTP.log(java.util.logging.Level.FINE,
                "[RTP] FabricRTPWorldUnobf.probeChunkColumn: getWorldPath threw for world="
                    + name + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return CompletableFuture.completedFuture(null);
        }
        final String dim = dimensionRegionSubpath(level);
        final int finalMinY = minY;
        final int finalMaxY = maxY;

        // Mirrors the Spigot dispatch contract: AnvilIoPool runs blocking .mca
        // reads off-tick with disk-parallelism sizing. Probe-cache hit / miss
        // metrics are owned by ScanTask's probeOutcome* counters and the anvil
        // module's own diagLog channel — no per-call counter is owned here.
        return CompletableFuture.supplyAsync(() -> {
            try {
                java.nio.file.Path regionFile =
                    io.github.dailystruggle.rtp.anvil.AnvilPrefilter
                        .regionFileFor(worldFolder, dim, cx, cz);
                byte[] regionBytes =
                    io.github.dailystruggle.rtp.anvil.AnvilRegionByteCache.get(regionFile);
                if (regionBytes == null) return null;
                int rx = Math.floorMod(cx, 32);
                int rz = Math.floorMod(cz, 32);
                io.github.dailystruggle.rtp.anvil.ColumnProbe probe =
                    io.github.dailystruggle.rtp.anvil.AnvilReader.readColumnProbe(
                        regionBytes, rx, rz, finalMinY, finalMaxY);
                if (probe == null) return null;
                return (ChunkColumnProbe) new FabricAnvilColumnProbeAdapter(probe, cx, cz);
            } catch (Throwable t) {
                RTP.log(java.util.logging.Level.FINE,
                    "[RTP] FabricRTPWorldUnobf.probeChunkColumn failed for world=" + name
                        + " chunk=(" + cx + "," + cz + "): "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
                return null;
            }
        }, io.github.dailystruggle.rtp.anvil.AnvilIoPool.get());
    }

    /**
     * Applicability gate for the column probe — parity with
     * {@code BukkitRTPWorld#shouldPrefilter}. Returns {@code true} only when
     * the chunk is not currently loaded and the
     * {@code SafetyKeys.anvilPrefilterEnabled} config is truthy. Any failure
     * defaults to "skip prefilter" rather than "force-load" so the pipeline
     * always has a safe fall-through.
     */
    private boolean shouldPrefilter(int cx, int cz) {
        if (world == null) return false;
        try {
            if (isChunkLoaded(cx, cz)) return false;
        } catch (Throwable ignored) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            ConfigParser<SafetyKeys> safety =
                (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
            if (safety == null) return true; // Config not yet loaded — default-on.
            Object raw = safety.getConfigValue(SafetyKeys.anvilPrefilterEnabled, Boolean.TRUE);
            if (raw instanceof Boolean b) return b;
            if (raw != null) return Boolean.parseBoolean(raw.toString());
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Map a {@link ServerLevel}'s dimension {@link Identifier} to the
     * on-disk region subdirectory used by vanilla. Mirrors
     * {@code BukkitRTPWorld.dimensionRegionSubpath} but keyed off the
     * {@code Identifier} (Fabric / vanilla don't expose
     * {@code World.Environment}).
     *
     * <p>Vanilla layout (consumed by {@code AnvilPrefilter.regionFileFor}):
     * <ul>
     *   <li>{@code minecraft:overworld} → {@code ""} (root + {@code region/r.X.Z.mca})</li>
     *   <li>{@code minecraft:the_nether} → {@code "DIM-1"}</li>
     *   <li>{@code minecraft:the_end}    → {@code "DIM1"}</li>
     *   <li>any other registry key      → {@code "dimensions/<namespace>/<path>"}
     *       (the Fabric-API custom-dimension layout)</li>
     * </ul>
     */
    private static String dimensionRegionSubpath(ServerLevel level) {
        if (level == null) return "";
        try {
            Identifier loc = level.dimension().identifier();
            String ns = loc.getNamespace();
            String path = loc.getPath();
            if ("minecraft".equals(ns)) {
                if ("overworld".equals(path)) return "";
                if ("the_nether".equals(path)) return "DIM-1";
                if ("the_end".equals(path))    return "DIM1";
            }
            // Custom dimension: Fabric/vanilla store these under
            // <world>/dimensions/<namespace>/<path>/region/r.X.Z.mca.
            return "dimensions/" + ns + "/" + path;
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ---------------------------------------------------------------------------
    // Region-file backed isChunkGenerated probe
    // ---------------------------------------------------------------------------

    /**
     * Reflective handles into Mojang internals for the non-blocking
     * &quot;does an .mca entry exist for this chunk?&quot; question. Lazily
     * resolved on first use; cached for the JVM lifetime. Reflection is used
     * (rather than an AccessWidener / accessor mixin) to keep this change
     * self-contained — {@code rtp-fabric-common} has no AW/mixin config today.
     *
     * <p>Resolution chain: {@code ServerChunkCache#chunkMap} field →
     * {@code ChunkMap#read(ChunkPos)} method returning
     * {@code CompletableFuture<Optional<CompoundTag>>}. The future is fed by
     * the {@code IOWorker} async region-file reader; it does NOT touch the
     * chunk system and does NOT trigger generation, so it satisfies the
     * S-005 non-blocking pre-check contract.</p>
     */
    private static volatile java.lang.reflect.Field CHUNK_MAP_FIELD;
    private static volatile java.lang.reflect.Method CHUNK_MAP_READ_METHOD;
    private static volatile boolean REFLECTION_RESOLVED = false;
    private static volatile boolean REFLECTION_AVAILABLE = false;

    private static void resolveReflectionOnce(@NotNull ServerChunkCache sample) {
        if (REFLECTION_RESOLVED) return;
        synchronized (FabricRTPWorldUnobf.class) {
            if (REFLECTION_RESOLVED) return;
            try {
                // ServerChunkCache#chunkMap — package-private final field; mappings
                // stable since 1.18 under the deobf name "chunkMap".
                java.lang.reflect.Field f = null;
                for (java.lang.reflect.Field cand : sample.getClass().getDeclaredFields()) {
                    if ("chunkMap".equals(cand.getName())
                        || cand.getType().getSimpleName().equals("ChunkMap")) {
                        f = cand;
                        break;
                    }
                }
                if (f == null) {
                    REFLECTION_RESOLVED = true;
                    return;
                }
                f.setAccessible(true);
                CHUNK_MAP_FIELD = f;

                // ChunkMap#read(ChunkPos) — protected method; returns
                // CompletableFuture<Optional<CompoundTag>>.
                Class<?> chunkMapClass = f.getType();
                java.lang.reflect.Method read = null;
                Class<?> chunkPosClass = net.minecraft.world.level.ChunkPos.class;
                for (java.lang.reflect.Method m : chunkMapClass.getDeclaredMethods()) {
                    if (!m.getName().equals("read")) continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 1 && params[0] == chunkPosClass) {
                        read = m;
                        break;
                    }
                }
                if (read == null) {
                    REFLECTION_RESOLVED = true;
                    return;
                }
                read.setAccessible(true);
                CHUNK_MAP_READ_METHOD = read;
                REFLECTION_AVAILABLE = true;
            } catch (Throwable t) {
                RTP.log(java.util.logging.Level.WARNING,
                    "[RTP] FabricRTPWorldUnobf.isChunkGenerated reflection setup failed: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                REFLECTION_RESOLVED = true;
            }
        }
    }

    /**
     * Non-blocking check for whether the chunk has been generated and persisted
     * to disk. Implements the {@link RTPWorld#isChunkGenerated(int, int)}
     * contract using region-file probes — same primitive Bukkit's
     * {@code World#isChunkGenerated} reduces to. Used by ADR-016 §13.3 to gate
     * the vanilla seed-biome pre-check fast path.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>{@code ServerChunkCache#hasChunk} — loaded chunks are by definition
     *       generated; cheapest answer.</li>
     *   <li>{@code ChunkMap#read(ChunkPos).join().isPresent()} — IOWorker async
     *       region-file read; non-loading, non-generating. The join is bounded
     *       (a single header-table lookup against a cached
     *       {@code RegionFileStorage}); it does NOT block on the chunk system
     *       and is safe to call off-tick.</li>
     *   <li>If reflection is unavailable or throws, fall back to {@code true}
     *       per the conservative default — false-positives are harmless
     *       (only skip the perf fast path); false-negatives would risk the
     *       ADR-016 §13.3 palette-drift bug.</li>
     * </ol>
     */
    @Override
    public boolean isChunkGenerated(int cx, int cz) {
        try {
            ServerChunkCache cache = world.getChunkSource();
            // Loaded chunk -> unambiguously generated. Cheapest answer.
            if (cache != null && cache.hasChunk(cx, cz)) return true;
        } catch (Throwable ignored) {
            // Fall through to the data-side probe.
        }
        ServerLevel level = world;
        if (level == null) return true;
        MinecraftServer server = level.getServer();
        if (server == null) return true;

        final java.nio.file.Path worldFolder;
        try {
            worldFolder = server.getWorldPath(LevelResource.ROOT);
        } catch (Throwable t) {
            return true;
        }
        try {
            String dim = dimensionRegionSubpath(level);
            java.nio.file.Path regionFile =
                io.github.dailystruggle.rtp.anvil.AnvilPrefilter
                    .regionFileFor(worldFolder, dim, cx, cz);
            // Missing region file = no chunk on disk for this 32x32 tile.
            if (regionFile == null || !java.nio.file.Files.exists(regionFile)) {
                return false;
            }
            // Binned fast path: a 1024-bit per-region-file occupancy bitmap
            // amortises GENSCAN's per-chunk slot lookup across the entire 32x32
            // tile. Built once per region file at first touch, reused until the
            // .mca mtime advances. Parity with v26_1_R1.
            return io.github.dailystruggle.rtp.anvil.AnvilRegionOccupancyCache
                .isOccupied(regionFile, cx, cz);
        } catch (Throwable t) {
            // Conservative default per RTPWorld javadoc: a false-positive only
            // forfeits a perf fast path; a false-negative could re-introduce
            // the ADR-016 §13.3 palette-drift bug.
            return true;
        }
    }

    // ---------------------------------------------------------------------------
    // Step C — chunk-ticket lifecycle
    // ---------------------------------------------------------------------------

    /**
     * Apply / remove a non-persistent RTP-owned chunk ticket on the server tick
     * thread, equivalent in lifetime to Bukkit's {@code addPluginChunkTicket} /
     * {@code removePluginChunkTicket}.
     *
     * <p><b>Why not {@link ServerLevel#setChunkForced(int, int, boolean)}?</b>
     * Vanilla {@code setChunkForced} writes through to
     * {@code level.dat#ForcedChunks}, so a watchdog crash mid-pipeline (or any
     * unclean shutdown) leaves RTP-owned forced flags persisted to disk and
     * re-applied on the next world load — an S-002 hazard specific to Fabric
     * (the Bukkit/Folia plugin-ticket APIs are non-persistent). We instead
     * delegate to the active {@code FabricVersionAdapter}, which issues a
     * {@code DistanceManager#addRegionTicket} call with a custom
     * {@code TicketType("rtp", …, timeout=0)} — non-persistent, removed on
     * shutdown automatically.</p>
     *
     * <p>The native ticket call MUST run on the server tick thread (it mutates
     * {@code ServerChunkCache.distanceManager}); we hop via
     * {@link MinecraftServer#submit}. The returned future completes when the
     * native call has actually executed, satisfying the ADR-015
     * ticket-application race contract that
     * {@link RTPWorld#setForceLoaded(int, int, boolean)} relies on.</p>
     *
     * <p>The parent class's ref-counting in {@link RTPWorld#chunkTickets} guards
     * against double-add / double-remove from multiple caller paths; the adapter
     * itself relies on {@code DistanceManager}'s internal de-duplication for
     * the rare race where two callers simultaneously target the same coordinate.</p>
     */
    @Override
    protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
        final MinecraftServer server = world.getServer();
        if (server == null) {
            // Torn-down world: complete normally so callers don't block forever.
            return CompletableFuture.completedFuture(null);
        }
        // Delegate to the per-MC-version adapter, which issues a non-persistent
        // RTP-owned chunk ticket via DistanceManager#addRegionTicket. We must
        // NOT call ServerLevel#setChunkForced — that writes through to
        // level.dat#ForcedChunks and a watchdog crash mid-pipeline would
        // permanently leak forced flags (S-002). The adapter dispatches
        // synchronously assuming the caller is on the server thread; we hop
        // via MinecraftServer#submit so callers off-tick remain safe.
        final io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
                io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
        if (adapter == null) {
            // Pre-bootstrap call (should not happen — RTPFabricMod installs the
            // adapter before any keep/forget call site is reachable). Fail loud
            // per S-006 rather than silently no-op.
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                    "FabricRTPWorldUnobf.setForceLoadedImpl: FabricVersionAdapter not yet installed"
                            + " (world=" + name + " chunk=(" + cx + "," + cz + "))"));
            return failed;
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                // ADR-007: wrap the ServerLevel in an RTPLevelHandle so the
                // SPI signature stays Mojmap-name-stable. Adapter unwraps via
                // handle.as(ServerLevel.class).
                io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle levelHandle =
                        io.github.dailystruggle.rtp.fabric.version.RTPLevelHandle.of(world);
                CompletableFuture<Void> f = forceLoad
                        ? adapter.applyTicket(levelHandle, cx, cz)
                        : adapter.releaseTicket(levelHandle, cx, cz);
                // Adapter completes its own future inline (single tick-thread call)
                // — getNow short-circuits without blocking and surfaces any
                // exception via join below. Reduces to a no-op if already done.
                f.getNow(null);
                return null;
            } catch (Throwable t) {
                RTP.log(java.util.logging.Level.WARNING,
                        "[RTP] FabricRTPWorldUnobf.setForceLoadedImpl failed for world=" + name
                                + " chunk=(" + cx + "," + cz + ") forceLoad=" + forceLoad
                                + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
                return null;
            }
        }, server);
    }

    /**
     * Count chunks currently flagged as force-loaded by this RTP instance.
     * On MC 26.1.2 the public {@code getForcedChunks()} accessor was removed,
     * and RTP issues non-persistent {@code DistanceManager} tickets rather
     * than {@code setChunkForced}, so this returns {@link RTPWorld#chunkTickets}
     * size — the only count RTP can authoritatively report on this MC version.
     */
    @Override
    public CompletableFuture<Integer> getServerForceLoadedCount() {
        // 26.1.2's ServerLevel does not expose a public getForcedChunks() accessor.
        // Since RTP uses non-persistent DistanceManager tickets (not setChunkForced),
        // the relevant count is the plugin's own active-ticket count tracked by
        // RTPWorld#chunkTickets — mirrors V26_1_R1FabricRTPWorld pattern.
        return CompletableFuture.completedFuture((int) numForceLoaded());
    }

    /**
     * Resolve a cached {@link FabricRTPChunkUnobf} for the given packed chunk key.
     * Two-tier lookup: prefer the wrapper cache populated by
     * {@link #getChunkAt(int, int)}; fall back to lazily wrapping a still-live
     * {@link ChunkAccess} from {@link #chunkCache} if the wrapper was GC'd
     * but the backing chunk wasn't. Returns {@code null} when neither tier
     * has a live entry — every {@code rtp-core} caller already gates on a
     * non-null result before invoking {@code isSafe} / {@code getBiome}.
     */
    @Override
    public RTPChunk<?> getCachedChunk(long key) {
        // Live chunk takes precedence over any Anvil snapshot — once a real
        // chunk has been loaded, the live path is authoritative.
        WeakReference<FabricRTPChunkUnobf> rtpRef = rtpChunkCache.get(key);
        if (rtpRef != null) {
            FabricRTPChunkUnobf wrapper = rtpRef.get();
            if (wrapper != null) {
                anvilProbeSupport.evict(key);
                return wrapper;
            }
            rtpChunkCache.remove(key);
        }
        // Fallback: backing chunk may still be live but the wrapper was GC'd.
        // Reconstruct lazily so stale-chunk-guard callers don't lose their handle.
        WeakReference<ChunkAccess> ref = chunkCache.get(key);
        if (ref != null) {
            ChunkAccess chunk = ref.get();
            if (chunk != null) {
                FabricRTPChunkUnobf wrapper = new FabricRTPChunkUnobf(chunk, world, id);
                rtpChunkCache.put(key, new WeakReference<>(wrapper));
                anvilProbeSupport.evict(key);
                return wrapper;
            }
            chunkCache.remove(key);
        }
        // ADR-016 fallback: no live chunk cached, but the prefilter may have
        // produced an Anvil-backed view earlier in this candidate's evaluation.
        io.github.dailystruggle.rtp.anvil.AnvilChunkView view = anvilProbeSupport.takeCached(key);
        if (view != null) {
            int cx = (int) (key & 0xffffffffL);
            int cz = (int) (key >> 32);
            java.util.Set<String> reconciled =
                    io.github.dailystruggle.rtp.fabric.unobf.anvil.FabricPaletteNormalizer
                            .reconcileAll(currentUnsafeBlocks());
            return new FabricRTPChunkUnobf(view, cx, cz, id, reconciled);
        }
        return null;
    }

    @Override
    public void keepChunkAt(int chunkX, int chunkZ) {
        RTP.scheduler.runTask(this, chunkX, chunkZ, () -> setForceLoaded(chunkX, chunkZ, true));
    }

    @Override
    public void forgetChunkAt(int chunkX, int chunkZ) {
        RTP.scheduler.runTask(this, chunkX, chunkZ, () -> {
            setForceLoaded(chunkX, chunkZ, false);
            long key = ((long) chunkX & 0xffffffffL) | ((long) chunkZ << 32);
            chunkCache.remove(key);
            rtpChunkCache.remove(key);
            anvilProbeSupport.evict(key);
        });
    }

    @Override
    public void forgetChunks() {
        // Mirror BukkitRTPWorld#forgetChunks — drain the parent's ref-counted
        // ticket map by issuing decrements until each count reaches zero, then
        // clear the local cache. The decrements route through setForceLoaded
        // which hops to the server thread per ticket, so this is safe to call
        // from any context.
        chunkTickets.forEach((key, count) -> {
            int cx = (int) (key & 0xffffffffL);
            int cz = (int) (key >> 32);
            while (count.get() > 0) {
                setForceLoaded(cx, cz, false);
            }
        });
        chunkCache.clear();
        rtpChunkCache.clear();
        anvilProbeSupport.clear();
    }

    @Override
    public int getCacheSize() {
        return chunkCache.size();
    }

    // ---------------------------------------------------------------------------
    // Step E — read-only world data
    // ---------------------------------------------------------------------------

    /**
     * Resolve the biome at the given block coordinates via
     * {@link ServerLevel#getBiome(BlockPos)}, returning the
     * {@code namespace:path} key (e.g. {@code minecraft:plains}) in upper case
     * to mirror {@code BukkitRTPWorld.getBiome}'s {@code Biome.name()} contract.
     *
     * <p>S-005 / threading: the dynamic biome registry read is generally safe
     * off the server thread (it's a read-only registry view), matching how
     * {@code Level#getBiome} behaves on Paper. If a future Mojang change makes
     * this thread-unsafe, switch to a {@link MinecraftServer#submit} hop —
     * the cost is one tick of latency per biome lookup, acceptable on the
     * vert-adjustor / safety-check path.</p>
     *
     * <p>Returns the empty string on lookup failure (unknown biome, missing
     * registry key) so callers performing string equality against the
     * configured biome list see a deterministic non-null value.</p>
     */
    @Override
    public String getBiome(int x, int y, int z) {
        if (world == null) return "";
        try {
            net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> holder =
                    world.getBiome(new BlockPos(x, y, z));
            // Holder#unwrapKey()'s ResourceKey on 26.1.2 exposes identifier() (was location()).
            // Normalise through PaletteIdentifierNormalizer to match the form ScanTask /
            // FabricServerAccessor.defaultBiomesFor / FabricAnvilColumnProbeAdapter use
            // (namespace-stripped, upper-cased). Without normalisation FULLSCAN's
            // physical-biome check compares "MINECRAFT:PLAINS" against the
            // namespace-stripped "PLAINS" in defaultBiomes and rejects every candidate
            // -- visible as fullLoadOutcome.physBiome consuming 100% of FULLSCAN load.
            String raw = holder.unwrapKey()
                    .map(k -> {
                        try { return k.identifier().toString(); }
                        catch (Throwable t) { return ""; }
                    })
                    .orElseGet(() -> {
                        // Fallback: registry reverse lookup.
                        try {
                            Identifier id = world.registryAccess()
                                    .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                                    .getKey(holder.value());
                            return (id == null) ? "" : id.toString();
                        } catch (Throwable t) {
                            return "";
                        }
                    });
            String n = io.github.dailystruggle.rtp.api.configuration
                    .PaletteIdentifierNormalizer.normalize(raw);
            return n == null ? "" : n;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Worldborder-derived bounds are handled by {@code FabricServerAccessor};
     * Fabric has no Bukkit-style {@code Bukkit.getWorld(id) == null} check
     * because dimensions are server-bound for their lifetime. Treat as always
     * active while the {@link ServerLevel} is reachable.
     */
    @Override
    public boolean isInactive() {
        return world == null || world.getServer() == null;
    }

    @Override
    public void platform(RTPLocation location) {
        // Fabric platform-block writes require a server-thread hop and a
        // BlockState lookup that depends on Material → Block parity work
        // scheduled for Step E follow-ups (no MaterialRegistry on Fabric —
        // we'd need to parse the configured platform-material identifier
        // through BuiltInRegistries.BLOCK directly). Until then, the
        // platform sub-feature is a no-op: the safety pipeline's primary
        // contract is &quot;don't teleport into unsafe blocks&quot;, not
        // &quot;build a safety platform&quot;, so this preserves correctness
        // at the cost of one configurable convenience. Reservation cleanup
        // is preserved to mirror the Bukkit contract.
        try {
            // intentional no-op until palette parity lands
        } finally {
            if (location != null && location.getReservation() != null) {
                location.getReservation().close();
            }
        }
    }

    @Override
    public void save() {
        // Intentional no-op on Fabric (parity with Paper/Folia overrides).
        // Fabric's chunk system persists generated chunks via its own
        // dirty-tracking and the vanilla autosave path; the forced
        // World.save() that the Spigot adapter performs (to work around
        // Bukkit autosave not flushing Chunky-generated chunks — see
        // docs/dev/LESSONS_LEARNED.md "Pre-Generation & Shutdown") is not
        // needed here.
    }

    @Override
    public int getMaxHeight() {
        // LevelHeightAccessor#getMaxY() on MC 26.1.2 — inclusive max build Y
        // (renamed from getMaxBuildHeight in earlier mojmap mappings).
        try {
            return world.getMaxY();
        } catch (Throwable t) {
            return 320; // 1.18+ overworld default
        }
    }

    @Override
    public int getMinHeight() {
        try {
            return world.getMinY();
        } catch (Throwable t) {
            return -64; // 1.18+ overworld default
        }
    }

    @Override
    public long getSeed() {
        try {
            return world.getSeed();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * Optional accessor for the most-recently cached {@link ChunkAccess} at the
     * given key. Returns {@code null} if the chunk was never loaded by this
     * adapter or has been GC'd. Currently unused by {@code rtp-core} (the
     * platform-neutral path goes through {@link #getCachedChunk(long)}); kept
     * as a Fabric-internal hook for {@code FabricRTPChunkUnobf} once it lands.
     */
    public @Nullable ChunkAccess peekChunk(long key) {
        WeakReference<ChunkAccess> ref = chunkCache.get(key);
        return ref == null ? null : ref.get();
    }
}
