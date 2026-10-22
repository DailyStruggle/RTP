package io.github.dailystruggle.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        BitCodeNamespace ns = registry.reserve("DEMO", 1000, 1999);
        BitCode code = ns.register(1001, "DEMO_UNKNOWN_KEY", Level.ERROR, BitTier.PBIT,
                "demo", "bit.demo.unknownKey", "Use an allowed key.");

        assertSame(code, registry.byId(1001).orElseThrow());
        assertSame(code, registry.byKey("demo_unknown_key").orElseThrow());
        assertEquals(OptionalInt.of(1001), registry.idOf(" DEMO_UNKNOWN_KEY "));
        assertEquals("DEMO_UNKNOWN_KEY", registry.keyOf(1001).orElseThrow());
        assertSame(code, registry.require("DEMO_UNKNOWN_KEY"));
        assertTrue(registry.byId(1002).isEmpty());
        assertTrue(registry.byKey(null).isEmpty());
        assertTrue(registry.idOf("DEMO_MISSING").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> registry.require("DEMO_MISSING"));
    }

    @Test
    void builtInNamespaceIsReservedAndPopulated() {
        BitCodeRegistry registry = new BitCodeRegistry();
        assertEquals(BitCodes.all(), registry.codes());
        assertEquals("BIT", registry.namespaces().get(0).prefix());
        assertSame(BitCodes.PBIT_THROWN, registry.byId(2).orElseThrow());
        assertThrows(IllegalStateException.class, () -> registry.reserve("OTHER", 500, 1500));
    }

    @Test
    void identicalRegistrationIsIdempotentForReload() {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCodeNamespace ns = registry.reserve("DEMO", 1000, 1999);
        assertSame(ns, registry.reserve("DEMO", 1000, 1999));
        BitCode first = ns.register(1001, "DEMO_A", Level.WARNING, BitTier.CBIT, "demo", "", "");
        BitCode again = ns.register(1001, "DEMO_A", Level.WARNING, BitTier.CBIT, "demo", "", "");
        assertSame(first, again);
        assertEquals(BitCodes.all().size() + 1, registry.codes().size());
    }

    @Test
    void conflictingRegistrationsThrow() {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCodeNamespace ns = registry.reserve("DEMO", 1000, 1999);
        ns.register(1001, "DEMO_A", Level.ERROR, BitTier.PBIT, "demo", "", "");

        assertThrows(IllegalStateException.class,
                () -> ns.register(1001, "DEMO_B", Level.ERROR, BitTier.PBIT, "demo", "", ""));
        assertThrows(IllegalStateException.class,
                () -> ns.register(1002, "DEMO_A", Level.ERROR, BitTier.PBIT, "demo", "", ""));
        assertThrows(IllegalStateException.class,
                () -> ns.register(1001, "DEMO_A", Level.WARNING, BitTier.PBIT, "demo", "", ""));
    }

    @Test
    void codesMustLieInsideTheirNamespace() {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCodeNamespace demo = registry.reserve("DEMO", 1000, 1999);
        registry.reserve("OTHER", 2000, 2999);

        assertThrows(IllegalArgumentException.class,
                () -> demo.register(2001, "DEMO_A", Level.ERROR, BitTier.PBIT, "", "", ""));
        assertThrows(IllegalArgumentException.class,
                () -> demo.register(1001, "OTHER_A", Level.ERROR, BitTier.PBIT, "", "", ""));
        assertThrows(IllegalStateException.class, () -> registry.register(
                new BitCode(5000, "DEMO_A", Level.ERROR, BitTier.PBIT, "", "", "")));
        assertThrows(IllegalStateException.class, () -> registry.register(
                new BitCode(1001, "OTHER_A", Level.ERROR, BitTier.PBIT, "", "", "")));
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    void namespaceReservationRules() {
        BitCodeRegistry registry = new BitCodeRegistry();
        registry.reserve("DEMO", 1000, 1999);

        assertThrows(IllegalStateException.class, () -> registry.reserve("DEMO", 1000, 1500));
        assertThrows(IllegalStateException.class, () -> registry.reserve("LATE", 1999, 2100));
        assertThrows(IllegalArgumentException.class, () -> registry.reserve("BAD_PREFIX", 3000, 3999));
        assertThrows(IllegalArgumentException.class, () -> registry.reserve("lower", 3000, 3999));
        assertThrows(IllegalArgumentException.class, () -> registry.reserve("ZERO", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> registry.reserve("BACK", 20, 10));
        assertThrows(NullPointerException.class, () -> registry.reserve(null, 3000, 3999));

        BitCodeNamespace late = registry.reserve("LATE", 2000, 2999);
        assertEquals(List.of("BIT", "DEMO", "LATE"),
                registry.namespaces().stream().map(BitCodeNamespace::prefix).toList());
        assertSame(late, registry.namespace("LATE").orElseThrow());
        assertSame(late, registry.namespaceForId(2500).orElseThrow());
        assertTrue(registry.namespace(null).isEmpty());
        assertTrue(registry.namespaceForId(9999).isEmpty());
        assertEquals("LATE[2000..2999]", late.toString());
        assertFalse(late.ownsKey(null));
        assertFalse(late.ownsKey("LATER_X"));
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
        BitCode code = registry.reserve("DEMO", 1000, 1999)
                .register(1001, "DEMO_A", Level.WARNING, BitTier.PBIT, "demo", "", "");

        BitFinding finding = BitFinding.of(code, "demo.yml", "a.b", "msg", "x", "y");
        assertEquals(1001, finding.codeId());
        assertEquals("DEMO_A", finding.code());
        assertEquals(Level.WARNING, finding.severity());
        assertEquals(Level.ERROR,
                BitFinding.of(code, Level.ERROR, "", "", "", "", "").severity());
        assertEquals(0, new BitFinding(Level.INFO, "LEGACY", "", "", "", "", "").codeId());
        assertThrows(IllegalArgumentException.class,
                () -> new BitFinding(Level.INFO, -1, "LEGACY", "", "", "", "", ""));
        assertThrows(NullPointerException.class, () -> BitFinding.of(null, "", "", "", "", ""));
        assertThrows(NullPointerException.class,
                () -> BitFinding.of(null, Level.ERROR, "", "", "", "", ""));
        assertTrue(BitReport.of(List.of(finding)).toJson().contains("\"id\":1001,\"code\":\"DEMO_A\""));
    }

    @Test
    void catalogJsonListsNamespacesAndCodesInIdOrder() {
        BitCodeRegistry registry = new BitCodeRegistry();
        BitCodeNamespace ns = registry.reserve("DEMO", 1000, 1999);
        ns.register(1002, "DEMO_B", Level.INFO, BitTier.IBIT, "demo", "bit.demo.b", "Say \"hi\".");
        ns.register(1001, "DEMO_A", Level.ERROR, BitTier.PBIT, "demo", "", "");

        String json = registry.toCatalogJson();
        assertTrue(json.startsWith("{\"schema\":1,\"namespaces\":[{\"prefix\":\"BIT\",\"firstId\":1,\"lastId\":999}"));
        assertTrue(json.contains("{\"prefix\":\"DEMO\",\"firstId\":1000,\"lastId\":1999}"));
        assertTrue(json.indexOf("DEMO_A") < json.indexOf("DEMO_B"));
        assertTrue(json.contains("\"remediation\":\"Say \\\"hi\\\".\""));
        assertTrue(json.contains("\"tier\":\"IBIT\""));
    }
}
