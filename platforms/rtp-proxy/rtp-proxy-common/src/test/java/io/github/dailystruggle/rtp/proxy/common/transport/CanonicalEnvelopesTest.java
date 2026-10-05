package io.github.dailystruggle.rtp.proxy.common.transport;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalEnvelopesTest {

    private static HmacVerifier verifier() {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 9);
        return HmacVerifier.forTesting(secret, 1, 1);
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: delimiter characters are rejected in every signed field")
    void delimitersRejected() {
        for (String bad : new String[]{"a\nb", "a\rb", "a=b", "a|b", "a\u0000b"}) {
            assertFalse(CanonicalEnvelopes.isSafeField(bad), bad);
            assertThrows(IllegalArgumentException.class, () ->
                    CanonicalEnvelopes.canonicalToken("t", "s", "p", "1", "2", "CLAIMED", bad));
            assertThrows(IllegalArgumentException.class, () ->
                    CanonicalEnvelopes.canonicalQueueEnvelope("c", "p", "r", bad, "1"));
        }
        assertTrue(CanonicalEnvelopes.isSafeField(null));
        assertTrue(CanonicalEnvelopes.isSafeField("survival:east-1"));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: field-boundary forgery fails verification instead of colliding")
    void fieldBoundaryForgeryFails() {
        HmacVerifier v = verifier();
        String hmac = CanonicalEnvelopes.signToken(v, 1, "t", "srv", "p", "1", "2", "CLAIMED", "east");
        // Moving bytes across a boundary would need a delimiter, which verify refuses.
        assertFalse(CanonicalEnvelopes.verifyToken(v, 1, "t", "srv\nplayerId=p", "", "1", "2", "CLAIMED", "east", hmac));
        assertTrue(CanonicalEnvelopes.verifyToken(v, 1, "t", "srv", "p", "1", "2", "CLAIMED", "east", hmac));
        assertFalse(CanonicalEnvelopes.verifyToken(v, 1, "t", "srv", "p", "1", "2", "CLAIMED", "west", hmac));
        assertFalse(CanonicalEnvelopes.verifyToken(v, 1, "t", "srv", "p", "1", "2", "CLAIMED", "east", ""));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: null and empty regionKey sign identically (regionless request)")
    void nullAndEmptyRegionEquivalent() {
        HmacVerifier v = verifier();
        String hmac = CanonicalEnvelopes.signToken(v, 1, "t", "s", "p", "1", "2", "CLAIMED", null);
        assertTrue(CanonicalEnvelopes.verifyToken(v, 1, "t", "s", "p", "1", "2", "CLAIMED", "", hmac));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: queue envelope HMAC binds serverHint and regionKey")
    void queueEnvelopeBindsFields() {
        HmacVerifier v = verifier();
        String hmac = CanonicalEnvelopes.signQueueEnvelope(v, 1, "c", "p", "east", "srv-a", "5");
        assertTrue(CanonicalEnvelopes.verifyQueueEnvelope(v, 1, "c", "p", "east", "srv-a", "5", hmac));
        assertFalse(CanonicalEnvelopes.verifyQueueEnvelope(v, 1, "c", "p", "east", "srv-b", "5", hmac));
        assertFalse(CanonicalEnvelopes.verifyQueueEnvelope(v, 1, "c", "p2", "east", "srv-a", "5", hmac));
    }
}
