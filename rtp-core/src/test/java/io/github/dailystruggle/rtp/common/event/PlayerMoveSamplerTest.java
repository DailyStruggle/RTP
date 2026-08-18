package io.github.dailystruggle.rtp.common.event;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.event.PlayerMoveEvent;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PlayerMoveSampler} (ADR-075).
 * Verifies tick-sampled {@link PlayerMoveEvent} baseline establishment, block changes, world changes, and watch filters.
 */
class PlayerMoveSamplerTest {

    private MockRTPServerAccessor accessor;
    private final List<AutoCloseable> handles = new ArrayList<>();

    @BeforeEach
    void setUp() {
        accessor = new MockRTPServerAccessor(new java.io.File("build/tmp/player-move-sampler-test"));
        RTP.serverAccessor = accessor;
    }

    @AfterEach
    void tearDown() {
        for (AutoCloseable h : handles) {
            try {
                h.close();
            } catch (Exception ignored) {
                // best-effort test cleanup
            }
        }
        handles.clear();
        RTP.serverAccessor = null;
    }

    private MockRTPPlayer watchedPlayerAt(List<PlayerMoveEvent> sink, int x, int y, int z) {
        UUID id = UUID.randomUUID();
        MockRTPPlayer player =
                new MockRTPPlayer(id, "p-" + id, new RTPLocation(new MockRTPWorld("world"), x, y, z));
        accessor.addPlayer(player);
        handles.add(RTPAPI.watchPlayerMove(id, sink::add));
        return player;
    }

    @Test
    void firstSampleEstablishesBaselineWithoutFiring() {
        List<PlayerMoveEvent> received = new ArrayList<>();
        watchedPlayerAt(received, 0, 64, 0);

        PlayerMoveSampler sampler = new PlayerMoveSampler();
        sampler.sample();

        assertTrue(received.isEmpty(), "first sample must not fire, only baseline");
    }

    @Test
    void sameWorldBlockChangeFiresWithCorrectFromAndTo() {
        List<PlayerMoveEvent> received = new ArrayList<>();
        MockRTPPlayer player = watchedPlayerAt(received, 10, 64, 20);

        PlayerMoveSampler sampler = new PlayerMoveSampler();
        sampler.sample(); // baseline @ (10,64,20)

        player.setLocation(new RTPLocation(new MockRTPWorld("world"), 11, 65, 20));
        sampler.sample();

        assertEquals(1, received.size());
        PlayerMoveEvent e = received.get(0);
        assertEquals("world", e.worldName());
        assertEquals(10, e.fromX());
        assertEquals(64, e.fromY());
        assertEquals(20, e.fromZ());
        assertEquals(11, e.toX());
        assertEquals(65, e.toY());
        assertEquals(20, e.toZ());
    }

    @Test
    void stayingInSameBlockFiresNothing() {
        List<PlayerMoveEvent> received = new ArrayList<>();
        MockRTPPlayer player = watchedPlayerAt(received, 3, 64, 3);

        PlayerMoveSampler sampler = new PlayerMoveSampler();
        sampler.sample(); // baseline
        // Re-set to the identical block; sub-block motion normalizes to no change.
        player.setLocation(new RTPLocation(new MockRTPWorld("world"), 3, 64, 3));
        sampler.sample();

        assertTrue(received.isEmpty(), "no block change => no event");
    }

    @Test
    void worldChangeResetsBaselineWithoutFiring() {
        List<PlayerMoveEvent> received = new ArrayList<>();
        MockRTPPlayer player = watchedPlayerAt(received, 0, 64, 0);

        PlayerMoveSampler sampler = new PlayerMoveSampler();
        sampler.sample(); // baseline in "world"

        player.setLocation(new RTPLocation(new MockRTPWorld("world_nether"), 5, 64, 5));
        sampler.sample(); // world changed: reset, no fire
        assertTrue(received.isEmpty(), "cross-world jump is not a same-world move");

        // A subsequent same-world block change fires against the new baseline.
        player.setLocation(new RTPLocation(new MockRTPWorld("world_nether"), 6, 64, 5));
        sampler.sample();
        assertEquals(1, received.size());
        assertEquals("world_nether", received.get(0).worldName());
        assertEquals(5, received.get(0).fromX());
        assertEquals(6, received.get(0).toX());
    }

    @Test
    void offlinePlayerProducesNothing() {
        List<PlayerMoveEvent> received = new ArrayList<>();
        MockRTPPlayer player = watchedPlayerAt(received, 0, 64, 0);

        PlayerMoveSampler sampler = new PlayerMoveSampler();
        sampler.sample(); // baseline

        player.setOnline(false);
        player.setLocation(new RTPLocation(new MockRTPWorld("world"), 9, 64, 9));
        sampler.sample();

        assertTrue(received.isEmpty(), "offline player must not fire");
    }

    @Test
    void unwatchedPlayerIsNeverSampled() {
        List<PlayerMoveEvent> received = new ArrayList<>();
        UUID id = UUID.randomUUID();
        MockRTPPlayer player =
                new MockRTPPlayer(id, "unwatched", new RTPLocation(new MockRTPWorld("world"), 0, 64, 0));
        accessor.addPlayer(player);
        // deliberately NOT watched

        PlayerMoveSampler sampler = new PlayerMoveSampler();
        sampler.sample();
        player.setLocation(new RTPLocation(new MockRTPWorld("world"), 1, 64, 0));
        sampler.sample();

        assertTrue(received.isEmpty(), "no watcher => no work, no event");
    }
}
