package io.github.dailystruggle.rtp.common.factory;

import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FactoryValue comprehensive coverage test")
class FactoryValueCoverageTest {

    private enum TestKey {
        KEY_NUMERIC,
        KEY_STRING,
        KEY_CHAR,
        KEY_BOOL,
        KEY_OBJECT,
        KEY_NESTED_FV,
        KEY_NESTED_MAP
    }

    private enum OtherEnum {
        OTHER
    }

    private static class ConcreteFV extends FactoryValue<TestKey> {
        ConcreteFV(String name) {
            super(TestKey.class, name);
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
    @DisplayName("clone() deep copies nested FactoryValue and Map entries")
    void testCloneNestedStructures() {
        ConcreteFV root = new ConcreteFV("root");
        ConcreteFV child = new ConcreteFV("child");
        child.set(TestKey.KEY_NUMERIC, 123);

        Map<String, Object> map = new HashMap<>();
        map.put("k1", "v1");

        root.set(TestKey.KEY_NESTED_FV, child);
        root.set(TestKey.KEY_NESTED_MAP, map);
        root.set(TestKey.KEY_STRING, "plain");

        ConcreteFV cloned = (ConcreteFV) root.clone();
        assertNotSame(root, cloned);
        assertNotSame(root.getData(), cloned.getData());

        // Nested FactoryValue should be cloned
        Object clonedChild = cloned.getData(TestKey.KEY_NESTED_FV);
        assertTrue(clonedChild instanceof ConcreteFV);
        assertNotSame(child, clonedChild);
        assertEquals(123, ((ConcreteFV) clonedChild).getData(TestKey.KEY_NUMERIC));

        // Nested Map should be cloned
        Object clonedMap = cloned.getData(TestKey.KEY_NESTED_MAP);
        assertTrue(clonedMap instanceof Map);
        assertNotSame(map, clonedMap);
        assertEquals("v1", ((Map<?, ?>) clonedMap).get("k1"));
    }

    @Test
    @DisplayName("getNumber handles DistanceParser explicit unit, unparseable strings, characters, and invalid types")
    void testGetNumberBranches() {
        ConcreteFV fv = new ConcreteFV("test");

        // DistanceParser explicit unit (e.g. chunks or blocks)
        fv.set(TestKey.KEY_STRING, "16c");
        Number chunks = fv.getNumber(TestKey.KEY_STRING, 0);
        assertEquals(16.0, chunks.doubleValue());

        // String with unparseable double falls back to default
        fv.set(TestKey.KEY_STRING, "not_a_number_value");
        Number fallback = fv.getNumber(TestKey.KEY_STRING, 999);
        assertEquals(999, fallback.intValue());

        // Character valid digit
        fv.set(TestKey.KEY_CHAR, '7');
        Number charNum = fv.getNumber(TestKey.KEY_CHAR, 0);
        assertEquals(7, charNum.intValue());

        // Character invalid digit falls back to default
        fv.set(TestKey.KEY_CHAR, 'Z');
        Number charFallback = fv.getNumber(TestKey.KEY_CHAR, 42);
        assertEquals(42, charFallback.intValue());

        // Unsupported object type throws IllegalArgumentException
        fv.set(TestKey.KEY_OBJECT, new Object());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> fv.getNumber(TestKey.KEY_OBJECT, 0));
        assertTrue(ex.getMessage().contains("NaN"));
    }

    @Test
    @DisplayName("setData(Map) handles null keys, null values, and unparseable key strings")
    void testSetDataMapValidation() {
        ConcreteFV fv = new ConcreteFV("test");
        fv.set(TestKey.KEY_NUMERIC, 10);

        Map<String, Object> map = new HashMap<>();
        map.put(null, "nullKey");
        map.put("KEY_STRING", null);
        map.put("NON_EXISTENT_ENUM_CONSTANT", "ignored");
        map.put("KEY_STRING", "validValue");

        assertDoesNotThrow(() -> fv.setData(map));
        assertEquals("validValue", fv.getData(TestKey.KEY_STRING));
        assertEquals(10, fv.getData(TestKey.KEY_NUMERIC)); // preserved merge semantics
    }

    @Test
    @DisplayName("setData(EnumMap), setDesc, and set parameter validation")
    void testValidation() {
        ConcreteFV fv = new ConcreteFV("test");

        // set(key, value)
        assertThrows(IllegalArgumentException.class, () -> fv.set(null, "value"));
        assertThrows(IllegalArgumentException.class, () -> fv.set(TestKey.KEY_STRING, null));

        // setDesc(key, desc)
        assertThrows(IllegalArgumentException.class, () -> fv.setDesc(null, new String[]{"desc"}));
        assertThrows(IllegalArgumentException.class, () -> fv.setDesc(TestKey.KEY_STRING, null));

        // setData(EnumMap) with incompatible enum type
        EnumMap<OtherEnum, Object> otherEnumMap = new EnumMap<>(OtherEnum.class);
        otherEnumMap.put(OtherEnum.OTHER, "val");
        assertThrows(IllegalArgumentException.class, () -> fv.setData(otherEnumMap));

        // keys()
        Collection<String> keys = fv.keys();
        assertTrue(keys.contains("KEY_NUMERIC"));
        assertSame(keys, fv.keys()); // cached collection
    }

    @Test
    @DisplayName("toYAML and toString rendering with nested structures and list")
    void testToYamlAndToString() {
        ConcreteFV fv = new ConcreteFV("yaml_test");
        fv.set(TestKey.KEY_NUMERIC, 42);
        fv.setDesc(TestKey.KEY_NUMERIC, new String[]{"# Number description"});
        fv.set(TestKey.KEY_STRING, "hello");

        ConcreteFV child = new ConcreteFV("nested");
        child.set(TestKey.KEY_NUMERIC, 99);
        fv.set(TestKey.KEY_NESTED_FV, child);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("subKey", "subVal");
        fv.set(TestKey.KEY_NESTED_MAP, map);

        fv.set(TestKey.KEY_OBJECT, List.of("item1", "item2"));

        String str = fv.toString();
        assertTrue(str.contains("KEY_NUMERIC: 42"));

        String yaml = fv.toYAML();
        assertTrue(yaml.contains("# Number description"));
        assertTrue(yaml.contains("KEY_NUMERIC: 42"));
        assertTrue(yaml.contains("KEY_STRING: hello"));
        assertTrue(yaml.contains("KEY_NESTED_FV:"));
        assertTrue(yaml.contains("KEY_NESTED_MAP:"));
        assertTrue(yaml.contains("subKey: subVal"));
        assertTrue(yaml.contains("item1"));
        assertTrue(yaml.contains("item2"));
    }

    @Test
    @DisplayName("loadLangFile creates default language mapping and reverse mapping")
    void testLoadLangFile() throws IOException {
        ConcreteFV fv = new ConcreteFV("test_lang");
        fv.loadLangFile("sub/lang");

        assertFalse(fv.language_mapping.isEmpty());
        assertFalse(fv.reverse_language_mapping.isEmpty());
        assertTrue(fv.language_mapping.containsKey("KEY_NUMERIC"));
        assertEquals("KEY_NUMERIC", fv.reverse_language_mapping.get("KEY_NUMERIC"));
    }

    @Test
    @DisplayName("equals and hashCode contract")
    void testEqualsAndHashCode() {
        ConcreteFV fv1 = new ConcreteFV("test_eq");
        fv1.set(TestKey.KEY_NUMERIC, 100);
        fv1.set(TestKey.KEY_STRING, "str");

        ConcreteFV fv2 = new ConcreteFV("test_eq");
        fv2.set(TestKey.KEY_NUMERIC, 100);
        fv2.set(TestKey.KEY_STRING, "str");

        assertEquals(fv1, fv2);
        assertEquals(fv1.hashCode(), fv2.hashCode());

        assertFalse(fv1.equals(null));
        assertFalse(fv1.equals("different_class"));

        ConcreteFV fvDiffVal = new ConcreteFV("test_eq");
        fvDiffVal.set(TestKey.KEY_NUMERIC, 200);
        fvDiffVal.set(TestKey.KEY_STRING, "str");
        assertNotEquals(fv1, fvDiffVal);
    }
}
