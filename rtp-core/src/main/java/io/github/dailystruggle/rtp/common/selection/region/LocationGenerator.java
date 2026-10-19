package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.world.*;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tools.CfDiag;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Non-blocking {@link ILocationGenerator} implementation (ADR-015).
 * Outer attempt loop is an async state machine ({@link PregenTask} / {@link QueueTask})
 * chaining via {@link CompletableFuture} to prevent blocking worker threads.
 */
public class LocationGenerator implements ILocationGenerator {

    /** RNG used for biome-recall entry selection. */
    static Random rng = null;

    /** Returns the active RNG, falling back to {@link ThreadLocalRandom#current()}. */
    @SuppressWarnings("java:S1845") // Accessor method name mirrors static field
    static Random rng() {
        return rng != null ? rng : ThreadLocalRandom.current();
    }

    /**
     * Injects a deterministic RNG. Pass {@code null} to restore {@link ThreadLocalRandom}
     * behaviour. Intended for unit tests only.
     */
    public static void setRng(Random rng) {
        LocationGenerator.rng = rng;
    }

    // Package-private shared state used by QueueTask's safetyCheck refresh block.
    static final Set<String> unsafeBlocksCache = new ConcurrentSkipListSet<>();
    static final AtomicLong lastUpdate = new AtomicLong(0);
    static final AtomicInteger safetyRadiusCache = new AtomicInteger(0);
    /**
     * Mirrors {@code SafetyKeys.platformDepth} for {@code QueueTask.runSafetyScan} ground
     * re-validation on live chunks, preventing drift between probe and live block state.
     */
    static final AtomicInteger platformDepthCache = new AtomicInteger(1);


    /** Timeout for the sync-shim join-on-async-future. Tests run well under this. */
    private static final long SYNC_SHIM_TIMEOUT_SECONDS = 60L;


    // ==========================================================================
    // ILocationGenerator async interface - primary entry points
    // ==========================================================================

    @Override
    public CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context) {
        if (!(region instanceof Region r)) return CompletableFuture.completedFuture(null);
        return getLocationFuture(r, context.sender(), context.player(), context.biomeNames());
    }

    @Override
    public CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context) {
        if (!(region instanceof Region r)) return CompletableFuture.completedFuture(null);
        return getLocationFuture(r, context.biomeNames());
    }

    @Override
    public CompletableFuture<GenerationResult> getLocation(
            Object region, RTPCommandSender sender, RTPPlayer player, @Nullable Set<String> biomeNames) {
        if (!(region instanceof Region r)) return CompletableFuture.completedFuture(null);
        return getLocationFuture(r, sender, player, biomeNames);
    }

    @Override
    public CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames) {
        if (!(region instanceof Region r)) return CompletableFuture.completedFuture(null);
        return getLocationFuture(r, biomeNames);
    }

    // ==========================================================================
    // Deprecated static sync shims - retained for test + internal back-compat
    // Per DEPRECATION_POLICY.md: synchronous blocking shims violate S-005.
    // Use async getLocationFuture(...) or ILocationGenerator methods instead.
    // ==========================================================================

    /**
     * Synchronous blocking shim.
     *
     * @param region region to generate from
     * @param context generation context
     * @return generation result
     * @deprecated As of 3.1.0, forRemoval = true. Synchronous chunk I/O or waiting on
     *             the main thread violates prohibition S-005. Use
     *             {@link #getLocation(Object, GenerationContext)} or
     *             {@link #getLocationFuture(Region, RTPCommandSender, RTPPlayer, Set)} instead.
     *             Scheduled for removal in milestone 4.0.0.
     */
    @Deprecated(since = "3.1.0", forRemoval = true)
    public static GenerationResult getLocation(Region region, GenerationContext context) {
        return joinSafely(getLocationFuture(region, context.sender(), context.player(), context.biomeNames()));
    }

    /**
     * Synchronous blocking shim.
     *
     * @param region region to generate from
     * @param context generation context
     * @return generation result
     * @deprecated As of 3.1.0, forRemoval = true. Synchronous chunk I/O or waiting on
     *             the main thread violates prohibition S-005. Use
     *             {@link #generateLocation(Object, GenerationContext)} or
     *             {@link #getLocationFuture(Region, Set)} instead.
     *             Scheduled for removal in milestone 4.0.0.
     */
    @Deprecated(since = "3.1.0", forRemoval = true)
    public static GenerationResult generateLocation(Region region, GenerationContext context) {
        return joinSafely(getLocationFuture(region, context.biomeNames()));
    }

    /**
     * Synchronous blocking shim.
     *
     * @param region region to generate from
     * @param sender command sender
     * @param player player to teleport
     * @param biomeNames targeted biomes
     * @return generation result
     * @deprecated As of 3.1.0, forRemoval = true. Synchronous chunk I/O or waiting on
     *             the main thread violates prohibition S-005. Use
     *             {@link #getLocation(Object, RTPCommandSender, RTPPlayer, Set)} or
     *             {@link #getLocationFuture(Region, RTPCommandSender, RTPPlayer, Set)} instead.
     *             Scheduled for removal in milestone 4.0.0.
     */
    @Deprecated(since = "3.1.0", forRemoval = true)
    public static GenerationResult getLocation(
            Region region, RTPCommandSender sender, RTPPlayer player, @Nullable Set<String> biomeNames) {
        return joinSafely(getLocationFuture(region, sender, player, biomeNames));
    }

    /**
     * Synchronous blocking shim.
     *
     * @param region region to generate from
     * @param biomeNames targeted biomes
     * @return generation result
     * @deprecated As of 3.1.0, forRemoval = true. Synchronous chunk I/O or waiting on
     *             the main thread violates prohibition S-005. Use
     *             {@link #getLocation(Object, Set)} or
     *             {@link #getLocationFuture(Region, Set)} instead.
     *             Scheduled for removal in milestone 4.0.0.
     */
    @Deprecated(since = "3.1.0", forRemoval = true)
    @Nullable
    public static GenerationResult getLocation(Region region, @Nullable Set<String> biomeNames) {
        return joinSafely(getLocationFuture(region, biomeNames));
    }

    @SuppressWarnings("BlockingMethodInNonBlockingContext")
    private static GenerationResult joinSafely(CompletableFuture<GenerationResult> f) {
        try {
            return f.get(SYNC_SHIM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            RTP.log(Level.WARNING, "[RTP] LocationGenerator sync shim interrupted");
            return null;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            RTP.log(Level.WARNING, "[RTP] LocationGenerator async task failed: " + cause, cause);
            return null;
        } catch (TimeoutException te) {
            RTP.log(Level.WARNING, "[RTP] LocationGenerator sync shim timed out after " + SYNC_SHIM_TIMEOUT_SECONDS + "s");
            return null;
        }
    }

    // ==========================================================================
    // Async primary implementations
    // ==========================================================================

    /**
     * Pregen path: generate a fresh candidate (used by {@link RegionCacheTask},
     * {@code /rtp biome:...}, and the queue-path UNQUEUED fast-path).
     */
    public static CompletableFuture<GenerationResult> getLocationFuture(
            Region region, @Nullable Set<String> biomeNames) {
        return getLocationFuture(region, biomeNames, LoadPurpose.IMMEDIATE);
    }

    /**
     * Pregen path with an explicit {@link LoadPurpose} (ADR-110). {@link LoadPurpose#SPECULATIVE}
     * verifies from resident chunks and region-file reads first; a native load returns a pinned
     * result (with a reservation the caller must hand to the kept queue or close) when a kept slot
     * is free, else an unpinned result without the success ring.
     */
    public static CompletableFuture<GenerationResult> getLocationFuture(
            Region region, @Nullable Set<String> biomeNames, LoadPurpose purpose) {
        return getLocationFuture(region, biomeNames, purpose, null);
    }

    /**
     * As above; with a non-null {@code backlog}, each attempt takes the backlog's oldest UNKNOWN
     * entry instead of a shape draw (ADR-028). The shape is drawn only while the backlog is empty;
     * the run completes without coordinates when entries exist but none is UNKNOWN.
     */
    static CompletableFuture<GenerationResult> getLocationFuture(
            Region region, @Nullable Set<String> biomeNames, LoadPurpose purpose,
            @Nullable BacklogLocationBuffer backlog) {
        CfDiag.locationGenPregenEntry.increment();
        CfDiag.ensureStarted();
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        try {
            PregenState state = PregenState.build(region, biomeNames);
            if (state == null) {
                // Malformed region: return null (preserves prior contract).
                result.complete(null);
                return result;
            }
            state.purpose = (purpose == null) ? LoadPurpose.IMMEDIATE : purpose;
            state.backlog = backlog;
            PregenTask task = new PregenTask(state, result, 1L);
            RTP.serverAccessor.getScheduler().runTaskAsynchronously(task);
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] LocationGenerator pregen dispatch failed: " + t, t);
            result.complete(null);
        }
        return result;
    }

    /**
     * Queue path: consumes pre-generated result from {@code region.queueManager.poll}
     * or dispatches generation/enqueuing via {@link QueueTask}.
     */
    public static CompletableFuture<GenerationResult> getLocationFuture(
            Region region, RTPCommandSender sender, RTPPlayer player, @Nullable Set<String> biomeNames) {
        CfDiag.locationGenQueueEntry.increment();
        CfDiag.ensureStarted();
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        try {
            new QueueTask(region, sender, player, biomeNames, result).start();
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] LocationGenerator queue dispatch failed: " + t, t);
            result.complete(null);
        }
        return result;
    }
}
