package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pl.minecodes.plots.api.plot.PlotApi;
import pl.minecodes.plots.api.plot.PlotServiceApi;

/**
 * REQ-RTP-S-003 regression: {@link MinePlotsChecker} runs against the published minePlots
 * {@code plugin-api} ({@code PlotServiceApi} from the ServicesManager, {@code getPlot(Location)}).
 *
 * <p>Earlier versions guessed class names, then picked the zero-arg {@code getPlots()} (a
 * {@code List}) as a "plot manager" and returned false for every location.
 */
class MinePlotsCheckerS003Test {

  private static final PlotApi PLOT = mock(PlotApi.class);

  private Server server;
  private PluginManager pluginManager;
  private ServicesManager servicesManager;
  private World world;

  @BeforeEach
  void setUp() throws Exception {
    server = mock(Server.class);
    pluginManager = mock(PluginManager.class);
    servicesManager = mock(ServicesManager.class);
    world = mock(World.class);
    when(world.getName()).thenReturn("world");
    when(server.getPluginManager()).thenReturn(pluginManager);
    when(server.getServicesManager()).thenReturn(servicesManager);
    when(pluginManager.isPluginEnabled("minePlots")).thenReturn(true);
    setBukkitServer(server);
  }

  @AfterEach
  void tearDown() throws Exception {
    setBukkitServer(null);
  }

  @Test
  @DisplayName("REQ-RTP-S-003: location inside a minePlots plot is reported as claimed")
  void plotAtLocationIsClaimed() {
    registerService(loc -> loc.getBlockX() == 100 ? PLOT : null);
    assertTrue(MinePlotsChecker.isInClaim(new Location(world, 100, 64, 200)));
  }

  @Test
  @DisplayName("REQ-RTP-S-003: location outside every minePlots plot is not claimed")
  void noPlotAtLocationIsNotClaimed() {
    registerService(loc -> loc.getBlockX() == 100 ? PLOT : null);
    assertFalse(MinePlotsChecker.isInClaim(new Location(world, 300, 64, 200)));
  }

  @Test
  @DisplayName("REQ-RTP-S-003: minePlots enabled but PlotServiceApi unregistered fails closed")
  void missingServiceFailsClosed() {
    when(servicesManager.load(PlotServiceApi.class)).thenReturn(null);
    assertTrue(MinePlotsChecker.isInClaim(new Location(world, 300, 64, 200)));
  }

  @Test
  @DisplayName("REQ-RTP-S-003: getPlot throwing fails closed")
  void lookupErrorFailsClosed() {
    registerService(loc -> {
      throw new IllegalStateException("Service unavailable");
    });
    assertTrue(MinePlotsChecker.isInClaim(new Location(world, 300, 64, 200)));
  }

  @Test
  @DisplayName("REQ-RTP-S-003: minePlots disabled fails open")
  void disabledPluginFailsOpen() {
    when(pluginManager.isPluginEnabled("minePlots")).thenReturn(false);
    registerService(loc -> PLOT);
    assertFalse(MinePlotsChecker.isInClaim(new Location(world, 100, 64, 200)));
  }

  private void registerService(Function<Location, PlotApi> plotAt) {
    PlotServiceApi service = mock(PlotServiceApi.class);
    when(service.getPlot(any(Location.class)))
        .thenAnswer(inv -> plotAt.apply(inv.getArgument(0)));
    when(service.getPlots()).thenReturn(List.of(PLOT));
    when(servicesManager.load(PlotServiceApi.class)).thenReturn(service);
  }

  private static void setBukkitServer(Server value) throws Exception {
    Field serverField = Bukkit.class.getDeclaredField("server");
    serverField.setAccessible(true);
    serverField.set(null, value);
  }
}
