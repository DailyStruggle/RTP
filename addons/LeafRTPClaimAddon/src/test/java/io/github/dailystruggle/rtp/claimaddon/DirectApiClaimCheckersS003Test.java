package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.bekvon.bukkit.residence.Residence;
import com.bekvon.bukkit.residence.protection.ClaimedResidence;
import com.bekvon.bukkit.residence.protection.ResidenceManager;
import com.massivecraft.factions.Board;
import com.massivecraft.factions.FLocation;
import com.massivecraft.factions.Faction;
import com.plotsquared.core.plot.Plot;
import java.lang.reflect.Field;
import java.util.Optional;
import net.william278.huskclaims.api.BukkitHuskClaimsAPI;
import net.william278.huskclaims.api.HuskClaimsAPI;
import net.william278.huskclaims.position.Position;
import net.william278.husktowns.api.BukkitHuskTownsAPI;
import net.william278.husktowns.api.HuskTownsAPI;
import net.william278.husktowns.claim.TownClaim;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * REQ-RTP-S-003 / REQ-RTP-S-004: claim checkers compiled against the plugin's published API
 * artifact (ADR-118 rule 1) detect a claim, pass open land, fail closed when the plugin is enabled
 * but its API is unregistered or throws, and fail open when the plugin is disabled.
 *
 * <p>The API types are the real classes from the artifacts in {@code build.gradle}; only their
 * singletons are replaced. Each singleton is installed through the field the plugin's own
 * {@code getInstance()} reads, so the checker runs the plugin's real lookup path.
 */
@DisplayName("REQ-RTP-S-003: claim checkers against the published claim-plugin APIs")
class DirectApiClaimCheckersS003Test {

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
    setStatic(Bukkit.class, "server", server);
  }

  @AfterEach
  void tearDown() throws Exception {
    setStatic(Bukkit.class, "server", null);
    setStatic(HuskClaimsAPI.class, "instance", null);
    setStatic(HuskTownsAPI.class, "instance", null);
    setStatic(Residence.class, "instance", null);
    setStatic(Board.class, "instance", null);
  }

  private Location at(int x) {
    return new Location(world, x, 64, Z);
  }

  private void enable(String pluginName) {
    when(pluginManager.isPluginEnabled(pluginName)).thenReturn(true);
  }

  private static boolean claimedBlock(Location loc) {
    return loc != null && loc.getBlockX() == CLAIMED_X && loc.getBlockZ() == Z;
  }

  @Nested
  @DisplayName("HuskClaims: BukkitHuskClaimsAPI.getInstance().isClaimAt(getPosition(Location))")
  class HuskClaims {
    private BukkitHuskClaimsAPI install() throws Exception {
      BukkitHuskClaimsAPI api = mock(BukkitHuskClaimsAPI.class);
      Position claimed = mock(Position.class);
      Position open = mock(Position.class);
      when(api.getPosition(any(Location.class)))
          .thenAnswer(inv -> claimedBlock(inv.getArgument(0)) ? claimed : open);
      when(api.isClaimAt(claimed)).thenReturn(true);
      when(api.isClaimAt(open)).thenReturn(false);
      setStatic(HuskClaimsAPI.class, "instance", api);
      return api;
    }

    @Test
    @DisplayName("claimed location is claimed, open location is not")
    void detectsClaims() throws Exception {
      enable(HuskClaimsChecker.PLUGIN_NAME);
      install();
      assertTrue(HuskClaimsChecker.isInClaim(at(CLAIMED_X)));
      assertFalse(HuskClaimsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("enabled but API not registered fails closed")
    void notRegisteredFailsClosed() {
      enable(HuskClaimsChecker.PLUGIN_NAME);
      assertTrue(HuskClaimsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("lookup error fails closed")
    void lookupErrorFailsClosed() throws Exception {
      enable(HuskClaimsChecker.PLUGIN_NAME);
      BukkitHuskClaimsAPI api = install();
      when(api.getPosition(any(Location.class)))
          .thenThrow(new IllegalStateException("claim world not loaded"));
      assertTrue(HuskClaimsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() throws Exception {
      install();
      assertFalse(HuskClaimsChecker.isInClaim(at(CLAIMED_X)));
    }
  }

  @Nested
  @DisplayName("HuskTowns: BukkitHuskTownsAPI.getInstance().getClaimAt(Location) -> Optional")
  class HuskTowns {
    private BukkitHuskTownsAPI install() throws Exception {
      BukkitHuskTownsAPI api = mock(BukkitHuskTownsAPI.class);
      TownClaim claim = mock(TownClaim.class);
      when(api.getClaimAt(any(Location.class)))
          .thenAnswer(inv -> claimedBlock(inv.getArgument(0)) ? Optional.of(claim) : Optional.empty());
      setStatic(HuskTownsAPI.class, "instance", api);
      return api;
    }

    @Test
    @DisplayName("claimed location is claimed, open location is not")
    void detectsClaims() throws Exception {
      enable(HuskTownsChecker.PLUGIN_NAME);
      install();
      assertTrue(HuskTownsChecker.isInClaim(at(CLAIMED_X)));
      assertFalse(HuskTownsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("enabled but API not registered fails closed")
    void notRegisteredFailsClosed() {
      enable(HuskTownsChecker.PLUGIN_NAME);
      assertTrue(HuskTownsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("lookup error fails closed")
    void lookupErrorFailsClosed() throws Exception {
      enable(HuskTownsChecker.PLUGIN_NAME);
      BukkitHuskTownsAPI api = install();
      when(api.getClaimAt(any(Location.class))).thenThrow(new IllegalStateException("no world"));
      assertTrue(HuskTownsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() throws Exception {
      install();
      assertFalse(HuskTownsChecker.isInClaim(at(CLAIMED_X)));
    }
  }

  @Nested
  @DisplayName("PlotSquared: Location.at(String, int, int, int), owned plot or road")
  class PlotSquared {
    private com.plotsquared.core.location.Location cell(boolean road, Plot plot) {
      com.plotsquared.core.location.Location loc = mock(com.plotsquared.core.location.Location.class);
      when(loc.isPlotRoad()).thenReturn(road);
      when(loc.getPlot()).thenReturn(plot);
      return loc;
    }

    private Plot plot(boolean owned) {
      Plot plot = mock(Plot.class);
      when(plot.hasOwner()).thenReturn(owned);
      return plot;
    }

    private void layout(MockedStatic<com.plotsquared.core.location.Location> at) {
      com.plotsquared.core.location.Location owned = cell(false, plot(true));
      com.plotsquared.core.location.Location road = cell(true, null);
      com.plotsquared.core.location.Location unowned = cell(false, plot(false));
      com.plotsquared.core.location.Location outside = cell(false, null);
      at.when(() -> com.plotsquared.core.location.Location.at(anyString(), anyInt(), anyInt(), anyInt()))
          .thenAnswer(inv -> {
            int x = inv.getArgument(1);
            if (x == CLAIMED_X) return owned;
            if (x == 200) return road;
            if (x == OPEN_X) return unowned;
            return outside;
          });
    }

    @Test
    @DisplayName("owned plot and road are rejected; unowned plot and non-plot land are open")
    void detectsClaims() {
      enable(PlotSquaredChecker.PLUGIN_NAME);
      try (MockedStatic<com.plotsquared.core.location.Location> at =
          mockStatic(com.plotsquared.core.location.Location.class)) {
        layout(at);
        assertTrue(PlotSquaredChecker.isInClaim(at(CLAIMED_X)), "owned plot");
        assertTrue(PlotSquaredChecker.isInClaim(at(200)), "plot road");
        assertFalse(PlotSquaredChecker.isInClaim(at(OPEN_X)), "unowned plot");
        assertFalse(PlotSquaredChecker.isInClaim(at(5000)), "outside plot area");
      }
    }

    @Test
    @DisplayName("lookup error fails closed")
    void lookupErrorFailsClosed() {
      enable(PlotSquaredChecker.PLUGIN_NAME);
      try (MockedStatic<com.plotsquared.core.location.Location> at =
          mockStatic(com.plotsquared.core.location.Location.class)) {
        at.when(() -> com.plotsquared.core.location.Location.at(anyString(), anyInt(), anyInt(), anyInt()))
            .thenThrow(new IllegalStateException("plot area not loaded"));
        assertTrue(PlotSquaredChecker.isInClaim(at(OPEN_X)));
      }
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() {
      try (MockedStatic<com.plotsquared.core.location.Location> at =
          mockStatic(com.plotsquared.core.location.Location.class)) {
        layout(at);
        assertFalse(PlotSquaredChecker.isInClaim(at(CLAIMED_X)));
      }
    }
  }

  @Nested
  @DisplayName("Residence: getInstance().getResidenceManager().getByLoc(Location)")
  class ResidenceShape {
    private ResidenceManager install() throws Exception {
      ResidenceManager manager = mock(ResidenceManager.class);
      ClaimedResidence residence = mock(ClaimedResidence.class);
      when(manager.getByLoc(any(Location.class)))
          .thenAnswer(inv -> claimedBlock(inv.getArgument(0)) ? residence : null);
      Residence plugin = mock(Residence.class);
      when(plugin.getResidenceManager()).thenReturn(manager);
      setStatic(Residence.class, "instance", plugin);
      return manager;
    }

    @Test
    @DisplayName("claimed location is claimed, open location is not")
    void detectsClaims() throws Exception {
      enable(ResidenceChecker.PLUGIN_NAME);
      install();
      assertTrue(ResidenceChecker.isInClaim(at(CLAIMED_X)));
      assertFalse(ResidenceChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("enabled but getInstance() null fails closed")
    void missingInstanceFailsClosed() {
      enable(ResidenceChecker.PLUGIN_NAME);
      assertTrue(ResidenceChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("enabled but residence manager null fails closed")
    void missingManagerFailsClosed() throws Exception {
      enable(ResidenceChecker.PLUGIN_NAME);
      setStatic(Residence.class, "instance", mock(Residence.class));
      assertTrue(ResidenceChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() throws Exception {
      install();
      assertFalse(ResidenceChecker.isInClaim(at(CLAIMED_X)));
    }
  }

  @Nested
  @DisplayName("SaberFactions: Board.getInstance().getFactionAt(new FLocation(Location))")
  class SaberFactions {
    private void install() throws Exception {
      Board board = mock(Board.class);
      Faction claimed = mock(Faction.class);
      Faction wilderness = mock(Faction.class);
      when(claimed.isWilderness()).thenReturn(false);
      when(wilderness.isWilderness()).thenReturn(true);
      when(board.getFactionAt(any(FLocation.class))).thenAnswer(inv -> {
        FLocation f = inv.getArgument(0);
        boolean inClaim = "world".equals(f.getWorldName())
            && f.getX() == CLAIMED_X >> 4
            && f.getZ() == Z >> 4;
        return inClaim ? claimed : wilderness;
      });
      setStatic(Board.class, "instance", board);
    }

    @Test
    @DisplayName("faction land is claimed, wilderness is not")
    void detectsClaims() throws Exception {
      enable(SaberFactionsChecker.PLUGIN_NAME);
      install();
      assertTrue(SaberFactionsChecker.isInClaim(at(CLAIMED_X)));
      assertFalse(SaberFactionsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("enabled but Board.getInstance() null fails closed")
    void missingBoardFailsClosed() {
      enable(SaberFactionsChecker.PLUGIN_NAME);
      assertTrue(SaberFactionsChecker.isInClaim(at(OPEN_X)));
    }

    @Test
    @DisplayName("plugin disabled fails open")
    void disabledFailsOpen() throws Exception {
      install();
      assertFalse(SaberFactionsChecker.isInClaim(at(CLAIMED_X)));
    }
  }

  static void setStatic(Class<?> owner, String name, Object value) throws Exception {
    Field field = owner.getDeclaredField(name);
    field.setAccessible(true);
    field.set(null, value);
  }
}
