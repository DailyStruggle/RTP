package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gap-filling coverage for block-comment preservation beyond the basic
 * cases in {@link RtpYamlBlockCommentRoundTripTest}. Targets edge cases
 * that real shipped configs exercise: multi-line comment blocks,
 * deeply-nested keys, comments above list items, comments preserved
 * through {@code set()} on the same key, document-leading/trailing
 * comments, and the full file-load → mutate → save → reload cycle
 * on real disk.
 *
 * <p>ADR-025 §Migration step 4 / ADR-042 block-only scope.</p>
 */
class RtpYamlBlockCommentPreservationTest {

    @Test
    @DisplayName("multi-line block comment (3+ lines) above a key survives re-emit")
    void multiLineBlockCommentRoundTrip() {
        String src = ""
                + "# line one\n"
                + "# line two\n"
                + "# line three\n"
                + "# line four\n"
                + "teleportDelay: 2\n";
        String out = RtpYamlWriter.emit(RtpYamlReader.parse(src));
        assertEquals(src, out);
    }

    @Test
    @DisplayName("block comment at 3-level-deep nesting survives re-emit")
    void deeplyNestedBlockCommentRoundTrip() {
        String src = ""
                + "network:\n"
                + "  redis:\n"
                + "    # whether redis sync is enabled\n"
                + "    enabled: false\n"
                + "    # redis server hostname\n"
                + "    host: \"127.0.0.1\"\n";
        String out = RtpYamlWriter.emit(RtpYamlReader.parse(src));
        assertEquals(src, out);
    }

    @Test
    @DisplayName("document-leading comment block survives re-emit")
    void leadingDocumentCommentRoundTrip() {
        String src = ""
                + "# --- RTP Core Configuration ---\n"
                + "# Documentation: https://example.com\n"
                + "\n"
                + "# Wait time before teleport\n"
                + "teleportDelay: 2\n";
        String out = RtpYamlWriter.emit(RtpYamlReader.parse(src));
        assertEquals(src, out);
    }

    @Test
    @DisplayName("block comment above a list item is preserved")
    void blockCommentAboveListItem() {
        String src = ""
                + "placeholders:\n"
                + "  # P0\n"
                + "  - \"[RTP]\"\n";
        String out = RtpYamlWriter.emit(RtpYamlReader.parse(src));
        assertEquals(src, out);
    }

    @Test
    @DisplayName("block comment is preserved when the same key's value is overwritten")
    void blockCommentSurvivesSameKeySet() {
        String src = ""
                + "# delay comment must survive\n"
                + "teleportDelay: 2\n";
        RtpYamlConfig cfg = RtpYamlConfig.parse(src);
        cfg.set("teleportDelay", 5);
        String out = cfg.saveToString();
        assertTrue(out.contains("# delay comment must survive"),
                "block comment lost after set() on same key:\n" + out);
        assertTrue(out.contains("teleportDelay: 5"), out);
    }

    @Test
    @DisplayName("block comments preserved through file save → reload cycle")
    void blockCommentsThroughDiskRoundTrip(@TempDir Path dir) throws IOException {
        String src = ""
                + "# top header\n"
                + "# second line of header\n"
                + "teleportDelay: 2\n"
                + "database:\n"
                + "  # yaml, sqlite, mysql, or postgresql\n"
                + "  type: \"sqlite\"\n";
        File f = dir.resolve("config.yml").toFile();
        Files.write(f.toPath(), src.getBytes(StandardCharsets.UTF_8));

        RtpYamlConfig cfg = RtpYamlConfig.load(f);
        cfg.set("teleportDelay", 7);
        cfg.save();

        String onDisk = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue(onDisk.contains("# top header"), onDisk);
        assertTrue(onDisk.contains("# second line of header"), onDisk);
        assertTrue(onDisk.contains("# yaml, sqlite, mysql, or postgresql"), onDisk);
        assertTrue(onDisk.contains("teleportDelay: 7"), onDisk);
        assertTrue(onDisk.contains("type: \"sqlite\""), onDisk);
    }

    @Test
    @DisplayName("blank line between comment and key is preserved")
    void blankLineSeparatedCommentRoundTrip() {
        String src = ""
                + "# detached comment\n"
                + "\n"
                + "teleportDelay: 2\n";
        String out = RtpYamlWriter.emit(RtpYamlReader.parse(src));
        assertEquals(src, out);
    }

    @Test
    @DisplayName("mixed nested + sibling comments preserved across full re-emit")
    void mixedNestedAndSiblingComments() {
        String src = ""
                + "# A\n"
                + "alpha: 1\n"
                + "# B-parent\n"
                + "beta:\n"
                + "  # B.x\n"
                + "  x: 10\n"
                + "  # B.y\n"
                + "  y: 20\n"
                + "# C\n"
                + "gamma: 3\n";
        String out = RtpYamlWriter.emit(RtpYamlReader.parse(src));
        assertEquals(src, out);
    }
}
