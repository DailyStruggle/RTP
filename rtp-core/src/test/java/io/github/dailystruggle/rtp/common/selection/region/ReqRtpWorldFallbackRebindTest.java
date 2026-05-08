package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Regression test for the "deferred region binding" fix: when an automatic world generator
 * (e.g. Multiverse) loads the configured world after {@code rtp-plugin} has already
 * constructed its regions, the region must:
 *
 * <ol>
 *   <li>Detect the fallback condition via
 *       {@link RegionConfigLoader#detectFallbackConfiguredWorld}.</li>
 *   <li>Skip the destructive database hydrate at construction time (otherwise the
 *       seed-mismatch check would delete every cached-location row for the real world on
 *       each startup).</li>
 *   <li>Hydrate the cache once {@link Region#rebindWorld} is invoked after the configured
 *       world has loaded.</li>
 * </ol>
 */
public class ReqRtpWorldFallbackRebindTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPWorld fallbackWorld;
    private MockRTPWorld configuredWorld;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        fallbackWorld = new MockRTPWorld("world");
        accessor.addWorld(fallbackWorld);
        // Note: configuredWorld ("test") is intentionally NOT added yet to simulate the
        // "world not loaded at plugin enable" condition that the fix targets.
        configuredWorld = new MockRTPWorld("test");
    }

    // ------------------------------------------------------------------
    // RegionConfigLoader.detectFallbackConfiguredWorld — pure logic
    // ------------------------------------------------------------------

    @Test
    @DisplayName("detectFallbackConfiguredWorld returns null when resolved world matches configured")
    void detectFallback_noFallbackWhenNamesMatch() {
        RegionSettings settings = settings("r", fallbackWorld);
        @SuppressWarnings("unchecked")
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        doReturn("world").when(parser).getConfigValue(eq(RegionKeys.world), any());

        assertNull(RegionConfigLoader.detectFallbackConfiguredWorld(parser, settings));
    }

    @Test
    @DisplayName("detectFallbackConfiguredWorld returns raw name when resolved differs from configured")
    void detectFallback_returnsRawNameWhenFallbackOccurred() {
        RegionSettings settings = settings("r", fallbackWorld);
        @SuppressWarnings("unchecked")
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        doReturn("test").when(parser).getConfigValue(eq(RegionKeys.world), any());

        assertEquals("test", RegionConfigLoader.detectFallbackConfiguredWorld(parser, settings));
    }

    @Test
    @DisplayName("detectFallbackConfiguredWorld treats [index] notation as non-fallback")
    void detectFallback_ignoresIndexNotation() {
        RegionSettings settings = settings("r", fallbackWorld);
        @SuppressWarnings("unchecked")
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        doReturn("[0]").when(parser).getConfigValue(eq(RegionKeys.world), any());

        assertNull(RegionConfigLoader.detectFallbackConfiguredWorld(parser, settings));
    }

    @Test
    @DisplayName("detectFallbackConfiguredWorld returns raw name when settings.world is null (dormant region)")
    void detectFallback_returnsRawNameForDormantRegion() {
        // When the configured world isn't loaded yet, RegionConfigLoader leaves settings.world()
        // null. The detector must still report the raw configured name so that OnWorldLoadUnload
        // can match a WorldLoadEvent to this region and rebind it.
        RegionSettings settings = settings("r", null);
        @SuppressWarnings("unchecked")
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        doReturn("test").when(parser).getConfigValue(eq(RegionKeys.world), any());

        assertEquals("test", RegionConfigLoader.detectFallbackConfiguredWorld(parser, settings));
    }

    // ------------------------------------------------------------------
    // Region construction — hydrate deferral
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Normally-bound region hydrates from the database at construction")
    void normalRegion_hydratesAtConstruction() {
        DatabaseAccessor<?> db = mock(DatabaseAccessor.class);
        doReturn(Collections.emptyList()).when(db).loadCachedLocations(anyString());
        RTP.getInstance().databaseAccessor = db;

        RegionSettings settings = settings("normal_region", fallbackWorld);
        Region region = new Region("normal_region", settings);

        verify(db, atLeastOnce()).loadCachedLocations("normal_region");
        assertFalse(region.worldFallbackBound);
        assertNull(region.configuredWorldName);
    }

    @Test
    @DisplayName("Dormant region (world not yet loaded) skips the database hydrate and has a null world")
    void dormantRegion_skipsHydrate() {
        DatabaseAccessor<?> db = mock(DatabaseAccessor.class);
        doReturn(Collections.emptyList()).when(db).loadCachedLocations(anyString());
        RTP.getInstance().databaseAccessor = db;

        // A dormant region is constructed with a null world: there is no fallback substitute.
        RegionSettings settings = settings("dormant_region", null);
        Region region =
            new Region("dormant_region", settings, true, "test");

        verify(db, never()).loadCachedLocations(anyString());
        assertTrue(region.worldFallbackBound);
        assertEquals("test", region.configuredWorldName);
        assertNull(region.getWorld(),
            "dormant regions must have no world until WorldLoadEvent triggers rebindWorld");
    }

    @Test
    @DisplayName("Dormant region execute() is a no-op and does not touch chunk I/O")
    void dormantRegion_executeIsNoop() {
        DatabaseAccessor<?> db = mock(DatabaseAccessor.class);
        doReturn(Collections.emptyList()).when(db).loadCachedLocations(anyString());
        RTP.getInstance().databaseAccessor = db;

        RegionSettings settings = settings("dormant_exec", null);
        Region region = new Region("dormant_exec", settings, true, "test");

        // Must return without NPE despite null world.
        region.execute(1_000_000L);
        assertNull(region.getWorld());
    }

    // ------------------------------------------------------------------
    // Region.rebindWorld — flips state + hydrates once
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rebindWorld swaps the world, clears the fallback flag, and hydrates")
    void rebindWorld_hydratesAndClearsFallback() {
        DatabaseAccessor<?> db = mock(DatabaseAccessor.class);
        doReturn(Collections.emptyList()).when(db).loadCachedLocations(anyString());
        RTP.getInstance().databaseAccessor = db;

        // Dormant initial settings: null world until WorldLoadEvent fires.
        RegionSettings initial = settings("deferred_region", null);
        Region region =
            new Region("deferred_region", initial, true, "test");
        verify(db, never()).loadCachedLocations(anyString());

        // Simulate WorldLoadEvent — the configured world is now available.
        accessor.addWorld(configuredWorld);
        RegionSettings rebound = settings("deferred_region", configuredWorld);
        region.rebindWorld(rebound);

        assertFalse(region.worldFallbackBound,
            "rebindWorld must clear the fallback flag so subsequent restarts hydrate normally");
        assertNull(region.configuredWorldName);
        assertEquals(configuredWorld, region.getWorld(),
            "rebindWorld must swap the region onto the configured world");
        verify(db, atLeastOnce()).loadCachedLocations("deferred_region");
    }

    // ------------------------------------------------------------------
    // hydrateCacheFromDatabase — flush-after-consumption
    // ------------------------------------------------------------------

    @Test
    @DisplayName("hydrateCacheFromDatabase removes every consumed row from the DB and never re-saves during hydration")
    void hydrate_flushesAfterConsumption() {
        @SuppressWarnings("unchecked")
        DatabaseAccessor<Object> db = mock(DatabaseAccessor.class);
        doReturn(Collections.emptyList()).when(db).loadCachedLocations(anyString());
        RTP.getInstance().databaseAccessor = db;

        // Construct a live region bound to a real world so hydrate's getWorld().getSeed() works.
        RegionSettings s = settings("flush_region", fallbackWorld);
        Region region = new Region("flush_region", s);

        long seed = fallbackWorld.getSeed();
        java.util.List<DatabaseAccessor.StoredLocation> rows = new ArrayList<>();
        rows.add(new DatabaseAccessor.StoredLocation(
                "id-1", "flush_region", "world",  100,  64,  200,  3, seed, null));
        rows.add(new DatabaseAccessor.StoredLocation(
                "id-2", "flush_region", "world", -300,  64, -400,  7, seed, null));
        rows.add(new DatabaseAccessor.StoredLocation(
                "id-3", "flush_region", "world",  500,  64,  600, 11, seed,
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")));
        // Stale-seed row: must be removed without being queued.
        rows.add(new DatabaseAccessor.StoredLocation(
                "id-stale", "flush_region", "world", 0, 64, 0, 1, seed + 1, null));

        region.hydrateCacheFromDatabase(rows);

        // Every row — consumed, per-player, or seed-rejected — must be removed from the DB.
        verify(db).removeCachedLocation("id-1");
        verify(db).removeCachedLocation("id-2");
        verify(db).removeCachedLocation("id-3");
        verify(db).removeCachedLocation("id-stale");

        // During hydration the save callback must be suppressed: no rows are re-saved.
        verify(db, never()).saveCachedLocation(anyString(), any(), any());

        // After hydration, the save callback must be re-installed: a subsequent offer persists.
        RTPLocation steadyStateLoc = new RTPLocation(
                new io.github.dailystruggle.rtp.api.world.RTPCoords("world", 1, 64, 1), 0, null);
        region.queueManager.unkeptLocations.offer(steadyStateLoc);
        verify(db, atLeastOnce()).saveCachedLocation(eq("flush_region"), eq(steadyStateLoc), any());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static RegionSettings settings(String name, MockRTPWorld world) {
        return new RegionSettings(
                name, world,
                new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false,
                16L, 1000L, 8,
                0.0, 1L, "", false);
    }
}
