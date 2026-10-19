package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reflective checkers with no Bukkit server or no enabled claim plugin report "not claimed".
 * Behaviour against each plugin's API shape is covered by
 * {@link ReflectiveClaimCheckerApiShapeS003Test} and {@link MinePlotsCheckerS003Test}.
 */
class ReflectiveClaimCheckersTest {

  @Test
  @DisplayName("HuskTownsChecker fails open without a server running HuskTowns")
  void testHuskTownsCheckerGracefulAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    Boolean inClaim = HuskTownsChecker.isInClaim(coords);
    assertNotNull(inClaim);
    assertFalse(inClaim);
  }

  @Test
  @DisplayName("PlotSquaredChecker fails open without a server running PlotSquared")
  void testPlotSquaredCheckerGracefulAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    Boolean inClaim = PlotSquaredChecker.isInClaim(coords);
    assertNotNull(inClaim);
    assertFalse(inClaim);
  }

  @Test
  @DisplayName("UltimateClaimsChecker fails open without a server running UltimateClaims")
  void testUltimateClaimsCheckerGracefulAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    Boolean inClaim = UltimateClaimsChecker.isInClaim(coords);
    assertNotNull(inClaim);
    assertFalse(inClaim);
  }

  @Test
  @DisplayName("MinePlotsChecker fails open without a server running minePlots")
  void testMinePlotsCheckerGracefulAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    Boolean inClaim = MinePlotsChecker.isInClaim(coords);
    assertNotNull(inClaim);
    assertFalse(inClaim);
  }

  @Test
  @DisplayName("HuskClaimsChecker fails open without a server running HuskClaims")
  void testHuskClaimsCheckerGracefulAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    Boolean inClaim = HuskClaimsChecker.isInClaim(coords);
    assertNotNull(inClaim);
    assertFalse(inClaim);
  }

  @Test
  @DisplayName("CrashClaimChecker fails open without a server running CrashClaim")
  void testCrashClaimCheckerGracefulAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    Boolean inClaim = CrashClaimChecker.isInClaim(coords);
    assertNotNull(inClaim);
    assertFalse(inClaim);
  }

  @Test
  @DisplayName("KingdomsXChecker fails open without a server running Kingdoms")
  void testKingdomsXCheckerGracefulAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    Boolean inClaim = KingdomsXChecker.isInClaim(coords);
    assertNotNull(inClaim);
    assertFalse(inClaim);
  }

  @Test
  @DisplayName("UltimateClaimsChecker and MinePlotsChecker fail open when the claim plugin is not enabled")
  void testCheckersFailOpenWhenPluginAbsent() {
    // Fail-closed paths are covered by ReflectiveClaimCheckerApiShapeS003Test and MinePlotsCheckerS003Test
    org.bukkit.Server mockServer = org.mockito.Mockito.mock(org.bukkit.Server.class);
    org.bukkit.World mockWorld = org.mockito.Mockito.mock(org.bukkit.World.class);
    org.mockito.Mockito.when(mockServer.getWorld("world")).thenReturn(mockWorld);
    org.mockito.Mockito.when(mockWorld.getName()).thenReturn("world");

    try {
      java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
      serverField.setAccessible(true);
      serverField.set(null, mockServer);

      org.bukkit.Location loc = new org.bukkit.Location(mockWorld, 100, 64, 200);

      // The mocked server has no plugin manager, so no claim plugin is enabled.
      assertFalse(UltimateClaimsChecker.isInClaim(loc));
      assertFalse(MinePlotsChecker.isInClaim(loc));
    } catch (Exception e) {
      fail(e);
    } finally {
      try {
        java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, null);
      } catch (Exception ignored) {
      }
    }
  }
}
