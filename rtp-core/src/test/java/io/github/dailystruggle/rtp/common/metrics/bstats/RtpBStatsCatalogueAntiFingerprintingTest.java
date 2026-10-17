package io.github.dailystruggle.rtp.common.metrics.bstats;

import io.github.dailystruggle.rtp.common.metrics.ChunkLoadProfile;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anti-fingerprinting guard for the platform-neutral chart suppliers in
 * {@link RtpBStatsCatalogue}. Payload labels are regex-scanned for UUIDs, IPv4
 * addresses and hostname-shaped tokens, and checked against the documented bucket
 * schemas. Runs without an initialised {@code RTP}: every detector must still fall
 * back to a safe sentinel.
 */
class RtpBStatsCatalogueAntiFingerprintingTest {

    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");
    private static final Pattern HOSTNAME = Pattern.compile("\\b[A-Za-z0-9-]+\\.[A-Za-z0-9.-]+\\b");
    private static final String COST_BUCKETS = "unknown|<0\\.1|0\\.1-0\\.5|0\\.5-2|2-10|10-50|50\\+";

    private static void assertNoFingerprint(String label, String payload) {
        assertNotNull(payload, label + " must not be null");
        assertFalse(UUID.matcher(payload).find(), label + " must not contain a UUID: " + payload);
        assertFalse(IPV4.matcher(payload).find(), label + " must not contain an IPv4 address: " + payload);
        assertFalse(HOSTNAME.matcher(payload).find(), label + " must not contain a hostname-shaped token: " + payload);
    }

    @Test
    @DisplayName("region_shapes_in_use keys contain no fingerprint")
    void regionShapes_noFingerprint() {
        Map<String, Integer> tally = RtpBStatsCatalogue.detectRegionShapesInUse();
        assertNotNull(tally);
        for (String key : tally.keySet()) {
            assertNoFingerprint("region_shapes_in_use key", key);
        }
    }

    @Test
    @DisplayName("safety_features_enabled is a documented low-cardinality slug")
    void safetyFeatures_noFingerprint() {
        String slug = RtpBStatsCatalogue.detectSafetyFeaturesEnabled();
        assertNoFingerprint("safety_features_enabled", slug);
        assertTrue(slug.matches("default|unknown|[a-z_]+(\\+[a-z_]+)*"), slug);
    }

    @Test
    @DisplayName("database_backend is a documented label")
    void databaseBackend_bounded() {
        String db = RtpBStatsCatalogue.detectDatabaseBackend();
        assertTrue(db.matches("none|unknown|yaml|h2|sqlite|postgresql|mysql|other"), db);
    }

    @Test
    @DisplayName("aggregate_success_rate is a bucket label")
    void aggregateSuccessRate_bucketised() {
        String bucket = RtpBStatsCatalogue.aggregateSuccessRateBucket();
        assertNoFingerprint("aggregate_success_rate", bucket);
        assertTrue(bucket.matches("unknown|<50|50-75|75-90|90-99|99\\+"), bucket);
    }

    @Test
    @DisplayName("aggregate_top_failure_cause is a FailTypes name or sentinel")
    void aggregateTopFailureCause_bounded() {
        String cause = RtpBStatsCatalogue.detectTopFailureCause();
        assertNoFingerprint("aggregate_top_failure_cause", cause);
        boolean allowed = "none".equals(cause) || "unknown".equals(cause);
        for (LocationGenerator.FailTypes ft : LocationGenerator.FailTypes.values()) {
            allowed |= ft.name().equals(cause);
        }
        assertTrue(allowed, cause);
    }

    @Test
    @DisplayName("mspt_p99 inner bucket is a documented label; outer defaults to unknown")
    void msptP99_bucketised() {
        String bucket = RtpBStatsCatalogue.msptP99Bucket(RtpBStatsCatalogue.msptP99Ms());
        assertTrue(bucket.matches("unknown|<25|25-50|50-100|100-250|250-1000|1000\\+"), bucket);
        assertEquals("250-1000", RtpBStatsCatalogue.msptP99Bucket(250.0));
        Map<String, Map<String, Integer>> drill = RtpBStatsCatalogue.msptP99Drilldown(null);
        assertTrue(drill.containsKey("unknown"));
    }

    @Test
    @DisplayName("player_count_buckets never echoes a raw count")
    void playerCount_bucketised() {
        assertEquals("0", RtpBStatsCatalogue.playerCountBucket(0));
        assertEquals("21-50", RtpBStatsCatalogue.playerCountBucket(42));
        assertEquals("250+", RtpBStatsCatalogue.playerCountBucket(1000));
        assertEquals("unknown", RtpBStatsCatalogue.playerCountBucket(-1));
    }

    @Test
    @DisplayName("rtp_*_cost_per_rtp: windowed, bucketised, components gated by chunk-load mode")
    void rtpCost_bucketisedAndGated() {
        for (RtpBStatsCatalogue.RtpCostWindow win : new RtpBStatsCatalogue.RtpCostWindow[] {
                RtpBStatsCatalogue.RTP_SYNC_COST_WINDOW, RtpBStatsCatalogue.RTP_ASYNC_COST_WINDOW}) {
            assertTrue(RtpBStatsCatalogue.rtpCostBucket(win.msPerRtp()).matches(COST_BUCKETS));
        }
        for (String mode : new String[] {"sync", "async", "unknown"}) {
            for (boolean sync : new boolean[] {true, false}) {
                Map<String, Map<String, Integer>> drill = RtpBStatsCatalogue.rtpCostDrilldown(sync, mode);
                assertTrue(drill.containsKey("scheduler"));
                boolean modeMatches = ("async".equals(mode) && !sync) || ("sync".equals(mode) && sync);
                assertEquals(modeMatches, drill.containsKey("chunk_generated"), mode + "/" + sync);
                assertEquals(modeMatches, drill.containsKey("chunk_ungenerated"), mode + "/" + sync);
                for (Map.Entry<String, Map<String, Integer>> e : drill.entrySet()) {
                    assertTrue(e.getKey().matches("scheduler|chunk_generated|chunk_ungenerated"));
                    for (String inner : e.getValue().keySet()) {
                        assertTrue(inner.matches(COST_BUCKETS), inner);
                    }
                }
            }
        }
        assertEquals("unknown", RtpBStatsCatalogue.rtpCostBucket(Double.NaN));
        assertEquals("<0.1", RtpBStatsCatalogue.rtpCostBucket(0.05));
        assertEquals("0.5-2", RtpBStatsCatalogue.rtpCostBucket(1.0));
        assertEquals("50+", RtpBStatsCatalogue.rtpCostBucket(123.0));

        RtpBStatsCatalogue.RtpCostWindow w = new RtpBStatsCatalogue.RtpCostWindow(true);
        assertTrue(w.isSync());
        w.sample(1_000_000L, 100L);
        w.sample(21_000_000L, 110L);
        assertEquals(2.0, w.msPerRtp(), 1.0e-9, "20 ms / 10 RTP");
        assertEquals("2-10", RtpBStatsCatalogue.rtpCostBucket(w.msPerRtp()));
        w.reset();
        assertTrue(Double.isNaN(w.msPerRtp()));

        RtpBStatsCatalogue.RtpCostWindow idle = new RtpBStatsCatalogue.RtpCostWindow(false);
        idle.sample(5_000_000L, 0L);
        idle.sample(9_000_000L, 0L);
        assertTrue(Double.isNaN(idle.msPerRtp()), "no RTP served -> NaN");
    }

    @Test
    @DisplayName("chunk_load_floor_ms: <mode>/<genstate> outer, bucket inner, placeholder when empty")
    void chunkLoadFloor_bucketised() {
        ChunkLoadProfile.GLOBAL.reset();
        Map<String, Map<String, Integer>> empty = RtpBStatsCatalogue.chunkLoadFloorDrilldown("async");
        assertEquals(1, empty.size());
        assertTrue(empty.get("async/generated").containsKey("unknown"));
        assertTrue(RtpBStatsCatalogue.chunkLoadFloorDrilldown(null).containsKey("unknown/generated"));

        ChunkLoadProfile.GLOBAL.record(true, 1_000_000L);
        ChunkLoadProfile.GLOBAL.record(false, 80_000_000L);
        Map<String, Map<String, Integer>> drill = RtpBStatsCatalogue.chunkLoadFloorDrilldown("sync");
        assertTrue(drill.get("sync/generated").containsKey("0.5-2"));
        assertTrue(drill.get("sync/ungenerated").containsKey("50+"));
        ChunkLoadProfile.GLOBAL.reset();

        assertEquals("unknown", RtpBStatsCatalogue.chunkLoadFloorBucket(-1L));
        assertEquals("<0.1", RtpBStatsCatalogue.chunkLoadFloorBucket(50_000L));
        assertEquals("50+", RtpBStatsCatalogue.chunkLoadFloorBucket(123_000_000L));
    }

    @Test
    @DisplayName("language_selection is a whitelisted locale or sentinel; whitelist is closed")
    void language_bounded() {
        String lang = RtpBStatsCatalogue.detectLanguage();
        assertNoFingerprint("language_selection", lang);
        assertTrue("other".equals(lang) || "unknown".equals(lang) || RtpBStatsCatalogue.KNOWN_LOCALES.contains(lang));
        assertThrows(UnsupportedOperationException.class, () -> RtpBStatsCatalogue.KNOWN_LOCALES.add("xx"));
    }

    @Test
    @DisplayName("majorMinor reduces version strings and never echoes free text")
    void majorMinor() {
        assertEquals("1.21", RtpBStatsCatalogue.majorMinor("1.21.4-R0.1-SNAPSHOT"));
        assertEquals("1.21", RtpBStatsCatalogue.majorMinor("1.21.11"));
        assertEquals("26.1", RtpBStatsCatalogue.majorMinor("26.1.2"));
        assertEquals("26", RtpBStatsCatalogue.majorMinor("26"));
        assertEquals("unknown", RtpBStatsCatalogue.majorMinor("git-Paper-123"));
        assertEquals("unknown", RtpBStatsCatalogue.majorMinor(null));
        assertEquals("unknown", RtpBStatsCatalogue.majorMinor(" "));
    }

    @Test
    @DisplayName("Remaining bucketisers keep their documented labels")
    void otherBuckets() {
        assertEquals("<10", RtpBStatsCatalogue.tpsBucket(8.5));
        assertEquals("15-19", RtpBStatsCatalogue.tpsBucket(18.2));
        assertEquals("19-20+", RtpBStatsCatalogue.tpsBucket(20.0));
        assertEquals("unknown", RtpBStatsCatalogue.tpsBucket(Double.NaN));
        assertEquals("<25", RtpBStatsCatalogue.msptBucket(15.0));
        assertEquals("100+", RtpBStatsCatalogue.msptBucket(120.0));
        assertEquals("unknown", RtpBStatsCatalogue.msptBucket(Double.NaN));
        assertEquals("<10", RtpBStatsCatalogue.memoryTrackerBucket(3));
        assertEquals("200+", RtpBStatsCatalogue.memoryTrackerBucket(500));
        assertEquals("0", RtpBStatsCatalogue.chunkBacklogBucket(0));
        assertEquals("21+", RtpBStatsCatalogue.chunkBacklogBucket(30));
        assertEquals("21-100", RtpBStatsCatalogue.queueDepthBucket(50));
        assertEquals("100+", RtpBStatsCatalogue.queueDepthBucket(101));
        assertTrue(Double.isNaN(RtpBStatsCatalogue.percentile(new double[] {Double.NaN}, 0.99)));
        assertEquals(9.0, RtpBStatsCatalogue.percentile(new double[] {1, 9, Double.NaN, 5}, 0.99));
    }
}
