package io.github.dailystruggle.rtp.common.configuration;

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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SafetyTokenExpander - Set Subtraction Tests")
class SafetyTokenExpanderTest {

  @TempDir
  Path tempDir;

  private File pluginDir;
  private RTPServerAccessor originalAccessor;

  @BeforeEach
  void setUp() {
    pluginDir = tempDir.toFile();
    originalAccessor = RTP.serverAccessor;
    RTP.serverAccessor = mock(RTPServerAccessor.class);
  }

  @AfterEach
  void tearDown() {
    RTP.serverAccessor = originalAccessor;
  }

  @Test
  @DisplayName("Tag-group with set subtraction excludes subtracted canonical materials from airBlocks and unsafeBlocks")
  void testTagGroupSetSubtractionExpansion() throws IOException {
    YamlFileDatabase db = new YamlFileDatabase(pluginDir);
    File safetyFile = new File(pluginDir, "safety.yml");
    Files.writeString(safetyFile.toPath(),
        "version: \"1.0\"\n" +
            "airBlocks:\n" +
            "  - \"#minecraft:leaves - AZALEA_LEAVES - FLOWERING_AZALEA_LEAVES\"\n" +
            "unsafeBlocks:\n" +
            "  - \"#minecraft:slabs - OAK_SLAB\"\n" +
            "  - \"LAVA - LAVA\"\n");

    ConfigParser<BlocksKeys> safetyParser = new ConfigParser<>(
        BlocksKeys.class,
        "safety",
        "1.0",
        pluginDir,
        db
    ) {
      @Override
      public InputStream getResourceFromJar(String filename) {
        return new ByteArrayInputStream("version: \"1.0\"\n".getBytes());
      }
    };

    Map<String, Set<String>> snapshot = new HashMap<>();
    snapshot.put("minecraft:leaves", Set.of("minecraft:oak_leaves", "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves"));
    snapshot.put("minecraft:slabs", Set.of("minecraft:oak_slab", "minecraft:birch_slab", "minecraft:stone_slab"));
    when(RTP.serverAccessor.blockTagSnapshot()).thenReturn(snapshot);

    SafetyTokenExpander.expandAndApply(safetyParser);

    Object airBlocksObj = safetyParser.getConfigValue(BlocksKeys.airBlocks, null);
    assertTrue(airBlocksObj instanceof List);
    List<?> airBlocks = (List<?>) airBlocksObj;
    assertTrue(airBlocks.contains("OAK_LEAVES"));
    assertFalse(airBlocks.contains("AZALEA_LEAVES"));
    assertFalse(airBlocks.contains("FLOWERING_AZALEA_LEAVES"));

    Object unsafeObj = safetyParser.getConfigValue(BlocksKeys.unsafeBlocks, null);
    assertTrue(unsafeObj instanceof List);
    List<?> unsafeBlocks = (List<?>) unsafeObj;
    assertTrue(unsafeBlocks.contains("BIRCH_SLAB"));
    assertTrue(unsafeBlocks.contains("STONE_SLAB"));
    assertFalse(unsafeBlocks.contains("OAK_SLAB"));
    assertFalse(unsafeBlocks.contains("LAVA"));
  }
}
