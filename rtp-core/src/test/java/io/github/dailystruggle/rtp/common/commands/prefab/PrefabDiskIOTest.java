package io.github.dailystruggle.rtp.common.commands.prefab;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 4b on-disk path tests for {@link PrefabDiskIO}. All file I/O is
 * confined to a JUnit {@code @TempDir} so the suite runs without
 * {@link io.github.dailystruggle.rtp.common.RTP} bootstrap.
 */
class PrefabDiskIOTest {

    @TempDir
    Path tempDir;

    private File pluginDir() {
        return tempDir.toFile();
    }

    private static List<PrefabApplier.Change> changes(String... pathValuePairs) {
        if ((pathValuePairs.length & 1) != 0) {
            throw new IllegalArgumentException("expected even number of args");
        }
        List<PrefabApplier.Change> out = new ArrayList<>();
        for (int i = 0; i < pathValuePairs.length; i += 2) {
            out.add(new PrefabApplier.Change(pathValuePairs[i], null, pathValuePairs[i + 1]));
        }
        return out;
    }

    // --- resolveFile ---------------------------------------------------------

    @Test
    void resolveFile_performance_yieldsPluginRootSibling() {
        File f = PrefabDiskIO.resolveFile(pluginDir(), "performance");
        assertEquals(new File(pluginDir(), "performance.yml"), f);
    }

    @Test
    void resolveFile_regions_namespacesUnderRegionsDir() {
        File f = PrefabDiskIO.resolveFile(pluginDir(), "regions/default");
        assertEquals(new File(new File(pluginDir(), "regions"), "default.yml"), f);
    }

    @Test
    void resolveFile_rejectsTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> PrefabDiskIO.resolveFile(pluginDir(), "../etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> PrefabDiskIO.resolveFile(pluginDir(), "/abs"));
        assertThrows(IllegalArgumentException.class,
                () -> PrefabDiskIO.resolveFile(pluginDir(), ""));
    }

    // --- readLive ------------------------------------------------------------

    @Test
    void readLive_missingFile_returnsEmptyMap() {
        Map<String, Object> tree = PrefabDiskIO.readLive(pluginDir(), "performance");
        assertNotNull(tree);
        assertTrue(tree.isEmpty());
    }

    @Test
    void readLive_existingFile_parsesYaml() throws IOException {
        File perf = new File(pluginDir(), "performance.yml");
        Files.write(perf.toPath(), "foo: 1\nbar: two\n".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> tree = PrefabDiskIO.readLive(pluginDir(), "performance");
        assertEquals("two", tree.get("bar"));
    }

    // --- writeWithBackup -----------------------------------------------------

    @Test
    void writeWithBackup_newFile_createsTarget_noBackup() throws IOException {
        Map<String, Object> newTree = new LinkedHashMap<>();
        newTree.put("foo", 42);
        Path bak = PrefabDiskIO.writeWithBackup(
                pluginDir(), "performance", newTree, changes("foo", "42"), 3);
        assertNull(bak, "new-file write must return null bakPath");
        File target = new File(pluginDir(), "performance.yml");
        assertTrue(target.exists());
        Map<String, Object> reread = PrefabDiskIO.readLive(pluginDir(), "performance");
        // Value was written via set() with String "42"; parser reads it as String.
        assertEquals("42", String.valueOf(reread.get("foo")));
    }

    @Test
    void writeWithBackup_existingFile_writesBakAndAppliesChange() throws IOException {
        File perf = new File(pluginDir(), "performance.yml");
        Files.write(perf.toPath(), "foo: original\n".getBytes(StandardCharsets.UTF_8));
        Path bak = PrefabDiskIO.writeWithBackup(
                pluginDir(), "performance",
                Map.of("foo", "updated"), changes("foo", "updated"), 3);
        assertNotNull(bak);
        assertTrue(Files.exists(bak));
        String bakContents = new String(Files.readAllBytes(bak), StandardCharsets.UTF_8);
        assertTrue(bakContents.contains("original"), "bak must contain original value");
        Map<String, Object> reread = PrefabDiskIO.readLive(pluginDir(), "performance");
        assertEquals("updated", reread.get("foo"));
    }

    @Test
    void writeWithBackup_regionsNewFile_createsParentDir() throws IOException {
        Path bak = PrefabDiskIO.writeWithBackup(
                pluginDir(), "regions/world_nether",
                Map.of("world", "world_nether"),
                changes("world", "world_nether"), 3);
        assertNull(bak);
        File target = new File(new File(pluginDir(), "regions"), "world_nether.yml");
        assertTrue(target.exists());
    }

    // --- retention -----------------------------------------------------------

    @Test
    void writeWithBackup_retention_keepsLastN() throws IOException, InterruptedException {
        File perf = new File(pluginDir(), "performance.yml");
        Files.write(perf.toPath(), "v: 0\n".getBytes(StandardCharsets.UTF_8));
        for (int i = 1; i <= 5; i++) {
            PrefabDiskIO.writeWithBackup(
                    pluginDir(), "performance",
                    Map.of("v", String.valueOf(i)), changes("v", String.valueOf(i)), 3);
            // Ensure distinct epoch ms suffixes; sub-millisecond writes are
            // disambiguated by writeWithBackup's collision guard, but a tiny
            // sleep also keeps the test deterministic on fast filesystems.
            Thread.sleep(5);
        }
        List<Path> baks = PrefabDiskIO.listBaks(pluginDir(), "performance");
        assertEquals(3, baks.size(), "retention=3 must keep exactly 3 backups, got " + baks.size());
    }

    @Test
    void writeWithBackup_retentionClampedToOne() throws IOException {
        File perf = new File(pluginDir(), "performance.yml");
        Files.write(perf.toPath(), "v: 0\n".getBytes(StandardCharsets.UTF_8));
        PrefabDiskIO.writeWithBackup(
                pluginDir(), "performance", Map.of("v", "1"), changes("v", "1"), 0);
        List<Path> baks = PrefabDiskIO.listBaks(pluginDir(), "performance");
        assertEquals(1, baks.size(), "retention<1 must be clamped to 1, just-written backup survives");
    }

    // --- listBaks / restoreLatest -------------------------------------------

    @Test
    void listBaks_sortsNewestFirst() throws IOException {
        File perf = new File(pluginDir(), "performance.yml");
        Files.write(perf.toPath(), "v: 0\n".getBytes(StandardCharsets.UTF_8));
        // Create three baks with hand-set epoch suffixes for determinism.
        Files.write(perf.toPath().resolveSibling("performance.yml.bak.1000"), new byte[0]);
        Files.write(perf.toPath().resolveSibling("performance.yml.bak.2000"), new byte[0]);
        Files.write(perf.toPath().resolveSibling("performance.yml.bak.500"), new byte[0]);
        // A non-bak sibling and a non-numeric-suffix bak must be filtered out.
        Files.write(perf.toPath().resolveSibling("performance.yml.bak.notnumeric"), new byte[0]);
        Files.write(perf.toPath().resolveSibling("performance.yml.other"), new byte[0]);
        List<Path> baks = PrefabDiskIO.listBaks(pluginDir(), "performance");
        assertEquals(3, baks.size());
        assertEquals("performance.yml.bak.2000", baks.get(0).getFileName().toString());
        assertEquals("performance.yml.bak.1000", baks.get(1).getFileName().toString());
        assertEquals("performance.yml.bak.500", baks.get(2).getFileName().toString());
    }

    @Test
    void restoreLatest_noBaks_returnsNull() throws IOException {
        assertNull(PrefabDiskIO.restoreLatest(pluginDir(), "performance"));
    }

    @Test
    void restoreLatest_movesNewestOverLive_andConsumesIt() throws IOException {
        File perf = new File(pluginDir(), "performance.yml");
        Files.write(perf.toPath(), "v: live\n".getBytes(StandardCharsets.UTF_8));
        Path olderBak = perf.toPath().resolveSibling("performance.yml.bak.1000");
        Path newerBak = perf.toPath().resolveSibling("performance.yml.bak.2000");
        Files.write(olderBak, "v: older\n".getBytes(StandardCharsets.UTF_8));
        Files.write(newerBak, "v: newer\n".getBytes(StandardCharsets.UTF_8));

        Path consumed = PrefabDiskIO.restoreLatest(pluginDir(), "performance");
        assertNotNull(consumed);
        assertEquals(newerBak, consumed);
        assertFalse(Files.exists(newerBak), "restored bak must be consumed");
        assertTrue(Files.exists(olderBak), "older bak must remain");

        String live = new String(Files.readAllBytes(perf.toPath()), StandardCharsets.UTF_8);
        assertTrue(live.contains("newer"), "live file must reflect newer bak content");

        // Second rollback walks to the older bak.
        Path consumed2 = PrefabDiskIO.restoreLatest(pluginDir(), "performance");
        assertEquals(olderBak, consumed2);
        assertFalse(Files.exists(olderBak));
        assertNull(PrefabDiskIO.restoreLatest(pluginDir(), "performance"),
                "after all baks consumed, restore must return null");
    }

    // --- snapshotLive --------------------------------------------------------

    @Test
    void snapshotLive_includesPerfAndEachRegionOverlay() throws IOException {
        File perf = new File(pluginDir(), "performance.yml");
        Files.write(perf.toPath(), "p: 1\n".getBytes(StandardCharsets.UTF_8));
        File regionsDir = new File(pluginDir(), "regions");
        assertTrue(regionsDir.mkdirs() || regionsDir.isDirectory());
        Files.write(new File(regionsDir, "default.yml").toPath(),
                "world: world\n".getBytes(StandardCharsets.UTF_8));

        Prefab p = new Prefab(
                "test-snapshot",
                "displayKey",
                "hoverKey",
                "test snapshot",
                Map.of("any", 1),                   // performanceOverlay (non-empty -> include)
                Map.of("default", Map.of("k", "v")),// regionOverlays
                false);
        Map<String, Map<String, Object>> snap = PrefabDiskIO.snapshotLive(pluginDir(), p);
        assertTrue(snap.containsKey("performance"));
        assertTrue(snap.containsKey("regions/default"));
        assertEquals("world", snap.get("regions/default").get("world"));
    }
}
