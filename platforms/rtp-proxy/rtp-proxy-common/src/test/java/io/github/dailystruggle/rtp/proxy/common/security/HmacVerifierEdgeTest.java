package io.github.dailystruggle.rtp.proxy.common.security;

import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfigException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HmacVerifierEdgeTest {

    @Test
    void forTesting_nullSecret_throws() {
        assertThrows(NullPointerException.class, () -> HmacVerifier.forTesting(null, 1, 1));
    }

    @Test
    void forTesting_shortSecret_throws() {
        byte[] shortSecret = new byte[31];
        assertThrows(IllegalArgumentException.class, () -> HmacVerifier.forTesting(shortSecret, 1, 1));
    }

    @Test
    void sign_nullPayload_throws() {
        byte[] secret = new byte[32];
        try (HmacVerifier verifier = HmacVerifier.forTesting(secret, 1, 1)) {
            assertThrows(NullPointerException.class, () -> verifier.sign(1, null));
        }
    }

    @Test
    void verify_nullHmacOrEmpty_returnsFalse() {
        byte[] secret = new byte[32];
        try (HmacVerifier verifier = HmacVerifier.forTesting(secret, 1, 2)) {
            assertFalse(verifier.verify(1, "payload", null));
            assertFalse(verifier.verify(1, "payload", ""));
        }
    }

    @Test
    void verify_schemaVersionOutOfBounds_returnsFalse() {
        byte[] secret = new byte[32];
        try (HmacVerifier verifier = HmacVerifier.forTesting(secret, 2, 4)) {
            String sig = verifier.sign(2, "payload");
            assertFalse(verifier.verify(1, "payload", sig)); // below min
            assertFalse(verifier.verify(5, "payload", sig)); // above max
            assertTrue(verifier.verify(2, "payload", sig));
            assertTrue(verifier.verify(3, "payload", verifier.sign(3, "payload")));
            assertTrue(verifier.verify(4, "payload", verifier.sign(4, "payload")));
        }
    }

    @Test
    void verify_invalidHex_returnsFalse() {
        byte[] secret = new byte[32];
        try (HmacVerifier verifier = HmacVerifier.forTesting(secret, 1, 1)) {
            assertFalse(verifier.verify(1, "payload", "not-hex"));
            assertFalse(verifier.verify(1, "payload", "zzzz"));
        }
    }

    @Test
    void verify_mismatchedPayloadOrMac_returnsFalse() {
        byte[] secret = new byte[32];
        try (HmacVerifier verifier = HmacVerifier.forTesting(secret, 1, 1)) {
            String sig = verifier.sign(1, "payload");
            assertFalse(verifier.verify(1, "different-payload", sig));

            // Flip first char in hex
            char flippedChar = sig.charAt(0) == 'a' ? 'b' : 'a';
            String corrupted = flippedChar + sig.substring(1);
            assertFalse(verifier.verify(1, "payload", corrupted));
        }
    }

    @Test
    void accessors_returnExpectedVersions() {
        byte[] secret = new byte[32];
        try (HmacVerifier verifier = HmacVerifier.forTesting(secret, 2, 5)) {
            assertEquals(2, verifier.minSchemaVersion());
            assertEquals(5, verifier.currentSchemaVersion());
        }
    }

    @Test
    void close_zeroesKeyAndIsIdempotent() {
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) (i + 1);
        }
        HmacVerifier verifier = HmacVerifier.forTesting(secret, 1, 1);
        String beforeClose = verifier.sign(1, "test");
        assertNotNull(beforeClose);

        verifier.close();
        verifier.close(); // idempotent

        // After close, secret buffer was zeroed, so signing again produces sign with all 0s
        HmacVerifier zeroVerifier = HmacVerifier.forTesting(new byte[32], 1, 1);
        String zeroSig = zeroVerifier.sign(1, "test");
        assertEquals(zeroSig, verifier.sign(1, "test"));
    }

    @Test
    void loadFromEnv_nullOrMissingEnv_throws() {
        assertThrows(NullPointerException.class, () -> HmacVerifier.loadFromEnv(null, 1, 1));
        assertThrows(NetworkConfigException.class, () -> HmacVerifier.loadFromEnv("NON_EXISTENT_VAR_XYZ_12345", 1, 1));
    }
}
