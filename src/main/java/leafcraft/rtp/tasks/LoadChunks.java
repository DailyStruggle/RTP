package leafcraft.rtp.tasks;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class LoadChunks extends BukkitRunnable {
    public final TeleportRegion.ChunkSet chunkSet;
    private final RTP plugin;
    private final Configs configs;
    private final CommandSender sender;
    private final Player player;
    private final Cache cache;
    private final Long delay;
    private final Location location;
    private final RandomSelectParams rsParams;
    private int i;
    private int j;
    private final int vd;
    private Boolean cancelled = false;

    public LoadChunks(RTP plugin, Configs configs, CommandSender sender, Player player, Cache cache, Long delay, Location location) {
        this.plugin = plugin;
        this.configs = configs;
        this.sender = sender;
        this.player = player;
        this.cache = cache;
        this.delay = delay;
        this.location = location;
        this.rsParams = cache.regionKeys.get(player.getUniqueId());
        if (cache.permRegions.containsKey(rsParams))
            chunkSet = cache.permRegions.get(rsParams).getChunks(location);
        else chunkSet = cache.tempRegions.get(rsParams).getChunks(location);
        int vd = configs.config.vd;
        i = -vd;
        j = -vd;
        this.vd = Bukkit.getViewDistance();
    }

    public LoadChunks(RTP plugin, Configs configs, CommandSender sender, Player player, Cache cache, Long delay, Location location, int i, int j) {
        this.plugin = plugin;
        this.configs = configs;
        this.sender = sender;
        this.player = player;
        this.cache = cache;
        this.delay = delay;
        this.location = location;
        this.rsParams = cache.regionKeys.get(player.getUniqueId());
        if (cache.permRegions.containsKey(rsParams))
            chunkSet = cache.permRegions.get(rsParams).getChunks(location);
        else chunkSet = cache.tempRegions.get(rsParams).getChunks(location);
        this.i = i;
        this.j = j;
        this.vd = Bukkit.getViewDistance();
    }

    @Override
    public void run() {
        loadChunksNow(true);
    }

    @Override
    public void cancel() {
        cancelled = true;
        super.cancel();
    }

    public boolean isNoDelay() {
        return sender.hasPermission("rtp.noDelay");
    }

    public void loadChunksNow(boolean async) {
        if(chunkSet.completed.get() < chunkSet.expectedSize) {
            if(sender.hasPermission("rtp.noDelay") && i==-vd && j==-vd) {
                String msg = configs.lang.getLog("chunkLoading");
                SendMessage.sendMessage(sender,player,msg);
            }

            if (PaperLib.isPaper()) {
                for (CompletableFuture<Chunk> chunk : chunkSet.chunks) {
                    if (cancelled) break;
                    if (chunk.isDone()) continue;
                    else {
                        try {
                            chunk.get();
                        } catch (InterruptedException | CancellationException e) {
                            cancel();
                            break;
                        } catch (ExecutionException e) {
                            e.printStackTrace();
                        }
                    }
                }
            } else {
                World world = Bukkit.getWorld(rsParams.worldID);
                if(world == null) return;
                Chunk centerChunk = location.getChunk();
                int vd = configs.config.vd;
                long len = (2L*vd+1)*(2L*vd+1);
                if(delay>0) len = 2*(len/delay);
                for(; i < vd; i++) {
                    if(j>=vd) j = 0;
                    for(; j < vd; j++) {
                        if(cancelled) return;
                        if(world.isChunkLoaded(centerChunk.getX()+i,centerChunk.getZ()+j)) continue;
                        PaperLib.getChunkAtAsyncUrgently(world, centerChunk.getX()+i,centerChunk.getZ()+j, true);
                        chunkSet.completed.addAndGet(1);
                        len--;
                        if(len<=0) {
                            cache.loadChunks.remove(player.getUniqueId());
                            LoadChunks loadChunks = new LoadChunks(plugin, configs, sender, player, cache, delay, location, i, j);
                            cache.loadChunks.put(player.getUniqueId(),loadChunks);
                            loadChunks.runTaskAsynchronously(plugin);
                            return;
                        }
                    }
                }
            }
        }

        if (!cancelled) {
            long remTime = 2 + delay - (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cache.lastTeleportTime.getOrDefault(player.getUniqueId(), System.nanoTime())) / 50);
            if (remTime < 0) remTime = 0;

            DoTeleport doTeleport = new DoTeleport(plugin,configs,sender,player,location,cache);
            if(async || remTime>0) {
                doTeleport.runTaskLater(plugin,remTime);
                cache.doTeleports.put(this.player.getUniqueId(),doTeleport);
            }
            else doTeleport.doTeleportNow();
        }
        cache.loadChunks.remove(player.getUniqueId());
    }
}
