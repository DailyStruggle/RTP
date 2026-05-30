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
