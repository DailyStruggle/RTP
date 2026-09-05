package io.github.dailystruggle.rtp.common.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import java.io.File;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConfigParserTimeTest {

  @BeforeAll
  static void setup() {
    RTPServerAccessor accessor = Mockito.mock(RTPServerAccessor.class);
    Mockito.when(accessor.getPluginDirectory()).thenReturn(new File("target/test-data/config-parser-time"));
    RTP.serverAccessor = accessor;
  }

  @Test
  @DisplayName("ConfigParser: parseDurationSeconds parses sentinels, numbers, single units, and composite durations")
  void testParseDurationSeconds() {
    // Sentinels
    assertEquals(-1L, ConfigParser.parseDurationSeconds("-1"));
    assertEquals(-1L, ConfigParser.parseDurationSeconds("infinite"));
    assertEquals(-1L, ConfigParser.parseDurationSeconds("permanent"));
    assertEquals(-1L, ConfigParser.parseDurationSeconds(null));
    assertEquals(-1L, ConfigParser.parseDurationSeconds(""));
    assertEquals(-1L, ConfigParser.parseDurationSeconds("invalidString"));

    // Custom fallback
    assertEquals(60L, ConfigParser.parseDurationSeconds(null, 60L));
    assertEquals(120L, ConfigParser.parseDurationSeconds("garbage", 120L));

    // Numeric inputs
    assertEquals(3600L, ConfigParser.parseDurationSeconds(3600));
    assertEquals(100L, ConfigParser.parseDurationSeconds("100"));
    assertEquals(-1L, ConfigParser.parseDurationSeconds(-100));

    // Single units
    assertEquals(45L, ConfigParser.parseDurationSeconds("45s"));
    assertEquals(30L * 60L, ConfigParser.parseDurationSeconds("30m"));
    assertEquals(2L * 3600L, ConfigParser.parseDurationSeconds("2h"));
    assertEquals(14L * 86400L, ConfigParser.parseDurationSeconds("14d"));
    assertEquals(3L * 7L * 86400L, ConfigParser.parseDurationSeconds("3w"));
    assertEquals(1L, ConfigParser.parseDurationSeconds("20t"));
    assertEquals(2L, ConfigParser.parseDurationSeconds("2000ms"));

    // Composite units
    assertEquals(86400L + 12L * 3600L, ConfigParser.parseDurationSeconds("1d12h"));
    assertEquals(2L * 3600L + 30L * 60L, ConfigParser.parseDurationSeconds("2h30m"));
    assertEquals(86400L + 2L * 3600L + 15L * 60L + 20L, ConfigParser.parseDurationSeconds("1d2h15m20s"));

    // Ticks and millis helpers
    assertEquals(20L, ConfigParser.parseDurationTicks("1s", 0L));
    assertEquals(100L, ConfigParser.parseDurationTicks("5s", 0L));
    assertEquals(1000L, ConfigParser.parseDurationMillis("1s", 0L));
    assertEquals(50L, ConfigParser.parseDurationMillis("1t", 0L));
  }

  @Test
  @DisplayName("ConfigParser: getTime reads duration values with fallback support")
  void testGetTime() {
    File tempDir = new File("target/test-data/config-parser-time");
    tempDir.mkdirs();
    YamlFileDatabase fileDatabase = new YamlFileDatabase(tempDir);

    ConfigParser<ConfigKeys> parser = new ConfigParser<>(
        ConfigKeys.class, "config.yml", "1.0",
        tempDir,
        null, fileDatabase, ConfigParserTimeTest.class.getClassLoader()
    );

    // Default numeric fallback when key absent or null
    assertEquals(30L, parser.getTime(ConfigKeys.lockAfterResetSeconds, 30L));

    // String literal in config data parsed to seconds
    parser.set(ConfigKeys.lockAfterResetSeconds, "2h30m");
    assertEquals(2L * 3600L + 30L * 60L, parser.getTime(ConfigKeys.lockAfterResetSeconds, 0L));

    // String fallback format
    parser.set(ConfigKeys.lockAfterResetSeconds, "1d");
    assertEquals(86400L, parser.getTime(ConfigKeys.lockAfterResetSeconds, "1h"));

    parser.set(ConfigKeys.lockAfterResetSeconds, "invalidToken");
    assertEquals(3600L, parser.getTime(ConfigKeys.lockAfterResetSeconds, "1h"));
  }
}
