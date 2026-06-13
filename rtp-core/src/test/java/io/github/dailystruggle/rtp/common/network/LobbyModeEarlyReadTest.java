package io.github.dailystruggle.rtp.common.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link NetworkModeBootstrap#readLobbyModeEarly(File)}
 * resolves {@code routing.lobbyMode} from {@code network.yml} BEFORE the rest
 * of the bootstrap runs, and defensively returns {@code false} on every
 * failure path (missing file, network disabled, missing routing section,
 * malformed yaml) so non-lobby deployments are byte-identical to pre-J.
 */
class LobbyModeEarlyReadTest {

  private Path tempDir;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("rtp-lobby-early-read-");
  }

  @AfterEach
  void tearDown() throws Exception {
    if (tempDir != null) {
      try (var stream = Files.walk(tempDir)) {
        stream
            .sorted(java.util.Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
      }
    }
  }

  private File write(String yaml) throws Exception {
    File f = new File(tempDir.toFile(), "network.yml");
    Files.writeString(f.toPath(), yaml);
    return f;
  }

  @Test
  @DisplayName("null file -> false")
  void nullFile_returnsFalse() {
    assertFalse(NetworkModeBootstrap.readLobbyModeEarly(null));
  }

  @Test
  @DisplayName("missing file -> false")
  void missingFile_returnsFalse() {
    File f = new File(tempDir.toFile(), "does-not-exist.yml");
    assertFalse(NetworkModeBootstrap.readLobbyModeEarly(f));
  }

  @Test
  @DisplayName("network.enabled=false -> false (regardless of lobbyMode)")
  void networkDisabled_returnsFalse() throws Exception {
    File f = write(
        "network:\n"
            + "  enabled: false\n"
            + "routing:\n"
            + "  lobbyMode: true\n");
    assertFalse(NetworkModeBootstrap.readLobbyModeEarly(f));
  }

  @Test
  @DisplayName("network.enabled=true, no routing section -> false")
  void networkEnabledNoRouting_returnsFalse() throws Exception {
    File f = write(
        "network:\n"
            + "  enabled: true\n"
            + "  serverId: backend-a\n");
    assertFalse(NetworkModeBootstrap.readLobbyModeEarly(f));
  }

  @Test
  @DisplayName("network.enabled=true, routing.lobbyMode absent -> false")
  void routingPresentButLobbyModeAbsent_returnsFalse() throws Exception {
    File f = write(
        "network:\n"
            + "  enabled: true\n"
            + "  serverId: backend-a\n"
            + "routing:\n"
            + "  mode: auto\n");
    assertFalse(NetworkModeBootstrap.readLobbyModeEarly(f));
  }

  @Test
  @DisplayName("network.enabled=true, routing.lobbyMode=false -> false")
  void lobbyModeExplicitFalse_returnsFalse() throws Exception {
    File f = write(
        "network:\n"
            + "  enabled: true\n"
            + "  serverId: backend-a\n"
            + "routing:\n"
            + "  lobbyMode: false\n");
    assertFalse(NetworkModeBootstrap.readLobbyModeEarly(f));
  }

  @Test
  @DisplayName("network.enabled=true, routing.lobbyMode=true -> true")
  void lobbyModeTrue_returnsTrue() throws Exception {
    File f = write(
        "network:\n"
            + "  enabled: true\n"
            + "  serverId: backend-a\n"
            + "routing:\n"
            + "  lobbyMode: true\n");
    assertTrue(NetworkModeBootstrap.readLobbyModeEarly(f));
  }

  @Test
  @DisplayName("malformed yaml -> false (defensive)")
  void malformedYaml_returnsFalse() throws Exception {
    File f = write("this: is: not: valid: yaml: [\n  bare\n");
    assertFalse(NetworkModeBootstrap.readLobbyModeEarly(f));
  }
}
