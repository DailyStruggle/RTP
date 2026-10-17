package io.github.dailystruggle.rtp.bukkit.tools.softdepends.hologram;

import io.github.dailystruggle.effectsapi.common.hologram.HologramHandle;
import io.github.dailystruggle.effectsapi.common.volumetric.Vector3d;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Folia: TextDisplay spawn/mutation must run on the region owning the chunk, not the caller thread. */
@DisplayName("BukkitTextDisplayHologramProvider routes entity work to the owning region")
class BukkitTextDisplayHologramThreadingTest {

    private RTPServerAccessor previousAccessor;
    private RTPScheduler previousScheduler;
    private MockedStatic<Bukkit> bukkit;
    private World world;
    private TextDisplay display;
    private final List<Runnable> regionQueue = new ArrayList<>();

    @BeforeEach
    void setUp() {
        previousAccessor = RTP.serverAccessor;
        previousScheduler = RTP.scheduler;

        world = mock(World.class);
        display = mock(TextDisplay.class);
        when(display.isValid()).thenReturn(true);
        when(world.spawnEntity(any(Location.class), eq(EntityType.valueOf("TEXT_DISPLAY")))).thenReturn(display);

        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        // PAPIChecker probes Bukkit.getServer().getPluginManager(); absent PAPI leaves text unchanged.
        Server server = mock(Server.class);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        bukkit.when(Bukkit::getServer).thenReturn(server);

        RTPServerAccessor accessor = mock(RTPServerAccessor.class);
        RTPWorld<?> rtpWorld = mock(RTPWorld.class);
        doReturn(rtpWorld).when(accessor).getRTPWorld(anyString());
        RTP.serverAccessor = accessor;

        RTPScheduler scheduler = mock(RTPScheduler.class);
        doAnswer(inv -> {
            regionQueue.add(inv.getArgument(3));
            return null;
        }).when(scheduler).runTask(any(RTPWorld.class), anyInt(), anyInt(), any(Runnable.class));
        RTP.scheduler = scheduler;
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
        RTP.serverAccessor = previousAccessor;
        RTP.scheduler = previousScheduler;
    }

    private void drainRegion() {
        List<Runnable> batch = new ArrayList<>(regionQueue);
        regionQueue.clear();
        batch.forEach(Runnable::run);
    }

    @Test
    @DisplayName("spawn, updateLines and close are scheduled on the chunk's region, never run on the caller thread")
    void entityWorkIsRegionScheduled() {
        BukkitTextDisplayHologramProvider provider = new BukkitTextDisplayHologramProvider();
        HologramHandle handle = provider.spawnHologram("t", "world", new Vector3d(33.5, 70, -17.5), List.of("a"));
        assertNotNull(handle);

        verify(world, never()).spawnEntity(any(Location.class), any(EntityType.class));
        verify(RTP.scheduler).runTask(any(RTPWorld.class), eq(2), eq(-2), any(Runnable.class));
        drainRegion();
        verify(world).spawnEntity(any(Location.class), any(EntityType.class));

        handle.updateLines(List.of("b"));
        verify(display, never()).setText("b");
        drainRegion();
        verify(display).setText("b");
        assertEquals(List.of("b"), handle.lines());

        handle.close();
        verify(display, never()).remove();
        drainRegion();
        verify(display).remove();
    }

    @Test
    @DisplayName("lines reach the entity with & and hex codes translated; handle keeps the raw lines")
    void linesAreColorTranslated() {
        BukkitTextDisplayHologramProvider provider = new BukkitTextDisplayHologramProvider();
        HologramHandle handle = provider.spawnHologram("t", "world", new Vector3d(0, 64, 0), List.of("&aHi", "#ff0000x"));
        drainRegion();
        verify(display).setText("§aHi\n§x§f§f§0§0§0§0x");
        assertEquals(List.of("&aHi", "#ff0000x"), handle.lines());

        handle.updateLines(List.of("&c&lGo"));
        drainRegion();
        verify(display).setText("§c§lGo");
    }

    @Test
    @DisplayName("close before the scheduled spawn runs leaves no entity behind")
    void closeBeforeSpawnDoesNotLeak() {
        BukkitTextDisplayHologramProvider provider = new BukkitTextDisplayHologramProvider();
        HologramHandle handle = provider.spawnHologram("t", "world", new Vector3d(0, 64, 0), List.of("a"));
        handle.close();
        drainRegion();
        verify(world, never()).spawnEntity(any(Location.class), any(EntityType.class));
    }
}
