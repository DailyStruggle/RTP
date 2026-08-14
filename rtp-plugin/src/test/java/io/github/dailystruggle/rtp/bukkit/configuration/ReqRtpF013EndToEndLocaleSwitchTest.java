package io.github.dailystruggle.rtp.bukkit.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.LanguageBootstrap;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REQ-RTP-F-013: faithful "restart with a changed language" reproduction. Drives the real
 * {@link io.github.dailystruggle.rtp.common.configuration.Configs#reloadConfigs()} full
 * multi-parser path (via {@link RTPTestSetup#install}) exactly as plugin startup / {@code /rtp
 * reload} does, then flips {@code language.yml} to a non-English locale and reloads again,
 * asserting the on-disk message file text actually switches.
 */
public class ReqRtpF013EndToEndLocaleSwitchTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("REQ-RTP-F-013: restart with fr language.yml switches on-disk player.yml text")
  void restartWithFrenchSwitchesMessageFile() throws Exception {
    File dir = tempDir.toFile();

    // 1. First "startup": default locale (en). This extracts all config files in English.
    RTPTestSetup.install(dir);

    File playerFile = new File(dir, "advanced/messages/player.yml");
    assertTrue(playerFile.exists(), "player.yml was not extracted on first startup");
    String english = Files.readString(playerFile.toPath());

    // 2. Operator edits language.yml -> fr, then restarts (second reloadConfigs).
    File languageFile = new File(dir, LanguageBootstrap.FILE_NAME);
    Files.writeString(languageFile.toPath(), "language: fr\n");

    RTP.configs.reloadConfigs();

    String afterSwitch = Files.readString(playerFile.toPath());
    assertNotEquals(
        english,
        afterSwitch,
        "switching language.yml to 'fr' and reloading did not change player.yml text");
  }

  /**
   * The real-world failure: the on-disk message file was extracted by an older build whose English
   * wording has since drifted, so it matches neither the current English baseline nor the target
   * locale. A migration decision anchored on "still equals the current English baseline" would skip
   * it forever; anchoring on "not yet in the target locale" migrates it. Simulated here by writing
   * a player.yml whose translated values are custom (older-build) English before switching to fr.
   */
  @Test
  @DisplayName("REQ-RTP-F-013: drifted older-build English still switches on locale change")
  void driftedEnglishStillSwitches() throws Exception {
    File dir = tempDir.toFile();

    RTPTestSetup.install(dir);
    File playerFile = new File(dir, "advanced/messages/player.yml");
    assertTrue(playerFile.exists(), "player.yml was not extracted on first startup");

    // Overwrite with an older-build-style English file: identity keys, custom (drifted) values
    // that equal neither the current English baseline nor the French translation.
    String drifted =
        "alreadyTeleporting: \"you are already teleporting (old build)\"\n"
            + "teleportMessage: \"teleported after [attempts] tries (old build)\"\n"
            + "cooldownMessage: \"wait [remainingCooldown] before teleporting again (old build)\"\n"
            + "unsafe: \"no safe spot in [attempts] tries (old build)\"\n"
            + "version: 1.0\n";
    Files.writeString(playerFile.toPath(), drifted);

    File languageFile = new File(dir, LanguageBootstrap.FILE_NAME);
    Files.writeString(languageFile.toPath(), "language: fr\n");

    RTP.configs.reloadConfigs();

    String afterSwitch = Files.readString(playerFile.toPath());
    assertNotEquals(
        drifted,
        afterSwitch,
        "drifted older-build English player.yml was not migrated on switch to 'fr'");
    assertTrue(
        afterSwitch.contains("\u00e9l\u00e9port") || afterSwitch.contains("t\u00e9l\u00e9port"),
        "player.yml did not contain the French translation after switching to 'fr'");
  }

  /**
   * The fr-&gt;en return path: after switching to French the on-disk files must switch BACK to
   * English when the operator selects {@code en} again. Before the format-agnostic migration gate
   * this was impossible - the value/key path early-returned on {@code isEnglish()} and left the
   * files in French.
   */
  @Test
  @DisplayName("REQ-RTP-F-013: switching fr -> en re-localizes files back to English")
  void switchBackToEnglishReLocalizes() throws Exception {
    File dir = tempDir.toFile();

    RTPTestSetup.install(dir);
    File playerFile = new File(dir, "advanced/messages/player.yml");
    String english = Files.readString(playerFile.toPath());

    File languageFile = new File(dir, LanguageBootstrap.FILE_NAME);
    Files.writeString(languageFile.toPath(), "language: fr\n");
    RTP.configs.reloadConfigs();
    String french = Files.readString(playerFile.toPath());
    assertNotEquals(english, french, "precondition: fr switch did not localize player.yml");

    // Switch back to English.
    Files.writeString(languageFile.toPath(), "language: en\n");
    RTP.configs.reloadConfigs();
    String back = Files.readString(playerFile.toPath());
    assertEquals(
        english,
        back,
        "switching language.yml back to 'en' did not restore the English player.yml text");
  }

  /**
   * Idempotency guard for the double-{@code .old} regression: re-selecting the already-active
   * locale (a plain {@code /rtp reload} with no language change) must not rotate a second backup.
   */
  @Test
  @DisplayName("REQ-RTP-F-013: re-selecting the active locale does not rotate a second .old backup")
  void reSelectingActiveLocaleDoesNotDoubleBackup() throws Exception {
    File dir = tempDir.toFile();

    RTPTestSetup.install(dir);
    File languageFile = new File(dir, LanguageBootstrap.FILE_NAME);
    Files.writeString(languageFile.toPath(), "language: fr\n");
    RTP.configs.reloadConfigs();

    // A second and third reload with the SAME locale (no change) must not create *.old2 for any
    // migrated file - the migration is idempotent once the file is already in the active locale.
    RTP.configs.reloadConfigs();
    RTP.configs.reloadConfigs();

    for (String rel :
        new String[] {
          "advanced/messages/player.yml", "advanced/biomes.yml", "config.yml", "safety.yml"
        }) {
      File old2 = new File(dir, rel + ".old2");
      assertFalse(
          old2.exists(),
          "re-selecting the active locale rotated a redundant backup: " + old2);
    }
  }

  /**
   * A comment-only-translated file (advanced/biomes.yml has no key renames and identical values
   * across locales - only its documentation comments are translated) must still switch on a
   * locale change. The value/key path could not see this file; the text-based gate does.
   */
  @Test
  @DisplayName("REQ-RTP-F-013: comment-only file (biomes.yml) switches on locale change")
  void commentOnlyFileSwitches() throws Exception {
    File dir = tempDir.toFile();

    RTPTestSetup.install(dir);
    File biomes = new File(dir, "advanced/biomes.yml");
    assertTrue(biomes.exists(), "biomes.yml was not extracted on first startup");
    String english = Files.readString(biomes.toPath());

    File languageFile = new File(dir, LanguageBootstrap.FILE_NAME);
    Files.writeString(languageFile.toPath(), "language: fr\n");
    RTP.configs.reloadConfigs();

    String french = Files.readString(biomes.toPath());
    assertNotEquals(
        english,
        french,
        "comment-only biomes.yml did not switch to French on locale change");
  }

  /**
   * ADR-076 colocation: the active locale's {@code .lang.yml} rename maps must sit beside their
   * value files in the data directory, NOT under a {@code plugins/RTP/lang/<locale>/} mirror. After
   * switching to a non-English locale there must be no {@code lang/} folder in the data directory,
   * and the colocated dotfile map beside the value file must exist.
   */
  @Test
  @DisplayName("REQ-RTP-F-013: no data-dir lang/ mirror is created for a non-English locale")
  void nonEnglishLocaleDoesNotCreateLangMirror() throws Exception {
    File dir = tempDir.toFile();

    RTPTestSetup.install(dir);
    File languageFile = new File(dir, LanguageBootstrap.FILE_NAME);
    Files.writeString(languageFile.toPath(), "language: fr\n");
    RTP.configs.reloadConfigs();

    File langMirror = new File(dir, "lang");
    assertFalse(
        langMirror.exists(),
        "a stray data-directory lang/ mirror was created for locale 'fr': " + langMirror);

    // The rename maps must be colocated beside their value files instead.
    assertTrue(
        new File(dir, ".config.lang.yml").exists(),
        "the config.yml rename map was not colocated at the data-directory root");
    assertTrue(
        new File(dir, "advanced/messages/.player.lang.yml").exists(),
        "the player.yml rename map was not colocated beside advanced/messages/player.yml");
  }

  /**
   * Migration cleanup: an install left over from an earlier build that mirrored the maps under
   * {@code plugins/RTP/lang/<locale>/...} must have that stray tree removed on the next reload.
   */
  @Test
  @DisplayName("REQ-RTP-F-013: a pre-existing legacy lang/<locale> mirror is purged on reload")
  void legacyLangMirrorIsPurged() throws Exception {
    File dir = tempDir.toFile();

    RTPTestSetup.install(dir);
    File languageFile = new File(dir, LanguageBootstrap.FILE_NAME);
    Files.writeString(languageFile.toPath(), "language: fr\n");
    RTP.configs.reloadConfigs();

    // Simulate the old on-disk layout: a stray lang/fr mirror with a rename-map dotfile.
    File stray = new File(dir, "lang/fr/advanced/messages/.player.lang.yml");
    stray.getParentFile().mkdirs();
    Files.writeString(stray.toPath(), "teleportMessage: teleportMessage\n");
    assertTrue(stray.exists(), "precondition: could not seed the legacy mirror");

    RTP.configs.reloadConfigs();

    assertFalse(new File(dir, "lang").exists(), "legacy data-dir lang/ mirror was not purged");
  }
}
