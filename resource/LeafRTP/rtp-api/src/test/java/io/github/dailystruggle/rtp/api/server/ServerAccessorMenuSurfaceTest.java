package io.github.dailystruggle.rtp.api.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.menu.MenuPlatformView;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@code menu*} default methods on {@link RTPServerAccessor} (ADR-048)
 * and the {@link MenuPlatformView#of} snapshot factory using a dynamic proxy stub.
 */
class ServerAccessorMenuSurfaceTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Builds a stub accessor that routes {@code getSender(uuid)} through {@code senderFn}. */
    private static RTPServerAccessor stub(Function<UUID, RTPCommandSender> senderFn) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("getSender".equals(method.getName())
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == UUID.class) {
                return senderFn.apply((UUID) args[0]);
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            throw new UnsupportedOperationException(
                "Stub does not implement " + method.getName());
        };
        return (RTPServerAccessor) Proxy.newProxyInstance(
            ServerAccessorMenuSurfaceTest.class.getClassLoader(),
            new Class<?>[]{RTPServerAccessor.class},
            handler);
    }

    /** Builds a stub {@link RTPCommandSender} whose {@code hasPermission} answers a {@link Predicate}. */
    private static RTPCommandSender sender(Predicate<String> hasPerm, Set<String> effective) {
        InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
            case "hasPermission" -> hasPerm.test((String) args[0]);
            case "getEffectivePermissions" -> effective;
            case "toString" -> "StubSender";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> {
                if (method.isDefault()) {
                    yield InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw new UnsupportedOperationException(method.getName());
            }
        };
        return (RTPCommandSender) Proxy.newProxyInstance(
            ServerAccessorMenuSurfaceTest.class.getClassLoader(),
            new Class<?>[]{RTPCommandSender.class},
            h);
    }

    @Nested
    @DisplayName("menuPermissionProbe default")
    class PermissionProbe {

        @Test
        @DisplayName("delegates to getSender(uuid).hasPermission(node) when sender resolves")
        void delegates_to_sender() {
            RTPServerAccessor acc = stub(uuid -> sender(n -> "rtp.use".equals(n), Set.of()));
            Predicate<String> probe = acc.menuPermissionProbe(PLAYER);
            assertNotNull(probe);
            assertTrue(probe.test("rtp.use"));
            assertFalse(probe.test("rtp.admin"));
        }

        @Test
        @DisplayName("conservative when sender is null (offline / unresolved player)")
        void returns_false_when_sender_null() {
            RTPServerAccessor acc = stub(uuid -> null);
            assertFalse(acc.menuPermissionProbe(PLAYER).test("rtp.use"));
        }

        @Test
        @DisplayName("conservative when player UUID is null")
        void returns_false_when_player_null() {
            RTPServerAccessor acc = stub(uuid -> sender(n -> true, Set.of()));
            assertFalse(acc.menuPermissionProbe(null).test("rtp.use"));
        }

        @Test
        @DisplayName("conservative when node is null")
        void returns_false_when_node_null() {
            RTPServerAccessor acc = stub(uuid -> sender(n -> true, Set.of()));
            assertFalse(acc.menuPermissionProbe(PLAYER).test(null));
        }

        @Test
        @DisplayName("swallows exceptions from the sender and returns false")
        void swallows_sender_throw() {
            RTPServerAccessor acc = stub(uuid -> {
                throw new RuntimeException("boom");
            });
            assertFalse(acc.menuPermissionProbe(PLAYER).test("rtp.use"));
        }
    }

    @Nested
    @DisplayName("menuEffectivePermissions default")
    class EffectivePermissions {

        @Test
        @DisplayName("returns the sender's set when sender resolves")
        void returns_sender_set() {
            Set<String> granted = new HashSet<>(Set.of("rtp.use", "rtp.see"));
            RTPServerAccessor acc = stub(uuid -> sender(n -> false, granted));
            assertEquals(granted, acc.menuEffectivePermissions(PLAYER));
        }

        @Test
        @DisplayName("returns empty set when sender is null")
        void empty_when_sender_null() {
            RTPServerAccessor acc = stub(uuid -> null);
            assertTrue(acc.menuEffectivePermissions(PLAYER).isEmpty());
        }

        @Test
        @DisplayName("returns empty set when player UUID is null")
        void empty_when_player_null() {
            RTPServerAccessor acc = stub(uuid -> sender(n -> true, Set.of("rtp.use")));
            assertTrue(acc.menuEffectivePermissions(null).isEmpty());
        }

        @Test
        @DisplayName("returns empty set when sender returns null")
        void empty_when_sender_returns_null() {
            RTPServerAccessor acc = stub(uuid -> sender(n -> false, null));
            assertTrue(acc.menuEffectivePermissions(PLAYER).isEmpty());
        }

        @Test
        @DisplayName("swallows exceptions from the sender and returns empty")
        void swallows_sender_throw() {
            RTPServerAccessor acc = stub(uuid -> {
                throw new RuntimeException("boom");
            });
            assertTrue(acc.menuEffectivePermissions(PLAYER).isEmpty());
        }
    }

    @Nested
    @DisplayName("menuLocale + menuRegionDescriptor defaults")
    class StringDefaults {

        @Test
        @DisplayName("menuLocale default is en_us")
        void locale_default_en_us() {
            assertEquals("en_us", stub(uuid -> null).menuLocale(PLAYER));
        }

        @Test
        @DisplayName("menuRegionDescriptor default is empty string")
        void region_default_empty() {
            assertEquals("", stub(uuid -> null).menuRegionDescriptor(PLAYER));
        }
    }

    @Nested
    @DisplayName("MenuPlatformView.of snapshot")
    class ViewSnapshot {

        @Test
        @DisplayName("captures all four fields from the accessor")
        void captures_four_fields() {
            Set<String> granted = Set.of("rtp.use");
            RTPServerAccessor acc = stub(uuid -> sender("rtp.use"::equals, granted));
            MenuPlatformView view = MenuPlatformView.of(acc, PLAYER);
            assertNotNull(view);
            assertTrue(view.hasPermission().test("rtp.use"));
            assertFalse(view.hasPermission().test("rtp.admin"));
            assertEquals(granted, view.effectivePermissions());
            assertEquals("en_us", view.locale());
            assertEquals("", view.regionDescriptor());
        }

        @Test
        @DisplayName("captures defaults verbatim when sender is null")
        void captures_defaults_when_sender_null() {
            MenuPlatformView view = MenuPlatformView.of(stub(uuid -> null), PLAYER);
            assertFalse(view.hasPermission().test("rtp.use"));
            assertTrue(view.effectivePermissions().isEmpty());
            assertEquals("en_us", view.locale());
            assertEquals("", view.regionDescriptor());
        }

        @Test
        @DisplayName("throws on null accessor")
        void rejects_null_accessor() {
            assertThrows(NullPointerException.class, () -> MenuPlatformView.of(null, PLAYER));
        }

        @Test
        @DisplayName("throws on null player UUID")
        void rejects_null_player() {
            assertThrows(NullPointerException.class,
                () -> MenuPlatformView.of(stub(uuid -> null), null));
        }

        @Test
        @DisplayName("canonical constructor rejects null components")
        void canonical_rejects_nulls() {
            assertThrows(NullPointerException.class,
                () -> new MenuPlatformView(null, Set.of(), "en_us", ""));
            assertThrows(NullPointerException.class,
                () -> new MenuPlatformView(n -> false, null, "en_us", ""));
            assertThrows(NullPointerException.class,
                () -> new MenuPlatformView(n -> false, Set.of(), null, ""));
            assertThrows(NullPointerException.class,
                () -> new MenuPlatformView(n -> false, Set.of(), "en_us", null));
        }

        @Test
        @DisplayName("snapshot is by-value: subsequent accessor changes do not leak in")
        void snapshot_is_by_value() {
            // The probe field holds the lambda returned at snapshot time; a fresh stub
            // built with a different policy must not affect the captured view.
            RTPServerAccessor first = stub(uuid -> sender(n -> true, Set.of("a")));
            MenuPlatformView view = MenuPlatformView.of(first, PLAYER);
            assertTrue(view.hasPermission().test("anything"));
            assertEquals(Set.of("a"), view.effectivePermissions());
            // Build a second accessor with the opposite policy; view stays the same.
            RTPServerAccessor second = stub(uuid -> sender(n -> false, Set.of("b")));
            MenuPlatformView other = MenuPlatformView.of(second, PLAYER);
            assertFalse(other.hasPermission().test("anything"));
            assertEquals(Set.of("b"), other.effectivePermissions());
            // Original view unaffected.
            assertTrue(view.hasPermission().test("anything"));
            assertEquals(Set.of("a"), view.effectivePermissions());
            assertSame(view.locale(), "en_us");
        }
    }
}
