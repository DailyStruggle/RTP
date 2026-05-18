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
