package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline guard for the Phase 2e-Redis A2 Lua script loader.
 *
 * <p>Verifies that the SHA1 sidecars under
 * {@code rtp-proxy-common/src/main/resources/redis/} agree with the LF-normalized
 * script bytes. A whitespace edit to either script without regenerating its
 * sidecar fails this test before it can poison the Redis script cache at
 * runtime (rtp-proxy-ADR-005 Amendment 2026-05-18).</p>
 *
 * <p>Pure classpath test - no Jedis pool, no Redis server.</p>
 */
final class RedisLuaScriptsTest {

    @Test
    @DisplayName("claim.lua sidecar matches LF-normalized script bytes")
    void claimLuaSidecarMatches() {
        RedisLuaScripts s = RedisLuaScripts.load("claim");
        assertNotNull(s.body());
        assertTrue(s.body().length() > 0);
        assertEquals(40, s.sha1().length(), "SHA1 hex must be 40 chars");
        assertTrue(s.sha1().matches("[0-9a-f]{40}"), "SHA1 hex must be lowercase");
        assertEquals("claim", s.name());
        // LF normalization invariant: the body must not contain a CR.
        assertTrue(s.body().indexOf('\r') < 0, "body must be LF-normalized");
    }

    @Test
    @DisplayName("release.lua sidecar matches LF-normalized script bytes")
    void releaseLuaSidecarMatches() {
        RedisLuaScripts s = RedisLuaScripts.load("release");
        assertNotNull(s.body());
        assertTrue(s.body().length() > 0);
        assertEquals(40, s.sha1().length());
        assertTrue(s.sha1().matches("[0-9a-f]{40}"));
        assertEquals("release", s.name());
        assertTrue(s.body().indexOf('\r') < 0);
    }

    @Test
    @DisplayName("reap.lua sidecar matches LF-normalized script bytes")
    void reapLuaSidecarMatches() {
        RedisLuaScripts s = RedisLuaScripts.load("reap");
        assertNotNull(s.body());
        assertTrue(s.body().length() > 0);
        assertEquals(40, s.sha1().length());
        assertTrue(s.sha1().matches("[0-9a-f]{40}"));
        assertEquals("reap", s.name());
        assertTrue(s.body().indexOf('\r') < 0);
    }

    @Test
    @DisplayName("enqueue_batch.lua sidecar matches LF-normalized script bytes")
    void enqueueBatchLuaSidecarMatches() {
        RedisLuaScripts s = RedisLuaScripts.load("enqueue_batch");
        assertNotNull(s.body());
        assertTrue(s.body().length() > 0);
        assertEquals(40, s.sha1().length());
        assertTrue(s.sha1().matches("[0-9a-f]{40}"));
        assertEquals("enqueue_batch", s.name());
        assertTrue(s.body().indexOf('\r') < 0);
    }

    @Test
    @DisplayName("pollStatus.lua sidecar matches LF-normalized script bytes")
    void pollStatusLuaSidecarMatches() {
        RedisLuaScripts s = RedisLuaScripts.load("pollStatus");
        assertNotNull(s.body());
        assertTrue(s.body().length() > 0);
        assertEquals(40, s.sha1().length());
        assertTrue(s.sha1().matches("[0-9a-f]{40}"));
        assertEquals("pollStatus", s.name());
        assertTrue(s.body().indexOf('\r') < 0);
    }

    @Test
    @DisplayName("dequeueReady.lua sidecar matches LF-normalized script bytes")
    void dequeueReadyLuaSidecarMatches() {
        RedisLuaScripts s = RedisLuaScripts.load("dequeueReady");
        assertNotNull(s.body());
        assertTrue(s.body().length() > 0);
        assertEquals(40, s.sha1().length());
        assertTrue(s.sha1().matches("[0-9a-f]{40}"));
        assertEquals("dequeueReady", s.name());
        assertTrue(s.body().indexOf('\r') < 0);
    }

    @Test
    @DisplayName("transition.lua sidecar matches LF-normalized script bytes")
    void transitionLuaSidecarMatches() {
        RedisLuaScripts s = RedisLuaScripts.load("transition");
        assertNotNull(s.body());
        assertTrue(s.body().length() > 0);
        assertEquals(40, s.sha1().length());
        assertTrue(s.sha1().matches("[0-9a-f]{40}"));
        assertEquals("transition", s.name());
        assertTrue(s.body().indexOf('\r') < 0);
    }

    @Test
    @DisplayName("dequeueReadyOwned.lua sidecar matches LF-normalized script bytes")
    void dequeueReadyOwnedLuaSidecarMatches() {
        RedisLuaScripts s = RedisLuaScripts.load("dequeueReadyOwned");
        assertNotNull(s.body());
        assertTrue(s.body().length() > 0);
        assertEquals(40, s.sha1().length());
        assertTrue(s.sha1().matches("[0-9a-f]{40}"));
        assertEquals("dequeueReadyOwned", s.name());
        assertTrue(s.body().indexOf('\r') < 0);
    }

    @Test
    @DisplayName("load() refuses unknown script names with a clear message")
    void loadMissingScriptFails() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> RedisLuaScripts.load("does-not-exist"));
        assertTrue(ex.getMessage().contains("does-not-exist"),
                "error must name the missing script");
    }
}
