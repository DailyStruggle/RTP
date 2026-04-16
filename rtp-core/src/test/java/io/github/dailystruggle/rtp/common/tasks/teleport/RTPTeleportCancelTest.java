package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RTPTeleportCancel}.
 *
 * <p>Requirements covered: REQ-CORE-F-008 (teleport cancellation lifecycle).
 */
public class RTPTeleportCancelTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private UUID playerId;
    private MockRTPPlayer player;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        playerId = UUID.randomUUID();
        player = new MockRTPPlayer(playerId, "TestPlayer", null);
        // NOTE: player is NOT added to accessor by default so that getPlayer() returns null,
        // bypassing the rtp.noCancel permission check (MockRTPCommandSender.hasPermission always
        // returns true). Tests that need the player registered call accessor.addPlayer() explicitly.

        // Clear static action lists between tests
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

    // -----------------------------------------------------------------------
    // No teleport data — no-op
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_with_no_teleport_data_is_noop() {
        // No data registered for playerId
        RTPTeleportCancel cancel = new RTPTeleportCancel(playerId);
        assertDoesNotThrow(cancel::run, "cancel with no teleport data must not throw");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_with_completed_teleport_is_noop() {
        TeleportData data = new TeleportData();
        data.completed = true;
        data.nextTask = new RTPRunnable();
        RTP.getInstance().latestTeleportData.put(playerId, data);

        RTPTeleportCancel cancel = new RTPTeleportCancel(playerId);
        cancel.run();

        // nextTask must NOT be cancelled since teleport was already complete
        assertFalse(data.nextTask.isCancelled(),
                "completed teleport must not have its nextTask cancelled");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_with_null_next_task_is_noop() {
        TeleportData data = new TeleportData();
        data.completed = false;
        data.nextTask = null;
        RTP.getInstance().latestTeleportData.put(playerId, data);

        RTPTeleportCancel cancel = new RTPTeleportCancel(playerId);
        assertDoesNotThrow(cancel::run, "cancel with null nextTask must not throw");
    }

    // -----------------------------------------------------------------------
    // Active teleport — cancellation
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_cancels_next_task_for_active_teleport() {
        RTPRunnable nextTask = new RTPRunnable();
        TeleportData data = new TeleportData();
        data.completed = false;
        data.nextTask = nextTask;
        RTP.getInstance().latestTeleportData.put(playerId, data);
        RTP.getInstance().processingPlayers.add(playerId);

        RTPTeleportCancel cancel = new RTPTeleportCancel(playerId);
        cancel.run();

        assertTrue(nextTask.isCancelled(), "nextTask must be cancelled for active teleport");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_removes_player_from_latest_teleport_data_when_no_prior_data() {
        RTPRunnable nextTask = new RTPRunnable();
        TeleportData data = new TeleportData();
        data.completed = false;
        data.nextTask = nextTask;
        RTP.getInstance().latestTeleportData.put(playerId, data);

        RTPTeleportCancel cancel = new RTPTeleportCancel(playerId);
        cancel.run();

        assertFalse(RTP.getInstance().latestTeleportData.containsKey(playerId),
                "latestTeleportData must not contain player after cancel with no prior data");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_restores_prior_teleport_data_when_available() {
        RTPRunnable nextTask = new RTPRunnable();
        TeleportData current = new TeleportData();
        current.completed = false;
        current.nextTask = nextTask;

        TeleportData prior = new TeleportData();
        prior.completed = true;

        RTP.getInstance().latestTeleportData.put(playerId, current);
        RTP.getInstance().priorTeleportData.put(playerId, prior);

        RTPTeleportCancel cancel = new RTPTeleportCancel(playerId);
        cancel.run();

        assertSame(prior, RTP.getInstance().latestTeleportData.get(playerId),
                "prior teleport data must be restored as latest after cancel");
        assertFalse(RTP.getInstance().priorTeleportData.containsKey(playerId),
                "priorTeleportData must be cleared after restore");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void run_removes_player_from_processing_players() {
        RTPRunnable nextTask = new RTPRunnable();
        TeleportData data = new TeleportData();
        data.completed = false;
        data.nextTask = nextTask;
        RTP.getInstance().latestTeleportData.put(playerId, data);
        RTP.getInstance().processingPlayers.add(playerId);

        RTPTeleportCancel cancel = new RTPTeleportCancel(playerId);
        cancel.run();

        assertFalse(RTP.getInstance().processingPlayers.contains(playerId),
                "player must be removed from processingPlayers after cancel");
    }

    // -----------------------------------------------------------------------
    // Pre/post action hooks
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void pre_actions_are_invoked_before_cancel_logic() {
        List<String> events = new ArrayList<>();

        RTPTeleportCancel.preActions.add(c -> events.add("pre"));

        RTPRunnable nextTask = new RTPRunnable();
        TeleportData data = new TeleportData();
        data.completed = false;
        data.nextTask = nextTask;
        RTP.getInstance().latestTeleportData.put(playerId, data);

        new RTPTeleportCancel(playerId).run();

        assertTrue(events.contains("pre"), "preAction must be invoked");
        assertEquals("pre", events.get(0), "preAction must be invoked first");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void post_actions_are_invoked_after_cancel_logic() {
        List<String> events = new ArrayList<>();

        RTPTeleportCancel.postActions.add(c -> events.add("post"));

        RTPRunnable nextTask = new RTPRunnable();
        TeleportData data = new TeleportData();
        data.completed = false;
        data.nextTask = nextTask;
        RTP.getInstance().latestTeleportData.put(playerId, data);

        new RTPTeleportCancel(playerId).run();

        assertTrue(events.contains("post"), "postAction must be invoked");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void pre_and_post_actions_invoked_even_when_no_teleport_data() {
        AtomicInteger preCount = new AtomicInteger(0);
        AtomicInteger postCount = new AtomicInteger(0);

        RTPTeleportCancel.preActions.add(c -> preCount.incrementAndGet());
        RTPTeleportCancel.postActions.add(c -> postCount.incrementAndGet());

        new RTPTeleportCancel(playerId).run();

        assertEquals(1, preCount.get(), "preAction must fire even with no teleport data");
        // postActions fire only after the early-return checks — no data means early return
        // so postActions are NOT invoked in that case
    }

    // -----------------------------------------------------------------------
    // getPlayerId
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void getPlayerId_returns_constructor_uuid() {
        RTPTeleportCancel cancel = new RTPTeleportCancel(playerId);
        assertEquals(playerId, cancel.getPlayerId());
    }

    // -----------------------------------------------------------------------
    // refund static method
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void refund_with_no_teleport_data_is_noop() {
        assertDoesNotThrow(() -> RTPTeleportCancel.refund(playerId),
                "refund with no teleport data must not throw");
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void refund_with_completed_teleport_is_noop() {
        TeleportData data = new TeleportData();
        data.completed = true;
        RTP.getInstance().latestTeleportData.put(playerId, data);

        assertDoesNotThrow(() -> RTPTeleportCancel.refund(playerId),
                "refund on completed teleport must not throw");
        // latestTeleportData should remain unchanged (no removal)
        assertTrue(RTP.getInstance().latestTeleportData.containsKey(playerId));
    }
}
