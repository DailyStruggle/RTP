package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.info.InfoCmd;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-API-F-006 — Bare-command root action.
 *
 * <p>Verifies the {@code RTPHooks.rootAction()} single-binding SPI: a bound action
 * handles a bare {@code /rtp} (replacing the classic teleport and bypassing the
 * teleport guards), an action that defers ({@code return false}) or that is unbound
 * falls through to the classic teleport, a thrown action does not suppress the
 * classic teleport, and subcommands are never routed through the action.
 */
@DisplayName("REQ-API-F-006: pluggable bare-/rtp root action")
public class ReqApiF006RootActionTest {
    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;

    private static class TestRTPCmd extends BaseRTPCmdImpl implements RTPCmd {
        public TestRTPCmd() { super(null); }

        @Override
        public boolean onCommand(UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            return onCommand(senderId, parameterValues, nextCommand, null);
        }

        @Override
        public boolean onCommand(UUID senderId, Map<String, List<String>> parameterValues,
                                 CommandsAPICommand nextCommand, java.util.function.Consumer<String> messageMethod) {
            if (nextCommand != null) return true;
            return compute(senderId, parameterValues, nextCommand, messageMethod);
        }

        @Override public String name() { return "rtp"; }
        @Override public String permission() { return "rtp.use"; }
        @Override public String description() { return "rtp"; }
        @Override public void successEvent(RTPCommandSender sender, RTPPlayer player) {}
        @Override public void failEvent(RTPCommandSender sender, String msg) {}
    }

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);

        ConfigParser<MessagesKeys> lang =
                (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        EnumMap<MessagesKeys, Object> data = new EnumMap<>(MessagesKeys.class);
        data.put(MessagesKeys.alreadyTeleporting, "you're already teleporting!");
        data.put(MessagesKeys.cooldownMessage, "cooldown active");
        lang.setData(data);

        RTP.getInstance().processingPlayers.clear();
        RTPAPI.hooks().rootAction().clear();
    }

    @AfterEach
    void tearDown() {
        RTPAPI.hooks().rootAction().clear();
    }

    @Test
    @DisplayName("bound action handles bare /rtp and bypasses the teleport guards")
    void boundAction_handlesBareRtp_andBypassesGuards() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);
        // Simulate an in-flight teleport so the classic path would emit alreadyTeleporting.
        RTP.getInstance().processingPlayers.add(sender.uuid());

        AtomicInteger invocations = new AtomicInteger();
        RTPAPI.hooks().rootAction().bind((player, feedback) -> {
            invocations.incrementAndGet();
            feedback.accept("menu opened");
            return true;
        });

        TestRTPCmd rtpCmd = new TestRTPCmd();
        sender.sentMessages.clear();
        rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[0]);

        assertEquals(1, invocations.get(), "bound action must run for a bare /rtp");
        assertTrue(sender.sentMessages.stream().anyMatch(m -> m.contains("menu opened")),
                "action feedback should reach the player. Sent: " + sender.sentMessages);
        assertFalse(sender.sentMessages.stream().anyMatch(m -> m.contains("already teleporting")),
                "a handled action must bypass teleport guards. Sent: " + sender.sentMessages);
    }

    @Test
    @DisplayName("action returning false falls through to the classic teleport")
    void actionReturningFalse_fallsThroughToClassic() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);
        RTP.getInstance().processingPlayers.add(sender.uuid());

        RTPAPI.hooks().rootAction().bind((player, feedback) -> false);

        TestRTPCmd rtpCmd = new TestRTPCmd();
        sender.sentMessages.clear();
        rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[0]);

        assertTrue(sender.sentMessages.stream().anyMatch(m -> m.contains("already teleporting")),
                "a deferring action must fall through to classic teleport. Sent: " + sender.sentMessages);
    }

    @Test
    @DisplayName("a thrown action does not suppress the classic teleport (S-004)")
    void thrownAction_fallsThroughToClassic() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);
        RTP.getInstance().processingPlayers.add(sender.uuid());

        RTPAPI.hooks().rootAction().bind((player, feedback) -> {
            throw new RuntimeException("boom");
        });

        TestRTPCmd rtpCmd = new TestRTPCmd();
        sender.sentMessages.clear();
        rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[0]);

        assertTrue(sender.sentMessages.stream().anyMatch(m -> m.contains("already teleporting")),
                "a thrown action must fall through to classic teleport. Sent: " + sender.sentMessages);
    }

    @Test
    @DisplayName("subcommands are never routed through the bound action")
    void subcommand_doesNotInvokeAction() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);

        AtomicBoolean invoked = new AtomicBoolean(false);
        RTPAPI.hooks().rootAction().bind((player, feedback) -> {
            invoked.set(true);
            return true;
        });

        TestRTPCmd rtpCmd = new TestRTPCmd();
        rtpCmd.addSubCommand(new InfoCmd(rtpCmd));
        sender.sentMessages.clear();

        rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[]{"info"});

        assertFalse(invoked.get(), "subcommands must not be routed through the root action");
    }

    @Test
    @DisplayName("explicit teleport parameters skip the bound action (parameterised /rtp)")
    void parameterisedRtp_doesNotInvokeAction() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);

        AtomicBoolean invoked = new AtomicBoolean(false);
        RTPAPI.hooks().rootAction().bind((player, feedback) -> {
            invoked.set(true);
            return true;
        });

        TestRTPCmd rtpCmd = new TestRTPCmd();
        sender.sentMessages.clear();

        // A non-subcommand argument (a teleport parameter such as region=foo) is an
        // explicit destination, so the GUI/menu override must be skipped.
        rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[]{"region=foo"});

        assertFalse(invoked.get(),
                "explicit teleport parameters must not be routed through the root action");
    }

    @Test
    @DisplayName("unbound (cleared) root action performs the classic teleport")
    void unboundAction_classicBehavior() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);
        RTP.getInstance().processingPlayers.add(sender.uuid());

        // No bind() call; registry current() is null.
        TestRTPCmd rtpCmd = new TestRTPCmd();
        sender.sentMessages.clear();
        rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[0]);

        assertTrue(sender.sentMessages.stream().anyMatch(m -> m.contains("already teleporting")),
                "with no action bound, a bare /rtp must perform the classic teleport. Sent: "
                        + sender.sentMessages);
    }
}
