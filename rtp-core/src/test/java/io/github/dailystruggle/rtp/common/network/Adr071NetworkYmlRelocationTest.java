package io.github.dailystruggle.rtp.common.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * ADR-071: {@code network.yml} is relocated from the plugin root into the
 * deliberate {@code advanced/} tier. Verifies {@link
 * NetworkModeBootstrap#ensureNetworkYml(File, Class)} resolves
 * {@code advanced/network.yml} and migrates a legacy root {@code network.yml}
 * (pre-ADR-071 install) into {@code advanced/} verbatim rather than stranding
 * an operator's tuned transport settings (rule 4).
 */
class Adr071NetworkYmlRelocationTest {

  private Path tempDir;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("rtp-network-relocate-");
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

  @Test
  @DisplayName("legacy root network.yml is migrated into advanced/ verbatim")
  void migratesLegacyRootNetworkYml() throws Exception {
    String legacyContent =
        "network:\n"
            + "  enabled: true\n"
            + "  serverId: backend-a\n";
    File legacy = new File(tempDir.toFile(), "network.yml");
    Files.writeString(legacy.toPath(), legacyContent);

    File result = NetworkModeBootstrap.ensureNetworkYml(tempDir.toFile(), getClass());

    File expected = new File(tempDir.toFile(), "advanced" + File.separator + "network.yml");
    assertEquals(expected.getCanonicalPath(), result.getCanonicalPath(),
        "ensureNetworkYml must resolve advanced/network.yml");
    assertTrue(expected.isFile(), "the migrated file must exist under advanced/");
    assertFalse(legacy.isFile(), "the legacy root network.yml must be moved, not left behind");
    assertEquals(legacyContent, Files.readString(expected.toPath()),
        "the operator's transport settings must be carried forward verbatim");
  }

  @Test
  @DisplayName("an existing advanced/network.yml is returned without re-migrating")
  void existingAdvancedFileIsIdempotent() throws Exception {
    File advanced = new File(tempDir.toFile(), "advanced" + File.separator + "network.yml");
    //noinspection ResultOfMethodCallIgnored
    advanced.getParentFile().mkdirs();
    Files.writeString(advanced.toPath(), "network:\n  enabled: false\n");

    // A stray legacy file present too: must be left untouched when advanced/ already exists.
    File legacy = new File(tempDir.toFile(), "network.yml");
    Files.writeString(legacy.toPath(), "network:\n  enabled: true\n");

    File result = NetworkModeBootstrap.ensureNetworkYml(tempDir.toFile(), getClass());

    assertEquals(advanced.getCanonicalPath(), result.getCanonicalPath());
    assertEquals("network:\n  enabled: false\n", Files.readString(advanced.toPath()),
        "an existing advanced/network.yml must not be overwritten");
    assertTrue(legacy.isFile(), "a stray legacy file is not migrated once advanced/ exists");
  }
}
