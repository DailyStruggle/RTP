package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdaptiveClaimProberTest {

  @Test
  @DisplayName("AdaptiveClaimProber accurately resolves boundaries of simulated rectangular claims")
  void testRectangularClaimProbing() {
    int expectedMinX = -84;
    int expectedMaxX = 142;
    int expectedMinZ = 30;
    int expectedMaxZ = 210;

    String world = "world";

    // Hit inside claim at (20, 100)
    Optional<ClaimBoundary> boundaryOpt = AdaptiveClaimProber.probeBoundary(
        world,
        20,
        100,
        coords -> coords.x() >= expectedMinX
            && coords.x() <= expectedMaxX
            && coords.z() >= expectedMinZ
            && coords.z() <= expectedMaxZ);

    assertTrue(boundaryOpt.isPresent(), "Boundary must be discovered");
    ClaimBoundary boundary = boundaryOpt.get();

    assertEquals(world, boundary.world());
    assertEquals(expectedMinX, boundary.minX(), "minX must match exact claim edge");
    assertEquals(expectedMaxX, boundary.maxX(), "maxX must match exact claim edge");
    assertEquals(expectedMinZ, boundary.minZ(), "minZ must match exact claim edge");
    assertEquals(expectedMaxZ, boundary.maxZ(), "maxZ must match exact claim edge");

    assertTrue(boundary.contains(20, 100));
    assertTrue(boundary.contains(expectedMinX, expectedMinZ));
    assertTrue(boundary.contains(expectedMaxX, expectedMaxZ));
    assertFalse(boundary.contains(expectedMinX - 1, 100));
    assertFalse(boundary.contains(expectedMaxX + 1, 100));
    assertFalse(boundary.contains(20, expectedMinZ - 1));
    assertFalse(boundary.contains(20, expectedMaxZ + 1));
  }

  @Test
  @DisplayName("AdaptiveClaimProber returns empty when probe origin is outside claim")
  void testProbeOriginOutsideClaim() {
    Optional<ClaimBoundary> boundaryOpt = AdaptiveClaimProber.probeBoundary(
        "world",
        500,
        500,
        coords -> coords.x() >= 0 && coords.x() <= 100 && coords.z() >= 0 && coords.z() <= 100);

    assertFalse(boundaryOpt.isPresent());
  }

  @Test
  @DisplayName("AdaptiveClaimProber caps probe at MAX_PROBE_DISTANCE when claim exceeds 512 blocks")
  void testProbeExceedingMaxDistance() {
    // Huge claim from -1000 to +1000
    Optional<ClaimBoundary> boundaryOpt = AdaptiveClaimProber.probeBoundary(
        "world",
        0,
        0,
        coords -> Math.abs(coords.x()) <= 1000 && Math.abs(coords.z()) <= 1000);

    assertTrue(boundaryOpt.isPresent());
    ClaimBoundary boundary = boundaryOpt.get();
    assertEquals(-512, boundary.minX());
    assertEquals(512, boundary.maxX());
    assertEquals(-512, boundary.minZ());
    assertEquals(512, boundary.maxZ());
  }

  @Test
  @DisplayName("AdaptiveClaimProber discovers non-convex L-shaped claims via bounded chunk flood fill")
  void testLShapedClaimFloodFillDiscovery() {
    String world = "world";

    // L-shaped claim consisting of:
    // Vertical arm: X: 0..32, Z: 0..160
    // Horizontal arm: X: 0..256, Z: 0..32
    java.util.function.Predicate<io.github.dailystruggle.rtp.api.world.RTPCoords> inLClaim = coords -> {
      int x = coords.x();
      int z = coords.z();
      boolean inVerticalArm = (x >= 0 && x <= 32 && z >= 0 && z <= 160);
      boolean inHorizontalArm = (x >= 0 && x <= 256 && z >= 0 && z <= 32);
      return inVerticalArm || inHorizontalArm;
    };

    // Probe starting at (16, 120) on the vertical arm
    // Notice: at Z=120, the horizontal arm does NOT intersect the X-axis through the origin!
    // Pure 4-axis ray probing would falsely truncate maxX at 32.
    Optional<ClaimBoundary> boundaryOpt = AdaptiveClaimProber.probeBoundary(world, 16, 120, inLClaim);
    assertTrue(boundaryOpt.isPresent(), "L-shaped claim boundary must be discovered");
    ClaimBoundary boundary = boundaryOpt.get();

    // Verify boundary encompasses both arms
    assertTrue(boundary.maxX() >= 256, "maxX must reach the horizontal arm (>= 256) via flood fill, got " + boundary.maxX());
    assertTrue(boundary.maxZ() >= 160, "maxZ must reach the top of the vertical arm (>= 160), got " + boundary.maxZ());
    assertTrue(boundary.minX() <= 0, "minX must reach 0");
    assertTrue(boundary.minZ() <= 0, "minZ must reach 0");

    // Coordinates in the vertical arm must be inside
    assertTrue(boundary.contains(16, 120), "Origin should be inside");
    assertTrue(boundary.contains(10, 10), "Base corner should be inside");
    assertTrue(boundary.contains(16, 150), "Top of vertical arm should be inside");

    // Coordinates in the horizontal arm must be inside
    assertTrue(boundary.contains(200, 16), "Point in horizontal arm should be inside");
    assertTrue(boundary.contains(250, 20), "Point near end of horizontal arm should be inside");

    // Point in the empty quadrant of the L-shape (X=200, Z=120) must NOT be inside!
    assertFalse(boundary.contains(200, 120), "Empty quadrant of L-shape must not be included");
    assertFalse(boundary.contains(-10, 50), "Outside to the west must not be included");
    assertFalse(boundary.contains(50, 180), "Outside to the north must not be included");
  }
}
