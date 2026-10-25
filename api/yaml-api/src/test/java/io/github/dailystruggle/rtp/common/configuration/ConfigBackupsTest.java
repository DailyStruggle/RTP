package io.github.dailystruggle.rtp.common.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConfigBackups} covering backup creation, FIFO retention
 * pruning, newest-first listing, and rollback consumption.
 */
class ConfigBackupsTest {

    @TempDir
    Path tempDir;

    @Test
    void backup_nonExistentFile_returnsNull() throws IOException {
        File missing = tempDir.resolve("missing.yml").toFile();
        assertNull(ConfigBackups.backup(missing, 3));
        assertTrue(ConfigBackups.listBaks(missing).isEmpty());
    }

    @Test
    void backup_createsSiblingAndPrunesOldestBeyondRetention() throws IOException {
        File cfg = tempDir.resolve("config.yml").toFile();

        Files.writeString(cfg.toPath(), "v: 1\n");
        Path b1 = ConfigBackups.backup(cfg, 2);
        assertNotNull(b1);
        assertTrue(Files.exists(b1));

        Files.writeString(cfg.toPath(), "v: 2\n");
        Path b2 = ConfigBackups.backup(cfg, 2);
        assertNotNull(b2);

        Files.writeString(cfg.toPath(), "v: 3\n");
        Path b3 = ConfigBackups.backup(cfg, 2);
        assertNotNull(b3);

        List<Path> baks = ConfigBackups.listBaks(cfg);
        assertEquals(2, baks.size(), "retention=2 must prune the oldest backup");
        assertEquals("v: 3\n", Files.readString(baks.get(0)), "newest backup must be first");
        assertEquals("v: 2\n", Files.readString(baks.get(1)), "second-newest backup must be second");
        assertFalse(Files.exists(b1), "oldest backup must have been pruned");
    }

    @Test
    void restoreLatest_restoresAndConsumesNewestBackup() throws IOException {
        File cfg = tempDir.resolve("safety.yml").toFile();

        Files.writeString(cfg.toPath(), "rev: one\n");
        ConfigBackups.backup(cfg, 3);
        Files.writeString(cfg.toPath(), "rev: two\n");
        ConfigBackups.backup(cfg, 3);
        Files.writeString(cfg.toPath(), "rev: current\n");

        Path consumed1 = ConfigBackups.restoreLatest(cfg);
        assertNotNull(consumed1);
        assertEquals("rev: two\n", Files.readString(cfg.toPath()));
        assertEquals(1, ConfigBackups.listBaks(cfg).size(), "consumed backup must be removed");

        Path consumed2 = ConfigBackups.restoreLatest(cfg);
        assertNotNull(consumed2);
        assertEquals("rev: one\n", Files.readString(cfg.toPath()));
        assertTrue(ConfigBackups.listBaks(cfg).isEmpty());

        assertNull(ConfigBackups.restoreLatest(cfg), "restore when empty must return null");
    }

    @Test
    void listBaks_ignoresUnrelatedFiles() throws IOException {
        File cfg = tempDir.resolve("economy.yml").toFile();
        Files.writeString(cfg.toPath(), "x: 1\n");
        Files.writeString(tempDir.resolve("economy.yml.old1"), "legacy\n");
        Files.writeString(tempDir.resolve("economy.yml.bak.notanumber"), "junk\n");
        Files.writeString(tempDir.resolve("other.yml.bak.12345"), "other\n");

        ConfigBackups.backup(cfg, 3);

        List<Path> baks = ConfigBackups.listBaks(cfg);
        assertEquals(1, baks.size());
    }
}
