package leafcraft.rtp.events;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.HashableChunk;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import leafcraft.rtp.tools.softdepends.GriefPreventionChecker;
import leafcraft.rtp.tools.softdepends.WorldGuardChecker;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class OnChunkLoad implements Listener {
    private static final Set<Material> acceptableAir = new HashSet<>();
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;
    private Map<HashableChunk,CheckChunk> checkChunkMap = new HashMap<>();

    public OnChunkLoad(RTP plugin,
                       Configs configs,
                       Cache cache) {
        this.plugin = plugin;
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
        Chunk chunk = event.getChunk();
        HashableChunk hashableChunk = new HashableChunk(chunk);
        if(checkChunkMap.containsKey(hashableChunk)) return;
        CheckChunk checkChunk = new CheckChunk(event.getWorld(),hashableChunk);
        //todo: async checking
        checkChunkMap.put(hashableChunk,checkChunk);
//        checkChunk.runTaskAsynchronously(plugin);
        checkChunk.checkChunkNow();
    }

    public void shutdown() {
        for(CheckChunk checkChunk : checkChunkMap.values()) {
            if(checkChunk!=null) checkChunk.cancel();
        }
    }

    private class CheckChunk extends BukkitRunnable {
        private final World world;
        private final HashableChunk hashableChunk;
        private final Chunk chunk;
        private final ChunkSnapshot chunkSnapshot;
        private final boolean rerollLiquid = configs.config.rerollLiquid;
        private final boolean rerollWorldGuard = configs.config.rerollWorldGuard;
        private final boolean rerollGriefPrevention = configs.config.rerollGriefPrevention;
        private boolean cancelled = false;

        public CheckChunk(World world, HashableChunk hashableChunk) {
            this.world = world;
            this.hashableChunk = hashableChunk;
            this.chunk = hashableChunk.getChunk();
            this.chunkSnapshot = chunk.getChunkSnapshot();
        }

        @Override
        public void run() {
            checkChunkNow();
        }

        public void checkChunkNow() {
            //for each region in the world, check & add
            // todo: optimize
            for (Map.Entry<RandomSelectParams, TeleportRegion> entry : cache.permRegions.entrySet()) {
                if (cancelled) return;
                if (!entry.getKey().worldID.equals(world.getUID())) continue;
                if (!entry.getValue().isInBounds(chunk.getX(),chunk.getZ())) continue;
                if (entry.getValue().isKnownBad(chunk.getX(),chunk.getZ())) continue;

                int y;
                y = entry.getValue().getFirstNonAir(chunkSnapshot);
                y = entry.getValue().getLastNonAir(chunkSnapshot,y);
                Block b = chunk.getBlock(7,y,7);
                Location location = b.getLocation();
                if (acceptableAir.contains(b.getType())
                        || (y >= entry.getKey().maxY)
                        || (rerollLiquid && b.isLiquid())
                        || (rerollWorldGuard && WorldGuardChecker.isInRegion(location))
                        || (rerollGriefPrevention && GriefPreventionChecker.isInClaim(location))
                        || (entry.getValue().requireSkyLight && b.getLightFromSky()<8)) {
                    entry.getValue().addBadLocation(chunk.getX(), chunk.getZ());
                }
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
            super.cancel();
        }
    }
}
