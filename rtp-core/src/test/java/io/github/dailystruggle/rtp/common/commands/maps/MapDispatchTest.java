package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.Cancellation;
import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapBinding;
import io.github.dailystruggle.mapsapi.MapCanvas;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.model.ChartModel;
import io.github.dailystruggle.mapsapi.noop.NoopMapBinding;
import io.github.dailystruggle.mapsapi.render.ChartRenderer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-MAP-006 / ADR-047 — {@link MapDispatch} orchestration coverage:
 *
 * <ul>
 *   <li>Happy path: real binding + healthy resolver -> {@code paint()}
 *       returns true; binding observed exactly one allocate + one
 *       renderEphemeral.</li>
 *   <li>Failure: {@link NoopMapBinding} active -> {@code paint()} returns
 *       false; no allocate observed.</li>
 *   <li>Failure: resolver throws -> {@code paint()} returns false; no
 *       allocate observed.</li>
 *   <li>Failure: binding rejects allocate -> {@code paint()} returns false;
 *       no renderEphemeral observed.</li>
 *   <li>Argument validation: null spec / null viewer -> NPE.</li>
 * </ul>
 */
@DisplayName("REQ-RTP-MAP-006 - MapDispatch orchestration contract")
class MapDispatchTest {

  @TempDir Path tempDir;

  private CountingMapBinding binding;
  private Square shape;
  private MapBinding originalBinding;

  @BeforeEach
  void setUp() throws Exception {
    RTPTestSetup.install(tempDir.toFile());
    MockRTPWorld world = new MockRTPWorld("dispatch_test_world");
    shape = new Square();
    LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
    RegionSettings settings = new RegionSettings(
        "default", world, shape, vert,
        false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
    Region region = new Region("default", settings);
    RTP.selectionAPI = new SelectionAPI();
    RTP.selectionAPI.permRegionLookup.put("default", region);
    // Seed a couple of bad keys so the resolver's happy path is reachable.
    long[] keys = new long[] {
        shape.xzToLocation(5L, 6L),
        shape.xzToLocation(-7L, 8L),
        shape.xzToLocation(9L, -10L)};
    java.util.Arrays.sort(keys);
    Field keysField = MemoryShape.class.getDeclaredField("badKeysCache");
    keysField.setAccessible(true);
    keysField.set(shape, keys);
    // Parallel-seed badPrefixSumsCache so a later flushAndRebuild (triggered
    // by a Region shutdown in another test in the same JVM) does not throw.
    long[] sums = new long[keys.length];
    for (int i = 0; i < keys.length; i++) sums[i] = i + 1L;
    Field sumsField = MemoryShape.class.getDeclaredField("badPrefixSumsCache");
    sumsField.setAccessible(true);
    sumsField.set(shape, sums);

    binding = new CountingMapBinding();
    originalBinding = MapDispatch.setMapBinding(binding);
    ChartSpecResolvers.resetForTest();
  }

  @AfterEach
  void tearDown() {
    MapDispatch.setMapBinding(originalBinding == null ? new NoopMapBinding() : originalBinding);
    ChartSpecResolvers.resetForTest();
    if (RTP.selectionAPI != null) RTP.selectionAPI.permRegionLookup.clear();
    RTP.serverAccessor = null;
    RTP.scheduler = null;
    io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = null;
  }

  @Test
  @DisplayName("Happy path: paint() returns true and drives binding exactly once")
  void happyPath() {
    boolean ok = MapDispatch.paint(
        ChartSpec.of(ChartSpec.Kind.BAD_POINTS_HEATMAP, "default"),
        UUID.randomUUID());
    assertTrue(ok, "paint shall return true on success");
    assertEquals(1, binding.allocateCalls, "allocate shall be called once");
    assertEquals(1, binding.renderCalls, "renderEphemeral shall be called once");
  }

  @Test
  @DisplayName("Failure: NoopMapBinding active -> paint() returns false, no allocate")
  void noopBinding_returnsFalse() {
    MapDispatch.setMapBinding(new NoopMapBinding());
    boolean ok = MapDispatch.paint(
        ChartSpec.of(ChartSpec.Kind.BAD_POINTS_HEATMAP, "default"),
        UUID.randomUUID());
    assertFalse(ok, "paint shall return false when NoopMapBinding is active");
  }

  @Test
  @DisplayName("Failure: resolver missing -> paint() returns false, no allocate")
  void missingResolver_returnsFalse() {
    // Override the BAD_POINTS_HEATMAP slot with a sentinel that maps to a
    // resolver that throws Unresolvable to simulate "no resolver"; or just
    // remove via reflection. Simpler: register a Kind that has no resolver
    // by clearing the registry through reset+remove.
    ChartSpecResolvers.register(ChartSpec.Kind.REGION_COVERAGE,
        spec -> { throw new AssertionError("must not be called"); });
    // Use a Kind that has no resolver in Stage 1: CACHE_OCCUPANCY.
    boolean ok = MapDispatch.paint(
        ChartSpec.of(ChartSpec.Kind.CACHE_OCCUPANCY, "default"),
        UUID.randomUUID());
    assertFalse(ok, "paint shall return false when no resolver is registered");
    assertEquals(0, binding.allocateCalls);
  }

  @Test
  @DisplayName("Failure: resolver throws Unresolvable -> paint() returns false, no allocate")
  void resolverUnresolvable_returnsFalse() {
    ChartSpecResolvers.register(ChartSpec.Kind.BAD_POINTS_HEATMAP,
        spec -> {
          throw new ChartSpecResolver.UnresolvableChartSpecException("test-injected");
        });
    boolean ok = MapDispatch.paint(
        ChartSpec.of(ChartSpec.Kind.BAD_POINTS_HEATMAP, "default"),
        UUID.randomUUID());
    assertFalse(ok);
    assertEquals(0, binding.allocateCalls, "allocate shall not be called when resolver failed");
  }

  @Test
  @DisplayName("Failure: binding allocate throws -> paint() returns false, no render")
  void allocateThrows_returnsFalse() {
    binding.failAllocate = true;
    boolean ok = MapDispatch.paint(
        ChartSpec.of(ChartSpec.Kind.BAD_POINTS_HEATMAP, "default"),
        UUID.randomUUID());
    assertFalse(ok);
    assertEquals(1, binding.allocateCalls);
    assertEquals(0, binding.renderCalls, "renderEphemeral shall not be called when allocate failed");
  }

  @Test
  @DisplayName("Argument validation: null spec / null viewer -> NullPointerException")
  void nullArgs_throw() {
    assertThrows(NullPointerException.class,
        () -> MapDispatch.paint(null, UUID.randomUUID()));
    assertThrows(NullPointerException.class,
        () -> MapDispatch.paint(ChartSpec.of(ChartSpec.Kind.BAD_POINTS_HEATMAP, "default"), null));
  }

  @Test
  @DisplayName("setMapBinding shall reject null and return prior installation")
  void setMapBinding_contract() {
    assertThrows(NullPointerException.class, () -> MapDispatch.setMapBinding(null));
    MapBinding prior = MapDispatch.setMapBinding(new NoopMapBinding());
    assertNotNull(prior);
  }

  // -----------------------------------------------------------------------
  // Local fake binding: counts allocate / renderEphemeral, supports failure
  // injection. Does NOT import org.bukkit / net.minecraft (rtp-core test
  // sources are platform-neutral).
  // -----------------------------------------------------------------------

  private static final class CountingMapBinding implements MapBinding {
    int allocateCalls = 0;
    int renderCalls = 0;
    boolean failAllocate = false;

    @Override
    public MapHandle allocate(MapAllocationRequest request) {
      allocateCalls++;
      if (failAllocate) throw new IllegalStateException("test-injected allocate failure");
      return new MapHandle(request.chartId(), request.viewer(), allocateCalls);
    }

    @Override
    public <M extends ChartModel> void renderEphemeral(MapHandle handle,
                                                       ChartRenderer<M> renderer,
                                                       M model) {
      renderCalls++;
      renderer.render(new NoopCanvas(), model);
    }

    @Override
    public <M extends ChartModel> Cancellation bindLive(MapHandle handle,
                                                        ChartRenderer<M> renderer,
                                                        Supplier<M> modelSupplier) {
      throw new UnsupportedOperationException("not under test");
    }
  }

  /** Minimal in-test canvas; commit/setPixel/etc. are no-ops. */
  private static final class NoopCanvas implements MapCanvas {
    @Override public int width() { return VANILLA_WIDTH; }
    @Override public int height() { return VANILLA_HEIGHT; }
    @Override public void setPixel(int x, int y, byte paletteIndex) {}
    @Override public void fillRect(int x0, int y0, int x1, int y1, byte paletteIndex) {}
    @Override public void drawText(int x, int y, String text, byte paletteIndex) {}
    @Override public void clear() {}
    @Override public void commit() {}
  }
}
