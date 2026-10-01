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
}
