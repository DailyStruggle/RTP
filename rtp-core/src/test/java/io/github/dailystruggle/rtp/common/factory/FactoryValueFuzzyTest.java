package io.github.dailystruggle.rtp.common.factory;

import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FactoryValue - fuzzy property resolution in setData")
class FactoryValueFuzzyTest {

    private enum ShapeParam {
        radius,
        center_radius,
        weight,
        expand
    }

    private static class ShapeValue extends FactoryValue<ShapeParam> {
        ShapeValue(String name) {
            super(ShapeParam.class, name);
        }
    }

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.toFile());
    }

    @AfterEach
    void tearDown() {
        RTPTestSetup.cleanUp();
    }

    @Test
    @DisplayName("setData exact and normalized keys match silently")
    void testExactAndNormalizedKeys() {
        ShapeValue val = new ShapeValue("test");
        Map<String, Object> map = new HashMap<>();
        map.put("radius", 500);
        map.put("center-radius", 100);
        map.put("WEIGHT", 2.5);

        val.setData(map);
        assertEquals(500, val.getData(ShapeParam.radius));
        assertEquals(100, val.getData(ShapeParam.center_radius));
        assertEquals(2.5, val.getData(ShapeParam.weight));
    }

    @Test
    @DisplayName("setData recognizes 'name' property and updates value name silently")
    void testNamePropertyExactAndNormalized() {
        ShapeValue val = new ShapeValue("initial");
        Map<String, Object> map = new HashMap<>();
        map.put("name", "CIRCLE");
        map.put("radius", 300);

        val.setData(map);
        assertEquals("CIRCLE", val.name);
        assertEquals(300, val.getData(ShapeParam.radius));

        // Case-insensitive / normalized name property
        Map<String, Object> map2 = new HashMap<>();
        map2.put("NAME", "SQUARE");
        val.setData(map2);
        assertEquals("SQUARE", val.name);
    }

    @Test
    @DisplayName("setData autocorrects perceptible typo for 'name' property")
    void testNamePropertyPerceptibleTypo() {
        ShapeValue val = new ShapeValue("initial");
        Map<String, Object> map = new HashMap<>();
        map.put("nam", "POLYGON"); // typo for name (dist 1)

        val.setData(map);
        assertEquals("POLYGON", val.name);
    }

    @Test
    @DisplayName("setData lists 'name' among valid properties when logging unrecognized keys")
    void testUnrecognizedPropertyListsNameCandidate() {
        ShapeValue val = new ShapeValue("test");
        Map<String, Object> map = new HashMap<>();
        map.put("unknownPropertyXYZ", 123);

        val.setData(map);
        io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor accessor =
                (io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor) io.github.dailystruggle.rtp.common.RTP.serverAccessor;
        assertNotNull(accessor);
        assertTrue(
                accessor.logMessages.stream().anyMatch(m ->
                        m.contains("Unrecognized property 'unknownPropertyXYZ'") && m.contains("name")),
                "Warning message must list 'name' as a valid candidate: " + accessor.logMessages);
    }

    @Test
    @DisplayName("setData autocorrects perceptible typos with warning log")
    void testPerceptibleTypos() {
        ShapeValue val = new ShapeValue("test");
        Map<String, Object> map = new HashMap<>();
        map.put("radiu", 500); // typo for radius (dist 1)
        map.put("exapnd", true); // typo for expand (dist 2)

        val.setData(map);
        assertEquals(500, val.getData(ShapeParam.radius));
        assertEquals(true, val.getData(ShapeParam.expand));
    }

    @Test
    @DisplayName("setData ignores imperceptible garbage keys safely")
    void testImperceptibleKeys() {
        ShapeValue val = new ShapeValue("test");
        Map<String, Object> map = new HashMap<>();
        map.put("xyz12345", 999);
        map.put("foobar", "invalid");

        val.setData(map);
        assertNull(val.getData(ShapeParam.radius));
        assertNull(val.getData(ShapeParam.weight));
    }

    @Test
    @DisplayName("setData handles null keys or values gracefully")
    void testNullEntries() {
        ShapeValue val = new ShapeValue("test");
        Map<String, Object> map = new HashMap<>();
        map.put(null, 123);
        map.put("radius", null);

        assertDoesNotThrow(() -> val.setData(map));
        assertNull(val.getData(ShapeParam.radius));
    }
}
