package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cc.javajobs.factionsbridge.FactionsBridge;
import cc.javajobs.factionsbridge.bridge.infrastructure.struct.FactionsAPI;
import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import com.craftaro.ultimateclaims.UltimateClaims;
import com.craftaro.ultimateclaims.claim.Claim;
import com.massivecraft.factions.Board;
import com.massivecraft.factions.FLocation;
import com.massivecraft.factions.Faction;
import com.plotsquared.core.plot.Plot;
import java.lang.reflect.Field;
import java.util.UUID;
import net.crashcraft.crashclaim.CrashClaim;
import net.crashcraft.crashclaim.api.CrashClaimAPI;
import net.william278.huskclaims.api.BukkitHuskClaimsAPI;
import net.william278.husktowns.api.BukkitHuskTownsAPI;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kingdoms.constants.land.Land;

/**
 * REQ-RTP-S-003 / REQ-RTP-S-004: each reflective claim checker runs against a stub of the claim
 * plugin's published API shape (same class names, plugin name and overload set) and must detect a
 * claim, pass open land, and fail closed when the plugin is enabled but its API is unusable.
 *
 * <p>Stub sources (ADR-118), read 2026-10-19:
 *
 * <ul>
 *   <li>UltimateClaims 2.4.0: github.com/Songoda-Plugins/UltimateClaimsLegacy, {@code
 *       com/craftaro/ultimateclaims/UltimateClaims.java}, {@code claim/ClaimManager.java}.
 *   <li>Residence 6.0.x: github.com/Zrips/Residence, {@code Residence.java}, {@code
 *       protection/ResidenceManager.java}.
 *   <li>CrashClaim 1.0.42: github.com/WhipDevelopment/CrashClaim, {@code CrashClaim.java}, {@code
 *       api/CrashClaimAPI.java}.
 *   <li>HuskClaims 1.5.x: github.com/WiIIiam278/HuskClaims, {@code api/HuskClaimsAPI.java}
 *       ({@code isClaimAt(Position)} and {@code isClaimAt(OnlineUser)}), {@code
 *       api/BukkitHuskClaimsAPI.java}.
 *   <li>HuskTowns 3.x: github.com/WiIIiam278/HuskTowns, {@code bukkit/.../api/BukkitHuskTownsAPI.java}.
 *   <li>PlotSquared 7: github.com/IntellectualSites/PlotSquared, {@code
 *       Core/.../location/Location.java} (eight {@code at} overloads), {@code plot/Plot.java}.
 *   <li>KingdomsX: wiki.kingdomsx.com/API ({@code Land.getLand(Location)}, {@code isClaimed()});
 *       no public source or artifact.
 *   <li>SaberFactions / FactionsUUID 1.6.x: github.com/SaberLLC/Saber-Factions and
 *       github.com/drtshock/Factions, {@code Board.java}, {@code FLocation.java}, {@code
 *       Faction.java}.
 *   <li>FactionsBridge 1.4.2: github.com/CallumJohnson/FactionsBridge, {@code FactionsBridge.java},
 *       {@code bridge/infrastructure/struct/FactionsAPI.java}, {@code Faction.java}.
 * </ul>
 */
@DisplayName("REQ-RTP-S-003: reflective claim checkers against the published claim-plugin API shapes")
class ReflectiveClaimCheckerApiShapeS003Test {

  private static final int CLAIMED_X = 100;
  private static final int OPEN_X = 300;
  private static final int Z = 200;

  private PluginManager pluginManager;
  private World world;

  @BeforeEach
  void setUp() throws Exception {
    Server server = mock(Server.class);
    pluginManager = mock(PluginManager.class);
    world = mock(World.class);
    when(world.getName()).thenReturn("world");
    when(server.getPluginManager()).thenReturn(pluginManager);
    setBukkitServer(server);
  }

  @AfterEach
  void tearDown() throws Exception {
    setBukkitServer(null);
    UltimateClaims.setInstance(null);
    Residence.setInstance(null);
    Residence.setResidenceManager(null);
    CrashClaim.setPlugin(null);
    BukkitHuskClaimsAPI.getInstance().setClaimPredicate(null);
    BukkitHuskTownsAPI.getInstance().setClaimPredicate(null);
    com.plotsquared.core.location.Location.setLayout(null);
    Land.setLookup(null);
    Board.setInstance(null);
    FactionsBridge.setFactionsAPI(null);
  }

  private Location at(int x) {
    return new Location(world, x, 64, Z);
  }

  private void enable(String pluginName) {
    when(pluginManager.isPluginEnabled(pluginName)).thenReturn(true);
  }

  private static boolean claimedChunk(String w, int cx, int cz) {
    return "world".equals(w) && cx == CLAIMED_X >> 4 && cz == Z >> 4;
  }

  private static boolean claimedBlock(Location loc) {
    return loc.getBlockX() == CLAIMED_X && loc.getBlockZ() == Z;
  }

  @Nested
  @DisplayName("UltimateClaims: getInstance().getClaimManager().getClaim(String, int, int)")
  class UltimateClaimsShape {
    private void install() {
      UltimateClaims plugin = new UltimateClaims();
      plugin.getClaimManager().setLookup((w, x, z) -> claimedChunk(w, x, z) ? new Claim() : null);
      UltimateClaims.setInstance(plugin);
    }

    @Test
    @DisplayName("claimed chunk is claimed, open chunk is not")
    void detectsClaims() {
      enable("UltimateClaims");
      install();
      assertTrue(UltimateClaimsChecker.isInClaim(at(CLAIMED_X)));
      assertFalse(UltimateClaimsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("enabled but getInstance() null fails closed")
    void missingInstanceFailsClosed() {
      enable("UltimateClaims");
      assertTrue(UltimateClaimsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("lookup error fails closed")
    void lookupErrorFailsClosed() {
      enable("UltimateClaims");
      install();
      UltimateClaims.getInstance().getClaimManager().setLookup((w, x, z) -> {
        throw new IllegalStateException("claims not loaded");
      });
      assertTrue(UltimateClaimsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() {
      install();
      assertFalse(UltimateClaimsChecker.isInClaim(at(CLAIMED_X)));
    }
  }

  @Nested
  @DisplayName("Residence: getInstance().getResidenceManager().getByLoc(Location)")
  class ResidenceShape {
    private ResidenceManager manager;

    private void install() {
      manager = mock(ResidenceManager.class);
      ClaimedResidence residence = mock(ClaimedResidence.class);
      when(manager.getByLoc(org.mockito.ArgumentMatchers.argThat(l -> l != null && claimedBlock(l))))
          .thenReturn(residence);
      Residence.setInstance(new Residence());
      Residence.setResidenceManager(manager);
    }

    @Test
    @DisplayName("claimed location is claimed, open location is not")
    void detectsClaims() {
      enable("Residence");
      install();
      assertTrue(ResidenceChecker.isInClaim(at(CLAIMED_X)));
      assertFalse(ResidenceChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("enabled but residence manager null fails closed")
    void missingManagerFailsClosed() {
      enable("Residence");
      Residence.setInstance(new Residence());
      assertTrue(ResidenceChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() {
      install();
      assertFalse(ResidenceChecker.isInClaim(at(CLAIMED_X)));
    }
  }

  @Nested
  @DisplayName("CrashClaim: getPlugin().getApi().getClaim(Location)")
  class CrashClaimShape {
    private void install() {
      CrashClaim plugin = new CrashClaim();
      plugin.setApi(new CrashClaimAPI(
          loc -> claimedBlock(loc) ? new net.crashcraft.crashclaim.claimobjects.Claim() : null));
      CrashClaim.setPlugin(plugin);
    }

    @Test
    @DisplayName("claimed location is claimed, open location is not")
    void detectsClaims() {
      enable("CrashClaim");
      install();
      assertTrue(CrashClaimChecker.isInClaim(at(CLAIMED_X)));
      assertFalse(CrashClaimChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("enabled but getApi() null fails closed")
    void missingApiFailsClosed() {
      enable("CrashClaim");
      CrashClaim.setPlugin(new CrashClaim());
      assertTrue(CrashClaimChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() {
      install();
      assertFalse(CrashClaimChecker.isInClaim(at(CLAIMED_X)));
    }
  }

  @Nested
  @DisplayName("HuskClaims: isClaimAt(Position), not isClaimAt(OnlineUser)")
  class HuskClaimsShape {
    @Test
    @DisplayName("claimed location is claimed, open location is not")
    void detectsClaims() {
      enable("HuskClaims");
      BukkitHuskClaimsAPI.getInstance().setClaimPredicate(ReflectiveClaimCheckerApiShapeS003Test::claimedBlock);
      // Repeat: overload choice must not depend on reflection order.
      for (int i = 0; i < 20; i++) {
        assertTrue(HuskClaimsChecker.isInClaim(at(CLAIMED_X)));
        assertFalse(HuskClaimsChecker.isInClaim(at(OPEN_X)));
      }
    }

    @Test
    @DisplayName("lookup error fails closed")
    void lookupErrorFailsClosed() {
      enable("HuskClaims");
      BukkitHuskClaimsAPI.getInstance().setClaimPredicate(loc -> {
        throw new IllegalStateException("claim world not loaded");
      });
      assertTrue(HuskClaimsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() {
      BukkitHuskClaimsAPI.getInstance().setClaimPredicate(loc -> true);
      assertFalse(HuskClaimsChecker.isInClaim(at(CLAIMED_X)));
    }
  }

  @Nested
  @DisplayName("HuskTowns: BukkitHuskTownsAPI.getInstance().getClaimAt(Location) -> Optional")
  class HuskTownsShape {
    @Test
    @DisplayName("claimed location is claimed, open location is not")
    void detectsClaims() {
      enable("HuskTowns");
      BukkitHuskTownsAPI.getInstance().setClaimPredicate(ReflectiveClaimCheckerApiShapeS003Test::claimedBlock);
      for (int i = 0; i < 20; i++) {
        assertTrue(HuskTownsChecker.isInClaim(at(CLAIMED_X)));
        assertFalse(HuskTownsChecker.isInClaim(at(OPEN_X)));
      }
    }

    @Test
    @DisplayName("enabled but API not registered fails closed")
    void missingInstanceFailsClosed() {
      enable("HuskTowns");
      BukkitHuskTownsAPI.getInstance().setClaimPredicate(loc -> {
        throw new IllegalStateException("NotRegisteredException");
      });
      assertTrue(HuskTownsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() {
      BukkitHuskTownsAPI.getInstance().setClaimPredicate(loc -> true);
      assertFalse(HuskTownsChecker.isInClaim(at(CLAIMED_X)));
    }
  }

  @Nested
  @DisplayName("PlotSquared: Location.at(String, int, int, int), owned plot or road")
  class PlotSquaredShape {
    private final UUID owner = UUID.randomUUID();

    private void layout(boolean ownedAtClaimed) {
      com.plotsquared.core.location.Location.setLayout((w, x, z) -> {
        if (!"world".equals(w) || x > 1000) return null; // outside the plot area
        if (x == 200) return new com.plotsquared.core.location.Location.Cell(true, null);
        if (x == CLAIMED_X && ownedAtClaimed) {
          return new com.plotsquared.core.location.Location.Cell(false, new Plot(owner));
        }
        return new com.plotsquared.core.location.Location.Cell(false, new Plot(null));
      });
    }

    @Test
    @DisplayName("owned plot and road are rejected; unowned plot and non-plot land are open")
    void detectsClaims() {
      enable("PlotSquared");
      layout(true);
      for (int i = 0; i < 20; i++) {
        assertTrue(PlotSquaredChecker.isInClaim(at(CLAIMED_X)), "owned plot");
        assertTrue(PlotSquaredChecker.isInClaim(at(200)), "plot road");
        assertFalse(PlotSquaredChecker.isInClaim(at(OPEN_X)), "unowned plot");
        assertFalse(PlotSquaredChecker.isInClaim(at(5000)), "outside plot area");
      }
    }

    @Test
    @DisplayName("lookup error fails closed")
    void lookupErrorFailsClosed() {
      enable("PlotSquared");
      com.plotsquared.core.location.Location.setLayout((w, x, z) -> {
        throw new IllegalStateException("plot area not loaded");
      });
      assertTrue(PlotSquaredChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() {
      layout(true);
      assertFalse(PlotSquaredChecker.isInClaim(at(CLAIMED_X)));
    }
  }

  @Nested
  @DisplayName("KingdomsX: Land.getLand(Location).isClaimed()")
  class KingdomsXShape {
    private void install() {
      Land.setLookup((w, x, z) -> {
        if (claimedChunk(w, x, z)) return new Land(UUID.randomUUID());
        if (x == OPEN_X >> 4) return new Land(null); // land data, no kingdom
        return null;
      });
    }

    @Test
    @DisplayName("claimed land is claimed; unclaimed land and chunks with no data are open")
    void detectsClaims() {
      enable("Kingdoms");
      install();
      for (int i = 0; i < 20; i++) {
        assertTrue(KingdomsXChecker.isInClaim(at(CLAIMED_X)));
        assertFalse(KingdomsXChecker.isInClaim(at(OPEN_X)));
        assertFalse(KingdomsXChecker.isInClaim(at(5000)));
      }
    }

    @Test
    @DisplayName("lookup error fails closed")
    void lookupErrorFailsClosed() {
      enable("Kingdoms");
      Land.setLookup((w, x, z) -> {
        throw new IllegalStateException("land data not loaded");
      });
      assertTrue(KingdomsXChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() {
      install();
      assertFalse(KingdomsXChecker.isInClaim(at(CLAIMED_X)));
    }
  }

  @Nested
  @DisplayName("SaberFactions / FactionsUUID: Board.getInstance().getFactionAt(new FLocation(Location))")
  class SaberFactionsShape {
    private void install() {
      Board.setInstance(new Board() {
        @Override
        public Faction getFactionAt(FLocation f) {
          boolean claimed = claimedChunk(f.getWorldName(), (int) f.getX(), (int) f.getZ());
          return () -> !claimed;
        }
      });
    }

    @Test
    @DisplayName("faction land is claimed, wilderness is not")
    void detectsClaims() {
      enable("Factions");
      install();
      assertTrue(SaberFactionsChecker.isInClaim(at(CLAIMED_X)));
      assertFalse(SaberFactionsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("enabled but Board.getInstance() null fails closed")
    void missingBoardFailsClosed() {
      enable("Factions");
      assertTrue(SaberFactionsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() {
      install();
      assertFalse(SaberFactionsChecker.isInClaim(at(CLAIMED_X)));
    }
  }

  @Nested
  @DisplayName("FactionsBridge: FactionsBridge.getFactionsAPI().getFactionAt(Location)")
  class FactionsBridgeShape {
    private void install() {
      FactionsBridge.setFactionsAPI(new FactionsAPI() {
        @Override
        public cc.javajobs.factionsbridge.bridge.infrastructure.struct.Faction getFactionAt(Chunk chunk) {
          throw new AssertionError("chunk lookup not expected");
        }

        @Override
        public cc.javajobs.factionsbridge.bridge.infrastructure.struct.Faction getFactionAtChunk(
            String w, int x, int z) {
          boolean claimed = claimedChunk(w, x, z);
          return () -> !claimed;
        }
      });
    }

    @Test
    @DisplayName("faction land is claimed, wilderness is not")
    void detectsClaims() {
      enable("FactionsBridge");
      install();
      assertTrue(FactionsBridgeChecker.isInClaim(at(CLAIMED_X)));
      assertFalse(FactionsBridgeChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("enabled but getFactionsAPI() null fails closed")
    void missingApiFailsClosed() {
      enable("FactionsBridge");
      assertTrue(FactionsBridgeChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() {
      install();
      assertFalse(FactionsBridgeChecker.isInClaim(at(CLAIMED_X)));
    }
  }

  private static void setBukkitServer(Server value) throws Exception {
    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, value);
  }
}
