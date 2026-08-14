package io.github.dailystruggle.rtp.bukkit.configuration;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class DatabaseYmlDuplicationTest {

    @TempDir
    Path tempDir;

    private List<String> databaseSiblings(File dir) throws Exception {
        Path root = dir.toPath();
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("database.yml"))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .collect(Collectors.toList());
        }
    }

    @Test
    void databaseYmlDoesNotDuplicateAcrossReboots() throws Exception {
        File dir = tempDir.toFile();
        MockRTPServerAccessor accessor = RTPTestSetup.install(dir);
        System.out.println("[DEBUG_LOG] after first boot: " + databaseSiblings(dir));

        for (int i = 0; i < 3; i++) {
            RTP.configs.reloadConfigs();
        }
        List<String> after = databaseSiblings(dir);
        System.out.println("[DEBUG_LOG] after reboots: " + after);

        assertFalse(new File(dir, "database.yml").exists(),
                "a root database.yml must never appear on a fresh install: " + after);
        assertEquals(1, after.size(),
                "expected exactly one database.yml, found: " + after);
    }

    @Test
    void legacyRootDatabaseYmlIsNotReMigratedEveryRebootWhenArchiveExists() throws Exception {
        File dir = tempDir.toFile();
        // an older install left a root database.yml AND a prior archive of it
        Files.writeString(new File(dir, "database.yml").toPath(),
                "database:\n  type: \"mysql\"\nversion: 1.1\n");
        Files.writeString(new File(dir, "database.yml.migrated").toPath(),
                "database:\n  type: \"sqlite\"\nversion: 1.1\n");

        RTPTestSetup.install(dir);
        for (int i = 0; i < 3; i++) {
            RTP.configs.reloadConfigs();
        }

        List<String> after = databaseSiblings(dir);
        System.out.println("[DEBUG_LOG] legacy scenario after reboots: " + after);

        // The legacy root database.yml must be vacated (moved to a uniquified .migrated
        // archive), leaving exactly one live database.yml (advanced/database.yml). Before
        // the fix, File.renameTo failed because database.yml.migrated already existed, so
        // the root database.yml survived and reappeared as a duplicate on every reboot.
        assertFalse(new File(dir, "database.yml").exists(),
                "legacy root database.yml must be archived, not left in place");
        long live = after.stream()
                .filter(n -> n.endsWith("database.yml") && !n.contains(".migrated"))
                .count();
        assertEquals(1, live,
                "expected exactly one live database.yml after reboots, found: " + after);
        long backups = after.stream().filter(n -> n.contains(".bak")).count();
        assertEquals(0, backups,
                "reboots must not accumulate database.yml.bak.<ts> copies: " + after);
    }
}
