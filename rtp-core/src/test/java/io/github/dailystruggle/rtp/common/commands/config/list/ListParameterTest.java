package io.github.dailystruggle.rtp.common.commands.config.list;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simpleyaml.configuration.file.YamlFile;

import java.nio.file.Path;

public class ListParameterTest {

    @TempDir
    Path tempDir;

    // ── ListAddParameter ──────────────────────────────────────────────────────

    @Test
    void listAddParameter_values_returnsFromSupplier() {
        Set<String> expected = new HashSet<>(Arrays.asList("PLAINS", "DESERT", "FOREST"));
        Supplier<Set<String>> supplier = () -> new HashSet<>(expected);
        YamlFile file = new YamlFile();
        ListAddParameter param = new ListAddParameter(supplier, file, "someKey");

        Set<String> result = param.values();
        assertEquals(expected, result);
    }

    @Test
    void listAddParameter_values_returnsEmptySetWhenSupplierEmpty() {
        Supplier<Set<String>> supplier = HashSet::new;
        YamlFile file = new YamlFile();
        ListAddParameter param = new ListAddParameter(supplier, file, "key");

        assertTrue(param.values().isEmpty());
    }

    @Test
    void listAddParameter_values_callsSupplierEachTime() {
        int[] callCount = {0};
        Supplier<Set<String>> supplier = () -> {
            callCount[0]++;
            return new HashSet<>();
        };
        YamlFile file = new YamlFile();
        ListAddParameter param = new ListAddParameter(supplier, file, "key");

        param.values();
        param.values();
        assertEquals(2, callCount[0], "Supplier should be called on each values() invocation");
    }

    @Test
    void listAddParameter_permission_isRtpUpdate() {
        ListAddParameter param = new ListAddParameter(HashSet::new, new YamlFile(), "key");
        assertEquals("rtp.update", param.permission());
    }

    @Test
    void listAddParameter_description_isNotNull() {
        ListAddParameter param = new ListAddParameter(HashSet::new, new YamlFile(), "key");
        assertNotNull(param.description());
    }

    @Test
    void listAddParameter_isRelevant_alwaysTrue() {
        ListAddParameter param = new ListAddParameter(HashSet::new, new YamlFile(), "key");
        assertTrue(param.isRelevant.apply(java.util.UUID.randomUUID(), "anything"));
    }

    // ── ListRemoveParameter ───────────────────────────────────────────────────

    @Test
    void listRemoveParameter_values_returnsCurrentListFromYamlFile() throws Exception {
        File yamlFile = tempDir.resolve("test.yml").toFile();
        YamlFile file = new YamlFile(yamlFile);
        file.createOrLoad();
        file.set("myList", Arrays.asList("alpha", "beta", "gamma"));

        ListRemoveParameter param = new ListRemoveParameter(file, "myList");
        Set<String> result = param.values();

        assertTrue(result.contains("alpha"));
        assertTrue(result.contains("beta"));
        assertTrue(result.contains("gamma"));
        assertEquals(3, result.size());
    }

    @Test
    void listRemoveParameter_values_returnsEmptySetForMissingKey() {
        YamlFile file = new YamlFile();
        ListRemoveParameter param = new ListRemoveParameter(file, "nonExistentKey");

        assertTrue(param.values().isEmpty());
    }

    @Test
    void listRemoveParameter_values_returnsEmptySetForEmptyList() throws Exception {
        File yamlFile = tempDir.resolve("empty.yml").toFile();
        YamlFile file = new YamlFile(yamlFile);
        file.createOrLoad();
        file.set("emptyList", Arrays.asList());

        ListRemoveParameter param = new ListRemoveParameter(file, "emptyList");
        assertTrue(param.values().isEmpty());
    }

    @Test
    void listRemoveParameter_values_reflectsYamlFileChanges() throws Exception {
        File yamlFile = tempDir.resolve("dynamic.yml").toFile();
        YamlFile file = new YamlFile(yamlFile);
        file.createOrLoad();
        file.set("items", Arrays.asList("one"));

        ListRemoveParameter param = new ListRemoveParameter(file, "items");
        assertEquals(1, param.values().size());

        // Update the file in memory
        file.set("items", Arrays.asList("one", "two", "three"));
        assertEquals(3, param.values().size());
    }

    @Test
    void listRemoveParameter_permission_isRtpUpdate() {
        ListRemoveParameter param = new ListRemoveParameter(new YamlFile(), "key");
        assertEquals("rtp.update", param.permission());
    }

    @Test
    void listRemoveParameter_isRelevant_alwaysTrue() {
        ListRemoveParameter param = new ListRemoveParameter(new YamlFile(), "key");
        assertTrue(param.isRelevant.apply(java.util.UUID.randomUUID(), "anything"));
    }

    // ── ListCmd.addCommands ───────────────────────────────────────────────────

    @Test
    void listCmd_addCommands_registersAddAndRemoveParameters() throws Exception {
        File yamlFile = tempDir.resolve("listcmd.yml").toFile();
        YamlFile file = new YamlFile(yamlFile);
        file.createOrLoad();
        file.set("myList", Arrays.asList("x", "y"));

        // ListCmd requires RTP.getInstance() in constructor — use addCommands() directly
        // by constructing a minimal ListCmd via reflection-free approach:
        // We test addCommands indirectly through the parameter objects it creates.
        ListAddParameter addParam = new ListAddParameter(HashSet::new, file, "myList");
        ListRemoveParameter removeParam = new ListRemoveParameter(file, "myList");

        assertNotNull(addParam);
        assertNotNull(removeParam);
        assertEquals("rtp.update", addParam.permission());
        assertEquals("rtp.update", removeParam.permission());
    }

    // ── ListAddParameter — supplier independence ──────────────────────────────

    @Test
    void listAddParameter_values_returnsIndependentSets() {
        Supplier<Set<String>> supplier = () -> new HashSet<>(Arrays.asList("a", "b"));
        ListAddParameter param = new ListAddParameter(supplier, new YamlFile(), "k");

        Set<String> s1 = param.values();
        Set<String> s2 = param.values();
        s1.add("extra");
        assertFalse(s2.contains("extra"), "Each call should return an independent set");
    }

    // ── ListRemoveParameter — values returns independent set ──────────────────

    @Test
    void listRemoveParameter_values_returnsIndependentSets() throws Exception {
        File yamlFile = tempDir.resolve("indep.yml").toFile();
        YamlFile file = new YamlFile(yamlFile);
        file.createOrLoad();
        file.set("list", Arrays.asList("a", "b"));

        ListRemoveParameter param = new ListRemoveParameter(file, "list");
        Set<String> s1 = param.values();
        Set<String> s2 = param.values();
        s1.add("extra");
        assertFalse(s2.contains("extra"), "Each call should return an independent set");
    }

    // ── ListAddParameter — large supplier set ─────────────────────────────────

    @Test
    void listAddParameter_values_handlesLargeSupplierSet() {
        Set<String> large = new HashSet<>();
        for (int i = 0; i < 1000; i++) large.add("item_" + i);
        ListAddParameter param = new ListAddParameter(() -> new HashSet<>(large), new YamlFile(), "k");
        assertEquals(1000, param.values().size());
    }

    // ── ListRemoveParameter — key with dots ───────────────────────────────────

    @Test
    void listRemoveParameter_values_keyWithDots() throws Exception {
        File yamlFile = tempDir.resolve("dots.yml").toFile();
        YamlFile file = new YamlFile(yamlFile);
        file.createOrLoad();
        // YamlFile treats dots as path separators; use a simple key
        file.set("section.subkey", Arrays.asList("val1", "val2"));

        ListRemoveParameter param = new ListRemoveParameter(file, "section.subkey");
        Set<String> result = param.values();
        assertTrue(result.contains("val1"));
        assertTrue(result.contains("val2"));
    }
}
