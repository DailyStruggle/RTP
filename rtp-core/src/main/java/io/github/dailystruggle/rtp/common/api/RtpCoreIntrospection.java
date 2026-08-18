package io.github.dailystruggle.rtp.common.api;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionQueueManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Read-only introspection facade over RTP's live region and cache state.
 *
 * <p>Exposes unstable internal state for GUI and dashboard integrations without chunk I/O.</p>
 */
public final class RtpCoreIntrospection {

    private RtpCoreIntrospection() {
    }

    /**
     * @return an immutable list of {@link RegionQueueDepths}, one per currently
     * registered region (both permanent and temporary). Empty if core has not
     * loaded yet. Never {@code null}.
     */
    public static List<RegionQueueDepths> queueDepths() {
        RTP rtp = RTP.getInstance();
        if (rtp == null || RTP.selectionAPI == null) {
            return Collections.emptyList();
        }
        List<RegionQueueDepths> out = new ArrayList<>();
        try {
            for (Region region : RTP.selectionAPI.permRegionLookup.values()) {
                RegionQueueDepths d = depthsOf(region);
                if (d != null) out.add(d);
            }
            for (Region region : RTP.selectionAPI.tempRegions.values()) {
                RegionQueueDepths d = depthsOf(region);
                if (d != null) out.add(d);
            }
        } catch (Throwable ignored) {
            // Introspection must never throw into a caller's render loop.
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * @param regionName canonical region name to look up; {@code null} yields
     *                   {@code null}.
     * @return the {@link RegionQueueDepths} for {@code regionName}, or
     * {@code null} if core has not loaded or no such region is registered.
     */
    public static RegionQueueDepths queueDepths(String regionName) {
        if (regionName == null) return null;
        RTP rtp = RTP.getInstance();
        if (rtp == null || RTP.selectionAPI == null) return null;
        try {
            Region region = RTP.selectionAPI.permRegionLookup.get(regionName);
            if (region != null) return depthsOf(region);
            for (Region temp : RTP.selectionAPI.tempRegions.values()) {
                if (temp != null && regionName.equals(temp.name)) {
                    return depthsOf(temp);
                }
            }
        } catch (Throwable ignored) {
            // Introspection must never throw into a caller's render loop.
        }
        return null;
    }

    /**
     * Whether {@code player} currently has a pre-filled "fast" location ready in
     * the given region (the per-player prefilled-future fast cache).
     *
     * @param regionName canonical region name; {@code null} yields {@code false}.
     * @param player     player UUID; {@code null} yields {@code false}.
     * @return {@code true} iff a fast location is banked for that player.
     */
    public static boolean hasFastLocation(String regionName, UUID player) {
        if (regionName == null || player == null) return false;
        RTP rtp = RTP.getInstance();
        if (rtp == null || RTP.selectionAPI == null) return false;
        try {
            Region region = RTP.selectionAPI.permRegionLookup.get(regionName);
            if (region == null) {
                for (Region temp : RTP.selectionAPI.tempRegions.values()) {
                    if (temp != null && regionName.equals(temp.name)) {
                        region = temp;
                        break;
                    }
                }
            }
            if (region == null || region.queueManager == null) return false;
            return region.queueManager.hasFastLocation(player);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static RegionQueueDepths depthsOf(Region region) {
        if (region == null) return null;
        RegionQueueManager q = region.queueManager;
        if (q == null) return null;
        int kept = safeSize(q.keptLocations);
        int unkept = safeSize(q.unkeptLocations);
        int backlog = (q.backlogLocations != null) ? q.backlogLocations.size() : 0;
        int login = (q.loginLocations != null) ? q.loginLocations.size() : 0;
        int networkKept = (q.networkKeptLocations != null) ? q.networkKeptLocations.size() : 0;
        int networkReserved = q.networkReservedLocations.size();
        int waitlist = q.playerQueue.size();
        int perPlayerQueues = q.perPlayerLocationQueue.size();
        return new RegionQueueDepths(
                region.name,
                kept,
                unkept,
                backlog,
                login,
                networkKept,
                networkReserved,
                waitlist,
                perPlayerQueues);
    }

    private static int safeSize(io.github.dailystruggle.rtp.common.selection.region.LockFreeLocationBuffer buf) {
        return (buf != null) ? buf.size() : 0;
    }
}
