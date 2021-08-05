package leafcraft.rtp.tools;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.Objects;

public class HashableChunk {
    String worldname;
    private int x,z;
    private Chunk chunk;

    public HashableChunk(Chunk chunk) {
        this.worldname = chunk.getWorld().getName();
        this.chunk = chunk;
        x = chunk.getX();
        z = chunk.getZ();
    }

    public HashableChunk(String worldname, int x, int z) {
        this.worldname = worldname;
        this.x = x;
        this.z = z;
    }

    public Chunk getChunk() {
        return (chunk != null) ? chunk : Bukkit.getWorld(worldname).getChunkAt(x,z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        if(o instanceof Chunk) {
            Chunk that = (Chunk) o;
            return (x == that.getX()) && (z == that.getZ()) && (worldname.equals(that.getWorld().getName()));
        }
        else if(o instanceof HashableChunk) {
            HashableChunk that = (HashableChunk) o;
            return (x == that.x) && (z == that.z) && (worldname.equals(that.worldname));
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(worldname + x + z);
    }
}
