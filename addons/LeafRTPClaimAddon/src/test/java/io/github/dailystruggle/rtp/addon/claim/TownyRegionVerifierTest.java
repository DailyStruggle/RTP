package io.github.dailystruggle.rtp.addon.claim;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.palmergames.bukkit.towny.TownyAPI;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.claimaddon.TownyAdvancedChecker;
import java.lang.reflect.Field;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TownyRegionVerifierTest {

  private Server mockServer;
  private PluginManager mockPluginManager;
  private World mockBukkitWorld;
  private RTPWorld<?> mockRtpWorld;

  private TownyAPI mockTownyApi;
  private TownyRegionVerifier verifier;

  @BeforeEach
  void setUp() throws Exception {
    mockServer = mock(Server.class);
    mockPluginManager = mock(PluginManager.class);
    mockBukkitWorld = mock(World.class);
    mockRtpWorld = mock(RTPWorld.class);

    when(mockServer.getPluginManager()).thenReturn(mockPluginManager);
    when(mockServer.getWorld("test_world")).thenReturn(mockBukkitWorld);
    when(mockBukkitWorld.getName()).thenReturn("test_world");
    when(mockRtpWorld.name()).thenReturn("test_world");

    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, mockServer);

    resetExists();

    mockTownyApi = mock(TownyAPI.class);
    Field townyInstanceField = TownyAPI.class.getDeclaredField("instance");
    townyInstanceField.setAccessible(true);
    townyInstanceField.set(null, mockTownyApi);

    verifier = new TownyRegionVerifier();
  }

  @AfterEach
  void tearDown() throws Exception {
    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, null);

    Field townyInstanceField = TownyAPI.class.getDeclaredField("instance");
    townyInstanceField.setAccessible(true);
    townyInstanceField.set(null, null);

    resetExists();
  }

  private void resetExists() {
    try {
      Field existsField = TownyAdvancedChecker.class.getDeclaredField("exists");
      existsField.setAccessible(true);
      existsField.set(null, true);
    } catch (Throwable ignored) {
    }
  }

  @Test
  @DisplayName("verify returns FALSE when inside Towny town claim (Rule S-003)")
  void testVerifyInsideTownReturnsFalse() {
    // isWilderness returning false means location is within a town claim
    when(mockTownyApi.isWilderness(any(Location.class))).thenReturn(false);

    RTPLocation loc = new RTPLocation(mockRtpWorld, 100, 64, 100);
    boolean result = verifier.verify(loc);

    assertFalse(result, "Location inside Towny claim must return FALSE (Rule S-003)");
  }

  @Test
  @DisplayName("verify returns TRUE in Towny wilderness")
  void testVerifyWildernessReturnsTrue() {
    when(mockTownyApi.isWilderness(any(Location.class))).thenReturn(true);

    RTPLocation loc = new RTPLocation(mockRtpWorld, 500, 64, 500);
    boolean result = verifier.verify(loc);

    assertTrue(result, "Wilderness location must return TRUE");
  }

  @Test
  @DisplayName("verify handles TownyAPI runtime exception gracefully")
  void testVerifyExceptionFallback() {
    when(mockTownyApi.isWilderness(any(Location.class))).thenThrow(new RuntimeException("Simulated Towny error"));

    RTPLocation loc = new RTPLocation(mockRtpWorld, 150, 64, 150);
    assertDoesNotThrow(() -> {
      boolean result = verifier.verify(loc);
      assertNotNull(result);
    });
  }

  @Test
  @DisplayName("verify returns FALSE for null location")
  void testVerifyNullLocation() {
    assertFalse(verifier.verify((RTPLocation) null));
  }
}
