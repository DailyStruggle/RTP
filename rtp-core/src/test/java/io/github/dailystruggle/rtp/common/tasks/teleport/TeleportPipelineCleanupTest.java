package io.github.dailystruggle.rtp.common.tasks.teleport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionChunkManager;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.common.selection.region.GenerationResult;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class TeleportPipelineCleanupTest {
    private RTP rtp;
    private RTPScheduler scheduler;
    private RTPServerAccessor serverAccessor;
    private RTPChunkManager rtpChunkManager;
    private Region region;
    private RegionChunkManager regionChunkManager;
    private RTPPlayer player;
    private RTPWorld world;
    private GenerationContext context;
    private Configs configs;
    private ConfigParser<PerformanceKeys> performanceConfig;
    private ConfigParser<MessagesKeys> messagesConfig;
    private ConfigParser<ConfigKeys> configKeysConfig;

    @BeforeEach
    void setUp() {
        scheduler = mock(RTPScheduler.class);
        serverAccessor = mock(RTPServerAccessor.class);
        rtpChunkManager = mock(RTPChunkManager.class);

        RTP.scheduler = scheduler;
        RTP.serverAccessor = serverAccessor;
        when(serverAccessor.createTaskPipe()).thenReturn(mock(io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe.class));
        when(serverAccessor.getPluginDirectory()).thenReturn(new java.io.File("."));

        rtp = new RTP();
        region = mock(Region.class);
        regionChunkManager = mock(RegionChunkManager.class);
        player = mock(RTPPlayer.class);
        world = mock(RTPWorld.class);
        configs = mock(Configs.class);
        performanceConfig = mock(ConfigParser.class);
        messagesConfig = mock(ConfigParser.class);
        configKeysConfig = mock(ConfigParser.class);

        RTP.configs = configs;

        when(serverAccessor.getChunkManager()).thenReturn(rtpChunkManager);
        rtp.databaseAccessor = mock(DatabaseAccessor.class);

        UUID playerId = UUID.randomUUID();
        when(player.uuid()).thenReturn(playerId);
        when(player.getLocation()).thenReturn(new RTPLocation(world, 0, 64, 0));
        when(player.delay()).thenReturn(0L);
        when(world.name()).thenReturn("world");
        when(serverAccessor.getRTPWorld("world")).thenReturn(world);
        when(region.getWorld()).thenReturn(world);

        try {
            java.lang.reflect.Field field = Region.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            field.set(region, regionChunkManager);

            java.lang.reflect.Field inflightField = Region.class.getDeclaredField("inFlightCalculations");
            inflightField.setAccessible(true);
            inflightField.set(region, new AtomicInteger(0));
        } catch (Exception e) {
            e.printStackTrace();
        }

        when(configs.getParser(PerformanceKeys.class)).thenReturn(performanceConfig);
        when(configs.getParser(MessagesKeys.class)).thenReturn(messagesConfig);
        when(configs.getParser(ConfigKeys.class)).thenReturn(configKeysConfig);
        when(performanceConfig.getNumber(any(), any())).thenReturn(0L);
        when(messagesConfig.getConfigValue(any(), any())).thenReturn("");

        context = new GenerationContext(player, player, null);
    }

    @Test
    void testCleanupPostSuccessfulTeleport() {
        try (MockedStatic<RTP> rtpStatic = mockStatic(RTP.class)) {
            rtpStatic.when(RTP::getInstance).thenReturn(rtp);

            RTPCoords coords = new RTPCoords("world", 100, 64, 100);
            CompletableFuture<Boolean> complete = new CompletableFuture<>();
            ChunkSet chunkSet = new ChunkSet(new ArrayList<>(), complete);
            complete.complete(true);

            when(region.getLocation(context)).thenReturn(new GenerationResult(coords, 1L, chunkSet));
            when(regionChunkManager.chunks(eq(coords), anyLong())).thenReturn(chunkSet);
            when(regionChunkManager.getChunkSet(coords)).thenReturn(chunkSet);
            when(player.setLocation(any())).thenReturn(CompletableFuture.completedFuture(true));

            TeleportPipelineTask task = new TeleportPipelineTask(context, region);

            // 1. Initialize and push through SETUP and TELEPORT phases
            task.setPhase(TeleportPipelineTask.Phase.SETUP);
            task.run(); // runSetup - populates latestTeleportData

            assertTrue(rtp.latestTeleportData.containsKey(player.uuid()), "latestTeleportData should be populated after SETUP");

            task.setPhase(TeleportPipelineTask.Phase.TELEPORT);
            task.run(); // runTeleport - populates invulnerablePlayers

            assertTrue(rtp.invulnerablePlayers.containsKey(player.uuid()), "invulnerablePlayers should be populated after TELEPORT");

            // 2. Manually transition to CLEANUP
            task.setPhase(TeleportPipelineTask.Phase.CLEANUP);
            task.run(); // runCleanup

            // 3. Assert cleared
            assertFalse(rtp.latestTeleportData.containsKey(player.uuid()), "latestTeleportData should be cleared after CLEANUP");
            assertFalse(rtp.invulnerablePlayers.containsKey(player.uuid()), "invulnerablePlayers should be cleared after CLEANUP");
        }
    }

    @Test
    void testCleanupPostCancelledTeleport() {
        try (MockedStatic<RTP> rtpStatic = mockStatic(RTP.class)) {
            rtpStatic.when(RTP::getInstance).thenReturn(rtp);

            RTPCoords coords = new RTPCoords("world", 100, 64, 100);
            ChunkSet chunkSet = new ChunkSet(new ArrayList<>(), new CompletableFuture<>());

            when(region.getLocation(context)).thenReturn(new GenerationResult(coords, 1L, chunkSet));
            when(regionChunkManager.chunks(eq(coords), anyLong())).thenReturn(chunkSet);
            when(regionChunkManager.getChunkSet(coords)).thenReturn(chunkSet);

            TeleportPipelineTask task = new TeleportPipelineTask(context, region);

            task.setPhase(TeleportPipelineTask.Phase.SETUP);
            task.run(); // runSetup - populates latestTeleportData

            assertTrue(rtp.latestTeleportData.containsKey(player.uuid()));

            // Simulate cancellation before teleport
            task.setCancelled(true);

            // Manually transition to CLEANUP (or it might transition automatically if run() is called in Phase.LOAD/TELEPORT when cancelled)
            task.setPhase(TeleportPipelineTask.Phase.CLEANUP);
            task.run(); // runCleanup

            // 3. Assert cleared
            assertFalse(rtp.latestTeleportData.containsKey(player.uuid()), "latestTeleportData should be cleared after CLEANUP (cancelled)");
            assertFalse(rtp.invulnerablePlayers.containsKey(player.uuid()), "invulnerablePlayers should be cleared after CLEANUP (cancelled)");
        }
    }
}
