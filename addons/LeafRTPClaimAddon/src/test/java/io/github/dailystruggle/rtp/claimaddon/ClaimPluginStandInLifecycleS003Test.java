package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.net.fabiozumbi12.RedProtect.Bukkit.API.RedProtectAPI;
import br.net.fabiozumbi12.RedProtect.Bukkit.RedProtect;
import br.net.fabiozumbi12.RedProtect.Bukkit.Region;
import cc.javajobs.factionsbridge.FactionsBridge;
import cc.javajobs.factionsbridge.bridge.infrastructure.struct.FactionsAPI;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import com.craftaro.ultimateclaims.UltimateClaims;
import com.griefdefender.api.GriefDefender;
import com.massivecraft.factions.Board;
import com.palmergames.bukkit.towny.TownyAPI;
import com.plotsquared.core.plot.Plot;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.model.FailTypes;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers.VerifierCheckResult;
import io.github.dailystruggle.rtp.common.selection.region.claim.ClaimAnchoredRegionTracker;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import me.angeschossen.lands.api.integration.LandsIntegration;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.DataStore;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import net.crashcraft.crashclaim.CrashClaim;
import net.crashcraft.crashclaim.api.CrashClaimAPI;
import net.william278.huskclaims.api.BukkitHuskClaimsAPI;
import net.william278.husktowns.api.BukkitHuskTownsAPI;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.kingdoms.constants.land.Land;
import pl.minecodes.plots.api.plot.PlotApi;
import pl.minecodes.plots.api.plot.PlotServiceApi;

/**
 * REQ-RTP-S-003 / ADR-079: every claim integration, registered through
 * {@link ClaimIntegrations#setup(ClassLoader)} on the mock RTP server against a stand-in of its
 * claim plugin, rejects a claimed destination (attributed to its checker class), admits open land,
 * and stores the rejection as {@link FailTypes#safetyExternal} memory carrying the per-verifier
 * {@code ttl.yml} retention, which survives a save/load and expires back to candidate-eligible.
 *
 * <p>Every stand-in reports the same claim: blocks [0, 128) on both axes of world {@code world}
 * (chunks 0..7).
 */
@DisplayName("REQ-RTP-S-003 / ADR-079: claim integrations reject, admit and store with TTL against plugin stand-ins")
class ClaimPluginStandInLifecycleS003Test {

  private static final String WORLD = "world";
  private static final int CLAIM_END = 128;
  private static final RTPCoords CLAIMED = new RTPCoords(WORLD, 64, 64, 64);
  private static final RTPCoords OPEN = new RTPCoords(WORLD, 1000, 64, 1000);

  /** One entry per integration toggle in {@code integrations.yml}. */
  enum StandIn {
    SABER_FACTIONS(IntegrationsKeys.rerollSaberFactions, SaberFactionsChecker.PLUGIN_NAME, SaberFactionsChecker.class),
    FACTIONS_BRIDGE(IntegrationsKeys.rerollFactionsBridge, FactionsBridgeChecker.PLUGIN_NAME, FactionsBridgeChecker.class),
    GRIEF_DEFENDER(IntegrationsKeys.rerollGriefDefender, "GriefDefender", GriefDefenderChecker.class),
    GRIEF_PREVENTION(IntegrationsKeys.rerollGriefPrevention, "GriefPrevention", GriefPreventionChecker.class),
    LANDS(IntegrationsKeys.rerollLands, "Lands", LandsChecker.class),
    RED_PROTECT(IntegrationsKeys.rerollRedProtect, "RedProtect", RedProtectChecker.class),
    RESIDENCE(IntegrationsKeys.rerollResidence, ResidenceChecker.PLUGIN_NAME, ResidenceChecker.class),
    CRASH_CLAIM(IntegrationsKeys.rerollCrashClaim, CrashClaimChecker.PLUGIN_NAME, CrashClaimChecker.class),
    HUSK_CLAIMS(IntegrationsKeys.rerollHuskClaims, HuskClaimsChecker.PLUGIN_NAME, HuskClaimsChecker.class),
    HUSK_TOWNS(IntegrationsKeys.rerollHuskTowns, HuskTownsChecker.PLUGIN_NAME, HuskTownsChecker.class),
    PLOT_SQUARED(IntegrationsKeys.rerollPlotSquared, PlotSquaredChecker.PLUGIN_NAME, PlotSquaredChecker.class),
    KINGDOMS_X(IntegrationsKeys.rerollKingdomsX, KingdomsXChecker.PLUGIN_NAME, KingdomsXChecker.class),
    TOWNY(IntegrationsKeys.rerollTownyAdvanced, "Towny", TownyAdvancedChecker.class),
    WORLD_GUARD(IntegrationsKeys.rerollWorldGuard, "WorldGuard", WorldGuardChecker.class),
    ULTIMATE_CLAIMS(IntegrationsKeys.rerollUltimateClaims, UltimateClaimsChecker.PLUGIN_NAME, UltimateClaimsChecker.class),
    MINE_PLOTS(IntegrationsKeys.rerollMinePlots, MinePlotsChecker.PLUGIN_NAME, MinePlotsChecker.class);

    final IntegrationsKeys flag;
    final String pluginName;
    final Class<?> checker;

    StandIn(IntegrationsKeys flag, String pluginName, Class<?> checker) {
      this.flag = flag;
      this.pluginName = pluginName;
      this.checker = checker;
    }
  }

  @TempDir
  File tempDir;

  private PluginManager pluginManager;
  private ServicesManager servicesManager;

  @BeforeEach
  void setUp() throws Exception {
    RTPTestSetup.install(tempDir);
    GlobalRegionVerifiers.clearGlobalRegionVerifiers();
    TtlConfig.resetDefaults();

    Server server = mock(Server.class);
    pluginManager = mock(PluginManager.class);
    servicesManager = mock(ServicesManager.class);
    World world = mock(World.class);
    when(world.getName()).thenReturn(WORLD);
    when(server.getVersion()).thenReturn("git-Paper-448 (MC: 1.20.1)");
    when(server.getBukkitVersion()).thenReturn("1.20.1-R0.1-SNAPSHOT");
    when(server.getPluginManager()).thenReturn(pluginManager);
    when(server.getServicesManager()).thenReturn(servicesManager);
    when(server.getWorld(WORLD)).thenReturn(world);
    // Lands registers its API against the host plugin.
    Plugin rtpPlugin = mock(Plugin.class);
    when(pluginManager.getPlugin("RTP")).thenReturn(rtpPlugin);
    setStatic(Bukkit.class, "server", server);
  }

  @AfterEach
  void tearDown() throws Exception {
    GlobalRegionVerifiers.clearGlobalRegionVerifiers();
    TtlConfig.resetDefaults();

    Board.setInstance(null);
    FactionsBridge.setFactionsAPI(null);
    GriefDefender.setCore(null);
    setStatic(GriefPrevention.class, "instance", null);
    LandsIntegration.setLookup(null);
    RedProtect.setInstance(null);
    Residence.setInstance(null);
    CrashClaim.setPlugin(null);
    BukkitHuskClaimsAPI.register(null);
    BukkitHuskTownsAPI.register(null);
    com.plotsquared.core.location.Location.setResolver(null);
    Land.setLookup(null);
    setStatic(TownyAPI.class, "instance", null);
    // WorldGuard.setPlatform rejects null; the platform mock stays until the next WorldGuard
    // install replaces it, and no other integration reads it.
    setStatic(WorldEditPlugin.class, "INSTANCE", null);
    UltimateClaims.setInstance(null);

    setStatic(Bukkit.class, "server", null);
    RTPTestSetup.cleanUp();
  }

  @ParameterizedTest(name = "{0}: claimed destination rejected by its checker, open land admitted")
  @EnumSource(StandIn.class)
  @DisplayName("rejection and admission through the registered verifier")
  void rejectsClaimedAndAdmitsOpen(StandIn standIn) throws Exception {
    enableIntegration(standIn, true);

    VerifierCheckResult claimed = verify(CLAIMED);
    assertFalse(claimed.passed(), standIn + " must reject a destination inside its claim");
    assertEquals(standIn.checker, claimed.failedVerifierClass(),
        "rejection must be attributed to " + standIn.checker.getSimpleName());

    assertTrue(verify(OPEN).passed(), standIn + " must admit a destination outside every claim");
  }

  @ParameterizedTest(name = "{0}: integrations.yml flag off registers no verifier")
  @EnumSource(StandIn.class)
  @DisplayName("an integration whose flag is off does not reject")
  void flagOffRegistersNothing(StandIn standIn) throws Exception {
    enableIntegration(standIn, false);
    assertTrue(verify(CLAIMED).passed(), standIn + " must not reject while its flag is off");
  }

  @ParameterizedTest(name = "{0}: rejection stored as safetyExternal with the per-verifier TTL, kept across save/load")
  @EnumSource(StandIn.class)
  @DisplayName("storage of a rejection with ttl.yml retention")
  void storesRejectionWithVerifierTtl(StandIn standIn) throws Exception {
    enableIntegration(standIn, true);
    long ttlSeconds = 2L * 3600L;
    TtlConfig.load(
        Map.of("safetyExternal", "14d"),
        Map.of(standIn.checker.getSimpleName(), "2h"));

    VerifierCheckResult claimed = verify(CLAIMED);
    assertFalse(claimed.passed());

    Circle shape = shape();
    long before = Instant.now().getEpochSecond();
    int marked = ClaimAnchoredRegionTracker.encapsulateClaim(
        shape, WORLD, CLAIMED.x(), CLAIMED.z(), claimed.failedVerifierClass());
    long after = Instant.now().getEpochSecond();
    shape.flushAndRebuild(1L);

    assertTrue(marked > 0, "the rejection must be stored");
    assertTrue(shape.isKnownBad(CLAIMED.x() >> 4, CLAIMED.z() >> 4), "rejected chunk must be known bad");
    assertFalse(shape.isKnownBad(OPEN.x() >> 4, OPEN.z() >> 4), "open chunk must stay candidate-eligible");
    assertFalse(shape.isKnownBad((CLAIM_END >> 4) + 4, (CLAIM_END >> 4) + 4),
        "land outside the claim must stay candidate-eligible");
    assertStoredRuns(shape, before + ttlSeconds, after + ttlSeconds);

    String file = "claim-ttl-" + standIn.name();
    shape.save(file, WORLD);
    Circle reloaded = shape();
    reloaded.load(file, WORLD).get(10, TimeUnit.SECONDS);

    assertTrue(reloaded.isKnownBad(CLAIMED.x() >> 4, CLAIMED.z() >> 4), "rejection must survive save/load");
    assertStoredRuns(reloaded, before + ttlSeconds, after + ttlSeconds);
  }

  @ParameterizedTest(name = "{0}: expired rejection is readmitted, then relearned while the claim stands")
  @EnumSource(StandIn.class)
  @DisplayName("TTL expiry readmits a stored rejection; a fresh rejection restores it")
  void expiredRejectionIsReadmittedThenRelearned(StandIn standIn) throws Exception {
    enableIntegration(standIn, true);
    TtlConfig.load(null, Map.of(standIn.checker.getSimpleName(), "1s"));

    VerifierCheckResult claimed = verify(CLAIMED);
    Circle shape = shape();
    ClaimAnchoredRegionTracker.encapsulateClaim(
        shape, WORLD, CLAIMED.x(), CLAIMED.z(), claimed.failedVerifierClass());
    shape.flushAndRebuild(1L);
    long expiry = maxExpiry(shape);
    assertTrue(expiry > 0L, "a 1s TTL must store a finite expiry");

    String file = "claim-expiry-" + standIn.name();
    shape.save(file, WORLD);
    for (int i = 0; i < 100 && Instant.now().getEpochSecond() < expiry; i++) {
      Thread.sleep(50L);
    }
    assertTrue(Instant.now().getEpochSecond() >= expiry, "expiry must have passed");

    Circle reloaded = shape();
    reloaded.load(file, WORLD).get(10, TimeUnit.SECONDS);
    int cx = CLAIMED.x() >> 4;
    int cz = CLAIMED.z() >> 4;
    assertFalse(reloaded.isKnownBad(cx, cz), "an expired rejection must be candidate-eligible again");
    assertTrue(reloaded.probationKeysSnapshot().length > 0, "an expired rejection must enter probation");

    // The claim still stands: the verifier rejects again and the memory is restored.
    VerifierCheckResult again = verify(CLAIMED);
    assertFalse(again.passed(), standIn + " must still reject the claimed destination");
    ClaimAnchoredRegionTracker.encapsulateClaim(
        reloaded, WORLD, CLAIMED.x(), CLAIMED.z(), again.failedVerifierClass());
    reloaded.flushAndRebuild(1L);
    assertTrue(reloaded.isKnownBad(cx, cz), "a fresh rejection must restore the stored memory");
  }

  // --- harness ---

  private static boolean claimedBlock(int x, int z) {
    return x >= 0 && x < CLAIM_END && z >= 0 && z < CLAIM_END;
  }

  private static boolean claimedBlock(Location loc) {
    return loc != null && WORLD.equals(loc.getWorld().getName())
        && claimedBlock(loc.getBlockX(), loc.getBlockZ());
  }

  private static boolean claimedChunk(String world, int cx, int cz) {
    return WORLD.equals(world) && claimedBlock(cx << 4, cz << 4);
  }

  /** Installs the stand-in, enables its plugin, writes its flag and runs the addon's registration. */
  private void enableIntegration(StandIn standIn, boolean flag) throws Exception {
    install(standIn);
    when(pluginManager.isPluginEnabled(standIn.pluginName)).thenReturn(true);
    writeIntegrationsYml(standIn.flag, flag);
    ClaimIntegrations.setup(ClaimIntegrations.class.getClassLoader());
  }

  private void writeIntegrationsYml(IntegrationsKeys key, boolean value) throws Exception {
    String bundled;
    try (InputStream in = ClaimIntegrations.class.getClassLoader()
        .getResourceAsStream("addons/integrations.yml")) {
      assertNotNull(in, "bundled addons/integrations.yml");
      bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String toggled = bundled.replace(key.name() + ": false", key.name() + ": " + value);
    File target = new File(new File(tempDir, "addons"), "integrations.yml");
    Files.createDirectories(target.getParentFile().toPath());
    Files.writeString(target.toPath(), toggled, StandardCharsets.UTF_8);
  }

  private void install(StandIn standIn) throws Exception {
    switch (standIn) {
      case SABER_FACTIONS -> Board.setInstance(new Board(f -> {
        boolean claimed = claimedChunk(f.getWorldName(), (int) f.getX(), (int) f.getZ());
        return () -> !claimed;
      }));
      case FACTIONS_BRIDGE -> FactionsBridge.setFactionsAPI(new FactionsAPI() {
        @Override
        public cc.javajobs.factionsbridge.bridge.infrastructure.struct.Faction getFactionAt(Chunk chunk) {
          throw new AssertionError("chunk lookup needs a loaded chunk; not expected");
        }

        @Override
        public cc.javajobs.factionsbridge.bridge.infrastructure.struct.Faction getFactionAtChunk(
            String world, int cx, int cz) {
          boolean claimed = claimedChunk(world, cx, cz);
          return () -> !claimed;
        }
      });
      case GRIEF_DEFENDER -> GriefDefender.setCore(loc -> {
        boolean claimed = claimedBlock((Location) loc);
        return () -> !claimed;
      });
      case GRIEF_PREVENTION -> {
        GriefPrevention gp = mock(GriefPrevention.class);
        DataStore dataStore = mock(DataStore.class);
        Field dataStoreField = GriefPrevention.class.getDeclaredField("dataStore");
        dataStoreField.setAccessible(true);
        dataStoreField.set(gp, dataStore);
        when(dataStore.getClaims(anyInt(), anyInt())).thenAnswer(inv ->
            claimedChunk(WORLD, inv.getArgument(0), inv.getArgument(1))
                ? List.of(mock(Claim.class)) : Collections.emptyList());
        setStatic(GriefPrevention.class, "instance", gp);
        when(pluginManager.getPlugin("GriefPrevention")).thenReturn(gp);
      }
      case LANDS -> LandsIntegration.setLookup(ClaimPluginStandInLifecycleS003Test::claimedChunk);
      case RED_PROTECT -> RedProtect.setInstance(new RedProtect(new RedProtectAPI(
          loc -> claimedBlock(loc) ? new Region() : null)));
      case RESIDENCE -> Residence.setInstance(new Residence(new ResidenceManager(
          loc -> claimedBlock(loc) ? new ClaimedResidence() : null)));
      case CRASH_CLAIM -> {
        CrashClaim plugin = new CrashClaim();
        plugin.setApi(new CrashClaimAPI(
            loc -> claimedBlock(loc) ? new net.crashcraft.crashclaim.claimobjects.Claim() : null));
        CrashClaim.setPlugin(plugin);
      }
      case HUSK_CLAIMS -> BukkitHuskClaimsAPI.register(
          new BukkitHuskClaimsAPI(ClaimPluginStandInLifecycleS003Test::claimedBlock));
      case HUSK_TOWNS -> BukkitHuskTownsAPI.register(
          new BukkitHuskTownsAPI(ClaimPluginStandInLifecycleS003Test::claimedBlock));
      case PLOT_SQUARED -> com.plotsquared.core.location.Location.setResolver((w, x, y, z) ->
          new com.plotsquared.core.location.Location(false,
              WORLD.equals(w) && claimedBlock(x, z) ? new Plot(true) : null));
      case KINGDOMS_X -> Land.setLookup((w, cx, cz) ->
          claimedChunk(w, cx, cz) ? new Land(UUID.randomUUID()) : null);
      case TOWNY -> {
        TownyAPI towny = mock(TownyAPI.class);
        when(towny.isWilderness(any(Location.class)))
            .thenAnswer(inv -> !claimedBlock((Location) inv.getArgument(0)));
        setStatic(TownyAPI.class, "instance", towny);
      }
      case WORLD_GUARD -> installWorldGuard();
      case ULTIMATE_CLAIMS -> {
        UltimateClaims plugin = new UltimateClaims();
        plugin.getClaimManager().setLookup((w, cx, cz) ->
            claimedChunk(w, cx, cz) ? new com.craftaro.ultimateclaims.claim.Claim() : null);
        UltimateClaims.setInstance(plugin);
      }
      case MINE_PLOTS -> {
        PlotApi plot = mock(PlotApi.class);
        PlotServiceApi service = mock(PlotServiceApi.class);
        when(service.getPlot(any(Location.class)))
            .thenAnswer(inv -> claimedBlock((Location) inv.getArgument(0)) ? plot : null);
        when(servicesManager.load(PlotServiceApi.class)).thenReturn(service);
      }
    }
  }

  private void installWorldGuard() throws Exception {
    setStatic(WorldEditPlugin.class, "INSTANCE", mock(WorldEditPlugin.class));
    WorldGuardPlugin wgPlugin = mock(WorldGuardPlugin.class);
    when(pluginManager.getPlugin("WorldGuard")).thenReturn(wgPlugin);
    WorldGuardPlatform platform = mock(WorldGuardPlatform.class);
    RegionContainer container = mock(RegionContainer.class);
    RegionManager regions = mock(RegionManager.class);
    when(platform.getRegionContainer()).thenReturn(container);
    when(container.get(any(com.sk89q.worldedit.world.World.class))).thenReturn(regions);
    when(regions.getApplicableRegions(any(BlockVector3.class))).thenAnswer(inv -> {
      BlockVector3 pt = inv.getArgument(0);
      ApplicableRegionSet set = mock(ApplicableRegionSet.class);
      boolean claimed = claimedBlock(pt.x(), pt.z());
      when(set.size()).thenReturn(claimed ? 1 : 0);
      // No region opts in through can-rtp-select-here.
      when(set.testState(any(), any())).thenReturn(false);
      return set;
    });
    WorldGuard.getInstance().setPlatform(platform);
  }

  private static VerifierCheckResult verify(RTPCoords coords) throws Exception {
    return GlobalRegionVerifiers.checkGlobalRegionVerifiersDetailed(coords).get(5, TimeUnit.SECONDS);
  }

  private static Circle shape() {
    Circle circle = new Circle();
    circle.set(GenericMemoryShapeParams.centerRadius, 0L);
    circle.set(GenericMemoryShapeParams.radius, 500L);
    circle.set(GenericMemoryShapeParams.centerX, 0L);
    circle.set(GenericMemoryShapeParams.centerZ, 0L);
    circle.set(GenericMemoryShapeParams.expand, false);
    return circle;
  }

  /** Every stored run is a claim rejection whose expiry lies in {@code [minExpiry, maxExpiry]}. */
  private static void assertStoredRuns(Circle shape, long minExpiry, long maxExpiry) {
    byte[] causes = shape.badCausesSnapshot();
    long[] expiries = shape.badExpiriesSnapshot();
    assertTrue(causes.length > 0, "at least one stored run");
    for (int i = 0; i < causes.length; i++) {
      assertEquals((byte) FailTypes.safetyExternal.ordinal(), causes[i], "run " + i + " cause");
      assertTrue(expiries[i] >= minExpiry && expiries[i] <= maxExpiry,
          "run " + i + " expiry " + expiries[i] + " outside [" + minExpiry + ", " + maxExpiry + "]");
    }
  }

  private static long maxExpiry(Circle shape) {
    long max = 0L;
    for (long e : shape.badExpiriesSnapshot()) max = Math.max(max, e);
    return max;
  }

  private static void setStatic(Class<?> owner, String name, Object value) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(null, value);
  }
}
