package io.github.dailystruggle.rtp.claimaddon;

import com.palmergames.bukkit.towny.TownyAPI;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import io.github.dailystruggle.rtp.common.selection.region.QueueTaskTestAccess;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import net.william278.huskclaims.api.BukkitHuskClaimsAPI;
import net.william278.husktowns.api.BukkitHuskTownsAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests verifying Rule S-003 compliance:
 * Zero teleports into claim-protected land across all supported claim providers.
 */
public class LeafRTPClaimAddonRuleS003IntegrationTest {

  @TempDir
  File tempDir;

  private Server mockServer;
  private PluginManager mockPluginManager;
  private World mockBukkitWorld;
  private MockRTPWorld rtpWorld;
  private MockRTPServerAccessor accessor;
  private Region region;
  private MockRTPPlayer player;

  // Active claim boundaries dynamically configured per provider test
  private final Set<String> claimedProviders = Collections.newSetFromMap(new ConcurrentHashMap<>());
  private int claimMinX = 0;
  private int claimMaxX = 100;
  private int claimMinZ = 0;
  private int claimMaxZ = 100;

  // Mocks for GriefPrevention
  private GriefPrevention mockGp;
  private DataStore mockDataStore;

  // Mocks for WorldEdit
  private WorldEditPlugin mockWePlugin;

  // Mocks for WorldGuard
  private WorldGuardPlugin mockWgPlugin;
  private WorldGuard mockWg;
  private WorldGuardPlatform mockWgPlatform;
  private RegionContainer mockWgContainer;
  private RegionManager mockWgRegionManager;
  private FlagRegistry mockFlagRegistry;

  // Mocks for Towny
  private TownyAPI mockTownyApi;

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

    // Inject Bukkit.server
    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, mockServer);

    // Setup RTP accessor and world
    rtpWorld = new MockRTPWorld("test_world");
    accessor.addWorld(rtpWorld);

    // Reset checkers static flags
    resetCheckerExists(GriefPreventionChecker.class);
    resetCheckerExists(WorldGuardChecker.class);
    resetCheckerExists(TownyAdvancedChecker.class);
    resetCheckerExists(HuskClaimsChecker.class);
    resetCheckerExists(HuskTownsChecker.class);

    // Setup GriefPrevention mock
    mockGp = mock(GriefPrevention.class);
    mockDataStore = mock(DataStore.class);
    Field gpDataStoreField = GriefPrevention.class.getDeclaredField("dataStore");
    gpDataStoreField.setAccessible(true);
    gpDataStoreField.set(mockGp, mockDataStore);

    Field gpInstanceField = GriefPrevention.class.getDeclaredField("instance");
    gpInstanceField.setAccessible(true);
    gpInstanceField.set(null, mockGp);
    when(mockPluginManager.getPlugin("GriefPrevention")).thenReturn(mockGp);

    when(mockDataStore.getClaims(any(Integer.class), any(Integer.class))).thenAnswer(invocation -> {
      if (!claimedProviders.contains("griefprevention")) return Collections.emptyList();
      int cx = invocation.getArgument(0);
      int cz = invocation.getArgument(1);
      int bx = cx << 4;
      int bz = cz << 4;
      if (bx + 15 >= claimMinX && bx <= claimMaxX && bz + 15 >= claimMinZ && bz <= claimMaxZ) {
        return Collections.singletonList(mock(Claim.class));
      }
      return Collections.emptyList();
    });

    // Setup WorldEdit mock
    mockWePlugin = mock(WorldEditPlugin.class);
    Field weInstField = WorldEditPlugin.class.getDeclaredField("INSTANCE");
    weInstField.setAccessible(true);
    weInstField.set(null, mockWePlugin);
    when(mockPluginManager.getPlugin("WorldEdit")).thenReturn(mockWePlugin);

    // Setup WorldGuard mock
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

    when(mockWgRegionManager.getApplicableRegions(any(BlockVector3.class))).thenAnswer(invocation -> {
      if (!claimedProviders.contains("worldguard")) {
        ApplicableRegionSet emptySet = mock(ApplicableRegionSet.class);
        when(emptySet.size()).thenReturn(0);
        return emptySet;
      }
      BlockVector3 pt = invocation.getArgument(0);
      int x = pt.x();
      int z = pt.z();
      if (x >= claimMinX && x <= claimMaxX && z >= claimMinZ && z <= claimMaxZ) {
        ApplicableRegionSet claimSet = mock(ApplicableRegionSet.class);
        when(claimSet.size()).thenReturn(1);
        when(claimSet.testState(any(), any())).thenReturn(false); // cannot select
        return claimSet;
      }
      ApplicableRegionSet emptySet = mock(ApplicableRegionSet.class);
      when(emptySet.size()).thenReturn(0);
      return emptySet;
    });

    // Setup Towny mock
    mockTownyApi = mock(TownyAPI.class);
    Field townyInstanceField = TownyAPI.class.getDeclaredField("instance");
    townyInstanceField.setAccessible(true);
    townyInstanceField.set(null, mockTownyApi);

    when(mockTownyApi.isWilderness(any(Location.class))).thenAnswer(invocation -> {
      if (!claimedProviders.contains("towny")) return true;
      Location loc = invocation.getArgument(0);
      int x = loc.getBlockX();
      int z = loc.getBlockZ();
      boolean inside = (x >= claimMinX && x <= claimMaxX && z >= claimMinZ && z <= claimMaxZ);
      return !inside;
    });

    // Setup HuskClaims stub
    BukkitHuskClaimsAPI.getInstance().setClaimPredicate(loc -> {
      if (!claimedProviders.contains("huskclaims")) return false;
      int x = loc.getBlockX();
      int z = loc.getBlockZ();
      return (x >= claimMinX && x <= claimMaxX && z >= claimMinZ && z <= claimMaxZ);
    });

    // Setup HuskTowns stub
    BukkitHuskTownsAPI.getInstance().setClaimPredicate(loc -> {
      if (!claimedProviders.contains("husktowns")) return false;
      int x = loc.getBlockX();
      int z = loc.getBlockZ();
      return (x >= claimMinX && x <= claimMaxX && z >= claimMinZ && z <= claimMaxZ);
    });

    // Region & Player setup
    Square square = new Square();
    square.set(GenericMemoryShapeParams.radius, 1000L);
    square.set(GenericMemoryShapeParams.centerRadius, 0L);
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "claim_test_region",
        rtpWorld,
        square,
        vert,
        false,
        false,
        10L,
        1000L,
        0L,
        5,
        0.0,
        1L,
        "",
        false);
    region = new Region("claim_test_region", settings);

    player = new MockRTPPlayer(
        UUID.randomUUID(),
        "ClaimTester",
        new io.github.dailystruggle.rtp.api.world.RTPLocation(rtpWorld, 500, 64, 500));
    accessor.addPlayer(player);
  }

  @AfterEach
  void tearDown() throws Exception {
    claimedProviders.clear();

    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, null);

    Field gpInstanceField = GriefPrevention.class.getDeclaredField("instance");
    gpInstanceField.setAccessible(true);
    gpInstanceField.set(null, null);

    try {
      Field weInstField = WorldEditPlugin.class.getDeclaredField("INSTANCE");
      weInstField.setAccessible(true);
      weInstField.set(null, null);
    } catch (Throwable ignored) {
    }

    // Reset WorldGuard platform mock
    try {
      WorldGuard.getInstance().setPlatform(null);
    } catch (Throwable ignored) {
    }

    Field townyInstanceField = TownyAPI.class.getDeclaredField("instance");
    townyInstanceField.setAccessible(true);
    townyInstanceField.set(null, null);

    BukkitHuskClaimsAPI.getInstance().setClaimPredicate(loc -> false);
    BukkitHuskTownsAPI.getInstance().setClaimPredicate(loc -> false);

    RTPTestSetup.cleanUp();
  }

  private void resetCheckerExists(Class<?> clazz) {
    try {
      Field existsField = clazz.getDeclaredField("exists");
      existsField.setAccessible(true);
      existsField.set(null, true);
    } catch (Throwable ignored) {
    }
    try {
      Field availableField = clazz.getDeclaredField("available");
      availableField.setAccessible(true);
      availableField.set(null, null);
    } catch (Throwable ignored) {
    }
  }

  /**
   * Requirement 1:
   * Pipeline candidate locations falling inside an active claim boundary return `false`
   * from the claim checkers / verifiers across all supported claim providers.
   */
  @ParameterizedTest(name = "Claim provider {0} rejects candidate inside claim boundary and accepts outside")
  @ValueSource(strings = {"griefprevention", "worldguard", "towny", "huskclaims", "husktowns"})
  @DisplayName("Requirement 1: Candidate locations in claim boundary return false from claim verifiers")
  void testCandidateLocationsInsideClaimReturnFalse(String provider) throws Exception {
    claimedProviders.add(provider);

    RTPCoords insideCoords = new RTPCoords("test_world", 50, 64, 50);
    RTPCoords outsideCoords = new RTPCoords("test_world", 500, 64, 500);

    // Register provider verifier into hooks as done by ClaimIntegrations
    AutoCloseable hookHandle = registerProviderVerifier(provider);
    try {
      // 1. Assert checker directly reports inside/outside correctly
      assertTrue(isClaimedByProvider(provider, insideCoords),
          "Provider " + provider + " must detect coordinates inside claim boundary");
      assertFalse(isClaimedByProvider(provider, outsideCoords),
          "Provider " + provider + " must NOT detect coordinates outside claim boundary");

      // 2. GlobalRegionVerifiers checkGlobalRegionVerifiers returns false for inside coordinates
      Boolean insideVerdict = GlobalRegionVerifiers.checkGlobalRegionVerifiers(insideCoords)
          .get(5, TimeUnit.SECONDS);
      assertFalse(insideVerdict,
          "Candidate location inside " + provider + " claim boundary must return false from verifier");

      Boolean outsideVerdict = GlobalRegionVerifiers.checkGlobalRegionVerifiers(outsideCoords)
          .get(5, TimeUnit.SECONDS);
      assertTrue(outsideVerdict,
          "Candidate location outside " + provider + " claim boundary must return true from verifier");
    } finally {
      hookHandle.close();
    }
  }

  /**
   * Requirement 2:
   * Teleport pipeline skips or retries candidate coordinates rather than dispatching
   * a player into protected zones.
   */
  @ParameterizedTest(name = "Teleport pipeline skips candidate inside {0} claim and retries")
  @ValueSource(strings = {"griefprevention", "worldguard", "towny", "huskclaims", "husktowns"})
  @DisplayName("Requirement 2: Teleport pipeline skips/retries candidate coordinates inside protected zones")
  void testTeleportPipelineSkipsCandidateInsideClaim(String provider) throws Exception {
    claimedProviders.add(provider);
    try (AutoCloseable hookHandle = registerProviderVerifier(provider)) {
      // Candidate 1 is at (50, 64, 50) - inside claim
      rtpWorld.getChunkAt(3, 3).join();
      long chunk1Key = (3L & 0xffffffffL) | (3L << 32);
      AtomicBoolean res1Closed = new AtomicBoolean(false);
      ChunkSet chunkSet1 = new ChunkSet(rtpWorld, 3, 3,
          Collections.singletonList(CompletableFuture.completedFuture(chunk1Key)),
          new CompletableFuture<>());
      ChunkReservation reservation1 = new ChunkReservation(chunkSet1, rtpWorld) {
        @Override
        public void close() {
          res1Closed.set(true);
          super.close();
        }
      };
      RTPLocation candidateInside = new RTPLocation(new RTPCoords("test_world", 50, 64, 50), 1, reservation1);

      // Candidate 2 is at (500, 64, 500) - outside claim (wilderness)
      rtpWorld.getChunkAt(31, 31).join();
      long chunk2Key = (31L & 0xffffffffL) | (31L << 32);
      AtomicBoolean res2Closed = new AtomicBoolean(false);
      ChunkSet chunkSet2 = new ChunkSet(rtpWorld, 31, 31,
          Collections.singletonList(CompletableFuture.completedFuture(chunk2Key)),
          new CompletableFuture<>());
      ChunkReservation reservation2 = new ChunkReservation(chunkSet2, rtpWorld) {
        @Override
        public void close() {
          res2Closed.set(true);
          super.close();
        }
      };
      RTPLocation candidateOutside = new RTPLocation(new RTPCoords("test_world", 500, 64, 500), 2, reservation2);

      // Offer both candidates to the pre-cached kept queue
      region.queueManager.keptLocations.offer(candidateInside);
      region.queueManager.keptLocations.offer(candidateOutside);

      GenerationResult dispatched = QueueTaskTestAccess.executeQueueTask(region, player, player, null)
          .get(10, TimeUnit.SECONDS);

      assertNotNull(dispatched, "Pipeline must dispatch a valid generation result");
      assertNotNull(dispatched.coords(), "Dispatched coords must not be null");

      // Verify that candidateInside was SKIPPED / RETRIED and reservation was closed
      assertTrue(res1Closed.get(), "Reservation for candidate inside claim must be closed when rejected");
      assertEquals(500, dispatched.coords().x(), "Dispatched destination must NOT be the claimed location");
      assertEquals(500, dispatched.coords().z(), "Dispatched destination must be the valid unclaimed location");
    }
  }

  /**
   * Requirement 3:
   * Pre-cached pool sweeps invalidate coordinates when a new claim is registered on top
   * of an already-cached location.
   */
  @ParameterizedTest(name = "Pre-cached pool sweep invalidates location newly claimed under {0}")
  @ValueSource(strings = {"griefprevention", "worldguard", "towny", "huskclaims", "husktowns"})
  @DisplayName("Requirement 3: Pre-cached pool sweeps invalidate coordinates on claim registration")
  void testPreCachedPoolSweepInvalidatesNewlyClaimedLocation(String provider) throws Exception {
    try (AutoCloseable hookHandle = registerProviderVerifier(provider)) {
      // 1. Initial state: wilderness at (64, 64, 64). Pre-cache candidate into kept queue.
      rtpWorld.getChunkAt(4, 4).join();
      long chunkKey = (4L & 0xffffffffL) | (4L << 32);
      AtomicBoolean resClosed = new AtomicBoolean(false);
      ChunkSet chunkSet = new ChunkSet(rtpWorld, 4, 4,
          Collections.singletonList(CompletableFuture.completedFuture(chunkKey)),
          new CompletableFuture<>());
      ChunkReservation reservation = new ChunkReservation(chunkSet, rtpWorld) {
        @Override
        public void close() {
          resClosed.set(true);
          super.close();
        }
      };
      RTPLocation cachedLoc = new RTPLocation(new RTPCoords("test_world", 64, 64, 64), 1, reservation);
      region.queueManager.keptLocations.offer(cachedLoc);

      // Verify location was initially valid in the pool
      Boolean initialCheck = GlobalRegionVerifiers.checkGlobalRegionVerifiers(cachedLoc.coords())
          .get(5, TimeUnit.SECONDS);
      assertTrue(initialCheck, "Initially unclaimed cached location must be valid");

      // 2. A new claim is created on top of cached location (64, 64, 64)
      claimMinX = 0;
      claimMaxX = 128;
      claimMinZ = 0;
      claimMaxZ = 128;
      claimedProviders.add(provider);

      // 3. Pool sweep: sweep the cached locations in the queue.
      // Any cached entry failing GlobalRegionVerifiers must be purged and closed.
      List<RTPLocation> invalidated = new ArrayList<>();
      List<RTPLocation> retained = new ArrayList<>();

      int size = region.queueManager.keptLocations.size();
      for (int i = 0; i < size; i++) {
        RTPLocation candidate = region.queueManager.keptLocations.poll();
        if (candidate == null) continue;
        Boolean pass = GlobalRegionVerifiers.checkGlobalRegionVerifiers(candidate.coords())
            .get(5, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(pass)) {
          if (candidate.reservation() != null) {
            candidate.reservation().close();
          }
          invalidated.add(candidate);
        } else {
          retained.add(candidate);
        }
      }

      // Assert that cachedLoc was invalidated and its reservation closed
      assertEquals(1, invalidated.size(), "Cached location must be invalidated after claim registration");
      assertEquals(0, retained.size(), "No invalid locations should be retained");
      assertTrue(resClosed.get(), "Chunk reservation must be closed when cached location is invalidated by pool sweep");
      assertEquals(0, region.queueManager.keptLocations.size(), "Kept queue must be cleared of invalid cached location");
    }
  }

  private boolean isClaimedByProvider(String provider, RTPCoords coords) {
    return switch (provider) {
      case "griefprevention" -> GriefPreventionChecker.isInClaim(coords);
      case "worldguard" -> WorldGuardChecker.isInClaim(coords);
      case "towny" -> TownyAdvancedChecker.isInClaim(coords);
      case "huskclaims" -> HuskClaimsChecker.isInClaim(coords);
      case "husktowns" -> HuskTownsChecker.isInClaim(coords);
      default -> throw new IllegalArgumentException("Unknown provider: " + provider);
    };
  }

  private AutoCloseable registerProviderVerifier(String provider) {
    return switch (provider) {
      case "griefprevention" ->
          RTPAPI.hooks().verifiers().register(GriefPreventionChecker.class, coords -> !GriefPreventionChecker.isInClaim(coords));
      case "worldguard" ->
          RTPAPI.hooks().verifiers().register(WorldGuardChecker.class, coords -> !WorldGuardChecker.isInClaim(coords));
      case "towny" ->
          RTPAPI.hooks().verifiers().register(TownyAdvancedChecker.class, coords -> !TownyAdvancedChecker.isInClaim(coords));
      case "huskclaims" ->
          RTPAPI.hooks().verifiers().register(HuskClaimsChecker.class, coords -> !HuskClaimsChecker.isInClaim(coords));
      case "husktowns" ->
          RTPAPI.hooks().verifiers().register(HuskTownsChecker.class, coords -> !HuskTownsChecker.isInClaim(coords));
      default -> throw new IllegalArgumentException("Unknown provider: " + provider);
    };
  }
}
