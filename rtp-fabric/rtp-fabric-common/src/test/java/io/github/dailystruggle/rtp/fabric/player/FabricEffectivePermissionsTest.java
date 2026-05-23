package io.github.dailystruggle.rtp.fabric.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link FabricEffectivePermissionsResolver} against the formal {@code effective(player)}
 * set defined in {@code rtp-fabric-ADR-011-effective-permissions-enumeration.md}.
 *
 * <p>The LuckPerms-Fabric API jar is not on the test classpath; {@link LuckPermsFabricEnumerator}
 * therefore short-circuits to an empty set in every test below, which is exactly the
 * "LP absent" fallback path the ADR documents. The LP-present path is covered by
 * integration tests in a downstream module that ships LP on its test classpath.
 *
 * <p>{@code effects-api} is also off the test classpath in this module, so the
 * {@code rtp.effect.*} probe contributes nothing. This is documented and intentional:
 * the test pins the on-event probe + DEFAULT_TRUE + op-grants branch, which is the
 * Fabric-only logic this ADR introduces. Effect-probe coverage lives in
 * {@code rtp-plugin}'s effects integration tests.
 */
class FabricEffectivePermissionsTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** Predicate that grants only nodes listed in {@code granted}. */
    private static Predicate<String> grants(String... granted) {
        Set<String> set = Set.of(granted);
        return set::contains;
    }

    @Nested
    @DisplayName("Group A - LP absent, non-op")
    class GroupA_LpAbsentNonOp {

        @Test
        @DisplayName("Empty grants -> only DEFAULT_TRUE")
        void emptyGrants() {
            Set<String> out = FabricEffectivePermissionsResolver.resolve(PLAYER, false, p -> false);
            assertTrue(out.contains("rtp.see"));
            assertTrue(out.contains("rtp.use"));
            assertFalse(out.contains("rtp.onevent.firstjoin"));
            assertFalse(out.contains("rtp.onevent.join"));
        }

        @Test
        @DisplayName("perms-api grants rtp.onevent.firstjoin -> included")
        void onEventGrant() {
            Set<String> out = FabricEffectivePermissionsResolver.resolve(
                    PLAYER, false, grants("rtp.onevent.firstjoin"));
            assertTrue(out.contains("rtp.onevent.firstjoin"));
            assertFalse(out.contains("rtp.onevent.join"));
            assertFalse(out.contains("rtp.onevent.respawn"));
        }

        @Test
        @DisplayName("Numeric tail nodes are NOT auto-discovered without LP")
        void numericTailAbsent() {
            // Even if the predicate would grant rtp.delay.300, the resolver does not
            // probe numeric tails (open-ended namespace) - it relies on LP enumeration.
            Set<String> out = FabricEffectivePermissionsResolver.resolve(
                    PLAYER, false, grants("rtp.delay.300", "rtp.cooldown.60"));
            assertFalse(out.contains("rtp.delay.300"));
            assertFalse(out.contains("rtp.cooldown.60"));
        }
    }

    @Nested
    @DisplayName("Group C - op, LP absent")
    class GroupC_OpLpAbsent {

        @Test
        @DisplayName("Op -> OP_CLOSED_NAMESPACE_GRANTS (all rtp.onevent.*)")
        void opGetsAllOnEvent() {
            Set<String> out = FabricEffectivePermissionsResolver.resolve(PLAYER, true, p -> false);
            assertTrue(out.contains("rtp.see"));
            assertTrue(out.contains("rtp.use"));
            for (String e : FabricOnEventPermissions.EVENT_NAMES) {
                assertTrue(out.contains("rtp.onevent." + e), "missing rtp.onevent." + e);
            }
        }

        @Test
        @DisplayName("Op does NOT auto-receive numeric-tail nodes")
        void opNoNumericTail() {
            Set<String> out = FabricEffectivePermissionsResolver.resolve(PLAYER, true, p -> false);
            assertFalse(out.contains("rtp.delay.0"));
            assertFalse(out.contains("rtp.cooldown.0"));
        }
    }

    @Nested
    @DisplayName("Group D - degenerate inputs")
    class GroupD_Degenerate {

        @Test
        @DisplayName("null UUID -> empty set, no throw")
        void nullUuid() {
            Set<String> out = FabricEffectivePermissionsResolver.resolve(null, false, p -> true);
            assertEquals(Set.of(), out);
        }

        @Test
        @DisplayName("null predicate -> still returns DEFAULT_TRUE (+ op grants if op)")
        void nullPredicate() {
            Set<String> out = FabricEffectivePermissionsResolver.resolve(PLAYER, false, null);
            assertTrue(out.contains("rtp.see"));
            assertTrue(out.contains("rtp.use"));
            // No on-event probe possible without predicate, no op grants -> nothing else
            for (String e : FabricOnEventPermissions.EVENT_NAMES) {
                assertFalse(out.contains("rtp.onevent." + e));
            }
        }

        @Test
        @DisplayName("Predicate that throws -> not propagated")
        void predicateThrows() {
            Set<String> out = FabricEffectivePermissionsResolver.resolve(
                    PLAYER, false, p -> { throw new RuntimeException("simulated"); });
            // Still gets DEFAULT_TRUE; predicate exceptions are swallowed per resolver contract.
            assertTrue(out.contains("rtp.see"));
        }
    }

    @Nested
    @DisplayName("Console resolver")
    class Console {

        @Test
        @DisplayName("Console gets DEFAULT_TRUE + all OP_GRANTS, no numeric tails")
        void consoleGrants() {
            Set<String> out = FabricEffectivePermissionsResolver.resolveConsole();
            assertTrue(out.contains("rtp.see"));
            assertTrue(out.contains("rtp.use"));
            for (String e : FabricOnEventPermissions.EVENT_NAMES) {
                assertTrue(out.contains("rtp.onevent." + e), "console missing rtp.onevent." + e);
            }
            assertFalse(out.contains("rtp.delay.0"));
            assertFalse(out.contains("rtp.cooldown.0"));
        }
    }

    @Nested
    @DisplayName("LuckPerms reflective enumerator (LP absent on test classpath)")
    class LpEnumerator {

        @Test
        @DisplayName("isAvailable() returns false when LP API jar is not on classpath")
        void notAvailable() {
            assertFalse(LuckPermsFabricEnumerator.isAvailable());
        }

        @Test
        @DisplayName("grantedNodes() returns empty set, no throw, when LP absent")
        void grantedNodesEmpty() {
            assertEquals(Set.of(), LuckPermsFabricEnumerator.grantedNodes(PLAYER));
            assertEquals(Set.of(), LuckPermsFabricEnumerator.grantedNodes(null));
        }
    }
}
