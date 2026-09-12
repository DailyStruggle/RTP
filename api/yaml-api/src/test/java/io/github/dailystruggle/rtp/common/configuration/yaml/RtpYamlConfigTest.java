package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RtpYamlConfig file handle (simpleyaml-compat)")
class RtpYamlConfigTest {

    @Test
    @DisplayName("parse(String) and saveToString round-trip through the writer")
    void parseAndSaveToString() {
        RtpYamlConfig c = RtpYamlConfig.parse("a: 1\nb: two\n");
        assertEquals(1, c.getInt("a"));
        assertEquals("a: 1\nb: two\n", c.saveToString());
    }

    @Test
    @DisplayName("parse(Reader) constructs an unbound config")
    void parseReader() throws IOException {
        RtpYamlConfig c = RtpYamlConfig.parse(new StringReader("k: v\n"));
        assertEquals("v", c.getString("k"));
        assertNull(c.file());
        assertNull(c.getConfigurationFile());
    }

    @Test
    @DisplayName("save() then load() round-trips through disk")
    void saveAndLoad(@TempDir Path dir) throws IOException {
        File f = dir.resolve("cfg.yml").toFile();
        RtpYamlConfig c = new RtpYamlConfig(f);
        c.set("greeting", "hi");
        c.set("count", 3);
        c.save();
        assertTrue(f.exists());

        RtpYamlConfig loaded = RtpYamlConfig.load(f);
        assertEquals("hi", loaded.getString("greeting"));
        assertEquals(3, loaded.getInt("count"));

        // instance load() replaces in-memory content
        RtpYamlConfig bound = new RtpYamlConfig(f.getPath());
        bound.load();
        assertEquals("hi", bound.getString("greeting"));
        bound.loadWithComments();
        assertEquals(3, bound.getInt("count"));
    }

    @Test
    @DisplayName("save(File) writes atomically and saveWithComments aliases save")
    void saveToFile(@TempDir Path dir) throws IOException {
        File f = dir.resolve("out.yml").toFile();
        RtpYamlConfig c = new RtpYamlConfig();
        c.set("x", 1);
        c.setConfigurationFile(f);
        c.saveWithComments();
        String onDisk = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertEquals("x: 1\n", onDisk);
    }

    @Test
    @DisplayName("save into a not-yet-existing subdirectory creates parents")
    void saveCreatesParents(@TempDir Path dir) throws IOException {
        File f = dir.resolve("nested/deeper/cfg.yml").toFile();
        RtpYamlConfig c = new RtpYamlConfig();
        c.set("k", "v");
        c.save(f);
        assertTrue(f.exists());
    }

    @Test
    @DisplayName("save() without a file binding throws IllegalStateException")
    void saveNoBinding() {
        RtpYamlConfig c = new RtpYamlConfig();
        assertThrows(IllegalStateException.class, c::save);
    }

    @Test
    @DisplayName("load() without a file binding throws IllegalStateException")
    void loadNoBinding() {
        RtpYamlConfig c = new RtpYamlConfig();
        assertThrows(IllegalStateException.class, c::load);
    }

    @Test
    @DisplayName("save(File) and copyTo reject null targets")
    void nullTargets() {
        RtpYamlConfig c = new RtpYamlConfig();
        assertThrows(IllegalArgumentException.class, () -> c.save((File) null));
        assertThrows(IllegalArgumentException.class, () -> c.copyTo((String) null));
        assertThrows(IllegalArgumentException.class, () -> c.copyTo((File) null));
    }

    @Test
    @DisplayName("load(File) rejects null and treats missing/empty files as empty")
    void loadEdgeCases(@TempDir Path dir) throws IOException {
        assertThrows(IllegalArgumentException.class, () -> RtpYamlConfig.load(null));

        File missing = dir.resolve("nope.yml").toFile();
        assertTrue(RtpYamlConfig.load(missing).getKeys(false).isEmpty());

        File empty = dir.resolve("empty.yml").toFile();
        assertTrue(empty.createNewFile());
        assertTrue(RtpYamlConfig.load(empty).getKeys(false).isEmpty());

        // instance load of an empty bound file clears contents
        RtpYamlConfig c = new RtpYamlConfig(empty);
        c.set("stale", 1);
        c.load();
        assertFalse(c.contains("stale"));
    }

    @Test
    @DisplayName("empty(File) makes a bound but empty document")
    void emptyFactory(@TempDir Path dir) {
        File f = dir.resolve("e.yml").toFile();
        RtpYamlConfig c = RtpYamlConfig.empty(f);
        assertSame(f, c.file());
        assertTrue(c.getKeys(false).isEmpty());
    }

    @Test
    @DisplayName("exists / getFilePath / createNewFile reflect the binding")
    void fileHandleParity(@TempDir Path dir) throws IOException {
        RtpYamlConfig unbound = new RtpYamlConfig();
        assertFalse(unbound.exists());
        assertNull(unbound.getFilePath());
        assertThrows(IllegalStateException.class, unbound::createNewFile);

        File f = dir.resolve("h.yml").toFile();
        RtpYamlConfig c = new RtpYamlConfig(f);
        assertFalse(c.exists());
        assertEquals(f.getAbsolutePath(), c.getFilePath());
        assertTrue(c.createNewFile());
        assertTrue(c.exists());
        assertFalse(c.createNewFile()); // already exists
        assertTrue(c.createNewFile(true)); // overwrite recreates
    }

    @Test
    @DisplayName("createOrLoad creates when missing and loads when present")
    void createOrLoad(@TempDir Path dir) throws IOException {
        File f = dir.resolve("col.yml").toFile();
        RtpYamlConfig c = new RtpYamlConfig(f);
        c.createOrLoad();
        assertTrue(f.exists());

        Files.write(f.toPath(), "k: loaded\n".getBytes(StandardCharsets.UTF_8));
        RtpYamlConfig c2 = new RtpYamlConfig(f);
        c2.createOrLoad();
        assertEquals("loaded", c2.getString("k"));

        assertThrows(IllegalStateException.class, () -> new RtpYamlConfig().createOrLoad());
    }

    @Test
    @DisplayName("copyTo writes the in-memory document to another path")
    void copyTo(@TempDir Path dir) throws IOException {
        RtpYamlConfig c = new RtpYamlConfig();
        c.set("k", "v");
        File byPath = dir.resolve("byPath.yml").toFile();
        c.copyTo(byPath.getPath());
        File byFile = dir.resolve("byFile.yml").toFile();
        c.copyTo(byFile);
        assertEquals("k: \"v\"\n", new String(Files.readAllBytes(byPath.toPath()), StandardCharsets.UTF_8));
        assertTrue(byFile.exists());
    }

    @Test
    @DisplayName("loadConfiguration(InputStream, closeStream) replaces the document")
    void loadConfiguration() throws IOException {
        RtpYamlConfig c = new RtpYamlConfig();
        c.set("stale", 1);
        InputStream in = new ByteArrayInputStream("fresh: 2\n".getBytes(StandardCharsets.UTF_8));
        c.loadConfiguration(in, true);
        assertFalse(c.contains("stale"));
        assertEquals(2, c.getInt("fresh"));
        assertThrows(IllegalArgumentException.class, () -> c.loadConfiguration(null, true));

        // closeStream=false branch
        RtpYamlConfig c2 = new RtpYamlConfig();
        c2.loadConfiguration(new ByteArrayInputStream("a: b\n".getBytes(StandardCharsets.UTF_8)), false);
        assertEquals("b", c2.getString("a"));
    }

    @Test
    @DisplayName("addDefault sets only when the key is absent")
    void addDefault() {
        RtpYamlConfig c = RtpYamlConfig.parse("present: 1\n");
        c.addDefault("present", 99);
        c.addDefault("added", 5);
        assertEquals(1, c.getInt("present"));
        assertEquals(5, c.getInt("added"));
    }

    @Test
    @DisplayName("options() exposes a fluent copyDefaults/indent knob object")
    void options() {
        RtpYamlConfig c = new RtpYamlConfig();
        RtpYamlOptions o = c.options();
        assertSame(o, o.copyDefaults(true).indent(4));
        assertTrue(o.copyDefaults());
        assertEquals(4, o.indent());
        assertThrows(IllegalArgumentException.class, () -> o.indent(0));
    }
}
