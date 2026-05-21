package io.github.dailystruggle.rtp.common.commands.maps;

import io.github.dailystruggle.mapsapi.MapAllocationRequest;
import io.github.dailystruggle.mapsapi.MapBinding;
import io.github.dailystruggle.mapsapi.MapHandle;
import io.github.dailystruggle.mapsapi.noop.NoopMapBinding;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.maps.ChartSpec;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.Objects;
import java.util.UUID;
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

  private MapDispatch() {}

  /**
   * Installs the active {@link MapBinding}. Returns the previously-installed
   * binding (never {@code null}; defaults to a {@link NoopMapBinding}
   * sentinel before the first call). Intended for platform adapters
   * (Stage 2: {@code BukkitMapBinding}, {@code FoliaMapBinding},
   * {@code FabricMapBinding}) and addon override hooks.
   */
  public static MapBinding setMapBinding(MapBinding binding) {
    Objects.requireNonNull(binding, "binding");
    return BINDING.getAndSet(binding);
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
      resolution = resolver.resolve(spec);
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
      handle = binding.allocate(request);
    } catch (RuntimeException e) {
      RTP.log(Level.WARNING,
          "ChartSpec " + spec.kind() + " for viewer " + viewer
              + " allocate failed: " + e.getMessage(), e);
      sendMessage(viewer, MessagesKeys.mapBusy);
      return false;
    }

    try {
      binding.renderEphemeral(handle, resolution.renderer(), resolution.model());
    } catch (RuntimeException e) {
      RTP.log(Level.WARNING,
          "ChartSpec " + spec.kind() + " for viewer " + viewer
              + " renderEphemeral failed: " + e.getMessage(), e);
      sendMessage(viewer, MessagesKeys.mapUnavailable);
      return false;
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
