package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@code /rtp clearcache} ({@link ClearCacheCmd}) and its
 * {@code cache} alias ({@link ClearCacheSubCmd}). Exercises wiping the
 * hot/cold caches across every registered region, the empty-board no-op, and
 * {@code nextCommand} delegation.
 */
class ClearCacheCmdTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private final List<String> registered = new ArrayList<>();

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        registered.clear();
    }

    @AfterEach
    void tearDown() {
        if (RTP.selectionAPI != null) {
            for (String name : registered) {
                RTP.selectionAPI.permRegionLookup.remove(name);
            }
        }
    }

    private Region newPopulatedRegion(String name) {
        MockRTPWorld world = new MockRTPWorld(name + "_world");
        accessor.addWorld(world);
        RegionSettings settings = new RegionSettings(
                name, world,
                new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false,
                50L, 0L, 0L, 10, 0.0, 16L, null, false
        );
        Region region = new Region(name, settings);
        region.queueManager.keptLocations.add(
                new RTPLocation(new RTPCoords(world.name(), 10, 64, 10), 1L, null));
        region.queueManager.unkeptLocations.add(
                new RTPLocation(new RTPCoords(world.name(), 20, 64, 20), 1L, null));
        RTP.selectionAPI.permRegionLookup.put(name, region);
        registered.add(name);
        return region;
    }

    @Test
    @DisplayName("clearcache empties the hot/cold caches for every registered region")
    void clearcache_wipesAllRegionCaches() {
        Region a = newPopulatedRegion("cache_region_a");
        Region b = newPopulatedRegion("cache_region_b");
        assertEquals(2, a.queueManager.getPublicQueueLength(), "precondition: region a populated");
        assertEquals(2, b.queueManager.getPublicQueueLength(), "precondition: region b populated");

        ClearCacheCmd cmd = new ClearCacheCmd(null);
        boolean handled = cmd.onCommand(RTPAPI.serverId, new HashMap<>(), null);

        assertTrue(handled, "command reports handled");
        assertEquals(0, a.queueManager.getPublicQueueLength(), "region a caches cleared");
        assertEquals(0, b.queueManager.getPublicQueueLength(), "region b caches cleared");
    }

    @Test
    @DisplayName("cache alias reuses the clear-cache behaviour under a renamed verb")
    void cacheAlias_clearsAndKeepsCacheName() {
        Region a = newPopulatedRegion("cache_alias_region");
        ClearCacheSubCmd alias = new ClearCacheSubCmd(null);

        assertEquals("cache", alias.name(), "alias exposes the cache verb");
        assertEquals(ClearCacheCmd.PERMISSION, alias.permission(), "alias inherits rtp.admin permission");

        alias.onCommand(RTPAPI.serverId, new HashMap<>(), null);

        assertEquals(0, a.queueManager.getPublicQueueLength(), "alias clears region caches");
    }

    @Test
    @DisplayName("clearcache is a safe no-op when no regions are registered")
    void clearcache_noRegions_returnsTrue() {
        ClearCacheCmd cmd = new ClearCacheCmd(null);
        assertTrue(cmd.onCommand(RTPAPI.serverId, new HashMap<>(), null),
                "clearcache with no regions still reports handled");
    }

    @Test
    @DisplayName("clearcache delegates to nextCommand and leaves caches untouched")
    void clearcache_delegatesToNextCommand() {
        Region a = newPopulatedRegion("cache_delegate_region");
        AtomicBoolean delegated = new AtomicBoolean(false);
        CommandsAPICommand next = new ClearCacheCmd(null) {
            @Override
            public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues,
                                     CommandsAPICommand nextCommand) {
                delegated.set(true);
                return true;
            }
        };

        ClearCacheCmd cmd = new ClearCacheCmd(null);
        cmd.onCommand(RTPAPI.serverId, new HashMap<>(), next);

        assertTrue(delegated.get(), "nextCommand must be invoked");
        assertEquals(2, a.queueManager.getPublicQueueLength(),
                "delegation short-circuits before clearing caches");
        assertFalse(a.queueManager.getPublicQueueLength() == 0,
                "caches left intact when delegating");
    }
}
