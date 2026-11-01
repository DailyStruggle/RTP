package io.github.dailystruggle.rtp.common.commands.editor.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Persistence, replacement, verification and peer-key checks of the editor channel key pair (ADR-106 §5.1). */
@DisplayName("ADR-106 §5.1: EditorKeys persistence and verification")
class EditorKeysTest {

    @TempDir
    Path tempDir;

    private static PublicKey rsaKey(int bits) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(bits);
        return g.generateKeyPair().getPublic();
    }

    @Test
    @DisplayName("loadOrCreate persists a pair, reloads the same key, and replaces a mismatched pair (S-004)")
    void loadReloadAndReplaceMismatch() throws IOException {
        Path dir = tempDir.resolve("keys");
        EditorKeys created = EditorKeys.loadOrCreate(dir);
        assertTrue(Files.isRegularFile(dir.resolve(EditorKeys.PRIVATE_FILE)));
        assertTrue(Files.isRegularFile(dir.resolve(EditorKeys.PUBLIC_FILE)));
        assertFalse(Files.exists(dir.resolve(EditorKeys.PRIVATE_FILE + ".tmp")), "temp file moved into place");

        EditorKeys reloaded = EditorKeys.loadOrCreate(dir);
        assertEquals(created.fingerprint(), reloaded.fingerprint(), "persisted pair is reused");

        // Public half of another pair: the probe signature no longer verifies
        Files.write(dir.resolve(EditorKeys.PUBLIC_FILE), EditorKeys.generate().publicKey().getEncoded());
        EditorKeys replaced = EditorKeys.loadOrCreate(dir);
        assertNotEquals(created.fingerprint(), replaced.fingerprint(), "mismatched pair replaced");
        assertEquals(replaced.fingerprint(), EditorKeys.loadOrCreate(dir).fingerprint(), "replacement persisted");
    }

    @Test
    @DisplayName("An unreadable pair is logged and replaced; a missing half regenerates both")
    void corruptOrPartialPairReplaced() throws IOException {
        Path dir = tempDir.resolve("corrupt");
        Files.createDirectories(dir);
        Files.write(dir.resolve(EditorKeys.PRIVATE_FILE), new byte[]{1, 2, 3});
        Files.write(dir.resolve(EditorKeys.PUBLIC_FILE), new byte[]{4, 5, 6});
        EditorKeys keys = EditorKeys.loadOrCreate(dir);
        assertEquals(keys.fingerprint(), EditorKeys.loadOrCreate(dir).fingerprint());

        Path partial = tempDir.resolve("partial");
        Files.createDirectories(partial);
        Files.write(partial.resolve(EditorKeys.PRIVATE_FILE), new byte[]{1});
        EditorKeys fresh = EditorKeys.loadOrCreate(partial);
        assertTrue(Files.isRegularFile(partial.resolve(EditorKeys.PUBLIC_FILE)));
        assertEquals(fresh.publicKeyBase64(), Base64.getEncoder().encodeToString(
                Files.readAllBytes(partial.resolve(EditorKeys.PUBLIC_FILE))));
        assertThrows(NullPointerException.class, () -> EditorKeys.loadOrCreate(null));
    }

    @Test
    @DisplayName("restrictToOwner succeeds on the default file system and reports false where neither POSIX nor ACL exists")
    void restrictToOwner() throws IOException {
        Path file = Files.createFile(tempDir.resolve("secret.bin"));
        assertTrue(EditorKeys.restrictToOwner(file));
        assertTrue(Files.isReadable(file), "owner keeps access");

        URI zip = URI.create("jar:" + tempDir.resolve("plain.zip").toUri());
        try (FileSystem zfs = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Path inZip = Files.createFile(zfs.getPath("/k.bin"));
            assertFalse(EditorKeys.restrictToOwner(inZip), "zip file system offers neither view");
        }
    }

    @Test
    @DisplayName("verify is true only for a matching signature and never throws on bad input")
    void verifyNeverThrows() throws Exception {
        EditorKeys keys = EditorKeys.generate();
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        byte[] sig = keys.sign(data);
        assertTrue(EditorKeys.verify(keys.publicKey(), data, sig));
        assertFalse(EditorKeys.verify(keys.publicKey(), "other".getBytes(StandardCharsets.UTF_8), sig));
        assertFalse(EditorKeys.verify(null, data, sig));
        assertFalse(EditorKeys.verify(keys.publicKey(), null, sig));
        assertFalse(EditorKeys.verify(keys.publicKey(), data, null));
        assertFalse(EditorKeys.verify(keys.publicKey(), data, new byte[]{1, 2, 3}), "wrong signature length");

        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        KeyPair ecPair = ec.generateKeyPair();
        assertFalse(EditorKeys.verify(ecPair.getPublic(), data, sig), "non-RSA key rejected without throwing");
    }

    @Test
    @DisplayName("decodePublicKey accepts 2048-4096 bit RSA SPKI and refuses missing, oversized, malformed and out-of-range keys")
    void decodePublicKey() throws Exception {
        EditorKeys keys = EditorKeys.generate();
        PublicKey decoded = EditorKeys.decodePublicKey(keys.publicKeyBase64());
        assertEquals(keys.fingerprint(), EditorKeys.fingerprint(decoded));

        assertThrows(IllegalArgumentException.class, () -> EditorKeys.decodePublicKey(null));
        assertThrows(IllegalArgumentException.class, () -> EditorKeys.decodePublicKey("A".repeat(1025)));
        assertThrows(IllegalArgumentException.class, () -> EditorKeys.decodePublicKey("not base64!"));
        assertThrows(IllegalArgumentException.class,
                () -> EditorKeys.decodePublicKey(Base64.getEncoder().encodeToString(new byte[]{0x30, 0x03, 1, 2, 3})));

        String weak = Base64.getEncoder().encodeToString(rsaKey(1024).getEncoded());
        IllegalArgumentException small = assertThrows(IllegalArgumentException.class, () -> EditorKeys.decodePublicKey(weak));
        assertTrue(small.getMessage().contains("1024"), small.getMessage());

        String big = Base64.getEncoder().encodeToString(rsaKey(EditorKeys.MAX_PEER_BITS + 8).getEncoded());
        assertTrue(big.length() <= 1024, "still under the length cap");
        IllegalArgumentException large = assertThrows(IllegalArgumentException.class, () -> EditorKeys.decodePublicKey(big));
        assertTrue(large.getMessage().contains("not allowed"), large.getMessage());
    }

    @Test
    @DisplayName("toString shows a 16-hex fingerprint prefix; constantTimeEquals handles nulls")
    void toStringAndConstantTimeEquals() {
        EditorKeys keys = EditorKeys.generate();
        assertEquals("EditorKeys[" + keys.fingerprint().substring(0, 16) + "]", keys.toString());
        assertTrue(keys.fingerprint().matches("[0-9a-f]{64}"));

        assertTrue(EditorKeys.constantTimeEquals("abc", "abc"));
        assertFalse(EditorKeys.constantTimeEquals("abc", "abd"));
        assertFalse(EditorKeys.constantTimeEquals("abc", "abcd"));
        assertFalse(EditorKeys.constantTimeEquals(null, "abc"));
        assertFalse(EditorKeys.constantTimeEquals("abc", null));
        assertFalse(EditorKeys.constantTimeEquals(null, null));
    }
}
