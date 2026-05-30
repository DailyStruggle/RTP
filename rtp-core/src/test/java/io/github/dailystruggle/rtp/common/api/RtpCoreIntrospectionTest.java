package io.github.dailystruggle.rtp.common.api;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionQueueManager;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for the unstable {@code rtp-core} GUI-author introspection hook
 * ({@link RtpCoreIntrospection} + {@link RegionQueueDepths}), Phase 2 of
 * {@code docs/dev/scratch/PROPOSAL-gui-author-spi.md} §3.2.
 *
 * <p>Verifies the read-only facade reports live kept/unkept buffer depths per
 * region, resolves a region by name, degrades safely (empty list / {@code null}
 * / {@code false}) rather than throwing when state is absent, and never exposes
 * a mutator. This hook deliberately does NOT follow the REQ-RTP-S-006
 * throw-on-pre-init contract (that governs the {@code rtp-api} surface), so a
 * dashboard polling before startup renders "nothing ready" instead of crashing.
 */
class RtpCoreIntrospectionTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        RTP.selectionAPI.permRegionLookup.clear();
        RTP.selectionAPI.tempRegions.clear();
    }

    private static Region buildRegion(String name) {
        RegionSettings settings = mock(RegionSettings.class);
        when(settings.cacheCap()).thenReturn(1024L);
        when(settings.activeChunkCap()).thenReturn(64);

        Region region = mock(Region.class);
        when(region.getSettings()).thenReturn(settings);
        region.name = name;
        region.queueManager = new RegionQueueManager(region);
        return region;
    }

    private static RTPLocation loc(int x, int z) {
        return new RTPLocation(new RTPCoords("world", x, 64, z), 1L, null);
    }

    @Test
    @DisplayName("queueDepths() reports live kept/unkept depths for a registered region")
    void queueDepths_reportsLiveDepths() {
        Region region = buildRegion("default");
        region.queueManager.keptLocations.offer(loc(100, 100));
        region.queueManager.keptLocations.offer(loc(200, 200));
        region.queueManager.unkeptLocations.offer(loc(300, 300));
        RTP.selectionAPI.permRegionLookup.put("default", region);

        List<RegionQueueDepths> all = RtpCoreIntrospection.queueDepths();
        assertEquals(1, all.size());
        RegionQueueDepths d = all.get(0);
        assertEquals("default", d.regionName());
        assertEquals(2, d.kept());
        assertEquals(1, d.unkept());
        assertEquals(3, d.readyDepth());
        assertEquals(0, d.backlog());
        assertEquals(0, d.login());
        assertEquals(0, d.networkKept());
        assertEquals(0, d.waitlist());
    }

    @Test
    @DisplayName("queueDepths(name) resolves a region by canonical name; unknown name yields null")
    void queueDepths_byName() {
        Region region = buildRegion("nether");
        region.queueManager.unkeptLocations.offer(loc(1, 1));
        RTP.selectionAPI.permRegionLookup.put("nether", region);

        RegionQueueDepths d = RtpCoreIntrospection.queueDepths("nether");
        assertNotNull(d);
        assertEquals("nether", d.regionName());
        assertEquals(1, d.unkept());

        assertNull(RtpCoreIntrospection.queueDepths("does-not-exist"));
        assertNull(RtpCoreIntrospection.queueDepths((String) null));
    }

    @Test
    @DisplayName("returned list is unmodifiable (read-only surface, no mutator)")
    void queueDepths_listIsUnmodifiable() {
        RTP.selectionAPI.permRegionLookup.put("default", buildRegion("default"));
        List<RegionQueueDepths> all = RtpCoreIntrospection.queueDepths();
        assertThrows(UnsupportedOperationException.class,
                () -> all.add(new RegionQueueDepths("x", 0, 0, 0, 0, 0, 0, 0, 0)));
    }

    @Test
    @DisplayName("hasFastLocation is false for unknown region/player and never throws on null args")
    void hasFastLocation_safeDefaults() {
        assertFalse(RtpCoreIntrospection.hasFastLocation(null, UUID.randomUUID()));
        assertFalse(RtpCoreIntrospection.hasFastLocation("default", null));
        assertFalse(RtpCoreIntrospection.hasFastLocation("missing", UUID.randomUUID()));

        Region region = buildRegion("default");
        RTP.selectionAPI.permRegionLookup.put("default", region);
        assertFalse(RtpCoreIntrospection.hasFastLocation("default", UUID.randomUUID()));
    }

    @Test
    @DisplayName("queueDepths() returns an empty list rather than throwing when no regions are registered")
    void queueDepths_emptyWhenNoRegions() {
        assertTrue(RtpCoreIntrospection.queueDepths().isEmpty());
    }
}
