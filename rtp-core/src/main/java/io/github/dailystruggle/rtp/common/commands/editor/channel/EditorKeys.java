package io.github.dailystruggle.rtp.common.commands.editor.channel;

import io.github.dailystruggle.rtp.common.RTP;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.logging.Level;

/**
 * The plugin's editor-channel key pair (ADR-106 §5.1): RSA-2048, SHA256withRSA
 * (RSASSA-PKCS1-v1_5), which Java and WebCrypto sign and verify with identical bytes. Persisted
 * under {@code <dataFolder>/editor/keys/} as PKCS#8 / X.509 SPKI DER, owner-only where the file
 * system supports POSIX permissions. A missing or corrupt pair is logged and replaced (S-004).
 */
public final class EditorKeys {

    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    static final String PRIVATE_FILE = "editor-private.pk8";
    static final String PUBLIC_FILE = "editor-public.spki";
    static final int KEY_BITS = 2048;
    /** Browser keys outside this range are refused (too weak / needlessly slow to verify). */
    static final int MIN_PEER_BITS = 2048;
    static final int MAX_PEER_BITS = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String publicKeyBase64;
    private final String fingerprint;

    private EditorKeys(KeyPair pair) {
        this.privateKey = pair.getPrivate();
        this.publicKey = pair.getPublic();
        this.publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.getEncoded());
        this.fingerprint = fingerprint(publicKey);
    }

    /** Fresh in-memory pair (tests, or when the key directory is not writable). */
    public static EditorKeys generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(KEY_BITS, RANDOM);
            return new EditorKeys(g.generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA key generation unavailable", e);
        }
    }

    /**
     * Loads the persisted pair from {@code dir}, or generates and persists a new one. Off the main
     * thread only: generation takes up to a second and touches the disk.
     */
    public static EditorKeys loadOrCreate(Path dir) throws IOException {
        Objects.requireNonNull(dir, "dir");
        Path priv = dir.resolve(PRIVATE_FILE);
        Path pub = dir.resolve(PUBLIC_FILE);
        if (Files.isRegularFile(priv) && Files.isRegularFile(pub)) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv)));
                PublicKey pu = kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub)));
                EditorKeys keys = new EditorKeys(new KeyPair(pu, pk));
                if (keys.matches()) return keys;
                RTP.log(Level.WARNING, "[editor] channel key pair in " + dir + " does not match; replacing it");
            } catch (GeneralSecurityException | IOException | RuntimeException e) {
                RTP.log(Level.WARNING, "[editor] channel key pair in " + dir + " is unreadable; replacing it: "
                        + e.getMessage(), e);
            }
        }
        EditorKeys keys = generate();
        Files.createDirectories(dir);
        writePrivate(priv, keys.privateKey.getEncoded());
        writePrivate(pub, keys.publicKey.getEncoded());
        RTP.log(Level.INFO, "[editor] generated editor channel key " + keys.fingerprint.substring(0, 16) + " in " + dir);
        return keys;
    }

    private boolean matches() {
        byte[] probe = new byte[32];
        RANDOM.nextBytes(probe);
        return verify(publicKey, probe, sign(probe));
    }

    private static void writePrivate(Path target, byte[] der) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.write(tmp, der);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** SHA256withRSA over {@code data}. */
    public byte[] sign(byte[] data) {
        try {
            Signature s = Signature.getInstance(SIGNATURE_ALGORITHM);
            s.initSign(privateKey);
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    /** {@code true} only for a valid SHA256withRSA signature; never throws. */
    public static boolean verify(PublicKey key, byte[] data, byte[] signature) {
        if (key == null || data == null || signature == null) return false;
        try {
            Signature s = Signature.getInstance(SIGNATURE_ALGORITHM);
            s.initVerify(key);
            s.update(data);
            return s.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /**
     * A browser's base64 SPKI RSA public key, size-checked.
     *
     * @throws IllegalArgumentException when it is not an RSA key of 2048-4096 bits
     */
    public static PublicKey decodePublicKey(String base64Spki) {
        if (base64Spki == null || base64Spki.length() > 1024) throw new IllegalArgumentException("public key missing or too long");
        try {
            PublicKey k = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64Spki)));
            if (!(k instanceof RSAPublicKey rsa)) throw new IllegalArgumentException("not an RSA key");
            int bits = rsa.getModulus().bitLength();
            if (bits < MIN_PEER_BITS || bits > MAX_PEER_BITS) throw new IllegalArgumentException("RSA key size " + bits + " not allowed");
            return k;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("malformed public key", e);
        }
    }

    /** Lowercase hex SHA-256 of the SPKI DER. */
    public static String fingerprint(PublicKey key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    /** Base64 SPKI DER: the snapshot's {@code channel.pluginKey}. */
    public String publicKeyBase64() {
        return publicKeyBase64;
    }

    public String fingerprint() {
        return fingerprint;
    }

    @Override
    public String toString() {
        return "EditorKeys[" + fingerprint.substring(0, 16) + "]";
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
