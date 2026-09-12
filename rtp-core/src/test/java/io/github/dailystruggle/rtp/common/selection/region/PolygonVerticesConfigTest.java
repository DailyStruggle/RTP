package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Polygon;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REQ-RTP-F-004 / ADR-034: the region config layer shall install a polygon's {@code vertices}
 * list. {@code FactoryValue.setData} drops the structured value, so without the explicit parse
 * the shape silently degrades to its bounding square.
 */
public class PolygonVerticesConfigTest {

  @BeforeAll
  static void setupServer() {
    MockRTPServerAccessor accessor =
        new MockRTPServerAccessor(new java.io.File("target/test-data"));
    RTP.serverAccessor = accessor;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
  }

  private static Map<String, Object> shapeMap(Object vertices) {
    Map<String, Object> map = new HashMap<>();
    map.put("name", "POLYGON");
    if (vertices != null) map.put("vertices", vertices);
    return map;
  }

  @Test
  @DisplayName("ADR-034: nested [x, z] lists from YAML install onto the polygon")
  void nestedListForm_installsVertices() {
    Polygon polygon = new Polygon();
    // L-shape, so a bounding-square degrade is observable via the excluded notch.
    List<Object> vertices = new ArrayList<>(Arrays.asList(
        Arrays.asList(0, 0),
        Arrays.asList(100, 0),
        Arrays.asList(100, 40),
        Arrays.asList(40, 40),
        Arrays.asList(40, 100),
        Arrays.asList(0, 100)));

    RegionConfigLoader.applyPolygonVertices(polygon, shapeMap(vertices));

    assertEquals(6, polygon.getVertices().size(), "vertex list should be installed from config");
    assertTrue(polygon.contains(20, 20), "interior column should be inside");
    assertFalse(polygon.contains(80, 80), "notch would only be inside a bounding-square degrade");
    // AABB mirrored onto the inherited Square.
    assertEquals(50, polygon.getNumber(GenericMemoryShapeParams.radius, -1).intValue());
    assertEquals(50, polygon.getNumber(GenericMemoryShapeParams.centerX, -1).intValue());
    assertEquals(50, polygon.getNumber(GenericMemoryShapeParams.centerZ, -1).intValue());
  }

  @Test
  @DisplayName("Scalar \"x,z\" and [x, z] string forms parse")
  void scalarStringForm_installsVertices() {
    Polygon polygon = new Polygon();
    List<Object> vertices = new ArrayList<>(Arrays.asList("0,0", "[100, 0]", "(50 , 80)"));

    RegionConfigLoader.applyPolygonVertices(polygon, shapeMap(vertices));

    assertEquals(3, polygon.getVertices().size());
    assertArrayEquals(new int[] {100, 0}, polygon.getVertices().get(1));
    assertArrayEquals(new int[] {50, 80}, polygon.getVertices().get(2));
  }

  @Test
  @DisplayName("Mapping forms (indexed list, and per-vertex x/z keys) parse")
  void mappingForms_installVertices() {
    Polygon polygon = new Polygon();
    Map<String, Object> indexed = new LinkedHashMap<>();
    indexed.put("0", Arrays.asList(0, 0));
    indexed.put("1", mapOf(100, 0));
    indexed.put("2", mapOf(50, 80));

    RegionConfigLoader.applyPolygonVertices(polygon, shapeMap(indexed));

    assertEquals(3, polygon.getVertices().size());
    assertArrayEquals(new int[] {50, 80}, polygon.getVertices().get(2));
  }

  private static Map<String, Object> mapOf(int x, int z) {
    Map<String, Object> vertex = new LinkedHashMap<>();
    vertex.put("x", x);
    vertex.put("z", z);
    return vertex;
  }

  @Test
  @DisplayName("A rejected vertex list leaves the polygon unset rather than throwing")
  void rejectedList_fallsBackWithoutThrowing() {
    // Self-intersecting bowtie: setVertices throws, the loader must absorb it.
    Polygon bowtie = new Polygon();
    assertDoesNotThrow(() -> RegionConfigLoader.applyPolygonVertices(bowtie, shapeMap(
        new ArrayList<>(Arrays.asList(
            Arrays.asList(0, 0),
            Arrays.asList(10, 0),
            Arrays.asList(0, 10),
            Arrays.asList(10, 10))))));
    assertTrue(bowtie.getVertices().isEmpty(), "invalid boundary must not be installed");

    // Fewer than 3 vertices.
    Polygon tooFew = new Polygon();
    assertDoesNotThrow(() -> RegionConfigLoader.applyPolygonVertices(tooFew, shapeMap(
        new ArrayList<>(Arrays.asList(Arrays.asList(0, 0), Arrays.asList(10, 10))))));
    assertTrue(tooFew.getVertices().isEmpty());

    // Malformed entries.
    Polygon malformed = new Polygon();
    assertDoesNotThrow(() -> RegionConfigLoader.applyPolygonVertices(malformed, shapeMap(
        new ArrayList<>(Arrays.asList("0,0", "not a vertex", Arrays.asList(50, 80))))));
    assertTrue(malformed.getVertices().isEmpty());

    // Wrong type entirely for the key.
    Polygon wrongType = new Polygon();
    assertDoesNotThrow(() -> RegionConfigLoader.applyPolygonVertices(wrongType, shapeMap(42)));
    assertTrue(wrongType.getVertices().isEmpty());

    // Absent key.
    Polygon absent = new Polygon();
    assertDoesNotThrow(() -> RegionConfigLoader.applyPolygonVertices(absent, shapeMap(null)));
    assertTrue(absent.getVertices().isEmpty());
  }

  @Test
  @DisplayName("ADR-034: expand stays off for a polygon even when config sets it true")
  void expandRemainsOff() {
    Polygon polygon = new Polygon();
    Map<String, Object> map = shapeMap(new ArrayList<>(Arrays.asList(
        Arrays.asList(0, 0), Arrays.asList(100, 0), Arrays.asList(50, 80))));
    map.put("expand", true);

    assertDoesNotThrow(() -> RegionConfigLoader.applyPolygonVertices(polygon, map));

    assertEquals(3, polygon.getVertices().size());
    assertEquals(Boolean.FALSE, polygon.getData(GenericMemoryShapeParams.expand));
  }
}
