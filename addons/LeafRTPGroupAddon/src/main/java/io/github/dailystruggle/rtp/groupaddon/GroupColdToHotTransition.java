package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.cache.RejectionReason;
import io.github.dailystruggle.rtp.common.selection.region.cache.StageTransition;
import io.github.dailystruggle.rtp.common.selection.region.cache.TransitionOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Asynchronous chunk reservation acquisition transition promoting cold {@link GroupSubspace}
 * to hot {@link GroupSubspace} with pinned chunk tickets (ADR-078 Phase 6, REQ-RTP-S-002, REQ-RTP-S-004, REQ-RTP-S-005).
 *
 * <p>Chunk loading is initiated non-blockingly via {@link RTPWorld#getChunkAtAsync(int, int)}.
 * If any chunk load fails or the transition encounters an error, any acquired reservations
 * are deterministically closed to prevent chunk ticket leaks (REQ-RTP-S-002).
 */
public final class GroupColdToHotTransition
    implements StageTransition<GroupSubspace, GroupSubspace> {
  private final Region region;
  private final RTPWorld<?> world;
  private final GroupProfile profile;

  public GroupColdToHotTransition(Region region, GroupProfile profile) {
    this.region = region;
    this.world =
        (region != null && region.getSettings() != null)
            ? region.getSettings().world()
            : null;
    this.profile = profile;
  }

  public GroupColdToHotTransition(RTPWorld<?> world, GroupProfile profile) {
    this.region = null;
    this.world = world;
    this.profile = profile;
  }

  public Region region() {
    return region;
  }

  public RTPWorld<?> world() {
    return world;
  }

  public GroupProfile profile() {
    return profile;
  }

  @Override
  public CompletableFuture<TransitionOutcome<GroupSubspace>> promote(GroupSubspace source) {
    if (source == null) {
      return CompletableFuture.completedFuture(
          TransitionOutcome.rejected(RejectionReason.ERROR, "source cold subspace was null"));
    }
    if (source.anchor() == null || source.anchor().coords() == null) {
      return CompletableFuture.completedFuture(
          TransitionOutcome.rejected(
              RejectionReason.OUT_OF_BOUNDS,
              "source cold subspace anchor coordinate was null"));
    }
    if (world == null) {
      return CompletableFuture.completedFuture(
          TransitionOutcome.rejected(
              RejectionReason.RESERVATION_FAILED, "world was null or unavailable"));
    }

    int anchorCX = source.anchor().coords().x() >> 4;
    int anchorCZ = source.anchor().coords().z() >> 4;
    int chunkRadius = (source.blockRadius() + 15) / 16;

    List<CompletableFuture<ChunkSet>> chunkFutures = new ArrayList<>();
    for (int cx = anchorCX - chunkRadius; cx <= anchorCX + chunkRadius; cx++) {
      for (int cz = anchorCZ - chunkRadius; cz <= anchorCZ + chunkRadius; cz++) {
        CompletableFuture<ChunkSet> f = world.getChunkAtAsync(cx, cz);
        if (f != null) {
          chunkFutures.add(f);
        }
      }
    }

    if (chunkFutures.isEmpty()) {
      return CompletableFuture.completedFuture(
          TransitionOutcome.rejected(
              RejectionReason.RESERVATION_FAILED, "no chunk futures could be created"));
    }

    return CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]))
        .thenApply(
            v -> {
              List<ChunkReservation> reservations = new ArrayList<>(chunkFutures.size());
              try {
                for (CompletableFuture<ChunkSet> f : chunkFutures) {
                  ChunkSet chunkSet = f.getNow(null);
                  if (chunkSet == null) {
                    closeAll(reservations);
                    return TransitionOutcome.<GroupSubspace>rejected(
                        RejectionReason.RESERVATION_FAILED,
                        "chunk future completed with null chunk set");
                  }
                  reservations.add(new ChunkReservation(chunkSet, world));
                }

                GroupSubspace hotSubspace =
                    new GroupSubspace(
                        source.anchor(),
                        source.blockRadius(),
                        source.slotLocations(),
                        reservations);
                return TransitionOutcome.promoted(hotSubspace);
              } catch (Throwable t) {
                closeAll(reservations);
                throw (t instanceof RuntimeException re) ? re : new RuntimeException(t);
              }
            })
        .exceptionally(
            ex -> {
              Throwable cause =
                  (ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null)
                      ? ex.getCause()
                      : ex;
              RTP.log(
                  Level.WARNING,
                  "[LeafRTPGroupAddon] cold->hot transition completed exceptionally",
                  cause);
              String detail =
                  (cause != null && cause.getMessage() != null)
                      ? cause.getMessage()
                      : "chunk load failure";
              return TransitionOutcome.rejected(RejectionReason.ERROR, detail);
            });
  }

  private static void closeAll(List<ChunkReservation> reservations) {
    if (reservations == null) return;
    for (ChunkReservation res : reservations) {
      if (res != null) {
        try {
          res.close();
        } catch (Throwable ignored) {
        }
      }
    }
  }
}
