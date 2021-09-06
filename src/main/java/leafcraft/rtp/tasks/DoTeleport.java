package leafcraft.rtp.tasks;

import leafcraft.rtp.RTP;
import leafcraft.rtp.customEvents.RandomTeleportEvent;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class DoTeleport extends BukkitRunnable {
    private final RTP plugin;
    private final Configs configs;
    private final CommandSender sender;
    private final Player player;
    private final Location location;
    private final Cache cache;

    public DoTeleport(RTP plugin, Configs configs, CommandSender sender, Player player, Location location, Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.sender = sender;
        this.player = player;
        this.location = location;
        this.cache = cache;
    }

    @Override
    public void run() {
        doTeleportNow();
    }

    public void doTeleportNow() {
        if(configs.config.platformRadius>0) {
            Bukkit.getScheduler().runTask(plugin, ()->makePlatform());
        }
        RandomTeleportEvent randomTeleportEvent = new RandomTeleportEvent(sender, player, location);
        Bukkit.getPluginManager().callEvent(randomTeleportEvent);
        new ChunkCleanup(configs,location,cache).runTask(plugin);
        cache.commandSenderLookup.remove(player.getUniqueId());
    }

    public boolean isNoDelay() {
        return sender.hasPermission("rtp.noDelay");
    }

    private void makePlatform() {
        Chunk chunk = location.getChunk();
        if(!chunk.isLoaded()) chunk.load(true);
        Material solid = location.getBlock().getRelative(BlockFace.DOWN).getType();
//        Material air = location.getBlock().getType();

        for(int i = 7-configs.config.platformRadius; i <= 7+configs.config.platformRadius; i++) {
            for(int j = 7-configs.config.platformRadius; j <= 7+configs.config.platformRadius; j++) {
                for(int y = location.getBlockY()-1; y >= location.getBlockY()-configs.config.platformDepth; y--) {
                    Block block = chunk.getBlock(i,y,j);
                    if(!block.getType().isSolid() || configs.config.unsafeBlocks.contains(block.getType()))
                        block.setType(solid,false);
                }
                for(int y = location.getBlockY()+configs.config.platformAirHeight-1; y >= location.getBlockY(); y--) {
                    chunk.getBlock(i,y,j).breakNaturally();
                }
            }
        }
    }
}
