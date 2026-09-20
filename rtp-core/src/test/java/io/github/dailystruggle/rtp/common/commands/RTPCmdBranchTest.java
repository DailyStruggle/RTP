package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
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
    void testOnCommandAlreadyTeleporting() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "busyUser", null);
        accessor.addPlayer(sender);

        RTP.getInstance().processingPlayers.add(sender.uuid());

        boolean result = rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[0]);
        assertTrue(result);
        assertFalse(sender.sentMessages.isEmpty());
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
