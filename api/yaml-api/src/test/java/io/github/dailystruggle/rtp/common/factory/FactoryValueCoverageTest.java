package io.github.dailystruggle.rtp.common.factory;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("FactoryValue Standalone Coverage Tests")
class FactoryValueCoverageTest {

    @TempDir
    Path tempDir;

    enum KeysWithName {
        name,
        radius,
        centerRadius,
        mode,
        active,
        nested,
        items
    }

    enum KeysWithoutName {
        minY,
        maxY,
        flag
    }

    static class NamedVal extends FactoryValue<KeysWithName> {
        NamedVal(String name) {
            super(KeysWithName.class, name);
        }
    }

    static class UnnamedVal extends FactoryValue<KeysWithoutName> {
        UnnamedVal(String name) {
            super(KeysWithoutName.class, name);
        }
    }

    @AfterEach
    void tearDown() {
        FactoryValue.setPluginDirectorySupplier(null);
    }

    @Test
    @DisplayName("setData matches exact keys, fuzzy matches typos, updates name, and ignores unknown keys")
    void setDataExactFuzzyAndUnrecognized() {
        NamedVal val = new NamedVal("INITIAL");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", "UPDATED_NAME");
        raw.put("radius", 256);
        raw.put("centerRadiu", 64); // fuzzy match to centerRadius
        raw.put("completely_unrelated_xyz_key", 999);
        raw.put("nullVal", null);

        val.setData(raw);
        assertEquals("UPDATED_NAME", val.name);
        assertEquals(256, val.getData(KeysWithName.radius));
        assertEquals(64, val.getData(KeysWithName.centerRadius));

        // Enum without 'name' constant still updates this.name when 'name' or fuzzy 'nam' is passed
        UnnamedVal unnamed = new UnnamedVal("OLD");
        Map<String, Object> raw2 = new LinkedHashMap<>();
        raw2.put("name", "NEW_UNNAMED");
        raw2.put("minY", 10);
        unnamed.setData(raw2);
        assertEquals("NEW_UNNAMED", unnamed.name);
        assertEquals(10, unnamed.getData(KeysWithoutName.minY));
    }

    @Test
    @DisplayName("getNumber handles Number, Boolean, String distances/auto/floats/invalid, Character, and non-numeric")
    void getNumberAllCoercions() {
        NamedVal val = new NamedVal("TEST");

        // Default when absent
        assertEquals(42, val.getNumber(KeysWithName.radius, 42).intValue());

        // Direct Number
        val.set(KeysWithName.radius, 128L);
        assertEquals(128L, val.getNumber(KeysWithName.radius, 0).longValue());

        // Boolean true -> 1, false -> 0
        val.set(KeysWithName.active, Boolean.TRUE);
        assertEquals(1, val.getNumber(KeysWithName.active, 0).intValue());
        val.set(KeysWithName.active, Boolean.FALSE);
        assertEquals(0, val.getNumber(KeysWithName.active, 99).intValue());

        // String with explicit spatial unit (e.g. "160b" -> 10 chunks)
        val.set(KeysWithName.radius, "160b");
        assertEquals(10L, val.getNumber(KeysWithName.radius, 0).longValue());

        // String "auto" -> returns default
        val.set(KeysWithName.radius, "auto");
        assertEquals(77, val.getNumber(KeysWithName.radius, 77).intValue());

        // String decimal with comma
        val.set(KeysWithName.radius, "12,5");
        assertEquals(12.5, val.getNumber(KeysWithName.radius, 0).doubleValue(), 1e-6);

        // Unparseable String -> logs and returns default
        val.set(KeysWithName.radius, "not_a_number_at_all");
        assertEquals(55, val.getNumber(KeysWithName.radius, 55).intValue());

        // Character digit and non-digit
        val.set(KeysWithName.centerRadius, '7');
        assertEquals(7, val.getNumber(KeysWithName.centerRadius, 0).intValue());
        val.set(KeysWithName.centerRadius, 'Z');
        assertEquals(9, val.getNumber(KeysWithName.centerRadius, 9).intValue());

        // Unsupported object type throws IllegalArgumentException
        val.set(KeysWithName.nested, new Object());
        assertThrows(IllegalArgumentException.class, () -> val.getNumber(KeysWithName.nested, 0));
    }

    @Test
    @DisplayName("setDesc, getDesc, toYAML, toString, clone, equals, and hashCode")
    void yamlFormattingCloneAndEquality() {
        NamedVal a = new NamedVal("CIRCLE");
        assertThrows(IllegalArgumentException.class, () -> a.setDesc(null, new String[]{"# a"}));
        assertThrows(IllegalArgumentException.class, () -> a.setDesc(KeysWithName.radius, null));
        assertThrows(IllegalArgumentException.class, () -> a.set(null, 1));
        assertThrows(IllegalArgumentException.class, () -> a.set(KeysWithName.radius, null));

        a.setDesc(KeysWithName.radius, new String[]{"# Radius comment"});
        assertArrayEquals(new String[]{"# Radius comment"}, a.desc.get(KeysWithName.radius));

        UnnamedVal child = new UnnamedVal("LINEAR");
        child.set(KeysWithoutName.minY, 0);
        child.set(KeysWithoutName.maxY, 256);

        a.set(KeysWithName.radius, 100);
        a.set(KeysWithName.nested, child);
        a.set(KeysWithName.mode, Map.of("k", "v"));
        a.set(KeysWithName.items, List.of("one", "two"));

        String yaml = a.toYAML();
        assertTrue(yaml.contains("# Radius comment"));
        assertTrue(yaml.contains("radius: 100"));
        assertTrue(yaml.contains("minY: 0"));
        assertTrue(yaml.contains("k: v"));
        assertTrue(yaml.contains("one"));
        assertTrue(a.toString().contains("radius: 100"));

        NamedVal cloned = (NamedVal) a.clone();
        assertEquals(a, cloned);
        assertEquals(a.hashCode(), cloned.hashCode());
        assertNotSame(a.getData(KeysWithName.nested), cloned.getData(KeysWithName.nested));
        assertNotSame(a.getData(KeysWithName.mode), cloned.getData(KeysWithName.mode));

        // Cross-type string equality in equals()
        cloned.set(KeysWithName.radius, "100");
        assertEquals(a, cloned);

        cloned.set(KeysWithName.radius, 999);
        assertNotEquals(a, cloned);
        assertNotEquals(a, "not a FactoryValue");
        assertNotEquals(a, child);

        EnumMap<KeysWithoutName, Object> replaced = new EnumMap<>(KeysWithoutName.class);
        replaced.put(KeysWithoutName.minY, 32);
        child.setData(replaced);
        assertEquals(32, child.getData(KeysWithoutName.minY));
    }

    @Test
    @DisplayName("loadLangFile creates default dotfile map and reads custom translations")
    void loadLangFileCreatesAndReadsMapping() throws IOException {
        File dir = tempDir.toFile();
        FactoryValue.setPluginDirectorySupplier(() -> null);
        NamedVal noDirVal = new NamedVal("CIRCLE");
        // When supplier returns null, loadLangFile returns early
        noDirVal.loadLangFile("definitions/regions/.shape");
        assertTrue(noDirVal.language_mapping.isEmpty());

        FactoryValue.setPluginDirectorySupplier(() -> dir);
        NamedVal val = new NamedVal("CIRCLE");
        val.loadLangFile("definitions/regions/.shape");

        File mapFile = new File(dir, "definitions/regions/.shape/.CIRCLE.lang.yml");
        assertTrue(mapFile.isFile());
        assertEquals("radius", val.language_mapping.get("radius"));
        assertEquals("radius", val.reverse_language_mapping.get("radius"));

        // Customize mapping on disk and reload
        Files.writeString(mapFile.toPath(), "radius: radio\nmode: modo\n", StandardCharsets.UTF_8);
        val.loadLangFile("definitions/regions/.shape");
        assertEquals("radio", val.language_mapping.get("radius"));
        assertEquals("radius", val.reverse_language_mapping.get("radio"));

        // setData with translated key
        val.setData(Map.of("radio", 500));
        assertEquals(500, val.getData(KeysWithName.radius));
    }
}
