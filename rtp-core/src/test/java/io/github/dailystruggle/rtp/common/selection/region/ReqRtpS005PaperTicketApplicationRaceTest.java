package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-S-005 / ADR-015 (Paper chunk-system-v2 ticket-application race follow-up).
 *
 * <p>The companion test {@link ReqRtpS005PaperStaleGuardFalsePositiveTest} assumed
 * {@code setForceLoadedImpl(true)} applies its plugin chunk ticket synchronously —
 * that assumption is true for Spigot when the call already runs on the primary
 * thread, but it is <b>not</b> true for the real failure the user reported on
 * Paper 26.1.
 *
 * <p>On Paper the location generator runs on {@code Craft Scheduler Thread - 1 - RTP}
 * (async) and {@code BukkitRTPWorld.setForceLoadedImpl} therefore schedules the
 * raw {@code addPluginChunkTicket} call via {@code Bukkit.getScheduler().runTask(...)}
 * — i.e. onto the <b>next</b> primary-thread tick. Without the ADR-015 ticket-
 * application follow-up, {@code new ChunkReservation(...)} returns before the
 * scheduled task fires, and the subsequent stale-chunk guard sees
 * {@code isChunkLoaded=false} because Paper chunk-system-v2 has already GC'd the
 * async-returned chunk. Every candidate is then rejected with
 * {@code cause=nullChunk reason=staleChunkBeforeVert fails=N}.
 *
 * <p>This test installs a deferred-apply mock world that reproduces exactly that
 * mechanic: {@code setForceLoadedImpl(true)} returns an <b>incomplete</b> future
 * and <b>does not</b> increment the active ticket counter until a background
 * "primary-thread simulator" drains the pending-apply queue. The test then
 * asserts that {@link LocationGenerator#getLocation(Region, Set)}:
 *
 * <ol>
 *   <li>Blocks in {@link io.github.dailystruggle.rtp.api.world.ChunkReservation#awaitReady(long, TimeUnit) reservation.awaitReady(...)}
 *       until the background thread drains the queue.</li>
 *   <li>Only then proceeds past the stale-chunk guard and invokes
 *       {@code MockRTPChunk.isSafe} — proving the guard sees the ticket as
 *       applied (as opposed to the Paper production reproduction where it did
 *       not).</li>
 *   <li>Releases every ticket on exit via {@code finally { reservation.close(); }}
 *       — the active ticket counter must drain to zero after the background
 *       thread runs the release-side apply callbacks too.</li>
 * </ol>
 *
 * <p>Regression intent: without the {@code awaitReady} call in
 * {@link LocationGenerator#getLocation(Region, Set)}, the stale-chunk guard
 * would fire before the background drain and the test would fail exactly as the
 * user's Paper 26.1 log showed {@code fails=10 reason=staleChunkBeforeVert}.
 */
@DisplayName("REQ-RTP-S-005 / ADR-015: Paper chunk-system-v2 ticket-application race is covered by awaitReady")
public class ReqRtpS005PaperTicketApplicationRaceTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private DeferredApplyWorld world;
    private Region region;
    private ExecutorService primaryThreadSimulator;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());

        world = new DeferredApplyWorld("paper_ticket_apply_world");
        accessor.addWorld(world);

        // Paper chunk-system-v2 simulation: the chunk is "loaded" only while the
        // plugin chunk ticket has actually been applied on the primary thread —
        // i.e. only after the DeferredApplyWorld's drain has run. Before the
        // drain runs, isChunkLoaded returns false, which is the exact state the
        // production stale-chunk guard saw in the user's Paper 26.1 log.
        world.isChunkLoadedPredicate = key -> world.getActiveTicketCount() > 0;

        // Force safetyCheck loop to run.
        @SuppressWarnings("unchecked")
        ConfigParser<SafetyKeys> safety =
                (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        try {
            java.lang.reflect.Field dataField = FactoryValue.class.getDeclaredField("data");
            dataField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.EnumMap<SafetyKeys, Object> data =
                    (java.util.EnumMap<SafetyKeys, Object>) dataField.get(safety);
            data.put(SafetyKeys.safetyRadius, 1);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to override safetyRadius for test", e);
        }

        Circle circle = new Circle();
        circle.setRng(new Random(42L));
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "paper_ticket_apply_region",
                world,
                circle,
                vert,
                false,
                false,
                10L,
                5,
                0.0,
                1L,
                "",
                false);

        region = new Region("paper_ticket_apply_region", settings);

        // Background "primary-thread simulator": continuously drains the world's
        // pending-apply queue, completing the futures returned by
        // setForceLoadedImpl and applying the +/- 1 to activeTicketCount. This
        // is analogous to Paper's primary thread polling the BukkitScheduler.
        primaryThreadSimulator = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "PaperPrimaryThreadSimulator");
            t.setDaemon(true);
            return t;
        });
        primaryThreadSimulator.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    DeferredApplyWorld.PendingApply task = world.pendingApplies.poll(
                            50L, TimeUnit.MILLISECONDS);
                    if (task != null) {
                        task.apply();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (primaryThreadSimulator != null) {
            primaryThreadSimulator.shutdownNow();
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @DisplayName("Paper v2 ticket-application race: awaitReady blocks until drain completes, then isSafe runs")
    void await_ready_bridges_deferred_ticket_application() {
        LocationGenerator.setRng(new Random(42L));

        GenerationResult result = LocationGenerator.getLocation(region, (Set<String>) null);

        // With reservation.awaitReady(2s) bracketing the stale guard (ADR-015
        // Paper follow-up), the location generator thread waits for the
        // DeferredApplyWorld drain to complete the apply future BEFORE the
        // guard reads isChunkLoaded. activeTicketCount is therefore >0 when
        // the guard runs, the guard passes, and block evaluation fires. In the
        // pre-fix state the guard would run first (with activeTicketCount=0)
        // and reject every candidate — exactly the user's Paper 26.1 log.
        assertTrue(world.isSafeCallCount.get() > 0,
                "ADR-015 ticket-application race: with awaitReady the pregen path "
                        + "must wait for the deferred addPluginChunkTicket to complete "
                        + "before the stale-chunk guard reads isChunkLoaded; "
                        + "MockRTPChunk.isSafe should then run at least once. Zero "
                        + "invocations indicates the fix regressed and Paper 26.1's "
                        + "staleChunkBeforeVert=N state is reachable again.");

        // Confirm the drain actually fired the apply callbacks (otherwise the
        // test is only verifying the fast-path). If this fails the mock is
        // wrong, not the production code.
        assertTrue(world.applyInvocations.get() >= 1,
                "Test-harness self-check: the deferred-apply drain must have run "
                        + "at least once; if this assertion fails the mock did not "
                        + "simulate the Paper race.");

        // REQ-RTP-S-002 bounded scope: every reservation.close() must also be
        // drained by the primary-thread simulator. Give the drain 1s to catch
        // up with the release-side applies before asserting.
        long deadline = System.currentTimeMillis() + 1_000L;
        while (world.getActiveTicketCount() != 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertEquals(0, world.getActiveTicketCount(),
                "REQ-RTP-S-002: every ChunkReservation opened by "
                        + "LocationGenerator.getLocation(Region, Set<String>) must be "
                        + "released before the method returns (the release side also "
                        + "routes through the deferred-apply queue on this mock).");
    }

    /**
     * {@link TrackedMockWorld} subclass whose {@code setForceLoadedImpl} simulates
     * Paper's deferred (main-thread-scheduled) plugin chunk ticket application.
     *
     * <p>Each invocation enqueues a {@link PendingApply} task; the background
     * "primary-thread simulator" drains the queue and applies the +/- 1 to the
     * active ticket counter, then completes the apply future. Until the drain
     * runs, the returned future is incomplete and the counter is unchanged —
     * exactly mirroring the state the real {@code BukkitRTPWorld} off-thread
     * branch leaves the world in between enqueue and main-thread execution.
     */
    static final class DeferredApplyWorld extends TrackedMockWorld {

        /** Count of times {@link PendingApply#apply()} has fired. */
        final AtomicInteger applyInvocations = new AtomicInteger();

        /** FIFO of apply tasks awaiting "primary-thread" execution. */
        final BlockingQueue<PendingApply> pendingApplies = new ArrayBlockingQueue<>(256);

        DeferredApplyWorld(String name) {
            super(name);
        }

        @Override
        protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
            // DO NOT touch the parent counter here — the counter must only move
            // after the deferred apply runs, otherwise the mock would degenerate
            // to the synchronous path and hide the Paper race we want to catch.
            CompletableFuture<Void> future = new CompletableFuture<>();
            PendingApply task = new PendingApply(forceLoad, future);
            if (!pendingApplies.offer(task)) {
                future.completeExceptionally(
                        new IllegalStateException("pendingApplies queue full"));
            }
            return future;
        }

        /** A deferred apply task that moves the parent ticket counter and completes its future. */
        final class PendingApply {
            final boolean forceLoad;
            final CompletableFuture<Void> future;

            PendingApply(boolean forceLoad, CompletableFuture<Void> future) {
                this.forceLoad = forceLoad;
                this.future = future;
            }

            void apply() {
                applyInvocations.incrementAndGet();
                // Mirror the parent TrackedMockWorld.setForceLoadedImpl effect
                // now that the "primary thread" is running the callback.
                if (forceLoad) {
                    incrementActiveTickets();
                } else {
                    decrementActiveTickets();
                }
                future.complete(null);
            }
        }

        /* Bridge to parent private field via test-visible helpers. */
        private void incrementActiveTickets() {
            super.setForceLoadedImpl_syncCounterForTesting(true);
        }

        private void decrementActiveTickets() {
            super.setForceLoadedImpl_syncCounterForTesting(false);
        }
    }
}
