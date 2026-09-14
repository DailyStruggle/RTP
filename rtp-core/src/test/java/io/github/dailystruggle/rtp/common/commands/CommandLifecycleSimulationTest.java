package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Command Lifecycle Simulation Test")
public class CommandLifecycleSimulationTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        RTP.selectionAPI = new SelectionAPI();
    }

    @Test
    @DisplayName("registerCommands binds aliases and executeCommand dispatches through mock pipeline")
    void testCommandRegistrationAndExecutionLifecycle() {
        CoreRtpRoot root = new CoreRtpRoot();
        accessor.registerCommands(root, "rtp", "wild");

        assertTrue(accessor.getRegisteredCommands().containsKey("rtp"), "Registered commands must contain 'rtp'");
        assertTrue(accessor.getRegisteredCommands().containsKey("wild"), "Registered commands must contain 'wild'");
        assertSame(root, accessor.getRegisteredCommands().get("rtp"));
        assertSame(root, accessor.getRegisteredCommands().get("wild"));

        UUID playerId = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(playerId, "testPlayer", null);
        accessor.addPlayer(player);

        boolean executed = accessor.executeCommand(player.uuid(), "rtp info");
        assertTrue(executed, "executeCommand should return true for registered command");

        assertFalse(player.sentMessages.isEmpty(), "Player should receive reply messages from 'rtp info'");
    }

    @Test
    @DisplayName("executeCommand on wildcard alias executes the same root command")
    void testWildcardAliasExecution() {
        CoreRtpRoot root = new CoreRtpRoot();
        accessor.registerCommands(root, "rtp", "wild");

        UUID playerId = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(playerId, "wildPlayer", null);
        accessor.addPlayer(player);

        boolean executed = accessor.executeCommand(player.uuid(), "wild info");
        assertTrue(executed, "executeCommand should return true for 'wild' alias");

        assertFalse(player.sentMessages.isEmpty(), "Player should receive reply messages from 'wild info'");
    }

    @Test
    @DisplayName("executeCommand returns false for unregistered command or invalid arguments")
    void testNegativeExecutionCases() {
        CoreRtpRoot root = new CoreRtpRoot();
        accessor.registerCommands(root, "rtp");

        UUID playerId = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(playerId, "testPlayer", null);
        accessor.addPlayer(player);

        assertFalse(accessor.executeCommand(player.uuid(), "unknowncmd foo"), "Unregistered command should return false");
        assertFalse(accessor.executeCommand(player.uuid(), ""), "Empty command line should return false");
        assertFalse(accessor.executeCommand(player.uuid(), null), "Null command line should return false");
        assertFalse(accessor.executeCommand(null, "rtp info"), "Null senderId should return false");
    }
}
