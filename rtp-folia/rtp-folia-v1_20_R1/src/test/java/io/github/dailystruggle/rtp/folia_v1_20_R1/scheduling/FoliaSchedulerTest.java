package io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FoliaSchedulerTest {
    private JavaPlugin plugin;
    private GlobalRegionScheduler globalScheduler;
    private AsyncScheduler asyncScheduler;
    private FoliaSchedulerImpl scheduler;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        globalScheduler = mock(GlobalRegionScheduler.class);
        asyncScheduler = mock(AsyncScheduler.class);
        scheduler = new FoliaSchedulerImpl(plugin);
    }

    @Test
    void testRunTask() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);

            Runnable task = mock(Runnable.class);
            scheduler.runTask(task);

            verify(globalScheduler).run(eq(plugin), any(Consumer.class));
        }
    }

    @Test
    void testRunTaskAsynchronously() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getAsyncScheduler).thenReturn(asyncScheduler);

            Runnable task = mock(Runnable.class);
            scheduler.runTaskAsynchronously(task);

            verify(asyncScheduler).runNow(eq(plugin), any(Consumer.class));
        }
    }

    @Test
    void testRunTaskLater() {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);

            Runnable task = mock(Runnable.class);
            scheduler.runTaskLater(task, 20L);

            verify(globalScheduler).runDelayed(eq(plugin), any(Consumer.class), eq(20L));
        }
    }
}
