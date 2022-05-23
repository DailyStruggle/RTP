package io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.substitutions;

import io.github.dailystruggle.rtp.common.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;
import io.github.dailystruggle.rtp.common.substitutions.RTPBlock;
import org.bukkit.Chunk;

public record BukkitRTPChunk(Chunk chunk) implements RTPChunk {
    @Override
    public RTPBlock getBlockAt(int x, int y, int z) {
        return new BukkitRTPBlock(chunk.getBlock(x, y, z));
    }

    @Override
    public RTPBlock getBlockAt(RTPLocation location) {
        return new BukkitRTPBlock(chunk.getBlock(location.x(), location.y(), location.z()));
    }

    @Override
    public void keep(boolean keep) {
        chunk.setForceLoaded(keep);
    }
}
