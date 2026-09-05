package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.logging.Level;

/**
 * Promotes unkept coordinates into {@link RegionQueueManager#loginLocations} (ADR-023).
 *
 * <p>S-005 compliant: asynchronous chunk load with regional safety verification (S-001).
 * Uses {@link LockFreeLocationBuffer#pollSilently()} to prevent DB deletion races on promotion.
 */
public final class LoginCacheTask implements Runnable {
    private final Region region;

    public LoginCacheTask(Region region) {
        this.region = region;
    }

    /**
     * Promote up to {@code count} entries from {@code unkeptLocations} into
     * {@code loginLocations}. Async; returns immediately. Each promotion is
     * accounted via {@link Region#inFlightCalculations}.
     */
    public void promoteUpTo(int count) {
        if (region.getWorld() == null) return;
        LockFreeLocationBuffer login = region.queueManager.loginLocations;
        if (login == null) return;

        for (int i = 0; i < count; i++) {
            if (login.size() + region.inFlightCalculations.get() >= effectiveCap()) break;
            promoteOne();
        }
    }

    @Override
    public void run() {
        promoteUpTo(1);
    }

    private int effectiveCap() {
        LockFreeLocationBuffer login = region.queueManager.loginLocations;
        if (login == null) return 0;
        // The buffer's own capacity is set at allocation to the configured login cap.
        // size() + inFlight already bounds growth; rely on offer(...) returning false
        // if exceeded.
        return Integer.MAX_VALUE;
    }

    private static boolean isFoliaPlatform() {
        try {
            return "Folia".equals(RTP.serverAccessor.getPlatform());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void promoteOne() {
        RegionQueueManager qm = region.queueManager;
        LockFreeLocationBuffer login = qm.loginLocations;
        if (login == null) return;

        // pollSilently: identical DB-key re-offer race protection as Region.execute().
        RTPLocation coldLoc = qm.unkeptLocations.pollSilently();
        if (coldLoc == null) return;

        region.inFlightCalculations.incrementAndGet();
        int cx = coldLoc.coords().x() >> 4;
        int cz = coldLoc.coords().z() >> 4;

        region.getWorld().recordChunkLoadOrigin("LoginCacheTask.promote");
        region.getWorld().getChunkAtAsync(cx, cz).thenAccept(chunkSet -> {
            chunkSet.complete().whenComplete((success, throwable) -> {
                if (success == null || !success) {
                    try {
                        qm.unkeptLocations.offer(coldLoc);
                    } finally {
                        region.inFlightCalculations.decrementAndGet();
                    }
                    return;
                }
                Runnable verify = () -> {
                    try {
                        // Re-resolve Y via the region's vertical adjustor against the
                        // loaded chunk. Mirrors Region.execute() unkept->kept promotion:
                        // the stored coords.y may be a backlog placeholder (ADR-028), and a
                        // single-block isSafe at a mid-air placeholder Y passes trivially
                        // (S-001 mid-air placement bug). vert.adjust(chunk) performs the
                        // ground+headroom sweep every adjustor already implements on the
                        // live-spiral path.
                        io.github.dailystruggle.rtp.api.world.RTPCoords resolved = null;
                        try {
                            long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
                            io.github.dailystruggle.rtp.api.world.RTPChunk<?> rtpChunk =
                                    region.getWorld().getCachedChunk(key);
                            io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor<?> v =
                                    region.getVert();
                            if (rtpChunk != null && v != null) {
                                resolved = v.adjust(rtpChunk);
                            }
                        } catch (Throwable verifyEx) {
                            resolved = null;
                            RTP.log(Level.FINE,
                                    "[LoginCacheTask] unkept->login safety re-verification failed: "
                                            + verifyEx.getClass().getSimpleName() + ": " + verifyEx.getMessage());
                        }

                        if (resolved == null) {
                            // Mirror Region.execute(): purge the now-unsafe DB row via offer+poll.
                            qm.unkeptLocations.offer(coldLoc);
                            qm.unkeptLocations.poll();
                            return;
                        }

                        ChunkReservation reservation = new ChunkReservation(chunkSet, region.getWorld());
                        boolean added = login.offer(
                                new RTPLocation(resolved, coldLoc.attempts(), reservation));
                        if (!added) {
                            reservation.close();
                            qm.unkeptLocations.offer(coldLoc);
                            RTP.log(Level.FINE,
                                    "[LoginCacheTask] login cache full; returned to unkept");
                        } else {
                            RTP.log(Level.FINE,
                                    "[LoginCacheTask] promoted to login cache; size="
                                            + login.size());
                        }
                    } finally {
                        region.inFlightCalculations.decrementAndGet();
                    }
                };
                if (isFoliaPlatform()) {
                    RTP.scheduler.runTask(region.getWorld(), cx, cz, verify);
                } else {
                    verify.run();
                }
            });
        }).exceptionally(throwable -> {
            region.inFlightCalculations.decrementAndGet();
            qm.unkeptLocations.offer(coldLoc);
            return null;
        });
    }
}
