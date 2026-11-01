package io.github.dailystruggle.rtp.common.bit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ADR-121 section 7: ConfigSnapshot is a read-only candidate copy")
class ConfigSnapshotTest {
    static final String BROKEN_YAML = "radius: [1, 2\nworld: \"unterminated\n";

    @TempDir
    Path dir;

    @Test
    @DisplayName("reading a directory writes nothing and records unparsable files")
    void readIsSideEffectFree() throws IOException {
        write("config.yml", "version: 3.1\nradius: 10\n");
        write("definitions/regions/default.yml", "world: world\n");
        write("advanced/messages/system.yml", BROKEN_YAML);
        write("advanced/schematics/default.schem", "binary");
        write("logs/ignored.yml", "a: 1\n");
        List<String> before = listing();

        ConfigSnapshot snapshot = ConfigSnapshot.read(dir);

        assertEquals(before, listing(), "snapshot must not create, rename or delete files");
        assertEquals(10, ((Number) snapshot.document("config.yml").orElseThrow().get("radius")).intValue());
        assertTrue(snapshot.unparsable().containsKey("advanced/messages/system.yml"));
        assertTrue(snapshot.exists("advanced/schematics/default.schem"));
        assertFalse(snapshot.document("logs/ignored.yml").isPresent());
        assertEquals(1, snapshot.documentsIn("definitions/regions").size());
    }

    @Test
    @DisplayName("localized top-level keys map to canonical names through the ADR-076 dotfile")
    void localizedKeysAreCanonicalised() {
        ConfigSnapshot snapshot = ConfigSnapshot.of(Map.of(
                "config.yml", "rayon: 5\n",
                ".config.lang.yml", "radius: rayon\n",
                "definitions/regions/a.yml", "monde: w\n",
                "definitions/.regions.lang.yml", "world: monde\n"));

        ConfigSnapshot.Document config = snapshot.document("config.yml").orElseThrow();
        assertEquals(5, ((Number) config.get("radius")).intValue());
        assertEquals("rayon", config.localizedKey("radius"));
        assertEquals("w", snapshot.document("definitions/regions/a.yml").orElseThrow().get("world"));
        assertFalse(snapshot.document(".config.lang.yml").isPresent(), "rename maps are not documents");
    }

    @Test
    @DisplayName("an overlay yields a new candidate and leaves the base snapshot unchanged")
    void overlayIsCopyOnWrite() throws IOException {
        write("config.yml", "radius: 10\n");
        ConfigSnapshot base = ConfigSnapshot.read(dir);

        ConfigSnapshot candidate = base.withOverlay(Map.of("config.yml", BROKEN_YAML, "economy.yml", "price: 1\n"));

        assertTrue(candidate.unparsable().containsKey("config.yml"));
        assertTrue(candidate.document("economy.yml").isPresent());
        assertTrue(base.unparsable().isEmpty());
        assertEquals(10, ((Number) base.document("config.yml").orElseThrow().get("radius")).intValue());
        assertEquals("radius: 10\n", Files.readString(dir.resolve("config.yml")));
    }

    private void write(String relative, String text) throws IOException {
        Path target = dir.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }

    private List<String> listing() throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.map(path -> dir.relativize(path) + ":" + path.toFile().lastModified()).sorted().toList();
        }
    }
}
