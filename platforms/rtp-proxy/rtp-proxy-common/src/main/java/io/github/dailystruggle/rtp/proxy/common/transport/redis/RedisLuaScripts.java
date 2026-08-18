package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisNoScriptException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

/**
 * Loader, SHA1-verifier, and EVALSHA dispatcher for the
 * atomic-claim/release Lua scripts.
 *
 * <p>Refines rtp-proxy-ADR-005 section "Lua script SHA1 verify-on-load" (Amendment
 * 2026-05-18): each script under {@code /redis/<name>.lua} ships with a
 * checked-in {@code .sha1} sidecar; the loader computes the SHA1 of the
 * LF-normalized script bytes and compares against the sidecar. Mismatch is a
 * <em>build-time defect</em> (refuse to enable, log WARNING via the throwing
 * constructor) rather than a runtime fallback. This pins script identity so a
 * whitespace edit cannot invalidate the cached Redis SHA at runtime.</p>
 *
 * <p>LF normalization happens both for hashing and for the bytes sent to
 * {@code SCRIPT LOAD}, so Windows checkouts (CRLF) and POSIX checkouts (LF)
 * produce the same SHA1 and the same Redis-cached script.</p>
 */
final class RedisLuaScripts {

    private final String name;
    private final String scriptBody;
    private final String sha1;

    private RedisLuaScripts(String name, String scriptBody, String sha1) {
        this.name = name;
        this.scriptBody = scriptBody;
        this.sha1 = sha1;
    }

    /**
     * Loads {@code /redis/<name>.lua} and {@code /redis/<name>.lua.sha1} from
     * the classpath, verifies the LF-normalized script body hashes to the
     * sidecar value, and returns a ready-to-dispatch handle.
     *
     * @throws IllegalStateException if either resource is missing or the
     *                               computed SHA1 disagrees with the sidecar.
     */
    static RedisLuaScripts load(String name) {
        Objects.requireNonNull(name, "name");
        String body = readClasspath("/redis/" + name + ".lua");
        String expected = readClasspath("/redis/" + name + ".lua.sha1").trim().toLowerCase();
        String actual = sha1Hex(body.getBytes(StandardCharsets.UTF_8));
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "RedisLuaScripts: SHA1 mismatch for " + name + ".lua "
                            + "(expected " + expected + " from sidecar, computed " + actual + " "
                            + "from LF-normalized script bytes). Regenerate the sidecar after "
                            + "editing the script (rtp-proxy-ADR-005 Amendment 2026-05-18).");
        }
        return new RedisLuaScripts(name, body, actual);
    }

    /** Pre-loads the script into Redis's script cache and returns the cached SHA1. */
    String scriptLoad(JedisPool pool) {
        try (Jedis j = pool.getResource()) {
            String cachedSha = j.scriptLoad(scriptBody);
            if (!sha1.equalsIgnoreCase(cachedSha)) {
                throw new IllegalStateException(
                        "RedisLuaScripts: Redis returned SHA1 " + cachedSha
                                + " but local SHA1 is " + sha1
                                + " (script bytes diverged from sidecar; refuse to enable).");
            }
            return cachedSha;
        }
    }

    /**
     * Dispatches the script via {@code EVALSHA}, falling back to {@code EVAL}
     * if Redis evicted the script from its cache (NOSCRIPT). The fallback also
     * re-primes the cache for the next call.
     */
    Object evalsha(Jedis j, List<String> keys, List<String> args) {
        try {
            return j.evalsha(sha1, keys, args);
        } catch (JedisNoScriptException nse) {
            // Cache eviction (Redis FLUSH, restart, or replica failover). Re-load and retry.
            j.scriptLoad(scriptBody);
            return j.evalsha(sha1, keys, args);
        }
    }

    String name() { return name; }
    String sha1() { return sha1; }
    String body() { return scriptBody; }

    // ---- helpers ---------------------------------------------------------

    private static String readClasspath(String path) {
        try (InputStream in = RedisLuaScripts.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(
                        "RedisLuaScripts: classpath resource not found: " + path);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            // LF-normalize so Windows (CRLF) and POSIX (LF) checkouts hash identically
            // and Redis stores identical bytes under identical SHA1s.
            return out.toString(StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException e) {
            throw new IllegalStateException(
                    "RedisLuaScripts: failed to read " + path + ": " + e.getMessage(), e);
        }
    }

    private static String sha1Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable on this JVM", e);
        }
    }
}
