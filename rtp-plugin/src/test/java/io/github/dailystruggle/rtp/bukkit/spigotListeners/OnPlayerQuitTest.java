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
import io.github.dailystruggle.rtp.common.selection.region.RegionChunkManager;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
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
        when(serverAccessor.getPluginDirectory()).thenReturn(new java.io.File("build/tmp/test"));
        new RTP();
        ConfigParser mockEco = mock(ConfigParser.class);
        when(mockEco.getConfigValue(any(), any())).thenReturn(true);
        RTP.configs.configParserMap.put(EconomyKeys.class, mockEco);
        ConfigParser mockMsg = mock(ConfigParser.class);
        when(mockMsg.getConfigValue(any(), any())).thenReturn("");
        RTP.configs.configParserMap.put(MessagesKeys.class, mockMsg);
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
        when(rtpWorld.id()).thenReturn(UUID.randomUUID());

        RegionSettings settings = new RegionSettings(
                "default",
                rtpWorld,
                mock(Shape.class),
                mock(VerticalAdjustor.class),
                false, false, 0, 0, 0, 1, "", false);
        Region region = new Region("default", settings);

        // Mock ChunkSet for cleanup and inject RegionChunkManager to guarantee retrieval
        ChunkSet chunkSet = mock(ChunkSet.class);
        RegionChunkManager mockRegionChunkManager = mock(RegionChunkManager.class);
        when(mockRegionChunkManager.getChunkSet(any())).thenReturn(chunkSet);
        try {
            java.lang.reflect.Field field = Region.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            field.set(region, mockRegionChunkManager);
        } catch (Exception ignored) {}

        TeleportData data = new TeleportData();
        GenerationContext context = new GenerationContext(rtpPlayer, rtpPlayer, null);
        TeleportPipelineTask task = new TeleportPipelineTask(context, region, coords);

        // INJECT: Manually bind the teleport data so it survives skipping Phase.SETUP
        try {
            java.lang.reflect.Field teleportDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
            teleportDataField.setAccessible(true);
            teleportDataField.set(task, data);
        } catch (Exception e) {
            e.printStackTrace();
        }

        data.nextTask = task;
        data.completed = false;
        rtp.latestTeleportData.put(uuid, data);

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

        // We want to specifically check the one that calls RTPChunkManager.keep(chunkSet, false, rtpWorld)
        RTPChunkManager chunkManager = serverAccessor.getChunkManager();
        boolean foundCleanup = false;
        for (Runnable runnable : runnableCaptor.getAllValues()) {
            runnable.run();
            try {
                // atLeastOnce ensures this passes regardless of loop re-execution
                verify(chunkManager, atLeastOnce()).keep(chunkSet, false, rtpWorld);
                foundCleanup = true;
                break;
            } catch (AssertionError e) {
                // Not this one
            }
        }

        if (!foundCleanup) {
            throw new AssertionError("RTPChunkManager.keep(chunkSet, false, rtpWorld) was not invoked via a regional task.");
        }
    }
}
