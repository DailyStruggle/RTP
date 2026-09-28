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
   * Anchor source resolved from a stable claim or faction boundary.
   *
   * @param boundary the territory claim boundary; must not be {@code null}
   * @return anchor source bound to the claim boundary
   */
  static AnchorSource claimBoundary(io.github.dailystruggle.rtp.api.claim.ClaimBoundary boundary) {
    Objects.requireNonNull(boundary, "boundary must not be null");
    return new ClaimBoundaryAnchorSource(boundary);
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

  /**
   * Anchor source backed by a {@link io.github.dailystruggle.rtp.api.claim.ClaimBoundary}.
   */
  final class ClaimBoundaryAnchorSource implements AnchorSource {
    private final io.github.dailystruggle.rtp.api.claim.ClaimBoundary boundary;

    public ClaimBoundaryAnchorSource(io.github.dailystruggle.rtp.api.claim.ClaimBoundary boundary) {
      this.boundary = Objects.requireNonNull(boundary, "boundary must not be null");
    }

    public io.github.dailystruggle.rtp.api.claim.ClaimBoundary boundary() {
      return boundary;
    }

    @Override
    public CompletableFuture<RTPCoords> resolveAnchor(Object regionContext) {
      int[] c = boundary.centroid();
      if (c == null || c.length < 2) {
        return CompletableFuture.completedFuture(null);
      }
      return CompletableFuture.completedFuture(new RTPCoords(boundary.world(), c[0], 64, c[1]));
    }
  }
}
