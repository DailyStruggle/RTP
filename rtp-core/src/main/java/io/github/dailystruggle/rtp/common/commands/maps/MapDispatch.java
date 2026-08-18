package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapBinding;
import io.github.dailystruggle.mapsapi.MapBindingLifecycle;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.noop.NoopMapBinding;
import io.github.dailystruggle.rtp.api.configuration.enums.CommandMessages;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
/**
 * Orchestrator for declarative chart composition (REQ-RTP-MAP-006, ADR-047).
 * Resolves {@link ChartSpecResolver}, allocates {@link MapHandle} from active
 * {@link MapBinding}, paints the model, and surfaces errors via configured messages.
 *
 * <p>S-004: all failures log WARNING and notify viewer. S-005: zero synchronous chunk I/O.
 * S-006: throws {@link IllegalStateException} if core is not initialized.
 */
public final class MapDispatch {

  private static final AtomicReference<MapBinding> BINDING =
      new AtomicReference<>(new NoopMapBinding());

  /**
   * Lifecycle registry (REQ-RTP-MAP-003).
   * Platform bridges (see {@code BukkitMapBindingListener} in
   * {@code rtp-bukkit-common}) call {@link #firePlayerQuit} / {@link
   * #fireDisable}; {@link #setMapBinding} additionally auto-registers
   * any installed {@link MapBindingLifecycle} so the common case
   * ("install BukkitMapBinding") needs no second call.
   */
  private static final List<MapBindingLifecycle> LIFECYCLES =
      new CopyOnWriteArrayList<>();

  /** Label used for {@link MemoryTracker} entries opened by {@link #paint}. */
  static final String MEMORY_TRACKER_LABEL = "BukkitMapBinding";

  /** Max lifespan (ms) for a {@link MemoryTracker} entry opened by {@link #paint}. */
  static final long MEMORY_TRACKER_TTL_MS = 30_000L;

  /**
   * Chart kinds that {@link #paint} drives through {@link MapBinding#bindLive}
   * (self-refreshing live binding) instead of one-shot {@link MapBinding#renderEphemeral}.
   */
  private static final java.util.EnumSet<ChartSpec.Kind> LIVE_REFRESH_KINDS =
      java.util.EnumSet.of(
          ChartSpec.Kind.METRIC_SPARKLINE,
          ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE,
          ChartSpec.Kind.REGION_BIOMES);

  private MapDispatch() {}

  /**
   * Installs the active {@link MapBinding}. Auto-registers if it implements {@link MapBindingLifecycle}.
   *
   * @param binding the new {@link MapBinding} to install; never {@code null}
   * @return the previously-installed binding; never {@code null}
   */
  public static MapBinding setMapBinding(MapBinding binding) {
    Objects.requireNonNull(binding, "binding");
    MapBinding previous = BINDING.getAndSet(binding);
    if (binding instanceof MapBindingLifecycle lifecycle) {
      registerLifecycle(lifecycle);
    }
    return previous;
  }

  /**
   * Registers a {@link MapBindingLifecycle} with the dispatch lifecycle bus. Idempotent.
   *
   * @param lifecycle the lifecycle listener to register; never {@code null}
   */
  public static void registerLifecycle(MapBindingLifecycle lifecycle) {
    Objects.requireNonNull(lifecycle, "lifecycle");
    if (!LIFECYCLES.contains(lifecycle)) {
      LIFECYCLES.add(lifecycle);
    }
  }

  /**
   * Deregisters a previously-installed {@link MapBindingLifecycle}. Safe to
   * call with a listener that was never registered.
   *
   * @param lifecycle the lifecycle listener to remove; ignored when {@code null}
   */
  public static void unregisterLifecycle(MapBindingLifecycle lifecycle) {
    if (lifecycle == null) return;
    LIFECYCLES.remove(lifecycle);
  }

  /**
   * Fan-out for the platform {@code PlayerQuitEvent} bridge. Each registered
   * {@link MapBindingLifecycle} is notified; an exception from one listener
   * is logged at WARNING and does not block notification of the others.
   *
   * @param viewer the UUID of the player who quit; ignored when {@code null}
   */
  public static void firePlayerQuit(UUID viewer) {
    if (viewer == null) return;
    for (MapBindingLifecycle lifecycle : LIFECYCLES) {
      try {
        lifecycle.onPlayerQuit(viewer);
      } catch (RuntimeException e) {
        RTP.log(Level.WARNING,
            "MapDispatch.firePlayerQuit: lifecycle " + lifecycle.getClass().getName()
                + " threw for viewer " + viewer + ": " + e.getMessage(), e);
      }
    }
  }

  /**
   * Fan-out for host-plugin {@code onDisable}. Each registered
   * {@link MapBindingLifecycle} is notified, and the registry is cleared so
   * a subsequent re-enable starts with a clean slate. An exception from one
   * listener is logged at WARNING and does not block the rest.
   */
  public static void fireDisable() {
    for (MapBindingLifecycle lifecycle : LIFECYCLES) {
      try {
        lifecycle.onDisable();
      } catch (RuntimeException e) {
        RTP.log(Level.WARNING,
            "MapDispatch.fireDisable: lifecycle " + lifecycle.getClass().getName()
                + " threw: " + e.getMessage(), e);
      }
    }
    LIFECYCLES.clear();
  }

  /**
   * Test-only accessor: the number of currently-registered lifecycle
   * listeners. Exposed for {@code MapDispatchTest} regression coverage of
   * REQ-RTP-MAP-003.
   *
   * @return the number of currently-registered lifecycle listeners
   */
  public static int registeredLifecycleCount() {
    return LIFECYCLES.size();
  }

  /**
   * Returns the active {@link MapBinding}. Never {@code null}; returns a
   * {@link NoopMapBinding} sentinel until a real binding is installed.
   *
   * @return the active {@link MapBinding}; never {@code null}
   */
  public static MapBinding getMapBinding() {
    return BINDING.get();
  }

  /**
   * Synchronous entry point: resolve and paint {@code spec} for {@code viewer}.
   * Callers on the main thread must dispatch asynchronously first.
   *
   * @param spec   the declarative chart request; never {@code null}
   * @param viewer the viewer UUID for player-facing failure messages; never {@code null}
   * @return {@code true} on successful paint; {@code false} on failure
   */
  public static boolean paint(ChartSpec spec, UUID viewer) {
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(viewer, "viewer");

    MapBinding binding = BINDING.get();
    RTP.log(Level.FINE,
        "[viz/bad-locations] MapDispatch.paint entry: kind=" + spec.kind()
            + " region=" + spec.regionName()
            + " viewer=" + viewer
            + " binding=" + (binding == null ? "null" : binding.getClass().getName()));
    if (binding instanceof NoopMapBinding) {
      RTP.log(Level.WARNING,
          "ChartSpec " + spec.kind() + " for viewer " + viewer
              + " skipped: no concrete MapBinding installed (NoopMapBinding active).");
      sendMessage(viewer, CommandMessages.mapBindingMissing);
      return false;
    }

    ChartSpecResolver resolver = ChartSpecResolvers.get(spec.kind());
    if (resolver == null) {
      RTP.log(Level.WARNING,
          "ChartSpec " + spec.kind() + " for viewer " + viewer
              + " skipped: no ChartSpecResolver registered.");
      sendMessage(viewer, CommandMessages.mapResolverMissing);
      return false;
    }

    ChartSpecResolver.Resolution resolution;
    try {
      RTP.log(Level.FINE,
          "[viz/bad-locations] resolver.resolve invoking: resolver="
              + resolver.getClass().getName());
      resolution = resolver.resolve(spec);
      RTP.log(Level.FINE,
          "[viz/bad-locations] resolver.resolve OK: renderer="
              + (resolution == null ? "null"
                  : resolution.renderer().getClass().getName())
              + " model=" + (resolution == null || resolution.model() == null
                  ? "null"
                  : resolution.model().getClass().getName()));
    } catch (ChartSpecResolver.UnresolvableChartSpecException e) {
      RTP.log(Level.WARNING,
          "ChartSpec " + spec.kind() + " for viewer " + viewer
              + " unresolvable: " + e.getMessage());
      sendMessage(viewer, CommandMessages.mapUnavailable);
      return false;
    } catch (RuntimeException e) {
      // S-004: never swallow. Defensive translation of an unexpected
      // resolver fault to the same user-facing path so the viewer is not
      // left without feedback.
      RTP.log(Level.WARNING,
          "ChartSpec " + spec.kind() + " for viewer " + viewer
              + " resolver threw: " + e.getMessage(), e);
      sendMessage(viewer, CommandMessages.mapUnavailable);
      return false;
    }

    // ChartSpec carries tilesRows/tilesCols for the Stage-2/3 mosaic path;
    // Single-tile allocation: the tile counts match the layout dimensions.
    // are recorded in spec but not forwarded to the binding yet.
    MapAllocationRequest request = new MapAllocationRequest(
        spec.kind().name(),
        viewer,
        MapAllocationRequest.Locking.LOCKED);
    MapHandle handle;
    try {
      RTP.log(Level.FINE,
          "[viz/bad-locations] binding.allocate invoking on "
              + binding.getClass().getName());
      handle = binding.allocate(request);
      RTP.log(Level.FINE,
          "[viz/bad-locations] binding.allocate OK: handle="
              + (handle == null ? "null" : handle.toString()));
    } catch (RuntimeException e) {
      RTP.log(Level.WARNING,
          "ChartSpec " + spec.kind() + " for viewer " + viewer
              + " allocate failed: " + e.getMessage(), e);
      sendMessage(viewer, CommandMessages.mapBusy);
      return false;
    }

    // REQ-RTP-MAP-003: register handle with active GC to reap leaks.
    UUID trackingId = MemoryTracker.track(handle, MEMORY_TRACKER_LABEL, MEMORY_TRACKER_TTL_MS);
    try {
      if (LIVE_REFRESH_KINDS.contains(spec.kind())) {
        // Live-refresh: re-invokes resolver per tick; faults handled inside LiveChartRenderer.
        final ChartSpecResolver resolverRef = resolver;
        RTP.log(Level.FINE,
            "[viz/live] binding.bindLive invoking for kind=" + spec.kind()
                + " viewer=" + viewer);
        binding.bindLive(handle,
            resolution.renderer(),
            () -> {
              try {
                return resolverRef.resolve(spec).model();
              } catch (ChartSpecResolver.UnresolvableChartSpecException ex) {
                return null; // skip this tick
              }
            });
        RTP.log(Level.FINE,
            "[viz/live] binding.bindLive OK for kind=" + spec.kind()
                + " viewer=" + viewer);
      } else {
        RTP.log(Level.FINE,
            "[viz/bad-locations] binding.renderEphemeral invoking for viewer="
                + viewer);
        binding.renderEphemeral(handle, resolution.renderer(), resolution.model());
        RTP.log(Level.FINE,
            "[viz/bad-locations] binding.renderEphemeral OK for viewer=" + viewer);
      }
    } catch (RuntimeException e) {
      RTP.log(Level.WARNING,
          "ChartSpec " + spec.kind() + " for viewer " + viewer
              + " render failed: " + e.getMessage(), e);
      sendMessage(viewer, CommandMessages.mapUnavailable);
      return false;
    } finally {
      MemoryTracker.untrack(trackingId);
    }

    // Delivery: drop FILLED_MAP item on viewer's owning thread (S-005/Folia region thread).
    final MapBinding deliverBinding = binding;
    final MapHandle deliverHandle = handle;
    Runnable deliver = () -> {
      try {
        RTP.log(Level.FINE,
            "[viz/bad-locations] binding.deliverTo invoking for viewer=" + viewer);
        deliverBinding.deliverTo(deliverHandle, viewer);
        RTP.log(Level.FINE,
            "[viz/bad-locations] binding.deliverTo OK for viewer=" + viewer);
      } catch (RuntimeException e) {
        RTP.log(Level.WARNING,
            "ChartSpec " + spec.kind() + " for viewer " + viewer
                + " deliverTo failed: " + e.getMessage(), e);
        sendMessage(viewer, CommandMessages.mapUnavailable);
      }
    };

    RTPLocation viewerLocation = null;
    if (RTP.serverAccessor != null) {
      try {
        RTPPlayer p = RTP.serverAccessor.getPlayer(viewer);
        if (p != null) viewerLocation = p.getLocation();
      } catch (RuntimeException e) {
        // Resolver fault: fall through to inline delivery below.
        viewerLocation = null;
      }
    }
    if (RTP.scheduler != null && viewerLocation != null) {
      RTP.scheduler.runTask(viewerLocation, deliver);
    } else {
      // Test context / pre-init / unresolvable viewer location: run inline,
      // preserving the pre-hop behaviour on non-Folia and unit-test paths.
      deliver.run();
    }
    return true;
  }

  private static void sendMessage(UUID viewer, Enum<?> key) {
    if (RTP.serverAccessor == null) return; // test contexts; warning already logged
    try {
      RTP.serverAccessor.sendMessage(viewer, key);
    } catch (RuntimeException e) {
      RTP.log(Level.WARNING,
          "MapDispatch failed to send " + key + " to " + viewer + ": " + e.getMessage());
    }
  }
}
