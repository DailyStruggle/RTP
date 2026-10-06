package io.github.dailystruggle.rtp.common.importer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Foreign config importer keeps imported world/region/zone names inside the target directory")
class ForeignImporterPathContainmentTest {

  @TempDir Path tempDir;

  static class Importer extends AbstractForeignConfigImporter {
    @Override
    public String sourceName() {
      return "dummy";
    }

    @Override
    public List<String> directoryAliases() {
      return List.of();
    }

    @Override
    public List<String> indicatorFiles() {
      return List.of();
    }

    @Override
    public boolean canImport(Path directory) {
      return false;
    }

    @Override
    public ImportResult importConfiguration(Path src, Path dst, boolean overwrite) {
      throw new UnsupportedOperationException();
    }
  }

  private static AbstractForeignConfigImporter.DiscoveredWorldRegion region(
      String name, String world, List<String> extra) {
    return new AbstractForeignConfigImporter.DiscoveredWorldRegion(
        name, world, "CIRCLE", 64, 1000, 0, 0, 64, 320, 0.0, -1, null, extra);
  }

  @Test
  @DisplayName("resolveSafeChild rejects traversal, separators, drive prefixes and leading dots")
  void resolveSafeChildRejectsEscapes() {
    Path dir = tempDir.resolve("worlds");
    for (String bad :
        new String[] {
          "../evil.yml", "..\\evil.yml", "a/b.yml", "a\\b.yml", "C:evil.yml", "/abs.yml",
          "..", ".hidden.yml", "", "nul\u0000.yml", "tab\t.yml", "x|y.yml"
        }) {
      assertNull(AbstractForeignConfigImporter.resolveSafeChild(dir, bad), bad);
    }
    assertNull(AbstractForeignConfigImporter.resolveSafeChild(dir, null));
    Path ok = AbstractForeignConfigImporter.resolveSafeChild(dir, "world_nether.yml");
    assertNotNull(ok);
    assertEquals(dir.toAbsolutePath().normalize(), ok.getParent());
    assertNotNull(AbstractForeignConfigImporter.resolveSafeChild(dir, "My World-2.yml"));
  }

  @Test
  @DisplayName("Crafted world and region names write nothing outside the destination")
  void craftedNamesAreSkipped() throws IOException {
    Path dest = Files.createDirectories(tempDir.resolve("plugins").resolve("RTP"));
    Path victim = tempDir.resolve("plugins").resolve("Essentials").resolve("config.yml");

    List<Path> written = new ArrayList<>();
    List<String> mapped = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    new Importer()
        .emitRegionsAndWorlds(
            List.of(
                region("evil", "../../Essentials/config", null),
                region("../../Essentials/config_region", "world", null),
                region("good", "world", List.of("../../Essentials/config", "world_nether"))),
            dest,
            true,
            written,
            mapped,
            errors);

    assertFalse(Files.exists(victim), "file escaped the destination: " + victim);
    Path destAbs = dest.toAbsolutePath().normalize();
    for (Path p : written) {
      assertTrue(p.toAbsolutePath().normalize().startsWith(destAbs), "escaped: " + p);
    }
    try (Stream<Path> all = Files.walk(tempDir)) {
      all.filter(Files::isRegularFile)
          .forEach(p -> assertTrue(p.toAbsolutePath().normalize().startsWith(destAbs), "escaped: " + p));
    }
    assertTrue(Files.exists(dest.resolve("regions").resolve("good_region.yml")));
    assertTrue(Files.exists(dest.resolve("worlds").resolve("world.yml")));
    assertTrue(Files.exists(dest.resolve("worlds").resolve("world_nether.yml")));
    assertEquals(3, errors.size(), errors.toString());
  }

  @Test
  @DisplayName("Crafted zone keys write nothing outside the actions directory")
  void craftedZoneKeysAreSkipped() throws IOException {
    Path src = Files.createDirectories(tempDir.resolve("plugins").resolve("Foreign"));
    Path dest = Files.createDirectories(tempDir.resolve("plugins").resolve("RTP"));
    Files.writeString(
        src.resolve("rtp_zones.yml"),
        """
        zones:
          'x/escaped\\evil':
            world: world
            x1: 0
            x2: 5
          "good_zone":
            world: world
            x1: 0
            x2: 5
        """);

    List<Path> written = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    new Importer()
        .mirrorZonesConfig(
            src, dest, true, new ArrayList<>(), written, new ArrayList<>(), errors);

    Path destAbs = dest.toAbsolutePath().normalize();
    try (Stream<Path> all = Files.walk(tempDir)) {
      all.filter(Files::isRegularFile)
          .filter(p -> !p.startsWith(src))
          .forEach(p -> assertTrue(p.toAbsolutePath().normalize().startsWith(destAbs), "escaped: " + p));
    }
    assertTrue(
        Files.exists(dest.resolve("definitions").resolve("actions").resolve("zone_good_zone.yml")));
    assertEquals(1, errors.size(), errors.toString());
  }
}
