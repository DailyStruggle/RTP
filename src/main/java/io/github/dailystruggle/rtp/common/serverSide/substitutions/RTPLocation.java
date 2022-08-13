package io.github.dailystruggle.rtp.common.serverSide.substitutions;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public record RTPLocation(RTPWorld world, int x, int y, int z) implements Cloneable {
    public long distanceSquared(RTPLocation that) {
        if (!this.world.equals(that.world)) return Long.MAX_VALUE; // another world is pretty far away
        long dx = this.x - that.x;
        long dy = this.y - that.y;
        long dz = this.z - that.z;
        return (long) (Math.pow(dx, 2) + Math.pow(dy, 2) + Math.pow(dz, 2));
    }

    public long distanceSquaredXZ(RTPLocation that) {
        if (!this.world.equals(that.world)) return Long.MAX_VALUE; // another world is pretty far away
        long dx = this.x - that.x;
        long dz = this.z - that.z;
        return (long) (Math.pow(dx, 2) + Math.pow(dz, 2));
    }

    @Override
    public RTPLocation clone() {
        RTPLocation clone;
        try {
            clone = (RTPLocation) super.clone();
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
            return null;
        }
        return clone;
    }

    public CompletableFuture<RTPBlock> getBlock() {
        CompletableFuture<RTPBlock> res = new CompletableFuture<>();
        int cx = (x>0) ? x%16 : x%16-1;
        int cz = (z>0) ? z%16 : z%16-1;
        CompletableFuture<RTPChunk> chunkAt = world.getChunkAt(cx, cz);
        if(chunkAt.isDone()) {
            try {
                RTPChunk chunk = chunkAt.get();
                res.complete(chunk.getBlockAt(this));
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        chunkAt.whenComplete((rtpChunk, throwable) -> res.complete(rtpChunk.getBlockAt(this)));
        return res;
    }
}
