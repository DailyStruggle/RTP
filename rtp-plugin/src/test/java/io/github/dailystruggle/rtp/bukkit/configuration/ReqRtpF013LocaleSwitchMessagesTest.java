package io.github.dailystruggle.rtp.bukkit.configuration;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * REQ-RTP-F-013: switching the active locale shall change the text of the on-disk message
 * files. This guards a regression where a locale that translates only message VALUES while
 * keeping identity KEY names (every {@code advanced/messages/*.yml} file) was skipped by the
 * migration's key-rename short-circuit ({@code anyRename}), so the on-disk file kept its English
 * text forever after a language change. The re-switch assertion additionally guards against the
 * inverse hazard (an already-localized file being re-extracted and backed up on every reload).
 */
public class ReqRtpF013LocaleSwitchMessagesTest {

  @TempDir Path tempDir;

  @ParameterizedTest(name = "locale ''{0}'' switches player.yml text")
  @ValueSource(strings = {"fr", "es", "zh", "de"})
  @DisplayName("REQ-RTP-F-013: locale switch rewrites message file text (values-only locales too)")
  void localeSwitchRewritesMessageFile(String locale) throws Exception {
    File dir = tempDir.resolve(locale).toFile();
    assertTrue(dir.mkdirs() || dir.isDirectory());

    RTPServerAccessor serverAccessor = mock(RTPServerAccessor.class);
    RTPScheduler scheduler = mock(RTPScheduler.class);
    when(serverAccessor.getPluginDirectory()).thenReturn(dir);
    when(serverAccessor.createTaskPipe()).thenReturn(mock(RTPTaskPipe.class));
    RTP.serverAccessor = serverAccessor;
    RTP.scheduler = scheduler;
    RTPAPI.serverAccessor = null;
    RTP rtp = new RTP() {};
    java.lang.reflect.Field instanceField = RTP.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, rtp);

    // 1. Fresh English extraction.
    YamlFileDatabase enDb = new YamlFileDatabase(dir);
    new ConfigParser<>(PlayerMessages.class, "advanced/messages/player.yml", "1.0", dir, enDb, "en");
    File file = new File(dir, "advanced/messages/player.yml");
    assertTrue(file.exists(), "English baseline was not extracted");
    String english = Files.readString(file.toPath());

    // 2. Switch to the target locale -> the on-disk text must change.
    YamlFileDatabase localeDb = new YamlFileDatabase(dir);
    new ConfigParser<>(PlayerMessages.class, "advanced/messages/player.yml", "1.0", dir, localeDb, locale);
    String localized = Files.readString(file.toPath());
    assertNotEquals(
        english,
        localized,
        "switching to '" + locale + "' did not change player.yml text (still English)");

    // 3. Re-switch to the SAME locale -> must not rotate another backup (no re-extract loop).
    YamlFileDatabase reDb = new YamlFileDatabase(dir);
    new ConfigParser<>(PlayerMessages.class, "advanced/messages/player.yml", "1.0", dir, reDb, locale);
    File secondBackup = new File(dir, "advanced/messages/player.yml.old2");
    assertFalse(
        secondBackup.exists(),
        "re-switching to the already-active locale '" + locale + "' rotated a redundant backup");
  }
}
