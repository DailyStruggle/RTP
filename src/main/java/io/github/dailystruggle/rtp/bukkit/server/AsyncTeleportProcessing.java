package io.github.dailystruggle.rtp.bukkit.server;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.TPS;
import io.github.dailystruggle.rtp.common.tasks.tick.AsyncTaskProcessing;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class AsyncTeleportProcessing extends BukkitRunnable {
    private static final AtomicReference<AsyncTaskProcessing> asyncTaskProcessing = new AtomicReference<>();
    private static final AtomicBoolean killed = new AtomicBoolean( false );
    private static final ConcurrentHashMap<Integer, BukkitTask> asyncTasks = new ConcurrentHashMap<>();

    public AsyncTeleportProcessing() {
        if ( killed.get() ) return;
        if ( asyncTaskProcessing.get() != null ) return;
        long avgTime = TPS.timeSinceTick( 20 ) / 20;
        long currTime = TPS.timeSinceTick( 1 );

        long availableTime = avgTime - currTime;
        availableTime = TimeUnit.MILLISECONDS.toNanos( availableTime ) / 2;

        asyncTaskProcessing.set( new AsyncTaskProcessing( availableTime) );
    }

    public static void clear() {
        if ( asyncTaskProcessing.get() != null && !asyncTaskProcessing.get().isCancelled() )
            asyncTaskProcessing.get().setCancelled( true );
        asyncTaskProcessing.set( null );

        for ( Map.Entry<Integer, BukkitTask> entry : asyncTasks.entrySet() ) {
            BukkitTask bukkitTask = entry.getValue();
            bukkitTask.cancel();
        }
        asyncTasks.clear();
    }

    public static void kill() {
        clear();
        killed.set( true );
    }

    @Override
    public void run() {
        if ( killed.get() ) return;
        if ( asyncTaskProcessing.get() == null ) return;
        if ( asyncTasks.size() > 1 ) return;

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        RTP.futures.add( future );
        BukkitTask task = Bukkit.getScheduler().runTaskAsynchronously( RTPBukkitPlugin.getInstance(), () -> {
            AsyncTaskProcessing asyncTaskProcessing2 = AsyncTeleportProcessing.asyncTaskProcessing.get();
            if ( asyncTaskProcessing2 == null ) {
                AsyncTeleportProcessing.asyncTaskProcessing.set( null );
                future.complete( false );
                return;
            }
            asyncTaskProcessing2.run();
            AsyncTeleportProcessing.asyncTaskProcessing.set( null );
            future.complete( true );
        } );
        asyncTasks.put( task.getTaskId(), task );
        future.thenAccept( aBoolean -> asyncTasks.remove( task.getTaskId()) );
    }

    @Override
    public void cancel() {
        kill();
        super.cancel();
    }
}
