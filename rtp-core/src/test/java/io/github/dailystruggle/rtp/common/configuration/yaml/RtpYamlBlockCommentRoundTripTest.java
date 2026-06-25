package io.github.dailystruggle.rtp.common.configuration.yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the comment-preservation contract for the in-house YAML
 * substrate (ADR-025 §Migration step 4, ADR-042 block-only scope).
 */
class RtpYamlBlockCommentRoundTripTest {

    @Test
    @DisplayName("ADR-025: block comments above a top-level key survive load→emit")
    void topLevelBlockCommentRoundTrip() {
        String src = ""
                + "# header comment\n"
                + "# second line\n"
                + "teleportDelay: 2\n";
        String out = RtpYamlWriter.emit(RtpYamlReader.parse(src));
        assertEquals(src, out);
    }

    @Test
    @DisplayName("ADR-025: block comments survive set() on a different key")
    void blockCommentSurvivesUnrelatedSet() {
        String src = ""
                + "# delay comment\n"
                + "teleportDelay: 2\n"
                + "# cooldown comment\n"
                + "teleportCooldown: 300\n";
        RtpYamlConfig cfg = RtpYamlConfig.parse(src);
        cfg.set("teleportCooldown", 600);
        String out = cfg.saveToString();
        assertTrue(out.contains("# delay comment"), out);
        assertTrue(out.contains("# cooldown comment"), out);
        assertTrue(out.contains("teleportCooldown: 600"), out);
    }

    @Test
    @DisplayName("ADR-025: nested key comments preserved across re-emit")
    void nestedCommentRoundTrip() {
        String src = ""
                + "database:\n"
                + "  # yaml, sqlite, mysql, or postgresql\n"
                + "  type: \"sqlite\"\n"
                + "  port: 3306\n";
        String out = RtpYamlWriter.emit(RtpYamlReader.parse(src));
        assertEquals(src, out);
    }

    @Test
    @DisplayName("ADR-025: round-trip is idempotent (emit∘parse∘emit∘parse == emit∘parse)")
    void idempotentDoubleRoundTrip() {
        String src = ""
                + "# top\n"
                + "consoleCommands:\n"
                + "  - \"say hi\"\n"
                + "database:\n"
                + "  type: \"sqlite\"\n"
                + "  port: 3306\n"
                + "version: \"3.0\"\n";
        String once = RtpYamlWriter.emit(RtpYamlReader.parse(src));
        String twice = RtpYamlWriter.emit(RtpYamlReader.parse(once));
        assertEquals(once, twice);
    }

    @Test
    @DisplayName("file header (blank-line-separated leading block) lifts off the first key onto the root")
    void documentHeaderLiftsOffFirstKey() {
        String src = ""
                + "# --- RTP Core Configuration ---\n"
                + "# Pick your language in language.yml.\n"
                + "\n"
                + "# Seconds to wait before teleporting. 0 = right away.\n"
                + "teleportDelay: 2\n";
        RtpYamlMapping root = RtpYamlReader.parse(src);
        // Header lands on the root mapping, not on the first key.
        assertEquals(java.util.List.of(
                        "--- RTP Core Configuration ---",
                        "Pick your language in language.yml."),
                root.blockComments());
        // The first key keeps only its own comment (what hover text would show).
        RtpYamlSection section = new RtpYamlSection(root);
        assertEquals("Seconds to wait before teleporting. 0 = right away.",
                section.getComment("teleportDelay"));
        // And the layout round-trips byte-for-byte.
        assertEquals(src, RtpYamlWriter.emit(root));
    }

    @Test
    @DisplayName("a leading comment glued to the first key (no blank line) is NOT treated as a file header")
    void gluedLeadingCommentStaysOnFirstKey() {
        String src = ""
                + "# Seconds to wait before teleporting. 0 = right away.\n"
                + "teleportDelay: 2\n";
        RtpYamlMapping root = RtpYamlReader.parse(src);
        assertTrue(root.blockComments().isEmpty(), "no file header expected");
        String out = RtpYamlWriter.emit(root);
        assertEquals(src, out);
    }

    @Test
    @DisplayName("quoted-value containing '#' is not split")
    void hexColorInQuotedString() {
        String src = ""
                + "# above the message key\n"
                + "noLocationsQueued: \"#f5eb73[P0] no locations ready\"\n";
        String out = RtpYamlWriter.emit(RtpYamlReader.parse(src));
        assertEquals(src, out);
    }
}
