package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.admin.ClearCooldownCmd;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("Region Cooldown and Warmup Delay Tests")
class RegionCooldownAndDelayTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        if (RTP.getInstance() != null) {
            RTP.getInstance().regionTeleportTimes.clear();
            RTP.getInstance().latestTeleportData.clear();
        }
    }

    @AfterEach
    void tearDown() {
        if (RTP.getInstance() != null) {
            RTP.getInstance().regionTeleportTimes.clear();
            RTP.getInstance().latestTeleportData.clear();
        }
    }

    @Test
    @DisplayName("parseDurationSetting parses units, raw numbers, and sentinels")
    void testParseDurationSetting() {
        assertEquals(300_000L, RegionConfigLoader.parseDurationSetting("5m"));
        assertEquals(3_000L, RegionConfigLoader.parseDurationSetting("3s"));
        assertEquals(1_500L, RegionConfigLoader.parseDurationSetting("1500ms"));
        assertEquals(300_000L, RegionConfigLoader.parseDurationSetting("300"));
        assertEquals(300_000L, RegionConfigLoader.parseDurationSetting(300));
        assertEquals(0L, RegionConfigLoader.parseDurationSetting(0));
        assertEquals(0L, RegionConfigLoader.parseDurationSetting("0s"));
        assertEquals(-1L, RegionConfigLoader.parseDurationSetting("-1"));
        assertEquals(-1L, RegionConfigLoader.parseDurationSetting("infinite"));
        assertEquals(-1L, RegionConfigLoader.parseDurationSetting("permanent"));
        assertNull(RegionConfigLoader.parseDurationSetting(null));
        assertNull(RegionConfigLoader.parseDurationSetting(""));
    }

    @Test
    @DisplayName("RegionConfigLoader loads cooldown and delay from ConfigParser")
    void testRegionConfigLoaderLoadsCooldownAndDelay() {
        ConfigParser<RegionKeys> parser = mock(ConfigParser.class);
        parser.name = "nether.yml";

        doReturn(null).when(parser).getConfigValue(eq(RegionKeys.world), any());
        doReturn(null).when(parser).getConfigValue(eq(RegionKeys.shape), any());
        doReturn(null).when(parser).getConfigValue(eq(RegionKeys.vert), any());
        doReturn(false).when(parser).getConfigValue(eq(RegionKeys.worldBorderOverride), any());
        doReturn(false).when(parser).getConfigValue(eq(RegionKeys.requirePermission), any());
        doReturn(10L).when(parser).getConfigValue(eq(RegionKeys.cacheCap), any());
        doReturn(0L).when(parser).getConfigValue(eq(RegionKeys.backlogCacheCap), any());
        doReturn(0L).when(parser).getConfigValue(eq(RegionKeys.networkReserveSize), any());
        doReturn(3).when(parser).getConfigValue(eq(RegionKeys.activeChunkCap), any());
        doReturn(0.0).when(parser).getConfigValue(eq(RegionKeys.price), any());
        doReturn(1L).when(parser).getConfigValue(eq(RegionKeys.spatialResolution), any());
        doReturn("default").when(parser).getConfigValue(eq(RegionKeys.override), any());

        doReturn("10m").when(parser).getConfigValue(eq(RegionKeys.cooldown), any());
        doReturn("5s").when(parser).getConfigValue(eq(RegionKeys.delay), any());

        RegionSettings settings = RegionConfigLoader.load(parser);
        assertNotNull(settings);
        assertEquals(600_000L, settings.cooldownMillis());
        assertEquals(5_000L, settings.delayMillis());
    }

    @Test
    @DisplayName("Cooldown precedence: bypass -> perm override -> region setting -> global default")
    void testCooldownPrecedence() {
        RegionSettings regionSettings = new RegionSettings(
                "customRegion", null, null, null, false, false,
                10, 0, 0, 3, 0.0, 1, "default", false,
                300_000L, 5_000L
        );

        // 1. Bypass permission (rtp.nocooldown) -> 0
        MockRTPPlayer bypassPlayer = new MockRTPPlayer(UUID.randomUUID(), "bypassUser", null) {
            @Override
            public boolean hasPermission(String perm) {
                return perm.equalsIgnoreCase("rtp.nocooldown");
            }
            @Override
            public long cooldown() {
                return 60_000L;
            }
        };
        assertEquals(0L, RTP.getCooldown(bypassPlayer, regionSettings));

        // 2. Permission override (rtp.cooldown.15) -> 15s = 15_000ms
        MockRTPPlayer permOverridePlayer = new MockRTPPlayer(UUID.randomUUID(), "overrideUser", null) {
            @Override
            public Set<String> getEffectivePermissions() {
                return Set.of("rtp.cooldown.15");
            }
            @Override
            public boolean hasPermission(String perm) {
                return perm.equalsIgnoreCase("rtp.cooldown.15");
            }
            @Override
            public long cooldown() {
                return 60_000L;
            }
        };
        assertEquals(15_000L, RTP.getCooldown(permOverridePlayer, regionSettings));

        // 3. Normal player without overrides uses region setting (300_000ms)
        MockRTPPlayer normalPlayer = new MockRTPPlayer(UUID.randomUUID(), "normalUser", null) {
            @Override
            public boolean hasPermission(String perm) {
                return false;
            }
            @Override
            public long cooldown() {
                return 60_000L;
            }
        };
        assertEquals(300_000L, RTP.getCooldown(normalPlayer, regionSettings));

        // 4. When region setting is null or -1, falls back to sender's global cooldown (60_000ms)
        RegionSettings fallbackSettings = new RegionSettings(
                "fallbackRegion", null, null, null, false, false,
                10, 0, 0, 3, 0.0, 1, "default", false,
                null, null
        );
        assertEquals(60_000L, RTP.getCooldown(normalPlayer, fallbackSettings));
    }

    @Test
    @DisplayName("Delay precedence: bypass -> perm override -> region setting -> global default")
    void testDelayPrecedence() {
        RegionSettings regionSettings = new RegionSettings(
                "customRegion", null, null, null, false, false,
                10, 0, 0, 3, 0.0, 1, "default", false,
                300_000L, 5_000L
        );

        // 1. Bypass permission (rtp.nodelay) -> 0
        MockRTPPlayer bypassPlayer = new MockRTPPlayer(UUID.randomUUID(), "bypassUser", null) {
            @Override
            public boolean hasPermission(String perm) {
                return perm.equalsIgnoreCase("rtp.nodelay");
            }
            @Override
            public long delay() {
                return 2_000L;
            }
        };
        assertEquals(0L, RTP.getDelay(bypassPlayer, regionSettings));

        // 2. Permission override (rtp.delay.8) -> 8s = 8_000ms
        MockRTPPlayer permOverridePlayer = new MockRTPPlayer(UUID.randomUUID(), "overrideUser", null) {
            @Override
            public Set<String> getEffectivePermissions() {
                return Set.of("rtp.delay.8");
            }
            @Override
            public boolean hasPermission(String perm) {
                return perm.equalsIgnoreCase("rtp.delay.8");
            }
            @Override
            public long delay() {
                return 2_000L;
            }
        };
        assertEquals(8_000L, RTP.getDelay(permOverridePlayer, regionSettings));

        // 3. Normal player without overrides uses region setting (5_000ms)
        MockRTPPlayer normalPlayer = new MockRTPPlayer(UUID.randomUUID(), "normalUser", null) {
            @Override
            public boolean hasPermission(String perm) {
                return false;
            }
            @Override
            public long delay() {
                return 2_000L;
            }
        };
        assertEquals(5_000L, RTP.getDelay(normalPlayer, regionSettings));

        // 4. When region setting is null, falls back to sender's global delay (2_000ms)
        RegionSettings fallbackSettings = new RegionSettings(
                "fallbackRegion", null, null, null, false, false,
                10, 0, 0, 3, 0.0, 1, "default", false,
                null, null
        );
        assertEquals(2_000L, RTP.getDelay(normalPlayer, fallbackSettings));
    }

    @Test
    @DisplayName("Independent per-region cooldown tracking per player")
    void testIndependentPerRegionCooldownTracking() {
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();
        long now = System.currentTimeMillis();

        assertEquals(0L, RTP.getLastRegionTeleportTime(playerA, "overworld"));
        assertEquals(0L, RTP.getLastRegionTeleportTime(playerA, "nether"));

        // Record teleport to overworld for playerA
        RTP.setLastRegionTeleportTime(playerA, "overworld", now);

        assertEquals(now, RTP.getLastRegionTeleportTime(playerA, "overworld"));
        assertEquals(0L, RTP.getLastRegionTeleportTime(playerA, "nether"));

        // playerB still has no recorded time for overworld
        assertEquals(0L, RTP.getLastRegionTeleportTime(playerB, "overworld"));

        // Record teleport to nether for playerA at later time
        long later = now + 5000L;
        RTP.setLastRegionTeleportTime(playerA, "nether", later);

        assertEquals(now, RTP.getLastRegionTeleportTime(playerA, "overworld"));
        assertEquals(later, RTP.getLastRegionTeleportTime(playerA, "nether"));

        // Case-insensitivity check
        assertEquals(now, RTP.getLastRegionTeleportTime(playerA, "OVERWORLD"));
        assertEquals(later, RTP.getLastRegionTeleportTime(playerA, "NeThEr"));
    }

    @Test
    @DisplayName("Default practice: regions without explicit cooldown inherit global timestamp and duration")
    void testDefaultPracticeGlobalCooldown() {
        UUID playerId = UUID.randomUUID();
        long now = System.currentTimeMillis();

        // Overworld has no regional override (null)
        RegionSettings defaultRegion = new RegionSettings(
                "overworld", null, null, null, false, false,
                10, 0, 0, 3, 0.0, 1, "default", false,
                null, null
        );

        // Nether has explicit regional override of 10m (600_000ms)
        RegionSettings overriddenRegion = new RegionSettings(
                "nether", null, null, null, false, false,
                10, 0, 0, 3, 0.0, 1, "default", false,
                600_000L, 5_000L
        );

        MockRTPPlayer player = new MockRTPPlayer(playerId, "testPlayer", null) {
            @Override
            public boolean hasPermission(String perm) {
                return false;
            }
            @Override
            public long cooldown() {
                return 60_000L; // 1m global cooldown
            }
        };

        // Simulate a global teleport 10 seconds ago
        io.github.dailystruggle.rtp.common.playerData.TeleportData tpData = new io.github.dailystruggle.rtp.common.playerData.TeleportData();
        tpData.time = now - 10_000L;
        tpData.completed = true;
        RTP.getInstance().latestTeleportData.put(playerId, tpData);

        // Region without override checks against global last teleport: 10s < 60s -> on cooldown
        long globalCooldown = RTP.getCooldown(player, defaultRegion);
        assertEquals(60_000L, globalCooldown);
        long lastTpGlobal = RTP.getEffectiveLastTeleportTime(playerId);
        assertEquals(now - 10_000L, lastTpGlobal);
        long remaining = Math.max(0L, globalCooldown - (now - lastTpGlobal));
        assertTrue(remaining > 0L);

        // Overridden region has never been visited: regional timestamp is 0 -> ready!
        long regionalCooldown = RTP.getCooldown(player, overriddenRegion);
        assertEquals(600_000L, regionalCooldown);
        long lastTpRegion = RTP.getLastRegionTeleportTime(playerId, "nether");
        assertEquals(0L, lastTpRegion);
    }

    @Test
    @DisplayName("ClearCooldownCmd clears both global and per-region cooldown records")
    void testClearCooldownCmdClearsRegionTimestamps() {
        UUID playerId = UUID.randomUUID();
        long now = System.currentTimeMillis();

        RTP.setLastRegionTeleportTime(playerId, "overworld", now);
        RTP.setLastRegionTeleportTime(playerId, "nether", now);

        assertEquals(now, RTP.getLastRegionTeleportTime(playerId, "overworld"));

        ClearCooldownCmd clearCmd = new ClearCooldownCmd(null);
        boolean result = clearCmd.onCommand(playerId, Collections.emptyMap(), null);
        assertTrue(result);

        assertEquals(0L, RTP.getLastRegionTeleportTime(playerId, "overworld"));
        assertEquals(0L, RTP.getLastRegionTeleportTime(playerId, "nether"));
    }
}
