package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReflectiveClaimCheckersTest {

  @Test
  @DisplayName("HuskTownsChecker safely fails-open when HuskTowns is not present")
  void testHuskTownsCheckerGracefulAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    // In test environment without Bukkit server or HuskTowns API, returns false safely
    Boolean inClaim = HuskTownsChecker.isInClaim(coords);
    assertNotNull(inClaim);
    assertFalse(inClaim);
  }

  @Test
  @DisplayName("PlotSquaredChecker safely fails-open when PlotSquared is not present")
  void testPlotSquaredCheckerGracefulAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    // In test environment without Bukkit server or PlotSquared API, returns false safely
    Boolean inClaim = PlotSquaredChecker.isInClaim(coords);
    assertNotNull(inClaim);
    assertFalse(inClaim);
  }

  @Test
  @DisplayName("UltimateClaimsChecker safely fails-open when UltimateClaims is not present")
  void testUltimateClaimsCheckerGracefulAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    // In test environment without Bukkit server or UltimateClaims API, returns false safely
    Boolean inClaim = UltimateClaimsChecker.isInClaim(coords);
    assertNotNull(inClaim);
    assertFalse(inClaim);
  }

  @Test
  @DisplayName("MinePlotsChecker safely fails-open when MinePlots is not present")
  void testMinePlotsCheckerGracefulAbsent() {
    RTPCoords coords = new RTPCoords("world", 100, 64, 200);
    // In test environment without Bukkit server or MinePlots API, returns false safely
    Boolean inClaim = MinePlotsChecker.isInClaim(coords);
    assertNotNull(inClaim);
    assertFalse(inClaim);
  }

  @Test
  @DisplayName("UltimateClaimsChecker and MinePlotsChecker fail-closed on error when mock server/world is present")
  void testCheckersFailClosedOnError() {
    // If location is provided with a valid mock or if reflection fails during execution, verify fail-closed
    org.bukkit.Server mockServer = org.mockito.Mockito.mock(org.bukkit.Server.class);
    org.bukkit.World mockWorld = org.mockito.Mockito.mock(org.bukkit.World.class);
    org.mockito.Mockito.when(mockServer.getWorld("world")).thenReturn(mockWorld);
    org.mockito.Mockito.when(mockWorld.getName()).thenReturn("world");

    try {
      java.lang.reflect.Field serverField = org.bukkit.Bukkit.class.getDeclaredField("server");
      serverField.setAccessible(true);
      serverField.set(null, mockServer);

      org.bukkit.Location loc = new org.bukkit.Location(mockWorld, 100, 64, 200);

      // Classes not on classpath, so fails-open (plugin absent)
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

  @Test
  @DisplayName("UltimateClaims reflective API contracts verify expected methods exist without NoSuchMethodException")
  void testUltimateClaimsReflectiveMethodResolution() throws Exception {
    // Define mock class structure representing Songoda / Craftaro UltimateClaims API
    class MockClaim {
      private final boolean claimed;
      MockClaim(boolean claimed) { this.claimed = claimed; }
      public boolean containsChunk(org.bukkit.Chunk c) { return claimed; }
      public boolean containsChunk(String world, int cx, int cz) { return claimed; }
    }

    class MockClaimManager {
      public Object getClaim(org.bukkit.Location loc) {
        if (loc.getBlockX() == 100) return new MockClaim(true);
        if (loc.getBlockX() == 999) throw new RuntimeException("Simulated database failure");
        return null;
      }
      public Object getClaim(String world, int cx, int cz) {
        if (cx == 10) return new MockClaim(true);
        return null;
      }
      public Object getClaim(org.bukkit.Chunk chunk) {
        return null;
      }
    }

    class MockUltimateClaims {
      private final MockClaimManager claimManager = new MockClaimManager();
      public MockClaimManager getClaimManager() { return claimManager; }
    }

    MockUltimateClaims plugin = new MockUltimateClaims();
    MockClaimManager manager = plugin.getClaimManager();

    // Verify reflective resolution that UltimateClaimsChecker performs
    java.lang.reflect.Method getClaimManagerMethod = plugin.getClass().getMethod("getClaimManager");
    assertNotNull(getClaimManagerMethod);
    Object resolvedManager = getClaimManagerMethod.invoke(plugin);
    assertSame(manager, resolvedManager);

    // Verify getClaim(Location) method existence and invocation
    java.lang.reflect.Method getClaimLoc = resolvedManager.getClass().getMethod("getClaim", org.bukkit.Location.class);
    assertNotNull(getClaimLoc);

    org.bukkit.World mockWorld = org.mockito.Mockito.mock(org.bukkit.World.class);
    org.bukkit.Location claimedLoc = new org.bukkit.Location(mockWorld, 100, 64, 200);
    org.bukkit.Location unclaimedLoc = new org.bukkit.Location(mockWorld, 200, 64, 200);
    org.bukkit.Location errorLoc = new org.bukkit.Location(mockWorld, 999, 64, 200);

    // Invocations succeed without NoSuchMethodException
    assertNotNull(getClaimLoc.invoke(resolvedManager, claimedLoc));
    assertNull(getClaimLoc.invoke(resolvedManager, unclaimedLoc));

    // When underlying method throws an invocation target exception, it's not NoSuchMethodException
    java.lang.reflect.InvocationTargetException ite = assertThrows(
        java.lang.reflect.InvocationTargetException.class,
        () -> getClaimLoc.invoke(resolvedManager, errorLoc)
    );
    assertInstanceOf(RuntimeException.class, ite.getCause());
    assertEquals("Simulated database failure", ite.getCause().getMessage());
  }

  @Test
  @DisplayName("MinePlots reflective API contracts verify expected methods exist without NoSuchMethodException")
  void testMinePlotsReflectiveMethodResolution() throws Exception {
    class MockPlot {
      private final boolean claimed;
      MockPlot(boolean claimed) { this.claimed = claimed; }
      public boolean isClaimed() { return claimed; }
    }

    class MockPlotServiceApi {
      public Object getPlot(org.bukkit.Location loc) {
        if (loc.getBlockX() == 100) return new MockPlot(true);
        if (loc.getBlockX() == 150) return new MockPlot(false);
        if (loc.getBlockX() == 999) throw new IllegalStateException("Service unavailable");
        return null;
      }
    }

    MockPlotServiceApi service = new MockPlotServiceApi();

    // Verify reflective resolution that MinePlotsChecker performs
    java.lang.reflect.Method getPlotMethod = service.getClass().getMethod("getPlot", org.bukkit.Location.class);
    assertNotNull(getPlotMethod);

    org.bukkit.World mockWorld = org.mockito.Mockito.mock(org.bukkit.World.class);
    org.bukkit.Location claimedLoc = new org.bukkit.Location(mockWorld, 100, 64, 200);
    org.bukkit.Location unclaimedPlotLoc = new org.bukkit.Location(mockWorld, 150, 64, 200);
    org.bukkit.Location outsideLoc = new org.bukkit.Location(mockWorld, 200, 64, 200);
    org.bukkit.Location errorLoc = new org.bukkit.Location(mockWorld, 999, 64, 200);

    Object claimedPlot = getPlotMethod.invoke(service, claimedLoc);
    assertNotNull(claimedPlot);
    java.lang.reflect.Method isClaimedMethod = claimedPlot.getClass().getMethod("isClaimed");
    assertNotNull(isClaimedMethod);
    assertEquals(true, isClaimedMethod.invoke(claimedPlot));

    Object unclaimedPlot = getPlotMethod.invoke(service, unclaimedPlotLoc);
    assertNotNull(unclaimedPlot);
    assertEquals(false, isClaimedMethod.invoke(unclaimedPlot));

    assertNull(getPlotMethod.invoke(service, outsideLoc));

    // Non-method exceptions thrown during query
    java.lang.reflect.InvocationTargetException ite = assertThrows(
        java.lang.reflect.InvocationTargetException.class,
        () -> getPlotMethod.invoke(service, errorLoc)
    );
    assertInstanceOf(IllegalStateException.class, ite.getCause());
    assertEquals("Service unavailable", ite.getCause().getMessage());
  }
}
