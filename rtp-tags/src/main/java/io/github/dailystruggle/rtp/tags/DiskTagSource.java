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
 * <p>The data root contains namespace subdirectories, structured as in data packs:
 * {@code <dataRoot>/<namespace>/tags/{block,blocks}/...json}.
 *
 * <p>Probes both {@code tags/block} (>= 1.21) and {@code tags/blocks} (<= 1.20) for compatibility.
 * Failures are reported via the provided {@code rejectionSink} (consistent with REQ-RTP-S-004).
 *
 * <p>Immutable and thread-safe.
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
