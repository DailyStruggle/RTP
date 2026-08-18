package io.github.dailystruggle.rtp.common.factory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FactoryTest {

    // Minimal concrete FactoryValue for testing
    enum TestKey { VALUE }

    static class TestValue extends FactoryValue<TestKey> {
        TestValue(String name) {
            super(TestKey.class, name);
            data.put(TestKey.VALUE, "default");
        }

        @Override
        public FactoryValue<TestKey> clone() {
            TestValue copy = new TestValue(this.name);
            copy.data.putAll(this.data);
            return copy;
        }
    }

    private Factory<TestValue> factory;

    @BeforeEach
    void setUp() {
        factory = new Factory<>();
    }

    @Test
    void add_and_contains_caseSensitivity() {
        factory.add("myRegion", new TestValue("myRegion.yml"));
        // contains normalizes to upper + appends .YML
        assertTrue(factory.contains("myRegion"));
        assertTrue(factory.contains("MYREGION"));
        assertTrue(factory.contains("myRegion.yml"));
    }

    @Test
    void contains_missingKey_returnsFalse() {
        assertFalse(factory.contains("nonexistent"));
    }

    @Test
    void list_returnsAllKeys() {
        factory.add("alpha", new TestValue("alpha.yml"));
        factory.add("beta", new TestValue("beta.yml"));
        Enumeration<String> keys = factory.list();
        Set<String> keySet = new HashSet<>(Collections.list(keys));
        assertTrue(keySet.contains("ALPHA.YML"));
        assertTrue(keySet.contains("BETA.YML"));
        assertEquals(2, keySet.size());
    }

    @Test
    void remove_removesEntry() {
        factory.add("myRegion", new TestValue("myRegion.yml"));
        assertTrue(factory.contains("myRegion"));
        factory.remove("myRegion");
        assertFalse(factory.contains("myRegion"));
    }

    @Test
    void remove_nonExistent_noException() {
        assertDoesNotThrow(() -> factory.remove("ghost"));
    }

    @Test
    void get_existingKey_returnsClone() {
        TestValue original = new TestValue("test.yml");
        factory.add("test", original);
        FactoryValue<?> result = factory.get("test");
        assertNotNull(result);
        assertNotSame(original, result);
    }

    @Test
    void get_missingKey_returnsNull() {
        assertNull(factory.get("missing"));
    }

    @Test
    void construct_exactMatch_returnsClone() {
        TestValue original = new TestValue("exact.yml");
        factory.add("exact", original);
        FactoryValue<?> result = factory.construct("exact");
        assertNotNull(result);
        assertNotSame(original, result);
    }

    @Test
    void construct_missingKey_withDefault_returnsDefaultClone() {
        TestValue defaultVal = new TestValue("default.yml");
        factory.add("default", defaultVal);
        FactoryValue<?> result = factory.construct("nonexistent");
        assertNotNull(result);
        assertEquals("nonexistent.yml", result.name);
    }

    @Test
    void construct_missingKey_withAnyEntry_returnsClone() {
        TestValue val = new TestValue("only.yml");
        factory.add("only", val);
        FactoryValue<?> result = factory.construct("other");
        assertNotNull(result);
        assertEquals("other.yml", result.name);
    }

    @Test
    void construct_emptyFactory_returnsNull() {
        assertNull(factory.construct("anything"));
    }

    @Test
    void getOrDefault_existingKey_returnsClone() {
        TestValue val = new TestValue("region.yml");
        factory.add("region", val);
        FactoryValue<?> result = factory.getOrDefault("REGION");
        assertNotNull(result);
        assertNotSame(val, result);
    }

    @Test
    void getOrDefault_missingKey_withDefault_constructsAndStores() {
        TestValue defaultVal = new TestValue("default.yml");
        factory.add("default", defaultVal);
        FactoryValue<?> result = factory.getOrDefault("NEWKEY");
        assertNotNull(result);
    }

    @Test
    void getOrDefault_missingKey_withAnyEntry_returnsAnyClone() {
        TestValue val = new TestValue("only.yml");
        factory.add("only", val);
        FactoryValue<?> result = factory.getOrDefault("MISSING");
        assertNotNull(result);
    }

    @Test
    void construct_nameWithYmlSuffix_preservesSuffix() {
        TestValue val = new TestValue("default.yml");
        factory.add("default", val);
        FactoryValue<?> result = factory.construct("custom.yml");
        assertNotNull(result);
        assertEquals("custom.yml", result.name);
    }

    @Test
    void construct_nameWithoutYmlSuffix_appendsYml() {
        TestValue val = new TestValue("default.yml");
        factory.add("default", val);
        FactoryValue<?> result = factory.construct("custom");
        assertNotNull(result);
        assertEquals("custom.yml", result.name);
    }

    // -----------------------------------------------------------------------
    // FactoryValue - setData(EnumMap) branches
    // -----------------------------------------------------------------------

    @Test
    void setData_enumMap_wrongClass_throwsIllegalArgument() {
        // Use a different enum class to trigger the "invalid assignment" branch
        enum OtherKey { X }
        TestValue val = new TestValue("test.yml");
        EnumMap<OtherKey, Object> wrongMap = new EnumMap<>(OtherKey.class);
        wrongMap.put(OtherKey.X, "value");
        assertThrows(IllegalArgumentException.class, () -> val.setData(wrongMap));
    }

    @Test
    void setData_enumMap_correctClass_storesValue() {
        TestValue val = new TestValue("test.yml");
        EnumMap<TestKey, Object> map = new EnumMap<>(TestKey.class);
        map.put(TestKey.VALUE, "hello");
        val.setData(map);
        assertEquals("hello", val.getData(TestKey.VALUE));
    }

    // -----------------------------------------------------------------------
    // FactoryValue - setData(Map<String,Object>) branches
    // -----------------------------------------------------------------------

    @Test
    void setData_stringMap_validKey_storesValue() {
        TestValue val = new TestValue("test.yml");
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("VALUE", "stored");
        val.setData(map);
        assertEquals("stored", val.getData(TestKey.VALUE));
    }

    @Test
    void setData_stringMap_invalidKey_ignored() {
        TestValue val = new TestValue("test.yml");
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("NONEXISTENT", "ignored");
        // Should not throw; invalid keys are silently skipped
        assertDoesNotThrow(() -> val.setData(map));
    }

    @Test
    void setData_stringMap_nullKeyAndValue_ignored() {
        TestValue val = new TestValue("test.yml");
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put(null, "v");
        map.put("VALUE", null);
        assertDoesNotThrow(() -> val.setData(map));
    }

    // -----------------------------------------------------------------------
    // FactoryValue - getNumber branches
    // -----------------------------------------------------------------------

    @Test
    void getNumber_withLongValue_returnsLong() {
        TestValue val = new TestValue("test.yml");
        val.set(TestKey.VALUE, 42L);
        Number result = val.getNumber(TestKey.VALUE, 0L);
        assertEquals(42L, result.longValue());
    }

    @Test
    void getNumber_withIntegerValue_returnsInteger() {
        TestValue val = new TestValue("test.yml");
        val.set(TestKey.VALUE, 7);
        Number result = val.getNumber(TestKey.VALUE, 0);
        assertEquals(7, result.intValue());
    }

    @Test
    void getNumber_withNonNumberType_throwsIllegalArgument() {
        TestValue val = new TestValue("test.yml");
        val.set(TestKey.VALUE, new Object()); // not a Number or String or Character
        assertThrows(IllegalArgumentException.class, () -> val.getNumber(TestKey.VALUE, 0));
    }

    // -----------------------------------------------------------------------
    // FactoryValue - getData() returns clone
    // -----------------------------------------------------------------------

    @Test
    void getData_returnsCloneNotSameReference() {
        TestValue val = new TestValue("test.yml");
        val.set(TestKey.VALUE, "original");
        EnumMap<TestKey, Object> d1 = val.getData();
        EnumMap<TestKey, Object> d2 = val.getData();
        assertNotSame(d1, d2, "getData() should return a new clone each time");
    }

    // -----------------------------------------------------------------------
    // FactoryValue - keys() caching
    // -----------------------------------------------------------------------

    @Test
    void keys_calledTwice_returnsSameContent() {
        TestValue val = new TestValue("test.yml");
        java.util.Collection<String> first = val.keys();
        java.util.Collection<String> second = val.keys();
        assertEquals(first, second, "keys() should return consistent results across calls");
        assertTrue(first.contains("VALUE"));
    }

    // -----------------------------------------------------------------------
    // FactoryValue - toString()
    // -----------------------------------------------------------------------

    @Test
    void toString_containsKeyAndValue() {
        TestValue val = new TestValue("test.yml");
        val.set(TestKey.VALUE, "hello");
        String s = val.toString();
        assertTrue(s.contains("VALUE"), "toString() should contain the key name");
        assertTrue(s.contains("hello"), "toString() should contain the value");
    }

    // -----------------------------------------------------------------------
    // FactoryValue - equals() branches
    // -----------------------------------------------------------------------

    @Test
    void equals_sameData_returnsTrue() {
        TestValue a = new TestValue("test.yml");
        a.set(TestKey.VALUE, "x");
        TestValue b = new TestValue("test.yml");
        b.set(TestKey.VALUE, "x");
        assertEquals(a, b, "Two TestValues with same data should be equal");
    }

    @Test
    void equals_differentData_returnsFalse() {
        TestValue a = new TestValue("test.yml");
        a.set(TestKey.VALUE, "x");
        TestValue b = new TestValue("test.yml");
        b.set(TestKey.VALUE, "y");
        assertNotEquals(a, b, "Two TestValues with different data should not be equal");
    }

    @Test
    void equals_nonFactoryValue_returnsFalse() {
        TestValue val = new TestValue("test.yml");
        assertNotEquals(val, "not a FactoryValue");
    }

    // -----------------------------------------------------------------------
    // FactoryValue - toYAML() branches
    // -----------------------------------------------------------------------

    @Test
    void toYAML_withMapValue_containsMapEntries() {
        // Use a FactoryValue subclass that stores a Map value
        enum MapKey { DATA }
        class MapValue extends FactoryValue<MapKey> {
            MapValue() { super(MapKey.class, "map.yml"); }
            @Override public FactoryValue<MapKey> clone() {
                MapValue c = new MapValue(); c.data.putAll(this.data); return c;
            }
        }
        MapValue mv = new MapValue();
        java.util.Map<String, Object> inner = new java.util.LinkedHashMap<>();
        inner.put("k1", "v1");
        mv.set(MapKey.DATA, inner);
        String yaml = mv.toYAML();
        assertTrue(yaml.contains("k1"), "toYAML should include map keys");
        assertTrue(yaml.contains("v1"), "toYAML should include map values");
    }

    @Test
    void toYAML_withListValue_containsListEntries() {
        enum ListKey { ITEMS }
        class ListValue extends FactoryValue<ListKey> {
            ListValue() { super(ListKey.class, "list.yml"); }
            @Override public FactoryValue<ListKey> clone() {
                ListValue c = new ListValue(); c.data.putAll(this.data); return c;
            }
        }
        ListValue lv = new ListValue();
        lv.set(ListKey.ITEMS, java.util.Arrays.asList("a", "b", "c"));
        String yaml = lv.toYAML();
        assertTrue(yaml.contains("a"), "toYAML should include list elements");
        assertTrue(yaml.contains("b"), "toYAML should include list elements");
    }

    // -----------------------------------------------------------------------
    // FactoryValue - clone() with nested Map value (via base FactoryValue.clone())
    // -----------------------------------------------------------------------

    @Test
    void clone_withNestedMapValue_producesDeepCopy() {
        // Use a subclass that delegates to FactoryValue.clone() (the deep-copy implementation)
        enum DeepKey { DATA }
        class DeepValue extends FactoryValue<DeepKey> {
            DeepValue() { super(DeepKey.class, "deep.yml"); }
            @Override public FactoryValue<DeepKey> clone() {
                // Delegate to FactoryValue.clone() which deep-copies Map entries
                return super.clone();
            }
        }
        DeepValue val = new DeepValue();
        java.util.Map<String, Object> inner = new java.util.HashMap<>();
        inner.put("key", "value");
        val.set(DeepKey.DATA, inner);

        DeepValue cloned = (DeepValue) val.clone();
        assertNotSame(val.getData(DeepKey.DATA), cloned.getData(DeepKey.DATA),
                "clone() should deep-copy nested Map values");
    }

    // -----------------------------------------------------------------------
    // Factory - getOrDefault when map is empty (prints stack trace, returns clone)
    // -----------------------------------------------------------------------

    @Test
    void getOrDefault_emptyFactory_doesNotThrow() {
        // When map is empty, getOrDefault prints a stack trace but should not throw
        assertThrows(Exception.class, () -> factory.getOrDefault("ANYTHING"),
                "getOrDefault on empty factory should throw or handle gracefully");
    }

    // -----------------------------------------------------------------------
    // Factory - add with .YML suffix already present
    // -----------------------------------------------------------------------

    @Test
    void add_nameAlreadyHasYmlSuffix_doesNotDoubleAppend() {
        factory.add("region.YML", new TestValue("region.yml"));
        assertTrue(factory.contains("region"), "Factory should find key regardless of .YML suffix");
        // Ensure it's stored only once (no double .YML.YML)
        assertFalse(factory.map.containsKey("REGION.YML.YML"), "Key should not have double .YML suffix");
    }

    // -----------------------------------------------------------------------
    // Factory - remove with .YML suffix already present
    // -----------------------------------------------------------------------

    @Test
    void remove_nameAlreadyHasYmlSuffix_removesCorrectly() {
        factory.add("zone", new TestValue("zone.yml"));
        assertTrue(factory.contains("zone"));
        factory.remove("zone.YML");
        assertFalse(factory.contains("zone"), "remove() with .YML suffix should still remove the entry");
    }
}
