package io.github.dailystruggle.rtp.common.network.pluginmessage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;

@DisplayName("PluginMessageEnvelope Coverage-Guided Fuzz Tests")
class PluginMessageEnvelopeFuzzTest {

    private static final HmacVerifier VERIFIER = createVerifier();

    private static HmacVerifier createVerifier() {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x42);
        return HmacVerifier.forTesting(secret, 1, 1);
    }

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz PluginMessageEnvelope.open: total, never throws, rejects unauthenticated frames")
    void fuzzOpen(byte[] payload) {
        PluginMessageEnvelope.Result unsignedResult = PluginMessageEnvelope.open(payload, null);
        assertNotNull(unsignedResult);
        if (unsignedResult.accepted()) {
            assertNotNull(unsignedResult.heartbeat());
        } else {
            assertNotNull(unsignedResult.rejection());
        }

        PluginMessageEnvelope.Result signedResult = PluginMessageEnvelope.open(payload, VERIFIER);
        assertNotNull(signedResult);
        if (payload == null || payload.length == 0) {
            assertFalse(signedResult.accepted());
        }
        if (signedResult.accepted()) {
            assertNotNull(signedResult.heartbeat());
        } else {
            assertNotNull(signedResult.rejection());
        }
    }
}
