package io.github.dailystruggle.rtp.bukkitplatform.world;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import org.bukkit.Chunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("BukkitRTPChunk coordinate boundary safety tests")
class BukkitRTPChunkBoundaryTest {

  private ServerMock server;

  @BeforeEach
  void setUp() {
    server = MockBukkit.mock();
  }

  @AfterEach
  void tearDown() {
    MockBukkit.unmock();
  }

  @Test
  @DisplayName("out-of-bounds coordinates return safe defaults without throwing IllegalArgumentException")
  void outOfBoundsCoordinatesReturnSafeDefaults() {
    WorldMock world = server.addSimpleWorld("boundary-test");
    Chunk chunk = world.getChunkAt(0, 0);
    BukkitRTPChunk rtpChunk = new BukkitRTPChunk(chunk);

    int minHeight = world.getMinHeight();
    int maxHeight = world.getMaxHeight();

    // Coordinates below minHeight
    assertFalse(rtpChunk.isAir(0, minHeight - 1, 0), "y below minHeight must not be air");
    assertFalse(rtpChunk.isSafe(0, minHeight - 1, 0, Set.of()), "y below minHeight must not be safe");
    assertEquals(0, rtpChunk.getSkyLight(0, minHeight - 1, 0), "y below minHeight skylight must be 0");

    // Coordinates at or above maxHeight
    assertFalse(rtpChunk.isAir(0, maxHeight, 0), "y at maxHeight must not be air");
    assertFalse(rtpChunk.isSafe(0, maxHeight, 0, Set.of()), "y at maxHeight must not be safe");
    assertEquals(0, rtpChunk.getSkyLight(0, maxHeight, 0), "y at maxHeight skylight must be 0");

    assertFalse(rtpChunk.isAir(0, maxHeight + 10, 0), "y above maxHeight must not be air");
    assertFalse(rtpChunk.isSafe(0, maxHeight + 10, 0, Set.of()), "y above maxHeight must not be safe");
    assertEquals(0, rtpChunk.getSkyLight(0, maxHeight + 10, 0), "y above maxHeight skylight must be 0");
  }
}
