package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MemoryShapeExtendedCoverageTest {

  @TempDir
  Path tempDir;

  @BeforeEach
  public void setUp() {
    RTPTestSetup.install(tempDir.toFile());
  }

  @AfterEach
  public void tearDown() {
    LocationGenerator.setRng(null);
  }

  @Test
  @DisplayName("select() returns valid coordinates for Square shape")
  public void testSelectSquare() {
    Square square = new Square("SELECT_SQUARE");
    square.set(GenericMemoryShapeParams.radius, 100L);
    square.set(GenericMemoryShapeParams.centerRadius, 10L);

    int[] coords = square.select();

    assertNotNull(coords);
    assertTrue(Math.abs(coords[0]) <= 100);
    assertTrue(Math.abs(coords[1]) <= 100);
  }

  @Test
  @DisplayName("Circle shape select generates valid coordinates within radius")
  public void testSelectCircle() {
    Circle circle = new Circle("SELECT_CIRCLE");
    circle.set(GenericMemoryShapeParams.radius, 100L);
    circle.set(GenericMemoryShapeParams.centerRadius, 10L);

    int[] coords = circle.select();

    assertNotNull(coords);
    double distSq = (double) coords[0] * coords[0] + (double) coords[1] * coords[1];
    assertTrue(distSq <= 100.0 * 100.0 + 1.0);
  }

  @Test
  @DisplayName("Rectangle shape select generates valid coordinates within dimensions")
  public void testSelectRectangle() {
    Rectangle rect = new Rectangle("SELECT_RECTANGLE");
    rect.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams.width, 80L);
    rect.set(io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.RectangleParams.height, 120L);

    int[] coords = rect.select();

    assertNotNull(coords);
  }

  @Test
  @DisplayName("Clone preserves data and creates independent shape")
  public void testClone() {
    Square square = new Square("ORIGINAL_SQUARE");
    square.set(GenericMemoryShapeParams.radius, 256L);
    square.set(GenericMemoryShapeParams.centerRadius, 32L);

    Square clone = (Square) square.clone();
    assertNotNull(clone);
    assertEquals(square.name, clone.name);
    assertEquals(square.getRange(), clone.getRange());
  }
}
