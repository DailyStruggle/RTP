package io.github.dailystruggle.rtp.common.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitCodeNamespace;
import io.github.dailystruggle.bit.BitCodeRegistry;
import io.github.dailystruggle.bit.BitTier;
import java.lang.System.Logger.Level;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 BIT code release lock")
class RtpBitCodeLockTest {
    @Test
    void parsesAndRendersCanonicalNamespaceLocalRows() {
        List<RtpBitCodeLock.Entry> parsed = RtpBitCodeLock.parse("""
                # namespace id key defaultSeverity
                OTHER 1 OTHER_FIRST WARNING
                DEMO 1 DEMO_FIRST INFO
                """);

        assertEquals(List.of("DEMO:1", "OTHER:1"),
                parsed.stream().map(entry -> entry.namespace() + ":" + entry.id()).toList());
        assertEquals("# namespace id key defaultSeverity\nDEMO 1 DEMO_FIRST INFO\nOTHER 1 OTHER_FIRST WARNING\n",
                RtpBitCodeLock.render(parsed));
        assertEquals(parsed, RtpBitCodeLock.parse(RtpBitCodeLock.render(parsed)));
    }

    @Test
    void rejectsRenamesRenumberingAndUnretiredRemoval() {
        List<RtpBitCodeLock.Entry> lock = List.of(entry("DEMO", 1, "DEMO_STABLE", Level.WARNING));

        assertTrue(RtpBitCodeLock.violations(registry("DEMO", 1, "DEMO_RENAMED", Level.WARNING), lock)
                .stream().anyMatch(message -> message.contains("was DEMO_STABLE")));
        assertTrue(RtpBitCodeLock.violations(registry("DEMO", 2, "DEMO_STABLE", Level.WARNING), lock)
                .stream().anyMatch(message -> message.contains("moved from DEMO:1")));
        assertTrue(RtpBitCodeLock.violations(registry(), lock)
                .stream().anyMatch(message -> message.contains("missing")));
    }

    @Test
    void retirementPreservesLockedIdentityAndCannotBeAddedWithoutPreviousSeverity() {
        BitCodeRegistry retired = new BitCodeRegistry();
        BitCodeNamespace namespace = retired.reserve("DEMO");
        namespace.retire(1, "DEMO_STABLE", "3.4.0");
        List<RtpBitCodeLock.Entry> lock = List.of(entry("DEMO", 1, "DEMO_STABLE", Level.WARNING));

        assertTrue(RtpBitCodeLock.violations(retired, lock).isEmpty());
        assertTrue(RtpBitCodeLock.violations(retired, List.of()).stream()
                .anyMatch(message -> message.contains("previous severity cannot be inferred")));
        List<RtpBitCodeLock.Entry> updated = RtpBitCodeLock.update(retired, lock);
        assertTrue(updated.containsAll(lock));
    }

    @Test
    void newCodesAreAppendOnlyAndSeverityChangesRequireLockRefresh() {
        BitCodeRegistry current = registry("DEMO", 1, "DEMO_STABLE", Level.ERROR);
        current.reserve("DEMO").register(2, "DEMO_NEW", Level.INFO, BitTier.PBIT, "demo", "", "");
        List<RtpBitCodeLock.Entry> lock = List.of(entry("DEMO", 1, "DEMO_STABLE", Level.WARNING));

        List<String> violations = RtpBitCodeLock.violations(current, lock);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("severity changed"));

        List<RtpBitCodeLock.Entry> updated = RtpBitCodeLock.update(current, lock);
        assertTrue(updated.contains(entry("DEMO", 1, "DEMO_STABLE", Level.ERROR)));
        assertTrue(updated.contains(entry("DEMO", 2, "DEMO_NEW", Level.INFO)));
        assertTrue(RtpBitCodeLock.violations(current, updated).isEmpty());
    }

    @Test
    void updateRefusesIdentityMutationsButAllowsSeverityRefresh() {
        BitCodeRegistry renamed = registry("DEMO", 1, "DEMO_RENAMED", Level.WARNING);
        List<RtpBitCodeLock.Entry> lock = List.of(entry("DEMO", 1, "DEMO_STABLE", Level.WARNING));

        assertThrows(IllegalStateException.class, () -> RtpBitCodeLock.update(renamed, lock));
        assertFalse(RtpBitCodeLock.violations(registry("DEMO", 1, "DEMO_STABLE", Level.ERROR), lock).isEmpty());
    }

    @Test
    void malformedOrDuplicateLockRowsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> RtpBitCodeLock.parse("DEMO 1 DEMO_ONE NOT_A_LEVEL"));
        assertThrows(IllegalArgumentException.class,
                () -> RtpBitCodeLock.parse("DEMO 1 DEMO_ONE INFO\nDEMO 1 DEMO_TWO INFO"));
        assertThrows(IllegalArgumentException.class,
                () -> RtpBitCodeLock.parse("DEMO 1 DEMO_ONE INFO\nDEMO 2 DEMO_ONE INFO"));
    }

    private static RtpBitCodeLock.Entry entry(String namespace, int id, String key, Level severity) {
        return new RtpBitCodeLock.Entry(namespace, id, key, severity);
    }

    private static BitCodeRegistry registry(String namespace, int id, String key, Level severity) {
        BitCodeRegistry registry = new BitCodeRegistry();
        registry.reserve(namespace).register(id, key, severity, BitTier.PBIT, "lock-test", "", "");
        return registry;
    }

    private static BitCodeRegistry registry() {
        return new BitCodeRegistry();
    }
}
