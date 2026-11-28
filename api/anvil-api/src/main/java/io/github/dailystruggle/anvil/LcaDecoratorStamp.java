package io.github.dailystruggle.anvil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable {@code 01:H_3}-sorted multi-chunk decorator and tree canopy stamp contract for {@code .lca} worldgen.
 *
 * <p>Provides a pure Tier 1 bridge allowing procedural tree and decorator producers (such as
 * {@code LeafTreeGen}) to export wood-first, leaf-distance-ordered multi-chunk canopy slices as
 * coalesced 3D Hilbert runs consumed lock-free during Pass 2 {@code .lca} slab stitching without
 * cross-module Tier 4 cycles.
 *
 * @governedBy anvil-api-ADR-003, leafgen-ADR-002, treegen-ADR-003, ADR-115
 * @trace REQ-CFG-ATLAS-001, REQ-RTP-S-004, REQ-RTP-S-005
 */
public final class LcaDecoratorStamp {

    private static final byte[] H2_TO_XZ = new byte[16];
    private static final ConcurrentHashMap<String, List<LcaDecoratorStamp>> STAMP_REGISTRY =
            new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong REGISTRY_EPOCH =
            new java.util.concurrent.atomic.AtomicLong(1L);
    private static volatile LcaDecoratorStamp[][] cachedStampsByBiome;

    static {
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                int h2 = LcaSectionCodec.xyzToOctreeHilbert12(x, 0, z) & 15;
                H2_TO_XZ[h2] = (byte) ((z << 2) | x);
            }
        }
    }

    private final String speciesId;
    private final int anchorLocalX;
    private final int anchorLocalZ;
    private final List<String> statePalette;
    private final List<ChunkCanopySlice> chunkSlices;
    private final Set<Integer> allowedBiomeIds;
    private final int totalVoxelCount;
    private final int totalHilbertRunCount;

    /**
     * Placement priority and lighting category for stamp blocks.
     */
    public enum BlockCategory {
        WOOD,
        DECORATOR,
        FOLIAGE
    }

    /**
     * Relative block entry relative to an anchor at {@code (anchorLocalX, anchorBaseY, anchorLocalZ)}.
     */
    public record RelativeVoxel(
            int dx,
            int dy,
            int dz,
            String blockState,
            BlockCategory category
    ) {
        public RelativeVoxel {
            Objects.requireNonNull(blockState, "blockState");
            Objects.requireNonNull(category, "category");
        }
    }

    /**
     * Contiguous {@code 01:H_3} Hilbert voxel run within a target chunk section.
     */
    public record HilbertRun(
            int relSectionY,
            int localYOffsetMode,
            int startH3,
            int runLength,
            int paletteIdx,
            BlockCategory category
    ) {
        public HilbertRun {
            if (startH3 < 0 || startH3 > 4095) {
                throw new IllegalArgumentException("startH3 out of [0..4095]: " + startH3);
            }
            if (runLength <= 0 || startH3 + runLength > 4096) {
                throw new IllegalArgumentException(
                        "Invalid runLength " + runLength + " at startH3=" + startH3);
            }
            if (paletteIdx < 0) {
                throw new IllegalArgumentException("paletteIdx must be >= 0: " + paletteIdx);
            }
            Objects.requireNonNull(category, "category");
        }
    }

    /**
     * Clipped canopy slice for neighboring chunk offset {@code (deltaChunkX, deltaChunkZ)}.
     */
    public record ChunkCanopySlice(
            int deltaChunkX,
            int deltaChunkZ,
            List<HilbertRun> runs
    ) {
        public ChunkCanopySlice {
            runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
        }
    }

    public LcaDecoratorStamp(
            String speciesId,
            int anchorLocalX,
            int anchorLocalZ,
            List<String> statePalette,
            List<ChunkCanopySlice> chunkSlices,
            Set<Integer> allowedBiomeIds
    ) {
        Objects.requireNonNull(speciesId, "speciesId");
        String normId = speciesId.trim().toLowerCase(Locale.ROOT);
        if (normId.isEmpty()) {
            throw new IllegalArgumentException("speciesId shall not be blank");
        }
        if (anchorLocalX < 0 || anchorLocalX > 15 || anchorLocalZ < 0 || anchorLocalZ > 15) {
            throw new IllegalArgumentException(
                    "Anchor local coordinates out of [0..15]: (" + anchorLocalX + ", " + anchorLocalZ + ")");
        }
        this.speciesId = normId;
        this.anchorLocalX = anchorLocalX;
        this.anchorLocalZ = anchorLocalZ;
        this.statePalette = List.copyOf(Objects.requireNonNull(statePalette, "statePalette"));
        this.chunkSlices = List.copyOf(Objects.requireNonNull(chunkSlices, "chunkSlices"));
        this.allowedBiomeIds = allowedBiomeIds == null || allowedBiomeIds.isEmpty()
                ? Set.of(0, 1, 5)
                : Collections.unmodifiableSet(new LinkedHashSet<>(allowedBiomeIds));

        int vox = 0;
        int runs = 0;
        for (ChunkCanopySlice slice : this.chunkSlices) {
            runs += slice.runs().size();
            for (HilbertRun r : slice.runs()) {
                vox += r.runLength();
            }
        }
        this.totalVoxelCount = vox;
        this.totalHilbertRunCount = runs;
    }

    public String speciesId() {
        return speciesId;
    }

    public int anchorLocalX() {
        return anchorLocalX;
    }

    public int anchorLocalZ() {
        return anchorLocalZ;
    }

    public List<String> statePalette() {
        return statePalette;
    }

    public List<ChunkCanopySlice> chunkSlices() {
        return chunkSlices;
    }

    public Set<Integer> allowedBiomeIds() {
        return allowedBiomeIds;
    }

    public int totalVoxelCount() {
        return totalVoxelCount;
    }

    public int totalHilbertRunCount() {
        return totalHilbertRunCount;
    }

    /**
     * Encodes section-local coordinates {@code (lx, ly, lz)} in {@code [0..15]^3} to their 12-bit
     * {@code 01:H_3} octree-Hilbert index.
     */
    public static int xyzToH3(int lx, int ly, int lz) {
        return LcaSectionCodec.xyzToOctreeHilbert12(lx & 15, ly & 15, lz & 15);
    }

    /**
     * Decodes a 12-bit {@code 01:H_3} octree-Hilbert index into packed {@code (ly << 8) | (lz << 4) | lx}.
     */
    public static int h3ToYzx(int h3) {
        int oct6 = (h3 >>> 6) & 63;
        int loc6 = h3 & 63;
        int octY = (oct6 >>> 4) & 3;
        int octXZ = H2_TO_XZ[oct6 & 15] & 15;
        int ly = (loc6 >>> 4) & 3;
        int lXZ = H2_TO_XZ[loc6 & 15] & 15;
        int lx = ((octXZ & 3) << 2) | (lXZ & 3);
        int y = (octY << 2) | ly;
        int lz = (((octXZ >>> 2) & 3) << 2) | ((lXZ >>> 2) & 3);
        return (y << 8) | (lz << 4) | lx;
    }

    /**
     * Classifies a Minecraft block-state string into {@link BlockCategory}.
     */
    public static BlockCategory classifyBlockState(String blockState) {
        if (blockState == null) {
            return BlockCategory.DECORATOR;
        }
        String lower = blockState.toLowerCase(Locale.ROOT);
        if (lower.contains("log")
                || lower.contains("wood")
                || lower.contains("stem")
                || lower.contains("hyphae")) {
            return BlockCategory.WOOD;
        }
        if (lower.contains("leaves") || lower.contains("vine") || lower.contains("wart_block")) {
            return BlockCategory.FOLIAGE;
        }
        return BlockCategory.DECORATOR;
    }

    /**
     * Compiles relative voxels anchored at {@code (anchorLocalX, 0, anchorLocalZ)} into per-chunk
     * {@code 01:H_3}-sorted and coalesced {@link ChunkCanopySlice} runs.
     *
     * <p>Note: {@code dy} is preserved modulo 16 inside {@code relSectionY = Math.floorDiv(dy, 16)}
     * with {@code localYOffsetMode = 0}, allowing Pass 2 stitches to shift vertical placement cleanly
     * relative to the anchor surface elevation.
     */
    public static LcaDecoratorStamp fromRelativeBlocks(
            String speciesId,
            int anchorLocalX,
            int anchorLocalZ,
            List<RelativeVoxel> voxels,
            Set<Integer> allowedBiomeIds
    ) {
        Objects.requireNonNull(voxels, "voxels");
        Map<String, Integer> paletteMap = new LinkedHashMap<>();
        Map<Long, Map<Long, VoxelCell>> perChunkCells = new LinkedHashMap<>();

        for (RelativeVoxel v : voxels) {
            int palIdx = paletteMap.computeIfAbsent(v.blockState(), k -> paletteMap.size());
            int bx = anchorLocalX + v.dx();
            int bz = anchorLocalZ + v.dz();
            int dy = v.dy();
            int dcx = Math.floorDiv(bx, 16);
            int dcz = Math.floorDiv(bz, 16);
            int relSecY = Math.floorDiv(dy, 16);
            int lx = Math.floorMod(bx, 16);
            int ly = Math.floorMod(dy, 16);
            int lz = Math.floorMod(bz, 16);
            int h3 = xyzToH3(lx, ly, lz);

            long chunkKey = ((long) dcx << 32) | (dcz & 0xFFFFFFFFL);
            long cellKey = ((long) relSecY << 16) | (h3 & 0xFFFFL);
            Map<Long, VoxelCell> cells = perChunkCells.computeIfAbsent(chunkKey, k -> new LinkedHashMap<>());
            VoxelCell existing = cells.get(cellKey);
            if (existing == null || v.category().ordinal() < existing.category.ordinal()) {
                cells.put(cellKey, new VoxelCell(relSecY, h3, palIdx, v.category()));
            }
        }

        List<ChunkCanopySlice> slices = new ArrayList<>(perChunkCells.size());
        for (Map.Entry<Long, Map<Long, VoxelCell>> entry : perChunkCells.entrySet()) {
            long ck = entry.getKey();
            int dcx = (int) (ck >> 32);
            int dcz = (int) ck;
            List<VoxelCell> sorted = new ArrayList<>(entry.getValue().values());
            sorted.sort(Comparator.comparingInt((VoxelCell c) -> c.relSecY)
                    .thenComparingInt(c -> c.category.ordinal())
                    .thenComparingInt(c -> c.palIdx)
                    .thenComparingInt(c -> c.h3));

            List<HilbertRun> runs = new ArrayList<>();
            int i = 0;
            while (i < sorted.size()) {
                VoxelCell head = sorted.get(i);
                int len = 1;
                while (i + len < sorted.size()) {
                    VoxelCell next = sorted.get(i + len);
                    if (next.relSecY == head.relSecY
                            && next.category == head.category
                            && next.palIdx == head.palIdx
                            && next.h3 == head.h3 + len) {
                        len++;
                    } else {
                        break;
                    }
                }
                runs.add(new HilbertRun(head.relSecY, 0, head.h3, len, head.palIdx, head.category));
                i += len;
            }
            slices.add(new ChunkCanopySlice(dcx, dcz, runs));
        }
        slices.sort(Comparator.comparingInt(ChunkCanopySlice::deltaChunkX)
                .thenComparingInt(ChunkCanopySlice::deltaChunkZ));

        return new LcaDecoratorStamp(
                speciesId,
                anchorLocalX,
                anchorLocalZ,
                new ArrayList<>(paletteMap.keySet()),
                slices,
                allowedBiomeIds);
    }

    /**
     * Registers or replaces custom species canopy stamp variants in the Tier 1 stamp registry.
     */
    public static void registerSpeciesVariants(
            String speciesId,
            Collection<LcaDecoratorStamp> variants
    ) {
        Objects.requireNonNull(speciesId, "speciesId");
        Objects.requireNonNull(variants, "variants");
        String key = speciesId.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || variants.isEmpty()) {
            return;
        }
        STAMP_REGISTRY.put(key, List.copyOf(variants));
        cachedStampsByBiome = null;
        REGISTRY_EPOCH.incrementAndGet();
    }

    /**
     * Registers a single {@link LcaDecoratorStamp} variant under its {@link #speciesId()}.
     */
    public static void registerStamp(LcaDecoratorStamp stamp) {
        Objects.requireNonNull(stamp, "stamp");
        STAMP_REGISTRY.compute(stamp.speciesId(), (k, prev) -> {
            List<LcaDecoratorStamp> next = new ArrayList<>();
            if (prev != null) {
                for (LcaDecoratorStamp s : prev) {
                    if (s.anchorLocalX() != stamp.anchorLocalX()
                            || s.anchorLocalZ() != stamp.anchorLocalZ()) {
                        next.add(s);
                    }
                }
            }
            next.add(stamp);
            return Collections.unmodifiableList(next);
        });
        cachedStampsByBiome = null;
        REGISTRY_EPOCH.incrementAndGet();
    }

    public static Set<String> registeredSpeciesIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(STAMP_REGISTRY.keySet()));
    }

    public static List<LcaDecoratorStamp> variantsForSpecies(String speciesId) {
        if (speciesId == null) {
            return List.of();
        }
        return STAMP_REGISTRY.getOrDefault(speciesId.trim().toLowerCase(Locale.ROOT), List.of());
    }

    public static List<LcaDecoratorStamp> allRegisteredStamps() {
        if (STAMP_REGISTRY.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(STAMP_REGISTRY.keySet());
        Collections.sort(keys);
        List<LcaDecoratorStamp> out = new ArrayList<>();
        for (String k : keys) {
            out.addAll(STAMP_REGISTRY.getOrDefault(k, List.of()));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Deterministically selects a registered stamp matching {@code biomeId} using {@code hashSeed},
     * or returns {@code null} when no matching custom stamp is registered.
     */
    public static LcaDecoratorStamp selectDeterministicStamp(int biomeId, int hashSeed) {
        if (STAMP_REGISTRY.isEmpty()) {
            return null;
        }
        LcaDecoratorStamp[][] table = cachedStampsByBiome;
        if (table == null) {
            List<LcaDecoratorStamp> all = allRegisteredStamps();
            LcaDecoratorStamp[][] built = new LcaDecoratorStamp[8][];
            for (int b = 0; b < 8; b++) {
                List<LcaDecoratorStamp> cands = new ArrayList<>();
                for (LcaDecoratorStamp s : all) {
                    if (s.allowedBiomeIds().contains(b)) {
                        cands.add(s);
                    }
                }
                built[b] = cands.toArray(new LcaDecoratorStamp[0]);
            }
            table = built;
            cachedStampsByBiome = table;
        }
        LcaDecoratorStamp[] bucket = table[biomeId & 7];
        if (bucket.length == 0) {
            return null;
        }
        return bucket[Math.floorMod(hashSeed, bucket.length)];
    }

    public static long registryEpoch() {
        return REGISTRY_EPOCH.get();
    }

    public static void clearRegistry() {
        STAMP_REGISTRY.clear();
        cachedStampsByBiome = null;
        REGISTRY_EPOCH.incrementAndGet();
    }

    private record VoxelCell(int relSecY, int h3, int palIdx, BlockCategory category) {}
}
