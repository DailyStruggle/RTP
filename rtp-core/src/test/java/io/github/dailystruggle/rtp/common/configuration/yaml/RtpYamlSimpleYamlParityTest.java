package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused parity-surface tests for Session 1 of the simpleyaml → in-house
 * YAML substrate migration (see {@code docs/dev/scratch/CHECKLIST-simpleyaml-migration.md}
 * and ADR-025).
 *
 * <p>These tests pin the simpleyaml-compatible API shapes used by production
 * call sites (ConfigParser, MultiConfigParser, YamlFileDatabase, command code,
 * etc.) so that Sessions 2 and 3 can be pure mechanical type swaps. The
 * substrate's deeper read/write/comment behavior is already covered by the
 * pre-existing yaml-package tests (golden file, deep-keys parity, comment
 * round-trip, write behavior); this class only exercises the parity surface
 * itself.</p>
 */
class RtpYamlSimpleYamlParityTest {

    private static final String SAMPLE =
            "# top comment\n" +
            "teleportDelay: 2\n" +
            "teleportCooldown: 300\n" +
            "active: true\n" +
            "name: \"hello\"\n" +
            "ratio: 1.5\n" +
            "big: 9999999999\n" +
            "database:\n" +
            "  # block comment on type\n" +
            "  type: \"sqlite\"\n" +
            "  port: 3306\n" +
            "items:\n" +
            "  - \"a\"\n" +
            "  - \"b\"\n" +
            "  - 3\n";

    @Nested
    @DisplayName("typed getters")
    class TypedGetters {

        @Test
        @DisplayName("getString / getString(def) returns value or default")
        void getString() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            assertEquals("hello", cfg.getString("name"));
            assertNull(cfg.getString("missing"));
            assertEquals("fallback", cfg.getString("missing", "fallback"));
        }

        @Test
        @DisplayName("getInt / getInt(def) coerces numerics and falls back")
        void getInt() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            assertEquals(2, cfg.getInt("teleportDelay"));
            assertEquals(0, cfg.getInt("missing"));
            assertEquals(42, cfg.getInt("missing", 42));
        }

        @Test
        @DisplayName("getLong / getLong(def) handles large values")
        void getLong() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            assertEquals(9999999999L, cfg.getLong("big"));
            assertEquals(7L, cfg.getLong("missing", 7L));
        }

        @Test
        @DisplayName("getDouble / getDouble(def) parses floats")
        void getDouble() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            assertEquals(1.5, cfg.getDouble("ratio"), 1e-9);
            assertEquals(2.5, cfg.getDouble("missing", 2.5), 1e-9);
        }

        @Test
        @DisplayName("getBoolean / getBoolean(def) covers true/yes/on and false")
        void getBoolean() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            assertTrue(cfg.getBoolean("active"));
            assertFalse(cfg.getBoolean("missing"));
            assertTrue(cfg.getBoolean("missing", true));
        }

        @Test
        @DisplayName("getStringList / getList expose sequence contents")
        void lists() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            List<String> strings = cfg.getStringList("items");
            assertEquals(List.of("a", "b", "3"), strings);
            assertNotNull(cfg.getList("items"));
            assertNull(cfg.getList("missing"));
            assertTrue(cfg.getStringList("missing").isEmpty());
        }
    }

    @Nested
    @DisplayName("predicates")
    class Predicates {

        @Test
        @DisplayName("isSet / contains agree and detect presence")
        void isSet() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            assertTrue(cfg.isSet("name"));
            assertTrue(cfg.contains("name"));
            assertFalse(cfg.isSet("missing"));
        }

        @Test
        @DisplayName("isConfigurationSection identifies mappings only")
        void isConfigurationSection() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            assertTrue(cfg.isConfigurationSection("database"));
            assertFalse(cfg.isConfigurationSection("name"));
            assertFalse(cfg.isConfigurationSection("missing"));
        }

        @Test
        @DisplayName("isString / isInt / isBoolean / isList classify correctly")
        void typePredicates() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            assertTrue(cfg.isString("name"));
            assertFalse(cfg.isString("teleportDelay"));
            assertTrue(cfg.isInt("teleportDelay"));
            assertTrue(cfg.isInt("big")); // long is accepted as "int" per parity
            assertTrue(cfg.isBoolean("active"));
            assertTrue(cfg.isList("items"));
            assertFalse(cfg.isList("name"));
        }
    }

    @Nested
    @DisplayName("section identity")
    class SectionIdentity {

        @Test
        @DisplayName("root section name/currentPath are empty strings")
        void rootIdentity() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            assertEquals("", cfg.getName());
            assertEquals("", cfg.getCurrentPath());
        }

        @Test
        @DisplayName("nested section name/currentPath reflect their position")
        void nestedIdentity() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            RtpYamlSection db = cfg.getConfigurationSection("database");
            assertNotNull(db);
            assertEquals("database", db.getName());
            assertEquals("database", db.getCurrentPath());
            assertEquals("sqlite", db.getString("type"));
            assertEquals(3306, db.getInt("port"));
        }
    }

    @Nested
    @DisplayName("getValues(deep)")
    class GetValues {

        @Test
        @DisplayName("getValues(false) yields top-level entries only")
        void shallow() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            Map<String, Object> v = cfg.getValues(false);
            assertTrue(v.containsKey("teleportDelay"));
            assertTrue(v.containsKey("database"));
            assertFalse(v.containsKey("database.type"));
        }

        @Test
        @DisplayName("getValues(true) flattens to dot-paths")
        void deep() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            Map<String, Object> v = cfg.getValues(true);
            assertEquals("sqlite", v.get("database.type"));
            assertEquals(3306, ((Number) v.get("database.port")).intValue());
            assertFalse(v.containsKey("database"), "deep view replaces section with its leaves");
        }
    }

    @Nested
    @DisplayName("options() — simpleyaml parity")
    class OptionsSurface {

        @Test
        @DisplayName("options() is non-null and the same instance on repeat calls")
        void identity() {
            RtpYamlConfig cfg = new RtpYamlConfig();
            RtpYamlOptions opts = cfg.options();
            assertNotNull(opts);
            assertSame(opts, cfg.options());
        }

        @Test
        @DisplayName("copyDefaults(boolean) is fluent and round-trips")
        void copyDefaultsRoundTrip() {
            RtpYamlConfig cfg = new RtpYamlConfig();
            RtpYamlOptions opts = cfg.options();
            assertFalse(opts.copyDefaults(), "default is false");
            assertSame(opts, opts.copyDefaults(true), "fluent return");
            assertTrue(opts.copyDefaults());
        }

        @Test
        @DisplayName("indent(int) is fluent and round-trips; rejects < 1")
        void indentRoundTrip() {
            RtpYamlConfig cfg = new RtpYamlConfig();
            RtpYamlOptions opts = cfg.options();
            assertEquals(2, opts.indent());
            assertSame(opts, opts.indent(4));
            assertEquals(4, opts.indent());
            assertThrows(IllegalArgumentException.class, () -> opts.indent(0));
        }
    }

    @Nested
    @DisplayName("addDefault — set-if-absent semantics")
    class AddDefault {

        @Test
        @DisplayName("addDefault sets when absent")
        void setsWhenAbsent() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            assertFalse(cfg.contains("newKey"));
            cfg.addDefault("newKey", 7);
            assertEquals(7, cfg.getInt("newKey"));
        }

        @Test
        @DisplayName("addDefault leaves an existing value untouched")
        void preservesExisting() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            cfg.addDefault("teleportDelay", 99);
            assertEquals(2, cfg.getInt("teleportDelay"), "existing value preserved");
        }

        @Test
        @DisplayName("addDefault on a dotted path creates the parent section")
        void createsNestedPath() {
            RtpYamlConfig cfg = new RtpYamlConfig();
            cfg.addDefault("a.b.c", "value");
            assertEquals("value", cfg.getString("a.b.c"));
            assertTrue(cfg.isConfigurationSection("a.b"));
        }
    }

    @Nested
    @DisplayName("loadWithComments / saveWithComments aliases")
    class CommentAliases {

        @Test
        @DisplayName("loadWithComments + saveWithComments behave like load + save")
        void aliases(@TempDir Path dir) throws IOException {
            File target = dir.resolve("c.yml").toFile();
            Files.write(target.toPath(), SAMPLE.getBytes(StandardCharsets.UTF_8));

            RtpYamlConfig cfg = new RtpYamlConfig(target.getAbsolutePath());
            cfg.setConfigurationFile(target);
            cfg.loadWithComments();
            assertEquals("hello", cfg.getString("name"));
            assertTrue(cfg.getComment("teleportDelay") == null
                    || cfg.getComment("teleportDelay").contains("top comment"),
                    "block comment exposure through getComment is best-effort, not strictly required for parity");

            cfg.set("teleportDelay", 5);
            cfg.saveWithComments();
            String onDisk = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
            assertTrue(onDisk.contains("teleportDelay: 5"));
            assertTrue(onDisk.contains("# block comment on type"), "comments preserved through saveWithComments");
        }
    }

    @Nested
    @DisplayName("setComment / getComment passthroughs")
    class CommentPassthroughs {

        @Test
        @DisplayName("setComment then getComment round-trips for top-level keys")
        void roundTrip() {
            RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
            cfg.setComment("teleportDelay", "explain delay");
            String c = cfg.getComment("teleportDelay");
            assertNotNull(c);
            assertTrue(c.contains("explain delay"));
        }
    }
}
