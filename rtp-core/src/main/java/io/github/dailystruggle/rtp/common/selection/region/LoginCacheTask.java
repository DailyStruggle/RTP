package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.logging.Level;

/**
 * ADR-023 — Login Reserve Cache promotion task.
 *
 * <p>Mirrors the unkept→kept promotion block in {@link Region#execute(long)},
 * but writes into {@link RegionQueueManager#loginLocations} instead of
 * {@link RegionQueueManager#keptLocations}. This task runs on its own
 * behavioral loop (startup burst + {@code PlayerQuitEvent} refill) and shares
 * no budget or state with {@code Region.execute()} beyond the source
 * {@code unkeptLocations} buffer.
 *
 * <p>Safety invariants (parallel to existing kept promotion, S-005 compliant):
 * <ul>
 *   <li>{@link LockFreeLocationBuffer#pollSilently()} on the source — the same
 *       composite DB key is re-offered to a kept-style buffer; firing the
 *       delete callback here would race with the destination's save callback
 *       and net out to a row loss across restarts.</li>
 *   <li>Async chunk load via {@code RTPWorld.getChunkAtAsync}.</li>
 *   <li>Second-pass {@code isSafe} verification on the chunk's owning region
 *       thread (Folia) or inline (Spigot/Paper). Fail-closed on any verify
 *       exception.</li>
 *   <li>Reservation lifecycle: created on success, closed on rejection or
 *       unsafe verdict. {@link Region#inFlightCalculations} accounts for the
 *       work in-flight.</li>
 * </ul>
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
                        boolean safe = false;
                        try {
                            long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
                            io.github.dailystruggle.rtp.api.world.RTPChunk<?> rtpChunk =
                                    region.getWorld().getCachedChunk(key);
                            if (rtpChunk != null) {
                                int localX = coldLoc.coords().x() - (cx << 4);
                                int localZ = coldLoc.coords().z() - (cz << 4);
                                localX = ((localX % 16) + 16) % 16;
                                localZ = ((localZ % 16) + 16) % 16;
                                int y = coldLoc.coords().y();
                                safe = rtpChunk.isSafe(localX, y, localZ, LocationGenerator.unsafeBlocksCache);
                            }
                        } catch (Throwable verifyEx) {
                            safe = false;
                            RTP.log(Level.FINE,
                                    "[LoginCacheTask] unkept→login safety re-verification failed: "
                                            + verifyEx.getClass().getSimpleName() + ": " + verifyEx.getMessage());
                        }

                        if (!safe) {
                            // Mirror Region.execute(): purge the now-unsafe DB row via offer+poll.
                            qm.unkeptLocations.offer(coldLoc);
                            qm.unkeptLocations.poll();
                            return;
                        }

                        ChunkReservation reservation = new ChunkReservation(chunkSet, region.getWorld());
                        boolean added = login.offer(
                                new RTPLocation(coldLoc.coords(), coldLoc.attempts(), reservation));
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
