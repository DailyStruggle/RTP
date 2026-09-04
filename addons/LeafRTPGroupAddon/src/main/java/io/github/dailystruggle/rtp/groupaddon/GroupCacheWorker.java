package io.github.dailystruggle.rtp.groupaddon;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.selection.region.CandidateValidator;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.cache.RejectionReason;
import io.github.dailystruggle.rtp.common.selection.region.cache.StageTransition;
import io.github.dailystruggle.rtp.common.selection.region.cache.TransitionOutcome;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
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
    CandidateValidator v = (validator != null) ? validator : region.candidateValidator();
    pulse(
        region,
        profile,
        v,
        createBacklogToColdTransition(region, profile, v),
        createColdToHotTransition(region, profile));
  }

  /**
   * Executes a single off-tick pulse cycle for a region and profile using specified
   * candidate validator and stage transitions.
   *
   * @param region target region
   * @param profile target group profile
   * @param validator candidate validator
   * @param backlogTransition transition driving backlog -> cold screening
   * @param coldToHotTransition transition driving cold -> hot chunk reservation acquisition
   */
  public void pulse(
      Region region,
      GroupProfile profile,
      CandidateValidator validator,
      StageTransition<GroupBacklogEntry, GroupSubspace> backlogTransition,
      StageTransition<GroupSubspace, GroupSubspace> coldToHotTransition) {
    if (region == null || profile == null) return;
    String profileKey = region.name + ":" + profile.name();

    // 1. Backlog Refill
    while (cache.sizeBacklog(profileKey) < cache.getBacklogCap()) {
      fillBacklog(region, profile, profileKey);
    }

    // 2. Backlog -> Cold Promotion (Off-tick screening via StageTransition)
    if (cache.sizeCold(profileKey) < cache.getColdCap() && backlogTransition != null) {
      promoteBacklogToCold(profileKey, backlogTransition);
    }

    // 3. Cold -> Hot Promotion (Async chunk reservation via StageTransition)
    if (cache.sizeHot(profileKey) < cache.getHotCap() && coldToHotTransition != null) {
      promoteColdToHot(profileKey, coldToHotTransition);
    }
  }

  /**
   * Creates a {@link StageTransition} for screening unverified backlog candidates into cold subspaces.
   */
  public StageTransition<GroupBacklogEntry, GroupSubspace> createBacklogToColdTransition(
      Region region, GroupProfile profile, CandidateValidator validator) {
    return new GroupBacklogToColdTransition(region, profile, validator);
  }

  /**
   * Creates a {@link StageTransition} for acquiring async chunk reservations to promote cold subspaces to hot.
   */
  public StageTransition<GroupSubspace, GroupSubspace> createColdToHotTransition(
      Region region, GroupProfile profile) {
    return new GroupColdToHotTransition(region, profile);
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
      String profileKey, StageTransition<GroupBacklogEntry, GroupSubspace> transition) {
    GroupBacklogEntry entry = cache.pollBacklog(profileKey);
    if (entry == null) return;

    CompletableFuture<TransitionOutcome<GroupSubspace>> future = transition.promote(entry);
    if (future == null) return;

    future
        .thenAccept(
            outcome -> {
              if (outcome != null && outcome.isPromoted()) {
                GroupSubspace coldSubspace = outcome.value().orElse(null);
                if (coldSubspace != null) {
                  cache.offerCold(profileKey, coldSubspace);
                }
              } else if (outcome instanceof TransitionOutcome.Rejected<GroupSubspace> rejected) {
                RTP.log(
                    Level.FINER,
                    "[LeafRTPGroupAddon] backlog->cold promotion rejected: reason="
                        + rejected.reason()
                        + ", detail="
                        + rejected.detail());
              }
            })
        .exceptionally(
            ex -> {
              RTP.log(
                  Level.WARNING,
                  "[LeafRTPGroupAddon] unhandled error in backlog->cold promotion callback",
                  ex);
              return null;
            });
  }

  private void promoteColdToHot(
      String profileKey, StageTransition<GroupSubspace, GroupSubspace> transition) {
    GroupSubspace cold = cache.pollCold(profileKey);
    if (cold == null) return;

    CompletableFuture<TransitionOutcome<GroupSubspace>> future = transition.promote(cold);
    if (future == null) {
      cache.offerCold(profileKey, cold);
      return;
    }

    future
        .thenAccept(
            outcome -> {
              if (outcome != null && outcome.isPromoted()) {
                GroupSubspace hotSubspace = outcome.value().orElse(null);
                if (hotSubspace != null) {
                  if (!cache.offerHot(profileKey, hotSubspace)) {
                    // Hot stage overflow: offerHot triggers onDispose (GroupSubspace::close), releasing tickets (S-002)
                  }
                }
              } else if (outcome instanceof TransitionOutcome.Rejected<GroupSubspace> rejected) {
                RTP.log(
                    Level.FINE,
                    "[LeafRTPGroupAddon] cold->hot promotion rejected: reason="
                        + rejected.reason()
                        + ", detail="
                        + rejected.detail());
                // Return bare coordinates to cold stage on temporary reservation failures (ADR-078)
                if (rejected.reason() == RejectionReason.RESERVATION_FAILED) {
                  cache.offerCold(profileKey, cold);
                }
              }
            })
        .exceptionally(
            ex -> {
              RTP.log(
                  Level.WARNING,
                  "[LeafRTPGroupAddon] unhandled error in cold->hot promotion callback",
                  ex);
              cache.offerCold(profileKey, cold);
              return null;
            });
  }
}
