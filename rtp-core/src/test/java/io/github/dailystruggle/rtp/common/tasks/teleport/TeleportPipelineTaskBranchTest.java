package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.platform.PlatformCreator;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.pvp.PvPGate;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TeleportPipelineTask branch and edge case coverage")
class TeleportPipelineTaskBranchTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;
    private TrackedMockWorld world;
    private Region region;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        world = new TrackedMockWorld("branch_test_world");
        accessor.addWorld(world);

        RTP.getInstance().databaseAccessor = org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.database.DatabaseAccessor.class);

        RegionSettings settings = new RegionSettings(
                "branch_reg",
                world,
                new Circle(),
                new LinearAdjustor(new ArrayList<>()),
                false,
                false,
                10L,
                100L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        region = new Region("branch_reg", settings);
    }

    @AfterEach
    void tearDown() {
        TeleportPipelineTask.setupPreActions.clear();
        TeleportPipelineTask.setupPostActions.clear();
        TeleportPipelineTask.loadPreActions.clear();
        TeleportPipelineTask.loadPostActions.clear();
        TeleportPipelineTask.teleportPreActions.clear();
        TeleportPipelineTask.teleportPostActions.clear();
        TeleportPipelineTask.cleanupPreActions.clear();
        TeleportPipelineTask.cleanupPostActions.clear();

        RTP.getInstance().latestTeleportData.clear();
        RTP.getInstance().processingPlayers.clear();
        RTP.deathEffectInFlight.clear();
        RTP.pendingDeathTeleports.clear();
        PvPGate.nativeTracker().clearAll();
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys> safety =
                (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys>)
                        RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.class);
        if (safety != null) {
            safety.set(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.pvpCheckEnabled, false);
            safety.set(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.pvpOnCombat, "DENY");
        }
    }

    @Test
    void testCompleteDeathTeleport_respawnedTrue() {
        UUID pid = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(pid, "DeathRespawned", new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        GenerationContext ctx = new GenerationContext(player, player, null);
        RTPCoords target = new RTPCoords(world.name(), 100, 70, 100);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, region, target);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.nextTask = task;
        setField(task, "teleportData", data);

        RTP.deathEffectInFlight.add(pid);
        RTP.pendingDeathTeleports.put(pid, task);

        // Turn on lockAfterUses & postTeleportQueueing to exercise those branches
        long oldUses = TeleportPipelineTask.ConfigCache.lockAfterUses;
        boolean oldPostQueue = TeleportPipelineTask.ConfigCache.postTeleportQueueing;
        TeleportPipelineTask.ConfigCache.lockAfterUses = 5L;
        TeleportPipelineTask.ConfigCache.lockAfterResetMillis = 60000L;
        TeleportPipelineTask.ConfigCache.postTeleportQueueing = true;

        try {
            task.completeDeathTeleport(true);

            assertTrue(data.completed);
            assertFalse(RTP.deathEffectInFlight.contains(pid));
            assertFalse(RTP.pendingDeathTeleports.containsKey(pid));
            assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
        } finally {
            TeleportPipelineTask.ConfigCache.lockAfterUses = oldUses;
            TeleportPipelineTask.ConfigCache.postTeleportQueueing = oldPostQueue;
        }
    }

    @Test
    void testCompleteDeathTeleport_respawnedFalse() {
        UUID pid = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(pid, "DeathCancelled", new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        GenerationContext ctx = new GenerationContext(player, player, null);
        RTPCoords target = new RTPCoords(world.name(), 100, 70, 100);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, region, target);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        setField(task, "teleportData", data);

        RTP.deathEffectInFlight.add(pid);
        RTP.pendingDeathTeleports.put(pid, task);

        task.completeDeathTeleport(false);

        assertFalse(data.completed);
        assertFalse(RTP.deathEffectInFlight.contains(pid));
        assertFalse(RTP.pendingDeathTeleports.containsKey(pid));
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    void testRunTeleport_PvpGateAbort() throws Exception {
        UUID pid = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(pid, "PvpPlayer", new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        GenerationContext ctx = new GenerationContext(player, player, null);
        RTPCoords target = new RTPCoords(world.name(), 200, 70, 200);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, region, target);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        setField(task, "teleportData", data);
        RTP.getInstance().processingPlayers.add(pid);

        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys> safety =
                (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys>)
                        RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.class);
        assertNotNull(safety);
        safety.set(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.pvpCheckEnabled, true);
        safety.set(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.pvpOnCombat, "DENY");
        safety.set(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.pvpCombatTagSeconds, 15L);

        // Stamp player in combat
        PvPGate.nativeTracker().stamp(pid, System.currentTimeMillis());

        Method runTeleport = TeleportPipelineTask.class.getDeclaredMethod("runTeleport");
        runTeleport.setAccessible(true);
        runTeleport.invoke(task);

        assertFalse(data.completed);
        assertFalse(RTP.getInstance().processingPlayers.contains(pid));
        assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
    }

    @Test
    void testBuildArrivalPlatform_customPlatformCreator_successAndDecline() throws Exception {
        RTPCoords target = new RTPCoords(world.name(), 300, 64, 300);
        GenerationContext ctx = new GenerationContext(null, null, null);
        TeleportPipelineTask task = new TeleportPipelineTask(ctx, region, target);

        AtomicBoolean creatorInvoked = new AtomicBoolean(false);
        PlatformCreator successCreator = new PlatformCreator() {
            @Override
            public String creatorName() {
                return "SuccessCreator";
            }

            @Override
            public CompletableFuture<?> prepare(RTPLocation location) {
                return CompletableFuture.completedFuture("prepared-context");
            }

            @Override
            public boolean createPlatform(RTPLocation location, Object preparedContext) {
                creatorInvoked.set(true);
                return true;
            }
        };

        setField(task, "platformCreator", successCreator);
        setField(task, "platformPrepare", CompletableFuture.completedFuture("ctx"));

        Method buildArrivalPlatform = TeleportPipelineTask.class.getDeclaredMethod("buildArrivalPlatform", RTPLocation.class);
        buildArrivalPlatform.setAccessible(true);
        buildArrivalPlatform.invoke(task, new RTPLocation(world, 300, 64, 300));

        assertTrue(creatorInvoked.get());

        // Platform creator throwing exception falls back safely to world.platform
        PlatformCreator throwingCreator = new PlatformCreator() {
            @Override
            public String creatorName() {
                return "ThrowingCreator";
            }

            @Override
            public CompletableFuture<?> prepare(RTPLocation location) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public boolean createPlatform(RTPLocation location, Object preparedContext) {
                throw new RuntimeException("Simulated platform error");
            }
        };
        setField(task, "platformCreator", throwingCreator);
        assertDoesNotThrow(() -> buildArrivalPlatform.invoke(task, new RTPLocation(world, 300, 64, 300)));
    }

    @Test
    void testTeleportOptions_setRespawnAndLockAfter() throws Exception {
        UUID pid = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(pid, "RespawnPlayer", new RTPLocation(world, 0, 64, 0));
        player.setPermission("*", true);
        accessor.addPlayer(player);

        GenerationContext ctx = new GenerationContext(player, player, null);
        RTPCoords target = new RTPCoords(world.name(), 400, 65, 400);

        java.util.List<CompletableFuture<Long>> chunks = new ArrayList<>();
        chunks.add(CompletableFuture.completedFuture(0L));
        io.github.dailystruggle.rtp.api.world.ChunkSet set =
                new io.github.dailystruggle.rtp.api.world.ChunkSet(world, 400 >> 4, 400 >> 4, chunks, CompletableFuture.completedFuture(true));
        io.github.dailystruggle.rtp.api.world.ChunkReservation res =
                new io.github.dailystruggle.rtp.api.world.ChunkReservation(set, world);

        TeleportPipelineTask task = new TeleportPipelineTask(ctx, region, target, res);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        setField(task, "teleportData", data);

        boolean oldRespawn = TeleportPipelineTask.ConfigCache.setRespawnOnTeleport;
        long oldUses = TeleportPipelineTask.ConfigCache.lockAfterUses;
        boolean oldPostQueue = TeleportPipelineTask.ConfigCache.postTeleportQueueing;

        TeleportPipelineTask.ConfigCache.setRespawnOnTeleport = true;
        TeleportPipelineTask.ConfigCache.lockAfterUses = 3L;
        TeleportPipelineTask.ConfigCache.lockAfterResetMillis = 3600000L;
        TeleportPipelineTask.ConfigCache.postTeleportQueueing = true;

        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys> cfg =
                (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys>)
                        RTP.configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.class);
        if (cfg != null) {
            cfg.set(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.setRespawnOnTeleport, true);
        }

        try {
            Method runTeleport = TeleportPipelineTask.class.getDeclaredMethod("runTeleport");
            runTeleport.setAccessible(true);
            runTeleport.invoke(task);

            accessor.getMockScheduler().tick(1);
            accessor.getMockScheduler().tick(1);
            accessor.getMockScheduler().tick(1);

            assertTrue(data.completed);
            assertEquals(400, player.getLocation().x());
            assertEquals(65, player.getLocation().y());
            assertEquals(400, player.getLocation().z());
            assertNotNull(player.getRespawnLocation());
            assertEquals(400, player.getRespawnLocation().x());
        } finally {
            TeleportPipelineTask.ConfigCache.setRespawnOnTeleport = oldRespawn;
            TeleportPipelineTask.ConfigCache.lockAfterUses = oldUses;
            TeleportPipelineTask.ConfigCache.postTeleportQueueing = oldPostQueue;
            if (cfg != null) {
                cfg.set(io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys.setRespawnOnTeleport, oldRespawn);
            }
        }
    }

    private static void setField(Object target, String name, Object val) {
        try {
            Field f = TeleportPipelineTask.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, val);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
