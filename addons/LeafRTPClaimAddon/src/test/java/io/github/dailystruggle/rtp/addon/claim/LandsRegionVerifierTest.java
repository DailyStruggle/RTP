package io.github.dailystruggle.rtp.addon.claim;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.claimaddon.LandsChecker;
import java.lang.reflect.Field;
import me.angeschossen.lands.api.integration.LandsIntegration;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LandsRegionVerifierTest {

  private Server mockServer;
  private PluginManager mockPluginManager;
  private World mockBukkitWorld;
  private RTPWorld<?> mockRtpWorld;

  private LandsRegionVerifier verifier;

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

    Plugin mockPlugin = mock(Plugin.class);
    LandsChecker.landsSetup(mockPlugin);

    verifier = new LandsRegionVerifier();
  }

  @AfterEach
  void tearDown() throws Exception {
    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, null);

    Field landsField = LandsChecker.class.getDeclaredField("landsIntegration");
    landsField.setAccessible(true);
    landsField.set(null, null);

    resetExists();
  }

  private void resetExists() {
    try {
      Field existsField = LandsChecker.class.getDeclaredField("exists");
      existsField.setAccessible(true);
      existsField.set(null, true);
    } catch (Throwable ignored) {
    }
  }

  @Test
  @DisplayName("verify returns FALSE when location is within a Lands claim (Rule S-003)")
  void testVerifyInsideLandsClaimReturnsFalse() {
    LandsIntegration.setClaimedResult(true);

    RTPLocation loc = new RTPLocation(mockRtpWorld, 100, 64, 100);
    boolean result = verifier.verify(loc);

    assertFalse(result, "Location inside Lands claim must return FALSE (Rule S-003)");
  }

  @Test
  @DisplayName("verify returns TRUE in wilderness (not claimed by Lands)")
  void testVerifyWildernessReturnsTrue() {
    LandsIntegration.setClaimedResult(false);

    RTPLocation loc = new RTPLocation(mockRtpWorld, 500, 64, 500);
    boolean result = verifier.verify(loc);

    assertTrue(result, "Wilderness location must return TRUE");
  }

  @Test
  @DisplayName("verify handles null landsIntegration gracefully")
  void testVerifyNullIntegration() throws Exception {
    Field landsField = LandsChecker.class.getDeclaredField("landsIntegration");
    landsField.setAccessible(true);
    landsField.set(null, null);

    RTPLocation loc = new RTPLocation(mockRtpWorld, 200, 64, 200);
    assertDoesNotThrow(() -> verifier.verify(loc));
  }

  @Test
  @DisplayName("verify returns FALSE for null location")
  void testVerifyNullLocation() {
    assertFalse(verifier.verify((RTPLocation) null));
  }
}
