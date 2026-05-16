package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Golden-file regression: every shipped {@code .yml} under
 * {@code rtp-plugin/src/main/resources/} must parse without error and
 * must re-emit to a byte-stable form (subsequent re-parse + re-emit
 * yields identical bytes). This guards both the no-inline-comments
 * contract (ADR-042) and the parser's coverage of the actual content
 * surface RTP ships.
 *
 * <p>If a shipped file uses a YAML feature outside the in-house subset,
 * this test fails loudly with the offending file path and line/column,
 * which is exactly what we want during Phase 1 (ADR-025 §Migration).</p>
 */
class RtpYamlShippedDefaultsGoldenFileTest {

    private static Path shippedRoot() {
        // rtp-core tests run with project root as CWD via Gradle.
        // Walk up from the working dir if necessary.
        Path cwd = Paths.get("").toAbsolutePath();
        // Try the standard relative path first.
        Path direct = cwd.resolve("rtp-plugin/src/main/resources");
        if (Files.isDirectory(direct)) return direct;
        // If the test runs from rtp-core/, go up one level.
        Path fromModule = cwd.getParent() != null
                ? cwd.getParent().resolve("rtp-plugin/src/main/resources")
                : null;
        if (fromModule != null && Files.isDirectory(fromModule)) return fromModule;
        return null; // No shipped defaults available in this run.
    }

    @TestFactory
    @DisplayName("every shipped .yml parses and re-emits idempotently")
    Stream<DynamicTest> everyShippedYamlIsIdempotent() throws IOException {
        Path root = shippedRoot();
        if (root == null) {
            return Stream.of(dynamicTest("shipped resources not on classpath (skipped)", () -> {}));
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".yml"))
                // plugin.yml is the Bukkit plugin manifest, parsed by the server (not by RTP's
                // own config layer). It legitimately uses flow sequences (authors: [...],
                // softdepend: [...]) which are outside the in-house parser's supported subset.
                .filter(p -> !p.getFileName().toString().equals("plugin.yml"))
                .forEach(files::add);
        }
        return files.stream().map(file -> dynamicTest(
                root.relativize(file).toString().replace('\\', '/'),
                () -> assertIdempotent(file)));
    }

    private static void assertIdempotent(Path file) throws IOException {
        String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        RtpYamlMapping once;
        try {
            once = RtpYamlReader.parse(src);
        } catch (RtpYamlParseException e) {
            fail("Parse failed for " + file + " — " + e.getMessage()
                    + " (messageKey=" + e.messageKey() + ")");
            return;
        }
        String emitted = RtpYamlWriter.emit(once);
        RtpYamlMapping twice = RtpYamlReader.parse(emitted);
        String reemitted = RtpYamlWriter.emit(twice);
        assertEquals(emitted, reemitted,
                "Double-emit not idempotent for " + file);
    }
}
