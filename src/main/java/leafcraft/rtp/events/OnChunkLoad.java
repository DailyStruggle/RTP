package leafcraft.rtp.events;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public record OnChunkLoad(Configs configs,
                          Cache cache) implements Listener {
    private static final Set<Material> acceptableAir = new HashSet<>();

    static {
        acceptableAir.add(Material.AIR);
        acceptableAir.add(Material.CAVE_AIR);
        acceptableAir.add(Material.VOID_AIR);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        if(!event.isNewChunk()) return;

        //for each region in the world, check & add
        // todo: optimize
        for (Map.Entry<RandomSelectParams, TeleportRegion> entry : cache.permRegions.entrySet()) {
            if (!entry.getKey().worldID.equals(event.getWorld().getUID())) continue;
            Location location = event.getChunk().getBlock(7, entry.getKey().minY, 7).getLocation();
            location = entry.getValue().getFirstNonAir(location);
            location = entry.getValue().getLastNonAir(location);
            if (acceptableAir.contains(location.getBlock().getType())
                    || (location.getBlockY() >= entry.getKey().maxY)
                    || ((Boolean) configs.config.getConfigValue("rerollLiquid", true) && location.getBlock().isLiquid())) {
                entry.getValue().addBadChunk(event.getChunk().getX(), event.getChunk().getZ());
                cache.addBadChunk(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
            }
        }
    }
}
