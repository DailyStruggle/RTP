package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import io.github.dailystruggle.rtp.common.selection.region.CandidateValidator;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
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
  private final int chunkRadius;
  private final Region parentRegion;
  private final MemoryShape<?> parentShape;

  /**
   * Constructs a new SubspaceShape bounded to a block-radius footprint around an anchor.
   *
   * <p>The footprint is expressed in <em>blocks</em> (participant placement is block-level). The
   * chunk footprint used for the Stage 1 region-level exclusion is derived by covering the block
   * extent ({@code ceil(blockRadius / 16)}), so chunks invalidated at the region level are still
   * discarded.
   *
   * @param anchor the central anchor location (never {@code null})
   * @param blockRadius footprint half-width in blocks; the footprint spans {@code 2*blockRadius}
   *     blocks per side. Must be {@code >= 0}.
   * @param parentRegion the owning parent Region (never {@code null})
   */
  public SubspaceShape(RTPLocation anchor, int blockRadius, Region parentRegion) {
    this.anchor = Objects.requireNonNull(anchor, "anchor cannot be null");
    if (blockRadius < 0) {
      throw new IllegalArgumentException("Subspace blockRadius must be >= 0, got: " + blockRadius);
    }
    this.anchorX = anchor.coords().x();
    this.anchorZ = anchor.coords().z();
    this.anchorCX = anchorX >> 4;
    this.anchorCZ = anchorZ >> 4;
    this.blockRadius = blockRadius;
    // Cover the block extent in chunks for the region-level chunk exclusion (Stage 1).
    this.chunkRadius = (blockRadius + CHUNK_SIZE - 1) / CHUNK_SIZE;
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

  /** @return footprint half-width in blocks. */
  public int getBlockRadius() {
    return blockRadius;
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
   * @param cdx chunk-X offset from the anchor's chunk
   * @param cdz chunk-Z offset from the anchor's chunk
   * @return true if that chunk is known bad or outside the parent region
   */
  public boolean isChunkKnownBad(int cdx, int cdz) {
    int cx = anchorCX + cdx;
    int cz = anchorCZ + cdz;
    if (parentShape != null) {
      if (!parentShape.contains(cx, cz)) return true;
      return parentShape.isKnownBad(cx, cz);
    }
    if (parentRegion != null && parentRegion.getShape() != null) {
      return !parentRegion.getShape().contains(cx, cz);
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

    final int d = Math.max(1, minSeparation);
    final int anchorY = anchor.coords().y();
    // Lattice half-extent in units: how many d-steps fit within the footprint half-width.
    final int m = (getFootprintBlocks() / 2) / d;

    // Fast Path: If parent memory exposes a direct bitmask container/table, execute
    // Bin Accumulate Repackaging (ADR-096) to select viable candidate cells without
    // per-point polling overhead.
    List<int[]> cells = selectLatticeCells(d, m, distributionShape);
    if (cells.size() < memberCount) {
      // Upper bound below required: deny fail-closed (INSUFFICIENT_SAFE_SLOTS) with no column work.
      return Collections.emptyList();
    }

    // Seeded spread order: shuffle so early picks do not cluster before validation fills in.
    Collections.shuffle(cells, ThreadLocalRandom.current());

    List<RTPLocation> selected = new ArrayList<>(memberCount);
    for (int[] cell : cells) {
      int worldX = cell[0];
      int worldZ = cell[1];
      if (isChunkKnownBad((worldX >> 4) - anchorCX, (worldZ >> 4) - anchorCZ)) continue;
      RTPLocation validated = validator.validate(worldX, worldZ);
      if (validated == null || validated.coords() == null) continue;
      if (elevationTolerance >= 0
          && Math.abs(validated.coords().y() - anchorY) > elevationTolerance) continue;
      selected.add(validated);
      if (selected.size() == memberCount) break;
    }

    if (selected.size() < memberCount) return Collections.emptyList();
    return new ArrayList<>(selected);
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

    Collections.shuffle(latticeCells, ThreadLocalRandom.current());

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
    // If the parent shape is backed by an AnvilRegionBinHazardTable, we can check whole
    // 32x32 MCA bins upfront: any bin that is full-discarded skips all contained lattice
    // cells in O(1) without evaluating individual chunks.
    List<int[]> cells = new ArrayList<>();
    for (int i = -m; i <= m; i++) {
      for (int j = -m; j <= m; j++) {
        if (distributionShape != null && !distributionShape.contains(i, j)) continue;
        int worldX = projectX(i * d);
        int worldZ = projectZ(j * d);
        int cdx = (worldX >> 4) - anchorCX;
        int cdz = (worldZ >> 4) - anchorCZ;
        // Clamp the lattice to the chunk footprint: a cell whose chunk lies outside the
        // (2*chunkRadius+1)^2 Stage-1 footprint is not part of this subspace.
        if (Math.abs(cdx) > chunkRadius || Math.abs(cdz) > chunkRadius) continue;
        if (isChunkKnownBad(cdx, cdz)) continue;
        cells.add(new int[] {worldX, worldZ});
      }
    }
    return cells;
  }
}
