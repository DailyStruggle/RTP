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

import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  private java.util.Random rng = null;

  public void setRng(java.util.Random rng) {
    this.rng = rng;
  }

  public static record PreparedPlacement(
      GroupPlacementResult result,
      int anchorX,
      int anchorZ,
      RTPWorld<?> world) {
    public boolean isSuccess() {
      return result != null && result.isSuccess();
    }
  }

  @Override
  public CompletableFuture<GroupPlacementResult> place(GroupPlacementRequest request) {
    return preparePlacement(request)
        .thenCompose(
            prep -> {
              if (!prep.isSuccess()) {
                return CompletableFuture.completedFuture(prep.result());
              }
              return dispatchTeleports(prep.result().placements(), prep.world());
            });
  }

  /**
   * Prepares and validates a group placement request without dispatching teleports (ADR-097).
   * Generates safe landing slots holding active chunk reservations.
   */
  public CompletableFuture<PreparedPlacement> preparePlacement(GroupPlacementRequest request) {
    if (request == null) {
      RTP.log(Level.WARNING, "[group] preparePlacement: request is null");
      return CompletableFuture.completedFuture(
          new PreparedPlacement(
              GroupPlacementResult.failure(GroupPlacementResult.Reason.ERROR, "null request"),
              0,
              0,
              null));
    }

    RTP.log(
        Level.FINE,
        "[group] preparePlacement: region='"
            + request.regionName()
            + "', participants="
            + request.participants()
            + ", profile="
            + request.profileSpec());

    // 1. Resolve the region by name if supplied.
    Region region = null;
    String regionName = request.regionName();
    if (regionName != null && !regionName.isBlank()) {
      try {
        region = RTP.selectionAPI.getRegion(regionName);
      } catch (Throwable t) {
        RTP.log(Level.WARNING, "[group] region lookup failed for '" + regionName + "'", t);
        return CompletableFuture.completedFuture(
            new PreparedPlacement(
                GroupPlacementResult.failure(
                    GroupPlacementResult.Reason.INVALID_REGION, t.getMessage()),
                0,
                0,
                null));
      }
      if (region == null) {
        return CompletableFuture.completedFuture(
            new PreparedPlacement(
                GroupPlacementResult.failure(
                    GroupPlacementResult.Reason.INVALID_REGION,
                    "unknown region '" + regionName + "'"),
                0,
                0,
                null));
      }
    }

    // 2. Static capacity gate before doing any generation work.
    final GroupProfileSpec spec = request.profileSpec();
    final List<UUID> participants = request.participants();
    final int n = participants.size();
    if (n > spec.maxGroupSize()) {
      return CompletableFuture.completedFuture(
          new PreparedPlacement(
              GroupPlacementResult.failure(
                  GroupPlacementResult.Reason.EXCEEDED_MAX_GROUP_SIZE,
                  "group size " + n + " exceeds maxGroupSize " + spec.maxGroupSize()),
              0,
              0,
              null));
    }

    final Region fRegion = region;

    // 3. Draw or resolve anchor via AnchorSource (non-blocking). Supports bounded retries (ADR-097).
    final int maxAttempts = Math.max(1, spec.retries());
    return attemptPlacement(fRegion, spec, participants, n, request.anchorSource(), 1, maxAttempts)
        .exceptionally(
            ex -> {
              RTP.log(Level.WARNING, "[group] placement failed for region '" + fRegion.name + "'", ex);
              return new PreparedPlacement(
                  GroupPlacementResult.failure(
                      GroupPlacementResult.Reason.ERROR, String.valueOf(ex)),
                  0,
                  0,
                  null);
            });
  }

  /**
   * Executes a bounded placement attempt sequence. If an attempt fails due to insufficient safe slots
   * (e.g. land encapsulated by claim verifier rejections), a fresh anchor is drawn and retried off-tick
   * up to {@code maxAttempts} times.
   */
  private CompletableFuture<PreparedPlacement> attemptPlacement(
      Region region,
      GroupProfileSpec spec,
      List<UUID> participants,
      int n,
      io.github.dailystruggle.rtp.api.group.AnchorSource anchorSource,
      int attempt,
      int maxAttempts) {
    return SubspaceAnchorResolver.resolveAnchor(region, anchorSource)
        .thenCompose(genResult -> allocate(region, spec, participants, n, genResult))
        .thenCompose(
            result -> {
              if (result.isSuccess() || attempt >= maxAttempts) {
                return CompletableFuture.completedFuture(result);
              }
              // Only retry on capacity / verifier rejection failures where new terrain could succeed
              if (result.result().reason() == GroupPlacementResult.Reason.INSUFFICIENT_SAFE_SLOTS) {
                return attemptPlacement(
                    region, spec, participants, n, anchorSource, attempt + 1, maxAttempts);
              }
              return CompletableFuture.completedFuture(result);
            });
  }

  /**
   * Resolves a distribution shape mask from the profile's distribution name and lattice units.
   */
  @SuppressWarnings("unchecked")
  private static io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> resolveShape(
      String distribution, int latticeUnits, int centerLatticeUnits) {
    if (distribution == null || distribution.isBlank() || "square".equalsIgnoreCase(distribution)) {
      return null;
    }
    String name = distribution.trim().toLowerCase(java.util.Locale.ROOT);
    try {
      io.github.dailystruggle.rtp.common.factory.Factory<io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?>> factory =
          (io.github.dailystruggle.rtp.common.factory.Factory<io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?>>)
              RTP.factoryMap.get(RTP.factoryNames.shape);
      if (factory == null || !factory.contains(name)) {
        return null;
      }
      io.github.dailystruggle.rtp.common.factory.FactoryValue<?> fVal = factory.get(name);
      if (fVal instanceof io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> shape) {
        io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> clone = shape.clone();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("radius", (long) Math.max(0, latticeUnits));
        data.put("centerRadius", (long) Math.max(0, centerLatticeUnits));
        data.put("centerX", 0L);
        data.put("centerZ", 0L);
        clone.setData(data);
        return clone;
      }
      return null;
    } catch (Throwable t) {
      return null;
    }
  }

  /**
   * Builds the subspace, selects safe slots, runs the per-slot verifier stage, and assembles the
   * per-participant destination map. Runs on the anchor-draw completion thread (off-tick).
   */
  private CompletableFuture<PreparedPlacement> allocate(
      Region region,
      GroupProfileSpec spec,
      List<UUID> participants,
      int n,
      GenerationResult genResult) {

    if (genResult == null || genResult.coords() == null) {
      String rName = (region != null) ? region.name : "unspecified";
      return CompletableFuture.completedFuture(
          new PreparedPlacement(
              GroupPlacementResult.failure(
                  GroupPlacementResult.Reason.NO_ANCHOR, "no anchor drawn for region '" + rName + "'"),
              0,
              0,
              null));
    }

    // 4. Convert the platform-agnostic RTPCoords anchor into a world-bound RTPLocation. RTPCoords is
    // keyed only by world name; SubspaceShape needs the RTPWorld platform wrapper, which the region
    // supplies. Preserve the anchor's reservation so its chunks stay resident during selection.
    final RTPCoords coords = genResult.coords();
    RTPWorld<?> resolvedWorld = null;
    if (region != null) {
      resolvedWorld = region.getWorld();
      if (resolvedWorld == null) {
        releaseReservation(genResult);
        return CompletableFuture.completedFuture(
            new PreparedPlacement(
                GroupPlacementResult.failure(
                    GroupPlacementResult.Reason.INVALID_REGION, "region '" + region.name + "' has no world"),
                coords.x(),
                coords.z(),
                null));
      }
    } else if (coords.worldName() != null && RTP.serverAccessor != null) {
      resolvedWorld = RTP.serverAccessor.getRTPWorld(coords.worldName());
    }
    final RTPWorld<?> world = resolvedWorld;
    if (world == null) {
      releaseReservation(genResult);
      String rName = (region != null) ? region.name : "unspecified";
      return CompletableFuture.completedFuture(
          new PreparedPlacement(
              GroupPlacementResult.failure(
                  GroupPlacementResult.Reason.INVALID_REGION, "region '" + rName + "' has no world"),
              coords.x(),
              coords.z(),
              null));
    }
    // Core RTPLocation is a (RTPCoords, attempts, reservation) record; SubspaceShape consumes it.
    final RTPLocation anchor = new RTPLocation(coords, genResult.attempts(), genResult.reservation());

    // 5. Build the subspace, warm surviving footprint chunks asynchronously, and select safe slots.
    final SubspaceShape subspace;
    try {
      subspace = new SubspaceShape(anchor, spec.radius(), spec.centerRadius(), region);
      if (this.rng != null) {
        subspace.setRng(this.rng);
      } else if (region != null && region.getShape() instanceof MemoryShape<?> ms && ms.getRng() != null) {
        subspace.setRng(ms.getRng());
      }
    } catch (Throwable t) {
      releaseReservation(genResult);
      String rName = (region != null) ? region.name : "unspecified";
      RTP.log(Level.WARNING, "[group] subspace construction failed for region '" + rName + "'", t);
      return CompletableFuture.completedFuture(
          new PreparedPlacement(
              GroupPlacementResult.failure(GroupPlacementResult.Reason.ERROR, String.valueOf(t)),
              coords.x(),
              coords.z(),
              world));
    }

    // Warm surviving footprint chunks non-blockingly before candidate validation (S-005).
    // Also warm a 1-chunk boundary margin so candidate columns on outer edges have their resident
    // SafetyScan neighbors. If the coarse known-bad pre-filter excluded the whole footprint (it can
    // false-exclude a valid anchor's footprint), warm the full footprint so on-demand column
    // validation is not starved of loaded chunks. Safety is still enforced per-column downstream.
    List<int[]> surviving = subspace.survivingChunks();
    if (surviving.isEmpty()) {
      surviving = new ArrayList<>();
      int cr = subspace.getChunkRadius();
      int acx = coords.x() >> 4;
      int acz = coords.z() >> 4;
      for (int cdx = -cr; cdx <= cr; cdx++) {
        for (int cdz = -cr; cdz <= cr; cdz++) {
          surviving.add(new int[] {acx + cdx, acz + cdz});
        }
      }
    }
    Set<Long> warmedKeys = new HashSet<>();
    List<CompletableFuture<?>> warmFutures = new ArrayList<>(surviving.size() * 2);
    for (int[] chunkCoords : surviving) {
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          int wx = chunkCoords[0] + dx;
          int wz = chunkCoords[1] + dz;
          long key = (((long) wx) << 32) ^ (wz & 0xffffffffL);
          if (warmedKeys.add(key)) {
            warmFutures.add(world.getChunkAt(wx, wz));
          }
        }
      }
    }

    CandidateValidator validator = (region != null && region.candidateValidator() != null)
        ? region.candidateValidator()
        : createWorldFallbackValidator(world);

    // Keep a final handle to the warmed footprint so the post-warm diagnostic can report how many of
    // those chunks are actually resident (the validator reads only resident chunks and fails closed
    // for non-resident columns - a silent-empty-pool trap when warming does not populate the cache).
    final List<int[]> warmedFootprint = surviving;

    CompletableFuture<?> allWarmed = CompletableFuture.allOf(warmFutures.toArray(new CompletableFuture[0]))
        .orTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .exceptionally(t -> null);

    return allWarmed
        .thenCompose(
            v -> {
              int residentCount = 0;
              for (int[] cc : warmedFootprint) {
                long ck = ((long) cc[0] & 0xffffffffL) | ((long) cc[1] << 32);
                if (world.getCachedChunk(ck) != null) residentCount++;
              }
              RTP.log(
                  Level.FINE,
                  "[group] chunk warming complete ("
                      + warmFutures.size()
                      + " futures, "
                      + residentCount
                      + "/"
                      + warmedFootprint.size()
                      + " footprint chunks resident). Selecting safe slots for "
                      + n
                      + " participants.");
              int latticeUnits = (subspace.getFootprintBlocks() / 2) / Math.max(1, spec.minSeparation());
              int centerLatticeUnits = spec.centerRadius() / Math.max(1, spec.minSeparation());
              io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape<?> shapeMask =
                  resolveShape(spec.distribution(), latticeUnits, centerLatticeUnits);

              // Oversample well beyond n: incomplete hazard memory means many candidates fail real
              // column/verifier validation, so pull a padded batch to absorb attrition (ADR-097).
              int targetCandidates = Math.max(n * 4, 8);
              return subspace.selectSafeSlotsAsync(
                  n,
                  targetCandidates,
                  spec.minSeparation(),
                  spec.elevationTolerance(),
                  shapeMask,
                  validator);
            })
        .thenCompose(
            slots -> {
              RTP.log(
                  Level.FINE,
                  "[group] safe slot selection returned "
                      + (slots == null ? 0 : slots.size())
                      + " slots (required "
                      + n
                      + ")");
              if (slots == null || slots.size() < n) {
                if (slots != null) {
                  for (RTPLocation loc : slots) {
                    releaseSlotReservation(loc);
                  }
                }
                releaseReservation(genResult);
                int got = (slots == null) ? 0 : slots.size();
                return CompletableFuture.completedFuture(
                    new PreparedPlacement(
                        GroupPlacementResult.failure(
                            GroupPlacementResult.Reason.INSUFFICIENT_SAFE_SLOTS,
                            "need " + n + " safe slots, found " + got),
                        coords.x(),
                        coords.z(),
                        world));
              }

              // 6. Per-slot async global-verifier (claim/S-003) stage across all returned candidates
              final List<RTPLocation> candidates = new ArrayList<>(slots);
              final int candidateSize = candidates.size();
              final boolean[] verified = new boolean[candidateSize];
              final List<CompletableFuture<Void>> stages = new ArrayList<>(candidateSize);
              for (int i = 0; i < candidateSize; i++) {
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
                        // 7. Build the verified pool (attrition-absorbed), then select n by minimum
                        // separation over real, validated locations. Separation is applied here -
                        // never baked into candidate enumeration - because the final layout depends
                        // on which candidates actually validate/verify (ADR-097).
                        List<RTPLocation> verifiedPool = new ArrayList<>(candidateSize);
                        List<RTPLocation> unassignedOrRejected = new ArrayList<>(candidateSize);

                        for (int i = 0; i < candidateSize; i++) {
                          RTPLocation cand = candidates.get(i);
                          if (verified[i]) {
                            verifiedPool.add(cand);
                          } else {
                            if (region.getShape() instanceof MemoryShape<?> memShape) {
                              RTPCoords rejected = cand.coords();
                              int cx = rejected.x() >> 4;
                              int cz = rejected.z() >> 4;
                              long loc = memShape.xzToLocation(cx, cz);
                              memShape.addBadChunk(loc, LocationGenerator.FailTypes.safetyExternal);
                            }
                            unassignedOrRejected.add(cand);
                          }
                        }

                        List<RTPLocation> accepted =
                            SubspaceShape.selectBySeparation(verifiedPool, n, spec.minSeparation());
                        // Any verified candidate not chosen (excess or too close) is released (S-002).
                        for (RTPLocation cand : verifiedPool) {
                          if (!accepted.contains(cand)) {
                            unassignedOrRejected.add(cand);
                          }
                        }

                        // Clean up reservations on any candidate not assigned to a participant (S-002)
                        for (RTPLocation excess : unassignedOrRejected) {
                          releaseSlotReservation(excess);
                        }

                        if (accepted.size() < n) {
                          for (RTPLocation acc : accepted) {
                            releaseSlotReservation(acc);
                          }
                          releaseReservation(genResult);
                          return CompletableFuture.completedFuture(
                              new PreparedPlacement(
                                  GroupPlacementResult.failure(
                                      GroupPlacementResult.Reason.INSUFFICIENT_SAFE_SLOTS,
                                      "need " + n + " separated safe slots, only " + accepted.size()
                                          + " of " + verifiedPool.size() + " verified survived separation"),
                                  coords.x(),
                                  coords.z(),
                                  world));
                        }

                        // Assign accepted slots to participants in order.
                        Map<UUID, io.github.dailystruggle.rtp.api.world.RTPLocation> placements =
                            new LinkedHashMap<>();
                        for (int i = 0; i < n; i++) {
                          RTPLocation chosen = accepted.get(i);
                          RTPCoords c = chosen.coords();
                          placements.put(
                              participants.get(i),
                              new io.github.dailystruggle.rtp.api.world.RTPLocation(
                                  world, c.x(), c.y(), c.z(), chosen.reservation()));
                        }

                        releaseReservation(genResult);

                        return CompletableFuture.completedFuture(
                            new PreparedPlacement(
                                GroupPlacementResult.success(placements),
                                coords.x(),
                                coords.z(),
                                world));
                      });
            })
        .exceptionally(
            t -> {
              releaseReservation(genResult);
              RTP.log(Level.WARNING, "[group] subspace selection failed for region '" + region.name + "'", t);
              return new PreparedPlacement(
                  GroupPlacementResult.failure(GroupPlacementResult.Reason.ERROR, String.valueOf(t)),
                  coords.x(),
                  coords.z(),
                  world);
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

    RTP.log(
        Level.FINE,
        "[group] dispatching teleports for "
            + placements.size()
            + " participants across "
            + bins.size()
            + " destination chunk bins in world '"
            + (world != null ? world.name() : "null")
            + "'");

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

  /** Best-effort release of a per-slot reservation carried on a common RTPLocation (never throws). */
  private static void releaseSlotReservation(RTPLocation loc) {
    if (loc == null || loc.reservation() == null) return;
    try {
      loc.reservation().close();
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
  public static CandidateValidator createWorldFallbackValidator(RTPWorld<?> world) {
    return new CandidateValidator() {
      @Override
      public RTPLocation validate(int worldX, int worldZ) {
        if (world == null) return null;
        int cx = worldX >> 4;
        int cz = worldZ >> 4;
        long chunkKey = ((long) cx & 0xffffffffL) | ((long) cz << 32);
        io.github.dailystruggle.rtp.api.world.RTPChunk<?> chunk = world.getCachedChunk(chunkKey);
        if (chunk == null) return null;
        int lx = worldX & 15;
        int lz = worldZ & 15;
        io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor adjustor =
            new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor(java.util.Collections.emptyList());
        RTPCoords resolved = adjustor.adjustColumn(chunk, lx, lz);
        if (resolved == null) return null;
        return new RTPLocation(resolved, 1L, null);
      }

      @Override
      public CompletableFuture<RTPLocation> validateAsync(int worldX, int worldZ) {
        if (world == null) return CompletableFuture.completedFuture(null);
        int cx = worldX >> 4;
        int cz = worldZ >> 4;
        return world.getChunkAt(cx, cz)
            .thenApply(ch -> validate(worldX, worldZ))
            .exceptionally(ex -> null);
      }
    };
  }
}
