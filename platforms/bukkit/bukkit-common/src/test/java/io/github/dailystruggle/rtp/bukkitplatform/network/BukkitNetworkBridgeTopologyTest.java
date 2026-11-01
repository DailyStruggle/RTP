package io.github.dailystruggle.rtp.bukkitplatform.network;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.MockPlugin;
import be.seeseemelk.mockbukkit.ServerMock;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge.Topology;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RTP-24: BukkitNetworkBridge topology handshake gating and peer bounding")
class BukkitNetworkBridgeTopologyTest {

    private ServerMock server;
    private MockPlugin plugin;
    private AtomicLong clock;
    private BukkitNetworkBridge bridge;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        clock = new AtomicLong(10000L);
        bridge = new BukkitNetworkBridge(plugin, clock::get);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static byte[] getServerMessage(String serverName) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("GetServer");
        out.writeUTF(serverName);
        return out.toByteArray();
    }

    private static byte[] getServersMessage(String csv) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("GetServers");
        out.writeUTF(csv);
        return out.toByteArray();
    }

    @Test
    @DisplayName("RTP-24: unsolicited GetServer and GetServers replies are dropped")
    void unsolicitedRepliesDropped() {
        List<Topology> received = new ArrayList<>();
        bridge.registerTopology(received::add);

        // Inject GetServer and GetServers without calling requestTopology()
        bridge.getListener().onPluginMessageReceived(BukkitNetworkBridge.CHANNEL, null, getServerMessage("lobby-1"));
        bridge.getListener().onPluginMessageReceived(BukkitNetworkBridge.CHANNEL, null, getServersMessage("lobby-1, survival-1"));

        assertTrue(received.isEmpty(), "Unsolicited topology messages must not update topology");
        assertFalse(bridge.isTopologyRequestPending());
    }

    @Test
    @DisplayName("RTP-24: replies arriving after TTL (>5000ms) are dropped")
    void expiredRepliesDropped() {
        server.addPlayer("CarrierPlayer");
        List<Topology> received = new ArrayList<>();
        bridge.registerTopology(received::add);

        bridge.requestTopology();
        assertTrue(bridge.isTopologyRequestPending());

        // Advance clock past 5000ms TTL
        clock.addAndGet(5001L);
        assertFalse(bridge.isTopologyRequestPending());

        bridge.getListener().onPluginMessageReceived(BukkitNetworkBridge.CHANNEL, null, getServerMessage("lobby-1"));
        bridge.getListener().onPluginMessageReceived(BukkitNetworkBridge.CHANNEL, null, getServersMessage("lobby-1, survival-1"));

        assertTrue(received.isEmpty(), "Expired topology messages must be dropped");
    }

    @Test
    @DisplayName("RTP-24: valid replies within window update topology and consume pending state")
    void validRepliesAccepted() {
        server.addPlayer("CarrierPlayer");
        List<Topology> received = new ArrayList<>();
        bridge.registerTopology(received::add);

        bridge.requestTopology();
        assertTrue(bridge.isTopologyRequestPending());

        // Reply within 1000ms
        clock.addAndGet(1000L);
        bridge.getListener().onPluginMessageReceived(BukkitNetworkBridge.CHANNEL, null, getServerMessage("lobby-1"));
        bridge.getListener().onPluginMessageReceived(BukkitNetworkBridge.CHANNEL, null, getServersMessage("lobby-1, survival-1, survival-2"));

        assertEquals(1, received.size());
        Topology t = received.get(0);
        assertEquals("lobby-1", t.ownServerId());
        assertTrue(t.peerServerIds().contains("lobby-1"));
        assertTrue(t.peerServerIds().contains("survival-1"));
        assertTrue(t.peerServerIds().contains("survival-2"));

        // Subsequent duplicate replies are dropped (single-use pending state)
        bridge.getListener().onPluginMessageReceived(BukkitNetworkBridge.CHANNEL, null, getServersMessage("lobby-1, rogue-1"));
        assertEquals(1, received.size(), "Duplicate replies without new request must be dropped");
    }

    @Test
    @DisplayName("RTP-24: peer list is capped at MAX_TOPOLOGY_PEERS (256) and invalid names are dropped")
    void peerListBoundingAndSanitization() {
        server.addPlayer("CarrierPlayer");
        List<Topology> received = new ArrayList<>();
        bridge.registerTopology(received::add);

        bridge.requestTopology();

        // Build CSV with 300 servers plus some invalid names
        StringBuilder sb = new StringBuilder();
        sb.append("valid_first, bad name with spaces, valid-second, !@#$, ");
        for (int i = 0; i < 300; i++) {
            sb.append("peer-").append(i).append(", ");
        }

        bridge.getListener().onPluginMessageReceived(BukkitNetworkBridge.CHANNEL, null, getServerMessage("my-server"));
        bridge.getListener().onPluginMessageReceived(BukkitNetworkBridge.CHANNEL, null, getServersMessage(sb.toString()));

        assertEquals(1, received.size());
        Topology t = received.get(0);
        assertEquals("my-server", t.ownServerId());
        assertEquals(BukkitNetworkBridge.MAX_TOPOLOGY_PEERS, t.peerServerIds().size());
        assertTrue(t.peerServerIds().contains("valid_first"));
        assertTrue(t.peerServerIds().contains("valid-second"));
        assertFalse(t.peerServerIds().contains("bad name with spaces"));
        assertFalse(t.peerServerIds().contains("!@#$"));
    }
}
