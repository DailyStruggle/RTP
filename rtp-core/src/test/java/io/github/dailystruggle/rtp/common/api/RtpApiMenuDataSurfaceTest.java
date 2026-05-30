package io.github.dailystruggle.rtp.common.api;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.RtpTargetStatus;
import io.github.dailystruggle.rtp.common.RTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Developer-UX surface test for the GUI-author read SPI added to {@link RTPAPI}
 * ({@code getAllowedTargets} / {@code getTargetStatus} / {@code getMetricsSnapshot}
 * / {@code getRegionSamples}) per {@code PROPOSAL-gui-author-spi.md}.
 *
 * <p>All assertions use only {@code rtp-api} / {@code metrics-api} imports
 * (beyond the class-init nudge), proving a third-party GUI plugin can populate
 * menu items through the public API alone:
 * <ul>
 *   <li>Pre-init calls throw {@link IllegalStateException} (REQ-RTP-S-006) and
 *       null arguments throw {@link IllegalArgumentException}.</li>
 *   <li>When core delegates are registered, each method routes to its delegate;
 *       a null delegate result degrades to a safe non-null value, never a silent
 *       no-op.</li>
 * </ul>
 */
class RtpApiMenuDataSurfaceTest {

    @BeforeAll
    static void ensureRtpClassInitialised() {
        // Force the RTP static block to run so the production delegates are wired.
        RTP.serverId.hashCode();
    }

    private Function<UUID, List<RtpTarget>> savedAllowed;
    private BiFunction<UUID, RtpTarget, RtpTargetStatus> savedStatus;
    private Supplier<MetricsSnapshot> savedMetrics;

    @BeforeEach
    void saveState() {
        savedAllowed = RTPAPI.allowedTargetsDelegate;
        savedStatus = RTPAPI.targetStatusDelegate;
        savedMetrics = RTPAPI.metricsSnapshotDelegate;
    }

    @AfterEach
    void restoreState() {
        RTPAPI.allowedTargetsDelegate = savedAllowed;
        RTPAPI.targetStatusDelegate = savedStatus;
        RTPAPI.metricsSnapshotDelegate = savedMetrics;
    }

    // ------------------------------------------------------------------
    // Production wiring
    // ------------------------------------------------------------------

    @Test
    @DisplayName("RTP static block wires all three menu-data delegates")
    void coreWiresDelegates() {
        assertNotNull(RTPAPI.allowedTargetsDelegate, "allowedTargetsDelegate must be wired by core");
        assertNotNull(RTPAPI.targetStatusDelegate, "targetStatusDelegate must be wired by core");
        assertNotNull(RTPAPI.metricsSnapshotDelegate, "metricsSnapshotDelegate must be wired by core");
    }

    // ------------------------------------------------------------------
    // Pre-init guards (REQ-RTP-S-006)
    // ------------------------------------------------------------------

    @Test
    void getAllowedTargets_throwsIllegalStateWhenDelegateMissing() {
        RTPAPI.allowedTargetsDelegate = null;
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> RTPAPI.getAllowedTargets(UUID.randomUUID()));
        assertTrue(ex.getMessage().contains("Core implementation is not loaded"));
    }

    @Test
    void getTargetStatus_throwsIllegalStateWhenDelegateMissing() {
        RTPAPI.targetStatusDelegate = null;
        assertThrows(IllegalStateException.class,
                () -> RTPAPI.getTargetStatus(UUID.randomUUID(), RtpTarget.defaultRegion()));
    }

    @Test
    void getMetricsSnapshot_throwsIllegalStateWhenDelegateMissing() {
        RTPAPI.metricsSnapshotDelegate = null;
        assertThrows(IllegalStateException.class, RTPAPI::getMetricsSnapshot);
    }

    @Test
    void getRegionSamples_throwsIllegalStateWhenDelegateMissing() {
        RTPAPI.metricsSnapshotDelegate = null;
        assertThrows(IllegalStateException.class, RTPAPI::getRegionSamples);
    }

    // ------------------------------------------------------------------
    // Argument validation
    // ------------------------------------------------------------------

    @Test
    void readMethods_rejectNullArguments() {
        assertThrows(IllegalArgumentException.class, () -> RTPAPI.getAllowedTargets(null));
        assertThrows(IllegalArgumentException.class,
                () -> RTPAPI.getTargetStatus(null, RtpTarget.defaultRegion()));
        assertThrows(IllegalArgumentException.class,
                () -> RTPAPI.getTargetStatus(UUID.randomUUID(), null));
    }

    // ------------------------------------------------------------------
    // Delegation behaviour (stubbed delegates)
    // ------------------------------------------------------------------

    @Test
    void getAllowedTargets_routesToDelegate() {
        UUID id = UUID.randomUUID();
        RTPAPI.allowedTargetsDelegate = uuid -> {
            assertEquals(id, uuid);
            return List.of(RtpTarget.defaultRegion(), RtpTarget.region("spawn"));
        };
        List<RtpTarget> targets = RTPAPI.getAllowedTargets(id);
        assertEquals(2, targets.size());
        assertEquals(RtpTarget.Kind.DEFAULT, targets.get(0).kind());
        assertEquals("spawn", targets.get(1).name());
    }

    @Test
    @DisplayName("getAllowedTargets degrades a null delegate result to an empty list")
    void getAllowedTargets_nullResultBecomesEmptyList() {
        RTPAPI.allowedTargetsDelegate = uuid -> null;
        assertEquals(Collections.emptyList(), RTPAPI.getAllowedTargets(UUID.randomUUID()));
    }

    @Test
    void getTargetStatus_routesToDelegate() {
        UUID id = UUID.randomUUID();
        RtpTargetStatus stub =
                new RtpTargetStatus(RtpTargetStatus.Availability.ON_COOLDOWN, 1500L, 25.0);
        RTPAPI.targetStatusDelegate = (uuid, target) -> {
            assertEquals(id, uuid);
            return stub;
        };
        RtpTargetStatus status = RTPAPI.getTargetStatus(id, RtpTarget.defaultRegion());
        assertEquals(RtpTargetStatus.Availability.ON_COOLDOWN, status.availability());
        assertEquals(1500L, status.remainingCooldownMillis());
        assertEquals(25.0, status.cost());
        assertFalse(status.isReady());
    }

    @Test
    @DisplayName("getTargetStatus degrades a null delegate result to UNKNOWN (no silent no-op)")
    void getTargetStatus_nullResultBecomesUnknown() {
        RTPAPI.targetStatusDelegate = (uuid, target) -> null;
        RtpTargetStatus status =
                RTPAPI.getTargetStatus(UUID.randomUUID(), RtpTarget.defaultRegion());
        assertEquals(RtpTargetStatus.Availability.UNKNOWN, status.availability());
    }

    @Test
    void getMetricsSnapshot_routesToDelegate() {
        MetricsSnapshot snapshot = new MetricsSnapshot(
                20.0, 20.0, 20.0, 38.2, 5, 100, 0L, 0L, 123L, Collections.emptyList());
        RTPAPI.metricsSnapshotDelegate = () -> snapshot;
        assertSame(snapshot, RTPAPI.getMetricsSnapshot());
    }

    @Test
    @DisplayName("getRegionSamples derives from the latest snapshot's foliaRegions")
    void getRegionSamples_derivesFromSnapshot() {
        FoliaRegionSample sample = new FoliaRegionSample("region-0", 19.5, 41.0, 3, 12);
        MetricsSnapshot snapshot = new MetricsSnapshot(
                20.0, 20.0, 20.0, 41.0, 3, 100, 0L, 0L, 123L, List.of(sample));
        RTPAPI.metricsSnapshotDelegate = () -> snapshot;

        List<FoliaRegionSample> samples = RTPAPI.getRegionSamples();
        assertEquals(1, samples.size());
        assertEquals("region-0", samples.get(0).regionId);
    }

    @Test
    void getRegionSamples_emptyOnSingleRegionRuntime() {
        MetricsSnapshot snapshot = new MetricsSnapshot(
                20.0, 20.0, 20.0, 38.2, 5, 100, 0L, 0L, 123L, Collections.emptyList());
        RTPAPI.metricsSnapshotDelegate = () -> snapshot;
        assertTrue(RTPAPI.getRegionSamples().isEmpty());
    }
}
