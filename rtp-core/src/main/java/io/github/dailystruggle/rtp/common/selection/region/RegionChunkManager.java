package io.github.dailystruggle.rtp.common.selection.region;

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
    private Region region;
    public ConcurrentHashMap<RTPCoords, ChunkSet> locAssChunks = new ConcurrentHashMap<>();

    public RegionChunkManager(Region region) {
        this.region = region;
    }

    /**
     * chunks - get a set of chunks around a coordinate
     *
     * @param coords center coordinates
     * @param radius chunk radius
     * @return set of chunks
     */
    public ChunkSet chunks(RTPCoords coords, long radius) {
        long sz = (radius * 2 + 1) * (radius * 2 + 1);
        if (locAssChunks.containsKey(coords)) {
            ChunkSet chunkSet = locAssChunks.get(coords);
            if (chunkSet.chunks.size() >= sz) return chunkSet;
            chunkSet.keep(false, region.getWorld());
            locAssChunks.remove(coords);
        }

        int cx = coords.x();
        int cz = coords.z();
        cx = (cx > 0) ? cx / 16 : cx / 16 - 1;
        cz = (cz > 0) ? cz / 16 : cz / 16 - 1;

        List<CompletableFuture<Long>> chunks = new ArrayList<>();

        Shape<?> shape = region.getShape();
        if (shape == null) return null;

        VerticalAdjustor<?> vert = region.getVert();
        if (vert == null) return null;

        RTPWorld<?> rtpWorld = region.getWorld();
        if (rtpWorld == null) return null;

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
        chunkSet.keep(true, rtpWorld);
        locAssChunks.put(coords, chunkSet);
        return chunkSet;
    }

    /**
     * removeChunks - stop keeping chunks loaded for a coordinate
     *
     * @param coords coordinates to remove chunks for
     */
    public void removeChunks(RTPCoords coords) {
        if (!locAssChunks.containsKey(coords)) return;
        ChunkSet chunkSet = locAssChunks.get(coords);
        chunkSet.keep(false, region.getWorld());
        locAssChunks.remove(coords);
    }
}
