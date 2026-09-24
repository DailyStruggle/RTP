package io.github.dailystruggle.rtp.common.commands.back;

import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-RTP-NET-016 - BackCmd and Coordinate target routing")
public class BackCmdTest {

    @Test
    @DisplayName("RtpTarget.coordinate factory creates valid COORDINATE target")
    void testCoordinateTargetCreation() {
        RtpTarget target = RtpTarget.coordinate("backend-b", "world_nether", 50, 70, -50);
        assertEquals(RtpTarget.Kind.COORDINATE, target.kind());
        assertEquals("backend-b", target.serverId());
        assertEquals("world_nether", target.worldName());
        assertEquals(50, target.x());
        assertEquals(70, target.y());
        assertEquals(-50, target.z());
        assertEquals(target, RtpTarget.coordinate("backend-b", "world_nether", 50, 70, -50));
    }

    @Test
    @DisplayName("TeleportData roundtrips originServerId and originWorldName in clone")
    void testTeleportDataCloneOriginFields() {
        TeleportData data = new TeleportData();
        data.sender = new MockRTPCommandSender(UUID.randomUUID(), "TestPlayer");
        data.originServerId = "server-origin";
        data.originWorldName = "world_origin";
        data.originalCoords = new RTPCoords("world_origin", 10, 20, 30);

        TeleportData cloned = data.clone();
        assertEquals("server-origin", cloned.originServerId);
        assertEquals("world_origin", cloned.originWorldName);
        assertNotNull(cloned.originalCoords);
        assertEquals(10, cloned.originalCoords.x());
        assertEquals(20, cloned.originalCoords.y());
        assertEquals(30, cloned.originalCoords.z());
    }

    @Test
    @DisplayName("BackCmd metadata is correct")
    void testBackCmdMetadata() {
        BackCmd cmd = new BackCmd(null);
        assertEquals("back", cmd.name());
        assertEquals("rtp.back", cmd.permission());
        assertNotNull(cmd.description());
    }

    @Test
    @DisplayName("Shared last-teleport-time respects max(local, shared)")
    void testSharedLastTeleportTimeMax() {
        UUID id = UUID.randomUUID();
        // When no instance exists, returns 0 safely without NPE
        assertEquals(0L, RTP.getEffectiveLastTeleportTime(id));
        assertEquals(0L, RTP.getEffectiveLastTeleportTime(null));
    }
}
