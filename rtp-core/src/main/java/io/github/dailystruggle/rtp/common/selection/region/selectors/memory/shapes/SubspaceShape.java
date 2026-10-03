package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.CandidateValidator;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionFileCoord;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
 *   <li><b>Stage 2 - shape lattice (bounded, block units).</b> A unit-scaled lattice (unit =
 *       placement distance) is masked by a distribution {@link Shape} and each surviving cell is
 *       screened by a {@link CandidateValidator} that resolves a real standable {@code Y} per column
 *       (the region vertical adjustor). The number of {@code VALIDATED} cells is the true slot
 *       count; capacity denial is enforced against these, not against chunk bits.</li>
 * </ol>
 *
 * <p><b>Invariant (Capacity Denial):</b> When selecting destinations for {@code N} participants, if
 * the bin holds fewer validated, sufficiently separated candidates than {@code N}, the selection is
 * denied fail-closed (S-004 audited) rather than stacking players or relaxing safety (S-001).
 */
public class SubspaceShape {

  /** Blocks per chunk edge (Minecraft chunk = 16x16 columns). */
  private static final int CHUNK_SIZE = 16;

  private final RTPLocation anchor;
  private final int anchorX;
  private final int anchorZ;
  private final int anchorCX;
  private final int anchorCZ;
  private final int blockRadius;
  private final int centerRadius;
  private final int chunkRadius;
  private final Region parentRegion;
  private final MemoryShape<?> parentShape;
  private java.util.Random rng = null;

  public void setRng(java.util.Random rng) {
    this.rng = rng;
  }

  protected java.util.Random rng() {
    return rng != null ? rng : ThreadLocalRandom.current();
  }

  /**
   * Constructs a new SubspaceShape bounded to a block-radius footprint around an anchor.
   *
   * @param anchor the central anchor location (never {@code null})
   * @param blockRadius footprint half-width in blocks
   * @param parentRegion the owning parent Region (optional, may be {@code null})
   */
  public SubspaceShape(RTPLocation anchor, int blockRadius, Region parentRegion) {
    this(anchor, blockRadius, 0, parentRegion);
  }

  /**
   * Constructs a new SubspaceShape bounded to a block-radius footprint around an anchor with inner exclusion radius.
   *
   * @param anchor the central anchor location (never {@code null})
   * @param blockRadius footprint half-width in blocks
   * @param centerRadius inner exclusion radius in blocks
   * @param parentRegion the owning parent Region (optional, may be {@code null})
   */
  public SubspaceShape(RTPLocation anchor, int blockRadius, int centerRadius, Region parentRegion) {
    this.anchor = Objects.requireNonNull(anchor, "anchor cannot be null");
    if (blockRadius < 0) {
      throw new IllegalArgumentException("Subspace blockRadius must be >= 0, got: " + blockRadius);
    }
    this.anchorX = anchor.coords().x();
    this.anchorZ = anchor.coords().z();
    this.anchorCX = anchorX >> 4;
    this.anchorCZ = anchorZ >> 4;
    this.blockRadius = blockRadius;
    this.centerRadius = Math.max(0, centerRadius);
    // Cover the block extent in chunks for the region-level chunk exclusion (Stage 1).
    this.chunkRadius = (blockRadius + CHUNK_SIZE - 1) / CHUNK_SIZE;
    this.parentRegion = parentRegion;
    if (parentRegion != null && parentRegion.getShape() instanceof MemoryShape<?> memShape) {
      this.parentShape = memShape;
    } else {
      this.parentShape = null;
    }
  }

  public RTPLocation getAnchor() {
    return anchor;
  }

  /** @return footprint half-width in blocks. */
  public int getBlockRadius() {
    return blockRadius;
  }

  /** @return inner exclusion radius in blocks. */
  public int getCenterRadius() {
    return centerRadius;
  }

  /** @return chunk footprint half-width derived from the block radius ({@code ceil(blockRadius/16)}). */
  public int getChunkRadius() {
    return chunkRadius;
  }

  /** @return footprint edge length in blocks ({@code 2 * blockRadius}). */
  public int getFootprintBlocks() {
    return 2 * blockRadius;
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
   * or outside bounds in the inherited parent spatial memory. Coordinates are chunk units, not blocks.
   *
   * <p>Subspaces inherit base memory hazards (terrain, material, void, ocean, scanned hazards) but
   * do not inherit {@code uniquePlacement} marks left by prior teleports or the anchor's own sampling.
   *
   * @param cdx chunk-X offset from the anchor's chunk
   * @param cdz chunk-Z offset from the anchor's chunk
   * @return true if that chunk is known bad or outside the parent region
   */
  public boolean isChunkKnownBad(int cdx, int cdz) {
    int cx = anchorCX + cdx;
    int cz = anchorCZ + cdz;
    if (parentShape != null) {
      return parentShape.isKnownHazard(cx, cz);
    }
    return false;
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
   * Calculates the set of finite Anvil / Linear macro-bins ({@link RegionFileCoord}) intersecting
   * this subspace's footprint (ADR-097 Section 2).
   *
   * <p>Because a typical subspace footprint (e.g. radius 32-128 blocks) is spatially compact
   * relative to a 512x512 block (32x32 chunk) region file, the footprint intersects a finite,
   * bounded set of macro-bins (typically 1 to 4 bins).
   *
   * @return set of intersecting {@link RegionFileCoord} bins; never null or empty
   */
  public Set<RegionFileCoord> intersectingBins() {
    String worldName = (anchor.coords() != null) ? anchor.coords().worldName() : null;
    int minX = anchorX - blockRadius;
    int maxX = anchorX + blockRadius;
    int minZ = anchorZ - blockRadius;
    int maxZ = anchorZ + blockRadius;

    int minRx = minX >> 9;
    int maxRx = maxX >> 9;
    int minRz = minZ >> 9;
    int maxRz = maxZ >> 9;

    Set<RegionFileCoord> bins = new LinkedHashSet<>();
    for (int rx = minRx; rx <= maxRx; rx++) {
      for (int rz = minRz; rz <= maxRz; rz++) {
        bins.add(new RegionFileCoord(worldName, rx, rz));
      }
    }
    return bins;
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
      int memberCount, int minSeparation, int elevationTolerance, CandidateValidator validator) {
    return selectSafeSlots(memberCount, minSeparation, elevationTolerance, null, validator);
  }

  /**
   * Unit-scaled, shape-masked lattice selection of safe landing slots.
   *
   * <p>The placement distance {@code d = max(1, minSeparation)} is the lattice unit: cell
   * {@code (i, j)} maps to the world column {@code anchor + (i*d, j*d)}, so any two distinct cells
   * are already at least {@code d} apart - separation is guaranteed by construction (no greedy
   * dedup). {@code distributionShape} masks the lattice: a cell is a candidate iff
   * {@code shape.contains(i, j)}; {@code null} means the full square lattice.
   *
   * <p><b>Arithmetic capacity pre-check (S-004).</b> Before validating any column, cells whose chunk
   * is known bad in the inherited {@link MemoryShape} are subtracted from the masked cell count; if
   * that upper bound is below {@code memberCount} the selection is denied fail-closed with no column
   * work. This is an upper bound (chunk-granular), so per-cell {@code validator} confirmation still
   * runs - no unproven slot is ever used.
   *
   * <p>Each surviving cell is validated at most once (uniqueness is intrinsic to the lattice), the
   * landing {@code Y} being resolved by {@code validator} (the region vertical adjustor). A cell is
   * kept only if {@code |Y - anchorY| <= elevationTolerance}. The walk stops at {@code memberCount};
   * if it ends short, an empty list is returned (fail-closed capacity denial).
   *
   * @param memberCount number of required landing positions
   * @param minSeparation placement distance in blocks; also the lattice unit
   * @param elevationTolerance maximum block {@code |Y - anchorY|} for a kept slot ({@code < 0}
   *     disables the elevation filter)
   * @param distributionShape lattice mask ({@code null} = full square lattice)
   * @param validator shared per-candidate validator (never {@code null})
   * @return resolved global locations for all members, or empty list if capacity is insufficient
   */
  public List<RTPLocation> selectSafeSlots(
      int memberCount,
      int minSeparation,
      int elevationTolerance,
      Shape<?> distributionShape,
      CandidateValidator validator) {
    Objects.requireNonNull(validator, "validator cannot be null");
    if (memberCount <= 0) return Collections.emptyList();

    final int anchorY = anchor.coords().y();

    List<int[]> cells = footprintCandidateColumns(distributionShape, minSeparation);
    if (cells.size() < memberCount) return Collections.emptyList();

    // Validate first into an attrition-absorbing pool (no separation yet), then select the required
    // count by minimum separation over the validated set - the final layout depends on which
    // candidates actually validate, so separation cannot be baked into enumeration.
    List<RTPLocation> validatedPool = new ArrayList<>();
    for (int[] cell : cells) {
      RTPLocation validated = validator.validate(cell[0], cell[1]);
      if (validated == null || validated.coords() == null) continue;
      if (elevationTolerance >= 0
          && Math.abs(validated.coords().y() - anchorY) > elevationTolerance) continue;
      validatedPool.add(validated);
    }

    List<RTPLocation> selected = selectBySeparation(validatedPool, memberCount, minSeparation);
    if (selected.size() < memberCount) return Collections.emptyList();
    return selected;
  }

  /**
   * Asynchronously selects safe landing slots, resolving column standability on demand (S-005)
   * without hard-depending on prior memory cache presence.
   */
  public java.util.concurrent.CompletableFuture<List<RTPLocation>> selectSafeSlotsAsync(
      int memberCount,
      int minSeparation,
      int elevationTolerance,
      Shape<?> distributionShape,
      CandidateValidator validator) {
    return selectSafeSlotsAsync(
        memberCount,
        memberCount,
        minSeparation,
        elevationTolerance,
        distributionShape,
        validator);
  }

  /**
   * Asynchronously selects safe landing slots with candidate over-provisioning (~2N), resolving
   * column standability on demand (S-005) without hard-depending on prior memory cache presence.
   *
   * @param memberCount minimum number of safe slots required
   * @param targetCandidates desired number of candidates to evaluate and return for attrition absorption
   * @param minSeparation minimum clearance between slots
   * @param elevationTolerance vertical deviation tolerance around anchor Y
   * @param distributionShape optional shape mask
   * @param validator block-level candidate validator
   * @return future completed with at least {@code memberCount} and up to {@code targetCandidates} safe slots,
   *         or empty list if {@code memberCount} could not be satisfied
   */
  public java.util.concurrent.CompletableFuture<List<RTPLocation>> selectSafeSlotsAsync(
      int memberCount,
      int targetCandidates,
      int minSeparation,
      int elevationTolerance,
      Shape<?> distributionShape,
      CandidateValidator validator) {
    Objects.requireNonNull(validator, "validator cannot be null");
    if (memberCount <= 0) return java.util.concurrent.CompletableFuture.completedFuture(Collections.emptyList());

    final int anchorY = anchor.coords().y();

    // Candidate pool: one world column per not-known-bad chunk in the footprint, drawn by consulting
    // the parent MemoryShape's effective-range / bin memory (isKnownBad). Because that hazard memory
    // is incomplete, the pool is oversampled and validated on demand so attrition can be absorbed;
    // minSeparation is deliberately NOT applied here - it is a post-validation selection filter (the
    // final layout depends on which candidates actually validate). The anchor column is proven-good
    // and evaluated first.
    List<int[]> cells = footprintCandidateColumns(distributionShape, minSeparation);
    if (cells.size() < memberCount) {
      // Coarse memory pre-filter starved the candidate pool; fall back without the pre-filter
      // so downstream on-demand CandidateValidator and verifiers evaluate columns directly.
      cells = footprintCandidateColumns(distributionShape, minSeparation, false);
    }
    if (cells.size() < memberCount) {
      RTP.log(
          java.util.logging.Level.FINE,
          "[group] subspace candidate enumeration produced "
              + cells.size()
              + " columns (< required "
              + memberCount
              + ") - footprint pre-filter starved the pool");
      return java.util.concurrent.CompletableFuture.completedFuture(Collections.emptyList());
    }

    final int candidateCount = cells.size();
    int target = Math.max(memberCount, targetCandidates);
    return evaluateSlotsSequentially(
            cells, 0, memberCount, target, elevationTolerance, anchorY, new ArrayList<>(), validator)
        .whenComplete(
            (validated, ex) -> {
              if (ex == null) {
                int got = (validated == null) ? 0 : validated.size();
                RTP.log(
                    java.util.logging.Level.FINE,
                    "[group] subspace candidate validation: "
                        + got
                        + " of "
                        + candidateCount
                        + " enumerated columns passed on-demand validation (anchor-first)");
              }
            });
  }

  private java.util.concurrent.CompletableFuture<List<RTPLocation>> evaluateSlotsSequentially(
      List<int[]> candidates,
      int index,
      int required,
      int target,
      int elevationTolerance,
      int anchorY,
      List<RTPLocation> acc,
      CandidateValidator validator) {
    if (acc.size() >= target) {
      return java.util.concurrent.CompletableFuture.completedFuture(new ArrayList<>(acc));
    }
    if (index >= candidates.size()) {
      return java.util.concurrent.CompletableFuture.completedFuture(
          acc.size() >= required ? new ArrayList<>(acc) : Collections.emptyList());
    }

    int[] cell = candidates.get(index);
    return validator.validateAsync(cell[0], cell[1])
        .thenCompose(loc -> {
          if (loc != null && loc.coords() != null
              && (elevationTolerance < 0 || Math.abs(loc.coords().y() - anchorY) <= elevationTolerance)) {
            acc.add(loc);
          }
          return evaluateSlotsSequentially(
              candidates, index + 1, required, target, elevationTolerance, anchorY, acc, validator);
        });
  }

  /**
   * Footprint candidate columns for group placement: one world column per not-known-bad chunk in the
   * {@code NxN} footprint. Chunk exclusion consults the parent {@link MemoryShape} (its
   * effective-range / bin memory via {@link #isChunkKnownBad}); for dual-layer shapes this is the
   * ACCUMULATE-style good-cell consult, and for a generic {@link MemoryShape} it degrades to a
   * per-chunk memory query (correct, if less optimal than a bin copy).
   *
   * <p>The list is oversampled (one column per surviving chunk, not per participant) and shuffled so
   * downstream on-demand validation can absorb attrition from incomplete hazard memory. The anchor
   * column - proven-good, drawn from the region's pre-verified queue - is always included and placed
   * first. {@code minSeparation} is used only to map an optional {@code distributionShape} mask into
   * lattice-unit space; separation itself is enforced later, over validated locations.
   *
   * @param distributionShape optional lattice mask ({@code null} = full footprint)
   * @param minSeparation placement distance in blocks (mask scaling only)
   * @return oversampled candidate world columns as {@code {x, z}}, anchor-first; never {@code null}
   */
  private List<int[]> footprintCandidateColumns(Shape<?> distributionShape, int minSeparation) {
    List<int[]> cols = footprintCandidateColumns(distributionShape, minSeparation, true);
    return cols;
  }

  private List<int[]> footprintCandidateColumns(
      Shape<?> distributionShape, int minSeparation, boolean applyKnownBadFilter) {
    final int d = Math.max(1, minSeparation);
    final long centerRadiusSq = (long) centerRadius * centerRadius;
    List<int[]> cols = new ArrayList<>();
    for (int cdx = -chunkRadius; cdx <= chunkRadius; cdx++) {
      for (int cdz = -chunkRadius; cdz <= chunkRadius; cdz++) {
        if (cdx == 0 && cdz == 0) continue; // anchor column handled explicitly below
        // Calculate world coordinates starting from chunk coordinates (chunk center: +8 blocks)
        int colX = ((anchorCX + cdx) * CHUNK_SIZE) + 8;
        int colZ = ((anchorCZ + cdz) * CHUNK_SIZE) + 8;

        // Clamp candidate columns to the block-radius footprint and enforce center exclusion radius
        long offX = (long) colX - anchorX;
        long offZ = (long) colZ - anchorZ;
        long distSq = offX * offX + offZ * offZ;
        if (distSq > (long) blockRadius * blockRadius) continue;
        if (centerRadius > 0 && distSq < centerRadiusSq) continue;

        if (distributionShape != null) {
          int li = Math.round((cdx * (float) CHUNK_SIZE) / d);
          int lj = Math.round((cdz * (float) CHUNK_SIZE) / d);
          if (!distributionShape.contains(li, lj)) continue;
        }
        if (applyKnownBadFilter && isChunkKnownBad(cdx, cdz)) continue;
        cols.add(new int[] {colX, colZ});
      }
    }
    Collections.shuffle(cols, rng());
    // Prepend the proven-good anchor column only when centerRadius == 0 and the distribution
    // mask admits the center; an annular mask (nearplayer / nearclaim) deliberately excludes the center.
    // Use the anchor chunk center if the anchor itself is not chunk-centered, to maintain separation.
    if (centerRadius <= 0 && (distributionShape == null || distributionShape.contains(0, 0))) {
      cols.add(0, new int[] {(anchorCX * CHUNK_SIZE) + 8, (anchorCZ * CHUNK_SIZE) + 8});
    }
    return cols;
  }

  /**
   * Greedy minimum-separation selection over already-validated locations (ADR-097). Seeds from the
   * first validated location (typically the anchor, which anchors the layout) and adds each
   * subsequent location at least {@code minSeparation} blocks from all already-selected ones, until
   * {@code required} are chosen or the pool is exhausted. Because the pool is already validated,
   * separation is decided over real landing points rather than a pre-committed lattice.
   *
   * @param validated validated candidate locations (attrition-absorbing pool)
   * @param required number of separated locations to select
   * @param minSeparation minimum block clearance between any two selected locations
   * @return up to {@code required} separated locations (fewer only if the pool cannot satisfy it)
   */
  public static List<RTPLocation> selectBySeparation(
      List<RTPLocation> validated, int required, int minSeparation) {
    List<RTPLocation> chosen = new ArrayList<>(required);
    if (validated == null || required <= 0) return chosen;
    final long minSq = (long) minSeparation * minSeparation;
    for (RTPLocation cand : validated) {
      if (cand == null || cand.coords() == null) continue;
      boolean ok = true;
      if (minSeparation > 0) {
        for (RTPLocation sel : chosen) {
          long dx = (long) cand.coords().x() - sel.coords().x();
          long dz = (long) cand.coords().z() - sel.coords().z();
          if ((dx * dx + dz * dz) < minSq) {
            ok = false;
            break;
          }
        }
      }
      if (ok) {
        chosen.add(cand);
        if (chosen.size() >= required) break;
      }
    }
    return chosen;
  }

  /**
   * Selects safe landing slots grouped into spatial clusters (e.g. 1v1, 2v2, 1v2, teams).
   *
   * <p>Cluster centers are spaced by at least {@code minClusterSeparation} across the subspace.
   * Members of each cluster are placed tightly within {@code intraClusterRadius} of their
   * cluster center, satisfying {@code elevationTolerance}.
   *
   * @param clusterSizes sizes of each cluster (e.g. [2, 2] for 2v2, [1, 2] for 1v2)
   * @param minClusterSeparation minimum clearance between different cluster centers
   * @param intraClusterRadius maximum radius around a cluster center for its members
   * @param elevationTolerance maximum vertical deviation within each cluster
   * @param distributionShape optional shape mask
   * @param validator block-level candidate validator
   * @return list of slot lists corresponding to each cluster, or empty list if placement fails
   */
  public List<List<RTPLocation>> selectSafeClusterSlots(
      List<Integer> clusterSizes,
      int minClusterSeparation,
      int intraClusterRadius,
      int elevationTolerance,
      Shape<?> distributionShape,
      CandidateValidator validator) {
    Objects.requireNonNull(validator, "validator cannot be null");
    if (clusterSizes == null || clusterSizes.isEmpty()) return Collections.emptyList();

    int totalMembers = 0;
    for (int size : clusterSizes) {
      if (size <= 0) return Collections.emptyList();
      totalMembers += size;
    }

    final int d = Math.max(1, minClusterSeparation);
    final int intraR = Math.max(1, intraClusterRadius);
    final int anchorY = anchor.coords().y();
    final int m = (getFootprintBlocks() / 2) / d;

    List<int[]> latticeCells = selectLatticeCells(d, m, distributionShape);
    if (latticeCells.size() < clusterSizes.size()) {
      return Collections.emptyList(); // Not enough well-separated cluster centers available
    }

    Collections.shuffle(latticeCells, rng());

    // Try finding valid cluster centers and safe columns for each cluster
    List<List<RTPLocation>> clusteredPlacements = new ArrayList<>(clusterSizes.size());
    List<int[]> chosenCenters = new ArrayList<>();

    for (int clusterIdx = 0; clusterIdx < clusterSizes.size(); clusterIdx++) {
      int requiredInCluster = clusterSizes.get(clusterIdx);
      boolean clusterPlaced = false;

      for (int[] candidateCenter : latticeCells) {
        // Must be sufficiently separated from already chosen cluster centers
        boolean separatedFromAll = true;
        for (int[] chosen : chosenCenters) {
          long dx = (long) candidateCenter[0] - chosen[0];
          long dz = (long) candidateCenter[1] - chosen[1];
          if ((dx * dx + dz * dz) < ((long) minClusterSeparation * minClusterSeparation)) {
            separatedFromAll = false;
            break;
          }
        }
        if (!separatedFromAll) continue;

        // Try placing requiredInCluster members around this candidate center
        List<RTPLocation> memberLocs = new ArrayList<>(requiredInCluster);
        int centerWorldX = candidateCenter[0];
        int centerWorldZ = candidateCenter[1];

        // Search columns in expanding concentric spiral/boxes around center
        int maxSearchRadius = Math.max(intraR, 2 * intraR);
        Integer clusterAnchorY = null;

        for (int r = 0; r <= maxSearchRadius && memberLocs.size() < requiredInCluster; r++) {
          for (int ox = -r; ox <= r && memberLocs.size() < requiredInCluster; ox++) {
            for (int oz = -r; oz <= r && memberLocs.size() < requiredInCluster; oz++) {
              if (Math.abs(ox) != r && Math.abs(oz) != r) continue; // Boundary only for this radius step
              if ((ox * ox + oz * oz) > (maxSearchRadius * maxSearchRadius)) continue;

              int colX = centerWorldX + ox;
              int colZ = centerWorldZ + oz;

              int cdx = (colX >> 4) - anchorCX;
              int cdz = (colZ >> 4) - anchorCZ;
              if (Math.abs(cdx) > chunkRadius || Math.abs(cdz) > chunkRadius) continue;
              if (isChunkKnownBad(cdx, cdz)) continue;

              RTPLocation validated = validator.validate(colX, colZ);
              if (validated == null || validated.coords() == null) continue;

              int vy = validated.coords().y();
              if (clusterAnchorY == null) {
                if (elevationTolerance >= 0 && Math.abs(vy - anchorY) > elevationTolerance * 2) continue;
                clusterAnchorY = vy;
              } else {
                if (elevationTolerance >= 0 && Math.abs(vy - clusterAnchorY) > elevationTolerance) continue;
              }

              memberLocs.add(validated);
            }
          }
        }

        if (memberLocs.size() == requiredInCluster) {
          chosenCenters.add(candidateCenter);
          clusteredPlacements.add(Collections.unmodifiableList(memberLocs));
          clusterPlaced = true;
          break;
        }
      }

      if (!clusterPlaced) {
        return Collections.emptyList(); // Fail-closed: cannot place full cluster
      }
    }

    return Collections.unmodifiableList(clusteredPlacements);
  }

  /**
   * Enumerates safe lattice cells across the subspace.
   * Excludes cells residing in known-bad or out-of-bounds chunks.
   */
  private List<int[]> selectLatticeCells(int d, int m, Shape<?> distributionShape) {
    return selectLatticeCells(d, m, distributionShape, true);
  }

  /**
   * Enumerates lattice cells across the subspace. When {@code applyKnownBadFilter} is true, cells
   * whose chunk is known bad in the inherited parent memory are excluded (the coarse Stage-1
   * pre-filter). When false, only the footprint clamp and distribution mask apply, leaving the
   * authoritative per-column {@code validator} and global verifiers to reject unsafe columns.
   *
   * <p>The Stage-1 known-bad filter is explicitly "a coarse pre-filter, never a safety guarantee":
   * it can false-exclude an entire footprint around a demonstrably-valid anchor (e.g. an anchor
   * drawn from the region's pre-verified kept queue whose chunk reads as known-bad due to stale or
   * coarse memory state). Callers therefore fall back to {@code applyKnownBadFilter = false} rather
   * than fail the placement, since the real safety checks still run downstream.
   */
  private List<int[]> selectLatticeCells(
      int d, int m, Shape<?> distributionShape, boolean applyKnownBadFilter) {
    List<int[]> cells = new ArrayList<>();
    for (int i = -m; i <= m; i++) {
      for (int j = -m; j <= m; j++) {
        if (distributionShape != null && !distributionShape.contains(i, j)) {
          continue;
        }
        int worldX = projectX(i * d);
        int worldZ = projectZ(j * d);
        int cdx = (worldX >> 4) - anchorCX;
        int cdz = (worldZ >> 4) - anchorCZ;
        // Clamp the lattice to the chunk footprint: a cell whose chunk lies outside the
        // (2*chunkRadius+1)^2 Stage-1 footprint is not part of this subspace.
        if (Math.abs(cdx) > chunkRadius || Math.abs(cdz) > chunkRadius) continue;
        if (applyKnownBadFilter && isChunkKnownBad(cdx, cdz)) continue;
        cells.add(new int[] {worldX, worldZ});
      }
    }
    return cells;
  }
}
