package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the dotted-path semantics of {@link RtpYamlSection#getKeys(boolean)}
 * (ADR-025 §Migration step 6). The shape of the output must match what
 * the prior simpleyaml-backed implementation produced so that no
 * call-site behaviour shifts when the substrate is swapped.
 */
class RtpYamlDeepKeysParityTest {

    @Test
    @DisplayName("getKeys(false) returns top-level keys in source order")
    void shallowKeys() {
        String src = ""
                + "a: 1\n"
                + "b:\n"
                + "  c: 2\n"
                + "  d: 3\n"
                + "e: 4\n";
        RtpYamlConfig cfg = RtpYamlConfig.parse(src);
        assertEquals(List.of("a", "b", "e"), List.copyOf(cfg.getKeys(false)));
    }

    @Test
    @DisplayName("getKeys(true) returns dotted-path keys including sections, in source order")
    void deepKeysIncludesSectionsAndLeaves() {
        String src = ""
                + "a: 1\n"
                + "b:\n"
                + "  c: 2\n"
                + "  d:\n"
                + "    e: 3\n"
                + "f: 4\n";
        RtpYamlConfig cfg = RtpYamlConfig.parse(src);
        Set<String> got = cfg.getKeys(true);
        Set<String> expected = new LinkedHashSet<>(List.of("a", "b", "b.c", "b.d", "b.d.e", "f"));
        assertEquals(expected, got);
    }

    @Test
    @DisplayName("get() resolves dotted paths to coerced scalars")
    void dottedGet() {
        String src = ""
                + "database:\n"
                + "  type: \"sqlite\"\n"
                + "  port: 3306\n";
        RtpYamlConfig cfg = RtpYamlConfig.parse(src);
        assertEquals("sqlite", cfg.get("database.type"));
        assertEquals(3306, cfg.get("database.port"));
    }

    @Test
    @DisplayName("set() with dotted path creates intermediate sections on demand")
    void dottedSetCreatesParents() {
        RtpYamlConfig cfg = RtpYamlConfig.parse("");
        cfg.set("a.b.c", 42);
        assertEquals(42, cfg.get("a.b.c"));
        Set<String> deep = cfg.getKeys(true);
        assertTrue(deep.contains("a"));
        assertTrue(deep.contains("a.b"));
        assertTrue(deep.contains("a.b.c"));
    }
}
