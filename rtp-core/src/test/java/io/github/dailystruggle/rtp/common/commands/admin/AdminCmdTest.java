package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@code /rtp admin} ({@link AdminCmd}) bare-form branches:
 * rejection when no admin-panel opener is wired, successful opener dispatch,
 * rejection when the opener throws (never propagating), and {@code nextCommand}
 * delegation (REQ-RTP-S-004, S-007).
 */
class AdminCmdTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
    }

    @Test
    @DisplayName("bare admin with no opener wired rejects (returns false)")
    void bareForm_noOpener_rejects() {
        AdminCmd cmd = new AdminCmd(null);
        boolean handled = cmd.onCommand(UUID.randomUUID(), new HashMap<>(), null);
        assertFalse(handled, "bare /rtp admin must reject when no opener is wired");
    }

    @Test
    @DisplayName("bare admin invokes the wired opener with the caller and reports handled")
    void bareForm_withOpener_invokesOpener() {
        AtomicReference<UUID> opened = new AtomicReference<>(null);
        UUID caller = UUID.randomUUID();
        AdminCmd cmd = new AdminCmd(null, opened::set);

        boolean handled = cmd.onCommand(caller, new HashMap<>(), null);

        assertTrue(handled, "opener success reports handled");
        assertEquals(caller, opened.get(), "opener invoked with the caller uuid");
    }

    @Test
    @DisplayName("opener runtime failure is caught and turned into a rejection")
    void bareForm_openerThrows_rejects() {
        AdminCmd cmd = new AdminCmd(null, uuid -> {
            throw new RuntimeException("boom");
        });

        boolean handled = cmd.onCommand(UUID.randomUUID(), new HashMap<>(), null);

        assertFalse(handled, "an opener exception must yield a rejection, not propagate");
    }

    @Test
    @DisplayName("nextCommand delegation short-circuits before opening the panel")
    void delegatesToNextCommand() {
        AtomicReference<UUID> opened = new AtomicReference<>(null);
        AdminCmd cmd = new AdminCmd(null, opened::set);

        AtomicBoolean delegated = new AtomicBoolean(false);
        CommandsAPICommand next = new AdminCmd(null) {
            @Override
            public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues,
                                     CommandsAPICommand nextCommand) {
                delegated.set(true);
                return true;
            }
        };

        assertTrue(cmd.onCommand(UUID.randomUUID(), new HashMap<>(), next));
        assertTrue(delegated.get(), "nextCommand must be invoked");
        assertNull(opened.get(), "delegation must short-circuit before the opener runs");
    }

    @Test
    @DisplayName("admin exposes its verb and menu permission")
    void metadata() {
        AdminCmd cmd = new AdminCmd(null);
        assertEquals("admin", cmd.name());
        assertEquals(AdminCmd.PERMISSION, cmd.permission());
        assertEquals("rtp.menu.admin", cmd.permission());
    }
}
