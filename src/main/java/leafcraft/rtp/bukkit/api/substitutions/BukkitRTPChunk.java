package leafcraft.rtp.bukkit.api.substitutions;

import leafcraft.rtp.api.substitutions.RTPBlock;
import leafcraft.rtp.api.substitutions.RTPChunk;
import leafcraft.rtp.api.substitutions.RTPLocation;
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
}
