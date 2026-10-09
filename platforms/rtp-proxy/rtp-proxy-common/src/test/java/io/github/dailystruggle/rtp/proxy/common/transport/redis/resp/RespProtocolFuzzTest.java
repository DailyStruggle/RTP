package io.github.dailystruggle.rtp.proxy.common.transport.redis.resp;

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;

@DisplayName("RespProtocol Coverage-Guided Fuzz Tests")
class RespProtocolFuzzTest {

    @FuzzTest(maxDuration = "2s")
    @DisplayName("Fuzz RESP protocol reply decoding against arbitrary byte mutations")
    void fuzzReadReply(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }

        try {
            ByteArrayInputStream in = new ByteArrayInputStream(data);
            RespProtocol.readReply(in);
        } catch (RespException | IOException | NumberFormatException expected) {
            // Expected safe fail-closed exceptions for corrupt or out-of-bounds streams
        }
    }
}
