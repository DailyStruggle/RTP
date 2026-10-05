package io.github.dailystruggle.bstats.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Root-field vocabulary matches upstream bstats-bukkit 3.x {@code appendPlatformData}. */
class BStatsPlatformTest {

    @Test
    @DisplayName("Bukkit: playerAmount, onlineMode, bukkitVersion, bukkitName")
    void bukkit() {
        Map<String, Object> m = BStatsPlatform.BUKKIT.rootFields(ServerInfo.of(5, "Paper", "1.21.4").withOnlineMode(false));
        assertEquals(List.of("playerAmount", "onlineMode", "bukkitVersion", "bukkitName"), List.copyOf(m.keySet()));
        assertEquals(5, m.get("playerAmount"));
        assertEquals(0, m.get("onlineMode"));
        assertEquals("1.21.4", m.get("bukkitVersion"));
        assertEquals("Paper", m.get("bukkitName"));
        assertEquals("bukkit", BStatsPlatform.BUKKIT.endpoint());
        assertEquals(BStatsConfig.Format.YAML, BStatsPlatform.BUKKIT.defaultConfigFormat());
    }

    @Test
    @DisplayName("Online mode true maps to 1")
    void onlineModeTrue() {
        Map<String, Object> m = BStatsPlatform.BUKKIT.rootFields(ServerInfo.of(1, "Paper", "v").withOnlineMode(true));
        assertEquals(1, m.get("onlineMode"));
    }

    @Test
    @DisplayName("Unknown facts are omitted from the payload; negative players clamp to 0")
    void unknownsOmitted() {
        ServerInfo sparse = ServerInfo.of(-1, "Fabric", null);
        Map<String, Object> m = BStatsPlatform.BUKKIT.rootFields(sparse);
        assertEquals(0, m.get("playerAmount"));
        assertNull(m.get("onlineMode"));
        StringBuilder sb = new StringBuilder();
        BStatsJson.appendFields(sb, m);
        assertEquals("\"playerAmount\":0,\"bukkitName\":\"Fabric\"", sb.toString());
        assertTrue(BStatsPlatform.BUKKIT.rootFields(null).isEmpty());
    }

    @Test
    @DisplayName("ServerInfo wither copies every field")
    void serverInfoWithers() {
        ServerInfo s = ServerInfo.of(1, "n", "v").withOnlineMode(true);
        assertEquals(new ServerInfo(1, true, "n", "v"), s);
    }

    @Test
    @DisplayName("BStatsLog adapters never throw")
    void logAdapters() {
        assertDoesNotThrow(() -> BStatsLog.NONE.log(Level.SEVERE, "x"));
        Logger logger = Logger.getLogger("bstats-api-test");
        logger.setUseParentHandlers(false);
        assertDoesNotThrow(() -> BStatsLog.of(logger).log(Level.INFO, "x"));
    }
}
