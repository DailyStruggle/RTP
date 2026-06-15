package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the ADR-065 world-override "teleport trap".
 *
 * <p>{@code /rtp region=default world=world_nether} resolves to a synthetic
 * world-override region (named {@code "<base>_<world>"}) that is stored in
 * {@code SelectionAPI.tempRegions}, not {@code permRegionLookup}. Only regions
 * in {@code permRegionLookup} are ticked by {@code AsyncTaskProcessing}, so
 * {@code Region.execute()} is never invoked for the synthetic region and its
 * teleport waitlist ({@code RegionQueueManager.playerQueue}) is never drained.
 *
 * <p>Before the fix, the queue path enqueued the player on that un-drained
 * waitlist, trapping them in the teleporting state forever. The fix makes
 * {@code QueueTask.fallback()} serve any region that is not pumped (i.e. not
 * identity-present in {@code permRegionLookup}) through the pregen path
 * instead, so the teleport completes on first use rather than hanging.
 */
public class ReqRtpAdr065WorldRegionTeleportTrapTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPWorld world;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());

        world = new MockRTPWorld("trap_world");
        accessor.addWorld(world);
    }

    private Region buildRegion(String name) {
        Circle circle = new Circle();
        circle.setRng(new Random(42L));
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                name, world, circle, vert,
                false, false,
                10L, 1000L, 0L, 5,
                0.0, 1L, "", false);
        return new Region(name, settings);
    }

    /**
     * A region that is NOT registered in {@code permRegionLookup} (a synthetic
     * world-override region) must NOT enqueue the player when its queue is
     * empty, because nothing would ever drain that waitlist.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void unpumpedWorldRegion_doesNotTrapPlayerInQueue() {
        Region synthetic = buildRegion("default_trap_world");
        // Deliberately NOT added to permRegionLookup — mirrors SelectionAPI.worldRegion's tempRegions placement.

        UUID playerId = UUID.randomUUID();
        MockRTPPlayer player =
                new MockRTPPlayer(playerId, "trapped",
                        new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        // Drive the queue path with a null sender so the rtp.unqueued shortcut
        // (MockRTPCommandSender.hasPermission always returns true) is bypassed;
        // the only reason to take the fast path here must be the not-pumped check.
        RTP.serverAccessor.getLocationGenerator()
                .getLocation(synthetic, null, player, null);

        assertFalse(synthetic.queueManager.playerQueue.contains(playerId),
                "un-pumped world-override region must not enqueue the player (teleport trap)");
        assertFalse(RTP.getInstance().queuedPlayers.contains(playerId),
                "un-pumped world-override region must not mark the player as queued (teleport trap)");
    }

    /**
     * Control: a region that IS registered in {@code permRegionLookup} (and is
     * therefore pumped by {@code AsyncTaskProcessing}) still uses the normal
     * enqueue branch when its queue is empty, so the deferred-teleport contract
     * (FM-001) is preserved.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void pumpedRegion_stillEnqueuesPlayer() {
        Region pumped = buildRegion("pumped_region");
        RTP.selectionAPI.permRegionLookup.put(pumped.name, pumped);

        UUID playerId = UUID.randomUUID();
        MockRTPPlayer player =
                new MockRTPPlayer(playerId, "waiter",
                        new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        RTP.serverAccessor.getLocationGenerator()
                .getLocation(pumped, null, player, null);

        assertTrue(pumped.queueManager.playerQueue.contains(playerId),
                "pumped region with an empty queue must enqueue the player (FM-001)");
    }
}
