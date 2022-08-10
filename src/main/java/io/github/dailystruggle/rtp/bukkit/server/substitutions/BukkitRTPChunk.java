package io.github.dailystruggle.rtp.bukkit.server.substitutions;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPBlock;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPChunk;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPLocation;
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
        x = x%16;
        z = z%16;
        return new BukkitRTPBlock(chunk.getBlock(x, y, z));
    }

    @Override
    public RTPBlock getBlockAt(RTPLocation location) {
        return new BukkitRTPBlock(chunk.getBlock(location.x()%16, location.y(), location.z()%16));
    }

    @Override
    public void keep(boolean keep) {
        if(keep) RTP.getInstance().forceLoads.put(new int[]{x(),z()},this);
        else RTP.getInstance().forceLoads.remove(new int[]{x(),z()});
        if(Bukkit.isPrimaryThread()) chunk.setForceLoaded(keep);
        else Bukkit.getScheduler().runTask(RTPBukkitPlugin.getInstance(), () -> chunk.setForceLoaded(keep));
    }
}
