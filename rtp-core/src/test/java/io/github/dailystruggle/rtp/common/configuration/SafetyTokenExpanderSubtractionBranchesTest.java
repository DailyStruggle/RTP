package io.github.dailystruggle.rtp.common.configuration;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Set-subtraction branches of {@link SafetyTokenExpander} (ADR-017 section 1): tag subtrahends,
 * unresolved subtrahend/base tags, null tag members, predicated bases and duplicate tokens.
 */
@DisplayName("SafetyTokenExpander - subtraction branch coverage")
class SafetyTokenExpanderSubtractionBranchesTest {

  @TempDir
  Path tempDir;

  private File pluginDir;
  private RTPServerAccessor originalAccessor;
  private RTPScheduler originalScheduler;

  @BeforeEach
  void setUp() {
    pluginDir = tempDir.toFile();
    originalAccessor = RTP.serverAccessor;
    originalScheduler = RTP.scheduler;
    RTP.serverAccessor = mock(RTPServerAccessor.class);
    // No scheduler: unresolved tags must not schedule deferred retries in this fixture.
    RTP.scheduler = null;
  }

  @AfterEach
  void tearDown() {
    RTP.serverAccessor = originalAccessor;
    RTP.scheduler = originalScheduler;
  }

  private ConfigParser<BlocksKeys> parserFor(String yaml) throws IOException {
    YamlFileDatabase db = new YamlFileDatabase(pluginDir);
    Files.writeString(new File(pluginDir, "safety.yml").toPath(), yaml);
    return new ConfigParser<>(BlocksKeys.class, "safety", "1.0", pluginDir, db) {
      @Override
      public InputStream getResourceFromJar(String filename) {
        return new ByteArrayInputStream("version: \"1.0\"\n".getBytes());
      }
    };
  }

  private static List<?> listOf(ConfigParser<BlocksKeys> parser, BlocksKeys key) {
    Object value = parser.getConfigValue(key, null);
    assertTrue(value instanceof List, key + " should be a list but was " + value);
    return (List<?>) value;
  }

  private static Map<String, Set<String>> snapshot() {
    Map<String, Set<String>> snapshot = new HashMap<>();
    Set<String> leaves = new HashSet<>();
    leaves.add("minecraft:oak_leaves");
    leaves.add("minecraft:azalea_leaves");
    leaves.add("minecraft:flowering_azalea_leaves");
    leaves.add(null);
    snapshot.put("minecraft:leaves", leaves);
    snapshot.put("minecraft:azaleas",
        Set.of("minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves"));
    snapshot.put("minecraft:slabs",
        Set.of("minecraft:oak_slab", "minecraft:birch_slab", "minecraft:stone_slab"));
    return snapshot;
  }

  @Test
  @DisplayName("Tag subtrahends remove their members; unknown subtrahend tags and null members are ignored")
  void tagSubtrahendsAndNullMembers() throws IOException {
    ConfigParser<BlocksKeys> parser = parserFor(
        "version: \"1.0\"\n"
            + "airBlocks:\n"
            + "  - \"#minecraft:leaves - #minecraft:azaleas - #minecraft:unknown\"\n"
            + "  - \"#minecraft:leaves - #minecraft:azaleas - #minecraft:unknown\"\n"
            + "unsafeBlocks:\n"
            + "  - \"LAVA\"\n");
    when(RTP.serverAccessor.blockTagSnapshot()).thenReturn(snapshot());

    SafetyTokenExpander.expandAndApply(parser);

    assertEquals(List.of("OAK_LEAVES"), listOf(parser, BlocksKeys.airBlocks),
        "duplicate token must not add OAK_LEAVES twice");
  }

  @Test
  @DisplayName("Predicated tag base writes per-member predicated tokens excluding subtracted materials")
  void predicatedTagBase() throws IOException {
    ConfigParser<BlocksKeys> parser = parserFor(
        "version: \"1.0\"\n"
            + "airBlocks:\n"
            + "  - \"AIR\"\n"
            + "unsafeBlocks:\n"
            + "  - \"#minecraft:slabs[type=bottom] - OAK_SLAB\"\n"
            + "  - \"#minecraft:slabs[type=bottom] - OAK_SLAB\"\n");
    when(RTP.serverAccessor.blockTagSnapshot()).thenReturn(snapshot());

    SafetyTokenExpander.expandAndApply(parser);

    List<?> unsafe = listOf(parser, BlocksKeys.unsafeBlocks);
    assertEquals(Set.of("BIRCH_SLAB[type=bottom]", "STONE_SLAB[type=bottom]"), new HashSet<>(unsafe));
    assertEquals(2, unsafe.size(), "duplicates are collapsed: " + unsafe);
  }

  @Test
  @DisplayName("Unresolved base tag with subtraction is preserved verbatim once")
  void unresolvedBaseTagPreserved() throws IOException {
    String token = "#minecraft:missing - OAK_LEAVES";
    ConfigParser<BlocksKeys> parser = parserFor(
        "version: \"1.0\"\n"
            + "airBlocks:\n"
            + "  - \"" + token + "\"\n"
            + "  - \"" + token + "\"\n"
            + "unsafeBlocks:\n"
            + "  - \"LAVA\"\n");
    when(RTP.serverAccessor.blockTagSnapshot()).thenReturn(snapshot());

    SafetyTokenExpander.expandAndApply(parser);

    assertEquals(List.of(token), listOf(parser, BlocksKeys.airBlocks));
  }

  @Test
  @DisplayName("Material bases with subtraction: predicated kept verbatim, plain canonicalised, both deduplicated")
  void materialBasesWithSubtraction() throws IOException {
    String predicated = "CAMPFIRE[lit=true] - LAVA";
    ConfigParser<BlocksKeys> parser = parserFor(
        "version: \"1.0\"\n"
            + "airBlocks:\n"
            + "  - \"AIR\"\n"
            + "unsafeBlocks:\n"
            + "  - \"" + predicated + "\"\n"
            + "  - \"" + predicated + "\"\n"
            + "  - \"magma_block - DIRT\"\n"
            + "  - \"MAGMA_BLOCK - DIRT\"\n"
            + "  - \"WATER - #minecraft:unknown\"\n");
    when(RTP.serverAccessor.blockTagSnapshot()).thenReturn(snapshot());

    SafetyTokenExpander.expandAndApply(parser);

    assertEquals(List.of(predicated, "MAGMA_BLOCK", "WATER"), listOf(parser, BlocksKeys.unsafeBlocks));
  }
}
