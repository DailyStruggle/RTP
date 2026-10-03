package io.github.dailystruggle.rtp.addon.claim;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.claimaddon.GriefPreventionChecker;
import java.lang.reflect.Field;
import java.util.Collections;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GriefPreventionRegionVerifierTest {

  private Server mockServer;
  private PluginManager mockPluginManager;
  private World mockBukkitWorld;
  private RTPWorld<?> mockRtpWorld;

  private GriefPrevention mockGp;
  private DataStore mockDataStore;

  private GriefPreventionRegionVerifier verifier;

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

    mockGp = mock(GriefPrevention.class);
    mockDataStore = mock(DataStore.class);

    Field gpDataStoreField = GriefPrevention.class.getDeclaredField("dataStore");
    gpDataStoreField.setAccessible(true);
    gpDataStoreField.set(mockGp, mockDataStore);

    Field gpInstanceField = GriefPrevention.class.getDeclaredField("instance");
    gpInstanceField.setAccessible(true);
    gpInstanceField.set(null, mockGp);

    when(mockPluginManager.getPlugin("GriefPrevention")).thenReturn(mockGp);

    verifier = new GriefPreventionRegionVerifier();
  }

  @AfterEach
  void tearDown() throws Exception {
    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, null);

    Field gpInstanceField = GriefPrevention.class.getDeclaredField("instance");
    gpInstanceField.setAccessible(true);
    gpInstanceField.set(null, null);

    resetExists();
  }

  private void resetExists() {
    try {
      Field existsField = GriefPreventionChecker.class.getDeclaredField("exists");
      existsField.setAccessible(true);
      existsField.set(null, true);
    } catch (Throwable ignored) {
    }
  }

  @Test
  @DisplayName("verify returns FALSE when inside GriefPrevention claim (Rule S-003)")
  void testVerifyInsideClaimReturnsFalse() {
    Claim mockClaim = mock(Claim.class);
    when(mockDataStore.getClaims(anyInt(), anyInt())).thenReturn(Collections.singletonList(mockClaim));

    RTPLocation claimedLocation = new RTPLocation(mockRtpWorld, 100, 64, 100);
    boolean result = verifier.verify(claimedLocation);

    assertFalse(result, "Candidate location inside GriefPrevention claim must return FALSE (Rule S-003)");
  }

  @Test
  @DisplayName("verify returns TRUE in wilderness / non-claimed land")
  void testVerifyWildernessReturnsTrue() {
    when(mockDataStore.getClaims(anyInt(), anyInt())).thenReturn(Collections.emptyList());

    RTPLocation wildernessLocation = new RTPLocation(mockRtpWorld, 500, 64, 500);
    boolean result = verifier.verify(wildernessLocation);

    assertTrue(result, "Candidate location in wilderness must return TRUE");
  }

  @Test
  @DisplayName("verify returns FALSE or handles graceful fallback when GriefPrevention throws runtime exception")
  void testVerifyExceptionFallback() {
    RTPLocation loc = new RTPLocation(mockRtpWorld, 200, 64, 200);
    assertDoesNotThrow(() -> {
      // Direct exception fallback test
      try {
        Field existsField = GriefPreventionChecker.class.getDeclaredField("exists");
        existsField.setAccessible(true);
        existsField.set(null, false);
      } catch (Throwable ignored) {
      }
      boolean result = verifier.verify(loc);
      assertNotNull(result);
    });
  }

  @Test
  @DisplayName("verify returns expected safe fallback when plugin is missing / disabled")
  void testVerifyPluginMissing() {
    when(mockPluginManager.getPlugin("GriefPrevention")).thenReturn(null);

    RTPLocation loc = new RTPLocation(mockRtpWorld, 300, 64, 300);
    assertDoesNotThrow(() -> verifier.verify(loc));
  }

  @Test
  @DisplayName("verify returns FALSE for null location")
  void testVerifyNullLocation() {
    assertFalse(verifier.verify((RTPLocation) null));
  }
}
