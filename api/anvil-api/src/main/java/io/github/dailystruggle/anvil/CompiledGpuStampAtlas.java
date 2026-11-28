package io.github.dailystruggle.anvil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable pre-interned integer GPU stamp atlas compiling {@link LcaDecoratorStamp} canopies and
 * {@link LcaStructureStamp} templates into flat little-endian {@code int[]} buffers for OpenCL
 * Stage {@code K2b} in-kernel voxel stamping and its lifted-CPU twin.
 *
 * <p>Eliminates per-chunk string parsing, {@code TreeMap} waterlogging property maps, and dynamic
 * palette lookups during world generation by resolving every template state (and its submerged
 * {@code waterlogged=true} variant) once per {@code atlasEpoch}.
 *
 * @governedBy leafgen-ADR-004, leafgen-ADR-003, anvil-api-ADR-003, ADR-115
 * @trace REQ-CFG-ATLAS-001, REQ-RTP-S-004, REQ-RTP-S-005, REQ-SIM-000
 */
public final class CompiledGpuStampAtlas {

    /** Placement category code for {@link LcaDecoratorStamp.BlockCategory#WOOD}. */
    public static final int CAT_WOOD = 0;
    /** Placement category code for {@link LcaDecoratorStamp.BlockCategory#DECORATOR}. */
    public static final int CAT_DECORATOR = 1;
    /** Placement category code for {@link LcaDecoratorStamp.BlockCategory#FOLIAGE}. */
    public static final int CAT_FOLIAGE = 2;
    /** Placement category code for non-air {@link LcaStructureStamp} voxels. */
    public static final int CAT_STRUCTURE_SOLID = 4;
    /** Placement category code for explicit interior {@code minecraft:air} / {@code cave_air} carving runs. */
    public static final int CAT_STRUCTURE_AIR = 5;

    /** Words per entry in {@link #paletteMetaI32()}: {@code [wet16|dry16, flags]}. */
    public static final int PALETTE_META_WORDS = 2;
    /** Words per entry in {@link #slicesI32()}: {@code [dChunkX, dChunkZ, runStartIdx, runCount]}. */
    public static final int SLICE_WORDS = 4;
    /** Words per entry in {@link #runsI32()}: {@code [relSectionY, (runLen<<16)|startH3, atlasPalIdx, (ruleCnt<<20)|ruleStart]}. */
    public static final int RUN_WORDS = 4;
    /** Words per entry in {@link #rulesI32()}: {@code [inPalIdx, outPalIdx, probBits, saltLo, saltHi, preserveWaterlogged]}. */
    public static final int RULE_WORDS = 6;
    /** Words per per-batch placed stamp instance: {@code [originX, originY, originZ, sliceIdx, flags, 0, 0, 0]}. */
    public static final int PLACED_STAMP_WORDS = 8;

    /** {@code GpuPlacedStamp.flags} bit 0: {@link LcaStructureStamp.TerrainProjection#TERRAIN_MATCHING}. */
    public static final int STAMP_FLAG_TERRAIN_MATCHING = 1;
    /** {@code GpuPlacedStamp.flags} bit 1: unsealed submerged structure ({@code beardAdjustment == BEARD_NONE}). */
    public static final int STAMP_FLAG_UNSEALED_SUBMERGED = 2;

    private final long atlasEpoch;
    private final int baseStateCount;
    private final List<String> globalStateTable;
    private final int[] paletteMetaI32;
    private final int[] slicesI32;
    private final int[] runsI32;
    private final int[] rulesI32;
    private final IdentityHashMap<LcaDecoratorStamp, Map<Long, Integer>> decoratorSliceLookup;
    private final IdentityHashMap<LcaStructureStamp, Map<Long, Integer>> structureSliceLookup;

    private CompiledGpuStampAtlas(
            long atlasEpoch,
            int baseStateCount,
            List<String> globalStateTable,
            int[] paletteMetaI32,
            int[] slicesI32,
            int[] runsI32,
            int[] rulesI32,
            IdentityHashMap<LcaDecoratorStamp, Map<Long, Integer>> decoratorSliceLookup,
            IdentityHashMap<LcaStructureStamp, Map<Long, Integer>> structureSliceLookup) {
        this.atlasEpoch = atlasEpoch;
        this.baseStateCount = baseStateCount;
        this.globalStateTable = Collections.unmodifiableList(globalStateTable);
        this.paletteMetaI32 = paletteMetaI32;
        this.slicesI32 = slicesI32;
        this.runsI32 = runsI32;
        this.rulesI32 = rulesI32;
        this.decoratorSliceLookup = decoratorSliceLookup;
        this.structureSliceLookup = structureSliceLookup;
    }

    /**
     * Compiles all supplied {@link LcaDecoratorStamp} canopies and {@link LcaStructureStamp}
     * variants against {@code baseStateNames} into an immutable GPU-ready integer atlas.
     */
    public static CompiledGpuStampAtlas compile(
            String[] baseStateNames,
            Collection<LcaDecoratorStamp> decoratorStamps,
            Collection<LcaStructureStamp> structureStamps,
            long atlasEpoch) {
        Objects.requireNonNull(baseStateNames, "baseStateNames");
        if (baseStateNames.length == 0) {
            throw new IllegalArgumentException("baseStateNames shall contain at least air at index 0");
        }

        List<String> globalStates = new ArrayList<>(baseStateNames.length + 128);
        Map<String, Integer> stateToGlobalId = new LinkedHashMap<>(256);
        for (String base : baseStateNames) {
            String norm = Objects.requireNonNull(base, "baseStateNames element");
            if (!stateToGlobalId.containsKey(norm)) {
                stateToGlobalId.put(norm, globalStates.size());
                globalStates.add(norm);
            }
        }
        int baseCount = globalStates.size();

        IntGrowList palMeta = new IntGrowList(256);
        IntGrowList slices = new IntGrowList(512);
        IntGrowList runs = new IntGrowList(2048);
        IntGrowList rules = new IntGrowList(128);

        IdentityHashMap<LcaDecoratorStamp, Map<Long, Integer>> decMap = new IdentityHashMap<>();
        IdentityHashMap<LcaStructureStamp, Map<Long, Integer>> strMap = new IdentityHashMap<>();

        if (decoratorStamps != null) {
            for (LcaDecoratorStamp stamp : decoratorStamps) {
                if (stamp == null || decMap.containsKey(stamp)) {
                    continue;
                }
                List<String> sPal = stamp.statePalette();
                int[][] catPalIndices = new int[3][sPal.size()];
                for (int c = 0; c < 3; c++) {
                    for (int p = 0; p < sPal.size(); p++) {
                        String stateName = sPal.get(p);
                        int dryId = internGlobalState(stateName, globalStates, stateToGlobalId);
                        int entryIdx = palMeta.size() / PALETTE_META_WORDS;
                        palMeta.add(((dryId & 0xFFFF) << 16) | (dryId & 0xFFFF));
                        palMeta.add(c & 0xFF);
                        catPalIndices[c][p] = entryIdx;
                    }
                }

                Map<Long, Integer> byDelta = new HashMap<>();
                for (LcaDecoratorStamp.ChunkCanopySlice slice : stamp.chunkSlices()) {
                    List<LcaDecoratorStamp.HilbertRun> sRuns = slice.runs();
                    if (sRuns.isEmpty()) {
                        continue;
                    }
                    int runStart = runs.size() / RUN_WORDS;
                    for (LcaDecoratorStamp.HilbertRun r : sRuns) {
                        int pIdx = Math.min(r.paletteIdx(), sPal.size() - 1);
                        int catOrd = Math.min(2, Math.max(0, r.category().ordinal()));
                        int atlasPalIdx = catPalIndices[catOrd][pIdx];
                        runs.add(r.relSectionY());
                        runs.add(((r.runLength() & 0xFFFF) << 16) | (r.startH3() & 0xFFFF));
                        runs.add(atlasPalIdx);
                        runs.add(0);
                    }
                    int sliceIdx = slices.size() / SLICE_WORDS;
                    slices.add(slice.deltaChunkX());
                    slices.add(slice.deltaChunkZ());
                    slices.add(runStart);
                    slices.add(sRuns.size());
                    byDelta.put(packChunkDelta(slice.deltaChunkX(), slice.deltaChunkZ()), sliceIdx);
                }
                decMap.put(stamp, byDelta);
            }
        }

        if (structureStamps != null) {
            for (LcaStructureStamp stamp : structureStamps) {
                if (stamp == null || strMap.containsKey(stamp)) {
                    continue;
                }
                List<String> sPal = stamp.statePalette();
                List<LcaStructureStamp.StructureProcessorRule> sRules = stamp.processorRules();
                int palBaseIdx = palMeta.size() / PALETTE_META_WORDS;

                for (int p = 0; p < sPal.size(); p++) {
                    String sName = sPal.get(p);
                    boolean isAir = "minecraft:air".equals(sName) || "minecraft:cave_air".equals(sName);
                    int dryId = isAir ? 0 : internGlobalState(sName, globalStates, stateToGlobalId);
                    boolean waterloggable = !isAir && LcaStructureStamp.isWaterloggableState(sName);
                    boolean preserveFluid = LcaStructureStamp.hasWaterloggedPreservationRule(p, sRules);
                    int wetId = dryId;
                    if (waterloggable && preserveFluid) {
                        String wetName = LcaStructureStamp.withWaterlogged(sName, true);
                        wetId = wetName.equals(sName)
                                ? dryId
                                : internGlobalState(wetName, globalStates, stateToGlobalId);
                    }
                    int cat = isAir ? CAT_STRUCTURE_AIR : CAT_STRUCTURE_SOLID;
                    int flags = (cat & 0xFF)
                            | ((waterloggable ? 1 : 0) << 8)
                            | ((preserveFluid ? 1 : 0) << 9);
                    palMeta.add(((wetId & 0xFFFF) << 16) | (dryId & 0xFFFF));
                    palMeta.add(flags);
                }

                int ruleStartIdx = rules.size() / RULE_WORDS;
                int ruleCount = Math.min(255, sRules.size());
                for (int r = 0; r < ruleCount; r++) {
                    LcaStructureStamp.StructureProcessorRule rule = sRules.get(r);
                    int inIdx = palBaseIdx + Math.min(rule.inputPaletteIndex(), Math.max(0, sPal.size() - 1));
                    int outIdx = palBaseIdx + Math.min(rule.outputPaletteIndex(), Math.max(0, sPal.size() - 1));
                    long salt = rule.salt();
                    rules.add(inIdx);
                    rules.add(outIdx);
                    rules.add(Float.floatToRawIntBits(rule.probability()));
                    rules.add((int) salt);
                    rules.add((int) (salt >>> 32));
                    rules.add(rule.preserveWaterloggedFluid() ? 1 : 0);
                }
                int packedRuleRef = (ruleCount << 20) | (ruleStartIdx & 0xFFFFF);

                Map<Long, Integer> byDelta = new HashMap<>();
                for (LcaStructureStamp.ChunkStampSlice slice : stamp.chunkSlices()) {
                    List<LcaDecoratorStamp.HilbertRun> sRuns = slice.runs();
                    if (sRuns.isEmpty()) {
                        continue;
                    }
                    int runStart = runs.size() / RUN_WORDS;
                    for (LcaDecoratorStamp.HilbertRun r : sRuns) {
                        int pIdx = Math.min(r.paletteIdx(), Math.max(0, sPal.size() - 1));
                        runs.add(r.relSectionY());
                        runs.add(((r.runLength() & 0xFFFF) << 16) | (r.startH3() & 0xFFFF));
                        runs.add(palBaseIdx + pIdx);
                        runs.add(packedRuleRef);
                    }
                    int sliceIdx = slices.size() / SLICE_WORDS;
                    slices.add(slice.dChunkX());
                    slices.add(slice.dChunkZ());
                    slices.add(runStart);
                    slices.add(sRuns.size());
                    byDelta.put(packChunkDelta(slice.dChunkX(), slice.dChunkZ()), sliceIdx);
                }
                strMap.put(stamp, byDelta);
            }
        }

        return new CompiledGpuStampAtlas(
                atlasEpoch,
                baseCount,
                globalStates,
                palMeta.toArray(),
                slices.toArray(),
                runs.toArray(),
                rules.toArray(),
                decMap,
                strMap);
    }

    public long atlasEpoch() {
        return atlasEpoch;
    }

    public int baseStateCount() {
        return baseStateCount;
    }

    public List<String> globalStateTable() {
        return globalStateTable;
    }

    public String stateNameForGlobalId(int globalStateId) {
        if (globalStateId < 0 || globalStateId >= globalStateTable.size()) {
            return globalStateTable.get(0);
        }
        return globalStateTable.get(globalStateId);
    }

    public int[] paletteMetaI32() {
        return paletteMetaI32.clone();
    }

    public int[] paletteMetaInternal() {
        return paletteMetaI32;
    }

    public int[] slicesI32() {
        return slicesI32.clone();
    }

    public int[] slicesInternal() {
        return slicesI32;
    }

    public int[] runsI32() {
        return runsI32.clone();
    }

    public int[] runsInternal() {
        return runsI32;
    }

    public int[] rulesI32() {
        return rulesI32.clone();
    }

    public int[] rulesInternal() {
        return rulesI32;
    }

    public int sliceCount() {
        return slicesI32.length / SLICE_WORDS;
    }

    public int runCount() {
        return runsI32.length / RUN_WORDS;
    }

    public int ruleCount() {
        return rulesI32.length / RULE_WORDS;
    }

    /**
     * Returns the compiled slice index for {@code (stamp, deltaChunkX, deltaChunkZ)}, or {@code -1}.
     */
    public int sliceIndexForDecorator(LcaDecoratorStamp stamp, int deltaChunkX, int deltaChunkZ) {
        Map<Long, Integer> m = decoratorSliceLookup.get(stamp);
        if (m == null) {
            return -1;
        }
        Integer idx = m.get(packChunkDelta(deltaChunkX, deltaChunkZ));
        return idx != null ? idx : -1;
    }

    /**
     * Returns the compiled slice index for {@code (stamp, dChunkX, dChunkZ)}, or {@code -1}.
     */
    public int sliceIndexForStructure(LcaStructureStamp stamp, int dChunkX, int dChunkZ) {
        Map<Long, Integer> m = structureSliceLookup.get(stamp);
        if (m == null) {
            return -1;
        }
        Integer idx = m.get(packChunkDelta(dChunkX, dChunkZ));
        return idx != null ? idx : -1;
    }

    private static int internGlobalState(
            String stateName, List<String> globalStates, Map<String, Integer> stateToGlobalId) {
        Integer existing = stateToGlobalId.get(stateName);
        if (existing != null) {
            return existing;
        }
        if (globalStates.size() >= 65535) {
            throw new IllegalStateException("CompiledGpuStampAtlas exceeded 65,535 distinct block states");
        }
        int id = globalStates.size();
        globalStates.add(stateName);
        stateToGlobalId.put(stateName, id);
        return id;
    }

    private static long packChunkDelta(int dx, int dz) {
        return ((long) dx << 32) | (dz & 0xFFFF_FFFFL);
    }

    private static final class IntGrowList {
        private int[] buf;
        private int size;

        IntGrowList(int initialCap) {
            this.buf = new int[Math.max(16, initialCap)];
        }

        void add(int v) {
            if (size >= buf.length) {
                int[] next = new int[buf.length << 1];
                System.arraycopy(buf, 0, next, 0, size);
                buf = next;
            }
            buf[size++] = v;
        }

        int size() {
            return size;
        }

        int[] toArray() {
            int[] out = new int[size];
            System.arraycopy(buf, 0, out, 0, size);
            return out;
        }
    }
}
