package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.CandidateValidator;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A localized relative spatial subspace bounded by an {@code NxN} chunk footprint around an anchor
 * {@code (X0, Z0)} that inherits chunk-granularity spatial memory from a parent {@link Region} /
 * {@link MemoryShape}.
 *
 * <p><b>Units contract (why two stages).</b> The parent {@link MemoryShape} records bad-location
 * data at <em>chunk</em> granularity (one 1D index per 16x16 chunk; see {@code addBadChunk} /
 * {@code chunkToLocations}). Player placement, minimum separation, and elevation tolerance are all
 * expressed in <em>blocks</em>. Counting not-known-bad chunk bits is therefore <em>not</em> a count
 * of standable player slots - it only says which chunks are worth examining. To keep the units
 * honest, selection is split into two stages:
 *
 * <ol>
 *   <li><b>Stage 1 - chunk pre-filter (O(footprint), chunk units).</b> The anchor block coordinate
 *       is reduced to a chunk coordinate ({@code >> 4}). Chunks in the {@code NxN} footprint that
 *       are known bad in the inherited {@link MemoryShape} are discarded. This is a <em>necessary,
 *       not sufficient</em> screen: an unmarked chunk is "not known bad" (unexplored), never
 *       "verified good".</li>
 *   <li><b>Stage 2 - block bin (bounded, block units).</b> Block candidates are generated inside the
 *       surviving chunks and screened by a {@link BlockValidator} that resolves a real standable
 *       {@code Y} per column (mirroring the L3 {@code BacklogLocationBuffer} bin-screening model).
 *       The number of {@code VALIDATED} block candidates is the true slot count; capacity denial and
 *       separation are enforced against these, not against chunk bits.</li>
 * </ol>
 *
 * <p><b>Invariant (Capacity Denial):</b> When selecting destinations for {@code N} participants, if
 * the bin holds fewer validated, sufficiently separated candidates than {@code N}, the selection is
 * denied fail-closed (S-004 audited) rather than stacking players or relaxing safety (S-001).
 */
public class SubspaceShape {

  /** Blocks per chunk edge (Minecraft chunk = 16x16 columns). */
  private static final int CHUNK_SIZE = 16;

  /**
   * Resolves whether a world column {@code (worldX, worldZ)} holds a standable landing block and, if
   * so, at what {@code Y}.
   *
   * <p>This is now primarily a <b>test seam</b>: it lets unit tests inject a deterministic
   * Y-resolver without a live world. Production callers should use the shared
   * {@link io.github.dailystruggle.rtp.common.selection.region.CandidateValidator} overload of
   * {@link #selectSafeSlots(int, int,
   * io.github.dailystruggle.rtp.common.selection.region.CandidateValidator)} (obtained via
   * {@code Region.candidateValidator()}), which chains the region's real vertical resolver, the
   * shared {@code SafetyScan} block-clearance verdict, and claim/global checks - rather than
   * re-deriving safety here. Implementations must never load chunks synchronously on the main
   * thread (S-005).
   */
  @FunctionalInterface
  public interface BlockValidator {
    /** Sentinel {@code Y} meaning "no standable block in this column". */
    int INVALID = Integer.MIN_VALUE;

    /**
     * @param worldX absolute world block X
     * @param worldZ absolute world block Z
     * @return the standable block {@code Y}, or {@link #INVALID} if the column has no safe landing
     */
    int standableY(int worldX, int worldZ);
  }

  private final RTPLocation anchor;
  private final int anchorX;
  private final int anchorZ;
  private final int anchorCX;
  private final int anchorCZ;
  private final int chunkRadius;
  private final Region parentRegion;
  private final MemoryShape<?> parentShape;

  /**
   * Constructs a new SubspaceShape bounded to an {@code NxN} chunk footprint around an anchor.
   *
   * @param anchor the central anchor location (never {@code null})
   * @param chunkRadius footprint half-width in chunks; the footprint spans
   *     {@code (2 * chunkRadius + 1)^2} chunks (e.g. {@code 1} = 3x3 chunks = 48x48 blocks). Must be
   *     {@code >= 0}.
   * @param parentRegion the owning parent Region (never {@code null})
   */
  public SubspaceShape(RTPLocation anchor, int chunkRadius, Region parentRegion) {
    this.anchor = Objects.requireNonNull(anchor, "anchor cannot be null");
    if (chunkRadius < 0) {
      throw new IllegalArgumentException("Subspace chunkRadius must be >= 0, got: " + chunkRadius);
    }
    this.anchorX = anchor.coords().x();
    this.anchorZ = anchor.coords().z();
    this.anchorCX = anchorX >> 4;
    this.anchorCZ = anchorZ >> 4;
    this.chunkRadius = chunkRadius;
    this.parentRegion = Objects.requireNonNull(parentRegion, "parentRegion cannot be null");
    if (parentRegion.getShape() instanceof MemoryShape<?> memShape) {
      this.parentShape = memShape;
    } else {
      this.parentShape = null;
    }
  }

  public RTPLocation getAnchor() {
    return anchor;
  }

  /** @return footprint half-width in chunks. */
  public int getChunkRadius() {
    return chunkRadius;
  }

  /** @return footprint edge length in blocks ({@code (2 * chunkRadius + 1) * 16}). */
  public int getFootprintBlocks() {
    return (2 * chunkRadius + 1) * CHUNK_SIZE;
  }

  public Region getParentRegion() {
    return parentRegion;
  }

  public MemoryShape<?> getParentShape() {
    return parentShape;
  }

  /** Projects a relative block offset {@code dx} to a global world X. */
  public int projectX(int dx) {
    return anchorX + dx;
  }

  /** Projects a relative block offset {@code dz} to a global world Z. */
  public int projectZ(int dz) {
    return anchorZ + dz;
  }

  /**
   * Stage 1: whether the chunk at chunk-offset {@code (cdx, cdz)} from the anchor chunk is known bad
   * in the inherited parent spatial memory. Coordinates are chunk units, not blocks.
   *
   * @param cdx chunk-X offset from the anchor's chunk
   * @param cdz chunk-Z offset from the anchor's chunk
   * @return true if that chunk is known bad in the parent {@link MemoryShape}
   */
  public boolean isChunkKnownBad(int cdx, int cdz) {
    if (parentShape == null) return false;
    return parentShape.isKnownBad(anchorCX + cdx, anchorCZ + cdz);
  }

  /**
   * Stage 1 helper: chunks in the footprint that are not known bad, as {@code {cx, cz}} chunk
   * coordinates. This is the surviving set worth block-screening; it is a coarse pre-filter, never a
   * safety guarantee.
   *
   * @return surviving chunk coordinates (world chunk units); never {@code null}
   */
  public List<int[]> survivingChunks() {
    List<int[]> out = new ArrayList<>();
    for (int cdx = -chunkRadius; cdx <= chunkRadius; cdx++) {
      for (int cdz = -chunkRadius; cdz <= chunkRadius; cdz++) {
        if (!isChunkKnownBad(cdx, cdz)) {
          out.add(new int[] {anchorCX + cdx, anchorCZ + cdz});
        }
      }
    }
    return out;
  }

  /**
   * Two-stage selection of safe landing slots for {@code memberCount} participants.
   *
   * <p>Stage 1 discards known-bad chunks; Stage 2 screens block columns inside the survivors with
   * {@code validator}, binning those with a standable {@code Y} and enforcing block-unit separation.
   * If fewer than {@code memberCount} sufficiently separated validated slots exist, an empty list is
   * returned (fail-closed capacity denial).
   *
   * <p>The Stage 2 sampling stride is derived from {@code minSeparation} rather than being a separate
   * knob: sampling coarser than the separation you enforce is wasteful, and sampling finer than it is
   * pointless. A slight oversampling ({@code minSeparation / 2}) gives the greedy separation pass
   * enough distinct columns to actually reach {@code memberCount} in jagged terrain where some grid
   * cells have no standable {@code Y}.
   *
   * @param memberCount number of required landing positions
   * @param minSeparation minimum block clearance between any two placed points; also drives the
   *     internal Stage 2 sampling stride
   * @param validator block-level standability resolver (never {@code null})
   * @return resolved global locations for all members, or empty list if capacity is insufficient
   */
  public List<RTPLocation> selectSafeSlots(
      int memberCount, int minSeparation, BlockValidator validator) {
    Objects.requireNonNull(validator, "validator cannot be null");
    final String worldName = anchor.coords().worldName();
    // Adapt the test-seam BlockValidator (Y-only) to the shared CandidateValidator contract so
    // there is a single Stage 2 selection code path. Production callers pass the region's real
    // CandidateValidator directly (see the overload below).
    CandidateValidator adapter =
        (worldX, worldZ) -> {
          int y = validator.standableY(worldX, worldZ);
          if (y == BlockValidator.INVALID) return null;
          return new RTPLocation(
              new RTPCoords(worldName, worldX, y, worldZ),
              anchor.attempts(),
              anchor.reservation());
        };
    return selectSafeSlots(memberCount, minSeparation, adapter);
  }

  /**
   * Two-stage selection of safe landing slots using the shared {@link CandidateValidator}.
   *
   * <p>This is the production selection path: Stage 1 discards known-bad chunks, Stage 2 screens
   * block columns inside the survivors with {@code validator} (which resolves a real standable
   * {@code Y} and applies block-clearance + claim checks). If fewer than {@code memberCount}
   * sufficiently separated validated slots exist, an empty list is returned (fail-closed capacity
   * denial, S-004).
   *
   * <p>The Stage 2 sampling stride is derived from {@code minSeparation} rather than being a
   * separate knob: sampling coarser than the separation you enforce is wasteful, and sampling finer
   * than it is pointless. A slight oversampling ({@code minSeparation / 2}) gives the greedy
   * separation pass enough distinct columns to actually reach {@code memberCount} in jagged terrain
   * where some grid cells have no standable {@code Y}.
   *
   * @param memberCount number of required landing positions
   * @param minSeparation minimum block clearance between any two placed points; also drives the
   *     internal Stage 2 sampling stride
   * @param validator shared per-candidate validator (never {@code null})
   * @return resolved global locations for all members, or empty list if capacity is insufficient
   */
  public List<RTPLocation> selectSafeSlots(
      int memberCount, int minSeparation, CandidateValidator validator) {
    Objects.requireNonNull(validator, "validator cannot be null");
    if (memberCount <= 0) return Collections.emptyList();

    final int sep = Math.max(1, minSeparation);
    final int sepSq = sep * sep;
    // Derive the sampling stride from the enforced separation (with 2x oversampling so the
    // greedy separation pass has room to fill in terrain with unstandable columns).
    final int step = Math.max(1, sep / 2);

    // Stage 2: bin validated candidates from surviving chunks (block units, real Y, claim-clear).
    List<RTPLocation> bin = new ArrayList<>();
    for (int[] chunk : survivingChunks()) {
      int baseX = chunk[0] << 4;
      int baseZ = chunk[1] << 4;
      for (int lx = 0; lx < CHUNK_SIZE; lx += step) {
        for (int lz = 0; lz < CHUNK_SIZE; lz += step) {
          RTPLocation validated = validator.validate(baseX + lx, baseZ + lz);
          if (validated != null && validated.coords() != null) {
            bin.add(validated);
          }
        }
      }
    }

    if (bin.size() < memberCount) {
      // Fail-closed: not even enough validated columns before separation. INSUFFICIENT_SAFE_SLOTS.
      return Collections.emptyList();
    }

    // Greedy separated selection over the validated bin (block units, real Y).
    Collections.shuffle(bin, ThreadLocalRandom.current());
    List<RTPLocation> selected = new ArrayList<>(memberCount);
    for (RTPLocation cand : bin) {
      boolean tooClose = false;
      for (RTPLocation prev : selected) {
        int ddx = cand.coords().x() - prev.coords().x();
        int ddz = cand.coords().z() - prev.coords().z();
        if (ddx * ddx + ddz * ddz < sepSq) {
          tooClose = true;
          break;
        }
      }
      if (!tooClose) {
        selected.add(cand);
        if (selected.size() == memberCount) break;
      }
    }

    if (selected.size() < memberCount) {
      // Enough validated columns, but separation constraint cannot be met. Deny fail-closed.
      return Collections.emptyList();
    }

    return new ArrayList<>(selected);
  }
}
