package io.github.dailystruggle.rtp.common.action;

import io.github.dailystruggle.rtp.api.action.ActionContext;
import io.github.dailystruggle.rtp.api.action.ActionDefinition;
import io.github.dailystruggle.rtp.api.group.AnchorSource;
import io.github.dailystruggle.rtp.api.group.GroupPlacementRequest;
import io.github.dailystruggle.rtp.api.group.GroupProfileSpec;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.GroupPlacementDispatcher;
import io.github.dailystruggle.rtp.common.selection.region.Region;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Background recurring task that pre-warms candidate landing placements for stationary actions (ADR-097).
 *
 * <p>Scans registered actions with {@code placement.cacheSize > 0} and stationary anchor sources
 * (e.g. {@code scatter}, {@code location}, {@code nearclaim}). Dynamic entity actions
 * (e.g. {@code nearplayer}) bypass background caching and execute on demand with bounded retries.
 *
 * <p>Non-blocking and S-005 compliant: candidate generation runs off-tick via
 * {@link GroupPlacementDispatcher#preparePlacement(GroupPlacementRequest)}. Prepared candidates
 * hold live chunk tickets until claimed by a participant or released upon eviction (S-002).
 */
public final class ActionCacheWarmTask implements Runnable {

  private final ActionManager actionManager;
  private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

  public ActionCacheWarmTask(ActionManager actionManager) {
    this.actionManager = actionManager;
  }

  @Override
  public void run() {
    if (actionManager == null) return;

    for (String actionId : actionManager.getActionIds()) {
      Optional<ActionDefinition> defOpt = actionManager.getAction(actionId);
      if (defOpt.isEmpty()) continue;
      ActionDefinition def = defOpt.get();
      ActionDefinition.PlacementSpec pSpec = def.placement();

      int targetCacheSize = pSpec.cacheSize();
      if (targetCacheSize <= 0) continue;

      if (!isStationary(pSpec)) continue;

      int currentCount = actionManager.getCachedPlacementCount(actionId);
      AtomicInteger activeInFlight = inFlight.computeIfAbsent(actionId.toLowerCase(), k -> new AtomicInteger(0));
      if (currentCount + activeInFlight.get() >= targetCacheSize) continue;

      warmOne(def, activeInFlight);
    }
  }

  /**
   * Determines whether the placement specification defines a stationary anchor source.
   */
  public static boolean isStationary(ActionDefinition.PlacementSpec pSpec) {
    if (pSpec == null) return false;
    Object rawType = pSpec.parameters().get("anchor");
    String type = (rawType == null) ? "regionqueue" : rawType.toString().trim().toLowerCase();
    switch (type) {
      case "entity":
      case "player":
      case "nearplayer":
        return false;
      default:
        return true;
    }
  }

  /**
   * Resolves the target participant slot count for warming this action's cache.
   */
  public static int resolveSlotCount(ActionDefinition.PlacementSpec pSpec) {
    if (pSpec == null) return 2;
    Map<String, Object> params = pSpec.parameters();
    if (params.containsKey("slots")) {
      Object v = params.get("slots");
      if (v instanceof Number n) return Math.max(1, n.intValue());
    }
    if (params.containsKey("participants")) {
      Object v = params.get("participants");
      if (v instanceof Number n) return Math.max(1, n.intValue());
    }
    return 2;
  }

  private void warmOne(ActionDefinition def, AtomicInteger activeInFlight) {
    GroupPlacementDispatcher dispatcher = getDispatcher();
    if (dispatcher == null) return;

    activeInFlight.incrementAndGet();

    final String actionId = def.id();
    final ActionDefinition.PlacementSpec pSpec = def.placement();
    final int n = resolveSlotCount(pSpec);

    List<UUID> dummyParticipants = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      dummyParticipants.add(UUID.randomUUID());
    }

    GroupProfileSpec profile =
        GroupProfileSpec.of(
            pSpec.shapeName(),
            pSpec.radius(),
            pSpec.minSeparation(),
            pSpec.elevationTolerance(),
            n,
            pSpec.retries());

    String effectiveRegion = pSpec.region();
    if (pSpec.parameters().containsKey("memoryRegion")) {
      Object mr = pSpec.parameters().get("memoryRegion");
      if (mr != null && !mr.toString().isBlank()) effectiveRegion = mr.toString().trim();
    } else if (pSpec.parameters().containsKey("inheritRegionMemory")) {
      Object mr = pSpec.parameters().get("inheritRegionMemory");
      if (mr != null && !mr.toString().isBlank()) effectiveRegion = mr.toString().trim();
    }

    final Region parentRegion = (effectiveRegion != null && !effectiveRegion.isBlank() && RTP.selectionAPI != null)
        ? RTP.selectionAPI.getRegion(effectiveRegion)
        : null;

    AnchorSource anchorSource =
        ActionManager.resolveAnchorSource(pSpec, ActionContext.EMPTY, dummyParticipants, parentRegion);

    String targetRegion = (pSpec.region() != null && !pSpec.region().isBlank())
        ? pSpec.region()
        : ((parentRegion != null) ? parentRegion.name : null);

    GroupPlacementRequest request =
        GroupPlacementRequest.of(targetRegion, profile, dummyParticipants, anchorSource);

    dispatcher.preparePlacement(request).whenComplete((prep, ex) -> {
      try {
        if (ex != null) {
          RTP.log(Level.FINE, "[ActionCacheWarmTask] Placement preparation failed for action '" + actionId + "': " + ex.getMessage());
          return;
        }

        if (prep != null && prep.isSuccess() && prep.result().placements() != null) {
          Map<UUID, RTPLocation> slots = prep.result().placements();
          ActionManager.PrevalidatedActionPlacement placement =
              new ActionManager.PrevalidatedActionPlacement(slots, prep.anchorX(), prep.anchorZ());

          boolean offered = actionManager.offerCachedPlacement(actionId, placement);
          if (!offered) {
            placement.release();
          } else {
            RTP.log(Level.FINER, "[ActionCacheWarmTask] Pre-warmed placement cached for action '" + actionId + "', size="
                + actionManager.getCachedPlacementCount(actionId));
          }
        }
      } catch (Throwable t) {
        RTP.log(Level.WARNING, "[ActionCacheWarmTask] Error offering cached placement for action '" + actionId + "'", t);
      } finally {
        activeInFlight.decrementAndGet();
      }
    });
  }

  private GroupPlacementDispatcher getDispatcher() {
    if (RTP.groupPlacementService instanceof GroupPlacementDispatcher dispatcher) {
      return dispatcher;
    }
    return null;
  }
}
