package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Polygon;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-RTP-F-004 / ADR-034: Chunky-style flow vertex pairs written by the web editor shall
 * survive RtpYamlReader -> shape section -> RegionConfigLoader.deserializeShape and install
 * on the polygon in chunk coordinates (unitless = chunks).
 */
public class PolygonYamlVerticesEndToEndTest {

  /** Exact block the web editor writes for a polygon region. */
  private static final String EDITOR_YAML = "shape:\n"
      + "  name: POLYGON\n"
      + "  vertices:\n"
      + "    - [-125c, 187c]\n"
      + "    - [2000b, 3000b]\n"
      + "    - [10, -4]\n";

  /** 2000b = 125c; 3000b = 187.5c -> Math.round = 188c. */
  private static final int[][] EXPECTED = {{-125, 187}, {125, 188}, {10, -4}};

  private Object previousShapeFactory;

  @BeforeAll
  static void setupServer() {
    MockRTPServerAccessor accessor =
        new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
  }

  @BeforeEach
  void registerPolygon() {
    previousShapeFactory = RTP.factoryMap.get(RTP.factoryNames.shape);
    Factory<Shape<?>> shapeFactory = new Factory<>();
    shapeFactory.add("POLYGON", new Polygon());
    RTP.factoryMap.put(RTP.factoryNames.shape, shapeFactory);
  }

  @AfterEach
  @SuppressWarnings({"unchecked", "rawtypes"})
  void restoreFactory() {
    if (previousShapeFactory != null) {
      RTP.factoryMap.put(RTP.factoryNames.shape, (Factory) previousShapeFactory);
    }
  }

  private static Polygon loadShape(String yaml) {
    RtpYamlSection shapeSection = RtpYamlConfig.parse(yaml).getConfigurationSection("shape");
    assertNotNull(shapeSection, "shape section should parse");
    Shape<?> shape = RegionConfigLoader.deserializeShape(shapeSection.getValues(false));
    assertInstanceOf(Polygon.class, shape);
    return (Polygon) shape;
  }

  private static void assertVertices(Polygon polygon) {
    List<int[]> vertices = polygon.getVertices();
    assertEquals(EXPECTED.length, vertices.size(), "all vertices should install");
    for (int i = 0; i < EXPECTED.length; i++) {
      assertArrayEquals(EXPECTED[i], vertices.get(i), "vertex " + i + " in chunks");
    }
  }

  @Test
  @DisplayName("ADR-034: editor-written '- [x, z]' vertices with c/b/unitless coords load as chunk vertices")
  void editorBlockForm_loadsPolygon() {
    assertVertices(loadShape(EDITOR_YAML));
  }

  @Test
  @DisplayName("ADR-034: inline nested 'vertices: [[x, z], ...]' form loads the same polygon")
  void inlineNestedForm_loadsPolygon() {
    assertVertices(loadShape("shape:\n  name: POLYGON\n"
        + "  vertices: [[-125c, 187c], [2000b, 3000b], [10, -4]]\n"));
  }

  @Test
  @DisplayName("ADR-034: a plugin save of the vertex list stays loadable as the same polygon")
  void saveAndReload_keepsPolygon() {
    RtpYamlConfig cfg = RtpYamlConfig.parse(EDITOR_YAML);
    cfg.set("shape.vertices", new ArrayList<>(cfg.getList("shape.vertices")));
    String saved = cfg.saveToString();
    assertTrue(saved.contains("    - [10, -4]\n"), "vertex pairs should stay compact: " + saved);
    assertVertices(loadShape(saved));
  }
}
