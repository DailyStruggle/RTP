package leafcraft.rtp.API.selection.region.selectors.memory.fill;

import io.papermc.lib.PaperLib;
import leafcraft.rtp.API.RTPAPI;
import leafcraft.rtp.API.selection.region.selectors.SelectorParams;
import leafcraft.rtp.API.selection.region.selectors.Shape;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.TPS;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class FillTask extends BukkitRunnable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    protected Semaphore fillIterGuard = new Semaphore(1);
    protected long fillIter = 0L;

    private boolean cancelled = false;
    private final RTP plugin;
    private final String name;
    private final World world;
    private final Shape shape;
    private final long limit;
    private final Long minY;
    private final Long maxY;
    private final ArrayList<CompletableFuture<Chunk>> chunks;

    private final List<Consumer<Long>> onPlacementSuccess = new ArrayList<>();
    private final List<Consumer<Long>> onPlacementFail = new ArrayList<>();

    private final List<Runnable> onCancel = new ArrayList<>();
    private final List<Consumer<FillTask>> onCancelConsumers = new ArrayList<>();

    private final List<Runnable> onCompletion = new ArrayList<>();
    private final List<Consumer<FillTask>> onCompletionConsumers = new ArrayList<>();

    public void runOnPlacementSuccess(Consumer<Long> runnable) {
        onPlacementSuccess.add(runnable);
    }

    public void runOnPlacementFail(Consumer<Long> runnable) {
        onPlacementSuccess.add(runnable);
    }

    public void runOnCancel(Runnable runnable) {
        onCancel.add(runnable);
    }

    public void runOnCancel(Consumer<FillTask> runnable) {
        onCancelConsumers.add(runnable);
    }

    public void runOnRun(Runnable runnable) {
        onCompletion.add(runnable);
    }

    public void runOnRun(Consumer<FillTask> runnable) {
        onCompletionConsumers.add(runnable);
    }

    public FillTask(RTP plugin, String name, EnumMap<SelectorParams,Object> params, long limit) {
        this.plugin = plugin;
        this.name = name;
        this.world = Bukkit.getWorld((String) params.get(SelectorParams.WORLD));
        this.shape = (Shape) params.get(SelectorParams.SHAPE);
        this.limit = limit;
        this.minY = (Long) params.get(SelectorParams.MINY);
        this.maxY = (Long) params.get(SelectorParams.MAXY);
        this.chunks = new ArrayList<>(2500);
    }

    @Override
    public void run() {
        if(TPS.getTPS()<Configs.config.minTPS) {
            runTaskLaterAsynchronously(plugin,20);
            return;
        }

        long it;
        try {
            fillIterGuard.acquire();
            it = fillIter;
        } catch (InterruptedException ignored) {
            return;
        } finally {
            fillIterGuard.release();
        }
        Semaphore completionGuard = new Semaphore(1);
        AtomicInteger completion = new AtomicInteger(2500);
//            AtomicLong max = new AtomicLong((long) ((expand) ? totalSpace : totalSpace - badLocationSum.get()));
        AtomicLong max = new AtomicLong(limit);
        long itStop;
        try {
            completionGuard.acquire();
            itStop = it + completion.get();
        } catch (InterruptedException e) {
            return;
        } finally {
            completionGuard.release();
        }
        AtomicBoolean completed = new AtomicBoolean(false);

        String msg = Configs.lang.getLog("fillStatus");
        msg = msg.replace("[num]", String.valueOf(it));
        msg = msg.replace("[total]", String.valueOf(max.get()));
        msg = msg.replace("[region]", name);

        SendMessage.sendMessage(Bukkit.getConsoleSender(),msg);
        for(Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("rtp.fill")) SendMessage.sendMessage(player, msg);
        }

        for(; it < itStop; it++) {
            if(cancelled) return;

            if (it > max.get()) {
                msg = Configs.lang.getLog("fillStatus");
                msg = msg.replace("[num]", String.valueOf(it-1));
                msg = msg.replace("[total]", String.valueOf(max.get()));
                msg = msg.replace("[region]", name);

                SendMessage.sendMessage(Bukkit.getConsoleSender(),msg);
                for(Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasPermission("rtp.fill")) SendMessage.sendMessage(player, msg);
                }

                for(Runnable runnable : onCompletion) {
                    runnable.run();
                }
                return;
            }

            long[] xz = shape.locationToXZ(it);
            if(cancelled) return;

            Objects.requireNonNull(world);
            //noinspection deprecation
            Biome currBiome = (RTPAPI.getServerIntVersion()<17)
                    ? world.getBiome((int)xz[0]*16+7,(int)xz[1]*16+7)
                    : world.getBiome((int)xz[0]*16+7,(int)(minY+maxY)/2,(int)xz[1]*16+7);
            if(Configs.config.biomeWhitelist != Configs.config.biomes.contains(currBiome)) {
                
                
                addBadLocation(it);
                removeBiomeLocation(it,currBiome);
                try {
                    fillIteratorGuard.acquire();
                    fillIterator.incrementAndGet();
                } catch (InterruptedException ignored) {
                    return;
                } finally {
                    fillIteratorGuard.release();
                }
                int completionLocal;
                try {
                    completionGuard.acquire();
                    completionLocal = completion.decrementAndGet();
                } catch (InterruptedException e) {
                    return;
                } finally {
                    completionGuard.release();
                }
                if(completionLocal == 0 && !completed.getAndSet(true)) {
                    fillTask = new TeleportRegion.FillTask(plugin);
                    fillTask.runTaskLaterAsynchronously(plugin,1);
                    for(Runnable runnable : onCompletion) {
                        runnable.run();
                    }
                }
                continue;
            }

            CompletableFuture<Chunk> cfChunk = PaperLib.getChunkAtAsync(Objects.requireNonNull(world),xz[0],xz[1],true);
            this.chunks.add(cfChunk);
            final long finalIt = it;
            max.set((long)totalSpace);
            cfChunk.whenCompleteAsync((chunk, throwable) -> {
                if(cancelled) return;
                if(!mode.equals(TeleportRegion.Modes.NONE)) {
                    int y = getFirstNonAir(chunk);
                    y = getLastNonAir(chunk, y);

                    if (checkLocation(chunk, y)) {
                        addBiomeLocation(finalIt, currBiome);
                    } else {
                        addBadLocation(finalIt);
                    }
                }

                if(cancelled) return;
                try {
                    fillIteratorGuard.acquire();
                    fillIterator.incrementAndGet();
                } catch (InterruptedException ignored) {
                    return;
                } finally {
                    fillIteratorGuard.release();
                }
                int completionLocal;
                try {
                    completionGuard.acquire();
                    completionLocal = completion.decrementAndGet();
                } catch (InterruptedException e) {
                    return;
                } finally {
                    completionGuard.release();
                }
                if(completionLocal == 0 && !completed.getAndSet(true)) {
                    fillTask = new TeleportRegion.FillTask(plugin);
                    fillTask.runTaskLaterAsynchronously(plugin,1);
                }
            });
            cfChunk.whenComplete((chunk, throwable) -> {
                if(cancelled) return;
                try {
                    fillIteratorGuard.acquire();
                    int completionLocal;
                    try {
                        completionGuard.acquire();
                        completionLocal = completion.get();
                    } catch (InterruptedException e) {
                        return;
                    } finally {
                        completionGuard.release();
                    }
                    if(completionLocal == 0 || fillIterator.get()==max.get()) {
                        world.save();
                    }
                } catch (InterruptedException ignored) {

                } finally {
                    fillIteratorGuard.release();
                }
            });
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        for(CompletableFuture<Chunk> cfChunk : chunks) {
            if(!cfChunk.isDone()) {
                try {
                    cfChunk.cancel(true);
                }
                catch (CancellationException ignored) {

                }
            }
        }
        super.cancel();
        for(Runnable runnable : onCancel) {
            runnable.run();
        }
    }
}
