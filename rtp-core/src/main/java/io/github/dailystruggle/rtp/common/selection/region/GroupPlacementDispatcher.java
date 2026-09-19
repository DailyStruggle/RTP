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
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

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
 * of safe standable slots for a group, then dispatches per-participant teleports.
 *
 * <p>The whole pipeline is non-blocking (S-005): the region anchor draw, every global-verifier
 * check, and each teleport hop are chained via {@link CompletableFuture} with no {@code get()}/{@code
 * join()}. Every request is answered with a {@link GroupPlacementResult}, never silently dropped
 * (S-004).
 *
 * <p><b>Pipeline.</b> Resolve the region, draw an anchor, build the {@link SubspaceShape}, select
 * safe slots via {@link Region#candidateValidator()}, run the per-slot {@link GlobalRegionVerifiers}
 * stage to produce a fully validated per-participant destination map, then dispatch teleports binned
 * by destination chunk (one region-thread task per chunk, launched concurrently and lock-free). A
 * participant offline at dispatch is logged and skipped (S-004); the returned future completes once
 * every teleport has been initiated.
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
      SubspaceShape subspace = new SubspaceShape(anchor, spec.radius(), region);
      // Stage 1: default (square) lattice; the preset-configured distribution shape is wired in a
      // later stage. Elevation tolerance is applied against the anchor Y by the selector.
      slots =
          subspace.selectSafeSlots(
              n,
              spec.minSeparation(),
              spec.elevationTolerance(),
              null,
              region.candidateValidator());
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
        .thenCompose(
            ignored -> {
              // 7. Assign surviving slots to participants in order. If any slot failed the verifier,
              // the group cannot be placed in full -> fail closed (all-or-nothing).
              // Convert each core slot to the api RTPLocation (world-bound) exposed by the result.
              Map<UUID, io.github.dailystruggle.rtp.api.world.RTPLocation> placements =
                  new LinkedHashMap<>();
              for (int i = 0; i < n; i++) {
                if (!verified[i]) {
                  releaseReservation(genResult);
                  return CompletableFuture.completedFuture(
                      GroupPlacementResult.failure(
                          GroupPlacementResult.Reason.INSUFFICIENT_SAFE_SLOTS,
                          "slot " + i + " rejected by region verifier"));
                }
                RTPCoords c = candidates.get(i).coords();
                placements.put(
                    participants.get(i),
                    new io.github.dailystruggle.rtp.api.world.RTPLocation(
                        world, c.x(), c.y(), c.z(), candidates.get(i).reservation()));
              }

              // Allocation and safety verdict are complete. The anchor reservation only pinned the
              // draw chunks; destinations are independently validated, so release it now (the
              // per-destination chunks are covered by the slot reservations carried on each
              // RTPLocation and released by the teleport path).
              releaseReservation(genResult);

              // 8. Dispatch teleports, binned by destination chunk to cut scheduling overhead.
              return dispatchTeleports(placements, world);
            });
  }

  /** A resolved, online participant paired with its destination and a per-teleport completion. */
  private static final class Member {
    final UUID uuid;
    final io.github.dailystruggle.rtp.api.entity.RTPPlayer player;
    final io.github.dailystruggle.rtp.api.world.RTPLocation dest;
    final CompletableFuture<Boolean> done = new CompletableFuture<>();

    Member(
        UUID uuid,
        io.github.dailystruggle.rtp.api.entity.RTPPlayer player,
        io.github.dailystruggle.rtp.api.world.RTPLocation dest) {
      this.uuid = uuid;
      this.player = player;
      this.dest = dest;
    }
  }

  /**
   * Dispatches teleports <em>binned by destination chunk</em> and completes once every teleport has
   * been initiated.
   *
   * <p>Rather than one scheduled hop per participant, participants sharing a destination chunk are
   * batched into a single {@link RTP#scheduler}{@code .runTask(world, cx, cz, ...)} - which lands on
   * that chunk's owning region thread on Folia and the main thread elsewhere. Because the subspace
   * footprint is a small NxN block of chunks, this collapses many per-player hops into a handful of
   * per-chunk tasks. Bins are launched together and run independently on their own region threads,
   * giving maximal concurrency with no shared locks; each teleport reports through its own future
   * (S-005, no cross-region blocking).
   *
   * <p>A participant offline at dispatch is logged (never silently dropped, S-004) and skipped; the
   * rest still teleport. If every participant is gone, the request fails closed with
   * {@link GroupPlacementResult.Reason#CANCELLED}.
   */
  private CompletableFuture<GroupPlacementResult> dispatchTeleports(
      Map<UUID, io.github.dailystruggle.rtp.api.world.RTPLocation> placements, RTPWorld<?> world) {

    // Bin online participants by destination chunk; LinkedHashMap keeps launch order deterministic.
    Map<Long, List<Member>> bins = new LinkedHashMap<>();
    List<CompletableFuture<Boolean>> teleports = new ArrayList<>(placements.size());
    for (Map.Entry<UUID, io.github.dailystruggle.rtp.api.world.RTPLocation> entry :
        placements.entrySet()) {
      final UUID uuid = entry.getKey();
      final io.github.dailystruggle.rtp.api.world.RTPLocation dest = entry.getValue();

      io.github.dailystruggle.rtp.api.entity.RTPPlayer player = null;
      try {
        player = RTP.serverAccessor.getPlayer(uuid);
      } catch (Throwable t) {
        RTP.log(Level.WARNING, "[group] failed to resolve participant " + uuid + " for dispatch", t);
      }
      if (player == null || !player.isOnline()) {
        RTP.log(
            Level.WARNING,
            "[group] participant " + uuid + " offline at dispatch; releasing slot and skipping");
        releaseSlotReservation(dest);
        continue;
      }

      long chunkKey = chunkKey(dest.getBlockX() >> 4, dest.getBlockZ() >> 4);
      Member member = new Member(uuid, player, dest);
      bins.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(member);
      teleports.add(member.done);
    }

    if (teleports.isEmpty()) {
      return CompletableFuture.completedFuture(
          GroupPlacementResult.failure(
              GroupPlacementResult.Reason.CANCELLED, "all participants offline at dispatch"));
    }

    // Launch every bin together. Each runs on its own destination-chunk region thread with no
    // shared mutable state between bins, so they proceed concurrently without locking.
    for (List<Member> bin : bins.values()) {
      final int cx = bin.get(0).dest.getBlockX() >> 4;
      final int cz = bin.get(0).dest.getBlockZ() >> 4;
      final List<Member> members = bin;
      Runnable binTask =
          () -> {
            for (Member m : members) {
              try {
                m.player
                    .setLocation(m.dest)
                    .whenComplete(
                        (ok, ex) -> {
                          if (ex != null) {
                            RTP.log(
                                Level.WARNING,
                                "[group] teleport failed for participant " + m.uuid, ex);
                            m.done.complete(false);
                          } else {
                            m.done.complete(Boolean.TRUE.equals(ok));
                          }
                        });
              } catch (Throwable t) {
                RTP.log(Level.WARNING, "[group] teleport threw for participant " + m.uuid, t);
                m.done.complete(false);
              }
            }
          };
      try {
        RTP.scheduler.runTask(world, cx, cz, binTask);
      } catch (Throwable t) {
        RTP.log(
            Level.WARNING,
            "[group] failed to schedule teleport bin at chunk (" + cx + "," + cz + ")", t);
        for (Member m : members) {
          releaseSlotReservation(m.dest);
          m.done.complete(false);
        }
      }
    }

    return CompletableFuture.allOf(teleports.toArray(new CompletableFuture[0]))
        .thenApply(ignored -> GroupPlacementResult.success(placements));
  }

  /** Packs chunk (x, z) into a single long key (x in low 32 bits, z in high 32). */
  private static long chunkKey(int cx, int cz) {
    return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
  }

  /** Best-effort release of a per-slot reservation carried on an api RTPLocation (never throws). */
  private static void releaseSlotReservation(io.github.dailystruggle.rtp.api.world.RTPLocation loc) {
    if (loc == null || loc.getReservation() == null) return;
    try {
      loc.getReservation().close();
    } catch (Throwable ignored) {
      // best-effort close
    }
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
