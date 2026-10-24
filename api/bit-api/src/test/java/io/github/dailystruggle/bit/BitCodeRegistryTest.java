package io.github.dailystruggle.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.System.Logger.Level;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 BitCodeRegistry int/string mapping")
class BitCodeRegistryTest {

    @Test
    void registeredCodeResolvesInBothDirections() {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCodeNamespace ns = registry.reserve("DEMO");
        BitCode code = ns.register(1, "DEMO_UNKNOWN_KEY", Level.ERROR, BitTier.PBIT,
                "demo", "bit.demo.unknownKey", "Use an allowed key.");

        assertSame(code, registry.byId("DEMO", 1).orElseThrow());
        assertSame(code, registry.byKey("demo_unknown_key").orElseThrow());
        assertEquals(OptionalInt.of(1), registry.idOf(" DEMO_UNKNOWN_KEY "));
        assertEquals("DEMO_UNKNOWN_KEY", registry.keyOf("DEMO", 1).orElseThrow());
        assertSame(code, registry.require("DEMO_UNKNOWN_KEY"));
        assertTrue(registry.byId("DEMO", 2).isEmpty());
        assertTrue(registry.byKey(null).isEmpty());
        assertTrue(registry.idOf("DEMO_MISSING").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.require("DEMO_MISSING"));
    }

    @Test
    void builtInNamespaceIsReservedAndPopulated() {
        BitCodeRegistry registry = new BitCodeRegistry();
        assertEquals(BitCodes.all(), registry.codes());
        assertEquals("BIT", registry.namespaces().get(0).prefix());
        assertSame(BitCodes.PBIT_THROWN, registry.byId("BIT", 2).orElseThrow());
        assertSame(registry.reserve("OTHER"), registry.reserve("OTHER"));
    }

    @Test
    void identicalRegistrationIsIdempotentForReload() {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCodeNamespace ns = registry.reserve("DEMO");
        assertSame(ns, registry.reserve("DEMO"));
        BitCode first = ns.register(1, "DEMO_A", Level.WARNING, BitTier.CBIT, "demo", "", "");
        BitCode again = ns.register(1, "DEMO_A", Level.WARNING, BitTier.CBIT, "demo", "", "");
        assertSame(first, again);
        assertEquals(BitCodes.all().size() + 1, registry.codes().size());
    }

    @Test
    void idsAreLocalToEachNamespace() {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCode demo = registry.reserve("DEMO")
                .register(1, "DEMO_ONE", Level.INFO, BitTier.PBIT, "demo", "", "");
        BitCode other = registry.reserve("OTHER")
                .register(1, "OTHER_ONE", Level.WARNING, BitTier.CBIT, "other", "", "");

        assertSame(demo, registry.byId("DEMO", 1).orElseThrow());
        assertSame(other, registry.byId("OTHER", 1).orElseThrow());
        List<BitCode> expected = new java.util.ArrayList<>(BitCodes.all());
        expected.add(demo);
        expected.add(other);
        assertEquals(expected, registry.codes());
    }

    @Test
    void reservingAPrefixForASecondOwnerIsRecordedAsAConflict() {
        BitCodeRegistry registry = new BitCodeRegistry();
        assertEquals("bit-api", registry.ownerOf("bit").orElseThrow());
        BitCodeNamespace first = registry.reserve("GUI", "gui-addon");
        assertSame(first, registry.reserve("GUI", "gui-addon"));
        assertSame(first, registry.reserve("GUI"));
        assertTrue(registry.conflicts().isEmpty());

        registry.reserve("UNOWNED");
        registry.reserve("UNOWNED", "late-owner");
        assertEquals("late-owner", registry.ownerOf("UNOWNED").orElseThrow());

        assertSame(first, registry.reserve("GUI", "other-addon"));
        registry.reserve("GUI", "other-addon");
        assertEquals(List.of(new NamespaceConflict("GUI", "gui-addon", "other-addon")), registry.conflicts());
        assertEquals("gui-addon", registry.ownerOf("GUI").orElseThrow());
        assertTrue(registry.ownerOf(null).isEmpty());
    }

    @Test
    void conflictingRegistrationsThrow() {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCodeNamespace ns = registry.reserve("DEMO");
        ns.register(1, "DEMO_A", Level.ERROR, BitTier.PBIT, "demo", "", "");

        assertThrows(IllegalStateException.class,
                () -> ns.register(1, "DEMO_B", Level.ERROR, BitTier.PBIT, "demo", "", ""));
        assertThrows(IllegalStateException.class,
                () -> ns.register(2, "DEMO_A", Level.ERROR, BitTier.PBIT, "demo", "", ""));
        assertThrows(IllegalStateException.class,
                () -> ns.register(1, "DEMO_A", Level.WARNING, BitTier.PBIT, "demo", "", ""));
    }

    @Test
    void codesMustUseAReservedNamespace() {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCodeNamespace demo = registry.reserve("DEMO");
        registry.reserve("OTHER");

        assertThrows(IllegalArgumentException.class,
                () -> demo.register(1, "OTHER_A", Level.ERROR, BitTier.PBIT, "", "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> demo.register(1, "UNREGISTERED_A", Level.ERROR, BitTier.PBIT, "", "", ""));
        BitCode unboundedId = registry.register(
                new BitCode(5000, "DEMO_A", Level.ERROR, BitTier.PBIT, "", "", ""));
        assertSame(unboundedId, registry.byId("DEMO", 5000).orElseThrow());
        assertThrows(IllegalStateException.class, () -> registry.register(
                new BitCode(1, "UNREGISTERED_A", Level.ERROR, BitTier.PBIT, "", "", "")));
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    void namespaceReservationRules() {
        BitCodeRegistry registry = new BitCodeRegistry();
        registry.reserve("DEMO");
        assertThrows(IllegalArgumentException.class, () -> registry.reserve("BAD_PREFIX"));
        assertThrows(IllegalArgumentException.class, () -> registry.reserve("lower"));
        assertThrows(NullPointerException.class, () -> registry.reserve(null));

        BitCodeNamespace late = registry.reserve("LATE");
        assertEquals(List.of("BIT", "DEMO", "LATE"),
                registry.namespaces().stream().map(BitCodeNamespace::prefix).toList());
        assertSame(late, registry.namespace("LATE").orElseThrow());
        assertTrue(registry.namespace(null).isEmpty());
        assertEquals("LATE", late.toString());
        assertTrue(!late.ownsKey(null));
        assertTrue(!late.ownsKey("LATER_X"));
    }

    @Test
    void retiredCodesRemainReserved() {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCodeNamespace namespace = registry.reserve("DEMO");
        RetiredBitCode retired = namespace.retire(1, "DEMO_OLD", "3.4.0");

        assertSame(retired, namespace.retire(1, "DEMO_OLD", "3.4.0"));
        assertEquals(List.of(retired), registry.retired());
        assertEquals("DEMO", retired.namespace());
        assertThrows(IllegalStateException.class,
                () -> namespace.register(1, "DEMO_NEW", Level.ERROR, BitTier.PBIT, "demo", "", ""));
        assertThrows(IllegalStateException.class,
                () -> namespace.register(2, "DEMO_OLD", Level.ERROR, BitTier.PBIT, "demo", "", ""));
        assertThrows(IllegalStateException.class, () -> namespace.retire(1, "DEMO_OTHER", "3.4.0"));
    }

    @Test
    void codeValidatesItsFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new BitCode(0, "DEMO_A", Level.ERROR, BitTier.PBIT, "", "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new BitCode(1, "demo_a", Level.ERROR, BitTier.PBIT, "", "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> new BitCode(1, "DEMO__A", Level.ERROR, BitTier.PBIT, "", "", ""));
        assertThrows(NullPointerException.class,
                () -> new BitCode(1, null, Level.ERROR, BitTier.PBIT, "", "", ""));
        assertThrows(NullPointerException.class,
                () -> new BitCode(1, "DEMO_A", null, BitTier.PBIT, "", "", ""));
        assertThrows(NullPointerException.class,
                () -> new BitCode(1, "DEMO_A", Level.ERROR, null, "", "", ""));
        BitCode defaults = new BitCode(1, "DEMO_A", Level.INFO, BitTier.IBIT, null, null, null);
        assertEquals("", defaults.subsystem());
        assertEquals("", defaults.messageKey());
        assertEquals("", defaults.remediation());
    }

    @Test
    void findingsCarryRegisteredIdAndKey() {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCode code = registry.reserve("DEMO")
                .register(1, "DEMO_A", Level.WARNING, BitTier.PBIT, "demo", "", "");

        BitFinding finding = BitFinding.of(code, "demo.yml", "a.b", "msg", "x", "y");
        assertEquals(1, finding.codeId());
        assertEquals("DEMO_A", finding.code());
        assertEquals("DEMO", code.namespace());
        assertEquals("DEMO", finding.namespace());
        assertEquals(Level.WARNING, finding.severity());
        assertEquals(Level.ERROR,
                BitFinding.of(code, Level.ERROR, "", "", "", "", "").severity());
        BitFinding legacy = new BitFinding(Level.INFO, "LEGACY", "", "", "", "", "");
        assertEquals(0, legacy.codeId());
        assertEquals("", legacy.namespace());
        assertThrows(IllegalArgumentException.class,
                () -> new BitFinding(Level.INFO, -1, "LEGACY", "", "", "", "", ""));
        assertThrows(NullPointerException.class, () -> BitFinding.of(null, "", "", "", "", ""));
        assertThrows(NullPointerException.class,
                () -> BitFinding.of(null, Level.ERROR, "", "", "", "", ""));
        assertTrue(BitReport.of(List.of(finding)).toJson()
                .contains("\"id\":1,\"ns\":\"DEMO\",\"code\":\"DEMO_A\""));
    }

    @Test
    void catalogJsonListsNamespacesAndCodesInNamespaceIdOrder() {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCodeNamespace ns = registry.reserve("DEMO");
        ns.register(2, "DEMO_B", Level.INFO, BitTier.IBIT, "demo", "bit.demo.b", "Say \"hi\".");
        ns.register(1, "DEMO_A", Level.ERROR, BitTier.PBIT, "demo", "", "");
        registry.reserve("OTHER").register(1, "OTHER_A", Level.WARNING, BitTier.PBIT, "other", "", "");
        ns.retire(3, "DEMO_RETIRED", "3.4.0");

        String json = registry.toCatalogJson();
        assertTrue(json.startsWith("{\"schema\":1,\"namespaces\":[{\"prefix\":\"BIT\"},{\"prefix\":\"DEMO\"}"));
        assertTrue(json.contains("{\"id\":1,\"ns\":\"DEMO\",\"key\":\"DEMO_A\""));
        assertTrue(json.indexOf("DEMO_A") < json.indexOf("DEMO_B"));
        assertTrue(json.indexOf("DEMO_B") < json.indexOf("OTHER_A"));
        assertTrue(json.contains("\"remediation\":\"Say \\\"hi\\\".\""));
        assertTrue(json.contains("\"tier\":\"IBIT\""));
        assertTrue(json.contains("\"retired\":[{\"ns\":\"DEMO\",\"id\":3,\"key\":\"DEMO_RETIRED\",\"since\":\"3.4.0\"}]"));
    }
}
