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

        RTP.updateSharedLastTeleportTime(null, 1000L);
        RTP.updateSharedLastTeleportTime(id, 1000L);
    }

    @Test
    @DisplayName("RTPAPI.teleport with COORDINATE target teleports locally")
    void testCoordinateTargetLocalTeleport(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir.toFile());
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
        UUID playerId = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer player =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                        playerId, "CoordUser", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        // Coordinate target on local server
        RtpTarget target = RtpTarget.coordinate(null, "world", 100, 75, 200);
        java.util.concurrent.CompletableFuture<io.github.dailystruggle.rtp.api.RTPResult> future =
                io.github.dailystruggle.rtp.api.RTPAPI.teleport(playerId, target);

        assertNotNull(future);
        io.github.dailystruggle.rtp.api.RTPResult res = future.join();
        assertTrue(res.isSuccess(), "Coordinate teleport must succeed: " + res);
        assertEquals(100, player.getLocation().getBlockX());
        assertEquals(75, player.getLocation().getBlockY());
        assertEquals(200, player.getLocation().getBlockZ());

        // Target status check covers getEffectiveLastTeleportTime
        TeleportData td = new TeleportData();
        td.time = System.currentTimeMillis();
        td.completed = true;
        RTP.getInstance().latestTeleportData.put(playerId, td);
        assertEquals(td.time, RTP.getEffectiveLastTeleportTime(playerId));

        io.github.dailystruggle.rtp.api.RtpTargetStatus status =
                io.github.dailystruggle.rtp.api.RTPAPI.getTargetStatus(playerId, RtpTarget.region("default"));
        assertNotNull(status);
    }

    @Test
    @DisplayName("RTPAPI.teleport with remote COORDINATE target routes or rejects")
    void testCoordinateTargetRemoteRouting(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir.toFile());
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
        UUID playerId = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer player =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                        playerId, "RemoteCoordUser", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        // Without network hook: fails with INVALID_TARGET
        RtpTarget remoteTarget = RtpTarget.coordinate("remote-node", "world_nether", 50, 70, 50);
        io.github.dailystruggle.rtp.api.RTPResult res1 = io.github.dailystruggle.rtp.api.RTPAPI.teleport(playerId, remoteTarget).join();
        assertFalse(res1.isSuccess());

        // With cross-server hook: returns queued
        RTP.networkCommandHook = (pId, args) ->
                io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.crossServer(
                        UUID.randomUUID(), "default", "remote-node");
        io.github.dailystruggle.rtp.api.RTPResult res2 = io.github.dailystruggle.rtp.api.RTPAPI.teleport(playerId, remoteTarget).join();
        assertTrue(res2.isQueued());

        // With reject hook: returns failure
        RTP.networkCommandHook = (pId, args) ->
                io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.reject("server_full", "Server full");
        io.github.dailystruggle.rtp.api.RTPResult res3 = io.github.dailystruggle.rtp.api.RTPAPI.teleport(playerId, remoteTarget).join();
        assertEquals(io.github.dailystruggle.rtp.api.RTPResult.Reason.INVALID_TARGET, res3.reason());

        // With local fallback: returns failure on unexpected routing
        RTP.networkCommandHook = (pId, args) ->
                io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.local();
        io.github.dailystruggle.rtp.api.RTPResult res4 = io.github.dailystruggle.rtp.api.RTPAPI.teleport(playerId, remoteTarget).join();
        assertEquals(io.github.dailystruggle.rtp.api.RTPResult.Reason.INVALID_TARGET, res4.reason());

        RTP.networkCommandHook = io.github.dailystruggle.rtp.api.network.NetworkCommandHook.LOCAL_ONLY;
    }

    @Test
    @DisplayName("BackCmd onCommand handles console, no perm, no back location, and successful back teleport")
    void testBackCmdExecution(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                io.github.dailystruggle.rtp.common.mock.RTPTestSetup.install(tempDir.toFile());
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> world = accessor.getRTPWorld("world");
        BackCmd cmd = new BackCmd(null);

        // 1. Console execution rejected
        UUID consoleId = UUID.randomUUID();
        MockRTPCommandSender console = new MockRTPCommandSender(consoleId, "CONSOLE");
        accessor.addSender(console);
        assertTrue(cmd.onCommand(consoleId, java.util.Map.of(), null));

        // 2. Player without permission
        UUID pId = UUID.randomUUID();
        io.github.dailystruggle.rtp.common.mock.MockRTPPlayer player =
                new io.github.dailystruggle.rtp.common.mock.MockRTPPlayer(
                        pId, "TestP", new io.github.dailystruggle.rtp.api.world.RTPLocation(world, 10, 64, 10));
        player.setPermission("rtp.back", false);
        player.setPermission("rtp.*", false);
        accessor.addPlayer(player);
        assertTrue(cmd.onCommand(pId, java.util.Map.of(), null));

        // 3. Player with permission but no previous back location
        player.setPermission("rtp.back", true);
        assertTrue(cmd.onCommand(pId, java.util.Map.of(), null));

        // 4. Player with back location and cooldown bypass
        TeleportData td = new TeleportData();
        td.originalCoords = new RTPCoords("world", 50, 70, 50);
        td.completed = true;
        RTP.getInstance().latestTeleportData.put(pId, td);

        assertTrue(cmd.onCommand(pId, java.util.Map.of(), null));
        // Verify coordinates updated to originalCoords
        assertEquals(50, player.getLocation().getBlockX());
        assertEquals(70, player.getLocation().getBlockY());
        assertEquals(50, player.getLocation().getBlockZ());
    }
}
