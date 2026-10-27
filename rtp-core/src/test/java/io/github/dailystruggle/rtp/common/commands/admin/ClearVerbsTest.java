package io.github.dailystruggle.rtp.common.commands.admin;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the player-targeted {@code /rtp clear} children added
 * alongside {@code cooldown}: {@link ClearQueueCmd}, {@link ClearLimitCmd}, and
 * {@link ClearInvulnCmd}. Each exercises the shared targeting modes from
 * {@link PlayerTargetedClearCmd} (self / console-global / explicit
 * {@code player=} list) against its own backing state.
 */
class ClearVerbsTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        RTP.getInstance();
        TeleportSessions.current().queuedPlayers.clear();
        TeleportSessions.current().processingPlayers.clear();
        TeleportSessions.current().invulnerablePlayers.clear();
        TeleportSessions.current().usageCaps.clear();
    }

    private static Map<String, List<String>> noArgs() {
        return new HashMap<>();
    }

    private static Map<String, List<String>> players(String csv) {
        Map<String, List<String>> args = new HashMap<>();
        args.put("player", Collections.singletonList(csv));
        return args;
    }

    @Test
    @DisplayName("clear queue (self) drops the caller's queue/processing markers only")
    void queue_selfClearsCallerOnly() {
        ClearQueueCmd cmd = new ClearQueueCmd(null);
        UUID caller = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        RTP.getInstance();
        TeleportSessions.current().queuedPlayers.add(caller);
        TeleportSessions.current().processingPlayers.add(caller);
        TeleportSessions.current().queuedPlayers.add(other);

        cmd.onCommand(caller, noArgs(), null);

        assertFalse(TeleportSessions.current().queuedPlayers.contains(caller), "caller removed from queuedPlayers");
        assertFalse(TeleportSessions.current().processingPlayers.contains(caller), "caller removed from processingPlayers");
        assertTrue(TeleportSessions.current().queuedPlayers.contains(other), "other player left queued");
    }

    @Test
    @DisplayName("clear queue (console) drains every player's queue markers")
    void queue_globalClearsAll() {
        ClearQueueCmd cmd = new ClearQueueCmd(null);
        RTP.getInstance();
        TeleportSessions.current().queuedPlayers.add(UUID.randomUUID());
        TeleportSessions.current().processingPlayers.add(UUID.randomUUID());
        TeleportSessions.current().invulnerablePlayers.put(UUID.randomUUID(), 1L);

        cmd.onCommand(RTPAPI.serverId, noArgs(), null);

        assertTrue(TeleportSessions.current().queuedPlayers.isEmpty(), "queuedPlayers cleared globally");
        assertTrue(TeleportSessions.current().processingPlayers.isEmpty(), "processingPlayers cleared globally");
        assertTrue(TeleportSessions.current().invulnerablePlayers.isEmpty(), "invulnerablePlayers cleared globally");
    }

    @Test
    @DisplayName("clear limit (player=a,b) resets just the named usage caps")
    void limit_namedClearsListed() {
        ClearLimitCmd cmd = new ClearLimitCmd(null);
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        UUID cId = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(aId, "alice", null));
        accessor.addPlayer(new MockRTPPlayer(bId, "bob", null));
        accessor.addPlayer(new MockRTPPlayer(cId, "carol", null));
        long now = System.currentTimeMillis();
        RTP.getInstance();
        TeleportSessions.current().usageCaps.recordSuccess(aId, 3, 0, now);
        TeleportSessions.current().usageCaps.recordSuccess(bId, 3, 0, now);
        TeleportSessions.current().usageCaps.recordSuccess(cId, 3, 0, now);

        cmd.onCommand(RTPAPI.serverId, players("alice,bob"), null);

        org.junit.jupiter.api.Assertions.assertNull(TeleportSessions.current().usageCaps.snapshot(aId), "alice usage cap reset");
        org.junit.jupiter.api.Assertions.assertNull(TeleportSessions.current().usageCaps.snapshot(bId), "bob usage cap reset");
        org.junit.jupiter.api.Assertions.assertNotNull(
                TeleportSessions.current().usageCaps.snapshot(cId), "carol not listed and must keep her usage cap");
    }

    @Test
    @DisplayName("clear invuln (console) clears every invulnerability marker")
    void invuln_globalClearsAll() {
        ClearInvulnCmd cmd = new ClearInvulnCmd(null);
        RTP.getInstance();
        TeleportSessions.current().invulnerablePlayers.put(UUID.randomUUID(), 1L);
        TeleportSessions.current().invulnerablePlayers.put(UUID.randomUUID(), 2L);

        cmd.onCommand(RTPAPI.serverId, noArgs(), null);

        assertTrue(TeleportSessions.current().invulnerablePlayers.isEmpty(), "all invulnerability markers cleared");
    }

    @Test
    @DisplayName("clear invuln (self) clears only the caller's marker")
    void invuln_selfClearsCallerOnly() {
        ClearInvulnCmd cmd = new ClearInvulnCmd(null);
        UUID caller = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        RTP.getInstance();
        TeleportSessions.current().invulnerablePlayers.put(caller, 1L);
        TeleportSessions.current().invulnerablePlayers.put(other, 2L);

        cmd.onCommand(caller, noArgs(), null);

        assertFalse(TeleportSessions.current().invulnerablePlayers.containsKey(caller), "caller marker cleared");
        assertTrue(TeleportSessions.current().invulnerablePlayers.containsKey(other), "other marker intact");
    }
}
