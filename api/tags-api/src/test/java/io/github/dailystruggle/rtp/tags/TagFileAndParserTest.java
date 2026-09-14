package io.github.dailystruggle.rtp.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TagFileAndParserTest {

  @Test
  @DisplayName("TagFile value semantics, equality, factories, and toString")
  void tagFileValueSemantics() {
    TagFile f1 = TagFile.of("minecraft:leaves", false, List.of("minecraft:oak_leaves"));
    TagFile f2 = new TagFile("minecraft:leaves", false, List.of("minecraft:oak_leaves"));
    TagFile f3 = TagFile.of("minecraft:leaves", true, List.of("minecraft:oak_leaves"));
    TagFile f4 = TagFile.of("minecraft:logs", false, List.of("minecraft:oak_leaves"));
    TagFile f5 = TagFile.empty("minecraft:empty");

    assertEquals(f1, f2);
    assertEquals(f1.hashCode(), f2.hashCode());
    assertNotEquals(f1, f3);
    assertNotEquals(f1, f4);
    assertNotEquals(f1, "other");
    assertNotEquals(f1, null);
    assertEquals(f1, f1);

    assertEquals("minecraft:leaves", f1.namespacedId());
    assertFalse(f1.replace());
    assertEquals(List.of("minecraft:oak_leaves"), f1.values());

    assertEquals("minecraft:empty", f5.namespacedId());
    assertFalse(f5.replace());
    assertTrue(f5.values().isEmpty());

    String str = f1.toString();
    assertTrue(str.contains("minecraft:leaves"));
    assertTrue(str.contains("replace=false"));

    assertThrows(NullPointerException.class, () -> new TagFile(null, false, List.of()));
    assertThrows(NullPointerException.class, () -> new TagFile("id", false, null));
  }

  @Test
  @DisplayName("TagFileParser validation error branches")
  void parserValidationErrors() {
    // Non-object root
    assertThrows(IllegalArgumentException.class, () -> TagFileParser.parse("test:tag", "[\"a\"]"));
    assertThrows(IllegalArgumentException.class, () -> TagFileParser.parse("test:tag", "\"string\""));

    // Replace not boolean
    assertThrows(IllegalArgumentException.class, () -> TagFileParser.parse("test:tag", "{\"replace\": 123}"));
    assertThrows(IllegalArgumentException.class, () -> TagFileParser.parse("test:tag", "{\"replace\": \"true\"}"));

    // Values not array
    assertThrows(IllegalArgumentException.class, () -> TagFileParser.parse("test:tag", "{\"values\": 123}"));
    assertThrows(IllegalArgumentException.class, () -> TagFileParser.parse("test:tag", "{\"values\": \"item\"}"));

    // Value entry invalid type (number or boolean in values array)
    assertThrows(IllegalArgumentException.class, () -> TagFileParser.parse("test:tag", "{\"values\": [123]}"));
    assertThrows(IllegalArgumentException.class, () -> TagFileParser.parse("test:tag", "{\"values\": [true]}"));

    // Object entry with non-string id or missing id is dropped permissively
    TagFile tf = TagFileParser.parse("test:tag", "{\"values\": [{\"id\": 123}, {\"other\": \"val\"}, \"minecraft:valid\"]}");
    assertEquals(List.of("minecraft:valid"), tf.values());
  }

  @Test
  @DisplayName("DiskTagSource resilience with throwing sink and non-directory roots")
  void diskTagSourceResilience(@TempDir Path tempDir) throws IOException {
    Path nonExistent = tempDir.resolve("does-not-exist");
    DiskTagSource emptySource = new DiskTagSource(nonExistent);
    assertTrue(emptySource.loadBlockTags().isEmpty());

    // Source with throwing rejection sink
    Path corruptedDir = tempDir.resolve("corrupted");
    Path tagFile = corruptedDir.resolve("minecraft/tags/block/broken.json");
    Files.createDirectories(tagFile.getParent());
    Files.writeString(tagFile, "{ broken json ]");

    AtomicInteger callCount = new AtomicInteger(0);
    DiskTagSource throwingSinkSource = new DiskTagSource(corruptedDir, (path, ex) -> {
      callCount.incrementAndGet();
      throw new RuntimeException("sink failed intentionally");
    });

    List<TagFile> loaded = throwingSinkSource.loadBlockTags();
    assertTrue(loaded.isEmpty());
    assertEquals(1, callCount.get());
  }
}
