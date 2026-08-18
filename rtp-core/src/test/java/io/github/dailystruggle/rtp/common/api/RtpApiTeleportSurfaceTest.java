package io.github.dailystruggle.rtp.common.api;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RTPResult;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RTPAPI} teleport surface (teleport, cancel, queueDepth, isWarmingUp).
 * Covers pre-init exceptions (REQ-RTP-S-006) and delegation guarantees (REQ-RTP-S-004).
 */
class RtpApiTeleportSurfaceTest {

    @BeforeAll
    static void ensureRtpClassInitialised() {
        // Force the RTP static block to run so the production delegates are wired.
        RTP.serverId.hashCode();
    }

    private BiFunction<UUID, RtpTarget, CompletableFuture<RTPResult>> savedTeleport;
    private Predicate<UUID> savedCancel;
    private ToIntFunction<io.github.dailystruggle.rtp.api.world.RTPWorld<?>> savedQueueDepth;
    private Predicate<UUID> savedWarmup;

    @BeforeEach
    void saveState() {
        savedTeleport = RTPAPI.teleportDelegate;
        savedCancel = RTPAPI.cancelDelegate;
        savedQueueDepth = RTPAPI.queueDepthDelegate;
        savedWarmup = RTPAPI.warmupDelegate;
    }

    @AfterEach
    void restoreState() {
        RTPAPI.teleportDelegate = savedTeleport;
        RTPAPI.cancelDelegate = savedCancel;
        RTPAPI.queueDepthDelegate = savedQueueDepth;
        RTPAPI.warmupDelegate = savedWarmup;
    }

    // ------------------------------------------------------------------
    // Production wiring
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RTP static block wires all four teleport-API delegates")
    void coreWiresDelegates() {
        assertNotNull(RTPAPI.teleportDelegate, "teleportDelegate must be wired by core");
        assertNotNull(RTPAPI.cancelDelegate, "cancelDelegate must be wired by core");
        assertNotNull(RTPAPI.queueDepthDelegate, "queueDepthDelegate must be wired by core");
        assertNotNull(RTPAPI.warmupDelegate, "warmupDelegate must be wired by core");
    }

    // ------------------------------------------------------------------
    // Pre-init guards (REQ-RTP-S-006)
    // ------------------------------------------------------------------

    @Test
    void teleport_throwsIllegalStateWhenDelegateMissing() {
        RTPAPI.teleportDelegate = null;
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> RTPAPI.teleport(UUID.randomUUID(), RtpTarget.defaultRegion()));
        assertTrue(ex.getMessage().contains("Core implementation is not loaded"));
    }

    @Test
    void cancel_throwsIllegalStateWhenDelegateMissing() {
        RTPAPI.cancelDelegate = null;
        assertThrows(IllegalStateException.class, () -> RTPAPI.cancel(UUID.randomUUID()));
    }

    @Test
    void queueDepth_throwsIllegalStateWhenDelegateMissing() {
        RTPAPI.queueDepthDelegate = null;
        assertThrows(IllegalStateException.class,
                () -> RTPAPI.queueDepth(new MockRTPWorld()));
    }

    @Test
    void isWarmingUp_throwsIllegalStateWhenDelegateMissing() {
        RTPAPI.warmupDelegate = null;
        assertThrows(IllegalStateException.class, () -> RTPAPI.isWarmingUp(UUID.randomUUID()));
    }

    // ------------------------------------------------------------------
    // Argument validation
    // ------------------------------------------------------------------

    @Test
    void teleport_rejectsNullArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> RTPAPI.teleport(null, RtpTarget.defaultRegion()));
        assertThrows(IllegalArgumentException.class,
                () -> RTPAPI.teleport(UUID.randomUUID(), null));
    }

    @Test
    void rtpTarget_factoriesRejectBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> RtpTarget.region(" "));
        assertThrows(IllegalArgumentException.class, () -> RtpTarget.world((String) null));
        assertEquals(RtpTarget.Kind.DEFAULT, RtpTarget.defaultRegion().kind());
        assertEquals("nether", RtpTarget.world("nether").name());
        assertEquals(RtpTarget.Kind.REGION, RtpTarget.region("spawn").kind());
    }

    // ------------------------------------------------------------------
    // Delegation behaviour (stubbed delegates)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("teleport always yields a completed result (no silent no-op, S-004)")
    void teleport_routesToDelegateAndAlwaysCompletes() throws ExecutionException, InterruptedException {
        UUID id = UUID.randomUUID();
        RTPAPI.teleportDelegate = (uuid, target) -> {
            assertEquals(id, uuid);
            assertEquals(RtpTarget.Kind.DEFAULT, target.kind());
            return CompletableFuture.completedFuture(
                    RTPResult.failure(RTPResult.Reason.NO_SAFE_LOCATION, "stub"));
        };

        CompletableFuture<RTPResult> future = RTPAPI.teleport(id, RtpTarget.defaultRegion());
        assertTrue(future.isDone(), "future must be delivered, never left hanging");
        RTPResult result = future.get();
        assertFalse(result.isSuccess());
        assertEquals(RTPResult.Reason.NO_SAFE_LOCATION, result.reason());
        assertNull(result.location());
    }

    @Test
    void cancel_routesToDelegate() {
        UUID id = UUID.randomUUID();
        RTPAPI.cancelDelegate = uuid -> uuid.equals(id);
        assertTrue(RTPAPI.cancel(id));
        assertFalse(RTPAPI.cancel(UUID.randomUUID()));
    }

    @Test
    void queueDepth_routesToDelegate() {
        RTPAPI.queueDepthDelegate = world -> 7;
        assertEquals(7, RTPAPI.queueDepth(new MockRTPWorld()));
    }

    @Test
    void isWarmingUp_routesToDelegate() {
        UUID id = UUID.randomUUID();
        RTPAPI.warmupDelegate = uuid -> uuid.equals(id);
        assertTrue(RTPAPI.isWarmingUp(id));
        assertFalse(RTPAPI.isWarmingUp(UUID.randomUUID()));
    }
}
