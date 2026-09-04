package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.cache.HotBudgetAllocator;
import io.github.dailystruggle.rtp.common.selection.region.cache.HotSink;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests verifying ADR-078 Phase 3 & 4 in {@link RegionQueueManager}:
 * <ul>
 *   <li>{@link RegionQueueManager#hotSinks()} registration and lifecycle.</li>
 *   <li>Stage wrapping behind {@link io.github.dailystruggle.rtp.common.selection.region.cache.RingCacheStage}.</li>
 *   <li>Centralized disposal and demotion transitions.</li>
 *   <li>Need-based zero-I/O rebalancing integration with {@link HotBudgetAllocator}.</li>
 * </ul>
 */
class RegionQueueManagerHotSinkLifecycleTest {

    @TempDir
    Path tempDir;

    private Region region;
    private RegionQueueManager qm;
    private MockRTPWorld world;

    private static final class CountingReservation extends ChunkReservation {
        final AtomicInteger closes = new AtomicInteger();

        CountingReservation(MockRTPWorld w, int cx, int cz) {
            super(new ChunkSet(w, cx, cz,
                    List.of(CompletableFuture.completedFuture(((long) cx << 32) | (cz & 0xFFFFFFFFL))),
                    new CompletableFuture<>()), w);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            super.close();
        }
    }

    private static RTPLocation reservedLoc(MockRTPWorld w, int x, int z, ChunkReservation res) {
        return new RTPLocation(new RTPCoords(w.name(), x, 64, z), 1L, res);
    }

    private static RTPLocation bareLoc(MockRTPWorld w, int x, int z) {
        return new RTPLocation(new RTPCoords(w.name(), x, 64, z), 1L, null);
    }

    @BeforeEach
    void setUp() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir.toFile());
        world = new MockRTPWorld("test_world");
        accessor.addWorld(world);

        RegionSettings settings = new RegionSettings(
                "test_region", world,
                new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false,
                32L, 1000L, 8L, 8,
                0.0, 1L, "", false);
        region = new Region("test_region", settings);
        qm = region.queueManager;
    }

    @Test
    @DisplayName("hotSinks() exposes registered sinks and their leasing contracts")
    void hotSinks_registrationAndLeasing() {
        Collection<HotSink<RTPLocation>> sinks = qm.hotSinks();
        assertNotNull(sinks);

        Map<String, HotSink<RTPLocation>> sinkMap = new HashMap<>();
        for (HotSink<RTPLocation> sink : sinks) {
            sinkMap.put(sink.name(), sink);
        }

        assertTrue(sinkMap.containsKey("keptLocations"));
        assertTrue(sinkMap.containsKey("networkKeptLocations"));
        assertTrue(sinkMap.containsKey("perPlayerLocationQueue"));
        assertFalse(sinkMap.containsKey("loginLocations"), "loginLocations not enabled yet");

        HotSink<RTPLocation> kept = sinkMap.get("keptLocations");
        assertFalse(kept.isExternallyLeased(), "general kept pool is not leased");
        assertEquals(qm.unkeptStage, kept.coldSource());
        assertEquals(qm.keptStage, kept.stage());

        HotSink<RTPLocation> netKept = sinkMap.get("networkKeptLocations");
        assertTrue(netKept.isExternallyLeased(), "networkKeptLocations is pinned to token leases");

        HotSink<RTPLocation> personal = sinkMap.get("perPlayerLocationQueue");
        assertTrue(personal.isExternallyLeased(), "per-player queues are pinned to player leases");
    }

    @Test
    @DisplayName("enableLoginCache and disableLoginCache toggle login sink registration dynamically")
    void loginSink_dynamicLifecycle() {
        assertFalse(qm.hotSinks().stream().anyMatch(s -> s.name().equals("loginLocations")));

        qm.enableLoginCache(8);
        assertTrue(qm.hotSinks().stream().anyMatch(s -> s.name().equals("loginLocations")));
        HotSink<RTPLocation> loginSink = qm.hotSinks().stream()
                .filter(s -> s.name().equals("loginLocations"))
                .findFirst()
                .orElseThrow();
        assertFalse(loginSink.isExternallyLeased());
        assertEquals(qm.unkeptStage, loginSink.coldSource());
        assertEquals(qm.loginStage, loginSink.stage());

        qm.disableLoginCache();
        assertFalse(qm.hotSinks().stream().anyMatch(s -> s.name().equals("loginLocations")));
        assertNull(qm.loginStage);
        assertNull(qm.loginLocations);
    }

    @Test
    @DisplayName("HotSink accepts fails closed on null, null reservation, or wrong world/bounds")
    void hotSink_accepts_failsClosed() {
        HotSink<RTPLocation> kept = qm.hotSinks().stream()
                .filter(s -> s.name().equals("keptLocations"))
                .findFirst()
                .orElseThrow();

        // Null location
        assertFalse(kept.accepts(null));

        // Bare location without reservation
        assertFalse(kept.accepts(bareLoc(world, 10, 10)));

        // Wrong world
        MockRTPWorld otherWorld = new MockRTPWorld("other_world");
        CountingReservation resOther = new CountingReservation(otherWorld, 0, 0);
        assertFalse(kept.accepts(reservedLoc(otherWorld, 10, 10, resOther)));
        resOther.close();

        // Valid location with reservation in correct world
        CountingReservation resGood = new CountingReservation(world, 0, 0);
        RTPLocation goodLoc = reservedLoc(world, 0, 0, resGood);
        assertTrue(kept.accepts(goodLoc));
        resGood.close();
    }

    @Test
    @DisplayName("Zero-I/O rebalance transfers surplus between kept and login sinks without ticket churn")
    void zeroIoRebalance_betweenKeptAndLogin() {
        qm.enableLoginCache(8);

        CountingReservation resA = new CountingReservation(world, 1, 1);
        CountingReservation resB = new CountingReservation(world, 2, 2);
        qm.keptLocations.offer(reservedLoc(world, 16, 16, resA));
        qm.keptLocations.offer(reservedLoc(world, 32, 32, resB));

        assertEquals(2, qm.keptStage.size());
        assertEquals(0, qm.loginStage.size());

        HotBudgetAllocator allocator = new HotBudgetAllocator();
        Map<String, Integer> targets = Map.of(
                "keptLocations", 1,
                "loginLocations", 1
        );

        int moved = allocator.rebalance(qm.hotSinks(), targets);
        assertEquals(1, moved, "1 surplus entry should move from kept to login");
        assertEquals(1, qm.keptStage.size());
        assertEquals(1, qm.loginStage.size());

        // Zero chunk ticket closures occurred during the transfer
        assertEquals(0, resA.closes.get());
        assertEquals(0, resB.closes.get());

        // Both locations remain valid and accessible from their respective stages
        Optional<RTPLocation> fromLogin = qm.loginStage.poll();
        assertTrue(fromLogin.isPresent());
        assertNotNull(fromLogin.get().reservation());

        Optional<RTPLocation> fromKept = qm.keptStage.poll();
        assertTrue(fromKept.isPresent());
        assertNotNull(fromKept.get().reservation());

        fromLogin.get().reservation().close();
        fromKept.get().reservation().close();
    }

    @Test
    @DisplayName("REQ-RTP-S-002: shutDown closes all hot stage reservations deterministically")
    void shutDown_closesAllHotStageReservations() {
        qm.enableLoginCache(4);

        CountingReservation keptRes = new CountingReservation(world, 1, 1);
        CountingReservation loginRes = new CountingReservation(world, 2, 2);
        CountingReservation netRes = new CountingReservation(world, 3, 3);
        CountingReservation personalRes = new CountingReservation(world, 4, 4);

        qm.keptLocations.offer(reservedLoc(world, 16, 16, keptRes));
        qm.loginLocations.offer(reservedLoc(world, 32, 32, loginRes));
        qm.networkKeptLocations.offer(reservedLoc(world, 48, 48, netRes));

        UUID player = UUID.randomUUID();
        qm.openPersonalQueue(player);
        qm.perPlayerLocationQueue.get(player).add(reservedLoc(world, 64, 64, personalRes));

        assertEquals(1, qm.keptStage.size());
        assertEquals(1, qm.loginStage.size());
        assertEquals(1, qm.networkKeptStage.size());

        qm.shutDown();

        assertEquals(1, keptRes.closes.get(), "kept ticket closed on shutdown");
        assertEquals(1, loginRes.closes.get(), "login ticket closed on shutdown");
        assertEquals(1, netRes.closes.get(), "network kept ticket closed on shutdown");
        assertEquals(1, personalRes.closes.get(), "personal queue ticket closed on shutdown");

        assertEquals(0, qm.keptStage.size());
        assertNull(qm.loginStage);
        assertEquals(0, qm.networkKeptStage.size());
        assertEquals(0, qm.perPlayerStage.size());
        assertEquals(0, qm.unkeptLocations.size(), "shutdown disposes rather than recycles into Cold");
    }
}
