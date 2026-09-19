package io.github.dailystruggle.rtp.api.server;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surface coverage for the platform-neutral {@code server} value types and lifecycle hooks
 * (ADR-049): {@link PlatformFamily}, {@link ProgressBar}, {@link NoopPlayerLifecycleHook}, and
 * {@link DispatchingPlayerLifecycleHook} fan-out with per-subscriber exception isolation.
 */
class ServerHooksAndValueTest {

    // --- PlatformFamily ---

    @Test
    void platformFamilyRoundTrips() {
        PlatformFamily[] values = PlatformFamily.values();
        assertEquals(4, values.length);
        for (PlatformFamily f : values) {
            assertSame(f, PlatformFamily.valueOf(f.name()));
        }
        assertThrows(IllegalArgumentException.class, () -> PlatformFamily.valueOf("PLASMA"));
    }

    // --- ProgressBar ---

    @Test
    void progressBarExposesFields() {
        ProgressBar bar = new ProgressBar("&aLoading", 0.5, "rtp.see");
        assertEquals("&aLoading", bar.title());
        assertEquals(0.5, bar.progress());
        assertEquals("rtp.see", bar.viewerPermission());
        assertTrue(bar.toString().contains("Loading"));
    }

    @Test
    void progressBarNullTitleBecomesEmpty() {
        ProgressBar bar = new ProgressBar(null, 0.0, null);
        assertEquals("", bar.title());
        assertNull(bar.viewerPermission());
    }

    @Test
    void progressBarValueSemantics() {
        ProgressBar a = new ProgressBar("t", 0.25, "perm");
        ProgressBar b = new ProgressBar("t", 0.25, "perm");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a, a);
        assertNotEquals(a, new ProgressBar("t", 0.30, "perm"));
        assertNotEquals(a, new ProgressBar("u", 0.25, "perm"));
        assertNotEquals(a, new ProgressBar("t", 0.25, "other"));
        assertNotEquals(a, null);
        assertNotEquals(a, "t");
    }

    // --- NoopPlayerLifecycleHook ---

    @Test
    void noopHookReturnsClosableNoops() throws Exception {
        NoopPlayerLifecycleHook hook = NoopPlayerLifecycleHook.INSTANCE;
        AutoCloseable join = hook.onPlayerJoin(id -> { throw new AssertionError("must not fire"); });
        AutoCloseable quit = hook.onPlayerQuit(id -> { throw new AssertionError("must not fire"); });
        // Closing a no-op subscription must be safe and idempotent.
        join.close();
        quit.close();
        assertSame(hook, NoopPlayerLifecycleHook.INSTANCE);
    }

    // --- DispatchingPlayerLifecycleHook ---

    @Test
    void dispatchingHookFansOutJoinAndQuit() {
        DispatchingPlayerLifecycleHook hook = new DispatchingPlayerLifecycleHook();
        assertFalse(hook.hasSubscribers());

        AtomicReference<UUID> joined = new AtomicReference<>();
        AtomicReference<UUID> quit = new AtomicReference<>();
        hook.onPlayerJoin(joined::set);
        hook.onPlayerQuit(quit::set);
        assertTrue(hook.hasSubscribers());

        UUID id = UUID.randomUUID();
        hook.fireJoin(id);
        hook.fireQuit(id);
        assertEquals(id, joined.get());
        assertEquals(id, quit.get());

        // null UUIDs are ignored, not dispatched.
        joined.set(null);
        hook.fireJoin(null);
        assertNull(joined.get());
    }

    @Test
    void dispatchingHookUnsubscribeRemovesHandler() throws Exception {
        DispatchingPlayerLifecycleHook hook = new DispatchingPlayerLifecycleHook();
        AtomicInteger count = new AtomicInteger();
        AutoCloseable sub = hook.onPlayerJoin(id -> count.incrementAndGet());
        hook.fireJoin(UUID.randomUUID());
        sub.close();
        hook.fireJoin(UUID.randomUUID());
        assertEquals(1, count.get());
        assertFalse(hook.hasSubscribers());
    }

    @Test
    void dispatchingHookIsolatesFaultySubscriber() {
        DispatchingPlayerLifecycleHook hook = new DispatchingPlayerLifecycleHook();
        AtomicInteger good = new AtomicInteger();
        Consumer<UUID> boom = id -> { throw new RuntimeException("boom"); };
        hook.onPlayerJoin(boom);
        hook.onPlayerJoin(id -> good.incrementAndGet());
        hook.onPlayerQuit(boom);
        hook.onPlayerQuit(id -> good.incrementAndGet());

        // A faulty subscriber must not break fan-out to the healthy one.
        hook.fireJoin(UUID.randomUUID());
        hook.fireQuit(UUID.randomUUID());
        assertEquals(2, good.get());
    }

    @Test
    void dispatchingHookRejectsNullHandlers() {
        DispatchingPlayerLifecycleHook hook = new DispatchingPlayerLifecycleHook();
        assertThrows(IllegalArgumentException.class, () -> hook.onPlayerJoin(null));
        assertThrows(IllegalArgumentException.class, () -> hook.onPlayerQuit(null));
    }
}
