package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Write-behavior verification for {@link RtpYamlConfig}.
 *
 * <p>The pre-existing suite (golden-file, deep-keys parity, etc.) only
 * exercises {@code parse → emit} as strings. This test covers the actual
 * on-disk write path: temp-file + atomic rename, parent-directory
 * creation, round-trip through real file I/O, overwrite semantics, the
 * {@code IllegalStateException} contract on unbound documents, and that
 * no temp-file litter is left behind after a successful save.</p>
 */
class RtpYamlWriteBehaviorTest {

    private static final String SAMPLE =
            "# header comment\n" +
            "teleportDelay: 2\n" +
            "# cooldown explanation\n" +
            "teleportCooldown: 300\n" +
            "database:\n" +
            "  # backend type\n" +
            "  type: \"sqlite\"\n" +
            "  port: 3306\n" +
            "list:\n" +
            "  - \"a\"\n" +
            "  - \"b\"\n";

    @Test
    @DisplayName("save() writes to the bound file and reload yields identical content")
    void saveRoundTrip(@TempDir Path dir) throws IOException {
        File target = dir.resolve("config.yml").toFile();
        Files.write(target.toPath(), SAMPLE.getBytes(StandardCharsets.UTF_8));

        RtpYamlConfig cfg = RtpYamlConfig.load(target);
        String emittedBefore = cfg.saveToString();
        cfg.save();

        assertTrue(target.isFile(), "target file must exist after save");
        String onDisk = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        assertEquals(emittedBefore, onDisk, "on-disk content must match saveToString()");

        // Reload and confirm byte-stable second save.
        RtpYamlConfig reloaded = RtpYamlConfig.load(target);
        assertEquals(emittedBefore, reloaded.saveToString(),
                "reload∘save must be byte-identical to the first emit");
    }

    @Test
    @DisplayName("save() overwrites an existing target file in place")
    void saveOverwritesExisting(@TempDir Path dir) throws IOException {
        File target = dir.resolve("config.yml").toFile();
        Files.write(target.toPath(), "old: 1\n".getBytes(StandardCharsets.UTF_8));

        RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
        cfg.save(target);

        String onDisk = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        assertFalse(onDisk.contains("old: 1"), "previous content must be replaced");
        assertTrue(onDisk.contains("teleportDelay: 2"), "new content must be written");
    }

    @Test
    @DisplayName("save(File) creates missing parent directories")
    void saveCreatesParentDirs(@TempDir Path dir) throws IOException {
        File nested = dir.resolve("a").resolve("b").resolve("config.yml").toFile();
        assertFalse(nested.getParentFile().exists(), "precondition: parent dirs absent");

        RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
        cfg.save(nested);

        assertTrue(nested.isFile(), "file must exist under the auto-created parents");
        String onDisk = new String(Files.readAllBytes(nested.toPath()), StandardCharsets.UTF_8);
        assertTrue(onDisk.contains("teleportDelay: 2"));
    }

    @Test
    @DisplayName("save() with no file binding throws IllegalStateException")
    void saveWithoutBindingThrows() {
        RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
        assertThrows(IllegalStateException.class, cfg::save);
    }

    @Test
    @DisplayName("save(null) throws IllegalArgumentException")
    void saveNullTargetThrows() {
        RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
        assertThrows(IllegalArgumentException.class, () -> cfg.save(null));
    }

    @Test
    @DisplayName("save() leaves no .tmp residue in the target directory")
    void saveLeavesNoTempResidue(@TempDir Path dir) throws IOException {
        File target = dir.resolve("config.yml").toFile();
        RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
        cfg.save(target);

        try (Stream<Path> entries = Files.list(dir)) {
            long tmpCount = entries.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
            assertEquals(0L, tmpCount, "no temp files should remain after a successful save");
        }
    }

    @Test
    @DisplayName("save() preserves block comments and indentation through real file I/O")
    void savePreservesBlockComments(@TempDir Path dir) throws IOException {
        File target = dir.resolve("config.yml").toFile();
        RtpYamlConfig cfg = RtpYamlConfig.parse(SAMPLE);
        cfg.save(target);

        String onDisk = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        assertTrue(onDisk.contains("# header comment"), "top-level block comment preserved");
        assertTrue(onDisk.contains("# cooldown explanation"), "above-key block comment preserved");
        assertTrue(onDisk.contains("  # backend type"), "nested block comment + indent preserved");
    }

    @Test
    @DisplayName("load() of empty/non-existent file yields empty doc that saves cleanly")
    void loadEmptyOrMissingThenSave(@TempDir Path dir) throws IOException {
        File missing = dir.resolve("missing.yml").toFile();
        RtpYamlConfig cfg = RtpYamlConfig.load(missing);
        cfg.save(missing);
        assertTrue(missing.isFile(), "save() must create the file even from an empty doc");
    }
}
