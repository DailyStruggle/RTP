package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapBinding;
import io.github.dailystruggle.mapsapi.MapBindingLifecycle;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.noop.NoopMapBinding;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Orchestrator for the ADR-047 declarative chart-composition bridge
 * (REQ-RTP-MAP-006): given a {@link ChartSpec} and a viewer UUID,
 * resolves a {@link ChartSpecResolver}, allocates a {@link MapHandle} from
 * the active {@link MapBinding}, paints the resolved model, and surfaces
 * failures through the configurable {@link MessagesKeys} family.
 *
 * <p>Pipeline (all async to the platform main thread via
 * {@link io.github.dailystruggle.rtp.api.scheduling.RTPScheduler#runTaskAsynchronously}):
 *
 * <ol>
 *   <li>If the active {@code MapBinding} is the {@link NoopMapBinding},
 *       send {@link MessagesKeys#mapBindingMissing} and log WARNING.</li>
 *   <li>Look up {@link ChartSpecResolvers#get}; on {@code null}, send
 *       {@link MessagesKeys#mapResolverMissing} and log WARNING.</li>
 *   <li>Call {@link ChartSpecResolver#resolve}; on
 *       {@link ChartSpecResolver.UnresolvableChartSpecException}, send
 *       {@link MessagesKeys#mapUnavailable} and log WARNING.</li>
 *   <li>Allocate a handle via {@link MapBinding#allocate}; on any
 *       {@link RuntimeException}, send {@link MessagesKeys#mapBusy} and log
 *       WARNING.</li>
 *   <li>Paint via {@link MapBinding#renderEphemeral}.</li>
 * </ol>
 *
 * <p>S-004: every failure exits via a WARNING-level
 * {@link RTP#log(Level, String)} entry plus a viewer-facing message.
 * S-005: the resolver path is the only thing that reads world state, and
 * resolvers contractually forbid chunk I/O / blocking futures.
 * REQ-RTP-S-006: when {@link RTP#serverAccessor} is {@code null} (require-by-contract
 * not yet installed), {@code paint} throws {@link IllegalStateException}
 * rather than silently no-opping.
 */
public final class MapDispatch {

  private static final AtomicReference<MapBinding> BINDING =
      new AtomicReference<>(new NoopMapBinding());

  /**
   * Lifecycle registry (CHECKLIST-maps-api.md Stage 2.2, REQ-RTP-MAP-003).
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

  private MapDispatch() {}

  /**
   * Installs the active {@link MapBinding}. Returns the previously-installed
   * binding (never {@code null}; defaults to a {@link NoopMapBinding}
   * sentinel before the first call). Intended for platform adapters
   * (Stage 2: {@code BukkitMapBinding}, {@code FoliaMapBinding},
   * {@code FabricMapBinding}) and addon override hooks.
   *
   * <p>If {@code binding} additionally implements {@link MapBindingLifecycle},
   * it is auto-registered with the lifecycle bus so the platform bridge
   * does not need a second call. The previously-installed binding (if any)
   * is implicitly retired: {@link #fireDisable} is <em>not</em> invoked on
   * it here because installation churn is not a server-disable event;
   * callers that want explicit teardown shall call {@link #fireDisable}
   * before installing a replacement.
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
   * Registers a {@link MapBindingLifecycle} with the dispatch lifecycle bus.
   * Idempotent: a listener already present is not re-registered. Platform
   * adapters whose {@link MapBinding} implementation also implements
   * {@link MapBindingLifecycle} are auto-registered via
   * {@link #setMapBinding}; this entry point exists for tests and for
   * standalone lifecycle observers that aren't themselves the active
   * binding.
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
   */
  public static void unregisterLifecycle(MapBindingLifecycle lifecycle) {
    if (lifecycle == null) return;
    LIFECYCLES.remove(lifecycle);
  }

  /**
   * Fan-out for the platform {@code PlayerQuitEvent} bridge. Each registered
   * {@link MapBindingLifecycle} is notified; an exception from one listener
   * is logged at WARNING and does not block notification of the others.
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
   */
  public static int registeredLifecycleCount() {
    return LIFECYCLES.size();
  }

  /**
   * Returns the active {@link MapBinding}. Never {@code null}; returns a
   * {@link NoopMapBinding} sentinel until a real binding is installed
   * (Stage 2 of CHECKLIST-maps-api.md).
   */
  public static MapBinding getMapBinding() {
    return BINDING.get();
  }

  /**
   * Synchronous entry point: resolve and paint {@code spec} for
   * {@code viewer}. Callers from the main thread shall hop to
   * {@link io.github.dailystruggle.rtp.api.scheduling.RTPScheduler#runTaskAsynchronously}
   * first; the dispatcher itself does not schedule.
   *
   * @param spec   the declarative chart request; never {@code null}
   * @param viewer the viewer UUID for player-facing failure messages; never {@code null}
   * @return {@code true} on successful paint; {@code false} if any failure
   *         path was hit (and the viewer has already been notified via
   *         {@link MessagesKeys})
   */
  public static boolean paint(ChartSpec spec, UUID viewer) {
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(viewer, "viewer");

    MapBinding binding = BINDING.get();
    RTP.log(Level.INFO,
        "[viz/bad-locations] MapDispatch.paint entry: kind=" + spec.kind()
            + " region=" + spec.regionName()
            + " viewer=" + viewer
            + " binding=" + (binding == null ? "null" : binding.getClass().getName()));
    if (binding instanceof NoopMapBinding) {
      RTP.log(Level.WARNING,
          "ChartSpec " + spec.kind() + " for viewer " + viewer
              + " skipped: no concrete MapBinding installed (NoopMapBinding active).");
      sendMessage(viewer, MessagesKeys.mapBindingMissing);
      return false;
    }

    ChartSpecResolver resolver = ChartSpecResolvers.get(spec.kind());
    if (resolver == null) {
      RTP.log(Level.WARNING,
          "ChartSpec " + spec.kind() + " for viewer " + viewer
              + " skipped: no ChartSpecResolver registered.");
      sendMessage(viewer, MessagesKeys.mapResolverMissing);
      return false;
    }

    ChartSpecResolver.Resolution resolution;
    try {
      RTP.log(Level.INFO,
          "[viz/bad-locations] resolver.resolve invoking: resolver="
              + resolver.getClass().getName());
      resolution = resolver.resolve(spec);
      RTP.log(Level.INFO,
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
      sendMessage(viewer, MessagesKeys.mapUnavailable);
      return false;
    } catch (RuntimeException e) {
      // S-004: never swallow. Defensive translation of an unexpected
      // resolver fault to the same user-facing path so the viewer is not
      // left without feedback.
      RTP.log(Level.WARNING,
          "ChartSpec " + spec.kind() + " for viewer " + viewer
              + " resolver threw: " + e.getMessage(), e);
      sendMessage(viewer, MessagesKeys.mapUnavailable);
      return false;
    }

    // ChartSpec carries tilesRows/tilesCols for the Stage-2/3 mosaic path;
    // ADR-046 Stage 1 only ships single-tile allocation, so the tile counts
    // are recorded in spec but not forwarded to the binding yet.
    MapAllocationRequest request = new MapAllocationRequest(
        spec.kind().name(),
        viewer,
        MapAllocationRequest.Locking.LOCKED);
    MapHandle handle;
    try {
      RTP.log(Level.INFO,
          "[viz/bad-locations] binding.allocate invoking on "
              + binding.getClass().getName());
      handle = binding.allocate(request);
      RTP.log(Level.INFO,
          "[viz/bad-locations] binding.allocate OK: handle="
              + (handle == null ? "null" : handle.toString()));
    } catch (RuntimeException e) {
      RTP.log(Level.WARNING,
          "ChartSpec " + spec.kind() + " for viewer " + viewer
              + " allocate failed: " + e.getMessage(), e);
      sendMessage(viewer, MessagesKeys.mapBusy);
      return false;
    }

    // CHECKLIST-maps-api.md Stage 2.2 / REQ-RTP-MAP-003: register the freshly
    // allocated handle with the active-GC safety net so a leak from a
    // renderEphemeral that never returns (platform fault, viewer disconnect
    // mid-paint, etc.) is reaped by the periodic sweep instead of pinning
    // the MapView reference forever. Untracked on every exit path below.
    UUID trackingId = MemoryTracker.track(handle, MEMORY_TRACKER_LABEL, MEMORY_TRACKER_TTL_MS);
    try {
      RTP.log(Level.INFO,
          "[viz/bad-locations] binding.renderEphemeral invoking for viewer="
              + viewer);
      binding.renderEphemeral(handle, resolution.renderer(), resolution.model());
      RTP.log(Level.INFO,
          "[viz/bad-locations] binding.renderEphemeral OK for viewer=" + viewer);
    } catch (RuntimeException e) {
      RTP.log(Level.WARNING,
          "ChartSpec " + spec.kind() + " for viewer " + viewer
              + " renderEphemeral failed: " + e.getMessage(), e);
      sendMessage(viewer, MessagesKeys.mapUnavailable);
      return false;
    } finally {
      MemoryTracker.untrack(trackingId);
    }
    return true;
  }

  private static void sendMessage(UUID viewer, MessagesKeys key) {
    if (RTP.serverAccessor == null) return; // test contexts; warning already logged
    try {
      RTP.serverAccessor.sendMessage(viewer, key);
    } catch (RuntimeException e) {
      RTP.log(Level.WARNING,
          "MapDispatch failed to send " + key + " to " + viewer + ": " + e.getMessage());
    }
  }
}
