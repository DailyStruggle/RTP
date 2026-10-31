package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.base.RTPCmd;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RTPCmd guard and compute branch tests")
class RTPCmdBranchTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;
    private TestRTPCmd rtpCmd;

    private static class TestRTPCmd extends BaseRTPCmdImpl implements RTPCmd {
        public TestRTPCmd() {
            super(null);
        }

        @Override
        public boolean onCommand(UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            return onCommand(senderId, parameterValues, nextCommand, null);
        }

        @Override
        public boolean onCommand(UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand, java.util.function.Consumer<String> messageMethod) {
            if (nextCommand != null) return true;
            return compute(senderId, parameterValues, nextCommand, messageMethod);
        }

        @Override public String name() { return "rtp"; }
        @Override public String permission() { return "rtp.use"; }
        @Override public String description() { return "rtp command"; }
        @Override public void successEvent(RTPCommandSender sender, RTPPlayer player) {}
        @Override public void failEvent(RTPCommandSender sender, String msg) {}
    }

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        rtpCmd = new TestRTPCmd();
    }

    @AfterEach
    void tearDown() {
        RTPCmd.setRng(null);
        TeleportSessions.current().processingPlayers.clear();
        TeleportSessions.current().latestTeleportData.clear();
    }

    @Test
    void testPickOneAndRng() {
        Random deterministic = new Random(42);
        RTPCmd.setRng(deterministic);
        assertSame(deterministic, RTPCmd.rng());

        assertEquals("fallback", RTPCmd.pickOne(null, "fallback"));
        assertEquals("fallback", RTPCmd.pickOne(Collections.emptyList(), "fallback"));

        List<String> list = List.of("a", "b", "c");
        String picked = RTPCmd.pickOne(list, "fallback");
        assertTrue(list.contains(picked));
    }

    @Test
    void testOnCommandNoPerms() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "noPermUser", null) {
            @Override
            public boolean hasPermission(String permission) {
                return false;
            }
        };
        accessor.addPlayer(sender);

        boolean result = rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[0]);
        assertTrue(result);
        assertFalse(sender.sentMessages.isEmpty());
    }

    @Test
    void testOnCommandCooldownRejected() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "cooldownUser", null) {
            @Override
            public long cooldown() {
                return 60000L; // 60s cooldown
            }
        };
        accessor.addPlayer(sender);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.completed = true;
        TeleportSessions.current().latestTeleportData.put(sender.uuid(), data);

        boolean result = rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[0]);
        assertTrue(result);
        assertFalse(sender.sentMessages.isEmpty());
    }

    @Test
    void testOnCommandBareRtpUsesGlobalCooldownNotPreviousRegionCooldown() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "zeroCooldownUser", null) {
            @Override
            public long cooldown() {
                return 0L;
            }
        };
        accessor.addPlayer(sender);

        TeleportData data = new TeleportData();
        data.time = System.currentTimeMillis();
        data.completed = true;
        io.github.dailystruggle.rtp.common.selection.region.Region targetRegion =
                org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.selection.region.Region.class);
        io.github.dailystruggle.rtp.common.selection.region.RegionSettings settings =
                new io.github.dailystruggle.rtp.common.selection.region.RegionSettings(
                        "testRegion", null, null, null, false, false, 0, 0, 0, 0, 0.0, 0, null, false, 3600000L, null);
        org.mockito.Mockito.when(targetRegion.getSettings()).thenReturn(settings);
        data.targetRegion = targetRegion;
        TeleportSessions.current().latestTeleportData.put(sender.uuid(), data);

        boolean result = rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[0]);
        assertTrue(result);
        assertTrue(sender.sentMessages.stream().noneMatch(msg -> msg.toLowerCase(Locale.ROOT).contains("cooldown")));
    }

    private static Region putOverrideRegion(String name, boolean requirePermission, long cooldownMillis) {
        // Explicit shape/vert: Region's null shape/vert self-heal rebuilds settings without the cooldown.
        RegionSettings settings = new RegionSettings(
                name, null,
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square(),
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new ArrayList<>()),
                false, requirePermission, 0, 0, 0, 0, 0.0, 0, null, false, cooldownMillis, null);
        Region region = new Region(name, settings);
        ActiveSelection.get().permRegionLookup.put(name, region);
        return region;
    }

    private static MockRTPPlayer cooldownSubject(String name, long globalCooldownMillis) {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), name, null);
        sender.setCooldown(globalCooldownMillis);
        sender.setPermission("rtp.nocooldown", false);
        sender.setPermission("rtp.noCooldown", false);
        return sender;
    }

    @Test
    @DisplayName("RTP-32: /rtp region:<r> early guard applies the requested region's cooldown override")
    void testOnCommandRequestedRegionCooldownOverrideRejectsEarly() {
        MockRTPPlayer sender = cooldownSubject("longRegionCooldownUser", 5000L);
        accessor.addPlayer(sender);
        Region longRegion = putOverrideRegion("rtp32Long", false, 120000L);
        try {
            TeleportData data = new TeleportData();
            data.time = System.currentTimeMillis() - 10000L;
            data.completed = true;
            data.targetRegion = longRegion;
            TeleportSessions.current().latestTeleportData.put(sender.uuid(), data);

            assertTrue(rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[]{"region:rtp32Long"}));

            String cooldownMsg = (String) ActiveConfigs.get().getConfigValue(
                    io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.cooldownMessage, "");
            assertTrue(sender.sentMessages.contains(cooldownMsg),
                    "10s after the last teleport, the requested region's 120s override must reject (global is 5s)");
            assertSame(data, TeleportSessions.current().latestTeleportData.get(sender.uuid()),
                    "the early guard must reject before compute() replaces the teleport data");
        } finally {
            ActiveSelection.get().permRegionLookup.remove("rtp32Long");
        }
    }

    @Test
    @DisplayName("RTP-32: bare /rtp early guard honors a shorter world-default region cooldown override")
    void testOnCommandBareRtpAllowsShorterWorldDefaultRegionCooldownOverride() {
        io.github.dailystruggle.rtp.common.mock.MockRTPWorld world =
                new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("rtp32ShortWorld");
        accessor.addWorld(world);
        Region shortRegion = putOverrideRegion("rtp32ShortWorldDefault", false, 5000L);
        Region previousDefault = ActiveSelection.get().permRegionLookup.get("default");
        ActiveSelection.get().permRegionLookup.put("default", shortRegion);
        try {
            MockRTPPlayer sender = new MockRTPPlayer(
                    UUID.randomUUID(),
                    "bareShortOverrideUser",
                    new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
            sender.setCooldown(60000L);
            sender.setPermission("rtp.nocooldown", false);
            sender.setPermission("rtp.noCooldown", false);
            accessor.addPlayer(sender);

            TeleportData data = new TeleportData();
            data.time = System.currentTimeMillis() - 10000L;
            data.completed = true;
            data.targetRegion = shortRegion;
            TeleportSessions.current().latestTeleportData.put(sender.uuid(), data);

            String cooldownMsg = (String) ActiveConfigs.get().getConfigValue(
                    io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.cooldownMessage, "");
            assertTrue(rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[0]));
            assertFalse(sender.sentMessages.contains(cooldownMsg),
                    "10s after last teleport, the world-default region's 5s override must allow bare /rtp despite 60s global cooldown");
        } finally {
            ActiveSelection.get().permRegionLookup.remove("rtp32ShortWorldDefault");
            if (previousDefault != null) {
                ActiveSelection.get().permRegionLookup.put("default", previousDefault);
            } else {
                ActiveSelection.get().permRegionLookup.remove("default");
            }
        }
    }

    @Test
    @DisplayName("RTP-32: requested region cooldown applies only to a permitted local region with an override")
    void testRequestedRegionWithOverrideResolution() {
        MockRTPPlayer sender = cooldownSubject("regionPermUser", 0L);
        Region open = putOverrideRegion("rtp32Open", false, 5000L);
        Region gated = putOverrideRegion("rtp32Gated", true, 5000L);
        putOverrideRegion("rtp32NoOverride", false, -1L);
        try {
            assertSame(open, RTPCmd.requestedRegionWithOverride(sender, new String[]{"region:rtp32Open"}));
            assertSame(open, RTPCmd.requestedRegionWithOverride(sender, new String[]{"REGION=rtp32Open"}));
            assertNull(RTPCmd.requestedRegionWithOverride(sender, new String[0]), "bare /rtp requests no region");
            assertNull(RTPCmd.requestedRegionWithOverride(sender, null));
            assertNull(RTPCmd.requestedRegionWithOverride(sender, new String[]{"world:rtp32Open"}));
            assertNull(RTPCmd.requestedRegionWithOverride(sender, new String[]{"region:rtp32NoOverride"}));
            assertNull(RTPCmd.requestedRegionWithOverride(sender, new String[]{"region:unknownRegion"}));
            assertNull(RTPCmd.requestedRegionWithOverride(sender, new String[]{"region:peer:rtp32Open"}));

            sender.setPermission("rtp.regions.rtp32Gated", false);
            sender.setPermission("rtp.regions.*", false);
            assertNull(RTPCmd.requestedRegionWithOverride(sender, new String[]{"region:rtp32Gated"}),
                    "a gated region the sender may not use must not set the cooldown");
            sender.setPermission("rtp.regions.rtp32Gated", true);
            assertSame(gated, RTPCmd.requestedRegionWithOverride(sender, new String[]{"region:rtp32Gated"}));

            sender.setPermission("rtp.region", false);
            assertNull(RTPCmd.requestedRegionWithOverride(sender, new String[]{"region:rtp32Open"}),
                    "without rtp.region the region parameter is not usable");
        } finally {
            ActiveSelection.get().permRegionLookup.remove("rtp32Open");
            ActiveSelection.get().permRegionLookup.remove("rtp32Gated");
            ActiveSelection.get().permRegionLookup.remove("rtp32NoOverride");
        }
    }

    private static TeleportData completedTeleport(Region target, long time) {
        TeleportData data = new TeleportData();
        data.time = time;
        data.completed = true;
        data.targetRegion = target;
        return data;
    }

    @Test
    @DisplayName("RTP-32: bare /rtp passes once the shorter of global and world-default region cooldown expires")
    void testCooldownRemainingUnrequestedRegionUsesShorter() {
        long now = System.currentTimeMillis();
        Region shortRegion = putOverrideRegion("rtp32Short", false, 5000L);
        Region longRegion = putOverrideRegion("rtp32LongDefault", false, 120000L);
        Region midRegion = putOverrideRegion("rtp32Mid", false, 30000L);
        try {
            MockRTPPlayer globalLong = cooldownSubject("globalLongUser", 60000L);
            assertEquals(0L, RTPCmd.cooldownRemaining(globalLong, globalLong.uuid(), shortRegion, false,
                    completedTeleport(shortRegion, now - 10000L), now),
                    "region 5s elapsed: global 60s must not hold bare /rtp");

            MockRTPPlayer globalShort = cooldownSubject("globalShortUser", 5000L);
            assertEquals(0L, RTPCmd.cooldownRemaining(globalShort, globalShort.uuid(), longRegion, false,
                    completedTeleport(longRegion, now - 10000L), now),
                    "global 5s elapsed: region 120s must not hold bare /rtp");

            assertEquals(20000L, RTPCmd.cooldownRemaining(globalLong, globalLong.uuid(), midRegion, false,
                    completedTeleport(midRegion, now - 10000L), now),
                    "neither elapsed: the shorter (30s region) remainder applies");
        } finally {
            ActiveSelection.get().permRegionLookup.remove("rtp32Short");
            ActiveSelection.get().permRegionLookup.remove("rtp32LongDefault");
            ActiveSelection.get().permRegionLookup.remove("rtp32Mid");
        }
    }

    @Test
    @DisplayName("RTP-32: a requested region uses its own cooldown override, longer or shorter than global")
    void testCooldownRemainingRequestedRegionUsesItsOverride() {
        long now = System.currentTimeMillis();
        Region shortRegion = putOverrideRegion("rtp32ReqShort", false, 5000L);
        Region longRegion = putOverrideRegion("rtp32ReqLong", false, 120000L);
        Region plain = putOverrideRegion("rtp32ReqPlain", false, -1L);
        try {
            MockRTPPlayer globalLong = cooldownSubject("reqGlobalLongUser", 60000L);
            assertEquals(0L, RTPCmd.cooldownRemaining(globalLong, globalLong.uuid(), shortRegion, true,
                    completedTeleport(shortRegion, now - 10000L), now),
                    "requested 5s region: global 60s must not apply");

            MockRTPPlayer globalShort = cooldownSubject("reqGlobalShortUser", 5000L);
            assertEquals(110000L, RTPCmd.cooldownRemaining(globalShort, globalShort.uuid(), longRegion, true,
                    completedTeleport(longRegion, now - 10000L), now),
                    "requested 120s region: an elapsed 5s global must not release it");

            assertEquals(50000L, RTPCmd.cooldownRemaining(globalLong, globalLong.uuid(), plain, true,
                    completedTeleport(plain, now - 10000L), now),
                    "a region without an override falls back to the global cooldown");
            assertEquals(0L, RTPCmd.cooldownRemaining(globalLong, globalLong.uuid(), null, false, null, now),
                    "no prior teleport: nothing to wait for");
        } finally {
            ActiveSelection.get().permRegionLookup.remove("rtp32ReqShort");
            ActiveSelection.get().permRegionLookup.remove("rtp32ReqLong");
            ActiveSelection.get().permRegionLookup.remove("rtp32ReqPlain");
        }
    }

    @Test
    @DisplayName("Cooldown guard ignores fresh in-flight teleport data when no prior teleport exists")
    void testCooldownRemainingIgnoresFreshPendingTeleport() {
        MockRTPPlayer sender = cooldownSubject("pendingTeleportUser", 5_000L);
        long now = System.currentTimeMillis();
        TeleportData pending = new TeleportData();
        pending.time = now;
        pending.completed = false;
        TeleportSessions.current().latestTeleportData.put(sender.uuid(), pending);

        assertEquals(0L, RTPCmd.cooldownRemaining(sender, sender.uuid(), null, false, null, now));
    }

    @Test
    @DisplayName("Cooldown guard still enforces shared last-teleport time without a local prior")
    void testCooldownRemainingUsesSharedTimestamp() {
        MockRTPPlayer sender = cooldownSubject("sharedCooldownUser", 5_000L);
        long now = System.currentTimeMillis();
        RTP rtp = RTP.getInstance();
        io.github.dailystruggle.rtp.common.network.RTPNetworkManager previous = rtp.networkManager;
        io.github.dailystruggle.rtp.common.network.RTPNetworkManager networkManager =
                org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.network.RTPNetworkManager.class);
        org.mockito.Mockito.when(networkManager.getLastTeleportTime(sender.uuid())).thenReturn(now - 1_000L);
        rtp.networkManager = networkManager;
        try {
            assertEquals(4_000L,
                    RTPCmd.cooldownRemaining(sender, sender.uuid(), null, false, null, now));
        } finally {
            rtp.networkManager = previous;
        }
    }

    @Test
    @DisplayName("REQ-RTP-NET-001: cooldown guard uses the newer shared timestamp over an older local prior")
    void cooldownGuardUsesNewerSharedTimestampThanLocalPrior() {
        MockRTPPlayer sender = cooldownSubject("sharedNewerCooldownUser", 60_000L);
        long now = System.currentTimeMillis();
        RTP rtp = RTP.getInstance();
        io.github.dailystruggle.rtp.common.network.RTPNetworkManager previous = rtp.networkManager;
        io.github.dailystruggle.rtp.common.network.RTPNetworkManager networkManager =
                org.mockito.Mockito.mock(io.github.dailystruggle.rtp.common.network.RTPNetworkManager.class);
        org.mockito.Mockito.when(networkManager.getLastTeleportTime(sender.uuid())).thenReturn(now - 1_000L);
        rtp.networkManager = networkManager;
        try {
            assertEquals(59_000L,
                    RTPCmd.cooldownRemaining(sender, sender.uuid(), null, false,
                            completedTeleport(null, now - 10_000L), now));
        } finally {
            rtp.networkManager = previous;
        }
    }

    @Test
    void testOnCommandAlreadyTeleporting() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "busyUser", null);
        accessor.addPlayer(sender);

        TeleportSessions.current().processingPlayers.add(sender.uuid());

        boolean result = rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[0]);
        assertTrue(result);
        assertFalse(sender.sentMessages.isEmpty());
    }

    @Test
    @DisplayName("REQ-RTP-S-004: per-target already-teleporting skip releases the sender's busy lock")
    void testComputeSelfAlreadyTeleportingSkipReleasesSenderLock() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "staleUser", null);
        accessor.addPlayer(sender);
        TeleportData stale = new TeleportData();
        stale.time = System.currentTimeMillis();
        stale.completed = false;
        TeleportSessions.current().latestTeleportData.put(sender.uuid(), stale);
        // Acquired by the outer onCommand before compute runs.
        TeleportSessions.current().processingPlayers.add(sender.uuid());

        assertTrue(rtpCmd.compute(sender.uuid(), new HashMap<>(), null, msg -> {}));

        assertFalse(TeleportSessions.current().processingPlayers.contains(sender.uuid()),
                "no pipeline was dispatched, so the sender must not stay 'already teleporting'");
    }

    @Test
    @DisplayName("REQ-RTP-S-004: rtp of another player releases the sender's lock even when the target is skipped")
    void testComputeCrossPlayerSkipReleasesSenderLock() {
        MockRTPPlayer admin = new MockRTPPlayer(UUID.randomUUID(), "adminUser", null);
        MockRTPPlayer target = new MockRTPPlayer(UUID.randomUUID(), "busyTarget", null);
        accessor.addPlayer(admin);
        accessor.addPlayer(target);
        TeleportData inFlight = new TeleportData();
        inFlight.time = System.currentTimeMillis();
        inFlight.completed = false;
        TeleportSessions.current().latestTeleportData.put(target.uuid(), inFlight);
        TeleportSessions.current().processingPlayers.add(admin.uuid());

        Map<String, List<String>> args = new HashMap<>();
        args.put("player", List.of("busyTarget"));
        assertTrue(rtpCmd.compute(admin.uuid(), args, null, msg -> {}));

        assertFalse(TeleportSessions.current().processingPlayers.contains(admin.uuid()),
                "the sender's lock is never owned by another player's pipeline");
    }

    @Test
    void testComputeConsoleSenderWithoutPlayerArgRejects() {
        UUID consoleId = new UUID(0, 0);
        List<String> messages = new ArrayList<>();
        boolean res = rtpCmd.compute(consoleId, Collections.emptyMap(), null, messages::add);
        assertTrue(res);
        assertFalse(messages.isEmpty());
    }

    @Test
    void testComputeNextCommandNotNullShortCircuits() {
        UUID senderId = UUID.randomUUID();
        CommandsAPICommand dummyNext = new BaseRTPCmdImpl(null) {
            @Override public String name() { return "sub"; }
            @Override public String permission() { return "rtp.sub"; }
            @Override public boolean onCommand(UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
                return true;
            }
        };
        assertTrue(rtpCmd.compute(senderId, Collections.emptyMap(), dummyNext, msg -> {}));
    }

    @Test
    @DisplayName("REQ-RTP-S-004: region economy rejection does not leave incomplete TeleportData stuck in latestTeleportData")
    void testRegionEconomyRejectionDoesNotLeavePlayerStuckAlreadyTeleporting() {
        io.github.dailystruggle.rtp.common.mock.MockRTPWorld world =
                new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("world");
        accessor.addWorld(world);
        MockRTPPlayer sender = new MockRTPPlayer(
                UUID.randomUUID(),
                "poorRegionUser",
                new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        sender.setPermission("rtp.free", false);
        sender.setPermission("rtp.region", true);
        accessor.addPlayer(sender);

        Region paidRegion = new Region("paidRegion", new RegionSettings(
                "paidRegion", world,
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square(),
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new ArrayList<>()),
                false, false, 0, 0, 0, 0, 250.0, 0, null, false, -1L, null));
        paidRegion.set(io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.price, 250.0);
        ActiveSelection.get().permRegionLookup.put("paidRegion", paidRegion);

        @SuppressWarnings("unchecked")
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys> eco =
                (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys>)
                        ActiveConfigs.get().getParser(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.class);
        EnumMap<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys, Object> savedEco = eco.getData();
        io.github.dailystruggle.rtp.api.economy.RTPEconomy prevEconomy =
                io.github.dailystruggle.rtp.common.runtime.RTPRuntime.economy();

        @SuppressWarnings("unchecked")
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages> msgParser =
                (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages>)
                        ActiveConfigs.get().getParser(io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.class);
        Object prevNotEnough = msgParser.getData(io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.notEnoughMoney);

        try {
            msgParser.set(io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.notEnoughMoney,
                    "[P0] you need at least $[money] for that.");
            EnumMap<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys, Object> mutated = new EnumMap<>(savedEco);
            mutated.put(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.price, 0.0);
            mutated.put(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.balanceFloor, 0.0);
            eco.setData(mutated);

            io.github.dailystruggle.rtp.common.runtime.RTPRuntime.setEconomy(new io.github.dailystruggle.rtp.api.economy.RTPEconomy() {
                @Override public void give(UUID playerId, double money) {}
                @Override public boolean take(UUID playerId, double money) { return false; }
                @Override public double bal(UUID playerId) { return 10.0; }
            });

            TeleportData priorCompleted = completedTeleport(paidRegion, System.currentTimeMillis() - 60000L);
            TeleportSessions.current().latestTeleportData.put(sender.uuid(), priorCompleted);

            Map<String, List<String>> args = new HashMap<>();
            args.put("region", List.of("paidRegion"));
            List<String> feedback = new ArrayList<>();
            assertTrue(rtpCmd.compute(sender.uuid(), args, null, feedback::add));

            assertFalse(feedback.isEmpty(), "Expected insufficient-funds feedback");
            assertTrue(feedback.get(0).contains("250.0"), "Expected region price 250.0 in message, got: " + feedback.get(0));
            assertSame(priorCompleted, TeleportSessions.current().latestTeleportData.get(sender.uuid()),
                    "Rejected region charge must preserve prior completed TeleportData rather than leaving an incomplete one");
        } finally {
            if (prevNotEnough != null) {
                msgParser.set(io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.notEnoughMoney, prevNotEnough);
            }
            eco.setData(savedEco);
            io.github.dailystruggle.rtp.common.runtime.RTPRuntime.setEconomy(prevEconomy);
            ActiveSelection.get().permRegionLookup.remove("paidRegion");
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-004: rtp.free sender is not blocked by balanceFloor when balance is below floor")
    void testFreePermissionBypassesBalanceFloorCheck() {
        io.github.dailystruggle.rtp.common.mock.MockRTPWorld world =
                new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("world");
        accessor.addWorld(world);
        Region previousDefault = ActiveSelection.get().permRegionLookup.get("default");
        Region defaultRegion = new Region("default", new RegionSettings(
                "default", world,
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square(),
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new ArrayList<>()),
                false, false, 0, 0, 0, 0, 0.0, 0, null, false, -1L, null));
        ActiveSelection.get().permRegionLookup.put("default", defaultRegion);
        MockRTPPlayer sender = new MockRTPPlayer(
                UUID.randomUUID(),
                "freeFloorUser",
                new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        sender.setPermission("rtp.free", true);
        accessor.addPlayer(sender);

        @SuppressWarnings("unchecked")
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys> eco =
                (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys>)
                        ActiveConfigs.get().getParser(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.class);
        EnumMap<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys, Object> savedEco = eco.getData();
        io.github.dailystruggle.rtp.api.economy.RTPEconomy prevEconomy =
                io.github.dailystruggle.rtp.common.runtime.RTPRuntime.economy();
        java.util.concurrent.atomic.AtomicInteger balCalls = new java.util.concurrent.atomic.AtomicInteger(0);

        try {
            EnumMap<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys, Object> mutated = new EnumMap<>(savedEco);
            mutated.put(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.price, 50.0);
            mutated.put(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.balanceFloor, 100.0);
            eco.setData(mutated);

            io.github.dailystruggle.rtp.common.runtime.RTPRuntime.setEconomy(new io.github.dailystruggle.rtp.api.economy.RTPEconomy() {
                @Override public void give(UUID playerId, double money) {}
                @Override public boolean take(UUID playerId, double money) { return true; }
                @Override public double bal(UUID playerId) {
                    balCalls.incrementAndGet();
                    return 0.0;
                }
            });

            List<String> feedback = new ArrayList<>();
            assertTrue(rtpCmd.compute(sender.uuid(), new HashMap<>(), null, feedback::add));
            assertEquals(0, balCalls.get(), "rtp.free sender must not query economy balance");
            assertTrue(feedback.isEmpty(), "rtp.free sender must not receive notEnoughMoney message");
        } finally {
            eco.setData(savedEco);
            io.github.dailystruggle.rtp.common.runtime.RTPRuntime.setEconomy(prevEconomy);
            if (previousDefault != null) {
                ActiveSelection.get().permRegionLookup.put("default", previousDefault);
            } else {
                ActiveSelection.get().permRegionLookup.remove("default");
            }
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-004: toggleTargetPerms target economy rejection refunds sender and leaves session state untouched")
    void testToggleTargetPermsTargetEconomyRejectionRefundsSender() {
        io.github.dailystruggle.rtp.common.mock.MockRTPWorld world =
                new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("world");
        accessor.addWorld(world);
        MockRTPPlayer sender = new MockRTPPlayer(
                UUID.randomUUID(),
                "richSender",
                new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        sender.setPermission("rtp.free", false);
        sender.setPermission("rtp.other", true);
        accessor.addPlayer(sender);

        MockRTPPlayer target = new MockRTPPlayer(
                UUID.randomUUID(),
                "brokeTarget",
                new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 10, 64, 10));
        target.setPermission("rtp.free", false);
        target.setPermission("rtp.notme", false);
        accessor.addPlayer(target);

        Region paidRegion = new Region("targetPaidRegion", new RegionSettings(
                "targetPaidRegion", world,
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square(),
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new ArrayList<>()),
                false, false, 0, 0, 0, 0, 100.0, 0, null, false, -1L, null));
        paidRegion.set(io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys.price, 100.0);
        ActiveSelection.get().permRegionLookup.put("targetPaidRegion", paidRegion);

        @SuppressWarnings("unchecked")
        io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys> eco =
                (io.github.dailystruggle.rtp.common.configuration.ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys>)
                        ActiveConfigs.get().getParser(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.class);
        EnumMap<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys, Object> savedEco = eco.getData();
        io.github.dailystruggle.rtp.api.economy.RTPEconomy prevEconomy =
                io.github.dailystruggle.rtp.common.runtime.RTPRuntime.economy();
        Map<UUID, Double> balances = new HashMap<>();
        balances.put(sender.uuid(), 500.0);
        balances.put(target.uuid(), 10.0);

        try {
            EnumMap<io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys, Object> mutated = new EnumMap<>(savedEco);
            mutated.put(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.price, 50.0);
            mutated.put(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.priceOther, 50.0);
            mutated.put(io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys.balanceFloor, 0.0);
            eco.setData(mutated);

            io.github.dailystruggle.rtp.common.runtime.RTPRuntime.setEconomy(new io.github.dailystruggle.rtp.api.economy.RTPEconomy() {
                @Override public void give(UUID playerId, double money) {
                    balances.merge(playerId, money, Double::sum);
                }
                @Override public boolean take(UUID playerId, double money) {
                    double cur = balances.getOrDefault(playerId, 0.0);
                    if (cur < money) return false;
                    balances.put(playerId, cur - money);
                    return true;
                }
                @Override public double bal(UUID playerId) {
                    return balances.getOrDefault(playerId, 0.0);
                }
            });

            Map<String, List<String>> args = new HashMap<>();
            args.put("player", List.of("brokeTarget"));
            args.put("region", List.of("targetPaidRegion"));
            args.put("toggletargetperms", List.of("true"));
            List<String> feedback = new ArrayList<>();
            assertTrue(rtpCmd.compute(sender.uuid(), args, null, feedback::add));

            assertEquals(500.0, balances.get(sender.uuid()), 1e-6,
                    "Sender charge must be refunded when target-pays charge fails");
            assertNull(TeleportSessions.current().latestTeleportData.get(target.uuid()),
                    "Target must not have a TeleportData entry committed when target charge fails");
        } finally {
            eco.setData(savedEco);
            io.github.dailystruggle.rtp.common.runtime.RTPRuntime.setEconomy(prevEconomy);
            ActiveSelection.get().permRegionLookup.remove("targetPaidRegion");
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-004: compute covers shape overrides, relative coords, biome filter, worldBorderOverride, and bad world rejection")
    void testShapeOverridesRelativeCoordsBiomeFilterAndWorldRejection() {
        io.github.dailystruggle.rtp.common.mock.MockRTPWorld world =
                new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("world");
        accessor.addWorld(world);
        Region previousDefault = ActiveSelection.get().permRegionLookup.get("default");
        Region defaultRegion = new Region("default", new RegionSettings(
                "default", world,
                new io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square(),
                new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(new ArrayList<>()),
                false, false, 0, 0, 0, 0, 0.0, 0, null, false, -1L, null));
        ActiveSelection.get().permRegionLookup.put("default", defaultRegion);

        MockRTPPlayer sender = new MockRTPPlayer(
                UUID.randomUUID(),
                "shapeOverridePlayer",
                new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 64, 64, -32));
        sender.setPermission("rtp.free", true);
        accessor.addPlayer(sender);

        try {
            // 1. Unknown world argument rejected with badArg feedback
            Map<String, List<String>> badWorldArgs = new HashMap<>();
            badWorldArgs.put("world", List.of("missing_world_xyz"));
            List<String> badWorldFeedback = new ArrayList<>();
            assertTrue(rtpCmd.compute(sender.uuid(), badWorldArgs, null, badWorldFeedback::add));
            assertFalse(badWorldFeedback.isEmpty());

            // 2. Shape overrides with relative coords (~, ~10, ~abc), booleans, explicit units, numbers, and biome filter
            Map<String, List<String>> shapeArgs = new HashMap<>();
            shapeArgs.put("region", List.of("default"));
            shapeArgs.put("shape", List.of("RECTANGLE"));
            shapeArgs.put("centerX", List.of("~10"));
            shapeArgs.put("centerZ", List.of("~"));
            shapeArgs.put("width", List.of("16c"));
            shapeArgs.put("length", List.of("32"));
            shapeArgs.put("weight", List.of("1.5"));
            shapeArgs.put("mode", List.of("ACCUMULATED"));
            shapeArgs.put("uniquePlacements", List.of("true"));
            shapeArgs.put("expand", List.of("false"));
            shapeArgs.put("biome", java.util.Arrays.asList("plains", "", null));
            shapeArgs.put("worldBorderOverride", List.of("true"));

            assertTrue(rtpCmd.compute(sender.uuid(), shapeArgs, null, msg -> {}));
            Region tempRegion = ActiveSelection.get().tempRegions.get(sender.uuid());
            assertNotNull(tempRegion);
            TeleportSessions.current().processingPlayers.remove(sender.uuid());
            TeleportSessions.current().latestTeleportData.remove(sender.uuid());

            // 3. Invalid relative coordinate offset (~abc) falls back to base coordinate
            Map<String, List<String>> invalidRelArgs = new HashMap<>();
            invalidRelArgs.put("region", List.of("default"));
            invalidRelArgs.put("shape", List.of("SQUARE"));
            invalidRelArgs.put("centerX", List.of("~abc"));
            invalidRelArgs.put("centerZ", List.of("~+"));
            invalidRelArgs.put("radius", List.of("2.5c"));
            assertTrue(rtpCmd.compute(sender.uuid(), invalidRelArgs, null, msg -> {}));
        } finally {
            TeleportSessions.current().processingPlayers.remove(sender.uuid());
            TeleportSessions.current().latestTeleportData.remove(sender.uuid());
            ActiveSelection.get().tempRegions.remove(sender.uuid());
            if (previousDefault != null) {
                ActiveSelection.get().permRegionLookup.put("default", previousDefault);
            } else {
                ActiveSelection.get().permRegionLookup.remove("default");
            }
        }
    }
}
