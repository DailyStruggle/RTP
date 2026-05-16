package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * End-to-end read → write → save → reload → similarity test over every
 * shipped {@code .yml} under {@code rtp-plugin/src/main/resources/}.
 *
 * <p>Unlike {@link RtpYamlShippedDefaultsGoldenFileTest}, which only
 * exercises in-memory parse∘emit∘parse∘emit byte-stability, this test
 * runs the full disk round-trip through {@link RtpYamlConfig#load(File)}
 * and {@link RtpYamlConfig#save(File)} (temp-file + atomic rename),
 * then reloads from disk and asserts:
 * <ul>
 *     <li>the saved bytes equal the canonical re-emit of the loaded source
 *         (idempotence under the file-backed write path); and</li>
 *     <li>the reloaded document's structural key surface
 *         ({@code getKeys(deep=true)}) matches the original's.</li>
 * </ul>
 */
class RtpYamlShippedReadWriteSaveSimilarityTest {

    private static Path shippedRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path direct = cwd.resolve("rtp-plugin/src/main/resources");
        if (Files.isDirectory(direct)) return direct;
        Path fromModule = cwd.getParent() != null
                ? cwd.getParent().resolve("rtp-plugin/src/main/resources")
                : null;
        if (fromModule != null && Files.isDirectory(fromModule)) return fromModule;
        return null;
    }

    @TempDir
    Path tmpDir;

    @TestFactory
    @DisplayName("every shipped .yml: read → write → save → reload yields similar document")
    Stream<DynamicTest> everyShippedYamlRoundTripsOnDisk() throws IOException {
        Path root = shippedRoot();
        if (root == null) {
            return Stream.of(dynamicTest("shipped resources not on classpath (skipped)", () -> {}));
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".yml"))
                .filter(p -> !p.getFileName().toString().equals("plugin.yml"))
                .forEach(files::add);
        }
        return files.stream().map(file -> dynamicTest(
                root.relativize(file).toString().replace('\\', '/'),
                () -> assertDiskRoundTrip(file)));
    }

    private void assertDiskRoundTrip(Path source) throws IOException {
        // 1. Read shipped file through the public API.
        File original = source.toFile();
        RtpYamlConfig loaded = RtpYamlConfig.load(original);

        // 2. Canonical emit (what save() should write).
        String canonical = loaded.saveToString();

        // 3. Write to a fresh temp location via save(File) — exercises the
        //    temp-file + atomic-rename code path.
        Path target = tmpDir.resolve(source.getFileName().toString());
        loaded.save(target.toFile());

        // 4. Saved bytes equal the canonical emit.
        String onDisk = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
        assertEquals(canonical, onDisk,
                "save(File) bytes differ from saveToString() for " + source);

        // 5. Reload from disk — must succeed and round-trip byte-stably.
        RtpYamlConfig reloaded = RtpYamlConfig.load(target.toFile());
        String reemitted = reloaded.saveToString();
        assertEquals(canonical, reemitted,
                "Reload→emit not idempotent for " + source);

        // 6. Structural similarity: deep key surface preserved.
        assertEquals(loaded.getKeys(true), reloaded.getKeys(true),
                "Deep key surface drifted across disk round-trip for " + source);

        // 7. No .tmp residue in target directory.
        try (Stream<Path> sib = Files.list(tmpDir)) {
            assertTrue(sib.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "Stray .tmp file after save for " + source);
        }
    }
}
