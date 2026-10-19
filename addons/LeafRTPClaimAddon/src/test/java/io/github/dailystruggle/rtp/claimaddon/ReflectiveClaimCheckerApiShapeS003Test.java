package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cc.javajobs.factionsbridge.FactionsBridge;
import cc.javajobs.factionsbridge.bridge.infrastructure.struct.FactionsAPI;
import com.craftaro.ultimateclaims.UltimateClaims;
import com.craftaro.ultimateclaims.claim.Claim;
import java.lang.reflect.Field;
import java.util.UUID;
import net.crashcraft.crashclaim.CrashClaim;
import net.crashcraft.crashclaim.api.CrashClaimAPI;
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
 * Checkers whose plugin publishes an API artifact are covered by
 * {@link DirectApiClaimCheckersS003Test} instead (ADR-118 rule 1).
 *
 * <p>Stub sources (ADR-118 rule 2), read 2026-10-19:
 *
 * <ul>
 *   <li>UltimateClaims 2.4.0: github.com/Songoda-Plugins/UltimateClaimsLegacy, {@code
 *       com/craftaro/ultimateclaims/UltimateClaims.java}, {@code claim/ClaimManager.java}.
 *   <li>CrashClaim 1.0.42: github.com/WhipDevelopment/CrashClaim, {@code CrashClaim.java}, {@code
 *       api/CrashClaimAPI.java}.
 *   <li>KingdomsX: wiki.kingdomsx.com/API ({@code Land.getLand(Location)}, {@code isClaimed()});
 *       no public source or artifact.
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
    CrashClaim.setPlugin(null);
    Land.setLookup(null);
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
