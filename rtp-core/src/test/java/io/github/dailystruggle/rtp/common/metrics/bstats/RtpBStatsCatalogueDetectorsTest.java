package io.github.dailystruggle.rtp.common.metrics.bstats;

import io.github.dailystruggle.bstats.api.BStatsService;
import io.github.dailystruggle.bstats.api.CustomChart;
import io.github.dailystruggle.bstats.api.ServerInfo;
import io.github.dailystruggle.metrics.api.MetricsSnapshot;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.database.ActiveDatabase;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile;
import io.github.dailystruggle.rtp.common.metrics.RTPMetricsExtension;
import io.github.dailystruggle.rtp.common.metrics.RtpOutcomeStats;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.LockFreeLocationBuffer;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionQueueManager;
import io.github.dailystruggle.rtp.common.selection.model.FailTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Detector, host-default and sampler-lifecycle branches of {@link RtpBStatsCatalogue}
 * driven against stubbed {@code RTP} statics. Every detector must map its inputs onto
 * a bounded label set and fall back to a sentinel when a collaborator throws.
 */
class RtpBStatsCatalogueDetectorsTest {

    /** Name-bearing accessor stand-ins: the backend detector keys off the class simple name. */
    abstract static class H2Accessor extends DatabaseAccessor<Object> { }
    abstract static class SqliteAccessor extends DatabaseAccessor<Object> { }
    abstract static class PostgresAccessor extends DatabaseAccessor<Object> { }
    abstract static class MysqlAccessor extends DatabaseAccessor<Object> { }
    abstract static class MariadbAccessor extends DatabaseAccessor<Object> { }
    abstract static class PlainAccessor extends DatabaseAccessor<Object> { }

    @TempDir
    Path tmp;

    private RTPServerAccessor savedAccessor;
    private RTPScheduler savedScheduler;
    private Configs savedConfigs;
    private DatabaseAccessor<?> savedDatabaseAccessor;
    private SelectionAPI savedSelection;
    private RTP savedInstance;
    private TeleportSessions savedSessions;

    @BeforeEach
    void setUp() throws Exception {
        savedAccessor = RTPRuntime.serverAccessor();
        savedScheduler = RTPRuntime.scheduler();
        savedConfigs = ActiveConfigs.get();
        savedDatabaseAccessor = ActiveDatabase.accessor();
        savedSelection = ActiveSelection.get();
        savedInstance = RTP.getInstance();
        savedSessions = TeleportSessions.current();
        TeleportSessions.install(null);
        RTPRuntime.setScheduler(null);
        RtpBStatsCatalogue.stopRtpCostSampler();
        RtpOutcomeStats.GLOBAL.reset();
        ChunkLoadProfile.GLOBAL.reset();
    }

    @AfterEach
    void tearDown() throws Exception {
        RTPRuntime.setScheduler(null);
        RtpBStatsCatalogue.stopRtpCostSampler();
        RTPRuntime.setServerAccessor(savedAccessor);
        RTPRuntime.setScheduler(savedScheduler);
        ActiveConfigs.set(savedConfigs);
        ActiveDatabase.setAccessor(savedDatabaseAccessor);
        ActiveSelection.set(savedSelection);
        setInstance(savedInstance);
        TeleportSessions.install(savedSessions);
        RtpOutcomeStats.GLOBAL.reset();
        ChunkLoadProfile.GLOBAL.reset();
        RtpBStatsCatalogue.RTP_SYNC_COST_WINDOW.reset();
        RtpBStatsCatalogue.RTP_ASYNC_COST_WINDOW.reset();
        RtpBStatsCatalogue.RTP_CHUNK_GEN_COST_WINDOW.reset();
        RtpBStatsCatalogue.RTP_CHUNK_UNGEN_COST_WINDOW.reset();
    }

    private static void setInstance(RTP rtp) throws Exception {
        Field f = RTP.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, rtp);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static RTP installMockInstance() throws Exception {
        RTP rtp = mock(RTP.class);
        setInstance(rtp);
        TeleportSessions.install(new TeleportSessions());
    }

        return rtp;
    // --- Host defaults ---

    @Test
    @DisplayName("Host defaults: game and plugin versions fall back to unknown without or on failure of the accessor")
    void hostVersionDefaults() {
        RtpBStatsCatalogue.Host host = () -> "paper";

        RTPRuntime.setServerAccessor(null);
        assertEquals("unknown", host.gameVersion());
        assertEquals("unknown", host.pluginVersion());
        assertEquals("Paper", host.serverInfo().version());
        assertEquals("unknown", host.chunkLoadMode());
        assertTrue(host.onlinePlayerCount() >= -1);

        RTPServerAccessor accessor = mock(RTPServerAccessor.class);
        RTPRuntime.setServerAccessor(accessor);
        when(accessor.getServerVersion()).thenReturn("1.21.4-R0.1-SNAPSHOT");
        when(accessor.getPluginVersion()).thenReturn("3.3.0");
        assertEquals("1.21", host.gameVersion());
        assertEquals("3.3.0", host.pluginVersion());
        ServerInfo info = host.serverInfo();
        assertEquals("Paper", info.name());
        assertEquals("Paper (MC: 1.21.4-R0.1-SNAPSHOT)", info.version());

        when(accessor.getPluginVersion()).thenReturn(" ");
        assertEquals("unknown", host.pluginVersion());
        when(accessor.getPluginVersion()).thenReturn(null);
        assertEquals("unknown", host.pluginVersion());

        when(accessor.getServerVersion()).thenReturn(" ");
        assertEquals("Paper", host.serverInfo().version(), "blank MC version keeps the bare name");

        when(accessor.getServerVersion()).thenThrow(new IllegalStateException("boom"));
        when(accessor.getPluginVersion()).thenThrow(new IllegalStateException("boom"));
        assertEquals("unknown", host.gameVersion());
        assertEquals("unknown", host.pluginVersion());
        assertEquals("Paper", host.serverInfo().version());
    }

    @Test
    @DisplayName("Host.of lower-cases the platform and maps blank to unknown")
    void hostOfLabels() {
        assertEquals("paper", RtpBStatsCatalogue.Host.of("Paper").platform());
        assertEquals("unknown", RtpBStatsCatalogue.Host.of(" ").platform());
        assertEquals("unknown", RtpBStatsCatalogue.displayName(null));
        assertEquals("Paper", RtpBStatsCatalogue.displayName("paper"));
        assertSame(io.github.dailystruggle.bstats.api.BStatsConfig.Format.TEXT,
                RtpBStatsCatalogue.Host.of("paper").configFormat());
        assertNull(RtpBStatsCatalogue.Host.of("paper").addonsLoaded());
    }

    // --- register ---

    @Test
    @DisplayName("register ignores a null service or host without touching the service")
    void registerNullGuards() {
        assertDoesNotThrow(() -> RtpBStatsCatalogue.register(null, RtpBStatsCatalogue.Host.of("paper"), "full"));
        BStatsService service = mock(BStatsService.class);
        RtpBStatsCatalogue.register(service, null, "full");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("register adds the addons chart when the host supplies a tally and reports the lite variant")
    void registerLiteWithAddons() {
        RtpBStatsCatalogue.Host host = new RtpBStatsCatalogue.Host() {
            @Override public String platform() { return "paper"; }
            @Override public String chunkLoadMode() { return "async"; }
            @Override public Map<String, Integer> addonsLoaded() { return Map.of("worldguard", 1); }
        };
        BStatsService service = RtpBStats.create(host, RtpBStats.LITE_SERVICE_ID, "lite",
                tmp.resolve("lite").resolve("RTP").toFile(), mock(RTPScheduler.class));

        Set<String> ids = service.charts().stream().map(CustomChart::getChartId).collect(Collectors.toSet());
        assertTrue(ids.contains(BStatsChartIds.ADDONS_LOADED));
        String json = service.buildPayload();
        assertTrue(json.contains("{\"chartId\":\"" + BStatsChartIds.LITE_FEATURES_DROPPED
                + "\",\"data\":{\"value\":\"lite\"}}"), json);
        assertTrue(json.contains("worldguard"), json);
    }

    @Test
    @DisplayName("register maps a blank assembly variant to unknown and reports no lite features dropped")
    void registerBlankVariant() {
        BStatsService service = RtpBStats.create(RtpBStatsCatalogue.Host.of("fabric"), RtpBStats.SERVICE_ID, " ",
                tmp.resolve("blank").resolve("RTP").toFile(), mock(RTPScheduler.class));
        String json = service.buildPayload();
        assertTrue(json.contains("{\"chartId\":\"" + BStatsChartIds.ASSEMBLY_VARIANT
                + "\",\"data\":{\"value\":\"unknown\"}}"), json);
        assertTrue(json.contains("{\"chartId\":\"" + BStatsChartIds.LITE_FEATURES_DROPPED
                + "\",\"data\":{\"value\":\"none\"}}"), json);

        BStatsService nullVariant = RtpBStats.create(RtpBStatsCatalogue.Host.of("fabric"), RtpBStats.SERVICE_ID, null,
                tmp.resolve("null").resolve("RTP").toFile(), mock(RTPScheduler.class));
        assertTrue(nullVariant.buildPayload().contains("{\"chartId\":\"" + BStatsChartIds.ASSEMBLY_VARIANT
                + "\",\"data\":{\"value\":\"unknown\"}}"));
    }

    // --- Detectors ---

    @Test
    @DisplayName("database_backend maps the accessor class name onto the documented labels")
    void databaseBackendLabels() {
        ActiveDatabase.setAccessor(null);
        assertEquals("none", RtpBStatsCatalogue.detectDatabaseBackend());

        ActiveDatabase.setAccessor(new YamlFileDatabase(tmp.toFile()));
        assertEquals("yaml", RtpBStatsCatalogue.detectDatabaseBackend());
        ActiveDatabase.setAccessor(mock(H2Accessor.class));
        assertEquals("h2", RtpBStatsCatalogue.detectDatabaseBackend());
        ActiveDatabase.setAccessor(mock(SqliteAccessor.class));
        assertEquals("sqlite", RtpBStatsCatalogue.detectDatabaseBackend());
        ActiveDatabase.setAccessor(mock(PostgresAccessor.class));
        assertEquals("postgresql", RtpBStatsCatalogue.detectDatabaseBackend());
        ActiveDatabase.setAccessor(mock(MysqlAccessor.class));
        assertEquals("mysql", RtpBStatsCatalogue.detectDatabaseBackend());
        ActiveDatabase.setAccessor(mock(MariadbAccessor.class));
        assertEquals("mysql", RtpBStatsCatalogue.detectDatabaseBackend());
        ActiveDatabase.setAccessor(mock(PlainAccessor.class));
        assertEquals("other", RtpBStatsCatalogue.detectDatabaseBackend());
    }

    @Test
    @DisplayName("language_selection: shipped locale, custom locale collapses to other, accessor failure to unknown")
    void languageLabels() throws Exception {
        RTPServerAccessor accessor = mock(RTPServerAccessor.class);
        RTPRuntime.setServerAccessor(accessor);
        File dir = tmp.resolve("lang").toFile();
        assertTrue(dir.mkdirs());
        when(accessor.getPluginDirectory()).thenReturn(dir);

        Files.writeString(new File(dir, "language.yml").toPath(), "language: DE\n");
        assertEquals("de", RtpBStatsCatalogue.detectLanguage());

        Files.writeString(new File(dir, "language.yml").toPath(), "language: klingon\n");
        assertEquals("other", RtpBStatsCatalogue.detectLanguage());

        when(accessor.getPluginDirectory()).thenThrow(new IllegalStateException("boom"));
        assertEquals("unknown", RtpBStatsCatalogue.detectLanguage());

        RTPRuntime.setServerAccessor(null);
        assertEquals("unknown", RtpBStatsCatalogue.detectLanguage());
    }

    @Test
    @DisplayName("region_shapes_in_use tallies shape class names; anonymous or failing shapes count as unknown")
    void regionShapesTally() throws Exception {
        ActiveSelection.set(null);
        assertTrue(RtpBStatsCatalogue.detectRegionShapesInUse().isEmpty());
        ActiveSelection.set(mock(SelectionAPI.class));
        assertTrue(RtpBStatsCatalogue.detectRegionShapesInUse().isEmpty(), "null lookup map yields empty tally");

        SelectionAPI api = mock(SelectionAPI.class);
        ConcurrentHashMap<String, Region> lookup = new ConcurrentHashMap<>();
        setField(api, "permRegionLookup", lookup);
        ActiveSelection.set(api);

        Region named = mock(Region.class);
        when(named.getData(RegionKeys.shape)).thenReturn(new ArrayList<String>());
        Region named2 = mock(Region.class);
        when(named2.getData(RegionKeys.shape)).thenReturn(new ArrayList<String>());
        Region anonymous = mock(Region.class);
        when(anonymous.getData(RegionKeys.shape)).thenReturn(new Object() { });
        Region missing = mock(Region.class);
        when(missing.getData(RegionKeys.shape)).thenReturn(null);
        Region failing = mock(Region.class);
        when(failing.getData(RegionKeys.shape)).thenThrow(new IllegalStateException("boom"));
        lookup.put("a", named);
        lookup.put("b", named2);
        lookup.put("c", anonymous);
        lookup.put("d", missing);
        lookup.put("e", failing);

        Map<String, Integer> tally = RtpBStatsCatalogue.detectRegionShapesInUse();
        assertEquals(Map.of("arraylist", 2, "unknown", 3), tally);
    }

    @Test
    @DisplayName("Region-map detectors never throw when the lookup map itself fails")
    void regionMapFailures() throws Exception {
        SelectionAPI api = mock(SelectionAPI.class);
        ConcurrentHashMap<String, Region> boom = new ConcurrentHashMap<>() {
            @Override public int size() { throw new IllegalStateException("boom"); }
            @Override public Collection<Region> values() { throw new IllegalStateException("boom"); }
        };
        setField(api, "permRegionLookup", boom);
        ActiveSelection.set(api);

        assertEquals(0, RtpBStatsCatalogue.safeRegionCount());
        assertTrue(RtpBStatsCatalogue.detectRegionShapesInUse().isEmpty());
        assertArrayEquals(new double[] {0.0, 0.0}, RtpBStatsCatalogue.computeCacheFillPercentages());
    }

    @Test
    @DisplayName("region_count reports the lookup size and zero without a selection API")
    void regionCount() throws Exception {
        ActiveSelection.set(null);
        assertEquals(0, RtpBStatsCatalogue.safeRegionCount());
        ActiveSelection.set(mock(SelectionAPI.class));
        assertEquals(0, RtpBStatsCatalogue.safeRegionCount());

        SelectionAPI api = mock(SelectionAPI.class);
        ConcurrentHashMap<String, Region> lookup = new ConcurrentHashMap<>();
        lookup.put("a", mock(Region.class));
        lookup.put("b", mock(Region.class));
        setField(api, "permRegionLookup", lookup);
        ActiveSelection.set(api);
        assertEquals(2, RtpBStatsCatalogue.safeRegionCount());
    }

    @Test
    @DisplayName("cache_pool_health sums kept/unkept fill across regions and skips missing queues")
    void cacheFillPercentages() throws Exception {
        ActiveSelection.set(null);
        assertArrayEquals(new double[] {0.0, 0.0}, RtpBStatsCatalogue.computeCacheFillPercentages());
        ActiveSelection.set(mock(SelectionAPI.class));
        assertArrayEquals(new double[] {0.0, 0.0}, RtpBStatsCatalogue.computeCacheFillPercentages());

        SelectionAPI api = mock(SelectionAPI.class);
        ConcurrentHashMap<String, Region> lookup = new ConcurrentHashMap<>();
        setField(api, "permRegionLookup", lookup);
        ActiveSelection.set(api);

        Region noQueue = mock(Region.class);
        noQueue.queueManager = null;

        Region emptyQueue = mock(Region.class);
        emptyQueue.queueManager = mock(RegionQueueManager.class);
        setField(emptyQueue.queueManager, "keptLocations", null);
        setField(emptyQueue.queueManager, "unkeptLocations", null);

        Region filled = mock(Region.class);
        filled.queueManager = mock(RegionQueueManager.class);
        LockFreeLocationBuffer kept = mock(LockFreeLocationBuffer.class);
        when(kept.size()).thenReturn(1);
        when(kept.capacity()).thenReturn(4);
        LockFreeLocationBuffer unkept = mock(LockFreeLocationBuffer.class);
        when(unkept.size()).thenReturn(3);
        when(unkept.capacity()).thenReturn(4);
        setField(filled.queueManager, "keptLocations", kept);
        setField(filled.queueManager, "unkeptLocations", unkept);

        lookup.put("a", noQueue);
        lookup.put("b", emptyQueue);
        lookup.put("c", filled);

        double[] fills = RtpBStatsCatalogue.computeCacheFillPercentages();
        assertEquals(25.0, fills[0], 1e-9);
        assertEquals(75.0, fills[1], 1e-9);
    }

    @Test
    @DisplayName("safety_features_enabled reports non-default toggles, default, or unknown on missing/failing config")
    @SuppressWarnings("unchecked")
    void safetyFeatures() {
        ActiveConfigs.set(null);
        assertEquals("unknown", RtpBStatsCatalogue.detectSafetyFeaturesEnabled());

        Configs configs = mock(Configs.class);
        ActiveConfigs.set(configs);
        assertEquals("unknown", RtpBStatsCatalogue.detectSafetyFeaturesEnabled(), "missing safety parser");

        ConfigParser<SafetyKeys> safety = mock(ConfigParser.class);
        doReturn(safety).when(configs).getParser(SafetyKeys.class);
        when(safety.getConfigValue(SafetyKeys.anvilPrefilterEnabled, Boolean.TRUE)).thenReturn(Boolean.TRUE);
        when(configs.getConfigValue(BiomesKeys.biomeWhitelist, Boolean.FALSE)).thenReturn(Boolean.FALSE);
        assertEquals("default", RtpBStatsCatalogue.detectSafetyFeaturesEnabled());

        when(safety.getConfigValue(SafetyKeys.anvilPrefilterEnabled, Boolean.TRUE)).thenReturn("false");
        when(configs.getConfigValue(BiomesKeys.biomeWhitelist, Boolean.FALSE)).thenReturn(Boolean.TRUE);
        assertEquals("anvil_prefilter_off+biome_whitelist", RtpBStatsCatalogue.detectSafetyFeaturesEnabled());

        doThrow(new IllegalStateException("boom")).when(configs).getParser(SafetyKeys.class);
        assertEquals("unknown", RtpBStatsCatalogue.detectSafetyFeaturesEnabled());
    }

    @Test
    @DisplayName("aggregate_success_rate covers every documented bucket")
    void successRateBuckets() {
        assertEquals("unknown", RtpBStatsCatalogue.aggregateSuccessRateBucket());
        assertEquals("<50", rateBucket(1, 3));
        assertEquals("50-75", rateBucket(3, 2));
        assertEquals("75-90", rateBucket(8, 2));
        assertEquals("90-99", rateBucket(95, 5));
        assertEquals("99+", rateBucket(10, 0));
    }

    private static String rateBucket(int successes, int failures) {
        RtpOutcomeStats.GLOBAL.reset();
        for (int i = 0; i < successes; i++) RtpOutcomeStats.GLOBAL.recordSuccess();
        for (int i = 0; i < failures; i++) RtpOutcomeStats.GLOBAL.recordFailure(FailTypes.values()[0]);
        return RtpBStatsCatalogue.aggregateSuccessRateBucket();
    }

    @Test
    @DisplayName("aggregate_top_failure_cause names the most frequent FailTypes, or none")
    void topFailureCause() {
        assertEquals("none", RtpBStatsCatalogue.detectTopFailureCause());
        FailTypes[] types = FailTypes.values();
        FailTypes expected = types[types.length - 1];
        RtpOutcomeStats.GLOBAL.recordFailure(types[0]);
        RtpOutcomeStats.GLOBAL.recordFailure(expected);
        RtpOutcomeStats.GLOBAL.recordFailure(expected);
        assertEquals(expected.name(), RtpBStatsCatalogue.detectTopFailureCause());
    }

    @Test
    @DisplayName("rtpExt returns the snapshot extension or a zeroed fallback")
    void rtpExtFallbacks() {
        assertEquals(0, RtpBStatsCatalogue.rtpExt(null).queueDepth);

        MetricsSnapshot empty = mock(MetricsSnapshot.class);
        assertEquals(0, RtpBStatsCatalogue.rtpExt(empty).queueDepth);

        RTPMetricsExtension ext = new RTPMetricsExtension(7, 0, 0, 0, Double.NaN, -1);
        MetricsSnapshot withExt = mock(MetricsSnapshot.class);
        when(withExt.extension(RTPMetricsExtension.class)).thenReturn(ext);
        assertSame(ext, RtpBStatsCatalogue.rtpExt(withExt));
    }

    @Test
    @DisplayName("mspt_p99 drilldown keeps a non-blank outer label and maps blank to unknown")
    void msptP99Drilldown() {
        assertTrue(RtpBStatsCatalogue.msptP99Drilldown(" ").containsKey("unknown"));
        Map<String, Map<String, Integer>> drill = RtpBStatsCatalogue.msptP99Drilldown("paper");
        assertTrue(drill.containsKey("paper"));
        assertEquals(1, drill.get("paper").size());
    }

    @Test
    @DisplayName("percentile handles empty input and clamps the rank to the sample range")
    void percentileEdges() {
        assertTrue(Double.isNaN(RtpBStatsCatalogue.percentile(null, 0.5)));
        assertTrue(Double.isNaN(RtpBStatsCatalogue.percentile(new double[0], 0.5)));
        assertEquals(1.0, RtpBStatsCatalogue.percentile(new double[] {3, 1, 2}, 0.0));
        assertEquals(3.0, RtpBStatsCatalogue.percentile(new double[] {3, 1, 2}, 2.0));
        assertEquals(2.0, RtpBStatsCatalogue.percentile(new double[] {3, 1, 2}, 0.5));
    }

    // --- RTP-cost sampler lifecycle ---

    @Test
    @DisplayName("Cost sampler starts once on the RTP scheduler, samples every window, and cancels on stop")
    void samplerLifecycle() {
        RTPScheduler scheduler = mock(RTPScheduler.class);
        Object handle = new Object();
        when(scheduler.runTaskTimerAsynchronously(any(Runnable.class), anyLong(), anyLong())).thenReturn(handle);
        RTPRuntime.setScheduler(scheduler);

        RtpBStatsCatalogue.startRtpCostSampler();
        RtpBStatsCatalogue.startRtpCostSampler();
        ArgumentCaptor<Runnable> body = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(1)).runTaskTimerAsynchronously(body.capture(), anyLong(), anyLong());

        RtpBStatsCatalogue.RTP_SYNC_COST_WINDOW.reset();
        body.getValue().run();
        RtpOutcomeStats.GLOBAL.recordSuccess();
        body.getValue().run();
        assertFalse(Double.isNaN(RtpBStatsCatalogue.RTP_SYNC_COST_WINDOW.msPerRtp()),
                "second sample after one served RTP yields a finite cost");

        RtpBStatsCatalogue.stopRtpCostSampler();
        verify(scheduler).cancelTask(handle);
    }

    @Test
    @DisplayName("Cost sampler survives a failing scheduler on start and on cancel")
    void samplerFailures() {
        RTPScheduler failing = mock(RTPScheduler.class);
        when(failing.runTaskTimerAsynchronously(any(Runnable.class), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("boom"));
        RTPRuntime.setScheduler(failing);
        assertDoesNotThrow(RtpBStatsCatalogue::startRtpCostSampler);
        RtpBStatsCatalogue.stopRtpCostSampler();
        verify(failing, never()).cancelTask(any());

        RTPScheduler cancelFails = mock(RTPScheduler.class);
        Object handle = new Object();
        when(cancelFails.runTaskTimerAsynchronously(any(Runnable.class), anyLong(), anyLong())).thenReturn(handle);
        doThrow(new IllegalStateException("boom")).when(cancelFails).cancelTask(handle);
        RTPRuntime.setScheduler(cancelFails);
        RtpBStatsCatalogue.startRtpCostSampler();
        assertDoesNotThrow(RtpBStatsCatalogue::stopRtpCostSampler);

        // Stop without a scheduler still clears the task so a later start re-schedules.
        RTPScheduler later = mock(RTPScheduler.class);
        when(later.runTaskTimerAsynchronously(any(Runnable.class), anyLong(), anyLong())).thenReturn(handle);
        RTPRuntime.setScheduler(later);
        RtpBStatsCatalogue.startRtpCostSampler();
        RTPRuntime.setScheduler(null);
        RtpBStatsCatalogue.stopRtpCostSampler();
        verify(later, never()).cancelTask(any());
        RTPRuntime.setScheduler(later);
        RtpBStatsCatalogue.startRtpCostSampler();
        verify(later, times(2)).runTaskTimerAsynchronously(any(Runnable.class), anyLong(), anyLong());
    }

    // --- Bucket boundaries ---

    @Test
    @DisplayName("TPS and MSPT bucketisers emit every documented label at their boundaries")
    void bucketBoundaries() {
        assertEquals("10-15", RtpBStatsCatalogue.tpsBucket(12.0));
        assertEquals("25-50", RtpBStatsCatalogue.msptBucket(30.0));
        assertEquals("50-100", RtpBStatsCatalogue.msptBucket(70.0));

        assertEquals("unknown", RtpBStatsCatalogue.msptP99Bucket(-1.0));
        assertEquals("<25", RtpBStatsCatalogue.msptP99Bucket(10.0));
        assertEquals("25-50", RtpBStatsCatalogue.msptP99Bucket(30.0));
        assertEquals("50-100", RtpBStatsCatalogue.msptP99Bucket(70.0));
        assertEquals("100-250", RtpBStatsCatalogue.msptP99Bucket(150.0));
        assertEquals("1000+", RtpBStatsCatalogue.msptP99Bucket(5000.0));
    }

    @Test
    @DisplayName("Count, cost and version bucketisers emit every documented label at their boundaries")
    void countAndCostBucketBoundaries() {
        assertEquals("10-50", RtpBStatsCatalogue.memoryTrackerBucket(20));
        assertEquals("50-200", RtpBStatsCatalogue.memoryTrackerBucket(100));
        assertEquals("1-5", RtpBStatsCatalogue.chunkBacklogBucket(3));
        assertEquals("6-20", RtpBStatsCatalogue.chunkBacklogBucket(10));
        assertEquals("1-5", RtpBStatsCatalogue.queueDepthBucket(3));
        assertEquals("6-20", RtpBStatsCatalogue.queueDepthBucket(10));

        assertEquals("1-5", RtpBStatsCatalogue.playerCountBucket(3));
        assertEquals("6-20", RtpBStatsCatalogue.playerCountBucket(10));
        assertEquals("51-100", RtpBStatsCatalogue.playerCountBucket(75));
        assertEquals("101-250", RtpBStatsCatalogue.playerCountBucket(200));

        assertEquals("unknown", RtpBStatsCatalogue.rtpCostBucket(-1.0));
        assertEquals("0.1-0.5", RtpBStatsCatalogue.rtpCostBucket(0.3));
        assertEquals("2-10", RtpBStatsCatalogue.rtpCostBucket(5.0));
        assertEquals("10-50", RtpBStatsCatalogue.rtpCostBucket(20.0));

        assertEquals("unknown", RtpBStatsCatalogue.majorMinor("a.b"));
        assertEquals("unknown", RtpBStatsCatalogue.majorMinor("1.b"));
        assertTrue(RtpBStatsCatalogue.chunkLoadFloorDrilldown(" ").containsKey("unknown/generated"));
    }
}
