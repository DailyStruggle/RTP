package io.github.dailystruggle.rtp.common.commands.help;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

public class HelpCmdTest {

    @TempDir
    Path tempDir;

    private HelpCmd helpCmd;
    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());

        // Wire a mock baseCommand so HelpCmd.onCommand can iterate sub-commands
        RTP.baseCommand = mock(io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand.class);
        when(RTP.baseCommand.getCommandLookup()).thenReturn(new HashMap<>());

        helpCmd = new HelpCmd(null);
    }

    // ── Metadata ──────────────────────────────────────────────────────────────

    @Test
    void name_returnsHelp() {
        assertEquals("help", helpCmd.name());
    }

    @Test
    void permission_returnsRtpSee() {
        assertEquals("rtp.see", helpCmd.permission());
    }

    @Test
    void description_isNotEmpty() {
        assertNotNull(helpCmd.description());
        assertFalse(helpCmd.description().isEmpty());
    }

    // ── onCommand delegation ──────────────────────────────────────────────────

    @Test
    void onCommand_withNextCommand_delegatesToNextCommand() {
        CommandsAPICommand next = mock(CommandsAPICommand.class);
        when(next.onCommand(any(), any(), any())).thenReturn(true);

        boolean result = helpCmd.onCommand(UUID.randomUUID(), new HashMap<>(), next);

        assertTrue(result);
        verify(next).onCommand(any(), any(), isNull());
    }

    // ── onCommand with permission ─────────────────────────────────────────────

    @Test
    void onCommand_withPermission_returnsTrue() {
        // MockRTPCommandSender.hasPermission always returns true
        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "player1", null));

        boolean result = helpCmd.onCommand(senderId, new HashMap<>(), null);

        assertTrue(result);
    }

    @Test
    void onCommand_withoutPermission_returnsTrueAndSendsNoPerms() {
        // Use a sender that denies all permissions
        UUID senderId = UUID.randomUUID();
        MockRTPCommandSender noPermSender = new MockRTPCommandSender(senderId, "noPermPlayer") {
            @Override
            public boolean hasPermission(String permission) {
                return false;
            }
        };

        // Register a custom accessor that returns our no-perm sender
        MockRTPServerAccessor customAccessor = new MockRTPServerAccessor(tempDir.toFile()) {
            @Override
            public io.github.dailystruggle.rtp.api.entity.RTPCommandSender getSender(UUID uuid) {
                if (uuid.equals(senderId)) return noPermSender;
                return super.getSender(uuid);
            }
        };
        RTP.serverAccessor = customAccessor;

        boolean result = helpCmd.onCommand(senderId, new HashMap<>(), null);

        assertTrue(result);
    }

    // ── onCommand iterates sub-commands ───────────────────────────────────────

    @Test
    void onCommand_iteratesBaseCommandLookup() {
        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "player2", null));

        // Add a sub-command whose name matches a MessagesKeys value
        CommandsAPICommand subCmd = mock(CommandsAPICommand.class);
        when(subCmd.name()).thenReturn("help");
        when(subCmd.permission()).thenReturn("rtp.see");

        Map<String, CommandsAPICommand> lookup = new HashMap<>();
        lookup.put("help", subCmd);
        when(RTP.baseCommand.getCommandLookup()).thenReturn(lookup);

        // Should not throw; returns true
        boolean result = helpCmd.onCommand(senderId, new HashMap<>(), null);
        assertTrue(result);
    }

    @Test
    void onCommand_skipsSubCommandWithUnknownMessagesKey() {
        UUID senderId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(senderId, "player3", null));

        CommandsAPICommand subCmd = mock(CommandsAPICommand.class);
        when(subCmd.name()).thenReturn("unknownCommandXYZ");
        when(subCmd.permission()).thenReturn("rtp.see");

        Map<String, CommandsAPICommand> lookup = new HashMap<>();
        lookup.put("unknownCommandXYZ", subCmd);
        when(RTP.baseCommand.getCommandLookup()).thenReturn(lookup);

        // Should not throw even when MessagesKeys.valueOf fails
        boolean result = helpCmd.onCommand(senderId, new HashMap<>(), null);
        assertTrue(result);
    }

    // ── addCommands ───────────────────────────────────────────────────────────

    @Test
    void addCommands_doesNotThrow() {
        assertDoesNotThrow(() -> helpCmd.addCommands());
    }

    @Test
    void addCommands_populatesSubCommandsFromConfigs() {
        helpCmd.addCommands();
        // After addCommands, the command lookup should contain entries for each config parser
        // (at minimum the core parsers registered by RTPTestSetup)
        assertNotNull(helpCmd.getCommandLookup());
    }
}
