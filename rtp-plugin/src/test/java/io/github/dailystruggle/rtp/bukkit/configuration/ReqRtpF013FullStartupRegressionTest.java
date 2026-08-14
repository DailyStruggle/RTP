package io.github.dailystruggle.rtp.bukkit.configuration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.effectsapi.common.EffectsGroupKeys;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.NetworkMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlaceholderMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.PlayerMessages;
import io.github.dailystruggle.rtp.api.configuration.enums.SystemMessages;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.LanguageBootstrap;
import io.github.dailystruggle.rtp.common.configuration.enums.BiomesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.DatabaseKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.MetricsKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * REQ-RTP-F-013 startup regression coverage. Where the other locale suites drive an individual
 * function ({@code reloadConfigs}, one parser, one migration decision), this suite exercises the
 * <em>whole configuration bring-up sequence</em> that plugin startup performs - the same steps
 * {@code RTPBukkitPlugin.onEnable} runs at the core level (wire accessor + scheduler, construct
 * {@link RTP}, {@link io.github.dailystruggle.rtp.common.configuration.Configs#reloadConfigs()},
 * {@link io.github.dailystruggle.rtp.common.configuration.Configs#reloadRegions()}) followed by a
 * few scheduler ticks to drain deferred startup tasks - and asserts the process comes up clean.
 *
 * <p>Rationale: the recurring locale/packaging defects in this area were "config setup issues" that
 * unit-level tests of a single function did not surface because they never ran the full parser
 * inventory together, extracted every bundled resource, or advanced the scheduler. A live Bukkit
 * {@code onEnable} boot (MockBukkit) is not usable in this module: the paper-api / MockBukkit
 * versions on the test classpath are mismatched ({@code SimpleCommandMap.<init>} signature drift),
 * so {@code MockBukkit.mock()} throws {@code NoSuchMethodError}. This harness therefore replays the
 * platform-independent startup core, which is where config setup lives.
 */
public class ReqRtpF013FullStartupRegressionTest {

  @TempDir Path tempDir;

  /** Every single-file config parser the startup path registers (locale-independent). */
  private static final Class<?>[] EXPECTED_CONFIG_PARSERS = {
    LoggingKeys.class,
    ConfigKeys.class,
    DatabaseKeys.class,
    PlaceholderMessages.class,
    PlayerMessages.class,
    NetworkMessages.class,
    CommandMessages.class,
    SystemMessages.class,
    EconomyKeys.class,
    PerformanceKeys.class,
    MetricsKeys.class,
    SafetyKeys.class,
    BlocksKeys.class,
    BiomesKeys.class,
  };

  /** Every multi-file parser the startup path registers. */
  private static final Class<?>[] EXPECTED_MULTI_PARSERS = {
    RegionKeys.class, WorldKeys.class, EffectsGroupKeys.class,
  };

  /** Config files the startup path is expected to extract onto disk. */
  private static final String[] EXPECTED_FILES = {
    "config.yml",
    "economy.yml",
    "safety.yml",
    "advanced/logging.yml",
    "advanced/performance.yml",
    "advanced/metrics.yml",
    "advanced/blocks.yml",
    "advanced/biomes.yml",
    "advanced/database.yml",
    "advanced/messages/placeholders.yml",
    "advanced/messages/player.yml",
    "advanced/messages/network.yml",
    "advanced/messages/commands.yml",
    "advanced/messages/system.yml",
  };

  /**
   * Replays plugin startup at the core level: wire the mock accessor + scheduler, construct RTP,
   * reload all config parsers, reload regions, then advance the scheduler a few ticks to drain the
   * deferred startup tasks. Returns without throwing on a healthy bring-up.
   */
  private static void bootStartup(File dir) {
    MockRTPServerAccessor accessor =
        RTPTestSetup.install(dir); // new RTP + Configs + full reloadConfigs()
    RTP.configs.reloadRegions();
    accessor.getMockScheduler().tick(5); // a few game ticks into startup
  }

  @Test
  @DisplayName("REQ-RTP-F-013: full startup completes without throwing and registers every parser")
  void fullStartupRegistersEveryParser() {
    File dir = tempDir.toFile();

    assertDoesNotThrow(() -> bootStartup(dir), "full config startup threw");

    for (Class<?> parser : EXPECTED_CONFIG_PARSERS) {
      assertTrue(
          RTP.configs.configParserMap.containsKey(parser),
          "startup did not register config parser " + parser.getSimpleName());
    }
    for (Class<?> parser : EXPECTED_MULTI_PARSERS) {
      assertTrue(
          RTP.configs.multiConfigParserMap.containsKey(parser),
          "startup did not register multi-config parser " + parser.getSimpleName());
    }
  }

  @Test
  @DisplayName("REQ-RTP-F-013: full startup extracts every config file, all non-empty")
  void fullStartupExtractsEveryFile() throws Exception {
    File dir = tempDir.toFile();
    bootStartup(dir);

    for (String rel : EXPECTED_FILES) {
      File f = new File(dir, rel);
      assertTrue(f.exists(), "startup did not extract " + rel);
      assertTrue(
          Files.size(f.toPath()) > 0, "startup extracted an empty " + rel);
    }
  }

  @Test
  @DisplayName("REQ-RTP-F-013: an English startup creates no data-dir lang/ mirror")
  void englishStartupCreatesNoLangMirror() {
    File dir = tempDir.toFile();
    bootStartup(dir);
    assertFalse(new File(dir, "lang").exists(), "English startup created a stray lang/ mirror");
  }

  @ParameterizedTest
  @ValueSource(strings = {"fr", "es", "zh", "de"})
  @DisplayName("REQ-RTP-F-013: foreign-locale startup is clean, colocated, and idempotent")
  void foreignLocaleStartupIsCleanAndIdempotent(String locale) throws Exception {
    File dir = tempDir.toFile();

    // First startup in English (extracts the baseline), then operator selects a foreign locale.
    MockRTPServerAccessor accessor = RTPTestSetup.install(dir);
    File languageFile = new File(dir, LanguageBootstrap.FILE_NAME);
    Files.writeString(languageFile.toPath(), "language: " + locale + "\n");

    assertDoesNotThrow(
        () -> {
          RTP.configs.reloadConfigs();
          RTP.configs.reloadRegions();
          accessor.getMockScheduler().tick(5);
        },
        "startup with locale '" + locale + "' threw");

    // No stray data-directory lang/ mirror; the active locale's rename maps are colocated.
    assertFalse(
        new File(dir, "lang").exists(),
        "locale '" + locale + "' startup created a stray data-dir lang/ mirror");
    assertTrue(
        new File(dir, ".config.lang.yml").exists(),
        "locale '" + locale + "' did not colocate the config.yml rename map");
    assertTrue(
        new File(dir, "advanced/messages/.player.lang.yml").exists(),
        "locale '" + locale + "' did not colocate the player.yml rename map");

    // Idempotency: a second reload (a /rtp reload with the locale unchanged) must rotate NO
    // additional .old backups on disk - each file is already in the active locale, so re-backing
    // it up would be redundant churn (the bug class of the earlier double-.old reports). This now
    // covers BOTH the operator-facing value config files (e.g. config.yml.old2) AND the hidden
    // .lang.yml rename-map dotfiles: a per-file clone (e.g. the per-world parser) writes its
    // colocated .world.lang.yml map inside the MultiConfigParser's scanned directory, and an
    // earlier defect re-scanned that dotfile as a value config and rotated a fresh
    // .world.lang.yml.old<N> on every reload. MultiConfigParser now skips dotfile/.lang.yml maps
    // during its directory scan, so no new .old of either kind should appear here.
    Set<String> backupsBefore = configFileBackups(dir);
    RTP.configs.reloadConfigs();
    Set<String> backupsAfter = configFileBackups(dir);
    Set<String> added = new TreeSet<>(backupsAfter);
    added.removeAll(backupsBefore);
    assertTrue(
        added.isEmpty(),
        "a redundant .old backup was rotated on an idempotent reload for locale '"
            + locale
            + "': "
            + added);
  }

  /**
   * Names of all backups ({@code *.yml.old<N>}) anywhere under the data directory, including the
   * hidden {@code .<name>.lang.yml.old<N>} rename-map dotfiles - a reload must never rotate a fresh
   * copy of either kind.
   */
  private static Set<String> configFileBackups(File dir) throws Exception {
    try (Stream<Path> paths = Files.walk(dir.toPath())) {
      return paths
          .filter(Files::isRegularFile)
          .map(p -> p.getFileName().toString())
          .filter(n -> n.contains(".yml.old"))
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }
}
