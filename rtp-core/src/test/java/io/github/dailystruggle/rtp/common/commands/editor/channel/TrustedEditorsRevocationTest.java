package io.github.dailystruggle.rtp.common.commands.editor.channel;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-106 §5.2: trusted browser keys can be revoked (prefix or all, file and open channels), expire
 * after the configured max age (legacy rows without {@code added} count from load), and the channel
 * key pair is owner-only on POSIX and ACL file systems.
 */
@DisplayName("ADR-106: editor trust revocation, max age and owner-only key files")
class TrustedEditorsRevocationTest {

    private static final String A = "abcd" + "0".repeat(60);
    private static final String B = "abce" + "1".repeat(60);
    private static final String C = "ffff" + "2".repeat(60);

    @TempDir
    Path dir;

    private Path file(String json) throws IOException {
        Path f = dir.resolve("trusted-editors.json");
        Files.writeString(f, json, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    @DisplayName("remove(prefix) and remove(all) untrust and persist; short or non-hex selectors are refused")
    void removeByPrefixAndAll() throws IOException {
        Path f = file("{\"version\":1,\"trusted\":[{\"fingerprint\":\"" + A + "\",\"added\":1},"
                + "{\"fingerprint\":\"" + B + "\",\"added\":2},{\"fingerprint\":\"" + C + "\",\"added\":3}]}");
        TrustedEditors t = TrustedEditors.load(f);
        assertEquals(Set.of(A), t.remove("abcd"));
        assertFalse(t.isTrusted(A));
        assertTrue(t.isTrusted(B));
        assertEquals(Set.of(B, C), TrustedEditors.load(f).fingerprints(), "the removal is persisted");

        assertTrue(t.remove("0123").isEmpty(), "no match leaves the list alone");
        assertEquals(Set.of(B, C), t.remove("all"));
        assertTrue(TrustedEditors.load(f).fingerprints().isEmpty());

        assertFalse(TrustedEditors.isSelector("abc"), "too short to be deliberate");
        assertFalse(TrustedEditors.isSelector("ABCD"), "fingerprints are lowercase hex");
        assertFalse(TrustedEditors.isSelector("../x"));
        assertTrue(TrustedEditors.isSelector("all"));
        assertThrows(IllegalArgumentException.class, () -> t.forget("zz"));
    }

    @Test
    @DisplayName("Max age: entries expire after maxAge from 'added'; legacy rows without 'added' count from load")
    void maxAge() throws IOException {
        Path f = file("{\"version\":1,\"trusted\":[{\"fingerprint\":\"" + A + "\",\"added\":1000},"
                + "{\"fingerprint\":\"" + B + "\"}]}");
        AtomicLong now = new AtomicLong(5_000L);
        TrustedEditors t = TrustedEditors.load(f, 10_000L, now::get);
        assertTrue(t.isTrusted(A));
        assertTrue(t.isTrusted(B), "the existing file format still loads");
        now.set(11_000L);
        assertFalse(t.isTrusted(A), "10 s after 'added'");
        assertTrue(t.isTrusted(B), "legacy row counts from load (5 s), not from 0");

        assertFalse(TrustedEditors.load(f, 10_000L, () -> 20_000L).isTrusted(A), "expired rows are dropped at load");
        assertTrue(TrustedEditors.load(f, 0L, () -> Long.MAX_VALUE / 2).isTrusted(A), "0 = never expires");
    }

    @Test
    @DisplayName("untrustAny drops the key from every open channel's in-memory list")
    void untrustOpenChannels() {
        TrustedEditors trusted = TrustedEditors.inMemory();
        assertDoesNotThrow(() -> trusted.add(A, 1L));
        InMemoryTransport[] pair = InMemoryTransport.pair();
        EditorChannel ch = new EditorChannel(EditorChannel.newChannelId(), EditorKeys.generate(), trusted, pair[0],
                (nonce, fp) -> { }, System::currentTimeMillis);
        ch.start().orTimeout(10, TimeUnit.SECONDS).join();
        try {
            assertEquals(Set.of(A), EditorChannel.untrustAny("abcd"));
            assertFalse(trusted.isTrusted(A));
            assertTrue(EditorChannel.untrustAny("abcd").isEmpty(), "already gone");
        } finally {
            ch.close("test done");
        }
    }

    @Test
    @DisplayName("The channel private key is owner-only (POSIX rw------- or a single owner ACL entry)")
    void privateKeyOwnerOnly() throws IOException {
        Path keys = dir.resolve("keys");
        EditorKeys.loadOrCreate(keys);
        assertOwnerOnly(keys.resolve(EditorKeys.PRIVATE_FILE));
    }

    @Test
    @DisplayName("trusted-editors.json is owner-only on POSIX and ACL file systems")
    void trustedEditorsFileOwnerOnly() throws IOException {
        Path f = dir.resolve("trusted-editors.json");
        TrustedEditors t = TrustedEditors.load(f);
        t.add(A, 1000L);
        assertOwnerOnly(f);
    }

    @Test
    @DisplayName("Concurrent writers reload and merge without dropping entries")
    void concurrentWritersMerge() throws IOException {
        Path f = dir.resolve("trusted-editors.json");
        TrustedEditors t1 = TrustedEditors.load(f);
        TrustedEditors t2 = TrustedEditors.load(f);

        t1.add(A, 1000L);
        t2.add(B, 2000L);

        TrustedEditors reloaded = TrustedEditors.load(f);
        assertTrue(reloaded.isTrusted(A), "A from t1 must be retained");
        assertTrue(reloaded.isTrusted(B), "B from t2 must be retained");
    }

    /** POSIX {@code rw-------}, or every ACL entry granted to the owner. */
    public static void assertOwnerOnly(Path f) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(f, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), posix.readAttributes().permissions(), f.toString());
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(f, AclFileAttributeView.class);
        Assumptions.assumeTrue(acl != null, "neither POSIX nor ACL view on this file system");
        UserPrincipal owner = acl.getOwner();
        List<AclEntry> entries = acl.getAcl();
        assertFalse(entries.isEmpty(), "the owner keeps access");
        for (AclEntry e : entries) assertEquals(owner, e.principal(), f + ": " + entries);
    }
}
