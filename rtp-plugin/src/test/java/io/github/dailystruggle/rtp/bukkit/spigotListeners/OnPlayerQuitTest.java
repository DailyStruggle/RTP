package io.github.dailystruggle.rtp.bukkit.spigotListeners;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class OnPlayerQuitTest {
    private RTPScheduler scheduler;
    private RTPServerAccessor serverAccessor;

    @BeforeEach
    public void setUp() {
        scheduler = mock(RTPScheduler.class);
        serverAccessor = mock(RTPServerAccessor.class);
        RTP.scheduler = scheduler;
        RTP.serverAccessor = serverAccessor;
        when(serverAccessor.createCachePipe()).thenReturn(mock(RTPTaskPipe.class));
        when(serverAccessor.createTaskPipe()).thenReturn(mock(RTPTaskPipe.class));
        when(serverAccessor.getChunkManager()).thenReturn(mock(RTPChunkManager.class));
        new RTP();
    }

    @Test
    public void testOnPlayerQuitReleasesChunkTicket() {
        // 1. Mock an RTPPlayer (with a specific UUID) and a target RTPChunk.
        UUID uuid = UUID.randomUUID();
        RTPPlayer rtpPlayer = mock(RTPPlayer.class);
        when(rtpPlayer.uuid()).thenReturn(uuid);
        when(serverAccessor.getPlayer(uuid)).thenReturn(rtpPlayer);

        // 2. Insert the UUID into RTP.getInstance().invulnerablePlayers
        // and register an active TeleportPipelineTask for the player in RTP.getInstance().processingPlayers.
        RTP rtp = RTP.getInstance();
        rtp.invulnerablePlayers.put(uuid, System.currentTimeMillis());
        rtp.processingPlayers.add(uuid);

        RTPCoords coords = new RTPCoords("world", 100, 64, 100);
        RTPWorld rtpWorld = mock(RTPWorld.class);
        when(rtpWorld.name()).thenReturn("world");

        RegionSettings settings = new RegionSettings(
            "default",
            rtpWorld,
            mock(Shape.class),
            mock(VerticalAdjustor.class),
            false, false, 0, 0, 0, 1, "", false);
        Region region = new Region("default", settings);

        TeleportData data = new TeleportData();
        GenerationContext context = new GenerationContext(rtpPlayer, rtpPlayer, null);
        TeleportPipelineTask task = new TeleportPipelineTask(context, region, coords);

        data.nextTask = task;
        data.completed = false;
        rtp.latestTeleportData.put(uuid, data);

        // Mock ChunkSet for cleanup
        ChunkSet chunkSet = mock(ChunkSet.class);
        region.chunkManager.putChunkSet(coords, chunkSet);

        // Fire a simulated PlayerQuitEvent.
        Player bukkitPlayer = mock(Player.class);
        when(bukkitPlayer.getUniqueId()).thenReturn(uuid);
        PlayerQuitEvent event = new PlayerQuitEvent(bukkitPlayer, "Quit");

        OnPlayerQuit listener = new OnPlayerQuit();
        listener.onPlayerQuit(event);

        // 3. Assert that invulnerablePlayers.containsKey(uuid) returns false.
        assertFalse(rtp.invulnerablePlayers.containsKey(uuid));

        // 4. Verify via Mockito (Mockito.verify(...)) that chunkSet.keep(false) was strictly invoked
        // inside a lambda routed to RTP.serverAccessor.getScheduler().runTask(destinationWorld, cx, cz, ...).

        // Verify OnPlayerQuit routed the task to the region scheduler
        verify(scheduler).runTask(eq(rtpWorld), eq(coords.x() >> 4), eq(coords.z() >> 4), eq(task));

        // Simulation: execute the routed task
        task.setCancelled(true);
        task.run();

        // Verify TeleportPipelineTask.runCleanup() routed the final cleanup lambda to the region scheduler
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, atLeastOnce()).runTask(eq(rtpWorld), anyInt(), anyInt(), runnableCaptor.capture());

        // We want to specifically check the one that calls chunkSet.keep(false)
        boolean foundCleanup = false;
        for (Runnable runnable : runnableCaptor.getAllValues()) {
            runnable.run();
            try {
                verify(chunkSet).keep(false, rtpWorld);
                foundCleanup = true;
                break;
            } catch (AssertionError e) {
                // Not this one
            }
        }

        if (!foundCleanup) {
            throw new AssertionError("chunkSet.keep(false) was not invoked via a regional task.");
        }
    }
}
