package io.github.dailystruggle.rtp.common.factory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
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
}
