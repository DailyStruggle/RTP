package io.github.dailystruggle.rtp.proxy.common.transport.direct;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;

@DisplayName("ProxyDirectWire Coverage-Guided Fuzz Tests")
class ProxyDirectWireFuzzTest {

    private static final HmacVerifier VERIFIER = createVerifier();

    private static HmacVerifier createVerifier() {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 0x5A);
        return HmacVerifier.forTesting(secret, 1, 1);
    }

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz ProxyDirectWire binary frame and string payload decoders")
    void fuzzWireDecoders(FuzzedDataProvider data) {
        if (data == null) {
            return;
        }
        String text = data.consumeString(512);
        byte[] raw = data.consumeRemainingAsBytes();

        // String payload decoders must be total (return null on malformed input, never throw)
        ProxyDirectWire.decodeToken(text);
        ProxyDirectWire.decodeEnvelope(text);
        ProxyDirectWire.decodeStatus(text);

        // Stream readers must only throw IOException (including FrameRejectedException / EOFException)
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            ProxyDirectWire.RequestHeader header = ProxyDirectWire.readRequestHeader(in);
            ProxyDirectWire.readSignedPayload(
                    in, header.context(), ProxyDirectWire.Direction.REQUEST, VERIFIER);
        } catch (IOException expected) {
            // Expected fail-closed rejection on malformed wire headers/frames
        }

        ProxyDirectWire.WireContext dummyCtx =
                new ProxyDirectWire.WireContext(ProxyDirectWire.OP_HEARTBEAT, 1000L, "nonce");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            ProxyDirectWire.readSignedPayload(
                    in, dummyCtx, ProxyDirectWire.Direction.REQUEST, VERIFIER);
        } catch (IOException expected) {
            // Expected fail-closed stream exception
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            ProxyDirectWire.readList(in, dummyCtx, ProxyDirectWire.Direction.RESPONSE, VERIFIER);
        } catch (IOException expected) {
            // Expected fail-closed stream/list rejection
        }
    }
}
