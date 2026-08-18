package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.info.InfoCmd;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test: invoking a subcommand (e.g. {@code rtp info}) must not emit the
 * {@link PlayerMessages#alreadyTeleporting} message, even when the sender is already
 * registered in {@code RTP.processingPlayers}. Subcommands are administrative / query
 * operations and are not themselves teleports.
 */
public class SubcommandBypassesTeleportGuardsTest {
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
    }

    @Test
    void rtpInfo_doesNotEmitAlreadyTeleporting_whenPlayerIsProcessing() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);

        // Simulate an in-flight teleport: sender is in processingPlayers.
        RTP.getInstance().processingPlayers.add(sender.uuid());

        TestRTPCmd rtpCmd = new TestRTPCmd();
        rtpCmd.addSubCommand(new InfoCmd(rtpCmd));

        sender.sentMessages.clear();

        // Simulation: `/rtp info`
        rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[]{"info"});

        boolean alreadyTeleportingFired = sender.sentMessages.stream()
                .anyMatch(m -> m.contains("already teleporting"));
        assertFalse(alreadyTeleportingFired,
                "`rtp info` must not emit alreadyTeleporting. Sent: " + sender.sentMessages);
    }

    @Test
    void rtpHelp_doesNotEmitAlreadyTeleporting_whenPlayerIsProcessing() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);

        // Simulate an in-flight teleport: sender is in processingPlayers.
        RTP.getInstance().processingPlayers.add(sender.uuid());

        TestRTPCmd rtpCmd = new TestRTPCmd();
        rtpCmd.addSubCommand(new InfoCmd(rtpCmd));

        sender.sentMessages.clear();

        // Simulation: `/rtp help` (built-in TreeCommand verb, not a registered subcommand).
        rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[]{"help"});

        boolean alreadyTeleportingFired = sender.sentMessages.stream()
                .anyMatch(m -> m.contains("already teleporting"));
        assertFalse(alreadyTeleportingFired,
                "`rtp help` must not emit alreadyTeleporting. Sent: " + sender.sentMessages);
    }

    @Test
    void rtpRoot_stillEmitsAlreadyTeleporting_whenPlayerIsProcessing() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);

        RTP.getInstance().processingPlayers.add(sender.uuid());

        TestRTPCmd rtpCmd = new TestRTPCmd();
        rtpCmd.addSubCommand(new InfoCmd(rtpCmd));

        sender.sentMessages.clear();

        // Root command (no subcommand arg) - the guard must still fire.
        rtpCmd.onCommand(sender, rtpCmd, "rtp", new String[0]);

        boolean alreadyTeleportingFired = sender.sentMessages.stream()
                .anyMatch(m -> m.contains("already teleporting"));
        assertTrue(alreadyTeleportingFired,
                "root `rtp` must still emit alreadyTeleporting when already processing. Sent: "
                        + sender.sentMessages);
    }
}
