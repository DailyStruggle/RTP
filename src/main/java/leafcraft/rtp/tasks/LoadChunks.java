package leafcraft.rtp.tasks;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class LoadChunks extends BukkitRunnable {
    private final RTP plugin;
    private final Configs configs;
    private final CommandSender sender;
    private final Player player;
    private final Cache cache;
    private final Integer totalTime;
    private final Location location;
    private final RandomSelectParams rsParams;
    private List<CompletableFuture<Chunk>> chunks = null;
    private Boolean cancelled = false;

    public LoadChunks(RTP plugin, Configs configs, CommandSender sender, Player player, Cache cache, Integer totalTime, Location location) {
        this.plugin = plugin;
        this.configs = configs;
        this.sender = sender;
        this.player = player;
        this.cache = cache;
        this.totalTime = totalTime;
        this.location = location;
        this.rsParams = cache.regionKeys.get(player.getUniqueId());
    }

    @Override
    public void run() {
        Long startTime = System.currentTimeMillis();

        if(!sender.hasPermission("rtp.noDelay.chunks")) {
            if (cache.permRegions.containsKey(rsParams))
                chunks = cache.permRegions.get(rsParams).getChunks(location);
            else chunks = cache.tempRegions.get(rsParams).getChunks(location);
            for (CompletableFuture<Chunk> chunk : chunks) {
                if (isCancelled() || cancelled) break;
                try {
                    chunk.get();
                } catch (CancellationException | InterruptedException e) {
                    break;
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
            }
        }

        if (!isCancelled() && !cancelled) {
            long finTime = System.currentTimeMillis();
            long remTime = totalTime - ((finTime - startTime) / 50);
            if (remTime < 0) remTime = 0;

            DoTeleport doTeleport = new DoTeleport(plugin,configs,sender,player,location,cache);
            doTeleport.runTaskLater(plugin,remTime);
            this.cache.doTeleports.put(this.player.getUniqueId(),doTeleport);
        }
        cache.loadChunks.remove(player.getUniqueId());
    }

    @Override
    public void cancel() {
        if(cache.permRegions.containsKey(rsParams)) {
            cache.permRegions.get(rsParams).queueLocation(location);
        }
        cache.loadChunks.remove(player.getUniqueId());
        cache.todoTP.remove(player.getUniqueId());
        if(cache.doTeleports.containsKey(player.getUniqueId())) {
            cache.doTeleports.get(player.getUniqueId()).cancel();
            cache.doTeleports.remove(player.getUniqueId());
        }
        cancelled = true;
        super.cancel();
    }

    public boolean isNoDelay() {
        return sender.hasPermission("rtp.noDelay");
    }
}
