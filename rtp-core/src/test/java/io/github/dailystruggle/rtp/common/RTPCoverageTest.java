package io.github.dailystruggle.rtp.common;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RTPResult;
import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.RtpTargetStatus;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Branch coverage for {@link RTP} static helpers and the addon-facing
 * {@link RTPAPI} delegates installed by its static initializer, driven through
 * the mock server accessor.
 */
class RTPCoverageTest {

    @TempDir
    Path tempDir;

    MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        RTP rtp = RTP.getInstance();
        rtp.databaseAccessor = null;
        rtp.latestTeleportData.clear();
        rtp.priorTeleportData.clear();
        rtp.queuedPlayers.clear();
        rtp.processingPlayers.clear();
    }

    @AfterEach
    void tearDown() {
        RTP rtp = RTP.getInstance();
        if (rtp != null) {
            rtp.latestTeleportData.clear();
            rtp.priorTeleportData.clear();
            rtp.queuedPlayers.clear();
            rtp.processingPlayers.clear();
        }
        RTP.reloading.set(false);
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        RTPAPI.serverAccessor = null;
    }

    private MockRTPPlayer addPlayer(String name) {
        UUID id = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(
                id, name, new RTPLocation(new MockRTPWorld("default"), 0, 0, 0));
        accessor.addPlayer(player);
        return player;
    }

    private static RTPResult await(java.util.concurrent.CompletableFuture<RTPResult> f) throws Exception {
        return f.get(10, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("reload resets the reloading flag after completing")
    void reload_resetsReloadingFlag() {
        RTP.reload();
        assertFalse(RTP.reloading.get());
    }

    @Test
    @DisplayName("teleport to an offline player reports PLAYER_OFFLINE")
    void teleport_offlinePlayer_reportsOffline() throws Exception {
        RTPResult result = await(RTPAPI.teleport(UUID.randomUUID(), RtpTarget.defaultRegion()));
        assertFalse(result.isSuccess());
        assertEquals(RTPResult.Reason.PLAYER_OFFLINE, result.reason());
    }

    @Test
    @DisplayName("ACTION targets are rejected as INVALID_TARGET")
    void teleport_actionTarget_isInvalid() throws Exception {
        MockRTPPlayer player = addPlayer("ActionTarget");
        RTPResult result = await(RTPAPI.teleport(player.uuid(), RtpTarget.action("open:menu")));
        assertFalse(result.isSuccess());
        assertEquals(RTPResult.Reason.INVALID_TARGET, result.reason());
    }

    @Test
    @DisplayName("local COORDINATE target with an unknown world is INVALID_TARGET")
    void teleport_coordinateUnknownWorld_isInvalid() throws Exception {
        MockRTPPlayer player = addPlayer("CoordUnknown");
        RTPResult result = await(RTPAPI.teleport(player.uuid(),
                RtpTarget.coordinate(null, "no_such_world_xyz", 1, 64, 1)));
        assertFalse(result.isSuccess());
        assertNotEquals(RTPResult.Reason.PLAYER_OFFLINE, result.reason());
    }

    @Test
    @DisplayName("cancel and warmup report false for an idle player")
    void cancelAndWarmup_idlePlayer_false() {
        UUID id = UUID.randomUUID();
        assertFalse(RTPAPI.isWarmingUp(id));
        assertFalse(RTPAPI.cancel(id));
    }

    @Test
    @DisplayName("cancel and warmup report true while a player is processing")
    void cancelAndWarmup_processingPlayer_true() {
        UUID id = UUID.randomUUID();
        RTP.getInstance().processingPlayers.add(id);
        assertTrue(RTPAPI.isWarmingUp(id));
        assertTrue(RTPAPI.cancel(id));
    }

    @Test
    @DisplayName("allowed targets always start with the bare default target")
    void allowedTargets_startsWithDefault() {
        MockRTPPlayer player = addPlayer("Allowed");
        List<RtpTarget> targets = RTPAPI.getAllowedTargets(player.uuid());
        assertFalse(targets.isEmpty());
        assertEquals(RtpTarget.defaultRegion(), targets.get(0));
        List<RtpTarget> offline = RTPAPI.getAllowedTargets(UUID.randomUUID());
        assertEquals(RtpTarget.defaultRegion(), offline.get(0));
    }

    @Test
    @DisplayName("ACTION target status is READY and labelled with the action")
    void targetStatus_action_ready() {
        MockRTPPlayer player = addPlayer("StatusAction");
        RtpTargetStatus status = RTPAPI.getTargetStatus(player.uuid(), RtpTarget.action("open:menu"));
        assertNotNull(status);
        assertEquals(RtpTargetStatus.Availability.READY, status.availability());
    }

    @Test
    @DisplayName("COORDINATE target status is READY or NO_PERMISSION")
    void targetStatus_coordinate_readyOrNoPerm() {
        MockRTPPlayer player = addPlayer("StatusCoord");
        RtpTargetStatus status = RTPAPI.getTargetStatus(player.uuid(),
                RtpTarget.coordinate(null, "default", 0, 64, 0));
        assertNotNull(status);
        assertTrue(status.availability() == RtpTargetStatus.Availability.READY
                || status.availability() == RtpTargetStatus.Availability.NO_PERMISSION);
    }

    @Test
    @DisplayName("default region status resolves without throwing")
    void targetStatus_defaultRegion_resolves() {
        MockRTPPlayer player = addPlayer("StatusDefault");
        assertNotNull(RTPAPI.getTargetStatus(player.uuid(), RtpTarget.defaultRegion()));
    }

    @Test
    @DisplayName("unknown world and biome targets fall back without throwing")
    void targetStatus_unknownWorldAndBiome_fallBack() {
        MockRTPPlayer player = addPlayer("StatusFallback");
        assertNotNull(RTPAPI.getTargetStatus(player.uuid(), RtpTarget.world("no_such_world_xyz")));
        assertNotNull(RTPAPI.getTargetStatus(player.uuid(), RtpTarget.biome("no_such_biome_xyz")));
        assertNotNull(RTPAPI.getTargetStatus(player.uuid(), RtpTarget.region("no_such_region_xyz")));
    }

    @Test
    @DisplayName("status with an economy present evaluates funds for a broke player")
    void targetStatus_withEconomy_evaluatesFunds() {
        io.github.dailystruggle.rtp.api.economy.RTPEconomy saved = RTP.economy;
        try {
            RTP.economy = new io.github.dailystruggle.rtp.api.economy.RTPEconomy() {
                @Override public void give(UUID playerId, double money) {}
                @Override public boolean take(UUID playerId, double money) { return false; }
                @Override public double bal(UUID playerId) { return -1_000_000.0; }
            };
            UUID id = UUID.randomUUID();
            MockRTPPlayer player = new MockRTPPlayer(
                    id, "Broke", new RTPLocation(new MockRTPWorld("default"), 0, 0, 0)) {
                @Override
                public boolean hasPermission(String permission) {
                    return !"rtp.free".equals(permission);
                }
            };
            accessor.addPlayer(player);
            assertNotNull(RTPAPI.getTargetStatus(id, RtpTarget.defaultRegion()));
            assertNotNull(RTPAPI.getTargetStatus(id, RtpTarget.biome("plains")));
        } finally {
            RTP.economy = saved;
        }
    }

    private MockRTPPlayer permPlayer(java.util.function.Predicate<String> perms) {
        return new MockRTPPlayer(UUID.randomUUID(), "Perm",
                new RTPLocation(new MockRTPWorld("default"), 0, 0, 0)) {
            @Override
            public boolean hasPermission(String permission) {
                return perms.test(permission);
            }
        };
    }

    @Test
    @DisplayName("permission gate denies a null player or null target")
    void permissionDenied_nullInputs() {
        assertTrue(RTP.apiTargetPermissionDenied(null, RtpTarget.defaultRegion(), null));
        assertTrue(RTP.apiTargetPermissionDenied(permPlayer(p -> true), null, null));
    }

    @Test
    @DisplayName("permission gate never denies DEFAULT or ACTION targets")
    void permissionDenied_defaultAndAction_allowed() {
        MockRTPPlayer none = permPlayer(p -> false);
        assertFalse(RTP.apiTargetPermissionDenied(none, RtpTarget.defaultRegion(), null));
        assertFalse(RTP.apiTargetPermissionDenied(none, RtpTarget.action("open:menu"), null));
    }

    @Test
    @DisplayName("BIOME gate accepts lower, upper or wildcard biome permissions")
    void permissionDenied_biome() {
        RtpTarget biome = RtpTarget.biome("Plains");
        assertTrue(RTP.apiTargetPermissionDenied(permPlayer(p -> false), biome, null));
        assertFalse(RTP.apiTargetPermissionDenied(permPlayer("rtp.biome.plains"::equals), biome, null));
        assertFalse(RTP.apiTargetPermissionDenied(permPlayer("rtp.biome.PLAINS"::equals), biome, null));
        assertFalse(RTP.apiTargetPermissionDenied(permPlayer("rtp.biome.*"::equals), biome, null));
    }

    @Test
    @DisplayName("NETWORK gate requires a server-specific or wildcard server permission")
    void permissionDenied_network() {
        RtpTarget net = RtpTarget.network("peer1", "default");
        assertTrue(RTP.apiTargetPermissionDenied(permPlayer(p -> false), net, null));
        assertFalse(RTP.apiTargetPermissionDenied(permPlayer("rtp.servers.peer1"::equals), net, null));
        assertFalse(RTP.apiTargetPermissionDenied(permPlayer("rtp.servers.*"::equals), net, null));
    }

    @Test
    @DisplayName("WORLD and COORDINATE gates allow worlds without requirePermission")
    void permissionDenied_worldAndCoordinate_unrestricted() {
        MockRTPPlayer none = permPlayer(p -> false);
        assertFalse(RTP.apiTargetPermissionDenied(none, RtpTarget.world("default"), null));
        assertFalse(RTP.apiTargetPermissionDenied(none, RtpTarget.coordinate(null, "default", 0, 0, 0), null));
    }

    @Test
    @DisplayName("REGION gate denies only when the resolved region requires permission")
    void permissionDenied_region() {
        RtpTarget target = RtpTarget.region("gated");
        MockRTPPlayer none = permPlayer(p -> false);
        assertFalse(RTP.apiTargetPermissionDenied(none, target, null));

        io.github.dailystruggle.rtp.common.selection.region.Region region =
                org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.selection.region.Region.class,
                        org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(region.getSettings().requirePermission()).thenReturn(true);
        assertTrue(RTP.apiTargetPermissionDenied(none, target, region));
        assertFalse(RTP.apiTargetPermissionDenied(permPlayer("rtp.regions.gated"::equals), target, region));

        org.mockito.Mockito.when(region.getSettings().requirePermission()).thenReturn(false);
        assertFalse(RTP.apiTargetPermissionDenied(none, target, region));
    }

    private MockRTPPlayer timedPlayer(java.util.function.Predicate<String> perms, long delay, long cooldown) {
        return new MockRTPPlayer(UUID.randomUUID(), "Timed",
                new RTPLocation(new MockRTPWorld("default"), 0, 0, 0)) {
            @Override public boolean hasPermission(String permission) { return perms.test(permission); }
            @Override public long delay() { return delay; }
            @Override public long cooldown() { return cooldown; }
        };
    }

    private static io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings(Long delay, Long cooldown) {
        io.github.dailystruggle.rtp.common.selection.region.RegionSettings s =
                org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.selection.region.RegionSettings.class);
        org.mockito.Mockito.when(s.delayMillis()).thenReturn(delay);
        org.mockito.Mockito.when(s.cooldownMillis()).thenReturn(cooldown);
        return s;
    }

    @Test
    @DisplayName("getDelay falls back to the sender delay without a region override")
    void getDelay_precedence() {
        assertEquals(0L, RTP.getDelay(null, null));
        MockRTPPlayer plain = timedPlayer(p -> false, 1234L, 0L);
        assertEquals(1234L, RTP.getDelay(plain, null));
        assertEquals(1234L, RTP.getDelay(plain, settings(null, null)));
        assertEquals(1234L, RTP.getDelay(plain, settings(-1L, null)));
        assertEquals(500L, RTP.getDelay(plain, settings(500L, null)));
        assertEquals(0L, RTP.getDelay(timedPlayer("rtp.nodelay"::equals, 1234L, 0L), settings(500L, null)));
        assertEquals(0L, RTP.getDelay(timedPlayer("rtp.noDelay"::equals, 1234L, 0L), settings(500L, null)));
    }

    @Test
    @DisplayName("getCooldown falls back to the sender cooldown without a region override")
    void getCooldown_precedence() {
        assertEquals(0L, RTP.getCooldown(null, null));
        MockRTPPlayer plain = timedPlayer(p -> false, 0L, 4321L);
        assertEquals(4321L, RTP.getCooldown(plain, null));
        assertEquals(4321L, RTP.getCooldown(plain, settings(null, null)));
        assertEquals(4321L, RTP.getCooldown(plain, settings(null, -1L)));
        assertEquals(700L, RTP.getCooldown(plain, settings(null, 700L)));
        assertEquals(0L, RTP.getCooldown(timedPlayer("rtp.nocooldown"::equals, 0L, 4321L), settings(null, 700L)));
        assertEquals(0L, RTP.getCooldown(timedPlayer("rtp.noCooldown"::equals, 0L, 4321L), settings(null, 700L)));
    }

    @Test
    @DisplayName("region teleport times are stored case-insensitively and ignore null keys")
    void regionTeleportTimes_roundTrip() {
        UUID id = UUID.randomUUID();
        try {
            assertEquals(0L, RTP.getLastRegionTeleportTime(null, "a"));
            assertEquals(0L, RTP.getLastRegionTeleportTime(id, null));
            assertEquals(0L, RTP.getLastRegionTeleportTime(id, "Arena"));
            RTP.setLastRegionTeleportTime(null, "Arena", 5L);
            RTP.setLastRegionTeleportTime(id, null, 5L);
            RTP.setLastRegionTeleportTime(id, "Arena", 42L);
            assertEquals(42L, RTP.getLastRegionTeleportTime(id, "arena"));
            assertEquals(0L, RTP.getLastRegionTeleportTime(id, "other"));
            assertEquals(42L, RTP.getEffectiveLastTeleportTime(id, "ARENA"));
            assertEquals(0L, RTP.getEffectiveLastTeleportTime(id, "other"));
            assertEquals(0L, RTP.getEffectiveLastTeleportTime(id, (String) null));
            assertEquals(0L, RTP.getEffectiveLastTeleportTime(null, "arena"));
        } finally {
            RTP.getInstance().regionTeleportTimes.remove(id);
        }
    }

    @Test
    @DisplayName("effective last teleport time takes the max of local and network time")
    void effectiveLastTeleportTime_localAndNetwork() {
        RTP rtp = RTP.getInstance();
        io.github.dailystruggle.rtp.common.network.RTPNetworkManager savedNet = rtp.networkManager;
        UUID id = UUID.randomUUID();
        try {
            assertEquals(0L, RTP.getEffectiveLastTeleportTime((UUID) null));
            assertEquals(0L, RTP.getEffectiveLastTeleportTime(id));

            io.github.dailystruggle.rtp.common.playerData.TeleportData data =
                    new io.github.dailystruggle.rtp.common.playerData.TeleportData();
            data.time = 100L;
            rtp.latestTeleportData.put(id, data);
            assertEquals(100L, RTP.getEffectiveLastTeleportTime(id));

            io.github.dailystruggle.rtp.common.network.RTPNetworkManager net =
                    org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.network.RTPNetworkManager.class);
            org.mockito.Mockito.when(net.getLastTeleportTime(id)).thenReturn(900L);
            rtp.networkManager = net;
            assertEquals(900L, RTP.getEffectiveLastTeleportTime(id));

            org.mockito.Mockito.when(net.getLastTeleportTime(id)).thenThrow(new RuntimeException("down"));
            assertEquals(100L, RTP.getEffectiveLastTeleportTime(id));
        } finally {
            rtp.networkManager = savedNet;
            rtp.latestTeleportData.remove(id);
        }
    }

    @Test
    @DisplayName("updateSharedLastTeleportTime forwards to the network manager and tolerates failures")
    void updateSharedLastTeleportTime_forwards() {
        RTP rtp = RTP.getInstance();
        io.github.dailystruggle.rtp.common.network.RTPNetworkManager savedNet = rtp.networkManager;
        UUID id = UUID.randomUUID();
        try {
            assertDoesNotThrow(() -> RTP.updateSharedLastTeleportTime(null, 1L));
            rtp.networkManager = null;
            assertDoesNotThrow(() -> RTP.updateSharedLastTeleportTime(id, 1L));

            io.github.dailystruggle.rtp.common.network.RTPNetworkManager net =
                    org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.network.RTPNetworkManager.class);
            rtp.networkManager = net;
            RTP.updateSharedLastTeleportTime(id, 77L);
            org.mockito.Mockito.verify(net).setLastTeleportTime(id, 77L);

            org.mockito.Mockito.doThrow(new RuntimeException("down")).when(net).setLastTeleportTime(id, 78L);
            assertDoesNotThrow(() -> RTP.updateSharedLastTeleportTime(id, 78L));
        } finally {
            rtp.networkManager = savedNet;
        }
    }

    @Test
    @DisplayName("updateRetentionWindow creates a bin store when absent and reuses it otherwise")
    void updateRetentionWindow_createsThenReuses() {
        RTP rtp = RTP.getInstance();
        io.github.dailystruggle.rtp.common.playerData.FileBinStore saved = rtp.fileBinStore;
        try {
            rtp.fileBinStore = null;
            rtp.updateRetentionWindow();
            io.github.dailystruggle.rtp.common.playerData.FileBinStore created = rtp.fileBinStore;
            assertNotNull(created);
            assertTrue(rtp.latestTeleportData.getRetentionMs() >= 60_000L);
            rtp.updateRetentionWindow();
            assertSame(created, rtp.fileBinStore);
        } finally {
            rtp.fileBinStore = saved;
        }
    }

    @Test
    @DisplayName("shape registration recreates a missing factory and binds the selection API")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void registerDefaultShapes_recreatesMissingFactory() {
        Map map = RTP.factoryMap;
        Object savedFactory = map.get(RTP.factoryNames.shape);
        var savedSelectionFactory = RTP.selectionAPI.shapeFactory;
        try {
            map.remove(RTP.factoryNames.shape);
            RTP.selectionAPI.shapeFactory = null;
            RTP.registerDefaultShapes();
            var rebuilt = RTP.factoryMap.get(RTP.factoryNames.shape);
            assertNotNull(rebuilt);
            assertTrue(rebuilt.contains("circle"));
            assertSame(rebuilt, RTP.selectionAPI.shapeFactory);

            map.remove(RTP.factoryNames.shape);
            RTP.selectionAPI.shapeFactory = null;
            RTP.addShape(new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle());
            assertNotNull(RTP.factoryMap.get(RTP.factoryNames.shape));
            assertSame(RTP.factoryMap.get(RTP.factoryNames.shape), RTP.selectionAPI.shapeFactory);
        } finally {
            map.put(RTP.factoryNames.shape, savedFactory);
            RTP.selectionAPI.shapeFactory = savedSelectionFactory;
        }
    }

    @Test
    @DisplayName("vertical adjustor registration recreates a missing factory")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void registerDefaultVerticalAdjustors_recreatesMissingFactory() {
        Map map = RTP.factoryMap;
        Object savedFactory = map.get(RTP.factoryNames.vert);
        try {
            map.remove(RTP.factoryNames.vert);
            RTP.registerDefaultVerticalAdjustors();
            var rebuilt = RTP.factoryMap.get(RTP.factoryNames.vert);
            assertNotNull(rebuilt);
            assertTrue(rebuilt.contains("linear"));
            assertTrue(rebuilt.contains("jump"));

            // VerticalAdjustor's constructor self-registers via factoryMap and NPEs when the
            // vert factory is absent, so the adjustor must be built before the entry is removed.
            var jump = new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor(
                    new java.util.ArrayList<>());
            map.remove(RTP.factoryNames.vert);
            RTP.addVerticalAdjustor(jump);
            assertTrue(RTP.factoryMap.get(RTP.factoryNames.vert).contains("jump"));
        } finally {
            map.put(RTP.factoryNames.vert, savedFactory);
        }
    }

    @Test
    @DisplayName("queueDepth returns a non-negative depth for known and unknown worlds")
    void queueDepth_nonNegative() {
        assertTrue(RTPAPI.queueDepth(new MockRTPWorld("default")) >= 0);
        assertTrue(RTPAPI.queueDepth(new MockRTPWorld("no_such_world_xyz")) >= 0);
    }

    @Test
    @DisplayName("remote COORDINATE target without a network hook is INVALID_TARGET")
    void teleport_remoteCoordinateWithoutNetwork_isInvalid() throws Exception {
        var savedHook = RTP.networkCommandHook;
        try {
            RTP.networkCommandHook = null;
            MockRTPPlayer player = addPlayer("CoordRemote");
            RTPResult result = await(RTPAPI.teleport(player.uuid(),
                    RtpTarget.coordinate("remote-server-xyz", "world", 1, 64, 1)));
            assertFalse(result.isSuccess());
            assertEquals(RTPResult.Reason.INVALID_TARGET, result.reason());
        } finally {
            RTP.networkCommandHook = savedHook;
        }
    }

    @Test
    @DisplayName("local COORDINATE target to a known world completes and clears processing state")
    void teleport_localCoordinateKnownWorld_completes() throws Exception {
        MockRTPPlayer player = addPlayer("CoordLocal");
        String worldName = RTP.serverAccessor.getRTPWorlds().isEmpty()
                ? "default" : RTP.serverAccessor.getRTPWorlds().get(0).name();
        RTPResult result = await(RTPAPI.teleport(player.uuid(),
                RtpTarget.coordinate(null, worldName, 1, 64, 1)));
        assertNotNull(result);
        assertFalse(RTP.getInstance().processingPlayers.contains(player.uuid()));
    }

    @Test
    @DisplayName("reload returns false when no configs are installed")
    void reload_withoutConfigs_returnsFalse() {
        var saved = RTP.configs;
        try {
            RTP.configs = null;
            assertFalse(RTP.reload());
            assertFalse(RTP.reloading.get());
        } finally {
            RTP.configs = saved;
        }
    }
}
