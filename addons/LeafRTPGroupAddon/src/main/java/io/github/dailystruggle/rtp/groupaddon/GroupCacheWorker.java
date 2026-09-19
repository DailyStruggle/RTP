package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.selection.region.CandidateValidator;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Asynchronous background worker maintaining the 3-tiered group cache pipeline:
 * <ul>
 *   <li><b>Backlog Refill:</b> Generates candidate anchor points from region shapes into the backlog.</li>
 *   <li><b>Backlog -> Cold Promotion:</b> Off-tick screening via {@link CandidateValidator} / Anvil pre-filter.</li>
 *   <li><b>Cold -> Hot Promotion:</b> Asynchronous chunk reservation acquisition for full subspace footprints.</li>
 * </ul>
 */
public final class GroupCacheWorker implements Runnable {
  private final GroupSubspaceCache cache;

  public GroupCacheWorker(GroupSubspaceCache cache) {
    this.cache = Objects.requireNonNull(cache, "cache cannot be null");
  }

  public GroupSubspaceCache cache() {
    return cache;
  }

  @Override
  public void run() {
    try {
      if (RTP.selectionAPI == null || RTP.configs == null) return;
      @SuppressWarnings("unchecked")
      MultiConfigParser<GroupKeys> parser =
          (MultiConfigParser<GroupKeys>) RTP.configs.multiConfigParserMap.get(GroupKeys.class);
      if (parser == null) return;

      Map<String, ConfigParser<GroupKeys>> parserMap = parser.configParserFactory.map;
      if (parserMap == null || parserMap.isEmpty()) return;

      for (Region region : RTP.selectionAPI.permRegionLookup.values()) {
        if (region == null) continue;
        for (Map.Entry<String, ConfigParser<GroupKeys>> entry : parserMap.entrySet()) {
          if (entry.getValue() == null) continue;
          GroupProfile profile = GroupProfile.fromConfig(entry.getKey(), entry.getValue());
          if (profile != null) {
            pulse(region, profile);
          }
        }
      }
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[LeafRTPGroupAddon] error during group cache pulse", t);
    }
  }

  /**
   * Executes a single off-tick pulse cycle for a region and profile using the region's
   * candidate validator.
   *
   * @param region target region
   * @param profile target group profile
   */
  public void pulse(Region region, GroupProfile profile) {
    pulse(region, profile, (region != null) ? region.candidateValidator() : null);
  }

  /**
   * Executes a single off-tick pulse cycle for a region and profile using a specified
   * candidate validator.
   *
   * @param region target region
   * @param profile target group profile
   * @param validator candidate validator (falls back to region candidate validator if null)
   */
  public void pulse(Region region, GroupProfile profile, CandidateValidator validator) {
    if (region == null || profile == null) return;
    String profileKey = region.name + ":" + profile.name();
    CandidateValidator v = (validator != null) ? validator : region.candidateValidator();

    // 1. Backlog Refill
    while (cache.sizeBacklog(profileKey) < cache.getBacklogCap()) {
      fillBacklog(region, profile, profileKey);
    }

    // 2. Backlog -> Cold Promotion (Off-tick screening)
    if (cache.sizeCold(profileKey) < cache.getColdCap()) {
      promoteBacklogToCold(region, profile, profileKey, v);
    }

    // 3. Cold -> Hot Promotion (Async chunk reservation)
    if (cache.sizeHot(profileKey) < cache.getHotCap()) {
      promoteColdToHot(region, profile, profileKey);
    }
  }

  private void fillBacklog(Region region, GroupProfile profile, String profileKey) {
    int[] xz = (region.getShape() != null) ? region.getShape().select() : new int[] {0, 0};
    if (xz == null || xz.length < 2) return;

    RTPWorld<?> world = (region.getSettings() != null) ? region.getSettings().world() : null;
    String worldName = (world != null) ? world.name() : "world";
    RTPCoords anchorCoords = new RTPCoords(worldName, xz[0], 64, xz[1]);

    GroupBacklogEntry entry =
        new GroupBacklogEntry(
            anchorCoords,
            profile.radiusBlocks(),
            profile,
            Collections.singletonList(anchorCoords));
    cache.offerBacklog(profileKey, entry);
  }

  private void promoteBacklogToCold(
      Region region, GroupProfile profile, String profileKey, CandidateValidator validator) {
    GroupBacklogEntry entry = cache.pollBacklog(profileKey);
    if (entry == null) return;

    RTPLocation anchor = new RTPLocation(entry.anchor(), 1);
    CandidateValidator v = (validator != null) ? validator : region.candidateValidator();
    SubspaceAllocationResult result =
        GroupPlacementEngine.allocate(anchor, region, profile, profile.maxGroupSize(), v);

    if (result.isSuccess() && !result.destinations().isEmpty()) {
      entry.setValidity(GroupBacklogEntry.Validity.VALIDATED);
      GroupSubspace coldSubspace =
          new GroupSubspace(
              anchor,
              profile.radiusBlocks(),
              result.destinations(),
              Collections.emptyList());
      cache.offerCold(profileKey, coldSubspace);
    } else {
      entry.setValidity(GroupBacklogEntry.Validity.INVALIDATED);
    }
  }

  private void promoteColdToHot(Region region, GroupProfile profile, String profileKey) {
    GroupSubspace cold = cache.pollCold(profileKey);
    if (cold == null) return;

    RTPWorld<?> world = (region.getSettings() != null) ? region.getSettings().world() : null;
    if (world == null) {
      // Re-offer back to cold if world is unavailable
      cache.offerCold(profileKey, cold);
      return;
    }

    int anchorCX = cold.anchor().coords().x() >> 4;
    int anchorCZ = cold.anchor().coords().z() >> 4;
    int chunkRadius = (cold.blockRadius() + 15) / 16;

    Set<CompletableFuture<ChunkSet>> chunkFutures = new HashSet<>();
    for (int cx = anchorCX - chunkRadius; cx <= anchorCX + chunkRadius; cx++) {
      for (int cz = anchorCZ - chunkRadius; cz <= anchorCZ + chunkRadius; cz++) {
        chunkFutures.add(world.getChunkAtAsync(cx, cz));
      }
    }

    CompletableFuture.allOf(chunkFutures.toArray(new CompletableFuture[0]))
        .thenAcceptAsync(
            v -> {
              List<ChunkReservation> reservations = new ArrayList<>();
              for (CompletableFuture<ChunkSet> f : chunkFutures) {
                ChunkSet chunkSet = f.getNow(null);
                if (chunkSet != null) {
                  ChunkReservation reservation = new ChunkReservation(chunkSet, world);
                  reservations.add(reservation);
                }
              }

              GroupSubspace hotSubspace =
                  new GroupSubspace(
                      cold.anchor(),
                      cold.blockRadius(),
                      cold.slotLocations(),
                      reservations);

              if (!cache.offerHot(profileKey, hotSubspace)) {
                // If the hot stage is full, reservations are closed by offerHot
              }
            })
        .exceptionally(
            ex -> {
              RTP.log(Level.FINE, "[LeafRTPGroupAddon] failed to promote subspace to hot queue", ex);
              return null;
            });
  }
}
