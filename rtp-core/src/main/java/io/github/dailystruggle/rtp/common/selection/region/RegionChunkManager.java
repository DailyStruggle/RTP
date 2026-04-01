package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class RegionChunkManager {
    private final Region region;
    public final ConcurrentHashMap<Long, ChunkSet> locAssChunks = new ConcurrentHashMap<>();
    final ConcurrentHashMap<Long, Integer> ticketCounts = new ConcurrentHashMap<>();

    public RegionChunkManager(Region region) {
        this.region = region;
    }

    long getChunkKey(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }

    public ChunkSet getChunkSet(RTPCoords coords) {
        return locAssChunks.get(getChunkKey(coords.x() >> 4, coords.z() >> 4));
    }

    public void putChunkSet(RTPCoords coords, ChunkSet chunkSet) {
        int cx = coords.x() >> 4;
        int cz = coords.z() >> 4;
        ChunkSet.register(region.getWorld(), cx, cz, chunkSet);
        locAssChunks.put(getChunkKey(cx, cz), chunkSet);
    }

    public void removeChunkSet(RTPCoords coords) {
        int cx = coords.x() >> 4;
        int cz = coords.z() >> 4;
        ChunkSet.unregister(region.getWorld(), cx, cz);
        locAssChunks.remove(getChunkKey(cx, cz));
    }

    public ChunkSet addTicket(int cx, int cz) {
        long key = getChunkKey(cx, cz);
        ticketCounts.compute(key, (k, v) -> (v == null) ? 1 : v + 1);
        try {
            return locAssChunks.computeIfAbsent(key, k -> {
                List<CompletableFuture<Long>> chunks = new ArrayList<>();
                chunks.add(RTP.serverAccessor.getChunkManager().getChunkAtAsync(region.getWorld(), cx, cz));
                ChunkSet chunkSet = new ChunkSet(chunks, new CompletableFuture<>());
                ChunkSet.register(region.getWorld(), cx, cz, chunkSet);
                chunkSet.keep(true, region.getWorld());
                return chunkSet;
            });
        } catch (Exception e) {
            removeTicket(cx, cz);
            throw e;
        }
    }

    public ChunkSet addTicket(RTPCoords coords) {
        return addTicket(coords.x() >> 4, coords.z() >> 4);
    }

    public void removeTicket(int cx, int cz) {
        long key = getChunkKey(cx, cz);
        ticketCounts.computeIfPresent(key, (k, v) -> {
            if (v <= 1) {
                ChunkSet chunkSet = locAssChunks.remove(key);
                if (chunkSet != null) {
                    ChunkSet.unregister(region.getWorld(), cx, cz);
                    RTPWorld<?> world = region.getWorld();
                    RTP.scheduler.runTask(world, cx, cz, () -> chunkSet.keep(false, world));
                }
                return null;
            }
            return v - 1;
        });
    }

    public void removeTicket(RTPCoords coords) {
        removeTicket(coords.x() >> 4, coords.z() >> 4);
    }

    public void runAt(int cx, int cz, Runnable runnable) {
        RTP.scheduler.runTask(region.getWorld(), cx, cz, runnable);
    }

    public Object runAtFixedRate(int cx, int cz, Runnable runnable, long delay, long period) {
        return RTP.scheduler.runTaskTimer(region.getWorld(), cx, cz, runnable, delay, period);
    }

    /**
     * chunks - get a set of chunks around a coordinate
     *
     * @param coords center coordinates
     * @param radius chunk radius
     * @return set of chunks
     */
    public ChunkSet chunks(RTPCoords coords, long radius) {
        int cx = coords.x() >> 4;
        int cz = coords.z() >> 4;
        long chunkKey = getChunkKey(cx, cz);

        long sz = (radius * 2 + 1) * (radius * 2 + 1);
        if (locAssChunks.containsKey(chunkKey)) {
            ChunkSet chunkSet = locAssChunks.get(chunkKey);
            if (chunkSet.chunks.size() >= sz) {
                ticketCounts.compute(chunkKey, (k, v) -> (v == null) ? 1 : v + 1);
                return chunkSet;
            }
            chunkSet.keep(false, region.getWorld());
            ChunkSet.unregister(region.getWorld(), cx, cz);
            locAssChunks.remove(chunkKey);
        }

        List<CompletableFuture<Long>> chunks = new ArrayList<>();

        Shape<?> shape = region.getShape();
        if (shape == null) return null;

        VerticalAdjustor<?> vert = region.getVert();
        if (vert == null) return null;

        RTPWorld<?> rtpWorld = region.getWorld();
        if (rtpWorld == null) return null;

        ticketCounts.compute(chunkKey, (k, v) -> (v == null) ? 1 : v + 1);
        try {
            for (long i = -radius; i <= radius; i++) {
                for (long j = -radius; j <= radius; j++) {
                    CompletableFuture<Long> cfChunk =
                            RTP.serverAccessor
                                    .getChunkManager()
                                    .getChunkAtAsync(rtpWorld, (int) (cx + i), (int) (cz + j));
                    chunks.add(cfChunk);
                }
            }

            ChunkSet chunkSet = new ChunkSet(chunks, new CompletableFuture<>());
            ChunkSet.register(rtpWorld, cx, cz, chunkSet);
            chunkSet.keep(true, rtpWorld);
            locAssChunks.put(chunkKey, chunkSet);
            return chunkSet;
        } catch (Exception e) {
            removeTicket(cx, cz);
            throw e;
        }
    }

    /**
     * removeChunks - stop keeping chunks loaded for a coordinate
     *
     * @param coords coordinates to remove chunks for
     */
    public void removeChunks(RTPCoords coords) {
        int cx = coords.x() >> 4;
        int cz = coords.z() >> 4;
        long chunkKey = getChunkKey(cx, cz);

        if (!locAssChunks.containsKey(chunkKey)) return;
        ChunkSet chunkSet = locAssChunks.remove(chunkKey);
        if (chunkSet == null) return;
        ChunkSet.unregister(region.getWorld(), cx, cz);
        RTPWorld<?> world = region.getWorld();
        RTP.scheduler.runTask(world, cx, cz, () -> chunkSet.keep(false, world));
    }
}
