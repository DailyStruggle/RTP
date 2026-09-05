package io.github.dailystruggle.rtp.common.configuration;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.database.options.YamlFileDatabase;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import java.io.File;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ConfigParserDataSizeTest {

  @BeforeAll
  static void setup() {
    RTPServerAccessor accessor = Mockito.mock(RTPServerAccessor.class);
    Mockito.when(accessor.getPluginDirectory()).thenReturn(new File("target/test-data/config-parser-datasize"));
    RTP.serverAccessor = accessor;
  }

  @Test
  @DisplayName("ConfigParser: parseDataSizeBytes parses sentinels, numbers, and unit strings")
  void testParseDataSizeBytes() {
    // Sentinels
    assertEquals(-1L, ConfigParser.parseDataSizeBytes("-1"));
    assertEquals(-1L, ConfigParser.parseDataSizeBytes("infinite"));
    assertEquals(-1L, ConfigParser.parseDataSizeBytes("unlimited"));
    assertEquals(-1L, ConfigParser.parseDataSizeBytes(null));
    assertEquals(-1L, ConfigParser.parseDataSizeBytes(""));
    assertEquals(-1L, ConfigParser.parseDataSizeBytes("invalidString"));

    // Custom fallback
    assertEquals(1024L, ConfigParser.parseDataSizeBytes(null, 1024L));
    assertEquals(2048L, ConfigParser.parseDataSizeBytes("garbage", 2048L));

    // Numeric inputs
    assertEquals(1048576L, ConfigParser.parseDataSizeBytes(1048576));
    assertEquals(4096L, ConfigParser.parseDataSizeBytes("4096"));
    assertEquals(-1L, ConfigParser.parseDataSizeBytes(-100));

    // Units
    assertEquals(100L, ConfigParser.parseDataSizeBytes("100b"));
    assertEquals(100L, ConfigParser.parseDataSizeBytes("100 bytes"));
    assertEquals(64000L, ConfigParser.parseDataSizeBytes("64KB"));
    assertEquals(64L * 1024L, ConfigParser.parseDataSizeBytes("64KiB"));
    assertEquals(256L * 1000L * 1000L, ConfigParser.parseDataSizeBytes("256MB"));
    assertEquals(256L * 1024L * 1024L, ConfigParser.parseDataSizeBytes("256MiB"));
    assertEquals(1000L * 1000L * 1000L, ConfigParser.parseDataSizeBytes("1GB"));
    assertEquals(1024L * 1024L * 1024L, ConfigParser.parseDataSizeBytes("1GiB"));
  }

  @Test
  @DisplayName("ConfigParser: getDataSize reads data size values with fallback support")
  void testGetDataSize() {
    File tempDir = new File("target/test-data/config-parser-datasize");
    tempDir.mkdirs();
    YamlFileDatabase fileDatabase = new YamlFileDatabase(tempDir);

    ConfigParser<RegionKeys> parser = new ConfigParser<>(
        RegionKeys.class, "region.yml", "1.0",
        tempDir,
        null, fileDatabase, ConfigParserDataSizeTest.class.getClassLoader()
    );

    // Default numeric fallback when key absent or null
    assertEquals(1024L, parser.getDataSize(RegionKeys.cacheCap, 1024L));

    // String literal in config data parsed to bytes
    parser.set(RegionKeys.cacheCap, "256MB");
    assertEquals(256_000_000L, parser.getDataSize(RegionKeys.cacheCap, 0L));

    // String fallback format
    parser.set(RegionKeys.cacheCap, "1GiB");
    assertEquals(1024L * 1024L * 1024L, parser.getDataSize(RegionKeys.cacheCap, "64MB"));

    parser.set(RegionKeys.cacheCap, "invalidToken");
    assertEquals(64_000_000L, parser.getDataSize(RegionKeys.cacheCap, "64MB"));
  }

  @Test
  @DisplayName("MemoryTracker: setMemoryCeiling and isOverMemoryCeiling with DataSizeParser")
  void testMemoryTrackerCeiling() {
    MemoryTracker.setMemoryCeiling("128MB");
    assertEquals(128_000_000L, MemoryTracker.getMemoryCeiling());

    assertFalse(MemoryTracker.isOverMemoryCeiling(64_000_000L));
    assertTrue(MemoryTracker.isOverMemoryCeiling(130_000_000L));

    MemoryTracker.setMemoryCeiling("infinite");
    assertEquals(-1L, MemoryTracker.getMemoryCeiling());
    assertFalse(MemoryTracker.isOverMemoryCeiling(1_000_000_000_000L));
  }
}
