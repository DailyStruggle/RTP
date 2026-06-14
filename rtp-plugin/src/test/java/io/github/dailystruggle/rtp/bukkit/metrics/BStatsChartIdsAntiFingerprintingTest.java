package io.github.dailystruggle.rtp.bukkit.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anti-fingerprinting guard for the bStats configuration-adoption chart
 * suppliers (CHECKLIST-metrics-and-multiserver.md row B13).
 *
 * <p>Each chart's value supplier is invoked and its rendered payload is
 * regex-scanned for patterns that would identify an individual server:
 * UUIDs, IPv4 addresses, hostnames containing dots, and bare integers that
 * look like ports or numeric server IDs. Any hit is a defect because RTP
 * publishes only aggregate, low-cardinality configuration adoption — never a
 * {@code serverId}-equivalent string.
 *
 * <p>This test deliberately drives the package-private detector methods
 * directly rather than constructing a real bStats {@code Metrics} handle.
 * The shape of the rendered chart payload (label string, tally map keys,
 * AdvancedPie bucket names) is what bStats serializes; that's what we scan.
 *
 * <p>The fixture runs without an initialized {@code RTP} singleton: each
 * detector is hardened with {@code Throwable}-guarded fallbacks
 * ({@code "default"}, {@code "unknown"}, {@code "none"}, empty maps), so the
 * suppliers must still produce safe values and the no-fingerprint guarantee
 * must still hold.
 */
class BStatsChartIdsAntiFingerprintingTest {

    /** UUID 8-4-4-4-12 hex pattern. */
    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Dotted-quad IPv4. */
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");

    /** Hostname-shaped token: two or more dot-separated labels. */
    private static final Pattern HOSTNAME = Pattern.compile("\\b[A-Za-z0-9-]+\\.[A-Za-z0-9.-]+\\b");

    private static void assertNoFingerprint(String label, String payload) {
        assertNotNull(payload, label + " must not be null");
        assertFalse(UUID.matcher(payload).find(),
                label + " must not contain a UUID: " + payload);
        assertFalse(IPV4.matcher(payload).find(),
                label + " must not contain an IPv4 address: " + payload);
        assertFalse(HOSTNAME.matcher(payload).find(),
                label + " must not contain a hostname-shaped token: " + payload);
    }

    @Test
    @DisplayName("region_shapes_in_use payload contains no UUID/IPv4/hostname tokens")
    void regionShapes_noFingerprint() {
        Map<String, Integer> tally = RTPCostMetricsCharts.detectRegionShapesInUse();
        assertNotNull(tally, "detectRegionShapesInUse must return a non-null map");
        // Map is allowed to be empty when permRegionLookup is unset (test fixture).
        for (Map.Entry<String, Integer> e : tally.entrySet()) {
            assertNoFingerprint("region_shapes_in_use key", e.getKey());
        }
    }

    @Test
    @DisplayName("safety_features_enabled payload is a low-cardinality slug, no fingerprint")
    void safetyFeatures_noFingerprint() {
        String slug = RTPCostMetricsCharts.detectSafetyFeaturesEnabled();
        assertNoFingerprint("safety_features_enabled", slug);
        // Whitelisted slugs only; any other shape would fail this check.
        // We allow "default" / "unknown" / a "+"-joined sorted subset of the
        // documented boolean toggle names.
        assertTrue(slug.matches("default|unknown|[a-z_]+(\\+[a-z_]+)*"),
                "safety_features_enabled slug must match the documented schema: " + slug);
    }

    @Test
    @DisplayName("addons_loaded payload only reports plugins from the hard-coded whitelist")
    void addonsLoaded_noFingerprint_andWhitelistOnly() {
        Map<String, Integer> tally = RTPCostMetricsCharts.detectAddonsLoaded();
        assertNotNull(tally, "detectAddonsLoaded must return a non-null map");
        assertFalse(tally.isEmpty(),
                "detectAddonsLoaded must always emit at least the 'none' bucket");
        for (Map.Entry<String, Integer> e : tally.entrySet()) {
            String key = e.getKey();
            assertNoFingerprint("addons_loaded key", key);
            // Allow the canonical whitelist entry or the "none" sentinel.
            boolean allowed = "none".equals(key)
                    || RTPCostMetricsCharts.KNOWN_ADDON_PLUGINS.contains(key);
            assertTrue(allowed,
                    "addons_loaded key must be 'none' or a whitelisted softdepend name; got: " + key);
        }
    }

    @Test
    @DisplayName("aggregate_success_rate payload is a bucketised label, no raw count, no fingerprint")
    void aggregateSuccessRate_noFingerprint_andBucketised() {
        String bucket = RTPCostMetricsCharts.aggregateSuccessRateBucket();
        assertNoFingerprint("aggregate_success_rate", bucket);
        // Only the documented bucket labels (or "unknown") are permitted; a raw
        // numeric percentage or count would fail this schema check.
        assertTrue(bucket.matches("unknown|<50|50-75|75-90|90-99|99\\+"),
                "aggregate_success_rate must be a documented bucket label; got: " + bucket);
    }

    @Test
    @DisplayName("aggregate_top_failure_cause payload is a FailTypes name or sentinel, no fingerprint")
    void aggregateTopFailureCause_noFingerprint_andBounded() {
        String cause = RTPCostMetricsCharts.detectTopFailureCause();
        assertNoFingerprint("aggregate_top_failure_cause", cause);
        boolean allowed = "none".equals(cause) || "unknown".equals(cause);
        if (!allowed) {
            for (var ft : io.github.dailystruggle.rtp.common.selection.region.LocationGenerator.FailTypes.values()) {
                if (ft.name().equals(cause)) {
                    allowed = true;
                    break;
                }
            }
        }
        assertTrue(allowed,
                "aggregate_top_failure_cause must be 'none', 'unknown', or a FailTypes name; got: " + cause);
    }

    @Test
    @DisplayName("mspt_p99_by_platform inner bucket is a documented label, no raw MSPT, no fingerprint")
    void msptP99ByPlatform_noFingerprint_andBucketised() {
        // Inner slice: bucketised MSPT p99. NaN/unsampled -> "unknown".
        String bucket = RTPCostMetricsCharts.msptP99Bucket(RTPCostMetricsCharts.msptP99Ms());
        assertNoFingerprint("mspt_p99_by_platform inner", bucket);
        assertTrue(bucket.matches("unknown|<25|25-50|50-100|100-250|250-1000|1000\\+"),
                "mspt_p99 inner bucket must be a documented label; got: " + bucket);
        // A representative non-NaN value must also bucket (never echo the raw ms).
        String b250 = RTPCostMetricsCharts.msptP99Bucket(250.0);
        assertTrue("250-1000".equals(b250),
                "250.0 ms must bucket to '250-1000'; got: " + b250);
        // Outer slice: the platform label is the same fingerprint-safe detector
        // already used by the platform pie.
        assertNoFingerprint("mspt_p99_by_platform outer", RTPCostMetricsCharts.detectPlatform());
    }

    @Test
    @DisplayName("mspt_p99_by_game_version / by_plugin_version outers are bounded, no fingerprint")
    void msptP99ByVersion_noFingerprint_andBounded() {
        // Game version reduced to major.minor (or "unknown"); never a raw build string.
        String game = RTPCostMetricsCharts.detectGameVersion();
        assertNoFingerprint("mspt_p99_by_game_version outer", game);
        assertTrue(game.matches("unknown|\\d+\\.\\d+|\\d+"),
                "game version must be major.minor (or 'unknown'); got: " + game);
        // Plugin version label resolves without throwing (no live server -> "unknown").
        String plugin = RTPCostMetricsCharts.detectPluginVersion();
        assertNoFingerprint("mspt_p99_by_plugin_version outer", plugin);
        // Inner slice on both charts is the same bucketised MSPT p99 label.
        String bucket = RTPCostMetricsCharts.msptP99Bucket(RTPCostMetricsCharts.msptP99Ms());
        assertTrue(bucket.matches("unknown|<25|25-50|50-100|100-250|250-1000|1000\\+"),
                "mspt-p99-by-version inner bucket must be a documented label; got: " + bucket);
    }

    @Test
    @DisplayName("player_count_buckets payload is a bucketised label, no raw count, no fingerprint")
    void playerCount_noFingerprint_andBucketised() {
        // No live server in the fixture -> count is unresolved -> "unknown".
        String bucket = RTPCostMetricsCharts.playerCountBucket(RTPCostMetricsCharts.detectOnlinePlayerCount());
        assertNoFingerprint("player_count_buckets", bucket);
        assertTrue(bucket.matches("unknown|0|1-5|6-20|21-50|51-100|101-250|250\\+"),
                "player_count_buckets must be a documented bucket label; got: " + bucket);
        // Representative counts must bucket (never echo the raw count).
        assertTrue("0".equals(RTPCostMetricsCharts.playerCountBucket(0)),
                "0 players must bucket to '0'");
        assertTrue("21-50".equals(RTPCostMetricsCharts.playerCountBucket(42)),
                "42 players must bucket to '21-50'");
        assertTrue("250+".equals(RTPCostMetricsCharts.playerCountBucket(1000)),
                "1000 players must bucket to '250+'");
        assertTrue("unknown".equals(RTPCostMetricsCharts.playerCountBucket(-1)),
                "an unresolved count must bucket to 'unknown'");
    }

    @Test
    @DisplayName("rtp_*_cost_per_rtp payload is a bucketised label, windowed, no raw timing, no fingerprint")
    void rtpCost_noFingerprint_andBucketised() {
        // No sampler has run in the fixture -> windows unusable -> "unknown".
        for (RTPCostMetricsCharts.RtpCostWindow win :
                new RTPCostMetricsCharts.RtpCostWindow[] {
                        RTPCostMetricsCharts.RTP_SYNC_COST_WINDOW,
                        RTPCostMetricsCharts.RTP_ASYNC_COST_WINDOW }) {
            String bucket = RTPCostMetricsCharts.rtpCostBucket(win.msPerRtp());
            assertNoFingerprint("rtp_cost_per_rtp", bucket);
            assertTrue(bucket.matches("unknown|<0\\.1|0\\.1-0\\.5|0\\.5-2|2-10|10-50|50\\+"),
                    "rtp_cost_per_rtp must be a documented bucket label; got: " + bucket);
        }

        // Drilldown structure: every chart always carries a "scheduler" component;
        // the chunk_generated / chunk_ungenerated components appear only on the
        // chart whose family matches the backend's chunk-load mode. All outer keys
        // and inner bucket labels are bounded / non-fingerprinting.
        String mode = RTPCostMetricsCharts.detectChunkLoadMode();
        for (boolean sync : new boolean[] {true, false}) {
            Map<String, Map<String, Integer>> drill = RTPCostMetricsCharts.rtpCostDrilldown(sync);
            assertTrue(drill.containsKey("scheduler"),
                    "every cost chart must carry a 'scheduler' component");
            boolean modeMatches =
                    ("async".equals(mode) && !sync) || ("sync".equals(mode) && sync);
            assertTrue(drill.containsKey("chunk_generated") == modeMatches,
                    "chunk_generated must appear iff the chart family matches the chunk-load mode");
            assertTrue(drill.containsKey("chunk_ungenerated") == modeMatches,
                    "chunk_ungenerated must appear iff the chart family matches the chunk-load mode");
            for (Map.Entry<String, Map<String, Integer>> e : drill.entrySet()) {
                assertNoFingerprint("rtp_cost_per_rtp outer", e.getKey());
                assertTrue(e.getKey().matches("scheduler|chunk_generated|chunk_ungenerated"),
                        "outer component must be a documented label; got: " + e.getKey());
                for (String inner : e.getValue().keySet()) {
                    // Inner bucket labels (e.g. "0.5-2") legitimately contain dots,
                    // so the bounded whitelist regex is the anti-fingerprint guard.
                    assertTrue(inner.matches("unknown|<0\\.1|0\\.1-0\\.5|0\\.5-2|2-10|10-50|50\\+"),
                            "inner bucket must be a documented label; got: " + inner);
                }
            }
        }

        // Representative figures must bucket (never echo the raw ms value).
        assertTrue("unknown".equals(RTPCostMetricsCharts.rtpCostBucket(Double.NaN)),
                "an unsampled window must bucket to 'unknown'");
        assertTrue("<0.1".equals(RTPCostMetricsCharts.rtpCostBucket(0.05)),
                "0.05 ms/rtp must bucket to '<0.1'");
        assertTrue("0.5-2".equals(RTPCostMetricsCharts.rtpCostBucket(1.0)),
                "1.0 ms/rtp must bucket to '0.5-2'");
        assertTrue("50+".equals(RTPCostMetricsCharts.rtpCostBucket(123.0)),
                "123 ms/rtp must bucket to '50+'");

        // Window math: total ns over the window / total RTP served, in ms.
        RTPCostMetricsCharts.RtpCostWindow w = new RTPCostMetricsCharts.RtpCostWindow(true);
        // First sample only seeds the cumulative baselines (deltas unknown).
        w.sample(1_000_000L, 100L);
        // +10 RTPs served and +20 ms (20_000_000 ns) cost across the next minute.
        w.sample(21_000_000L, 110L);
        // ns delta in window: 0 + 20_000_000 = 20_000_000 (20 ms); RTP: 0 + 10 = 10.
        double ms = w.msPerRtp();
        assertTrue(Math.abs(ms - 2.0) < 1.0e-9,
                "windowed cost must be 20ms / 10 = 2.0 ms/rtp; got: " + ms);
        assertTrue("2-10".equals(RTPCostMetricsCharts.rtpCostBucket(ms)),
                "2.0 ms/rtp must bucket to '2-10'");

        // A window with cost but no RTP served must stay 'unknown'.
        RTPCostMetricsCharts.RtpCostWindow idle = new RTPCostMetricsCharts.RtpCostWindow(false);
        idle.sample(5_000_000L, 0L);
        idle.sample(9_000_000L, 0L);
        assertTrue(Double.isNaN(idle.msPerRtp()),
                "a window with zero RTP served must produce NaN (-> 'unknown')");
    }

    @Test
    @DisplayName("chunk_load_floor_ms drilldown outer is <mode>/<genstate>, inner is a bucket, no fingerprint")
    void chunkLoadFloor_noFingerprint_andBucketised() {
        // Reset so this test sees a deterministic profile (no load recorded yet).
        io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile.GLOBAL.reset();

        // Outer slice: chunk-load mode is assumed from the detected platform and
        // bounded to {sync, async, unknown}.
        String mode = RTPCostMetricsCharts.detectChunkLoadMode();
        assertNoFingerprint("chunk_load_floor_ms mode", mode);
        assertTrue(mode.matches("sync|async|unknown"),
                "chunk_load_floor_ms mode must be sync/async/unknown; got: " + mode);

        // With no load recorded, the drilldown reports a single placeholder slice
        // ("<mode>/generated" -> {"unknown":1}) so the chart still appears.
        Map<String, Map<String, Integer>> emptyDrill = RTPCostMetricsCharts.chunkLoadFloorDrilldown();
        assertTrue(emptyDrill.size() == 1, "no load -> single placeholder outer slice");
        assertTrue(emptyDrill.containsKey(mode + "/generated"),
                "placeholder outer slice must be <mode>/generated; got: " + emptyDrill.keySet());
        assertTrue(emptyDrill.get(mode + "/generated").containsKey("unknown"),
                "placeholder inner must be 'unknown'");

        // Record a generated and an ungenerated load; both outer slices must appear,
        // each carrying a documented bucket and never a raw timing.
        io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile.GLOBAL.record(true, 1_000_000L);   // 1 ms generated
        io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile.GLOBAL.record(false, 80_000_000L); // 80 ms ungenerated
        Map<String, Map<String, Integer>> drill = RTPCostMetricsCharts.chunkLoadFloorDrilldown();
        assertTrue(drill.containsKey(mode + "/generated") && drill.containsKey(mode + "/ungenerated"),
                "both gen/ungen outer slices must appear; got: " + drill.keySet());
        for (Map.Entry<String, Map<String, Integer>> e : drill.entrySet()) {
            assertNoFingerprint("chunk_load_floor_ms outer", e.getKey());
            assertTrue(e.getKey().matches("(sync|async|unknown)/(generated|ungenerated)"),
                    "outer slice must be <mode>/<genstate>; got: " + e.getKey());
            for (String inner : e.getValue().keySet()) {
                // Inner bucket labels (e.g. "0.5-2") legitimately contain dots, so
                // the bounded whitelist regex - not the hostname scan - is the guard.
                assertTrue(inner.matches("unknown|<0\\.1|0\\.1-0\\.5|0\\.5-2|2-10|10-50|50\\+"),
                        "inner must be a documented bucket label; got: " + inner);
            }
        }
        assertTrue(drill.get(mode + "/generated").containsKey("0.5-2"),
                "1 ms generated floor must bucket to '0.5-2'");
        assertTrue(drill.get(mode + "/ungenerated").containsKey("50+"),
                "80 ms ungenerated floor must bucket to '50+'");
        io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile.GLOBAL.reset();

        // Representative floors must bucket (never echo the raw ns/ms value).
        assertTrue("unknown".equals(RTPCostMetricsCharts.chunkLoadFloorBucket(-1L)),
                "no recorded load must bucket to 'unknown'");
        assertTrue("<0.1".equals(RTPCostMetricsCharts.chunkLoadFloorBucket(50_000L)),
                "0.05 ms (50_000 ns) must bucket to '<0.1'");
        assertTrue("0.5-2".equals(RTPCostMetricsCharts.chunkLoadFloorBucket(1_000_000L)),
                "1.0 ms (1_000_000 ns) must bucket to '0.5-2'");
        assertTrue("50+".equals(RTPCostMetricsCharts.chunkLoadFloorBucket(123_000_000L)),
                "123 ms must bucket to '50+'");
    }

    @Test
    @DisplayName("language_selection payload is a whitelisted locale or sentinel, no fingerprint")
    void languageSelection_noFingerprint_andBounded() {
        String lang = RTPCostMetricsCharts.detectLanguage();
        assertNoFingerprint("language_selection", lang);
        boolean allowed = "other".equals(lang)
                || "unknown".equals(lang)
                || RTPCostMetricsCharts.KNOWN_LOCALES.contains(lang);
        assertTrue(allowed,
                "language_selection must be a whitelisted locale, 'other', or 'unknown'; got: " + lang);
    }

    @Test
    @DisplayName("KNOWN_LOCALES list is closed; no arbitrary locale names can leak")
    void knownLocalesList_isImmutableAndBounded() {
        assertNotNull(RTPCostMetricsCharts.KNOWN_LOCALES);
        assertFalse(RTPCostMetricsCharts.KNOWN_LOCALES.isEmpty(),
                "shipped-locale whitelist must not be empty");
        try {
            RTPCostMetricsCharts.KNOWN_LOCALES.add("xx");
            org.junit.jupiter.api.Assertions.fail(
                    "KNOWN_LOCALES must be unmodifiable so runtime code cannot leak arbitrary locale names");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    @DisplayName("KNOWN_ADDON_PLUGINS list is closed; no arbitrary plugin names can leak")
    void knownAddonPluginsList_isImmutableAndBounded() {
        assertNotNull(RTPCostMetricsCharts.KNOWN_ADDON_PLUGINS);
        assertFalse(RTPCostMetricsCharts.KNOWN_ADDON_PLUGINS.isEmpty(),
                "whitelist must mirror plugin.yml softdepend; empty would defeat the chart");
        // Mutation must throw — caller can't expand the whitelist at runtime.
        try {
            RTPCostMetricsCharts.KNOWN_ADDON_PLUGINS.add("BackdoorPlugin");
            org.junit.jupiter.api.Assertions.fail(
                    "KNOWN_ADDON_PLUGINS must be unmodifiable so runtime code cannot leak arbitrary plugin names");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
