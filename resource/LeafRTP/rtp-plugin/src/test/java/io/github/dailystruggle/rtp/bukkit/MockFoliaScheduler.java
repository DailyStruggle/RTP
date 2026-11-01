package io.github.dailystruggle.rtp.bukkit;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.mockito.MockedStatic;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mockito;

public class MockFoliaScheduler {
    public static void registerFoliaMocks(MockedStatic<Bukkit> bukkit) {
        GlobalRegionScheduler globalRegionScheduler = Mockito.mock(GlobalRegionScheduler.class);
        AsyncScheduler asyncScheduler = Mockito.mock(AsyncScheduler.class);
        RegionScheduler regionScheduler = Mockito.mock(RegionScheduler.class);

        bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalRegionScheduler);
        bukkit.when(Bukkit::getAsyncScheduler).thenReturn(asyncScheduler);
        bukkit.when(Bukkit::getRegionScheduler).thenReturn(regionScheduler);

        // Mock GlobalRegionScheduler.run
        Mockito.lenient().doAnswer(invocation -> {
            Consumer<ScheduledTask> consumer = invocation.getArgument(1);
            consumer.accept(Mockito.mock(ScheduledTask.class));
            return Mockito.mock(ScheduledTask.class);
        }).when(globalRegionScheduler).run(any(), any());

        // Mock GlobalRegionScheduler.runDelayed
        Mockito.lenient().doAnswer(invocation -> {
            Consumer<ScheduledTask> consumer = invocation.getArgument(1);
            consumer.accept(Mockito.mock(ScheduledTask.class));
            return Mockito.mock(ScheduledTask.class);
        }).when(globalRegionScheduler).runDelayed(any(), any(), Mockito.anyLong());

        // Mock RegionScheduler.run
        Mockito.lenient().doAnswer(invocation -> {
            Consumer<ScheduledTask> consumer = invocation.getArgument(4);
            consumer.accept(Mockito.mock(ScheduledTask.class));
            return Mockito.mock(ScheduledTask.class);
        }).when(regionScheduler).run(any(), any(World.class), Mockito.anyInt(), Mockito.anyInt(), any());

        Mockito.lenient().doAnswer(invocation -> {
            Consumer<ScheduledTask> consumer = invocation.getArgument(2);
            consumer.accept(Mockito.mock(ScheduledTask.class));
            return Mockito.mock(ScheduledTask.class);
        }).when(regionScheduler).run(any(), any(Location.class), any());

        // Mock AsyncScheduler.runNow
        Mockito.lenient().doAnswer(invocation -> {
            Consumer<ScheduledTask> consumer = invocation.getArgument(1);
            consumer.accept(Mockito.mock(ScheduledTask.class));
            return Mockito.mock(ScheduledTask.class);
        }).when(asyncScheduler).runNow(any(), any());

        // Mock AsyncScheduler.runDelayed
        Mockito.lenient().doAnswer(invocation -> {
            Consumer<ScheduledTask> consumer = invocation.getArgument(1);
            consumer.accept(Mockito.mock(ScheduledTask.class));
            return Mockito.mock(ScheduledTask.class);
        }).when(asyncScheduler).runDelayed(any(), any(), Mockito.anyLong(), any(TimeUnit.class));

        // Mock AsyncScheduler.runAtFixedRate
        Mockito.lenient().doAnswer(invocation -> {
            Consumer<ScheduledTask> consumer = invocation.getArgument(1);
            consumer.accept(Mockito.mock(ScheduledTask.class));
            return Mockito.mock(ScheduledTask.class);
        }).when(asyncScheduler).runAtFixedRate(any(), any(), Mockito.anyLong(), Mockito.anyLong(), any(TimeUnit.class));
    }
}
