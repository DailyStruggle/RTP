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

    /**
     * ADR-016 section 13.3 (2026-04-20 revision): pre-chunk-load biome pre-check disabled by
     * default (preserved as dead code for a potential future biome-targeted workload).
     */
    private static final boolean PRE_CHUNK_BIOME_PRECHECK_ENABLED = false;

    /** Timeout for the sync-shim join-on-async-future. Tests run well under this. */
    private static final long SYNC_SHIM_TIMEOUT_SECONDS = 60L;

    public enum FailTypes {
        biome,
        worldBorder,
        timeout,
        vert,
        safety,
        safetyExternal,
        nullChunk,
        /** Probe fast path rejected the candidate because the center-column biome
         * didn't match the allow/deny filter. Accounts probe-driven biome misses
         * separately from post-chunk-load {@link #biome} misses so operators can
         * see how much work the fast path is saving. */
        prefilterBiome,
        /** Probe fast path rejected the candidate because the center-column block
         * at the selected Y was unsafe (water / lava / caller-configured unsafe set). */
        prefilterBlock,
        /** Probe fast path rejected the candidate because the vertical adjustor
         * found no acceptable Y within the probe's window (either the heightmap
         * was outside {@code [minY, maxY]} or every Y-step failed the adjustor's
         * placement test). */
        prefilterRange,
        /** Candidate chunk's column is not yet generated on disk and the
         * configured {@code PerformanceKeys.pregeneratedPreference} weight
         * drew a reject this attempt. Tracked separately so operators can see
         * how often the pregenerated-area bias dropped a candidate vs. the
         * other reject buckets. */
        ungenerated,
        /** Candidate location (and, for a {@code uniquePlacements} radius &gt; 1,
         * the surrounding chunks) was marked bad to enforce unique placements:
         * a successfully-used spot is consumed so the same area is not handed
         * out again. Tracked separately from {@link #misc} so operators can see
         * how much of the region the unique-placements knob has retired. */
        uniquePlacement,
        misc
    }

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
    // ==========================================================================

    @Deprecated
    public static GenerationResult getLocation(Region region, GenerationContext context) {
        return joinSafely(getLocationFuture(region, context.sender(), context.player(), context.biomeNames()));
    }

    @Deprecated
    public static GenerationResult generateLocation(Region region, GenerationContext context) {
        return joinSafely(getLocationFuture(region, context.biomeNames()));
    }

    @Deprecated
    public static GenerationResult getLocation(
            Region region, RTPCommandSender sender, RTPPlayer player, @Nullable Set<String> biomeNames) {
        return joinSafely(getLocationFuture(region, sender, player, biomeNames));
    }

    @Deprecated
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
