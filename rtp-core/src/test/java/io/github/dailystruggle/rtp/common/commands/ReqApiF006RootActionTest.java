package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.info.InfoCmd;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-API-F-006: Bare-command root action.
 * Verifies {@code RTPHooks.rootAction()} SPI binding, fall-through on unhandled/exception,
 * and exclusion of subcommands.
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

        java.util.Map<Enum<?>, Object> data = new java.util.HashMap<>();
        data.put(PlayerMessages.alreadyTeleporting, "you're already teleporting!");
        data.put(PlayerMessages.cooldownMessage, "cooldown active");
        data.forEach((k, v) -> {
            Object fv = RTP.configs.getParser((Class) k.getDeclaringClass());
            if (fv instanceof io.github.dailystruggle.rtp.common.configuration.ConfigParser) {
                ((io.github.dailystruggle.rtp.common.configuration.ConfigParser) fv).set((Enum) k, v);
            }
        });

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
    @DisplayName("compute() path (Fabric/NeoForge) opens the bound action for a bare /rtp")
    void computePath_invokesBoundAction_forBareRtp() {
        // Fabric (RTPCmdFabricRoot) and NeoForge dispatch a bare /rtp straight
        // through compute(...), not the Bukkit String[]-args onCommand. Before the
        // fix, the rootAction hook lived only in the String[] path, so the GUI never
        // opened on those platforms. This exercises compute() directly.
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);
        RTP.getInstance().processingPlayers.add(sender.uuid());

        AtomicInteger invocations = new AtomicInteger();
        RTPAPI.hooks().rootAction().bind((player, feedback) -> {
            invocations.incrementAndGet();
            feedback.accept("menu opened");
            return true;
        });

        TestRTPCmd rtpCmd = new TestRTPCmd();
        sender.sentMessages.clear();
        boolean handled = rtpCmd.compute(sender.uuid(),
                new java.util.HashMap<>(), null, sender::sendMessage);

        assertTrue(handled, "compute() must report the bare /rtp as handled by the bound action");
        assertEquals(1, invocations.get(), "bound action must run for a bare /rtp via compute()");
        assertTrue(sender.sentMessages.stream().anyMatch(m -> m.contains("menu opened")),
                "action feedback should reach the player. Sent: " + sender.sentMessages);
        assertFalse(RTP.getInstance().processingPlayers.contains(sender.uuid()),
                "a handled action must release the processingPlayers lock");
    }

    @Test
    @DisplayName("compute() path skips the bound action for parameterised /rtp")
    void computePath_skipsBoundAction_forParameterisedRtp() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);

        AtomicBoolean invoked = new AtomicBoolean(false);
        RTPAPI.hooks().rootAction().bind((player, feedback) -> {
            invoked.set(true);
            return true;
        });

        TestRTPCmd rtpCmd = new TestRTPCmd();
        Map<String, List<String>> args = new java.util.HashMap<>();
        args.put("region", List.of("foo"));
        rtpCmd.compute(sender.uuid(), args, null, sender::sendMessage);

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
