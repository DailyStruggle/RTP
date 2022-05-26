package io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.substitutions;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;

public record BukkitRTPChunk(Chunk chunk) implements RTPChunk {
    @Override
    public int x() {
        return chunk.getX();
    }

    @Override
    public int z() {
        return chunk.getZ();
    }

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
        if(keep) RTP.getInstance().forceLoads.put(new int[]{x(),z()},this);
        else RTP.getInstance().forceLoads.remove(new int[]{x(),z()});
        if(Bukkit.isPrimaryThread()) chunk.setForceLoaded(keep);
        else Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(), () -> chunk.setForceLoaded(keep));
    }
}
