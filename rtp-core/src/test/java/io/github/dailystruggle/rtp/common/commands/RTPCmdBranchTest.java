package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.base.RTPCmd;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
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
        RTP.getInstance().processingPlayers.clear();
        RTP.getInstance().latestTeleportData.clear();
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
        RTP.getInstance().latestTeleportData.put(sender.uuid(), data);

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
        RTP.getInstance().latestTeleportData.put(sender.uuid(), data);

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
        RTP.selectionAPI.permRegionLookup.put(name, region);
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
            RTP.getInstance().latestTeleportData.put(sender.uuid(), data);

            assertTrue(rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[]{"region:rtp32Long"}));

            String cooldownMsg = (String) RTP.configs.getConfigValue(
                    io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages.cooldownMessage, "");
            assertTrue(sender.sentMessages.contains(cooldownMsg),
                    "10s after the last teleport, the requested region's 120s override must reject (global is 5s)");
            assertSame(data, RTP.getInstance().latestTeleportData.get(sender.uuid()),
                    "the early guard must reject before compute() replaces the teleport data");
        } finally {
            RTP.selectionAPI.permRegionLookup.remove("rtp32Long");
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
            RTP.selectionAPI.permRegionLookup.remove("rtp32Open");
            RTP.selectionAPI.permRegionLookup.remove("rtp32Gated");
            RTP.selectionAPI.permRegionLookup.remove("rtp32NoOverride");
        }
    }

    @Test
    void testOnCommandAlreadyTeleporting() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "busyUser", null);
        accessor.addPlayer(sender);

        RTP.getInstance().processingPlayers.add(sender.uuid());

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
        RTP.getInstance().latestTeleportData.put(sender.uuid(), stale);
        // Acquired by the outer onCommand before compute runs.
        RTP.getInstance().processingPlayers.add(sender.uuid());

        assertTrue(rtpCmd.compute(sender.uuid(), new HashMap<>(), null, msg -> {}));

        assertFalse(RTP.getInstance().processingPlayers.contains(sender.uuid()),
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
        RTP.getInstance().latestTeleportData.put(target.uuid(), inFlight);
        RTP.getInstance().processingPlayers.add(admin.uuid());

        Map<String, List<String>> args = new HashMap<>();
        args.put("player", List.of("busyTarget"));
        assertTrue(rtpCmd.compute(admin.uuid(), args, null, msg -> {}));

        assertFalse(RTP.getInstance().processingPlayers.contains(admin.uuid()),
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
}
