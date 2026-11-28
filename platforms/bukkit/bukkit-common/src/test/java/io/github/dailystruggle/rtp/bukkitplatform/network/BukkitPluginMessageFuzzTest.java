package io.github.dailystruggle.rtp.bukkitplatform.network;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.dailystruggle.rtp.common.network.pluginmessage.PluginMessageEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;

@DisplayName("BukkitNetworkBridge Plugin-Message Length Reader Fuzz Tests")
class BukkitPluginMessageFuzzTest {

    private static final Logger SILENT_LOGGER = createSilentLogger();

    private static Logger createSilentLogger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        return logger;
    }

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz BukkitNetworkBridge plugin-message listener and length-prefixed payload reader")
    void fuzzPluginMessageLengthReader(byte[] message) {
        Plugin plugin = mock(Plugin.class);
        org.mockito.Mockito.when(plugin.getLogger()).thenReturn(SILENT_LOGGER);

        BukkitNetworkBridge bridge = new BukkitNetworkBridge(plugin, () -> 10_000L);
        List<byte[]> received = new ArrayList<>(2);
        bridge.registerInbound(received::add);

        bridge.getListener().onPluginMessageReceived(BukkitNetworkBridge.CHANNEL, null, message);
        bridge.getListener().onPluginMessageReceived(BukkitNetworkBridge.LEGACY_CHANNEL, null, message);
        bridge.getListener().onPluginMessageReceived(BukkitNetworkBridge.PROXY_CHANNEL, null, message);

        for (byte[] payload : received) {
            assertTrue(
                    payload.length > 0 && payload.length <= PluginMessageEnvelope.MAX_PAYLOAD_BYTES,
                    "Accepted plugin-message payload length must be within 1..MAX_PAYLOAD_BYTES");
        }
    }
}
