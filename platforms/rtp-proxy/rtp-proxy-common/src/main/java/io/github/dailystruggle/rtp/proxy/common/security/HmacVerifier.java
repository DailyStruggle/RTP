package io.github.dailystruggle.rtp.proxy.common.security;

import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfigException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * HMAC-SHA-256 envelope authenticator per
 * <a href="../../../../../../../../../docs/adr/rtp-proxy-ADR-010-security-hardening.md">rtp-proxy-ADR-010</a>.
 *
 * <p>Owns the shared-secret byte array decoded from the environment variable
 * named by {@code network.secretEnv}. Exposes constant-time sign / verify over
 * payload bytes prefixed by the wire {@code schemaVersion} per the envelope
 * formula:</p>
 *
 * <pre>{@code
 *   hmac = HMAC-SHA-256(secret, schemaVersion || '|' || canonicalPayload)
 * }</pre>
 *
 * <p>The canonical payload form is the caller's responsibility - the
 * {@code RedisNetworkStateBinding} uses its existing line-oriented
 * {@code key=value\n...} encoding (excluding the {@code hmac=} line itself);
 * the SQL binding will use a canonical-JSON form when HMAC support lands
 * there. Either way, the bytes signed must match the bytes verified on the
 * other end, byte-for-byte, including the {@code schemaVersion || '|'}
 * prefix.</p>
 *
 * <p>Verification failure is reported by returning {@code false} from
 * {@link #verify(int, String, String)}; the caller is responsible for the
 * {@code WARNING}-level audit log (REQ-RTP-S-004) and for dropping the
 * offending row.</p>
 *
 * <p>This class is thread-safe: the internal {@link Mac} is constructed on
 * each call (allocation is cheap relative to the network round-trip it
 * accompanies). The secret byte array is held immutably for the lifetime of
 * the verifier.</p>
 */
public final class HmacVerifier implements AutoCloseable {

    /** HMAC algorithm per ADR-010. */
    public static final String ALGORITHM = "HmacSHA256";

    /** Minimum acceptable secret length (32 bytes = 256 bits per ADR-010). */
    public static final int MIN_SECRET_BYTES = 32;

    private final byte[] secret;
    private final int minSchemaVersion;
    private final int currentSchemaVersion;

    private HmacVerifier(byte[] secret, int minSchemaVersion, int currentSchemaVersion) {
        this.secret = secret;
        this.minSchemaVersion = minSchemaVersion;
        this.currentSchemaVersion = currentSchemaVersion;
    }

    /**
     * Construct an {@code HmacVerifier} from a configured environment variable.
     *
     * <p>The env var value is Base64-decoded. The decoded byte array must be
     * at least {@link #MIN_SECRET_BYTES} bytes long; shorter secrets throw
     * {@link NetworkConfigException} per ADR-010 §Key Material.</p>
     *
     * @param envVarName            name of the env var to read (typically {@code RTP_NET_SECRET})
     * @param minSchemaVersion      minimum acceptable schema version on verify
     * @param currentSchemaVersion  schema version this binary publishes with
     * @return a configured verifier
     * @throws NetworkConfigException if the env var is unset, empty, not valid
     *                                Base64, or decodes to fewer than 32 bytes
     */
    public static HmacVerifier loadFromEnv(String envVarName,
                                           int minSchemaVersion,
                                           int currentSchemaVersion) {
        Objects.requireNonNull(envVarName, "envVarName");
        String raw = System.getenv(envVarName);
        if (raw == null || raw.isEmpty()) {
            throw new NetworkConfigException(
                    "network.secretEnv='" + envVarName + "' is unset or empty; "
                            + "cannot construct HmacVerifier (REQ-RTP-PROXY-007).");
        }
        // Accept both standard and URL-safe Base64 alphabets. Operators often
        // generate secrets with `openssl rand -base64` (standard) or with tools
        // emitting URL-safe Base64 (`-` and `_` in place of `+` and `/`), which
        // is also safer to embed in `.env` / YAML / TOML unquoted. The HMAC
        // input is raw bytes regardless of which alphabet encoded them.
        String trimmed = raw.trim();
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(trimmed);
        } catch (IllegalArgumentException primary) {
            try {
                decoded = Base64.getUrlDecoder().decode(trimmed);
            } catch (IllegalArgumentException urlSafe) {
                throw new NetworkConfigException(
                        "network.secretEnv='" + envVarName + "' is not valid Base64"
                                + " (tried standard and URL-safe alphabets): "
                                + primary.getMessage());
            }
        }
        if (decoded.length < MIN_SECRET_BYTES) {
            throw new NetworkConfigException(
                    "network.secretEnv='" + envVarName + "' decoded to " + decoded.length
                            + " bytes; rtp-proxy-ADR-010 requires >= " + MIN_SECRET_BYTES
                            + " bytes (256 bits) of key material.");
        }
        if (minSchemaVersion > currentSchemaVersion) {
            throw new NetworkConfigException(
                    "minSchemaVersion (" + minSchemaVersion + ") > currentSchemaVersion ("
                            + currentSchemaVersion + ")");
        }
        return new HmacVerifier(decoded, minSchemaVersion, currentSchemaVersion);
    }

    /**
     * Test-only factory accepting raw secret bytes. Production code shall
     * use {@link #loadFromEnv(String, int, int)}.
     *
     * @param secret               raw secret bytes (>= 32)
     * @param minSchemaVersion     minimum acceptable schema version
     * @param currentSchemaVersion current schema version
     * @return a configured verifier
     */
    public static HmacVerifier forTesting(byte[] secret,
                                          int minSchemaVersion,
                                          int currentSchemaVersion) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("secret must be >= " + MIN_SECRET_BYTES + " bytes");
        }
        return new HmacVerifier(secret.clone(), minSchemaVersion, currentSchemaVersion);
    }

    /**
     * Sign the canonical payload bytes with the configured secret.
     *
     * @param schemaVersion   wire schema version of the payload being signed
     * @param canonicalPayload canonical payload string (UTF-8 encoded for the HMAC input)
     * @return lowercase hexadecimal HMAC string (64 chars)
     */
    public String sign(int schemaVersion, String canonicalPayload) {
        Objects.requireNonNull(canonicalPayload, "canonicalPayload");
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update(Integer.toString(schemaVersion).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '|');
            mac.update(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (Exception e) {
            // HmacSHA256 is in every JRE; failure here is fatal misconfiguration.
            throw new IllegalStateException("HMAC-SHA-256 unavailable: " + e.getMessage(), e);
        }
    }

    /**
     * Constant-time verify of the supplied hex HMAC against a freshly-signed
     * value over the same canonical payload. Rejects on any of:
     *
     * <ul>
     *   <li>{@code hmacHex} is null, empty, or not valid hex</li>
     *   <li>{@code schemaVersion} is outside [{@code minSchemaVersion},
     *       {@code currentSchemaVersion}]</li>
     *   <li>computed HMAC does not match {@code hmacHex} under
     *       {@link MessageDigest#isEqual(byte[], byte[])}</li>
     * </ul>
     *
     * @param schemaVersion    wire schema version carried on the payload
     * @param canonicalPayload canonical payload string
     * @param hmacHex          hex-encoded HMAC carried alongside the payload
     * @return {@code true} iff all checks pass
     */
    public boolean verify(int schemaVersion, String canonicalPayload, String hmacHex) {
        if (hmacHex == null || hmacHex.isEmpty()) return false;
        if (schemaVersion < minSchemaVersion || schemaVersion > currentSchemaVersion) return false;
        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(hmacHex);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] expected;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update(Integer.toString(schemaVersion).getBytes(StandardCharsets.UTF_8));
            mac.update((byte) '|');
            mac.update(canonicalPayload.getBytes(StandardCharsets.UTF_8));
            expected = mac.doFinal();
        } catch (Exception e) {
            return false;
        }
        return MessageDigest.isEqual(expected, supplied);
    }

    /** Minimum schema version this verifier will accept on inbound payloads. */
    public int minSchemaVersion() { return minSchemaVersion; }

    /** Schema version this verifier publishes with. */
    public int currentSchemaVersion() { return currentSchemaVersion; }

    /**
     * Zero the in-memory secret bytes per rtp-proxy-ADR-010 §Key Material.
     * Idempotent: a second call is a no-op (the secret is already zero).
     * After {@code close()}, subsequent {@link #sign} / {@link #verify} calls
     * compute against the zero-filled buffer; callers must drop their
     * verifier reference at shutdown rather than rely on the zeroized
     * instance to "fail closed".
     */
    @Override
    public void close() {
        java.util.Arrays.fill(secret, (byte) 0);
    }
}
