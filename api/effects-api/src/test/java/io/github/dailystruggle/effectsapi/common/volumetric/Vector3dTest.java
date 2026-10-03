package io.github.dailystruggle.effectsapi.common.volumetric;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class Vector3dTest {

  @Test
  void testAllMethods() {
    Vector3d v1 = new Vector3d(1.0, 2.0, 3.0);
    Vector3d v2 = new Vector3d(4.0, 6.0, 8.0);

    // add double
    Vector3d addedD = v1.add(1.0, 2.0, 3.0);
    assertEquals(2.0, addedD.x());
    assertEquals(4.0, addedD.y());
    assertEquals(6.0, addedD.z());

    // add Vector3d
    Vector3d addedV = v1.add(v2);
    assertEquals(5.0, addedV.x());
    assertEquals(8.0, addedV.y());
    assertEquals(11.0, addedV.z());

    // subtract Vector3d
    Vector3d subV = v2.subtract(v1);
    assertEquals(3.0, subV.x());
    assertEquals(4.0, subV.y());
    assertEquals(5.0, subV.z());

    // multiply
    Vector3d mulV = v1.multiply(2.0);
    assertEquals(2.0, mulV.x());
    assertEquals(4.0, mulV.y());
    assertEquals(6.0, mulV.z());

    // distanceSquared and distance with Vector3d
    double distSq = v1.distanceSquared(v2);
    assertEquals(9.0 + 16.0 + 25.0, distSq, 1e-6);
    assertEquals(Math.sqrt(50.0), v1.distance(v2), 1e-6);

    // distanceSquared and distance with doubles
    double distSqD = v1.distanceSquared(4.0, 6.0, 8.0);
    assertEquals(50.0, distSqD, 1e-6);
    assertEquals(Math.sqrt(50.0), v1.distance(4.0, 6.0, 8.0), 1e-6);

    assertEquals(0.0, Vector3d.ZERO.x());
    assertEquals(0.0, Vector3d.ZERO.y());
    assertEquals(0.0, Vector3d.ZERO.z());
  }

  @Test
  void testNullGuards() {
    Vector3d v = new Vector3d(1, 2, 3);
    assertThrows(NullPointerException.class, () -> v.add(null));
    assertThrows(NullPointerException.class, () -> v.subtract(null));
    assertThrows(NullPointerException.class, () -> v.distanceSquared(null));
  }
}
