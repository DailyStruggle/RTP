package io.github.dailystruggle.bit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ADR-121 section 10: MapValidator BitCode overloads keep the registered id")
class MapValidatorRegisteredCodeTest {
    private static final BitCodeNamespace NS = new BitCodeRegistry().reserve("CFG");
    private static final BitCode CODE = NS.register(1001, "CFG_CHECK", Level.WARNING, BitTier.PBIT,
            "config", "", "Fix it.");
    private static final KeyPath ROOT = KeyPath.root("root");

    @Test
    void everyOverloadCarriesTheCodeIdKeyAndDefaultSeverity() {
        Vocabulary allowed = new Vocabulary("key", List.of("a", "b"));
        List<BitFinding> findings = new java.util.ArrayList<>(
                MapValidator.unknownKeys(Map.of("c", 1), allowed, "f.yml", ROOT, CODE));
        findings.add(MapValidator.required(Map.of(), "a", "f.yml", ROOT, CODE));
        findings.add(MapValidator.type("x", Integer.class, "f.yml", ROOT, CODE));
        findings.add(MapValidator.oneOf("c", allowed, "f.yml", ROOT, CODE));
        findings.add(MapValidator.range(5, 0, 1, "f.yml", ROOT, CODE));
        findings.add(MapValidator.reference("x", value -> false, "f.yml", ROOT, CODE));

        assertEquals(6, findings.size());
        for (BitFinding finding : findings) {
            assertEquals(1001, finding.codeId());
            assertEquals("CFG_CHECK", finding.code());
            assertEquals(Level.WARNING, finding.severity());
            assertEquals("f.yml", finding.source());
        }
        assertEquals("root.c", findings.get(0).path());
    }

    @Test
    void passingValuesProduceNoFinding() {
        Vocabulary allowed = new Vocabulary("key", List.of("a"));
        assertEquals(List.of(), MapValidator.unknownKeys(Map.of("a", 1), allowed, "f", ROOT, CODE));
        assertNull(MapValidator.required(Map.of("a", 1), "a", "f", ROOT, CODE));
        assertNull(MapValidator.type(1, Integer.class, "f", ROOT, CODE));
        assertNull(MapValidator.oneOf("A", allowed, "f", ROOT, CODE));
        assertNull(MapValidator.range(1, 0, 1, "f", ROOT, CODE));
        assertNull(MapValidator.reference("x", value -> true, "f", ROOT, CODE));
    }
}
