package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RtpYamlSection accessors (simpleyaml-compat surface)")
class RtpYamlSectionTest {

    private RtpYamlConfig cfg(String yaml) {
        return RtpYamlConfig.parse(yaml);
    }

    @Test
    @DisplayName("Typed getters coerce plain scalar values")
    void typedGetters() {
        RtpYamlConfig c = cfg("i: 5\nl: 3000000000\nd: 2.5\nb: true\ns: hello\n");
        assertEquals(5, c.getInt("i"));
        assertEquals(3000000000L, c.getLong("l"));
        assertEquals(2.5, c.getDouble("d"));
        assertTrue(c.getBoolean("b"));
        assertEquals("hello", c.getString("s"));
    }

    @Test
    @DisplayName("Typed getters parse string-typed values and fall back on defaults")
    void typedGettersFromStrings() {
        RtpYamlConfig c = cfg("i: \"5\"\nd: \"2.5\"\nl: \"9\"\n");
        assertEquals(5, c.getInt("i"));
        assertEquals(2.5, c.getDouble("d"));
        assertEquals(9L, c.getLong("l"));
        // missing keys yield defaults
        assertEquals(-1, c.getInt("nope", -1));
        assertEquals(-1L, c.getLong("nope", -1L));
        assertEquals(-1.0, c.getDouble("nope", -1.0));
        assertEquals("x", c.getString("nope", "x"));
        assertNull(c.getString("nope"));
        // unparseable strings fall back too
        RtpYamlConfig bad = cfg("i: abc\n");
        assertEquals(7, bad.getInt("i", 7));
        assertEquals(7L, bad.getLong("i", 7L));
        assertEquals(7.0, bad.getDouble("i", 7.0));
    }

    @Test
    @DisplayName("getBoolean accepts yes/no/on/off strings and defaults otherwise")
    void booleanStrings() {
        assertTrue(cfg("b: yes\n").getBoolean("b"));
        assertTrue(cfg("b: on\n").getBoolean("b"));
        assertFalse(cfg("b: no\n").getBoolean("b"));
        assertFalse(cfg("b: off\n").getBoolean("b"));
        assertTrue(cfg("b: maybe\n").getBoolean("b", true));
    }

    @Test
    @DisplayName("Type-probe predicates classify values")
    void predicates() {
        RtpYamlConfig c = cfg("s: hi\ni: 3\nb: false\nlist:\n  - a\nsec:\n  k: 1\n");
        assertTrue(c.isString("s"));
        assertTrue(c.isInt("i"));
        assertTrue(c.isBoolean("b"));
        assertTrue(c.isList("list"));
        assertTrue(c.isSet("i"));
        assertFalse(c.isSet("absent"));
        assertTrue(c.isConfigurationSection("sec"));
        assertFalse(c.isConfigurationSection("s"));
    }

    @Test
    @DisplayName("Dotted-path get and set traverse and create intermediate sections")
    void dottedPaths() {
        RtpYamlConfig c = cfg("a:\n  b:\n    c: 1\n");
        assertEquals(1, c.getInt("a.b.c"));
        c.set("x.y.z", 9);
        assertEquals(9, c.getInt("x.y.z"));
        assertTrue(c.isConfigurationSection("x"));
        assertTrue(c.isConfigurationSection("x.y"));
        // path that runs through a non-mapping resolves to absent
        assertFalse(c.contains("a.b.c.d"));
    }

    @Test
    @DisplayName("get(key, fallback) returns the fallback for absent keys")
    void getWithFallback() {
        RtpYamlConfig c = cfg("a: 1\n");
        assertEquals(1, c.get("a", 99));
        assertEquals(99, c.get("absent", 99));
    }

    @Test
    @DisplayName("getConfigurationSection returns a handle with name and path, null for scalars")
    void configurationSection() {
        RtpYamlConfig c = cfg("root:\n  leaf: 1\n");
        RtpYamlSection sec = c.getConfigurationSection("root");
        assertNotNull(sec);
        assertEquals("root", sec.getName());
        assertEquals("root", sec.getCurrentPath());
        assertEquals(1, sec.getInt("leaf"));
        assertNull(c.getConfigurationSection("root.leaf"));
        assertSame(c.mapping(), c.mapping());
        assertEquals("", c.getName());
        assertEquals("", c.getCurrentPath());
    }

    @Test
    @DisplayName("Lists coerce recursively; getList returns null for non-lists")
    void lists() {
        RtpYamlConfig c = cfg("nums:\n  - 1\n  - 2\nmix:\n  - a\n  - 3\nscalar: x\n");
        List<?> nums = c.getList("nums");
        assertEquals(List.of(1, 2), nums);
        assertEquals(List.of("a", "3"), c.getStringList("mix"));
        assertNull(c.getList("scalar"));
        assertTrue(c.getStringList("scalar").isEmpty());
    }

    @Test
    @DisplayName("getValues shallow and deep, and getMapValues alias")
    void getValues() {
        RtpYamlConfig c = cfg("a: 1\nsec:\n  b: 2\n  c: 3\n");
        Map<String, Object> shallow = c.getValues(false);
        assertEquals(Set.of("a", "sec"), shallow.keySet());
        Map<String, Object> deep = c.getValues(true);
        assertEquals(1, deep.get("a"));
        assertEquals(2, deep.get("sec.b"));
        assertEquals(3, deep.get("sec.c"));
        assertEquals(deep, c.getMapValues(true));
    }

    @Test
    @DisplayName("getKeys shallow and deep")
    void getKeys() {
        RtpYamlConfig c = cfg("a: 1\nsec:\n  b: 2\n");
        assertEquals(Set.of("a", "sec"), c.getKeys(false));
        assertEquals(Set.of("a", "sec", "sec.b"), c.getKeys(true));
    }

    @Test
    @DisplayName("createSection installs an empty or populated section")
    void createSection() {
        RtpYamlConfig c = cfg("");
        RtpYamlSection empty = c.createSection("e");
        assertNotNull(empty);
        assertTrue(c.isConfigurationSection("e"));
        c.createSection("p", Map.of("k", 1));
        assertEquals(1, c.getInt("p.k"));
    }

    @Test
    @DisplayName("set preserves existing block comments across re-typed writes")
    void setPreservesComments() {
        RtpYamlConfig c = cfg("# note\nk: 1\n");
        assertEquals("note", c.getComment("k"));
        c.set("k", 2);
        assertEquals("note", c.getComment("k"));
        assertEquals(2, c.getInt("k"));
    }

    @Test
    @DisplayName("set rejects null/empty keys")
    void setRejectsEmptyKey() {
        RtpYamlConfig c = cfg("");
        assertThrows(IllegalArgumentException.class, () -> c.set("", 1));
        assertThrows(IllegalArgumentException.class, () -> c.set(null, 1));
    }

    @Test
    @DisplayName("set replaces a non-mapping intermediate with a fresh section")
    void setReplacesNonMapping() {
        RtpYamlConfig c = cfg("a: scalar\n");
        c.set("a.b", 5);
        assertEquals(5, c.getInt("a.b"));
    }

    @Test
    @DisplayName("remove deletes leaf and dotted-path keys; no-op for absent")
    void remove() {
        RtpYamlConfig c = cfg("a: 1\nsec:\n  b: 2\n");
        c.remove("a");
        assertFalse(c.contains("a"));
        c.remove("sec.b");
        assertFalse(c.contains("sec.b"));
        c.remove("");
        c.remove("nope.deep");
        c.remove("a.b"); // parent not a mapping -> no-op
    }

    @Test
    @DisplayName("get/set comment round-trips and clears")
    void comments() {
        RtpYamlConfig c = cfg("k: 1\n");
        assertNull(c.getComment("k"));
        assertNull(c.getComment("absent"));
        c.setComment("k", "line1\nline2");
        assertEquals("line1\nline2", c.getComment("k"));
        c.setComment("k", null);
        assertNull(c.getComment("k"));
        c.setComment("k", "");
        assertNull(c.getComment("k"));
        c.setComment("absent", "ignored"); // no-op
    }

    @Test
    @DisplayName("set materialises Maps, Lists, and nested nodes")
    void setStructuredValues() {
        RtpYamlConfig c = cfg("");
        c.set("m", Map.of("x", 1));
        c.set("list", List.of("a", "b"));
        c.set("bool", true);
        c.set("num", 3);
        c.set("str", "text");
        c.set("nullish", null);
        assertEquals(1, c.getInt("m.x"));
        assertEquals(List.of("a", "b"), c.getStringList("list"));
        assertTrue(c.getBoolean("bool"));
        assertEquals(3, c.getInt("num"));
        assertEquals("text", c.getString("str"));
        assertEquals("", c.get("nullish"));
    }

    @Test
    @DisplayName("set accepts an existing RtpYamlSection value")
    void setSectionValue() {
        RtpYamlConfig c = cfg("src:\n  k: 1\n");
        RtpYamlSection src = c.getConfigurationSection("src");
        c.set("copy", src);
        assertEquals(1, c.getInt("copy.k"));
    }
}
