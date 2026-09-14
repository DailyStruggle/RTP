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
}
