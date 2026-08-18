package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.fixed;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.mock.ConfigurableMockChunk;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the core logic paths of {@link FixedAdjustor}: a vertical
 * adjustor that places the player at a configured Y in mid-air, with no
 * terrain scan. The destination cell {@code (x, y, z)} and the head cell
 * {@code (x, y+1, z)} must both be air; any non-air block in either cell
 * causes the chunk to be rejected so {@code ScanTask} can roll a different
 * one.
 */
public class FixedAdjustorTest {

  @TempDir Path tempDir;

  private MockRTPWorld world;

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir.toFile());
    world = new MockRTPWorld("test_world");
  }

  private FixedAdjustor buildAdjustor(int y) {
    FixedAdjustor adj = new FixedAdjustor(new ArrayList<>());
    adj.set(FixedAdjustorKeys.y, (long) y);
    return adj;
  }

  /**
   * Default mock chunk is fully air. With {@code y=128} configured, both
   * {@code y=128} and {@code y=129} are air, so the adjustor returns coords
   * at the configured Y.
   */
  @Test
  void midAirDestination_emptyChunk_returnsConfiguredY() {
    ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
    FixedAdjustor adj = buildAdjustor(128);

    RTPCoords result = adj.adjust(chunk);

    assertNotNull(result, "FixedAdjustor should accept an air-only chunk at the configured Y");
    assertEquals(128, result.y(), "Y should match the configured value");
    assertEquals("test_world", result.worldName());
  }

  /**
   * If the destination cell at the configured Y is non-air (terrain
   * intrusion), the adjustor must reject so a different chunk is rolled.
   */
  @Test
  void destinationBlocked_nonAirAtY_returnsNull() {
    ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
    // (lx, lz) = (7, 7) is the column FixedAdjustor uses. Block the feet cell.
    chunk.setSolid(64);
    FixedAdjustor adj = buildAdjustor(64);

    assertNull(adj.adjust(chunk),
        "FixedAdjustor must reject when the feet cell at the configured Y is non-air");
  }

  /**
   * Head clearance: a solid block at {@code y+1} must also reject - a
   * standing player would suffocate against it.
   */
  @Test
  void headBlocked_nonAirAtYPlus1_returnsNull() {
    ConfigurableMockChunk chunk = new ConfigurableMockChunk(0, 0, world);
    chunk.setSolid(65);
    FixedAdjustor adj = buildAdjustor(64);

    assertNull(adj.adjust(chunk),
        "FixedAdjustor must reject when the head cell (y+1) is non-air");
  }

  /**
   * The {@code adjust(chunk, output)} overload mirrors {@link FixedAdjustor#adjust(RTPChunk)}
   * writing into caller-owned {@link MutableRTPCoords}.
   */
  @Test
  void adjustWithOutput_emptyChunk_returnsTrueAndSetsCoords() {
    ConfigurableMockChunk chunk = new ConfigurableMockChunk(2, 3, world);
    FixedAdjustor adj = buildAdjustor(96);
    MutableRTPCoords out = new MutableRTPCoords("placeholder", 0, 0, 0);

    assertTrue(adj.adjust(chunk, out));
    assertEquals(96, out.y);
    assertEquals((2 << 4) + 7, out.x, "X should be chunkX*16 + 7 (the column FixedAdjustor uses)");
    assertEquals((3 << 4) + 7, out.z, "Z should be chunkZ*16 + 7");
    assertEquals("test_world", out.worldName);
  }

  /** {@code minY} / {@code maxY} accessors expose the configured Y window. */
  @Test
  void minYMaxY_accessors_returnConfiguredValues() {
    FixedAdjustor adj = buildAdjustor(80);
    assertEquals(80, adj.minY());
    assertEquals(81, adj.maxY(), "maxY should be y+1 to cover the head cell");
  }

  /** Sky-light is irrelevant for mid-air placement. */
  @Test
  void requiresSkyLight_isAlwaysFalse() {
    assertFalse(buildAdjustor(64).requiresSkyLight());
  }

  /** Tab-completion sub-parameter map is non-null and exposes the {@code y} key. */
  @Test
  void getParameters_exposesYKey() {
    FixedAdjustor adj = buildAdjustor(64);
    assertNotNull(adj.getParameters());
    assertTrue(adj.getParameters().containsKey("y"),
        "FixedAdjustor should expose a tab-completion entry for the 'y' parameter");
  }

  /**
   * The {@code FixedAdjustor} is registered with the global vertical-adjustor
   * factory under name {@code "fixed"} via {@link RTP}'s constructor. Verifies
   * users can select it by name from {@code regions/*.yml}.
   */
  @Test
  @SuppressWarnings("unchecked")
  void factoryRegistration_fixedNameIsResolvable() {
    Factory<VerticalAdjustor<?>> factory =
        (Factory<VerticalAdjustor<?>>) RTP.factoryMap.get(RTP.factoryNames.vert);
    assertNotNull(factory, "vertical-adjustor factory must be present");
    assertNotNull(factory.get("fixed"),
        "FixedAdjustor must be registered under the name 'fixed'");
    assertTrue(factory.get("fixed") instanceof FixedAdjustor);
  }
}
