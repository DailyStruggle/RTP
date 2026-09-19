package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RTPTeleportCancel mutation & behaviour tests")
class RTPTeleportCancelTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPWorld world;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        world = new MockRTPWorld("cancel_world");
        accessor.addWorld(world);
        RTPTeleportCancel.preActions.clear();
        RTPTeleportCancel.postActions.clear();
    }

    @AfterEach
    void tearDown() {
        RTPTeleportCancel.preActions.clear();
        RTPTeleportCancel.postActions.clear();
        RTP.getInstance().latestTeleportData.clear();
        RTP.getInstance().priorTeleportData.clear();
        RTP.getInstance().processingPlayers.clear();
    }

    @Test
    void getPlayerId_returns_instantiated_playerId() {
        UUID id = UUID.randomUUID();
        RTPTeleportCancel cancel = new RTPTeleportCancel(id);
        assertEquals(id, cancel.getPlayerId());
    }

    @Test
    void preAndPostActions_are_executed_during_run() {
        UUID id = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(id, "CancelP", new RTPLocation(world, 0, 64, 0)) {
            @Override
            public boolean hasPermission(String perm) {
                if ("rtp.noCancel".equalsIgnoreCase(perm)) return false;
                return true;
            }
        };
        accessor.addPlayer(player);

        AtomicInteger preCount = new AtomicInteger();
        AtomicInteger postCount = new AtomicInteger();

        RTPTeleportCancel.preActions.add(c -> preCount.incrementAndGet());
        RTPTeleportCancel.postActions.add(c -> postCount.incrementAndGet());

        TeleportData data = new TeleportData();
        data.sender = player;
        data.completed = false;
        TeleportPipelineTask task = new TeleportPipelineTask(new io.github.dailystruggle.rtp.api.selection.GenerationContext(player, player, null));
        data.nextTask = task;
        RTP.getInstance().latestTeleportData.put(id, data);

        RTPTeleportCancel cancel = new RTPTeleportCancel(id);
        cancel.run();

        assertEquals(1, preCount.get(), "preAction must be executed exactly once");
        assertEquals(1, postCount.get(), "postAction must be executed exactly once");
        assertTrue(task.isCancelled());
    }

    @Test
    void run_invokes_refund_and_message() {
        UUID id = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(id, "CancelP2", new RTPLocation(world, 0, 64, 0));
        player.setPermission("rtp.noCancel", false);
        accessor.addPlayer(player);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.completed = false;
        TeleportPipelineTask task = new TeleportPipelineTask(new io.github.dailystruggle.rtp.api.selection.GenerationContext(player, player, null));
        data.nextTask = task;
        data.selectedCoords = new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 10, 64, 10);
        RTP.getInstance().latestTeleportData.put(id, data);
        RTP.getInstance().processingPlayers.add(id);

        RTPTeleportCancel cancel = new RTPTeleportCancel(id);
        cancel.run();

        // 1. refund removes latestTeleportData and removes processingPlayers
        assertFalse(RTP.getInstance().processingPlayers.contains(id), "refund should remove player from processingPlayers");
        assertNull(RTP.getInstance().latestTeleportData.get(id), "refund should remove latestTeleportData");

        // 2. message sends teleportCancel message to player
        assertFalse(player.sentMessages.isEmpty(), "Player should receive cancel message during run");
    }

    @Test
    void message_delivers_configured_teleportCancel_message() {
        UUID id = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(id, "MsgP", new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        RTPTeleportCancel.message(id);

        assertFalse(player.sentMessages.isEmpty(), "Player should receive cancellation message");
    }

    @Test
    void refund_restores_priorTeleportData_when_present() {
        UUID id = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(id, "PriorP", new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        TeleportData currentData = new TeleportData();
        currentData.completed = false;
        RTP.getInstance().latestTeleportData.put(id, currentData);

        TeleportData priorData = new TeleportData();
        priorData.completed = true;
        priorData.attempts = 42;
        RTP.getInstance().priorTeleportData.put(id, priorData);

        RTPTeleportCancel.refund(id);

        assertFalse(RTP.getInstance().priorTeleportData.containsKey(id));
        assertSame(priorData, RTP.getInstance().latestTeleportData.get(id));
    }

    @Test
    void refund_executes_economy_refund_when_configured() {
        UUID id = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(id, "EcoP", new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        TeleportData currentData = new TeleportData();
        currentData.completed = false;
        currentData.cost = 50.0;
        currentData.sender = player;
        RTP.getInstance().latestTeleportData.put(id, currentData);

        AtomicReference<UUID> refundedUser = new AtomicReference<>();
        AtomicReference<Double> refundedAmount = new AtomicReference<>(0.0);
        RTP.economy = new RTPEconomy() {
            @Override
            public void give(UUID player, double amount) {
                refundedUser.set(player);
                refundedAmount.set(amount);
            }

            @Override
            public boolean take(UUID player, double amount) { return false; }
            @Override
            public double bal(UUID player) { return 100.0; }
        };

        RTPTeleportCancel.refund(id);

        assertEquals(id, refundedUser.get());
        assertEquals(50.0, refundedAmount.get(), 0.001);
    }

    @Test
    void noCancel_permission_prevents_cancellation() {
        UUID id = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(id, "NoCancelP", new RTPLocation(world, 0, 64, 0)) {
            @Override
            public boolean hasPermission(String permission) {
                if ("rtp.noCancel".equalsIgnoreCase(permission)) return true;
                return super.hasPermission(permission);
            }
        };
        accessor.addPlayer(player);

        TeleportData data = new TeleportData();
        data.sender = player;
        data.completed = false;
        TeleportPipelineTask task = new TeleportPipelineTask(new io.github.dailystruggle.rtp.api.selection.GenerationContext(player, player, null));
        data.nextTask = task;
        RTP.getInstance().latestTeleportData.put(id, data);

        RTPTeleportCancel cancel = new RTPTeleportCancel(id);
        cancel.run();

        assertFalse(task.isCancelled(), "Task should not be cancelled if player has rtp.noCancel permission");
    }

    @Test
    void refund_null_teleport_data_noops() {
        UUID id = UUID.randomUUID();
        assertDoesNotThrow(() -> RTPTeleportCancel.refund(id));
    }

    @Test
    void refund_completed_teleport_data_noops() {
        UUID id = UUID.randomUUID();
        TeleportData data = new TeleportData();
        data.completed = true;
        RTP.getInstance().latestTeleportData.put(id, data);

        RTPTeleportCancel.refund(id);
        assertSame(data, RTP.getInstance().latestTeleportData.get(id));
    }

    @Test
    void refund_removes_latestTeleportData_when_no_prior_data() {
        UUID id = UUID.randomUUID();
        TeleportData data = new TeleportData();
        data.completed = false;
        RTP.getInstance().latestTeleportData.put(id, data);
        RTP.getInstance().processingPlayers.add(id);

        RTPTeleportCancel.refund(id);
        assertNull(RTP.getInstance().latestTeleportData.get(id));
        assertFalse(RTP.getInstance().processingPlayers.contains(id));
    }

    @Test
    void refund_with_zero_cost_does_not_call_economy() {
        UUID id = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(id, "ZeroCostP", new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        TeleportData currentData = new TeleportData();
        currentData.completed = false;
        currentData.cost = 0.0;
        currentData.sender = player;
        RTP.getInstance().latestTeleportData.put(id, currentData);

        AtomicInteger giveCalls = new AtomicInteger(0);
        RTP.economy = new RTPEconomy() {
            @Override public void give(UUID player, double amount) { giveCalls.incrementAndGet(); }
            @Override public boolean take(UUID player, double amount) { return false; }
            @Override public double bal(UUID player) { return 0; }
        };

        RTPTeleportCancel.refund(id);
        assertEquals(0, giveCalls.get());
    }

    @Test
    void run_noops_when_teleportData_is_null_or_completed_or_nextTask_is_null() {
        UUID id = UUID.randomUUID();
        RTPTeleportCancel cancel = new RTPTeleportCancel(id);
        assertDoesNotThrow(cancel::run);

        TeleportData data = new TeleportData();
        data.completed = true;
        RTP.getInstance().latestTeleportData.put(id, data);
        assertDoesNotThrow(cancel::run);

        data.completed = false;
        data.nextTask = null;
        assertDoesNotThrow(cancel::run);
    }

    @Test
    void refund_handles_null_configs_and_missing_eco_parser() {
        UUID id = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.configuration.Configs savedConfigs = RTP.configs;
        try {
            RTP.configs = null;
            assertDoesNotThrow(() -> RTPTeleportCancel.refund(id));

            RTP.configs = new io.github.dailystruggle.rtp.common.configuration.Configs(tempDir);
            RTP.configs.configParserMap = null;
            assertDoesNotThrow(() -> RTPTeleportCancel.refund(id));

            RTP.configs.configParserMap = new java.util.concurrent.ConcurrentHashMap<>();
            assertDoesNotThrow(() -> RTPTeleportCancel.refund(id));
        } finally {
            RTP.configs = savedConfigs;
        }
    }

    @Test
    void refund_handles_string_and_custom_object_config_values() {
        UUID id = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(id, "EcoStringP", new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys> eco =
            (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys>)
                RTP.configs.configParserMap.get(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.class);

        AtomicInteger refundCalls = new AtomicInteger();
        RTP.economy = new RTPEconomy() {
            @Override public void give(UUID player, double amount) { refundCalls.incrementAndGet(); }
            @Override public boolean take(UUID player, double amount) { return false; }
            @Override public double bal(UUID player) { return 0; }
        };

        // 1. String configValue "true"
        eco.set(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.refundOnCancel, "true");
        TeleportData data = new TeleportData();
        data.completed = false;
        data.cost = 10.0;
        data.sender = player;
        data.selectedCoords = new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 1, 64, 1);
        io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle circle =
            new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle();
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor vert =
            new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new java.util.ArrayList<>());
        io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings =
            new io.github.dailystruggle.rtp.common.selection.region.RegionSettings(
                "dummy", world, circle, vert, false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        data.targetRegion = new io.github.dailystruggle.rtp.common.selection.region.Region("dummy", settings);
        RTP.getInstance().latestTeleportData.put(id, data);
        RTPTeleportCancel.refund(id);
        assertEquals(1, refundCalls.get());

        // 2. Custom Object configValue whose toString() is "false"
        eco.set(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.refundOnCancel, new Object() {
            @Override public String toString() { return "false"; }
        });
        TeleportData data2 = new TeleportData();
        data2.completed = false;
        data2.cost = 10.0;
        data2.sender = player;
        RTP.getInstance().latestTeleportData.put(id, data2);
        RTPTeleportCancel.refund(id);
        assertEquals(1, refundCalls.get(), "False refundOnCancel should not issue refund");

        // 3. Sender is non-player (e.g. console/server)
        eco.set(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.refundOnCancel, true);
        TeleportData data3 = new TeleportData();
        data3.completed = false;
        data3.cost = 10.0;
        data3.sender = new io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender(UUID.randomUUID(), "CONSOLE");
        RTP.getInstance().latestTeleportData.put(id, data3);
        RTPTeleportCancel.refund(id);
        assertEquals(1, refundCalls.get(), "Non-player sender should not receive player refund");
    }

    @Test
    void run_handles_null_player_and_offline_player() {
        UUID id = UUID.randomUUID();
        // player is null in accessor
        TeleportData data = new TeleportData();
        data.completed = false;
        TeleportPipelineTask task = new TeleportPipelineTask(new io.github.dailystruggle.rtp.api.selection.GenerationContext(null, null, null));
        data.nextTask = task;
        RTP.getInstance().latestTeleportData.put(id, data);

        RTPTeleportCancel cancel = new RTPTeleportCancel(id);
        cancel.run();
        assertTrue(task.isCancelled());

        // player is offline
        UUID id2 = UUID.randomUUID();
        MockRTPPlayer offlinePlayer = new MockRTPPlayer(id2, "OfflineP", new RTPLocation(world, 0, 64, 0)) {
            @Override public boolean isOnline() { return false; }
            @Override public boolean hasPermission(String permission) { return true; }
        };
        accessor.addPlayer(offlinePlayer);

        TeleportData data2 = new TeleportData();
        data2.completed = false;
        TeleportPipelineTask task2 = new TeleportPipelineTask(new io.github.dailystruggle.rtp.api.selection.GenerationContext(offlinePlayer, offlinePlayer, null));
        data2.nextTask = task2;
        RTP.getInstance().latestTeleportData.put(id2, data2);

        RTPTeleportCancel cancel2 = new RTPTeleportCancel(id2);
        cancel2.run();
        assertTrue(task2.isCancelled());
    }
}
