package leafcraft.rtp.tools;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.UUID;

public class HashableChunk {
    UUID worldUID;
    private Long coordinate;
    private Chunk chunk;

    public HashableChunk(Chunk chunk) {
        this.chunk = chunk;
        coordinate = (Long.valueOf(chunk.getX())<<32) + chunk.getZ();
        worldUID = chunk.getWorld().getUID();
    }

    public HashableChunk(World world, int x, int z) {
        this.worldUID = world.getUID();
        this.coordinate = (Long.valueOf(x)<<32)+z;
    }

    public Chunk getChunk() {
        return (chunk != null) ? chunk : Bukkit.getWorld(worldUID).getChunkAt((int)(coordinate >>32), (int)(coordinate &0xFFFF));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        if(o instanceof Chunk) {
            Chunk that = (Chunk) o;
            return (coordinate == ((Long.valueOf(that.getX())<<32)+that.getZ())) && (worldUID.equals(that.getWorld().getUID()));
        }
        else if(o instanceof HashableChunk) {
            HashableChunk that = (HashableChunk) o;
            return (coordinate.equals(that.coordinate)) && (worldUID.equals(that.worldUID));
        }
        return false;
    }

    @Override
    public int hashCode() {
        return worldUID.hashCode() ^ coordinate.hashCode();
    }
}
