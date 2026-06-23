package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
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
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class InvalidCommandTest {
    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;
    private TestReloadCmd reloadCmd;

    private static class TestReloadCmd extends io.github.dailystruggle.rtp.common.commands.reload.ReloadCmd {
        public int msgBadParameterCount = 0;

        public TestReloadCmd(io.github.dailystruggle.commandsapi.common.CommandsAPICommand parent) {
            super(parent);
        }

        @Override
        public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {
            msgBadParameterCount++;
            super.msgBadParameter(callerId, parameterName, parameterValue);
        }
    }

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
        @Override public String description() { return "rtp"; }
        @Override public void successEvent(RTPCommandSender sender, RTPPlayer player) {}
        @Override public void failEvent(RTPCommandSender sender, String msg) {}
    }

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        // Using ReloadCmd as a concrete TreeCommand to test routing
        reloadCmd = new TestReloadCmd(null);

        // Set some default messages in the mock config
        java.util.Map<Enum<?>, Object> data = new java.util.HashMap<>();
        data.put(PlayerMessages.busy, "busy");
        data.put(PlayerMessages.consoleCmdNotAllowed, "You must be an in-game player");
        data.put(PlayerMessages.invalidCommand, "invalid command - [arg]");
        data.forEach((k, v) -> {
            Object fv = io.github.dailystruggle.rtp.common.RTP.configs.getParser((Class) k.getDeclaringClass());
            if (fv instanceof io.github.dailystruggle.rtp.common.configuration.ConfigParser) {
                ((io.github.dailystruggle.rtp.common.configuration.ConfigParser) fv).set((Enum) k, v);
            }
        });
    }

    @Test
    void rtpReloadInvalid_providesConfiguredFeedback() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);

        // Pre-heat language parser with some defaults to ensure MockRTPServerAccessor picks them up
        accessor.sendMessage(sender.uuid(), PlayerMessages.invalidCommand, "frobnicate");
        sender.sentMessages.clear();

        // Create a custom RTP root command to use its onCommand logic
        TestRTPCmd rtpCmd = new TestRTPCmd();
        rtpCmd.getCommandLookup().put(reloadCmd.name().toUpperCase(), reloadCmd);

        // Simulation: rtp reload frobnicate
        String[] args = new String[]{"reload", "frobnicate"};

        // Use the entry point that provides wrapping and configurability
        rtpCmd.onCommand(sender, rtpCmd, "rtp", args);

        io.github.dailystruggle.commandsapi.common.CommandsAPI.execute();

        // Since reload subcommands might involve async parsing, we wait briefly for the feedback to arrive
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {}

        // Check if the message matches the configured one in messages.yml (or its default in the test setup)
        // Default in messages.yml: "&c[P0] invalid command - [arg]"
        boolean foundFeedback = sender.sentMessages.stream()
                .anyMatch(m -> m.contains("invalid command") || m.contains("frobnicate"));

        assertTrue(foundFeedback, "No configured feedback sent to player. Sent: " + sender.sentMessages);
    }

    @Test
    void rtpReloadBusy_providesConfiguredFeedback() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);

        // Ensure messages are loaded
        accessor.sendMessage(sender.uuid(), PlayerMessages.busy, "");
        sender.sentMessages.clear();

        io.github.dailystruggle.rtp.common.RTP.reloading.set(true);
        try {
            TestRTPCmd rtpCmd = new TestRTPCmd();

            String[] args = new String[]{"reload"};

            // Use the entry point that provides wrapping and configurability
            rtpCmd.onCommand(sender, rtpCmd, "rtp", args);

            io.github.dailystruggle.commandsapi.common.CommandsAPI.execute();

            // Default in messages.yml: "&c[P0] busy"
            boolean foundFeedback = sender.sentMessages.stream()
                    .anyMatch(m -> m.contains("busy"));

            assertTrue(foundFeedback, "No 'busy' feedback sent to player. Sent: " + sender.sentMessages);
        } finally {
            io.github.dailystruggle.rtp.common.RTP.reloading.set(false);
        }
    }
    @Test
    void consoleInvalidSubcommand_providesConfiguredFeedback() {
        MockRTPPlayer console = new MockRTPPlayer(new UUID(0, 0), "CONSOLE", null);
        accessor.addPlayer(console);

        // Pre-heat language parser
        accessor.sendMessage(console.uuid(), PlayerMessages.invalidCommand, "frobnicate");
        console.sentMessages.clear();

        TestRTPCmd rtpCmd = new TestRTPCmd();

        // Add a parameter to mimic the real RTP command
        rtpCmd.addParameter("player", new io.github.dailystruggle.rtp.common.commands.parameters.RegionParameter(
                "rtp.other", "test", (uuid, s) -> false
        ));

        // Simulation: rtp frobnicate
        String[] args = new String[]{"frobnicate"};

        rtpCmd.onCommand(console, rtpCmd, "rtp", args);

        io.github.dailystruggle.commandsapi.common.CommandsAPI.execute();

        boolean foundInvalidFeedback = console.sentMessages.stream()
                .anyMatch(m -> m.contains("invalid command") || m.contains("frobnicate"));
        boolean foundConsoleFeedback = console.sentMessages.stream()
                .anyMatch(m -> m.contains("You must be an in-game player"));

        assertTrue(foundInvalidFeedback, "No 'invalid command' feedback sent to console. Sent: " + console.sentMessages);
        assertTrue(!foundConsoleFeedback, "Console should NOT receive 'in-game player' message for invalid subcommand. Sent: " + console.sentMessages);
    }

    @Test
    void delimitedUnknownParameter_providesBadParameterFeedback() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);

        // Pre-heat language parser
        ((io.github.dailystruggle.rtp.common.configuration.ConfigParser) io.github.dailystruggle.rtp.common.RTP.configs.getParser(PlayerMessages.class)).set(PlayerMessages.badArg, "bad parameter - [arg]");

        sender.sentMessages.clear();

        TestRTPCmd rtpCmd = new TestRTPCmd();

        // Simulation: rtp unknown=val
        String[] args = new String[]{"unknown=val"};

        rtpCmd.onCommand(sender, rtpCmd, "rtp", args);

        io.github.dailystruggle.commandsapi.common.CommandsAPI.execute();

        boolean foundBadParameterFeedback = sender.sentMessages.stream()
                .anyMatch(m -> m.contains("bad parameter") && m.contains("unknown=val"));

        assertTrue(foundBadParameterFeedback, "No 'bad parameter' feedback sent for unknown delimited key. Sent: " + sender.sentMessages);
    }

    @Test
    void positionalUnknown_providesInvalidCommandFeedback() {
        MockRTPPlayer sender = new MockRTPPlayer(UUID.randomUUID(), "test", null);
        accessor.addPlayer(sender);

        sender.sentMessages.clear();

        TestRTPCmd rtpCmd = new TestRTPCmd();

        // Simulation: rtp unknown
        String[] args = new String[]{"unknown"};

        rtpCmd.onCommand(sender, rtpCmd, "rtp", args);

        io.github.dailystruggle.commandsapi.common.CommandsAPI.execute();

        boolean foundInvalidFeedback = sender.sentMessages.stream()
                .anyMatch(m -> m.contains("invalid command") && m.contains("unknown"));

        assertTrue(foundInvalidFeedback, "No 'invalid command' feedback sent for unknown positional arg. Sent: " + sender.sentMessages);
    }
}
