package io.github.dailystruggle.rtp.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests covering {@link TagResolver} + {@link DiskTagSource} +
 * {@link TagFileParser} against filesystem fixtures built in a per-test
 * temp directory. Verifies:
 *
 * <ul>
 *   <li>Snapshot keys in {@code namespace:path} form, matching
 *       {@code RTPServerAccessor.blockTagSnapshot()} (e.g. {@code "minecraft:leaves"}).</li>
 *   <li>Upper-cased, namespace-stripped material name values.</li>
 *   <li>Fixed-point expansion of {@code #}-references across files.</li>
 *   <li>{@code replace=true} semantics vs union-append default.</li>
 *   <li>Cycle-tolerance (no stack overflow on self- or mutual-references).</li>
 *   <li>Both {@code tags/block} (>=1.21) and {@code tags/blocks} (<=1.20)
 *       directory layouts in a single pass.</li>
 *   <li>Immutability of the returned snapshot.</li>
 * </ul>
 */
class TagResolverTest {

  @TempDir Path tmp;

  private Path dataRoot() throws IOException {
    Path root = tmp.resolve("data");
    Files.createDirectories(root);
    return root;
  }

  private void writeTag(Path dataRoot, String ns, String layout, String localId, String body)
      throws IOException {
    Path file = dataRoot.resolve(ns).resolve(layout).resolve(localId + ".json");
    Files.createDirectories(file.getParent());
    Files.writeString(file, body, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("resolves a single vanilla-shape tag file")
  void resolvesSingleTagFile() throws IOException {
    Path root = dataRoot();
    writeTag(
        root,
        "minecraft",
        "tags/block",
        "leaves",
        "{\"replace\":false,\"values\":[\"minecraft:oak_leaves\",\"minecraft:birch_leaves\"]}");

    Map<String, Set<String>> snapshot = TagResolver.resolve(List.of(new DiskTagSource(root)));

    assertEquals(Set.of("OAK_LEAVES", "BIRCH_LEAVES"), snapshot.get("minecraft:leaves"));
  }

  @Test
  @DisplayName("expands nested tag references transitively")
  void expandsNestedReferences() throws IOException {
    Path root = dataRoot();
    writeTag(
        root,
        "minecraft",
        "tags/block",
        "logs",
        "{\"values\":[\"minecraft:oak_log\",\"#minecraft:stripped_logs\"]}");
    writeTag(
        root,
        "minecraft",
        "tags/block",
        "stripped_logs",
        "{\"values\":[\"minecraft:stripped_oak_log\"]}");

    Map<String, Set<String>> snapshot = TagResolver.resolve(List.of(new DiskTagSource(root)));

    assertEquals(
        Set.of("OAK_LOG", "STRIPPED_OAK_LOG"), snapshot.get("minecraft:logs"));
    assertEquals(Set.of("STRIPPED_OAK_LOG"), snapshot.get("minecraft:stripped_logs"));
  }

  @Test
  @DisplayName("replace=true drops prior values for the same tag id")
  void replaceOverridesPrior() throws IOException {
    Path root = dataRoot();
    // Earlier source: base vanilla.
    Path base = root.resolve("base");
    writeTag(
        base,
        "minecraft",
        "tags/block",
        "leaves",
        "{\"values\":[\"minecraft:oak_leaves\"]}");
    // Later source: data-pack that replaces.
    Path pack = root.resolve("pack");
    writeTag(
        pack,
        "minecraft",
        "tags/block",
        "leaves",
        "{\"replace\":true,\"values\":[\"minecraft:cherry_leaves\"]}");

    Map<String, Set<String>> snapshot =
        TagResolver.resolve(List.of(new DiskTagSource(base), new DiskTagSource(pack)));

    assertEquals(Set.of("CHERRY_LEAVES"), snapshot.get("minecraft:leaves"));
  }

  @Test
  @DisplayName("default append merges values from multiple sources")
  void appendMergesSources() throws IOException {
    Path root = dataRoot();
    Path a = root.resolve("a");
    writeTag(a, "minecraft", "tags/block", "leaves", "{\"values\":[\"minecraft:oak_leaves\"]}");
    Path b = root.resolve("b");
    writeTag(b, "minecraft", "tags/block", "leaves", "{\"values\":[\"minecraft:birch_leaves\"]}");

    Map<String, Set<String>> snapshot =
        TagResolver.resolve(List.of(new DiskTagSource(a), new DiskTagSource(b)));

    assertEquals(Set.of("OAK_LEAVES", "BIRCH_LEAVES"), snapshot.get("minecraft:leaves"));
  }

  @Test
  @DisplayName("tolerates self-referential cycles without stack overflow")
  void toleratesSelfCycle() throws IOException {
    Path root = dataRoot();
    writeTag(
        root,
        "minecraft",
        "tags/block",
        "loop",
        "{\"values\":[\"#minecraft:loop\",\"minecraft:stone\"]}");

    Map<String, Set<String>> snapshot = TagResolver.resolve(List.of(new DiskTagSource(root)));

    assertEquals(Set.of("STONE"), snapshot.get("minecraft:loop"));
  }

  @Test
  @DisplayName("reads legacy 1.20 'tags/blocks' directory layout")
  void readsLegacyBlocksDir() throws IOException {
    Path root = dataRoot();
    writeTag(
        root,
        "minecraft",
        "tags/blocks",
        "leaves",
        "{\"values\":[\"minecraft:oak_leaves\"]}");

    Map<String, Set<String>> snapshot = TagResolver.resolve(List.of(new DiskTagSource(root)));

    assertEquals(Set.of("OAK_LEAVES"), snapshot.get("minecraft:leaves"));
  }

  @Test
  @DisplayName("object-form entry with 'id' field is resolved like a string")
  void objectFormEntryResolves() throws IOException {
    Path root = dataRoot();
    writeTag(
        root,
        "minecraft",
        "tags/block",
        "leaves",
        "{\"values\":[{\"id\":\"minecraft:oak_leaves\",\"required\":false}]}");

    Map<String, Set<String>> snapshot = TagResolver.resolve(List.of(new DiskTagSource(root)));

    assertEquals(Set.of("OAK_LEAVES"), snapshot.get("minecraft:leaves"));
  }

  @Test
  @DisplayName("malformed JSON is reported to the rejection sink and skipped")
  void malformedJsonReported() throws IOException {
    Path root = dataRoot();
    writeTag(root, "minecraft", "tags/block", "broken", "{ this is not json");
    writeTag(root, "minecraft", "tags/block", "ok", "{\"values\":[\"minecraft:stone\"]}");

    List<String> rejected = new ArrayList<>();
    DiskTagSource src = new DiskTagSource(root, (path, cause) -> rejected.add(path));

    Map<String, Set<String>> snapshot = TagResolver.resolve(List.of(src));

    assertEquals(Set.of("STONE"), snapshot.get("minecraft:ok"));
    assertFalse(snapshot.containsKey("minecraft:broken"));
    assertEquals(1, rejected.size());
    assertTrue(rejected.get(0).endsWith("broken.json"));
  }

  @Test
  @DisplayName("missing data root returns an empty snapshot, not an exception")
  void missingDataRootIsEmpty() {
    Map<String, Set<String>> snapshot =
        TagResolver.resolve(List.of(new DiskTagSource(tmp.resolve("does-not-exist"))));
    assertTrue(snapshot.isEmpty());
  }

  @Test
  @DisplayName("returned snapshot is fully immutable")
  void snapshotIsImmutable() throws IOException {
    Path root = dataRoot();
    writeTag(root, "minecraft", "tags/block", "leaves", "{\"values\":[\"minecraft:oak_leaves\"]}");

    Map<String, Set<String>> snapshot = TagResolver.resolve(List.of(new DiskTagSource(root)));

    assertThrows(UnsupportedOperationException.class, () -> snapshot.put("x", Set.of()));
    Set<String> leaves = snapshot.get("minecraft:leaves");
    assertThrows(UnsupportedOperationException.class, () -> leaves.add("X"));
  }

  @Test
  @DisplayName("empty sources list yields an empty map")
  void emptySourcesList() {
    assertTrue(TagResolver.resolve(List.of()).isEmpty());
  }

  @Test
  @DisplayName("unknown registry folders in the same namespace are ignored")
  void unrelatedRegistriesIgnored() throws IOException {
    Path root = dataRoot();
    writeTag(
        root,
        "minecraft",
        "tags/item",
        "logs",
        "{\"values\":[\"minecraft:oak_log\"]}");
    writeTag(
        root, "minecraft", "tags/block", "leaves", "{\"values\":[\"minecraft:oak_leaves\"]}");

    Map<String, Set<String>> snapshot = TagResolver.resolve(List.of(new DiskTagSource(root)));

    assertFalse(snapshot.containsKey("minecraft:logs"));
    assertEquals(Set.of("OAK_LEAVES"), snapshot.get("minecraft:leaves"));
  }

  @Test
  @DisplayName("non-namespaced reference defaults to 'minecraft'")
  void bareReferenceDefaultsMinecraft() throws IOException {
    Path root = dataRoot();
    // 'values' contains a bare '#leaves' (no namespace) - vanilla treats as minecraft:leaves.
    writeTag(
        root,
        "minecraft",
        "tags/block",
        "all_leaves",
        "{\"values\":[\"#leaves\"]}");
    writeTag(
        root,
        "minecraft",
        "tags/block",
        "leaves",
        "{\"values\":[\"minecraft:oak_leaves\"]}");

    Map<String, Set<String>> snapshot = TagResolver.resolve(List.of(new DiskTagSource(root)));

    assertEquals(Set.of("OAK_LEAVES"), snapshot.get("minecraft:all_leaves"));
  }
}
