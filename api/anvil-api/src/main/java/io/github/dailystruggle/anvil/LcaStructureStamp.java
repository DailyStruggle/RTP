package io.github.dailystruggle.anvil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable {@code 01:H_3}-sorted multi-chunk structure template stamp contract for {@code .lca} worldgen.
 *
 * <p>Generalizes {@link LcaDecoratorStamp} from unrotated {@code 3x3} tree canopies to arbitrary
 * {@code NxN} multi-chunk {@code .nbt} structure templates across all eight horizontal symmetries
 * ({@code 4} quarter-turn rotations by {@code 2} front-back mirror states), preserving
 * {@code minecraft:structure_void} terrain passthrough, deterministic {@link StructureProcessorRule}
 * evaluation, submerged waterlogging guards, Jigsaw connector metadata, and {@link LcaBlockEntitySidecar}
 * residual NBT descriptors.
 *
 * @governedBy leafgen-ADR-003, anvil-api-ADR-003, treegen-ADR-003, ADR-115
 * @trace REQ-CFG-ATLAS-001, REQ-RTP-S-004, REQ-RTP-S-005
 */
public record LcaStructureStamp(
        String templateId,
        int rotationQuarterTurns,
        boolean mirroredFrontBack,
        int sizeX,
        int sizeY,
        int sizeZ,
        int chunkRadiusX,
        int chunkRadiusZ,
        TerrainProjection projection,
        List<String> statePalette,
        List<ChunkStampSlice> chunkSlices,
        List<StructureProcessorRule> processorRules,
        List<LcaBlockEntitySidecar> blockEntities) {

    private static final java.util.concurrent.atomic.AtomicLong REGISTRY_EPOCH =
            new java.util.concurrent.atomic.AtomicLong(1L);

    /** Returns the monotonic epoch incremented whenever structure template registries mutate. */
    public static long registryEpoch() {
        return REGISTRY_EPOCH.get();
    }

    /** Advances and returns the structure template registry epoch for GPU atlas cache invalidation. */
    public static long bumpRegistryEpoch() {
        return REGISTRY_EPOCH.incrementAndGet();
    }

    /** Vertical placement mode matching Mojang Jigsaw pool projection semantics. */
    public enum TerrainProjection {
        RIGID,
        TERRAIN_MATCHING
    }

    /**
     * Clipped {@code 01:H_3} Hilbert voxel slice for relative chunk offset {@code (dChunkX, dChunkZ)}.
     */
    public record ChunkStampSlice(
            int dChunkX,
            int dChunkZ,
            int relSectionFloor,
            List<LcaDecoratorStamp.HilbertRun> runs) {

        public ChunkStampSlice {
            runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
        }
    }

    /**
     * Deterministic palette-index substitution rule evaluated per world coordinate during stamping.
     */
    public record StructureProcessorRule(
            int inputPaletteIndex,
            int outputPaletteIndex,
            float probability,
            long salt,
            boolean preserveWaterloggedFluid) {

        public StructureProcessorRule {
            if (inputPaletteIndex < 0 || outputPaletteIndex < 0) {
                throw new IllegalArgumentException(
                        "Processor palette indices shall be >= 0: " + inputPaletteIndex + " -> " + outputPaletteIndex);
            }
            if (Float.isNaN(probability) || probability < 0.0f || probability > 1.0f) {
                throw new IllegalArgumentException("probability out of [0.0, 1.0]: " + probability);
            }
        }
    }

    /**
     * Rotated block-entity NBT sidecar emitted into chunk residual {@code block_entities} lists.
     */
    public record LcaBlockEntitySidecar(
            int relX,
            int relY,
            int relZ,
            String blockEntityId,
            String lootTableOrConfigId,
            Map<String, Object> nbtPayload) {

        public LcaBlockEntitySidecar {
            Objects.requireNonNull(blockEntityId, "blockEntityId");
            String normId = blockEntityId.trim().toLowerCase(Locale.ROOT);
            if (normId.isEmpty()) {
                throw new IllegalArgumentException("blockEntityId shall not be blank");
            }
            blockEntityId = normId.contains(":") ? normId : "minecraft:" + normId;
            lootTableOrConfigId = lootTableOrConfigId == null ? "" : lootTableOrConfigId.trim();
            nbtPayload = deepUnmodifiableMap(nbtPayload);
        }
    }

    /**
     * Pre-rotated Jigsaw socket metadata extracted from {@code minecraft:jigsaw} template blocks.
     */
    public record JigsawConnector(
            int relX,
            int relY,
            int relZ,
            String frontFacing,
            String topFacing,
            String name,
            String target,
            String pool,
            String finalState,
            String jointType) {

        public JigsawConnector {
            frontFacing = Objects.requireNonNull(frontFacing, "frontFacing").toLowerCase(Locale.ROOT);
            topFacing = Objects.requireNonNull(topFacing, "topFacing").toLowerCase(Locale.ROOT);
            name = name == null ? "minecraft:empty" : name;
            target = target == null ? "minecraft:empty" : target;
            pool = pool == null ? "minecraft:empty" : pool;
            finalState = (finalState == null || finalState.isBlank()) ? "minecraft:air" : finalState;
            jointType = (jointType == null || jointType.isBlank()) ? "rollable" : jointType;
        }
    }

    public LcaStructureStamp {
        Objects.requireNonNull(templateId, "templateId");
        String normId = templateId.trim().toLowerCase(Locale.ROOT);
        if (normId.isEmpty()) {
            throw new IllegalArgumentException("templateId shall not be blank");
        }
        templateId = normId;
        if (rotationQuarterTurns < 0 || rotationQuarterTurns > 3) {
            throw new IllegalArgumentException("rotationQuarterTurns out of [0..3]: " + rotationQuarterTurns);
        }
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
            throw new IllegalArgumentException(
                    "Invalid template bounds: (" + sizeX + ", " + sizeY + ", " + sizeZ + ")");
        }
        if (chunkRadiusX < 1 || chunkRadiusZ < 1) {
            throw new IllegalArgumentException(
                    "chunkRadius out of bounds: (" + chunkRadiusX + ", " + chunkRadiusZ + ")");
        }
        Objects.requireNonNull(projection, "projection");
        statePalette = List.copyOf(Objects.requireNonNull(statePalette, "statePalette"));

        List<ChunkStampSlice> sortedSlices = new ArrayList<>(Objects.requireNonNull(chunkSlices, "chunkSlices"));
        sortedSlices.sort(Comparator.comparingInt(ChunkStampSlice::dChunkZ)
                .thenComparingInt(ChunkStampSlice::dChunkX));
        chunkSlices = Collections.unmodifiableList(sortedSlices);

        processorRules = processorRules == null ? List.of() : List.copyOf(processorRules);
        blockEntities = blockEntities == null ? List.of() : List.copyOf(blockEntities);
    }

    /**
     * Looks up the {@link ChunkStampSlice} for relative chunk delta {@code (dChunkX, dChunkZ)}, or {@code null}.
     */
    public ChunkStampSlice sliceAt(int dChunkX, int dChunkZ) {
        int lo = 0;
        int hi = chunkSlices.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            ChunkStampSlice s = chunkSlices.get(mid);
            int cmp = Integer.compare(s.dChunkZ(), dChunkZ);
            if (cmp == 0) {
                cmp = Integer.compare(s.dChunkX(), dChunkX);
            }
            if (cmp < 0) {
                lo = mid + 1;
            } else if (cmp > 0) {
                hi = mid - 1;
            } else {
                return s;
            }
        }
        return null;
    }

    public int totalVoxelCount() {
        int total = 0;
        for (ChunkStampSlice s : chunkSlices) {
            for (LcaDecoratorStamp.HilbertRun r : s.runs()) {
                total += r.runLength();
            }
        }
        return total;
    }

    public int totalHilbertRunCount() {
        int total = 0;
        for (ChunkStampSlice s : chunkSlices) {
            total += s.runs().size();
        }
        return total;
    }

    /**
     * Evaluates deterministic {@link StructureProcessorRule} substitutions for {@code basePaletteIndex}
     * at world block coordinates {@code (worldX, worldY, worldZ)}.
     */
    public static int evaluateProcessorPaletteIndex(
            int basePaletteIndex,
            List<StructureProcessorRule> rules,
            int worldX,
            int worldY,
            int worldZ,
            long worldSeed) {
        if (rules == null || rules.isEmpty()) {
            return basePaletteIndex;
        }
        for (int i = 0; i < rules.size(); i++) {
            StructureProcessorRule rule = rules.get(i);
            if (rule.inputPaletteIndex() != basePaletteIndex) {
                continue;
            }
            if (rule.probability() >= 1.0f) {
                return rule.outputPaletteIndex();
            }
            if (rule.probability() <= 0.0f) {
                continue;
            }
            long h = worldSeed ^ rule.salt() ^ (i * 0x9E3779B97F4A7C15L);
            h ^= worldX * 0xBF58476D1CE4E5B9L;
            h ^= worldY * 0x94D049BB133111EBL;
            h ^= worldZ * 0xD6E8FEB86659FD93L;
            h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
            h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
            h = h ^ (h >>> 31);
            float sample = ((h >>> 40) & 0xFFFFFFL) * (1.0f / 16777216.0f);
            if (sample < rule.probability()) {
                return rule.outputPaletteIndex();
            }
        }
        return basePaletteIndex;
    }

    /**
     * Returns whether any rule touching {@code paletteIndex} requests fluid preservation when submerged.
     */
    public static boolean hasWaterloggedPreservationRule(
            int paletteIndex, List<StructureProcessorRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        for (StructureProcessorRule r : rules) {
            if ((r.inputPaletteIndex() == paletteIndex || r.outputPaletteIndex() == paletteIndex)
                    && !r.preserveWaterloggedFluid()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if {@code canonicalState} supports Vanilla {@code waterlogged} block state properties.
     */
    public static boolean isWaterloggableState(String canonicalState) {
        if (canonicalState == null || canonicalState.isEmpty()) {
            return false;
        }
        if (canonicalState.contains("waterlogged=")) {
            return true;
        }
        int bracket = canonicalState.indexOf('[');
        String base = (bracket >= 0 ? canonicalState.substring(0, bracket) : canonicalState)
                .toLowerCase(Locale.ROOT);
        int colon = base.indexOf(':');
        String path = colon >= 0 ? base.substring(colon + 1) : base;
        if (path.endsWith("_slab")) {
            return !canonicalState.contains("type=double");
        }
        return path.endsWith("_stairs")
                || path.endsWith("_fence")
                || path.endsWith("_wall")
                || path.endsWith("_trapdoor")
                || path.endsWith("_sign")
                || path.endsWith("_grate")
                || path.equals("iron_bars")
                || path.equals("chain")
                || path.equals("chest")
                || path.equals("trapped_chest")
                || path.equals("ender_chest")
                || path.equals("decorated_pot")
                || path.equals("ladder")
                || path.equals("lantern")
                || path.equals("soul_lantern")
                || path.equals("campfire")
                || path.equals("soul_campfire")
                || path.equals("conduit")
                || path.equals("heavy_core")
                || path.equals("scaffolding");
    }

    /**
     * Updates or inserts {@code waterlogged=true|false} in a canonical {@code name[k1=v1,k2=v2]} state string.
     */
    public static String withWaterlogged(String canonicalState, boolean waterlogged) {
        Objects.requireNonNull(canonicalState, "canonicalState");
        if (!isWaterloggableState(canonicalState)) {
            return canonicalState;
        }
        int bracket = canonicalState.indexOf('[');
        String val = waterlogged ? "true" : "false";
        if (bracket < 0 || !canonicalState.endsWith("]")) {
            return canonicalState + "[waterlogged=" + val + "]";
        }
        String base = canonicalState.substring(0, bracket);
        String inner = canonicalState.substring(bracket + 1, canonicalState.length() - 1);
        TreeMap<String, String> props = new TreeMap<>();
        if (!inner.isEmpty()) {
            for (String token : inner.split(",")) {
                int eq = token.indexOf('=');
                if (eq > 0) {
                    props.put(token.substring(0, eq).trim(), token.substring(eq + 1).trim());
                }
            }
        }
        props.put("waterlogged", val);
        StringBuilder sb = new StringBuilder(canonicalState.length() + 18);
        sb.append(base).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Adapts an existing {@link LcaDecoratorStamp} into an unrotated {@link LcaStructureStamp}.
     */
    public static LcaStructureStamp fromDecoratorStamp(LcaDecoratorStamp decoratorStamp) {
        Objects.requireNonNull(decoratorStamp, "decoratorStamp");
        List<ChunkStampSlice> slices = new ArrayList<>(decoratorStamp.chunkSlices().size());
        int maxAbsChunkX = 1;
        int maxAbsChunkZ = 1;
        int maxRelSec = 0;
        int minRelSec = 0;
        for (LcaDecoratorStamp.ChunkCanopySlice cs : decoratorStamp.chunkSlices()) {
            maxAbsChunkX = Math.max(maxAbsChunkX, Math.abs(cs.deltaChunkX()) + 1);
            maxAbsChunkZ = Math.max(maxAbsChunkZ, Math.abs(cs.deltaChunkZ()) + 1);
            int floorSec = Integer.MAX_VALUE;
            for (LcaDecoratorStamp.HilbertRun r : cs.runs()) {
                floorSec = Math.min(floorSec, r.relSectionY());
                minRelSec = Math.min(minRelSec, r.relSectionY());
                maxRelSec = Math.max(maxRelSec, r.relSectionY());
            }
            slices.add(new ChunkStampSlice(
                    cs.deltaChunkX(),
                    cs.deltaChunkZ(),
                    floorSec == Integer.MAX_VALUE ? 0 : floorSec,
                    cs.runs()));
        }
        int spanY = Math.max(16, (maxRelSec - minRelSec + 1) * 16);
        return new LcaStructureStamp(
                decoratorStamp.speciesId(),
                0,
                false,
                maxAbsChunkX * 16,
                spanY,
                maxAbsChunkZ * 16,
                maxAbsChunkX,
                maxAbsChunkZ,
                TerrainProjection.RIGID,
                decoratorStamp.statePalette(),
                slices,
                List.of(),
                List.of());
    }

    private static Map<String, Object> deepUnmodifiableMap(Map<String, Object> src) {
        if (src == null || src.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>(src.size());
        for (Map.Entry<String, Object> e : src.entrySet()) {
            copy.put(e.getKey(), deepCopyNbtValue(e.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyNbtValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            LinkedHashMap<String, Object> sub = new LinkedHashMap<>(m.size());
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                sub.put(String.valueOf(entry.getKey()), deepCopyNbtValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(sub);
        }
        if (v instanceof Nbt.NbtList nbtList) {
            List<Object> items = new ArrayList<>(nbtList.items.size());
            for (Object item : nbtList.items) {
                items.add(deepCopyNbtValue(item));
            }
            return new Nbt.NbtList(nbtList.elementType, Collections.unmodifiableList(items));
        }
        if (v instanceof List<?> list) {
            List<Object> items = new ArrayList<>(list.size());
            for (Object item : list) {
                items.add(deepCopyNbtValue(item));
            }
            return Collections.unmodifiableList(items);
        }
        if (v instanceof byte[] b) {
            return b.clone();
        }
        if (v instanceof int[] ia) {
            return ia.clone();
        }
        if (v instanceof long[] la) {
            return la.clone();
        }
        return v;
    }
}
