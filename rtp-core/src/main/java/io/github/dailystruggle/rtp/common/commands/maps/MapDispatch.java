package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapBinding;
import io.github.dailystruggle.mapsapi.MapBindingLifecycle;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.noop.NoopMapBinding;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
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

  /**
   * Chart kinds that {@link #paint} drives through {@link MapBinding#bindLive}
   * (a self-refreshing live binding) instead of a one-shot
   * {@link MapBinding#renderEphemeral}. Every other kind renders once.
   *
   * <ul>
   *   <li>{@link ChartSpec.Kind#METRIC_SPARKLINE}: MSPT / heap evolve over
   *       time, so the chart is only useful when it refreshes.</li>
   *   <li>{@link ChartSpec.Kind#REGION_BAD_LOCATIONS_SHAPE}: a running
   *       world-scan ({@code ScanTask}) flags new unsafe sectors into the
   *       region's {@code MemoryShape} bad-keys cache; binding live lets an
   *       admin watch the bad-locations map fill in as the scan progresses
   *       (ROADMAP Tier 2: world-scan UX). The resolver is pure and
   *       re-runnable per tick (no chunk I/O; REQ-RTP-S-005).</li>
   *   <li>{@link ChartSpec.Kind#REGION_BIOMES}: a running world-scan
   *       ({@code ScanTask}) records newly-sampled biomes into the
   *       region's {@code MemoryShape} saved biome-location cache; binding
   *       live lets an admin watch the biome map fill in as the scan
   *       progresses. {@code RegionBiomesResolver} is pure and re-runnable
   *       per tick (no chunk I/O; it reads only the in-memory, persisted
   *       biome cache via {@code biomeAt()}; REQ-RTP-S-005).</li>
   * </ul>
   */
  private static final java.util.EnumSet<ChartSpec.Kind> LIVE_REFRESH_KINDS =
      java.util.EnumSet.of(
          ChartSpec.Kind.METRIC_SPARKLINE,
          ChartSpec.Kind.REGION_BAD_LOCATIONS_SHAPE,
          ChartSpec.Kind.REGION_BIOMES);

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
   * Registers a {@link MapBindingLifecycle} with the dispatch lifecycle bus.
   * Idempotent: a listener already present is not re-registered. Platform
   * adapters whose {@link MapBinding} implementation also implements
   * {@link MapBindingLifecycle} are auto-registered via
   * {@link #setMapBinding}; this entry point exists for tests and for
   * standalone lifecycle observers that aren't themselves the active
   * binding.
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
    RTP.log(Level.FINE,
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
      if (LIVE_REFRESH_KINDS.contains(spec.kind())) {
        // Live-refresh path: the chart pulls a fresh model from the
        // resolver on every CraftMapView tick (~1 Hz while held), so the
        // viewer sees the underlying state evolve in real time. For
        // METRIC_SPARKLINE this surfaces MSPT + heap; for
        // REGION_BAD_LOCATIONS_SHAPE it surfaces newly-flagged unsafe
        // sectors as a world-scan (ScanTask) records them into the
        // region's MemoryShape bad-keys cache (ROADMAP Tier 2: world-scan
        // UX). The Supplier re-invokes resolver.resolve(spec); any
        // UnresolvableChartSpec / runtime fault inside the supplier is
        // caught by LiveChartRenderer and logged without crashing the
        // tick. Initial Resolution is discarded (its model is a stale
        // snapshot by the next tick anyway); the supplier is the source
        // of truth from here on.
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
      sendMessage(viewer, MessagesKeys.mapUnavailable);
      return false;
    } finally {
      MemoryTracker.untrack(trackingId);
    }

    // Delivery: a freshly-rendered MapView is invisible to the client unless
    // a FILLED_MAP item referencing its id reaches the viewer. The binding
    // drops a FILLED_MAP item entity at the viewer's feet, which mutates
    // world state and must therefore run on the thread that owns the
    // viewer's location. We hop there via RTP.scheduler.runTask(location,..)
    // rather than letting each platform binding reimplement the hop: on
    // Paper/Spigot the scheduler runs the task inline (main thread); on
    // Folia it dispatches onto the region thread that owns the viewer's
    // chunk (a foreign region thread would otherwise throw, surfacing as an
    // NPE in ServerLevel.getCurrentWorldData()). S-004: delivery faults exit
    // through the same WARNING + mapUnavailable message path as
    // renderEphemeral, evaluated inside the scheduled runnable since the
    // hop may run after this method returns.
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
        sendMessage(viewer, MessagesKeys.mapUnavailable);
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
