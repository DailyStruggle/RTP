package io.github.dailystruggle.rtp.api.group;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Strategy interface for resolving the central anchor coordinate $(X_0, Z_0)$ of a
 * subspace placement.
 *
 * <p>Every subspace placement (whether a standard group RTP, a proximity placement near a player
 * or entity, or a placement near a claim perimeter) is anchored at a base coordinate. Implementations
 * resolve this coordinate asynchronously without blocking the main thread (S-005).
 */
@PublicApi
@FunctionalInterface
public interface AnchorSource {

  /**
   * Resolves the anchor coordinate for the given region context.
   *
   * @param regionContext the contextual region or region descriptor (never {@code null})
   * @return a future completing with the resolved anchor coordinate, or {@code null} / failure if unresolvable
   */
  CompletableFuture<RTPCoords> resolveAnchor(Object regionContext);

  /**
   * Default anchor source: draws a verified anchor location from the parent region's pre-warmed queue.
   */
  static AnchorSource regionQueue() {
    return RegionQueueAnchorSource.INSTANCE;
  }

  /**
   * Anchor source resolved from an explicit, fixed coordinate (e.g. minigame arena center or landmark).
   *
   * @param coords the fixed coordinates; must not be {@code null}
   */
  static AnchorSource fixed(RTPCoords coords) {
    Objects.requireNonNull(coords, "coords must not be null");
    return regionContext -> CompletableFuture.completedFuture(coords);
  }

  /**
   * Anchor source resolved dynamically from an entity or player coordinate supplier (e.g. for {@code nearplayer}).
   *
   * @param coordsSupplier supplier returning current entity coordinates; must not be {@code null}
   */
  static AnchorSource entity(Supplier<RTPCoords> coordsSupplier) {
    Objects.requireNonNull(coordsSupplier, "coordsSupplier must not be null");
    return regionContext -> {
      try {
        RTPCoords c = coordsSupplier.get();
        return CompletableFuture.completedFuture(c);
      } catch (Throwable t) {
        CompletableFuture<RTPCoords> failed = new CompletableFuture<>();
        failed.completeExceptionally(t);
        return failed;
      }
    };
  }

  /**
   * Anchor source that recalls claim hazard boundaries from spatial memory (ADR-079 / ADR-095).
   */
  static AnchorSource claimHazard() {
    return ClaimHazardAnchorSource.INSTANCE;
  }

  /**
   * Marker singleton for region queue anchor source.
   */
  enum RegionQueueAnchorSource implements AnchorSource {
    INSTANCE;

    @Override
    public CompletableFuture<RTPCoords> resolveAnchor(Object regionContext) {
      return CompletableFuture.completedFuture(null);
    }
  }

  /**
   * Marker singleton for claim hazard boundary anchor source.
   */
  enum ClaimHazardAnchorSource implements AnchorSource {
    INSTANCE;

    @Override
    public CompletableFuture<RTPCoords> resolveAnchor(Object regionContext) {
      return CompletableFuture.completedFuture(null);
    }
  }
}
