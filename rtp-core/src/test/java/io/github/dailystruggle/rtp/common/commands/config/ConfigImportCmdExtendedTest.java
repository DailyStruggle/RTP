package io.github.dailystruggle.rtp.common.commands.config;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigImportCmdExtendedTest {

  @AfterEach
  void tearDown() {
    RTPTestSetup.cleanUp();
  }

  private Path setupPluginDir(Path tempDir) throws IOException {
    Path pluginsDir = tempDir.resolve("plugins");
    Path rtpDir = pluginsDir.resolve("RTP");
    Files.createDirectories(rtpDir);
    RTPTestSetup.install(rtpDir.toFile());
    return rtpDir;
  }

  @Test
  void testParameterValuesAndSuggestions(@TempDir Path tempDir) throws IOException {
    setupPluginDir(tempDir);
    ConfigImportCmd cmd = new ConfigImportCmd(null);
    assertNotNull(cmd.getParameterLookup().get(ConfigImportCmd.PARAM_SOURCE));
    assertNotNull(cmd.getParameterLookup().get(ConfigImportCmd.PARAM_PATH));
    assertNotNull(cmd.getParameterLookup().get(ConfigImportCmd.PARAM_OVERWRITE));
    assertNotNull(cmd.getParameterLookup().get(ConfigImportCmd.PARAM_PERMISSIONS));

    var values = cmd.getParameterLookup().get(ConfigImportCmd.PARAM_SOURCE).values();
    assertNotNull(values);
    assertFalse(values.isEmpty());
  }

  @Test
  void testImportNullDirectory() {
    ConfigImportCmd cmd = new ConfigImportCmd(null);
    RTP.configs = null;
    RTP.serverAccessor = null;
    assertFalse(cmd.onCommand(UUID.randomUUID(), Collections.emptyMap(), null));
  }

  @Test
  void testImportNoDetectedSources(@TempDir Path tempDir) throws IOException {
    setupPluginDir(tempDir);
    ConfigImportCmd cmd = new ConfigImportCmd(null);
    Map<String, List<String>> params = new HashMap<>();
    assertFalse(cmd.onCommand(UUID.randomUUID(), params, null));
  }

  @Test
  void testImportUnknownSource(@TempDir Path tempDir) throws IOException {
    setupPluginDir(tempDir);
    ConfigImportCmd cmd = new ConfigImportCmd(null);
    Map<String, List<String>> params = Map.of(
        ConfigImportCmd.PARAM_SOURCE, List.of("unknown_plugin_source")
    );
    assertFalse(cmd.onCommand(UUID.randomUUID(), params, null));
  }

  @Test
  void testImportSpecifiedCustomPathNotDirectory(@TempDir Path tempDir) throws IOException {
    setupPluginDir(tempDir);
    ConfigImportCmd cmd = new ConfigImportCmd(null);
    Map<String, List<String>> params = Map.of(
        ConfigImportCmd.PARAM_PATH, List.of(tempDir.resolve("not_a_dir.txt").toString())
    );
    assertFalse(cmd.onCommand(UUID.randomUUID(), params, null));
  }

  @Test
  void testImportSuccessfulExecutionWithAutoDetectAndPermissions(@TempDir Path tempDir) throws IOException {
    Path rtpDir = setupPluginDir(tempDir);
    Path pluginsDir = rtpDir.getParent();

    // Create a mock BetterRTP folder in plugins
    Path betterRtpDir = pluginsDir.resolve("BetterRTP");
    Files.createDirectories(betterRtpDir);
    Files.writeString(betterRtpDir.resolve("config.yml"), """
        Default:
          UseWorldBorder: true
          Biomes: []
          MaxRadius: 1000
          MinRadius: 100
          CenterX: 0
          CenterZ: 0
          Price: 0
          Cooldown: 30
        """);

    ConfigImportCmd cmd = new ConfigImportCmd(null);
    UUID callerId = UUID.randomUUID();

    // 1. Dry run with auto-detect
    Map<String, List<String>> params = new HashMap<>();
    params.put(ConfigImportCmd.PARAM_OVERWRITE, List.of("false"));
    params.put(ConfigImportCmd.PARAM_PERMISSIONS, List.of("true"));
    boolean res1 = cmd.onCommand(callerId, params, null);
    assertTrue(res1);

    // 2. Overwrite run with explicit source
    params.clear();
    params.put(ConfigImportCmd.PARAM_SOURCE, List.of("betterrtp"));
    params.put(ConfigImportCmd.PARAM_OVERWRITE, List.of("true"));
    params.put(ConfigImportCmd.PARAM_PERMISSIONS, List.of("true"));
    boolean res2 = cmd.onCommand(callerId, params, null);
    assertTrue(res2);

    // 3. Custom path pointing directly to BetterRTP folder
    params.clear();
    params.put(ConfigImportCmd.PARAM_PATH, List.of(betterRtpDir.toString()));
    params.put(ConfigImportCmd.PARAM_OVERWRITE, List.of("true"));
    params.put(ConfigImportCmd.PARAM_PERMISSIONS, List.of("false"));
    boolean res3 = cmd.onCommand(callerId, params, null);
    assertTrue(res3);
  }

  @Test
  void testImportMultipleDetectedSourcesPrompt(@TempDir Path tempDir) throws IOException {
    Path rtpDir = setupPluginDir(tempDir);
    Path pluginsDir = rtpDir.getParent();

    // Create two mock folders
    Path betterRtpDir = pluginsDir.resolve("BetterRTP");
    Files.createDirectories(betterRtpDir);
    Files.writeString(betterRtpDir.resolve("config.yml"), "Default:\n  MaxRadius: 500\n");

    Path wildernessDir = pluginsDir.resolve("Wilderness-TP");
    Files.createDirectories(wildernessDir);
    Files.writeString(wildernessDir.resolve("config.yml"), "world:\n  max-x: 500\n");

    ConfigImportCmd cmd = new ConfigImportCmd(null);
    UUID callerId = UUID.randomUUID();

    // With no source specified, should detect multiple sources and return false with prompt
    assertFalse(cmd.onCommand(callerId, Map.of(), null));
  }
}
