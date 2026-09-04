package io.github.dailystruggle.rtp.common.selection.region.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.selection.region.RegionConfigLoader;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import java.util.Collections;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class CacheMemoryCostTest {
  private static MockedStatic<RTP> rtpMockedStatic;

  @BeforeAll
  static void setup() {
    rtpMockedStatic = Mockito.mockStatic(RTP.class);
    Configs mockConfigs = Mockito.mock(Configs.class);
    @SuppressWarnings("unchecked")
    ConfigParser<LoggingKeys> mockLoggingParser = Mockito.mock(ConfigParser.class);
    RTP.configs = mockConfigs;
    Mockito.when(mockConfigs.getParser(LoggingKeys.class)).thenReturn(mockLoggingParser);
    Mockito.doReturn(false).when(mockLoggingParser).getConfigValue(Mockito.eq(LoggingKeys.detailed_region_init), Mockito.any());

    RTPServerAccessor accessor = Mockito.mock(RTPServerAccessor.class);
    Mockito.when(accessor.getRTPWorlds()).thenReturn(Collections.<RTPWorld<?>>emptyList());
    Mockito.when(accessor.getRTPWorld(Mockito.anyString())).thenReturn(null);
    RTP.serverAccessor = accessor;
  }

  @AfterAll
  static void tearDown() {
    if (rtpMockedStatic != null) {
      rtpMockedStatic.close();
    }
  }

  @Test
  @DisplayName("Numeric counts resolve directly to basic count")
  void testBasicCountFallback() {
    assertEquals(10L, CacheMemoryCost.resolveCapacity(10L, 5L, CacheMemoryCost.COLD_CACHE_BYTES_PER_ENTRY));
    assertEquals(64L, CacheMemoryCost.resolveCapacity("64", 5L, CacheMemoryCost.COLD_CACHE_BYTES_PER_ENTRY));
    assertEquals(0L, CacheMemoryCost.resolveCapacity(0, 5L, CacheMemoryCost.BACKLOG_CACHE_BYTES_PER_ENTRY));
  }

  @Test
  @DisplayName("Hot queue memory limits derive capacity from 1 MiB chunk cost")
  void testHotQueueMemoryDerivation() {
    // 16 MB with 1 MiB chunks -> 16 chunks (16 * 1,000,000 / 1,048,576 = 15 chunks)
    // 16 MiB with 1 MiB chunks -> 16 chunks
    assertEquals(16, CacheMemoryCost.resolveCapacityInt("16MiB", 3, CacheMemoryCost.HOT_CACHE_BYTES_PER_ENTRY));
    assertEquals(64, CacheMemoryCost.resolveCapacityInt("64MiB", 3, CacheMemoryCost.HOT_CACHE_BYTES_PER_ENTRY));
    assertEquals(1024, CacheMemoryCost.resolveCapacityInt("1GiB", 3, CacheMemoryCost.HOT_CACHE_BYTES_PER_ENTRY));
    assertEquals(0, CacheMemoryCost.resolveCapacityInt("512KB", 3, CacheMemoryCost.HOT_CACHE_BYTES_PER_ENTRY));
  }

  @Test
  @DisplayName("Cold queue memory limits derive capacity from 128 bytes per location")
  void testColdQueueMemoryDerivation() {
    // 1 KiB (1024 bytes) / 128 bytes = 8 locations
    assertEquals(8L, CacheMemoryCost.resolveCapacity("1KiB", 10L, CacheMemoryCost.COLD_CACHE_BYTES_PER_ENTRY));
    // 128 KB (128,000 bytes) / 128 bytes = 1000 locations
    assertEquals(1000L, CacheMemoryCost.resolveCapacity("128KB", 10L, CacheMemoryCost.COLD_CACHE_BYTES_PER_ENTRY));
    // 1 MiB (1,048,576 bytes) / 128 bytes = 8192 locations
    assertEquals(8192L, CacheMemoryCost.resolveCapacity("1MiB", 10L, CacheMemoryCost.COLD_CACHE_BYTES_PER_ENTRY));
  }

  @Test
  @DisplayName("Backlog queue memory limits derive capacity from 128 bytes per candidate")
  void testBacklogQueueMemoryDerivation() {
    // 2 MiB / 128 bytes = 16384 entries
    assertEquals(16384L, CacheMemoryCost.resolveCapacity("2MiB", 0L, CacheMemoryCost.BACKLOG_CACHE_BYTES_PER_ENTRY));
    // 256 KiB / 128 bytes = 2048 entries
    assertEquals(2048L, CacheMemoryCost.resolveCapacity("256KiB", 0L, CacheMemoryCost.BACKLOG_CACHE_BYTES_PER_ENTRY));
  }

  @Test
  @DisplayName("RegionConfigLoader seamlessly applies memory limits from region parser")
  void testRegionConfigLoaderWithMemoryLimits() {
    @SuppressWarnings("unchecked")
    ConfigParser<RegionKeys> parser = Mockito.mock(ConfigParser.class);
    parser.name = "testRegion.yml";
    Mockito.doReturn("32MiB").when(parser).getConfigValue(Mockito.eq(RegionKeys.activeChunkCap), Mockito.any());
    Mockito.doReturn("256KiB").when(parser).getConfigValue(Mockito.eq(RegionKeys.cacheCap), Mockito.any());
    Mockito.doReturn("1MiB").when(parser).getConfigValue(Mockito.eq(RegionKeys.backlogCacheCap), Mockito.any());

    RegionSettings settings = RegionConfigLoader.load(parser);
    assertEquals(32, settings.activeChunkCap()); // 32 MiB / 1 MiB = 32
    assertEquals(2048L, settings.cacheCap()); // 256 KiB (262,144 bytes) / 128 bytes = 2048
    assertEquals(8192L, settings.backlogCacheCap()); // 1 MiB (1,048,576 bytes) / 128 bytes = 8192
  }

  @Test
  @DisplayName("RegionConfigLoader retains basic count when no data size unit is present")
  void testRegionConfigLoaderWithBasicCounts() {
    @SuppressWarnings("unchecked")
    ConfigParser<RegionKeys> parser = Mockito.mock(ConfigParser.class);
    parser.name = "testRegionBasic.yml";
    Mockito.doReturn(12).when(parser).getConfigValue(Mockito.eq(RegionKeys.activeChunkCap), Mockito.any());
    Mockito.doReturn(150L).when(parser).getConfigValue(Mockito.eq(RegionKeys.cacheCap), Mockito.any());
    Mockito.doReturn(5000L).when(parser).getConfigValue(Mockito.eq(RegionKeys.backlogCacheCap), Mockito.any());

    RegionSettings settings = RegionConfigLoader.load(parser);
    assertEquals(12, settings.activeChunkCap());
    assertEquals(150L, settings.cacheCap());
    assertEquals(5000L, settings.backlogCacheCap());
  }
}
