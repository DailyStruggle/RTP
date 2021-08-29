package leafcraft.rtp.tools;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.UUID;

public class HashableChunk {
    UUID worldUID;
    public final int x,z;
    private Chunk chunk;

    public HashableChunk(Chunk chunk) {
        this.chunk = chunk;
        worldUID = chunk.getWorld().getUID();
        this.x = chunk.getX();
        this.z = chunk.getZ();
    }

    public HashableChunk(World world, int x, int z) {
        this.worldUID = world.getUID();
        this.x = x;
        this.z = z;
        this.chunk = null;
    }

    public Chunk getChunk() {
        return (chunk != null) ? chunk : Bukkit.getWorld(worldUID).getChunkAt(x,z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        if(o instanceof Chunk) {
            Chunk that = (Chunk) o;
            return (x == that.getX()) && (z == that.getZ()) && (worldUID.equals(that.getWorld().getUID()));
        }
        else if(o instanceof HashableChunk) {
            HashableChunk that = (HashableChunk) o;
            return (x == that.x) && (z == that.z) && (worldUID.equals(that.worldUID));
        }
        return false;
    }

    @Override
    public int hashCode() {
        return worldUID.hashCode() ^ Integer.hashCode(x) ^ Integer.hashCode(z);
    }
}
