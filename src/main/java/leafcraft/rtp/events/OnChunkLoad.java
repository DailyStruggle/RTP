package leafcraft.rtp.events;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.softdepends.GriefPreventionChecker;
import leafcraft.rtp.tools.softdepends.WorldGuardChecker;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class OnChunkLoad implements Listener {
    private static final Set<Material> acceptableAir = new HashSet<>();
    private final Configs configs;
    private final Cache cache;

    public OnChunkLoad(Configs configs,
                       Cache cache) {
        this.configs = configs;
        this.cache = cache;
    }

    static {
        acceptableAir.add(Material.AIR);
        acceptableAir.add(Material.CAVE_AIR);
        acceptableAir.add(Material.VOID_AIR);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkLoad(ChunkLoadEvent event) {
        boolean rerollLiquid = configs.config.rerollLiquid;
        boolean rerollWorldGuard = configs.config.rerollWorldGuard;
        boolean rerollGriefPrevention = configs.config.rerollGriefPrevention;

        //for each region in the world, check & add
        // todo: optimize
        for (Map.Entry<RandomSelectParams, TeleportRegion> entry : cache.permRegions.entrySet()) {
            if (!entry.getKey().worldID.equals(event.getWorld().getUID())) continue;
            if (!entry.getValue().isInBounds(event.getChunk().getX(),event.getChunk().getZ())) continue;
            if (entry.getValue().isKnownBad(event.getChunk().getX(),event.getChunk().getZ())) continue;

            int y;
            y = entry.getValue().getFirstNonAir(event.getChunk().getChunkSnapshot());
            y = entry.getValue().getLastNonAir(event.getChunk().getChunkSnapshot(),y);
            Block b = event.getChunk().getBlock(7,y,7);
            Location res = b.getLocation();
            if (acceptableAir.contains(b.getType())
                    || (y >= entry.getKey().maxY)
                    || (rerollLiquid && b.isLiquid())
                    || (rerollWorldGuard && WorldGuardChecker.isInRegion(res))
                    || (rerollGriefPrevention && GriefPreventionChecker.isInClaim(res))) {
                entry.getValue().addBadLocation(event.getChunk().getX(), event.getChunk().getZ());
            }
        }
    }
}
