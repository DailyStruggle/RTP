package io.github.dailystruggle.rtp.api.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.BiomeSampleCapability;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the delegating and value-returning default methods on
 * {@link RTPServerAccessor} (platform-version gating, {@code sendMessage} /
 * {@code announce} overload fan-out, and the conservative platform-neutral
 * defaults). A recording dynamic proxy stands in for a platform adapter: default
 * methods run for real, abstract methods are captured so delegation can be
 * asserted without a live server.
 */
class ServerAccessorDefaultsTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** One recorded invocation of an abstract method routed through the proxy. */
    private record Call(String name, Object[] args) {}

    /** Recording handler: runs default methods, captures abstract ones. */
    private static final class Recorder implements InvocationHandler {
        final List<Call> calls = new ArrayList<>();
        String platform = "paper";
        Integer intVersion = 21;
        Function<UUID, RTPCommandSender> senderFn = u -> null;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "getPlatform":
                    return platform;
                case "getServerIntVersion":
                    return intVersion;
                case "getSender":
                    return senderFn.apply((UUID) args[0]);
                case "toString":
                    return "RecorderStub";
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    break;
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            calls.add(new Call(name, args));
            return defaultReturn(method.getReturnType());
        }

        Call last(String name) {
            for (int i = calls.size() - 1; i >= 0; i--) {
                if (calls.get(i).name().equals(name)) {
                    return calls.get(i);
                }
            }
            throw new AssertionError("no recorded call to " + name);
        }
    }

    private static Object defaultReturn(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        return null;
    }

    private static RTPServerAccessor proxy(Recorder rec) {
        return (RTPServerAccessor) Proxy.newProxyInstance(
            ServerAccessorDefaultsTest.class.getClassLoader(),
            new Class<?>[] {RTPServerAccessor.class},
            rec);
    }

    // --- getPlatformFamily ---

    @Test
    void platformFamilyMapsKnownPlatforms() {
        Recorder rec = new Recorder();
        RTPServerAccessor acc = proxy(rec);

        rec.platform = "fabric";
        assertSame(PlatformFamily.FABRIC, acc.getPlatformFamily());
        rec.platform = "neoforge";
        assertSame(PlatformFamily.NEOFORGE, acc.getPlatformFamily());
        rec.platform = "Paper";
        assertSame(PlatformFamily.BUKKIT, acc.getPlatformFamily());
        rec.platform = "Folia";
        assertSame(PlatformFamily.BUKKIT, acc.getPlatformFamily());
        rec.platform = "Spigot";
        assertSame(PlatformFamily.BUKKIT, acc.getPlatformFamily());
    }

    @Test
    void platformFamilyUnknownLogsWarning() {
        Recorder rec = new Recorder();
        RTPServerAccessor acc = proxy(rec);

        rec.platform = "sponge";
        assertSame(PlatformFamily.UNKNOWN, acc.getPlatformFamily());
        rec.platform = null;
        assertSame(PlatformFamily.UNKNOWN, acc.getPlatformFamily());
        // Unrecognised platform must be logged (S-004 auditing).
        assertEquals(2, rec.calls.stream().filter(c -> c.name().equals("log")).count());
    }

    // --- version / compatibility gates ---

    @Test
    void platformFamilyPredicate() {
        Recorder rec = new Recorder();
        RTPServerAccessor acc = proxy(rec);
        rec.platform = "paper";
        assertTrue(acc.isPlatformFamily(PlatformFamily.BUKKIT));
        assertFalse(acc.isPlatformFamily(PlatformFamily.FABRIC));
        assertFalse(acc.isPlatformFamily(null));
    }

    @Test
    void versionBoundsFailClosedWhenUnknown() {
        Recorder rec = new Recorder();
        RTPServerAccessor acc = proxy(rec);

        rec.intVersion = 21;
        assertTrue(acc.isServerVersionAtLeast(21));
        assertTrue(acc.isServerVersionAtLeast(20));
        assertFalse(acc.isServerVersionAtLeast(26));
        assertTrue(acc.isServerVersionAtMost(21));
        assertTrue(acc.isServerVersionAtMost(26));
        assertFalse(acc.isServerVersionAtMost(20));

        rec.intVersion = null;
        assertFalse(acc.isServerVersionAtLeast(1));
        assertFalse(acc.isServerVersionAtMost(99));
    }

    @Test
    void isCompatibleCoversAllBranches() {
        Recorder rec = new Recorder();
        RTPServerAccessor acc = proxy(rec);
        rec.platform = "paper";
        rec.intVersion = 21;

        // Family mismatch short-circuits.
        assertFalse(acc.isCompatible(PlatformFamily.FABRIC, 0, Integer.MAX_VALUE));
        // Null family + no version bounds -> compatible.
        assertTrue(acc.isCompatible(null, 0, Integer.MAX_VALUE));
        // Matching family, in range.
        assertTrue(acc.isCompatible(PlatformFamily.BUKKIT, 20, 26));
        // Below floor.
        assertFalse(acc.isCompatible(PlatformFamily.BUKKIT, 26, Integer.MAX_VALUE));
        // Above ceiling.
        assertFalse(acc.isCompatible(PlatformFamily.BUKKIT, 0, 20));
        // Version bounded but version unknown -> fail closed.
        rec.intVersion = null;
        assertFalse(acc.isCompatible(null, 20, 26));
    }

    // --- sendMessage delegating overloads ---

    @Test
    void sendMessageOverloadsDelegateWithNullTag() {
        Recorder rec = new Recorder();
        RTPServerAccessor acc = proxy(rec);

        acc.sendMessage(A, CommandMessages.menuInvalid);
        Call c1 = rec.last("sendMessage");
        assertEquals(3, c1.args().length);
        assertNull(c1.args()[2]);

        acc.sendMessage(A, B, CommandMessages.menuInvalid);
        Call c2 = rec.last("sendMessage");
        assertEquals(4, c2.args().length);
        assertNull(c2.args()[3]);

        acc.sendMessage(A, "hello");
        Call c3 = rec.last("sendMessage");
        assertEquals(3, c3.args().length);
        assertEquals("hello", c3.args()[1]);
        assertNull(c3.args()[2]);

        acc.sendMessage(A, B, "hi there");
        Call c4 = rec.last("sendMessage");
        assertEquals(4, c4.args().length);
        assertEquals("hi there", c4.args()[2]);
        assertNull(c4.args()[3]);
    }

    @Test
    void richSendMessageAndRunCommandOverloadsDelegate() {
        Recorder rec = new Recorder();
        RTPServerAccessor acc = proxy(rec);
        RTPCommandSender target = sender();

        acc.sendMessage(target, "msg", "hover", "click");
        Call c = rec.last("sendMessage");
        assertEquals(5, c.args().length);
        assertEquals("click", c.args()[3]);
        assertNull(c.args()[4]);

        acc.sendMessageWithRunCommand(target, "msg", "hover", "/rtp");
        Call run = rec.last("sendMessage");
        assertEquals("/rtp", run.args()[3]);
        assertNull(run.args()[4]);

        acc.sendMessageWithRunCommand(target, "msg", "hover", "/rtp", "tag");
        Call runTag = rec.last("sendMessage");
        assertEquals("tag", runTag.args()[4]);
    }

    @Test
    void announceOverloadDelegatesWithNullTag() {
        Recorder rec = new Recorder();
        RTPServerAccessor acc = proxy(rec);

        acc.announce("broadcast", "rtp.see");
        Call c = rec.last("announce");
        assertEquals(3, c.args().length);
        assertEquals("broadcast", c.args()[0]);
        assertEquals("rtp.see", c.args()[1]);
        assertNull(c.args()[2]);
    }

    // --- conservative platform-neutral defaults ---

    @Test
    void conservativeDefaults() {
        Recorder rec = new Recorder();
        RTPServerAccessor acc = proxy(rec);

        assertTrue(acc.getOnlinePlayerNames().isEmpty());
        assertSame(NoopPlayerLifecycleHook.INSTANCE, acc.getPlayerLifecycleHook());
        assertSame(BiomeSampleCapability.GENERATE_REQUIRED, acc.biomeSampleCapability(null));
        assertNull(acc.sampleBiome(null, 0, 0, 0));
        Map<String, ?> tags = acc.blockTagSnapshot();
        assertTrue(tags.isEmpty());

        // No-op defaults must run without throwing.
        acc.rebuildBlockTagSnapshot();
        acc.releaseAllChunkTickets();
        acc.updateProgressBars(Map.of("bar", new ProgressBar("t", 0.1, null)));
        acc.clearProgressBars();
    }

    // --- active-task registry (registerAction / removeAction / getTaskSnapshot) ---

    @Test
    void activeTaskRegistryRoundTrips() {
        Recorder rec = new Recorder();
        RTPServerAccessor acc = proxy(rec);

        String id = "test-" + UUID.randomUUID();
        TrackedRTPTask task = new TrackedRTPTask(new RTPRunnable(() -> {}), id);
        try {
            acc.registerAction(task);
            Map<String, Long> snap = acc.getTaskSnapshot();
            assertTrue(snap.containsKey(id));
            // Age is the wall-clock delta since the task was queued; never negative.
            assertTrue(snap.get(id) >= 0L);
        } finally {
            acc.removeAction(id);
        }
        assertFalse(acc.getTaskSnapshot().containsKey(id));
    }

    private static RTPCommandSender sender() {
        return (RTPCommandSender) Proxy.newProxyInstance(
            ServerAccessorDefaultsTest.class.getClassLoader(),
            new Class<?>[] {RTPCommandSender.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "toString" -> "StubSender";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> {
                    if (method.isDefault()) {
                        yield InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    yield null;
                }
            });
    }
}
