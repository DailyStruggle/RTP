package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.group.GroupPlacementRequest;
import io.github.dailystruggle.rtp.api.group.GroupPlacementResult;
import io.github.dailystruggle.rtp.api.group.GroupPlacementService;
import io.github.dailystruggle.rtp.api.group.GroupProfileSpec;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SubspaceShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Core implementation of {@link GroupPlacementService}: allocates and validates a localized subspace
 * of safe standable slots for a group, then (in a later phase) dispatches per-participant teleports.
 *
 * <p>The whole pipeline is non-blocking (S-005): the region anchor draw and every global-verifier
 * check are chained via {@link CompletableFuture} with no {@code get()}/{@code join()}. Every request
 * is answered with a {@link GroupPlacementResult}, never silently dropped (S-004).
 *
 * <p><b>Current scope.</b> This pass resolves the region, draws an anchor, builds the
 * {@link SubspaceShape}, selects safe slots via {@link Region#candidateValidator()}, and runs the
 * per-slot {@link GlobalRegionVerifiers} stage - producing a fully validated per-participant
 * destination map. It intentionally <em>stops before moving any player</em>: the per-participant
 * Folia region-scheduler dispatch and reservation hand-off are handled separately.
 *
 * <p><b>Footprint residency (fail-closed).</b> {@code selectSafeSlots} validates through
 * {@link Region#candidateValidator()}, which reads only <em>resident</em> chunks and fails closed for
 * any non-resident column. This pass does not warm the full NxN footprint off-tick; it relies on the
 * chunks kept loaded by the anchor's reservation and lets non-resident slots fail closed. A large
 * {@code subspaceChunkRadius} may therefore under-fill and return {@link GroupPlacementResult.Reason#INSUFFICIENT_SAFE_SLOTS};
 * explicit off-tick footprint warming is a planned follow-up, not a correctness gap.
 */
public final class GroupPlacementDispatcher implements GroupPlacementService {

  @Override
  public CompletableFuture<GroupPlacementResult> place(GroupPlacementRequest request) {
    if (request == null) {
      return CompletableFuture.completedFuture(
          GroupPlacementResult.failure(GroupPlacementResult.Reason.ERROR, "null request"));
    }

    // 1. Resolve the region by name (fail closed if core is not ready or the name is unknown).
    Region region;
    try {
      region = RTP.selectionAPI.getRegion(request.regionName());
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[group] region lookup failed for '" + request.regionName() + "'", t);
      return CompletableFuture.completedFuture(
          GroupPlacementResult.failure(GroupPlacementResult.Reason.INVALID_REGION, t.getMessage()));
    }
    if (region == null) {
      return CompletableFuture.completedFuture(
          GroupPlacementResult.failure(
              GroupPlacementResult.Reason.INVALID_REGION,
              "unknown region '" + request.regionName() + "'"));
    }

    // 2. Static capacity gate before doing any generation work.
    final GroupProfileSpec spec = request.profileSpec();
    final List<UUID> participants = request.participants();
    final int n = participants.size();
    if (n > spec.maxGroupSize()) {
      return CompletableFuture.completedFuture(
          GroupPlacementResult.failure(
              GroupPlacementResult.Reason.EXCEEDED_MAX_GROUP_SIZE,
              "group size " + n + " exceeds maxGroupSize " + spec.maxGroupSize()));
    }

    final Region fRegion = region;

    // 3. Draw a verified anchor from the region (non-blocking). Empty biome set = any biome.
    return fRegion
        .getLocation(Collections.emptySet())
        .thenCompose(genResult -> allocate(fRegion, spec, participants, n, genResult))
        .exceptionally(
            ex -> {
              RTP.log(Level.WARNING, "[group] placement failed for region '" + fRegion.name + "'", ex);
              return GroupPlacementResult.failure(GroupPlacementResult.Reason.ERROR, String.valueOf(ex));
            });
  }

  /**
   * Builds the subspace, selects safe slots, runs the per-slot verifier stage, and assembles the
   * per-participant destination map. Runs on the anchor-draw completion thread (off-tick).
   */
  private CompletableFuture<GroupPlacementResult> allocate(
      Region region,
      GroupProfileSpec spec,
      List<UUID> participants,
      int n,
      GenerationResult genResult) {

    if (genResult == null || genResult.coords() == null) {
      return CompletableFuture.completedFuture(
          GroupPlacementResult.failure(
              GroupPlacementResult.Reason.NO_ANCHOR, "no anchor drawn for region '" + region.name + "'"));
    }

    // 4. Convert the platform-agnostic RTPCoords anchor into a world-bound RTPLocation. RTPCoords is
    // keyed only by world name; SubspaceShape needs the RTPWorld platform wrapper, which the region
    // supplies. Preserve the anchor's reservation so its chunks stay resident during selection.
    final RTPWorld<?> world = region.getWorld();
    if (world == null) {
      releaseReservation(genResult);
      return CompletableFuture.completedFuture(
          GroupPlacementResult.failure(
              GroupPlacementResult.Reason.INVALID_REGION, "region '" + region.name + "' has no world"));
    }
    // Core RTPLocation is a (RTPCoords, attempts, reservation) record; SubspaceShape consumes it.
    final RTPCoords coords = genResult.coords();
    final RTPLocation anchor = new RTPLocation(coords, genResult.attempts(), genResult.reservation());

    // 5. Build the subspace and select safe slots (fail-closed capacity check, S-004).
    final List<RTPLocation> slots;
    try {
      SubspaceShape subspace = new SubspaceShape(anchor, spec.subspaceChunkRadius(), region);
      slots = subspace.selectSafeSlots(n, spec.minSeparation(), region.candidateValidator());
    } catch (Throwable t) {
      releaseReservation(genResult);
      RTP.log(Level.WARNING, "[group] subspace selection failed for region '" + region.name + "'", t);
      return CompletableFuture.completedFuture(
          GroupPlacementResult.failure(GroupPlacementResult.Reason.ERROR, String.valueOf(t)));
    }
    if (slots == null || slots.size() < n) {
      releaseReservation(genResult);
      int got = (slots == null) ? 0 : slots.size();
      return CompletableFuture.completedFuture(
          GroupPlacementResult.failure(
              GroupPlacementResult.Reason.INSUFFICIENT_SAFE_SLOTS,
              "need " + n + " safe slots, found " + got));
    }

    // 6. Per-slot async global-verifier (claim/S-003) stage, mirroring QueueTask.runSafetyScan's tail.
    // Each check is non-blocking; a throwing or false verifier drops that slot (fail-closed).
    final List<RTPLocation> candidates = new ArrayList<>(slots.subList(0, n));
    final boolean[] verified = new boolean[n];
    final List<CompletableFuture<Void>> stages = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      final int idx = i;
      final RTPCoords slotCoords = candidates.get(i).coords();
      stages.add(
          GlobalRegionVerifiers.checkGlobalRegionVerifiers(slotCoords)
              .handle(
                  (ok, ex) -> {
                    verified[idx] = (ex == null) && Boolean.TRUE.equals(ok);
                    return null;
                  }));
    }

    return CompletableFuture.allOf(stages.toArray(new CompletableFuture[0]))
        .thenApply(
            ignored -> {
              // 7. Assign surviving slots to participants in order. If any slot failed the verifier,
              // the group cannot be placed in full -> fail closed (all-or-nothing).
              // Convert each core slot to the api RTPLocation (world-bound) exposed by the result.
              Map<UUID, io.github.dailystruggle.rtp.api.world.RTPLocation> placements =
                  new LinkedHashMap<>();
              for (int i = 0; i < n; i++) {
                if (!verified[i]) {
                  releaseReservation(genResult);
                  return GroupPlacementResult.failure(
                      GroupPlacementResult.Reason.INSUFFICIENT_SAFE_SLOTS,
                      "slot " + i + " rejected by region verifier");
                }
                RTPCoords c = candidates.get(i).coords();
                placements.put(
                    participants.get(i),
                    new io.github.dailystruggle.rtp.api.world.RTPLocation(
                        world, c.x(), c.y(), c.z(), candidates.get(i).reservation()));
              }

              // Allocation and safety verdict are complete; no player has been moved. The
              // per-participant Folia region-scheduler dispatch and reservation hand-off are the
              // next phase. Release the anchor reservation here so this validation-only pass does
              // not leak the pinned chunk ticket; the dispatch phase will instead hold/transfer it
              // through teleport.
              releaseReservation(genResult);
              return GroupPlacementResult.success(placements);
            });
  }

  /** Best-effort release of the anchor's chunk reservation (fail-closed, never throws). */
  private static void releaseReservation(GenerationResult genResult) {
    if (genResult == null || genResult.reservation() == null) return;
    try {
      genResult.reservation().close();
    } catch (Throwable ignored) {
      // best-effort close
    }
  }
}
