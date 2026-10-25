package io.github.dailystruggle.rtp.tags;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.block.BlockStateString;
import io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet;
import io.github.dailystruggle.rtp.api.safety.SafetyCompilationCache;
import io.github.dailystruggle.rtp.api.safety.SafetyToken;
import io.github.dailystruggle.rtp.api.safety.SafetyTokenParser;
import io.github.dailystruggle.rtp.api.safety.StatePredicate;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mechanical package-boundary verification for {@code :tags-api} (ADR-017).
 *
 * <p>Asserts that no production class in {@code api/tags-api} imports or references
 * {@code org.bukkit.*}, {@code net.minecraft.*}, {@code io.github.dailystruggle.rtp.common.*},
 * or {@code io.github.dailystruggle.rtp.api.world.*}.
 */
@DisplayName("ADR-017 - tags-api package boundary")
class TagsApiBoundaryTest {

  private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
      "org.bukkit.",
      "net.minecraft.",
      "io.github.dailystruggle.rtp.common.",
      "io.github.dailystruggle.rtp.api.world."
  );

  private static final List<String> FORBIDDEN_INTERNAL_DESCRIPTORS = List.of(
      "org/bukkit/",
      "net/minecraft/",
      "io/github/dailystruggle/rtp/common/",
      "io/github/dailystruggle/rtp/api/world/"
  );

  private static final List<Class<?>> PRODUCTION_CLASSES = List.of(
      DiskTagSource.class,
      TagFile.class,
      TagFileParser.class,
      TagResolver.class,
      TagSource.class,
      TinyJsonReader.class,
      BlockStateString.class,
      CompiledUnsafeSet.class,
      SafetyCompilationCache.class,
      SafetyToken.class,
      SafetyTokenParser.class,
      StatePredicate.class
  );

  @Test
  @DisplayName("No source file in api/tags-api imports forbidden platform, rtp-core, or rtp-api.world packages")
  void noSourceFileImportsForbiddenPackages() throws IOException {
    Path srcRoot = locateMainSourceRoot();
    List<Path> javaFiles = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(srcRoot)) {
      walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
          .forEach(javaFiles::add);
    }
    assertTrue(
        javaFiles.size() >= PRODUCTION_CLASSES.size(),
        "Expected at least " + PRODUCTION_CLASSES.size() + " production source files under " + srcRoot);

    for (Path file : javaFiles) {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        String trimmed = lines.get(i).trim();
        if (!trimmed.startsWith("import ")) {
          continue;
        }
        for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
          assertFalse(
              trimmed.contains(forbidden),
              "Forbidden import '" + forbidden + "*' in " + file + ":" + (i + 1) + " -> " + trimmed);
        }
      }
    }
  }

  @Test
  @DisplayName("No compiled class in api/tags-api references forbidden bytecode descriptors")
  void noCompiledClassReferencesForbiddenPackages() throws IOException {
    for (Class<?> clazz : PRODUCTION_CLASSES) {
      String resourcePath = clazz.getName().replace('.', '/') + ".class";
      try (InputStream in = clazz.getClassLoader().getResourceAsStream(resourcePath)) {
        assertTrue(in != null, "Missing classfile resource: " + resourcePath);
        byte[] bytes = in.readAllBytes();
        String asciiView = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String forbiddenDescriptor : FORBIDDEN_INTERNAL_DESCRIPTORS) {
          assertFalse(
              asciiView.contains(forbiddenDescriptor),
              "Class " + clazz.getName() + " references forbidden package descriptor '"
                  + forbiddenDescriptor + "'");
        }
      }
    }
  }

  private static Path locateMainSourceRoot() {
    Path local = Path.of("src", "main", "java");
    if (Files.isDirectory(local)) {
      return local;
    }
    Path fromRepoRoot = Path.of("api", "tags-api", "src", "main", "java");
    if (Files.isDirectory(fromRepoRoot)) {
      return fromRepoRoot;
    }
    throw new IllegalStateException("Cannot locate api/tags-api/src/main/java from working directory");
  }
}
