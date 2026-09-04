package io.github.dailystruggle.rtp.common.selection.region.util;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("WorldBorderAuditor Tests")
class WorldBorderAuditorTest {

  @BeforeAll
  static void setupServer() {
    MockRTPServerAccessor accessor =
        new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
  }

  @Test
  void testInscribeBlockRadiusToChunks() {
    // 1000 blocks radius: (1000 - 15) / 16 = 985 / 16 = 61.5625 -> 61 chunks
    long chunks = WorldBorderAuditor.inscribeBlockRadiusToChunks(1000.0);
    assertEquals(61L, chunks);
    // At chunk 61: max block is 61 * 16 + 15 = 976 + 15 = 991 <= 1000.
    // If it were chunk 62: max block would be 62 * 16 + 15 = 1007 > 1000.

    // 4096 blocks radius: (4096 - 15) / 16 = 4081 / 16 = 255 chunks
    assertEquals(255L, WorldBorderAuditor.inscribeBlockRadiusToChunks(4096.0));

    // Under 16 blocks:
    assertEquals(0L, WorldBorderAuditor.inscribeBlockRadiusToChunks(15.0));
  }

  @Test
  void testWorldBorderCheckPassesWhenInside() {
    MockRTPServerAccessor accessor =
        new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;

    Square regionSquare = new Square();
    regionSquare.set(GenericMemoryShapeParams.radius, 100L); // 100 chunks
    regionSquare.set(GenericMemoryShapeParams.centerX, 0L);
    regionSquare.set(GenericMemoryShapeParams.centerZ, 0L);

    Square borderSquare = new Square();
    borderSquare.set(GenericMemoryShapeParams.radius, 200L); // 200 chunks
    borderSquare.set(GenericMemoryShapeParams.centerX, 0L);
    borderSquare.set(GenericMemoryShapeParams.centerZ, 0L);

    WorldBorder border = new WorldBorder(() -> borderSquare, loc -> true);

    RTPWorld<?> mockWorld = mock(RTPWorld.class);
    when(mockWorld.name()).thenReturn("world");
    accessor.setWorldBorderFunction(s -> border);

    boolean result = WorldBorderAuditor.checkRegionWorldBorder("testRegion", mockWorld, regionSquare, false);
    assertTrue(result);
  }

  @Test
  void testWorldBorderCheckFailsWhenExceedingBorder() {
    MockRTPServerAccessor accessor =
        new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;

    Square regionSquare = new Square();
    regionSquare.set(GenericMemoryShapeParams.radius, 300L); // 300 chunks
    regionSquare.set(GenericMemoryShapeParams.centerX, 0L);
    regionSquare.set(GenericMemoryShapeParams.centerZ, 0L);

    Square borderSquare = new Square();
    borderSquare.set(GenericMemoryShapeParams.radius, 200L); // 200 chunks
    borderSquare.set(GenericMemoryShapeParams.centerX, 0L);
    borderSquare.set(GenericMemoryShapeParams.centerZ, 0L);

    WorldBorder border = new WorldBorder(() -> borderSquare, loc -> true);

    RTPWorld<?> mockWorld = mock(RTPWorld.class);
    when(mockWorld.name()).thenReturn("world");
    accessor.setWorldBorderFunction(s -> border);

    boolean result = WorldBorderAuditor.checkRegionWorldBorder("testRegion", mockWorld, regionSquare, false);
    assertFalse(result);
  }

  @Test
  void testAutoInterpretShapeReinterpretsBlockInputPastBorder() {
    MockRTPServerAccessor accessor =
        new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;

    // Suppose world border is 10,000 blocks radius (625 chunks)
    Square borderSquare = new Square();
    borderSquare.set(GenericMemoryShapeParams.radius, 625L);
    borderSquare.set(GenericMemoryShapeParams.centerX, 0L);
    borderSquare.set(GenericMemoryShapeParams.centerZ, 0L);

    WorldBorder border = new WorldBorder(() -> borderSquare, loc -> true);

    RTPWorld<?> mockWorld = mock(RTPWorld.class);
    when(mockWorld.name()).thenReturn("world");
    accessor.setWorldBorderFunction(s -> border);

    // Operator wrote radius: 5000 in config.
    // If interpreted as chunks: 5000 * 16 = 80,000 blocks (> 10,000 border radius)!
    // But 5000 blocks <= 10,000 border radius.
    // So autoInterpretShape reinterprets 5000 as blocks -> 312.5 chunks (312 or 312.5 chunks).
    Square regionSquare = new Square();
    regionSquare.set(GenericMemoryShapeParams.radius, 5000L);
    regionSquare.set(GenericMemoryShapeParams.centerRadius, 1000L);

    WorldBorderAuditor.autoInterpretShape("testRegion", mockWorld, regionSquare);

    // In chunks: 5000 blocks / 16 = 312.5 chunks.
    Number newRad = regionSquare.getNumber(GenericMemoryShapeParams.radius, 0);
    assertEquals(312.5, newRad.doubleValue(), 0.001);

    // 1000 blocks / 16 = 62.5 chunks
    Number newCenterRad = regionSquare.getNumber(GenericMemoryShapeParams.centerRadius, 0);
    assertEquals(62.5, newCenterRad.doubleValue(), 0.001);
  }
}
