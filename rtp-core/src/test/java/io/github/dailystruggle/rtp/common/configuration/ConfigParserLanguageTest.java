package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ConfigParserLanguageTest {

    @TempDir
    Path tempDir;

    public enum TestKeys {
        alpha,
        beta,
        gamma,
        version
    }

    @BeforeEach
    void setUp() {
        RTPServerAccessor serverAccessor = mock(RTPServerAccessor.class);
        RTPScheduler scheduler = mock(RTPScheduler.class);
        when(serverAccessor.getPluginDirectory()).thenReturn(tempDir.toFile());
        when(serverAccessor.createTaskPipe()).thenReturn(mock(io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe.class));
        RTP.serverAccessor = serverAccessor;
        RTP.scheduler = scheduler;

        // Clear any accessor left by a prior test so the write-once guard in
        // RTPAPI.setServerAccessor() does not reject this test's mock instance.
        RTPAPI.serverAccessor = null;

        RTP rtp = new RTP() {};
        try {
            java.lang.reflect.Field instanceField = RTP.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, rtp);
        } catch (Exception ignored) {}
    }

    private ConfigParser<TestKeys> buildParser(String yamlContent) throws IOException {
        return buildParser(yamlContent, null);
    }

    private ConfigParser<TestKeys> buildParser(String yamlContent, File langFile) throws IOException {
        File configFile = tempDir.resolve("test.yml").toFile();
        Files.writeString(configFile.toPath(), yamlContent);
        YamlFileDatabase fileDatabase = new YamlFileDatabase(tempDir.toFile());
        return new ConfigParser<>(
                TestKeys.class,
                "test",
                "1.0",
                tempDir.toFile(),
                langFile,
                fileDatabase
        );
    }

    // --- original test ---

    @Test
    void testAlternateLanguageMapping() throws IOException {
        File langDir = tempDir.resolve("lang").toFile();
        langDir.mkdirs();
        File langFile = new File(langDir, "test.lang.yml");

        // Write alternate language mapping
        Files.writeString(langFile.toPath(), "alpha: alternate_alpha\nbeta: alternate_beta\nversion: version\n");

        // Write config file using alternate keys
        File configFile = tempDir.resolve("test.yml").toFile();
        Files.writeString(configFile.toPath(), "alternate_alpha: 10\nalternate_beta: 20\nversion: 1.0\n");

        YamlFileDatabase fileDatabase = new YamlFileDatabase(tempDir.toFile());

        ConfigParser<TestKeys> parser = new ConfigParser<>(
                TestKeys.class,
                "test",
                "1.0",
                tempDir.toFile(),
                langFile,
                fileDatabase
        );

        // The parser should have translated alternate_alpha to alpha and parsed the value 10
        Object alphaVal = parser.getData(TestKeys.alpha);
        Object betaVal = parser.getData(TestKeys.beta);
        assertEquals(10, alphaVal, "Parser failed to map alternate_alpha back to alpha");
        assertEquals(20, betaVal, "Parser failed to map alternate_beta back to beta");
    }

    // --- missing key lookups ---

    @Test
    void testMissingKeyReturnsNull() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 42\nversion: 1.0\n");
        assertNull(parser.getData(TestKeys.beta), "Missing key should return null from getData");
        assertNull(parser.getData(TestKeys.gamma), "Missing key should return null from getData");
    }

    @Test
    void testGetConfigValueWithDefaultForMissingKey() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 42\nversion: 1.0\n");
        Object result = parser.getConfigValue(TestKeys.beta, "fallback");
        assertEquals("fallback", result, "getConfigValue should return default when key is absent");
    }

    @Test
    void testGetConfigValueWithDefaultForPresentKey() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 99\nversion: 1.0\n");
        Object result = parser.getConfigValue(TestKeys.alpha, 0);
        assertEquals(99, result, "getConfigValue should return stored value when key is present");
    }

    // --- type coercion edge cases ---

    @Test
    void testIntegerValueParsed() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 7\nversion: 1.0\n");
        Object val = parser.getData(TestKeys.alpha);
        assertNotNull(val);
        assertEquals(7, val);
    }

    @Test
    void testBooleanValueParsed() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: true\nversion: 1.0\n");
        Object val = parser.getData(TestKeys.alpha);
        assertNotNull(val);
        assertEquals(true, val);
    }

    @Test
    void testStringValueParsed() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: hello\nversion: 1.0\n");
        Object val = parser.getData(TestKeys.alpha);
        assertNotNull(val);
        assertEquals("hello", val);
    }

    // --- set and re-read (round-trip) ---

    @Test
    void testSetAndGetRoundTrip() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 1\nbeta: 2\nversion: 1.0\n");
        parser.set(TestKeys.alpha, 999);
        Object val = parser.getData(TestKeys.alpha);
        assertEquals(999, val, "set() should update the in-memory value immediately");
    }

    @Test
    void testSetStringKeyRoundTrip() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 1\nbeta: 2\nversion: 1.0\n");
        parser.set("beta", 55);
        assertEquals(55, parser.getData(TestKeys.beta));
    }

    @Test
    void testSetInvalidKeyThrows() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 1\nversion: 1.0\n");
        assertThrows(IllegalArgumentException.class, () -> parser.set("nonexistent_key", 1));
    }

    // --- save and reload round-trip ---

    @Test
    void testSaveAndReloadRoundTrip() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 1\nbeta: 2\nversion: 1.0\n");
        parser.set(TestKeys.alpha, 777);
        parser.save();

        // Build a second parser reading the same file
        YamlFileDatabase fileDatabase2 = new YamlFileDatabase(tempDir.toFile());
        ConfigParser<TestKeys> parser2 = new ConfigParser<>(
                TestKeys.class, "test", "1.0", tempDir.toFile(), null, fileDatabase2);
        assertEquals(777, parser2.getData(TestKeys.alpha), "Saved value should persist after reload");
    }

    // --- getMap() branches ---

    @Test
    void testGetMapReturnsEmptyForScalarValue() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 42\nversion: 1.0\n");
        // alpha holds a scalar - getMap should return an empty map
        java.util.Map<String, Object> map = parser.getMap(TestKeys.alpha);
        assertNotNull(map);
        assertTrue(map.isEmpty(), "getMap on a scalar value should return an empty map");
    }

    @Test
    void testGetMapReturnsEmptyForMissingKey() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 42\nversion: 1.0\n");
        java.util.Map<String, Object> map = parser.getMap(TestKeys.beta);
        assertNotNull(map);
        assertTrue(map.isEmpty(), "getMap on a missing key should return an empty map");
    }

    // --- clone() ---

    @Test
    void testCloneProducesIndependentParser() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 5\nbeta: 10\nversion: 1.0\n");
        ConfigParser<TestKeys> clone = parser.clone();
        assertNotSame(parser, clone, "clone() should return a different instance");
        // Mutating the clone should not affect the original
        clone.set(TestKeys.alpha, 999);
        assertEquals(5, parser.getData(TestKeys.alpha), "Original should be unaffected by clone mutation");
        assertEquals(999, clone.getData(TestKeys.alpha), "Clone should reflect its own mutation");
    }

    // --- version mismatch triggers update() / renameFiles() ---

    @Test
    void testVersionMismatchTriggersRename() throws IOException {
        // Write a config with version 0.9 but request version 1.0 - should trigger update/rename
        File configFile = tempDir.resolve("test.yml").toFile();
        Files.writeString(configFile.toPath(), "alpha: 7\nversion: 0.9\n");
        YamlFileDatabase fileDatabase = new YamlFileDatabase(tempDir.toFile());
        // Constructor calls check() which detects version mismatch and calls renameFiles()
        ConfigParser<TestKeys> parser = new ConfigParser<>(
                TestKeys.class, "test", "1.0", tempDir.toFile(), null, fileDatabase);
        // After rename, test.yml.old1 should exist
        File renamed = tempDir.resolve("test.yml.old1").toFile();
        assertTrue(renamed.exists(), "Version mismatch should cause old config to be renamed to .old1");
    }

    // --- null value in YAML ---
    @Test
    void testNullYamlValueNotStored() throws IOException {
        // YAML null (~) should not be stored in data map
        ConfigParser<TestKeys> parser = buildParser("alpha: ~\nversion: 1.0\n");
        assertNull(parser.getData(TestKeys.alpha), "Null YAML value should not be stored (getData returns null)");
    }

    // -----------------------------------------------------------------------
    // getMainDirectory() and getClassLoader()
    // -----------------------------------------------------------------------

    @Test
    void testGetMainDirectoryReturnsPluginDirectory() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 1\nversion: 1.0\n");
        assertEquals(tempDir.toFile().getAbsolutePath(),
                parser.getMainDirectory().getAbsolutePath(),
                "getMainDirectory() should return the plugin directory");
    }

    @Test
    void testGetClassLoaderReturnsNonNull() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 1\nversion: 1.0\n");
        assertNotNull(parser.getClassLoader(), "getClassLoader() should return a non-null ClassLoader");
    }

    // -----------------------------------------------------------------------
    // getMap() - nested map value stored in YAML
    // -----------------------------------------------------------------------

    @Test
    void testGetMapReturnsEntriesForNestedSection() throws IOException {
        // alpha holds a nested map section
        ConfigParser<TestKeys> parser = buildParser(
                "alpha:\n  key1: val1\n  key2: val2\nversion: 1.0\n");
        java.util.Map<String, Object> map = parser.getMap(TestKeys.alpha);
        assertNotNull(map);
        assertFalse(map.isEmpty(), "getMap on a nested section should return its entries");
        assertTrue(map.containsKey("key1"), "getMap should contain key1");
        assertEquals("val1", map.get("key1"));
    }

    // -----------------------------------------------------------------------
    // set(String, Object) - valid and invalid key
    // -----------------------------------------------------------------------

    @Test
    void testSetStringKeyValid() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 1\nbeta: 2\nversion: 1.0\n");
        parser.set("alpha", 42);
        assertEquals(42, parser.getData(TestKeys.alpha),
                "set(String,Object) with valid key should update the value");
    }

    @Test
    void testSetStringKeyInvalidThrows() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 1\nversion: 1.0\n");
        assertThrows(IllegalArgumentException.class,
                () -> parser.set("no_such_key", "value"),
                "set(String,Object) with unknown key should throw IllegalArgumentException");
    }

    // -----------------------------------------------------------------------
    // saveResource() - overwrite=false does not replace existing file
    // -----------------------------------------------------------------------

    @Test
    void testSaveResourceNoOverwriteKeepsExistingFile() throws IOException {
        // Build parser first (writes test.yml), then overwrite with sentinel content
        ConfigParser<TestKeys> parser = buildParser("alpha: 1\nversion: 1.0\n");

        // Now stamp the sentinel - saveResource(overwrite=false) must not touch it
        java.nio.file.Path target = tempDir.resolve("test.yml");
        Files.writeString(target, "alpha: SENTINEL\nversion: 1.0\n");

        parser.saveResource("test.yml", false);

        String content = Files.readString(target);
        assertTrue(content.contains("SENTINEL"),
                "saveResource(overwrite=false) should not overwrite an existing file");
    }

    // -----------------------------------------------------------------------
    // getConfigValue() - null stored value returns default
    // -----------------------------------------------------------------------

    @Test
    void testGetConfigValueNullStoredReturnsDefault() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: ~\nversion: 1.0\n");
        Object result = parser.getConfigValue(TestKeys.alpha, "default_val");
        assertEquals("default_val", result,
                "getConfigValue should return default when stored value is null");
    }

    // -----------------------------------------------------------------------
    // keys() - all enum names present
    // -----------------------------------------------------------------------

    @Test
    void testKeysContainsAllEnumNames() throws IOException {
        ConfigParser<TestKeys> parser = buildParser("alpha: 1\nversion: 1.0\n");
        java.util.Collection<String> keys = parser.keys();
        for (TestKeys k : TestKeys.values()) {
            assertTrue(keys.contains(k.name()),
                    "keys() should contain enum name: " + k.name());
        }
    }
}
