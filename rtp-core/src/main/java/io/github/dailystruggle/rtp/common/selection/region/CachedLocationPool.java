package io.github.dailystruggle.rtp.common.selection.region;

import java.util.concurrent.ConcurrentLinkedQueue;

public class CachedLocationPool {
    private static final ConcurrentLinkedQueue<CachedLocation> pool = new ConcurrentLinkedQueue<>();

    public static CachedLocation acquire(io.github.dailystruggle.rtp.api.world.RTPCoords coords, long attempts) {
        CachedLocation loc = pool.poll();
        if (loc == null) {
            return new CachedLocation(coords, attempts);
        }
        loc.setCoords(coords);
        loc.setAttempts(attempts);
        return loc;
    }

    public static void release(CachedLocation loc) {
        if (loc == null) return;
        loc.setCoords(null);
        pool.offer(loc);
    }
}
