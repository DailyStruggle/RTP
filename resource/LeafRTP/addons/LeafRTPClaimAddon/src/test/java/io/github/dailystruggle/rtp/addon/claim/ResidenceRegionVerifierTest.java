package io.github.dailystruggle.rtp.addon.claim;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
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

class ResidenceRegionVerifierTest {

  private Server mockServer;
  private PluginManager mockPluginManager;
  private World mockBukkitWorld;
  private RTPWorld<?> mockRtpWorld;

  private ResidenceManager mockResidenceManager;
  private Residence mockResidence;
  private ResidenceRegionVerifier verifier;

  @BeforeEach
  void setUp() throws Exception {
    mockServer = mock(Server.class);
    mockPluginManager = mock(PluginManager.class);
    mockBukkitWorld = mock(World.class);
    mockRtpWorld = mock(RTPWorld.class);

    when(mockServer.getPluginManager()).thenReturn(mockPluginManager);
    when(mockPluginManager.isPluginEnabled("Residence")).thenReturn(true);
    when(mockServer.getWorld("test_world")).thenReturn(mockBukkitWorld);
    when(mockBukkitWorld.getName()).thenReturn("test_world");
    when(mockRtpWorld.name()).thenReturn("test_world");

    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, mockServer);

    mockResidenceManager = mock(ResidenceManager.class);
    mockResidence = mock(Residence.class);
    when(mockResidence.getResidenceManager()).thenReturn(mockResidenceManager);
    setResidenceInstance(mockResidence);

    verifier = new ResidenceRegionVerifier();
  }

  @AfterEach
  void tearDown() throws Exception {
    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, null);

    setResidenceInstance(null);
  }

  private static void setResidenceInstance(Residence value) throws Exception {
    Field instanceField = Residence.class.getDeclaredField("instance");
    instanceField.setAccessible(true);
    instanceField.set(null, value);
  }

  @Test
  @DisplayName("verify returns FALSE when location is within a ClaimedResidence (Rule S-003)")
  void testVerifyInsideResidenceClaimReturnsFalse() {
    ClaimedResidence claimedResidence = mock(ClaimedResidence.class);
    when(mockResidenceManager.getByLoc(any(Location.class))).thenReturn(claimedResidence);

    RTPLocation loc = new RTPLocation(mockRtpWorld, 100, 64, 100);
    boolean result = verifier.verify(loc);

    assertFalse(result, "Location inside Residence claim must return FALSE (Rule S-003)");
  }

  @Test
  @DisplayName("verify returns TRUE in wilderness (no ClaimedResidence at location)")
  void testVerifyWildernessReturnsTrue() {
    when(mockResidenceManager.getByLoc(any(Location.class))).thenReturn(null);

    RTPLocation loc = new RTPLocation(mockRtpWorld, 500, 64, 500);
    boolean result = verifier.verify(loc);

    assertTrue(result, "Wilderness location must return TRUE");
  }

  @Test
  @DisplayName("verify handles Residence reflection/manager exception gracefully")
  void testVerifyExceptionFallback() {
    when(mockResidenceManager.getByLoc(any(Location.class))).thenThrow(new RuntimeException("Simulated Residence error"));

    RTPLocation loc = new RTPLocation(mockRtpWorld, 150, 64, 150);
    assertDoesNotThrow(() -> verifier.verify(loc));
  }

  @Test
  @DisplayName("verify handles null residence manager gracefully")
  void testVerifyNullManagerFallback() {
    when(mockResidence.getResidenceManager()).thenReturn(null);

    RTPLocation loc = new RTPLocation(mockRtpWorld, 200, 64, 200);
    assertDoesNotThrow(() -> verifier.verify(loc));
  }

  @Test
  @DisplayName("verify returns FALSE for null location")
  void testVerifyNullLocation() {
    assertFalse(verifier.verify((RTPLocation) null));
  }
}
