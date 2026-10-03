package io.github.dailystruggle.rtp.addon.claim;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.claimaddon.WorldGuardChecker;
import java.io.File;
import java.lang.reflect.Field;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldGuardRegionVerifierTest {

  @TempDir
  File tempDir;

  private Server mockServer;
  private PluginManager mockPluginManager;
  private World mockBukkitWorld;
  private MockRTPWorld rtpWorld;
  private MockRTPServerAccessor accessor;

  private WorldGuardPlugin mockWgPlugin;
  private WorldEditPlugin mockWePlugin;
  private WorldGuardPlatform mockWgPlatform;
  private RegionContainer mockWgContainer;
  private RegionManager mockWgRegionManager;

  private WorldGuardRegionVerifier verifier;

  @BeforeEach
  void setUp() throws Exception {
    accessor = RTPTestSetup.install(tempDir);

    mockServer = mock(Server.class);
    mockPluginManager = mock(PluginManager.class);
    mockBukkitWorld = mock(World.class);

    when(mockServer.getVersion()).thenReturn("git-Paper-448 (MC: 1.20.1)");
    when(mockServer.getBukkitVersion()).thenReturn("1.20.1-R0.1-SNAPSHOT");
    when(mockServer.getPluginManager()).thenReturn(mockPluginManager);
    when(mockServer.getWorld("test_world")).thenReturn(mockBukkitWorld);
    when(mockBukkitWorld.getName()).thenReturn("test_world");

    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, mockServer);

    rtpWorld = new MockRTPWorld("test_world");
    accessor.addWorld(rtpWorld);

    resetExists();

    mockWePlugin = mock(WorldEditPlugin.class);
    Field weInstField = WorldEditPlugin.class.getDeclaredField("INSTANCE");
    weInstField.setAccessible(true);
    weInstField.set(null, mockWePlugin);
    when(mockPluginManager.getPlugin("WorldEdit")).thenReturn(mockWePlugin);

    mockWgPlugin = mock(WorldGuardPlugin.class);
    when(mockPluginManager.getPlugin("WorldGuard")).thenReturn(mockWgPlugin);

    mockWgPlatform = mock(WorldGuardPlatform.class);
    mockWgContainer = mock(RegionContainer.class);
    mockWgRegionManager = mock(RegionManager.class);

    when(mockWgPlatform.getRegionContainer()).thenReturn(mockWgContainer);
    when(mockWgContainer.get(any(com.sk89q.worldedit.world.World.class))).thenReturn(mockWgRegionManager);

    WorldGuard.getInstance().setPlatform(mockWgPlatform);

    try {
      WorldGuardChecker.setupWGFlag();
    } catch (Throwable ignored) {
    }
    if (WorldGuardChecker.CAN_RTP_SELECT_HERE == null) {
      StateFlag canRtpFlag = new StateFlag("can-rtp-select-here", false);
      try {
        WorldGuard.getInstance().getFlagRegistry().register(canRtpFlag);
      } catch (Throwable ignored) {
      }
      WorldGuardChecker.CAN_RTP_SELECT_HERE = canRtpFlag;
    }

    verifier = new WorldGuardRegionVerifier();
  }

  @AfterEach
  void tearDown() throws Exception {
    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, null);

    try {
      Field weInstField = WorldEditPlugin.class.getDeclaredField("INSTANCE");
      weInstField.setAccessible(true);
      weInstField.set(null, null);
    } catch (Throwable ignored) {
    }

    try {
      WorldGuard.getInstance().setPlatform(null);
    } catch (Throwable ignored) {
    }

    resetExists();
    RTPTestSetup.cleanUp();
  }

  private void resetExists() {
    try {
      Field existsField = WorldGuardChecker.class.getDeclaredField("exists");
      existsField.setAccessible(true);
      existsField.set(null, true);
    } catch (Throwable ignored) {
    }
    try {
      Field wgPluginField = WorldGuardChecker.class.getDeclaredField("worldGuardPlugin");
      wgPluginField.setAccessible(true);
      wgPluginField.set(null, null);
    } catch (Throwable ignored) {
    }
  }

  @Test
  @DisplayName("verify returns FALSE when inside protected WorldGuard region without allow flag (Rule S-003)")
  void testVerifyInsideProtectedRegionReturnsFalse() {
    ApplicableRegionSet claimSet = mock(ApplicableRegionSet.class);
    when(claimSet.size()).thenReturn(1);
    when(claimSet.testState(any(), any())).thenReturn(false); // cannot select here

    when(mockWgRegionManager.getApplicableRegions(any(BlockVector3.class))).thenReturn(claimSet);

    RTPLocation loc = new RTPLocation(rtpWorld, 100, 64, 100);
    boolean result = verifier.verify(loc);

    assertFalse(result, "Inside WorldGuard region without allow flag must return FALSE (Rule S-003)");
  }

  @Test
  @DisplayName("verify returns TRUE in wilderness (zero applicable WorldGuard regions)")
  void testVerifyWildernessReturnsTrue() {
    ApplicableRegionSet emptySet = mock(ApplicableRegionSet.class);
    when(emptySet.size()).thenReturn(0);

    when(mockWgRegionManager.getApplicableRegions(any(BlockVector3.class))).thenReturn(emptySet);

    RTPLocation loc = new RTPLocation(rtpWorld, 500, 64, 500);
    boolean result = verifier.verify(loc);

    assertTrue(result, "Wilderness location must return TRUE");
  }

  @Test
  @DisplayName("verify returns TRUE when inside WorldGuard region with can-rtp-select-here ALLOW flag")
  void testVerifyRegionWithBypassFlagReturnsTrue() {
    ApplicableRegionSet claimSet = mock(ApplicableRegionSet.class);
    when(claimSet.size()).thenReturn(1);
    when(claimSet.testState(any(), any())).thenReturn(true); // admin explicitly opted-in / allowed

    when(mockWgRegionManager.getApplicableRegions(any(BlockVector3.class))).thenReturn(claimSet);

    RTPLocation loc = new RTPLocation(rtpWorld, 100, 64, 100);
    boolean result = verifier.verify(loc);

    assertTrue(result, "WorldGuard region with can-rtp-select-here set to ALLOW must return TRUE");
  }

  @Test
  @DisplayName("verify handles runtime exception gracefully without crashing")
  void testVerifyExceptionFallback() {
    when(mockWgRegionManager.getApplicableRegions(any(BlockVector3.class)))
        .thenThrow(new RuntimeException("Simulated WG error"));

    RTPLocation loc = new RTPLocation(rtpWorld, 150, 64, 150);
    assertDoesNotThrow(() -> {
      boolean result = verifier.verify(loc);
      assertNotNull(result);
    });
  }

  @Test
  @DisplayName("verify handles missing WorldGuard plugin gracefully")
  void testVerifyPluginMissing() {
    when(mockPluginManager.getPlugin("WorldGuard")).thenReturn(null);

    RTPLocation loc = new RTPLocation(rtpWorld, 200, 64, 200);
    assertDoesNotThrow(() -> verifier.verify(loc));
  }

  @Test
  @DisplayName("verify returns FALSE for null location")
  void testVerifyNullLocation() {
    assertFalse(verifier.verify((RTPLocation) null));
  }
}
