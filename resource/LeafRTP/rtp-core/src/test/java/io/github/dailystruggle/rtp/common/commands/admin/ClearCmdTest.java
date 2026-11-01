package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the {@code /rtp clear} parent command ({@link ClearCmd}):
 * registration of its child verbs, the {@code data} alias for cooldown, its
 * metadata, the usage-message bare form, and {@code nextCommand} delegation.
 */
class ClearCmdTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
    }

    @Test
    @DisplayName("clear parent registers every child verb plus the data alias")
    void registersChildVerbsAndDataAlias() {
        ClearCmd cmd = new ClearCmd(null);
        Map<String, CommandsAPICommand> lookup = cmd.getCommandLookup();

        assertNotNull(lookup.get("CACHE"), "cache child registered");
        assertNotNull(lookup.get("COOLDOWN"), "cooldown child registered");
        assertNotNull(lookup.get("QUEUE"), "queue child registered");
        assertNotNull(lookup.get("LIMIT"), "limit child registered");
        assertNotNull(lookup.get("INVULN"), "invuln child registered");

        CommandsAPICommand data = lookup.get(ClearCooldownCmd.ALIAS.toUpperCase());
        assertNotNull(data, "data alias registered");
        assertSame(lookup.get("COOLDOWN"), data, "data alias maps to the cooldown child");
    }

    @Test
    @DisplayName("clear parent exposes its verb, permission, and description")
    void metadata() {
        ClearCmd cmd = new ClearCmd(null);
        assertEquals("clear", cmd.name());
        assertEquals(ClearCmd.PERMISSION, cmd.permission());
        assertEquals("rtp.admin", cmd.permission());
        assertNotNull(cmd.description());
    }

    @Test
    @DisplayName("bare clear invocation reports handled (usage feedback)")
    void bareInvocation_returnsTrue() {
        ClearCmd cmd = new ClearCmd(null);
        assertTrue(cmd.onCommand(RTPAPI.serverId, new HashMap<>(), null),
                "bare /rtp clear reports handled after emitting usage");
    }

    @Test
    @DisplayName("clear parent delegates to nextCommand when present")
    void delegatesToNextCommand() {
        ClearCmd cmd = new ClearCmd(null);
        AtomicBoolean delegated = new AtomicBoolean(false);
        CommandsAPICommand next = new ClearCmd(null) {
            @Override
            public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues,
                                     CommandsAPICommand nextCommand) {
                delegated.set(true);
                return true;
            }
        };

        assertTrue(cmd.onCommand(UUID.randomUUID(), new HashMap<>(), next));
        assertTrue(delegated.get(), "nextCommand must be invoked");
    }
}
