package io.github.dailystruggle.rtp.bukkit.metrics;

import io.github.dailystruggle.bstats.api.BStatsConfig;
import io.github.dailystruggle.bstats.api.BStatsPlatform;
import io.github.dailystruggle.bstats.api.BStatsScheduler;
import io.github.dailystruggle.bstats.api.BStatsService;
import io.github.dailystruggle.bstats.api.CustomChart;
import io.github.dailystruggle.bstats.api.ServerInfo;
import io.github.dailystruggle.rtp.common.metrics.bstats.BStatsChartIds;
import io.github.dailystruggle.rtp.common.metrics.bstats.RtpBStatsCatalogue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anti-fingerprinting guard for the Bukkit-only bStats facts in
 * {@link BukkitBStatsHost}; the platform-neutral catalogue is covered by
 * {@code RtpBStatsCatalogueAntiFingerprintingTest} in rtp-core.
 *
 * <p>Runs without a live server: every detector must fall back to a safe sentinel.
 */
class BukkitBStatsHostTest {

    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b");
    private static final Pattern HOSTNAME = Pattern.compile("\\b[A-Za-z0-9-]+\\.[A-Za-z0-9.-]+\\b");

    private static final BStatsScheduler NO_OP_SCHEDULER = new BStatsScheduler() {
        @Override public void runOnMainThread(Runnable task) { }
        @Override public void runAsync(Runnable task) { }
        @Override public void runLater(Runnable task, long delayTicks) { }
        @Override public Object runAsyncTimer(Runnable task, long delayTicks, long periodTicks) { return null; }
        @Override public void cancel(Object handle) { }
    };

    private static void assertNoFingerprint(String label, String payload) {
        assertNotNull(payload, label + " must not be null");
        assertFalse(UUID.matcher(payload).find(), label + " must not contain a UUID: " + payload);
        assertFalse(IPV4.matcher(payload).find(), label + " must not contain an IPv4 address: " + payload);
        assertFalse(HOSTNAME.matcher(payload).find(), label + " must not contain a hostname-shaped token: " + payload);
    }

    @Test
    @DisplayName("addons_loaded only reports plugins from the hard-coded whitelist")
    void addonsLoaded_whitelistOnly() {
        Map<String, Integer> tally = BukkitBStatsHost.detectAddonsLoaded();
        assertFalse(tally.isEmpty(), "must always emit at least the 'none' bucket");
        for (String key : tally.keySet()) {
            assertNoFingerprint("addons_loaded key", key);
            assertTrue("none".equals(key) || BukkitBStatsHost.KNOWN_ADDON_PLUGINS.contains(key), key);
        }
    }

    @Test
    @DisplayName("platform / chunk-load mode labels are bounded")
    void platformAndMode_bounded() {
        assertNoFingerprint("platform", BukkitBStatsHost.detectPlatform());
        assertTrue(BukkitBStatsHost.detectChunkLoadMode().matches("sync|async|unknown"));
    }

    @Test
    @DisplayName("game version is major.minor (or unknown); plugin version resolves without throwing")
    void versions_bounded() {
        String game = BukkitBStatsHost.detectGameVersion();
        assertNoFingerprint("game version", game);
        assertTrue(game.matches("unknown|\\d+\\.\\d+|\\d+"), game);
        assertNoFingerprint("plugin version", BukkitBStatsHost.detectPluginVersion());
        assertNoFingerprint("host plugin version", new BukkitBStatsHost(null).pluginVersion());
    }

    @Test
    @DisplayName("Server info without a live server: unknown players, no guessed fields")
    void serverInfo_unresolved() {
        assertTrue(BukkitBStatsHost.detectOnlinePlayerCount() >= -1);
        BukkitBStatsHost host = new BukkitBStatsHost(null);
        ServerInfo info = host.serverInfo();
        Map<String, Object> root = BStatsPlatform.BUKKIT.rootFields(info);
        assertTrue((Integer) root.get("playerAmount") >= 0, "playerAmount is never negative");
        assertTrue(host.collectOnMainThread(), "Bukkit suppliers touch the Bukkit API");
        assertEquals(BStatsConfig.Format.YAML, host.configFormat());
    }

    @Test
    @DisplayName("Bukkit host registers the shared catalogue plus addons_loaded")
    void registersCatalogue(@TempDir Path tmp) {
        BStatsService service = BStatsService.builder(BStatsPlatform.BUKKIT, 30865)
                .pluginDirectory(tmp.resolve("plugins").resolve("RTP").toFile())
                .scheduler(NO_OP_SCHEDULER)
                .transport((url, body) -> 200)
                .build();
        RtpBStatsCatalogue.register(service, new BukkitBStatsHost(null), "lite");
        Set<String> ids = service.charts().stream().map(CustomChart::getChartId).collect(Collectors.toSet());
        assertTrue(ids.contains(BStatsChartIds.ADDONS_LOADED));
        assertTrue(ids.contains(BStatsChartIds.PLATFORM));
        assertTrue(ids.contains(BStatsChartIds.MSPT_P99_BY_PLUGIN_VERSION));
        String json = service.buildPayload();
        assertTrue(json.contains("{\"chartId\":\"lite_features_dropped\",\"data\":{\"value\":\"lite\"}}"), json);
    }

    @Test
    @DisplayName("KNOWN_ADDON_PLUGINS is closed")
    void knownAddonPluginsList_isImmutable() {
        assertFalse(BukkitBStatsHost.KNOWN_ADDON_PLUGINS.isEmpty());
        try {
            BukkitBStatsHost.KNOWN_ADDON_PLUGINS.add("BackdoorPlugin");
            org.junit.jupiter.api.Assertions.fail("KNOWN_ADDON_PLUGINS must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
