package io.github.dailystruggle.rtp.tags;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Loads Minecraft block-tag JSON files from a filesystem "data root".
 *
 * <p>A data root is the directory that contains one subdirectory per namespace
 * — exactly as laid out inside a Minecraft server jar, a data pack, or a mod
 * resource tree:
 *
 * <pre>{@code
 * <dataRoot>/
 *   minecraft/
 *     tags/
 *       block/          (>= MC 1.21)
 *       blocks/         (<= MC 1.20)
 *   mymod/
 *     tags/
 *       block/
 * }</pre>
 *
 * <p>This implementation probes both {@code tags/block} and {@code tags/blocks}
 * so it is forward- and backward-compatible across the 1.21 rename. Each
 * discovered JSON file is parsed via {@link TagFileParser}; parse failures are
 * reported through the supplied {@code rejectionSink} and the offending file
 * is skipped — never silently dropped (matching the REQ-RTP-S-004 contract
 * extended to the tag loader).
 *
 * <p>Thread-safety: instances are immutable and the {@link #loadBlockTags()}
 * call performs only read-only filesystem I/O, so instances may be shared.
 */
public final class DiskTagSource implements TagSource {

  private final Path dataRoot;
  private final java.util.function.BiConsumer<String, Throwable> rejectionSink;

  /**
   * @param dataRoot filesystem path whose direct children are namespace
   *     directories; must not be {@code null}.
   * @param rejectionSink optional consumer invoked with the offending path and
   *     the failure cause when a file cannot be parsed; may be {@code null} to
   *     suppress reporting.
   */
  public DiskTagSource(Path dataRoot, java.util.function.BiConsumer<String, Throwable> rejectionSink) {
    this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot");
    this.rejectionSink = rejectionSink;
  }

  /** Convenience overload that suppresses rejection reporting. */
  public DiskTagSource(Path dataRoot) {
    this(dataRoot, null);
  }

  @Override
  public List<TagFile> loadBlockTags() {
    if (!Files.isDirectory(dataRoot)) return Collections.emptyList();
    List<TagFile> out = new ArrayList<>();
    try (Stream<Path> namespaces = Files.list(dataRoot)) {
      namespaces
          .filter(Files::isDirectory)
          .forEach(ns -> collectFromNamespace(ns, out));
    } catch (IOException e) {
      report(dataRoot.toString(), e);
    }
    return Collections.unmodifiableList(out);
  }

  private void collectFromNamespace(Path namespace, List<TagFile> out) {
    String nsName = namespace.getFileName().toString();
    // Probe both singular (>=1.21) and plural (<=1.20) directories.
    for (String sub : new String[] {"tags/block", "tags/blocks"}) {
      Path blockTags = namespace.resolve(sub);
      if (!Files.isDirectory(blockTags)) continue;
      try (Stream<Path> walk = Files.walk(blockTags)) {
        walk.filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().endsWith(".json"))
            .forEach(p -> parseOne(p, blockTags, nsName, out));
      } catch (IOException e) {
        report(blockTags.toString(), e);
      }
    }
  }

  private void parseOne(Path jsonFile, Path blockTagsRoot, String nsName, List<TagFile> out) {
    try {
      String json = Files.readString(jsonFile, StandardCharsets.UTF_8);
      Path rel = blockTagsRoot.relativize(jsonFile);
      // Strip ".json" and convert platform separators to forward slashes so the
      // canonical id is identical on Windows and POSIX.
      String raw = rel.toString().replace('\\', '/');
      String localId = raw.substring(0, raw.length() - ".json".length());
      TagFile parsed = TagFileParser.parse(nsName + ":" + localId, json);
      out.add(parsed);
    } catch (IOException | RuntimeException e) {
      report(jsonFile.toString(), e);
    }
  }

  private void report(String path, Throwable t) {
    if (rejectionSink == null) return;
    try {
      rejectionSink.accept(path, t);
    } catch (RuntimeException ignored) {
      // Never let a misbehaving sink break tag loading.
    }
  }
}
