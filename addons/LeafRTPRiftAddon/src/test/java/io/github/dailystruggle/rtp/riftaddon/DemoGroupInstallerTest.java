package io.github.dailystruggle.rtp.riftaddon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A demo group left in the legacy {@code effects/} folder is migrated by core into
 * {@code definitions/effects/} on the next start, so a group deleted while the server is down
 * came back once. The installer must write only to the live directory and clear its own legacy
 * copy.
 */
@DisplayName("Rift demo group: deleted effect group file is not written back on the next start")
class DemoGroupInstallerTest {

  private static final byte[] CONTENT = "when: preload\neffects:\n  - RIFT.6.2\n"
      .getBytes(StandardCharsets.UTF_8);

  @TempDir File pluginDir;

  private File groupsDir() {
    return new File(pluginDir, "definitions" + File.separator + "effects");
  }

  private File legacyDir() {
    return new File(pluginDir, "effects");
  }

  @Test
  @DisplayName("installs into definitions/effects and leaves nothing in legacy effects/")
  void installsIntoLiveDirectoryOnly() throws Exception {
    DemoGroupInstaller.Outcome outcome =
        DemoGroupInstaller.install(groupsDir(), legacyDir(), "rift.yml", CONTENT);

    assertTrue(outcome.installed());
    assertArrayEquals(CONTENT, Files.readAllBytes(new File(groupsDir(), "rift.yml").toPath()));
    assertFalse(legacyDir().exists(), "a legacy copy would be migrated back after a delete");
  }

  @Test
  @DisplayName("removes an unedited legacy copy written by an older version")
  void removesStaleLegacyCopy() throws Exception {
    assertTrue(legacyDir().mkdirs());
    Files.write(new File(legacyDir(), "rift.yml").toPath(), CONTENT);
    assertTrue(groupsDir().mkdirs());
    Files.write(new File(groupsDir(), "rift.yml").toPath(), CONTENT);

    DemoGroupInstaller.Outcome outcome =
        DemoGroupInstaller.install(groupsDir(), legacyDir(), "rift.yml", CONTENT);

    assertTrue(outcome.removedLegacyCopy());
    assertFalse(outcome.installed());
    assertFalse(legacyDir().exists(), "empty legacy folder is removed with the stale copy");
  }

  @Test
  @DisplayName("treats a legacy copy differing only in CRLF line endings as unedited")
  void removesCrlfLegacyCopy() throws Exception {
    assertTrue(legacyDir().mkdirs());
    String crlf = new String(CONTENT, StandardCharsets.UTF_8).replace("\n", "\r\n");
    Files.write(new File(legacyDir(), "rift.yml").toPath(), crlf.getBytes(StandardCharsets.UTF_8));

    DemoGroupInstaller.Outcome outcome =
        DemoGroupInstaller.install(groupsDir(), legacyDir(), "rift.yml", CONTENT);

    assertTrue(outcome.removedLegacyCopy());
    assertTrue(outcome.installed());
  }

  @Test
  @DisplayName("keeps an edited legacy copy for core's migration")
  void keepsEditedLegacyCopy() throws Exception {
    assertTrue(legacyDir().mkdirs());
    File edited = new File(legacyDir(), "rift.yml");
    Files.write(edited.toPath(), "effects: []\n".getBytes(StandardCharsets.UTF_8));

    DemoGroupInstaller.Outcome outcome =
        DemoGroupInstaller.install(groupsDir(), legacyDir(), "rift.yml", CONTENT);

    assertFalse(outcome.removedLegacyCopy());
    assertTrue(edited.exists());
  }

  @Test
  @DisplayName("keeps the legacy folder when other files remain in it")
  void keepsNonEmptyLegacyFolder() throws Exception {
    assertTrue(legacyDir().mkdirs());
    Files.write(new File(legacyDir(), "rift.yml").toPath(), CONTENT);
    File other = new File(legacyDir(), "mine.yml");
    Files.write(other.toPath(), CONTENT);

    DemoGroupInstaller.install(groupsDir(), legacyDir(), "rift.yml", CONTENT);

    assertFalse(new File(legacyDir(), "rift.yml").exists());
    assertTrue(other.exists());
  }

  @Test
  @DisplayName("never overwrites an operator-edited group file")
  void doesNotOverwriteExistingGroup() throws Exception {
    assertTrue(groupsDir().mkdirs());
    File target = new File(groupsDir(), "rift.yml");
    byte[] edited = "effects: []\n".getBytes(StandardCharsets.UTF_8);
    Files.write(target.toPath(), edited);

    DemoGroupInstaller.Outcome outcome =
        DemoGroupInstaller.install(groupsDir(), legacyDir(), "rift.yml", CONTENT);

    assertFalse(outcome.installed());
    assertArrayEquals(edited, Files.readAllBytes(target.toPath()));
  }
}
