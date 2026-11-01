package io.github.dailystruggle.rtp.proxy.common.transport.redis;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * No-Redis smoke test that the six (seven, counting {@code waitlist_peek})
 * waitlist Lua scripts ship in the resources/redis/ classpath with valid
 * SHA1 sidecars matching the LF-normalized script bytes.
 *
 * <p>Reuses {@link RedisLuaScripts}'s package-private {@code load(name)}
 * which throws {@link IllegalStateException} on SHA1 mismatch; a green
 * test here means an editor that converted CRLF on a Windows checkout
 * has not silently invalidated the sidecar.</p>
 *
 * <p>Regression guard for
 * {@code rtp-proxy-ADR-015-shared-network-waitlist-and-dynamic-batched-dispatch.md}.</p>
 */
class RedisNetworkWaitlistScriptLoadTest {

    private static Object loadViaReflection(String name) throws Exception {
        Method m = RedisLuaScripts.class.getDeclaredMethod("load", String.class);
        m.setAccessible(true);
        return m.invoke(null, name);
    }

    private static String sha1Of(Object handle) throws Exception {
        Method m = handle.getClass().getDeclaredMethod("sha1");
        m.setAccessible(true);
        return (String) m.invoke(handle);
    }

    @Test
    void all_seven_scripts_load_with_valid_sha1_sidecars() throws Exception {
        String[] names = {
                "waitlist_enrol",
                "waitlist_peek",
                "waitlist_drain_batch",
                "waitlist_position",
                "waitlist_remove_uuid",
                "waitlist_reap",
                "waitlist_refresh_ttl",
        };
        for (String n : names) {
            Object handle = loadViaReflection(n);
            assertNotNull(handle, "script handle for " + n);
            String sha = sha1Of(handle);
            assertEquals(40, sha.length(),
                    "SHA1 must be 40 hex chars for " + n + " (got '" + sha + "')");
        }
    }
}
