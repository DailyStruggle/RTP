package io.github.dailystruggle.anvil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Pure-Java Vanilla {@code .nbt} structure template loader and {@code 8}-variant {@code 01:H_3}
 * {@link LcaStructureStamp} baker.
 *
 * <p>Decodes compressed or uncompressed {@code .nbt} structure payloads using {@link Nbt}, omits
 * {@code minecraft:structure_void} voxels while preserving interior {@code minecraft:air} carving runs,
 * resolves {@code minecraft:jigsaw} {@code final_state} blocks and {@link LcaStructureStamp.JigsawConnector}
 * sockets, and pre-bakes all {@code 4} quarter-turn rotations across {@code 2} front-back mirror states
 * so Pass-2 {@code .lca} chunk stitching performs zero per-chunk string parsing.
 *
 * @governedBy leafgen-ADR-003, anvil-api-ADR-003, treegen-ADR-003, ADR-115
 * @trace REQ-CFG-ATLAS-001, REQ-RTP-S-004, REQ-RTP-S-005
 */
public final class StructureTemplateNbtLoader {

    private static final ConcurrentHashMap<String, TemplateVariantSet> BUILTIN_CACHE =
            new ConcurrentHashMap<>();

    private StructureTemplateNbtLoader() {}

    /**
     * Pre-baked 8-symmetry table ({@code index = ((mirrored ? 1 : 0) << 2) | (rotation & 3)}) for a structure piece.
     */
    public record TemplateVariantSet(
            String templateId,
            List<LcaStructureStamp> variants,
            List<List<LcaStructureStamp.JigsawConnector>> connectorsByVariant) {

        public TemplateVariantSet {
            Objects.requireNonNull(templateId, "templateId");
            if (variants == null || variants.size() != 8) {
                throw new IllegalArgumentException("Expected 8 symmetry variants, got " + variants);
            }
            if (connectorsByVariant == null || connectorsByVariant.size() != 8) {
                throw new IllegalArgumentException("Expected 8 connector lists, got " + connectorsByVariant);
            }
            variants = List.copyOf(variants);
            List<List<LcaStructureStamp.JigsawConnector>> connCopy = new ArrayList<>(8);
            for (List<LcaStructureStamp.JigsawConnector> c : connectorsByVariant) {
                connCopy.add(List.copyOf(c));
            }
            connectorsByVariant = Collections.unmodifiableList(connCopy);
        }

        public static int variantIndex(int rotationQuarterTurns, boolean mirroredFrontBack) {
            return ((mirroredFrontBack ? 1 : 0) << 2) | (rotationQuarterTurns & 3);
        }

        public LcaStructureStamp variant(int rotationQuarterTurns, boolean mirroredFrontBack) {
            return variants.get(variantIndex(rotationQuarterTurns, mirroredFrontBack));
        }

        public List<LcaStructureStamp.JigsawConnector> connectors(
                int rotationQuarterTurns, boolean mirroredFrontBack) {
            return connectorsByVariant.get(variantIndex(rotationQuarterTurns, mirroredFrontBack));
        }
    }

    private record RawBlockEntry(
            int x,
            int y,
            int z,
            int paletteIndex,
            Map<String, Object> nbt) {}

    private record BakedVoxel(
            int relSec,
            int h3,
            int paletteIdx,
            LcaDecoratorStamp.BlockCategory category) {}

    /**
     * Parses a raw or gzipped Vanilla {@code .nbt} structure template byte array and bakes all 8 symmetry variants.
     */
    @SuppressWarnings("unchecked")
    public static TemplateVariantSet loadFromBytes(
            String templateId,
            byte[] rawOrGzipNbt,
            LcaStructureStamp.TerrainProjection projection,
            List<LcaStructureStamp.StructureProcessorRule> rules) throws IOException {
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(projection, "projection");
        String normId = templateId.trim().toLowerCase(Locale.ROOT);
        if (normId.isEmpty()) {
            throw new IllegalArgumentException("templateId shall not be blank");
        }
        if (rawOrGzipNbt == null || rawOrGzipNbt.length < 4) {
            throw new IOException("Malformed or empty structure .nbt for template '" + normId + "'");
        }

        byte[] uncompressed = maybeGunzip(normId, rawOrGzipNbt);
        LinkedHashMap<String, Object> root;
        try {
            root = Nbt.readRootCompound(uncompressed);
        } catch (RuntimeException | IOException ex) {
            throw new IOException("Failed to decode structure .nbt for template '" + normId + "': " + ex.getMessage(), ex);
        }

        int[] dims = parseIntTriple(root.get("size"), "size", normId);
        int origSx = dims[0];
        int origSy = dims[1];
        int origSz = dims[2];
        if (origSx < 1 || origSy < 1 || origSz < 1 || origSx > 512 || origSy > 384 || origSz > 512) {
            throw new IOException("Invalid structure dimensions [" + origSx + "," + origSy + "," + origSz
                    + "] in template '" + normId + "'");
        }

        Object singlePaletteObj = root.get("palette");
        List<Object> paletteList = singlePaletteObj != null
                ? extractListItems(singlePaletteObj, "palette", normId)
                : List.of();
        if (paletteList.isEmpty()) {
            Object palettesObj = root.get("palettes");
            if (palettesObj instanceof Nbt.NbtList multi && !multi.items.isEmpty()) {
                paletteList = extractListItems(multi.items.get(0), "palettes[0]", normId);
            } else if (palettesObj instanceof List<?> multiList && !multiList.isEmpty()) {
                paletteList = extractListItems(multiList.get(0), "palettes[0]", normId);
            }
        }
        if (paletteList.isEmpty()) {
            throw new IOException("Empty palette in structure template '" + normId + "'");
        }

        List<String> rawPalette = new ArrayList<>(paletteList.size());
        for (Object item : paletteList) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new IOException("Palette item is not a compound in '" + normId + "'");
            }
            Object nameObj = entry.get("Name");
            if (!(nameObj instanceof String rawName) || rawName.isBlank()) {
                throw new IOException("Palette entry missing Name in '" + normId + "'");
            }
            String baseName = normalizeNamespacedId(rawName);
            TreeMap<String, String> props = new TreeMap<>();
            if (entry.get("Properties") instanceof Map<?, ?> propMap) {
                for (Map.Entry<?, ?> pe : propMap.entrySet()) {
                    props.put(String.valueOf(pe.getKey()), String.valueOf(pe.getValue()));
                }
            }
            rawPalette.add(formatCanonicalState(baseName, props));
        }

        List<Object> blocksList = extractListItems(root.get("blocks"), "blocks", normId);
        List<RawBlockEntry> rawBlocks = new ArrayList<>(blocksList.size());
        for (Object bObj : blocksList) {
            if (!(bObj instanceof Map<?, ?> bMap)) {
                throw new IOException("Block entry is not a compound in '" + normId + "'");
            }
            int[] pos = parseIntTriple(bMap.get("pos"), "blocks[].pos", normId);
            Object stateObj = bMap.get("state");
            if (!(stateObj instanceof Number stateNum)) {
                throw new IOException("Block entry missing numeric state index in '" + normId + "'");
            }
            int stateIdx = stateNum.intValue();
            if (stateIdx < 0 || stateIdx >= rawPalette.size()) {
                throw new IOException("Block state index " + stateIdx + " out of bounds for palette size "
                        + rawPalette.size() + " in '" + normId + "'");
            }
            Map<String, Object> blockNbt = bMap.get("nbt") instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : null;
            rawBlocks.add(new RawBlockEntry(pos[0], pos[1], pos[2], stateIdx, blockNbt));
        }

        List<LcaStructureStamp.StructureProcessorRule> safeRules =
                rules == null ? List.of() : List.copyOf(rules);
        return bakeAllVariants(normId, origSx, origSy, origSz, rawPalette, rawBlocks, projection, safeRules);
    }

    /**
     * Serializes a structure template compound into raw or gzipped Vanilla {@code .nbt} bytes.
     */
    public static byte[] writeStructureNbtBytes(
            int sizeX,
            int sizeY,
            int sizeZ,
            List<String> paletteStates,
            int[][] posAndStateRows,
            Map<Integer, Map<String, Object>> blockEntityNbtByRow,
            boolean gzip) throws IOException {
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
            throw new IllegalArgumentException("Dimensions must be >= 1");
        }
        Objects.requireNonNull(paletteStates, "paletteStates");
        Objects.requireNonNull(posAndStateRows, "posAndStateRows");

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", 3953);
        root.put("size", new Nbt.NbtList(Nbt.TAG_INT, List.of(sizeX, sizeY, sizeZ)));

        List<Object> palItems = new ArrayList<>(paletteStates.size());
        for (String state : paletteStates) {
            LinkedHashMap<String, Object> pEntry = new LinkedHashMap<>();
            int bracket = state.indexOf('[');
            if (bracket < 0) {
                pEntry.put("Name", normalizeNamespacedId(state));
            } else {
                pEntry.put("Name", normalizeNamespacedId(state.substring(0, bracket)));
                String inner = state.substring(bracket + 1, state.length() - 1);
                if (!inner.isEmpty()) {
                    LinkedHashMap<String, Object> props = new LinkedHashMap<>();
                    for (String tok : inner.split(",")) {
                        int eq = tok.indexOf('=');
                        if (eq > 0) {
                            props.put(tok.substring(0, eq).trim(), tok.substring(eq + 1).trim());
                        }
                    }
                    pEntry.put("Properties", props);
                }
            }
            palItems.add(pEntry);
        }
        root.put("palette", new Nbt.NbtList(Nbt.TAG_COMPOUND, palItems));

        List<Object> blockItems = new ArrayList<>(posAndStateRows.length);
        for (int i = 0; i < posAndStateRows.length; i++) {
            int[] row = posAndStateRows[i];
            LinkedHashMap<String, Object> bEntry = new LinkedHashMap<>();
            bEntry.put("pos", new Nbt.NbtList(Nbt.TAG_INT, List.of(row[0], row[1], row[2])));
            bEntry.put("state", row[3]);
            if (blockEntityNbtByRow != null && blockEntityNbtByRow.containsKey(i)) {
                bEntry.put("nbt", new LinkedHashMap<>(blockEntityNbtByRow.get(i)));
            }
            blockItems.add(bEntry);
        }
        root.put("blocks", new Nbt.NbtList(Nbt.TAG_COMPOUND, blockItems));
        root.put("entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of()));

        byte[] raw = Nbt.writeNamedRoot("", root);
        if (!gzip) {
            return raw;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(raw.length);
        try (GZIPOutputStream gos = new GZIPOutputStream(baos)) {
            gos.write(raw);
        }
        return baos.toByteArray();
    }

    /**
     * Synthesizes deterministic pure-Java Vanilla structure / Jigsaw piece templates when external Mojang
     * server jar {@code .nbt} assets are not mounted on disk.
     */
    public static TemplateVariantSet synthesizeBuiltinVanillaTemplate(String structureOrPieceId) {
        Objects.requireNonNull(structureOrPieceId, "structureOrPieceId");
        String norm = normalizeNamespacedId(structureOrPieceId);
        return BUILTIN_CACHE.computeIfAbsent(norm, k -> {
            TemplateVariantSet set = buildBuiltinTemplateUncached(k);
            LcaStructureStamp.bumpRegistryEpoch();
            return set;
        });
    }

    /** Returns an immutable snapshot of all cached built-in {@link LcaStructureStamp} symmetry variants. */
    public static List<LcaStructureStamp> allCachedBuiltinStamps() {
        if (BUILTIN_CACHE.isEmpty()) {
            prewarmBuiltinTemplates();
        }
        List<LcaStructureStamp> out = new ArrayList<>(BUILTIN_CACHE.size() * 8);
        for (TemplateVariantSet set : BUILTIN_CACHE.values()) {
            out.addAll(set.variants());
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Pre-bakes all built-in Vanilla structure and Jigsaw templates in parallel into the in-memory cache.
     */
    public static void prewarmBuiltinTemplates() {
        List.of(
                        "minecraft:buried_treasure",
                        "minecraft:igloo",
                        "minecraft:swamp_hut",
                        "minecraft:desert_pyramid",
                        "minecraft:jungle_temple",
                        "minecraft:pillager_outpost",
                        "minecraft:shipwreck",
                        "minecraft:ocean_ruin_cold",
                        "minecraft:ruined_portal",
                        "minecraft:mineshaft",
                        "minecraft:mineshaft/corridor",
                        "minecraft:village_plains",
                        "minecraft:village/plains/streets",
                        "minecraft:village/plains/houses",
                        "minecraft:trial_chambers",
                        "minecraft:trial_chambers/corridor",
                        "minecraft:trial_chambers/chamber",
                        "minecraft:ancient_city",
                        "minecraft:ancient_city/walkway",
                        "minecraft:trail_ruins")
                .parallelStream()
                .forEach(StructureTemplateNbtLoader::synthesizeBuiltinVanillaTemplate);
    }

    private static TemplateVariantSet bakeAllVariants(
            String normId,
            int origSx,
            int origSy,
            int origSz,
            List<String> rawPalette,
            List<RawBlockEntry> rawBlocks,
            LcaStructureStamp.TerrainProjection projection,
            List<LcaStructureStamp.StructureProcessorRule> baseRules) {
        List<LcaStructureStamp> variants = new ArrayList<>(8);
        List<List<LcaStructureStamp.JigsawConnector>> connectorsAll = new ArrayList<>(8);

        for (int mir = 0; mir <= 1; mir++) {
            boolean mirrored = (mir == 1);
            for (int rot = 0; rot < 4; rot++) {
                int outSx = (rot == 1 || rot == 3) ? origSz : origSx;
                int outSz = (rot == 1 || rot == 3) ? origSx : origSz;
                int chunkRadX = Math.max(1, (outSx + 15) >>> 4);
                int chunkRadZ = Math.max(1, (outSz + 15) >>> 4);

                List<String> variantPalette = new ArrayList<>(rawPalette.size() + 2);
                Map<String, Integer> paletteLookup = new LinkedHashMap<>(rawPalette.size() * 2);
                int[] rawIdxToVariantIdx = new int[rawPalette.size()];
                for (int p = 0; p < rawPalette.size(); p++) {
                    String rawBase = extractBaseName(rawPalette.get(p));
                    if ("minecraft:structure_void".equals(rawBase) || "minecraft:jigsaw".equals(rawBase)) {
                        rawIdxToVariantIdx[p] = -1;
                        continue;
                    }
                    String transformed = transformState(rawPalette.get(p), mirrored, rot);
                    int mapped = paletteLookup.computeIfAbsent(transformed, k -> {
                        variantPalette.add(k);
                        return variantPalette.size() - 1;
                    });
                    rawIdxToVariantIdx[p] = mapped;
                }

                List<LcaStructureStamp.StructureProcessorRule> variantRules = new ArrayList<>(baseRules.size());
                for (LcaStructureStamp.StructureProcessorRule r : baseRules) {
                    if (r.inputPaletteIndex() < rawIdxToVariantIdx.length
                            && r.outputPaletteIndex() < rawIdxToVariantIdx.length
                            && rawIdxToVariantIdx[r.inputPaletteIndex()] >= 0
                            && rawIdxToVariantIdx[r.outputPaletteIndex()] >= 0) {
                        variantRules.add(new LcaStructureStamp.StructureProcessorRule(
                                rawIdxToVariantIdx[r.inputPaletteIndex()],
                                rawIdxToVariantIdx[r.outputPaletteIndex()],
                                r.probability(),
                                r.salt(),
                                r.preserveWaterloggedFluid()));
                    }
                }

                Map<Long, List<BakedVoxel>> voxelsByChunk = new LinkedHashMap<>();
                List<LcaStructureStamp.LcaBlockEntitySidecar> sidecars = new ArrayList<>();
                List<LcaStructureStamp.JigsawConnector> connectors = new ArrayList<>();

                for (RawBlockEntry b : rawBlocks) {
                    int mx = mirrored ? (origSx - 1 - b.x()) : b.x();
                    int mz = b.z();
                    int rx;
                    int rz;
                    switch (rot) {
                        case 1 -> {
                            rx = origSz - 1 - mz;
                            rz = mx;
                        }
                        case 2 -> {
                            rx = origSx - 1 - mx;
                            rz = origSz - 1 - mz;
                        }
                        case 3 -> {
                            rx = mz;
                            rz = origSx - 1 - mx;
                        }
                        default -> {
                            rx = mx;
                            rz = mz;
                        }
                    }
                    int ry = b.y();

                    String origState = rawPalette.get(b.paletteIndex());
                    String baseName = extractBaseName(origState);
                    if ("minecraft:structure_void".equals(baseName)) {
                        continue;
                    }

                    int finalPaletteIdx;
                    if ("minecraft:jigsaw".equals(baseName)) {
                        String[] frontTop = extractJigsawFrontTop(origState, mirrored, rot);
                        String jName = getStringTag(b.nbt(), "name", "minecraft:empty");
                        String jTarget = getStringTag(b.nbt(), "target", "minecraft:empty");
                        String jPool = getStringTag(b.nbt(), "pool", "minecraft:empty");
                        String rawFinal = getStringTag(b.nbt(), "final_state", "minecraft:air");
                        String jJoint = getStringTag(b.nbt(), "joint", "rollable");
                        String normFinal = transformState(canonicalizeStateString(rawFinal), mirrored, rot);
                        connectors.add(new LcaStructureStamp.JigsawConnector(
                                rx, ry, rz, frontTop[0], frontTop[1], jName, jTarget, jPool, normFinal, jJoint));
                        if ("minecraft:structure_void".equals(extractBaseName(normFinal))) {
                            continue;
                        }
                        finalPaletteIdx = paletteLookup.computeIfAbsent(normFinal, k -> {
                            variantPalette.add(k);
                            return variantPalette.size() - 1;
                        });
                    } else {
                        finalPaletteIdx = rawIdxToVariantIdx[b.paletteIndex()];
                        if (b.nbt() != null && !b.nbt().isEmpty()) {
                            String beId = getStringTag(b.nbt(), "id", baseName);
                            String lootId = getStringTag(b.nbt(), "LootTable", "");
                            if (lootId.isEmpty()) {
                                lootId = getStringTag(b.nbt(), "config", "");
                            }
                            sidecars.add(new LcaStructureStamp.LcaBlockEntitySidecar(
                                    rx, ry, rz, beId, lootId, b.nbt()));
                        }
                    }

                    String placedState = variantPalette.get(finalPaletteIdx);
                    LcaDecoratorStamp.BlockCategory category = classifyStructureStateCategory(placedState);
                    int dcx = Math.floorDiv(rx, 16);
                    int dcz = Math.floorDiv(rz, 16);
                    int relSec = Math.floorDiv(ry, 16);
                    int h3 = LcaSectionCodec.xyzToOctreeHilbert12(rx & 15, ry & 15, rz & 15);
                    long cKey = (((long) dcx) << 32) ^ (dcz & 0xFFFF_FFFFL);
                    voxelsByChunk.computeIfAbsent(cKey, k -> new ArrayList<>())
                            .add(new BakedVoxel(relSec, h3, finalPaletteIdx, category));
                }

                List<LcaStructureStamp.ChunkStampSlice> slices = new ArrayList<>(voxelsByChunk.size());
                for (Map.Entry<Long, List<BakedVoxel>> ce : voxelsByChunk.entrySet()) {
                    long key = ce.getKey();
                    int dcx = (int) (key >> 32);
                    int dcz = (int) key;
                    List<BakedVoxel> vList = ce.getValue();
                    vList.sort(Comparator.comparingInt(BakedVoxel::relSec)
                            .thenComparingInt(BakedVoxel::h3));
                    List<LcaDecoratorStamp.HilbertRun> runs = new ArrayList<>();
                    int floorSec = vList.get(0).relSec();
                    int i = 0;
                    while (i < vList.size()) {
                        BakedVoxel cur = vList.get(i);
                        int endH3 = cur.h3();
                        int j = i + 1;
                        while (j < vList.size()) {
                            BakedVoxel nxt = vList.get(j);
                            if (nxt.relSec() != cur.relSec()) {
                                break;
                            }
                            if (nxt.h3() == endH3) {
                                j++;
                                continue;
                            }
                            if (nxt.h3() == endH3 + 1
                                    && nxt.paletteIdx() == cur.paletteIdx()
                                    && nxt.category() == cur.category()) {
                                endH3 = nxt.h3();
                                j++;
                            } else {
                                break;
                            }
                        }
                        runs.add(new LcaDecoratorStamp.HilbertRun(
                                cur.relSec(),
                                0,
                                cur.h3(),
                                endH3 - cur.h3() + 1,
                                cur.paletteIdx(),
                                cur.category()));
                        i = j;
                    }
                    slices.add(new LcaStructureStamp.ChunkStampSlice(dcx, dcz, floorSec, runs));
                }

                variants.add(new LcaStructureStamp(
                        normId,
                        rot,
                        mirrored,
                        outSx,
                        origSy,
                        outSz,
                        chunkRadX,
                        chunkRadZ,
                        projection,
                        variantPalette,
                        slices,
                        variantRules,
                        sidecars));
                connectorsAll.add(connectors);
            }
        }
        return new TemplateVariantSet(normId, variants, connectorsAll);
    }

    static String transformState(String canonicalState, boolean mirroredFrontBack, int rotQuarterTurns) {
        int bracket = canonicalState.indexOf('[');
        if (bracket < 0 || !canonicalState.endsWith("]")) {
            return canonicalState;
        }
        String base = canonicalState.substring(0, bracket);
        String inner = canonicalState.substring(bracket + 1, canonicalState.length() - 1);
        if (inner.isEmpty()) {
            return base;
        }
        TreeMap<String, String> props = new TreeMap<>();
        for (String tok : inner.split(",")) {
            int eq = tok.indexOf('=');
            if (eq > 0) {
                props.put(tok.substring(0, eq).trim(), tok.substring(eq + 1).trim());
            }
        }

        if (mirroredFrontBack) {
            if (props.containsKey("facing")) {
                props.put("facing", mirrorDirFrontBack(props.get("facing")));
            }
            if (props.containsKey("orientation")) {
                String[] parts = props.get("orientation").split("_", 2);
                if (parts.length == 2) {
                    props.put("orientation", mirrorDirFrontBack(parts[0]) + "_" + mirrorDirFrontBack(parts[1]));
                }
            }
            if (props.containsKey("east") || props.containsKey("west")) {
                String e = props.get("east");
                String w = props.get("west");
                if (w != null) {
                    props.put("east", w);
                } else {
                    props.remove("east");
                }
                if (e != null) {
                    props.put("west", e);
                } else {
                    props.remove("west");
                }
            }
            if (props.containsKey("shape")) {
                props.put("shape", mirrorShapeFrontBack(props.get("shape")));
            }
            if (props.containsKey("rotation")) {
                int r16 = parseSmallNonNegInt(props.get("rotation"));
                if (r16 >= 0) {
                    props.put("rotation", Integer.toString((16 - (r16 & 15)) & 15));
                }
            }
        }

        int r = rotQuarterTurns & 3;
        for (int step = 0; step < r; step++) {
            if (props.containsKey("facing")) {
                props.put("facing", rotateDirCw(props.get("facing")));
            }
            if (props.containsKey("orientation")) {
                String[] parts = props.get("orientation").split("_", 2);
                if (parts.length == 2) {
                    props.put("orientation", rotateDirCw(parts[0]) + "_" + rotateDirCw(parts[1]));
                }
            }
            if (props.containsKey("axis")) {
                String ax = props.get("axis");
                if ("x".equals(ax)) {
                    props.put("axis", "z");
                } else if ("z".equals(ax)) {
                    props.put("axis", "x");
                }
            }
            if (props.containsKey("north")
                    || props.containsKey("east")
                    || props.containsKey("south")
                    || props.containsKey("west")) {
                String n = props.get("north");
                String e = props.get("east");
                String s = props.get("south");
                String w = props.get("west");
                putOrRemove(props, "east", n);
                putOrRemove(props, "south", e);
                putOrRemove(props, "west", s);
                putOrRemove(props, "north", w);
            }
            if (props.containsKey("shape")) {
                props.put("shape", rotateRailShapeCw(props.get("shape")));
            }
            if (props.containsKey("rotation")) {
                int r16 = parseSmallNonNegInt(props.get("rotation"));
                if (r16 >= 0) {
                    props.put("rotation", Integer.toString(((r16 & 15) + 4) & 15));
                }
            }
        }
        return formatCanonicalState(base, props);
    }

    private static int parseSmallNonNegInt(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 3) {
            return -1;
        }
        int acc = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch < '0' || ch > '9') {
                return -1;
            }
            acc = acc * 10 + (ch - '0');
        }
        return acc;
    }

    private static void putOrRemove(Map<String, String> map, String key, String val) {
        if (val != null) {
            map.put(key, val);
        } else {
            map.remove(key);
        }
    }

    private static String mirrorDirFrontBack(String dir) {
        return switch (dir) {
            case "east" -> "west";
            case "west" -> "east";
            default -> dir;
        };
    }

    private static String rotateDirCw(String dir) {
        return switch (dir) {
            case "north" -> "east";
            case "east" -> "south";
            case "south" -> "west";
            case "west" -> "north";
            default -> dir;
        };
    }

    private static String mirrorShapeFrontBack(String shape) {
        return switch (shape) {
            case "inner_left" -> "inner_right";
            case "inner_right" -> "inner_left";
            case "outer_left" -> "outer_right";
            case "outer_right" -> "outer_left";
            case "ascending_east" -> "ascending_west";
            case "ascending_west" -> "ascending_east";
            case "north_east" -> "north_west";
            case "north_west" -> "north_east";
            case "south_east" -> "south_west";
            case "south_west" -> "south_east";
            default -> shape;
        };
    }

    private static String rotateRailShapeCw(String shape) {
        return switch (shape) {
            case "north_south" -> "east_west";
            case "east_west" -> "north_south";
            case "ascending_north" -> "ascending_east";
            case "ascending_east" -> "ascending_south";
            case "ascending_south" -> "ascending_west";
            case "ascending_west" -> "ascending_north";
            case "north_east" -> "south_east";
            case "south_east" -> "south_west";
            case "south_west" -> "north_west";
            case "north_west" -> "north_east";
            default -> shape;
        };
    }

    private static String[] extractJigsawFrontTop(String origState, boolean mirrored, int rot) {
        String transformed = transformState(origState, mirrored, rot);
        int b = transformed.indexOf('[');
        String front = "north";
        String top = "up";
        if (b >= 0 && transformed.endsWith("]")) {
            String inner = transformed.substring(b + 1, transformed.length() - 1);
            for (String tok : inner.split(",")) {
                if (tok.startsWith("orientation=")) {
                    String[] parts = tok.substring("orientation=".length()).split("_", 2);
                    if (parts.length == 2) {
                        front = parts[0];
                        top = parts[1];
                    }
                } else if (tok.startsWith("facing=")) {
                    front = tok.substring("facing=".length());
                }
            }
        }
        return new String[] {front, top};
    }

    private static LcaDecoratorStamp.BlockCategory classifyStructureStateCategory(String canonicalState) {
        String base = extractBaseName(canonicalState);
        if (base.endsWith("_log") || base.endsWith("_wood") || base.endsWith("_stem") || base.endsWith("_planks")) {
            return LcaDecoratorStamp.BlockCategory.WOOD;
        }
        if (base.endsWith("_leaves")) {
            return LcaDecoratorStamp.BlockCategory.FOLIAGE;
        }
        return LcaDecoratorStamp.BlockCategory.DECORATOR;
    }

    private static String canonicalizeStateString(String rawState) {
        if (rawState == null || rawState.isBlank()) {
            return "minecraft:air";
        }
        int bracket = rawState.indexOf('[');
        if (bracket < 0 || !rawState.endsWith("]")) {
            return normalizeNamespacedId(rawState);
        }
        String base = normalizeNamespacedId(rawState.substring(0, bracket));
        String inner = rawState.substring(bracket + 1, rawState.length() - 1);
        TreeMap<String, String> props = new TreeMap<>();
        if (!inner.isEmpty()) {
            for (String tok : inner.split(",")) {
                int eq = tok.indexOf('=');
                if (eq > 0) {
                    props.put(tok.substring(0, eq).trim(), tok.substring(eq + 1).trim());
                }
            }
        }
        return formatCanonicalState(base, props);
    }

    private static String formatCanonicalState(String baseName, Map<String, String> sortedProps) {
        if (sortedProps == null || sortedProps.isEmpty()) {
            return baseName;
        }
        StringBuilder sb = new StringBuilder(baseName.length() + sortedProps.size() * 12);
        sb.append(baseName).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : sortedProps.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append(']');
        return sb.toString();
    }

    private static String extractBaseName(String state) {
        int b = state.indexOf('[');
        return b >= 0 ? state.substring(0, b) : state;
    }

    private static String normalizeNamespacedId(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    private static String getStringTag(Map<String, Object> nbt, String key, String fallback) {
        if (nbt == null) {
            return fallback;
        }
        Object v = nbt.get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    private static byte[] maybeGunzip(String templateId, byte[] bytes) throws IOException {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return gis.readAllBytes();
            } catch (IOException ex) {
                throw new IOException("Corrupt GZIP stream in structure .nbt '" + templateId + "'", ex);
            }
        }
        return bytes;
    }

    private static List<Object> extractListItems(Object obj, String fieldName, String templateId) throws IOException {
        if (obj instanceof Nbt.NbtList nbtList) {
            return nbtList.items;
        }
        if (obj instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        throw new IOException("Missing or invalid TAG_List '" + fieldName + "' in structure '" + templateId + "'");
    }

    private static int[] parseIntTriple(Object obj, String fieldName, String templateId) throws IOException {
        if (obj instanceof int[] arr && arr.length == 3) {
            return arr;
        }
        List<Object> items = extractListItems(obj, fieldName, templateId);
        if (items.size() != 3) {
            throw new IOException("Expected 3 integers in '" + fieldName + "' for '" + templateId + "'");
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!(items.get(i) instanceof Number num)) {
                throw new IOException("Non-numeric coordinate in '" + fieldName + "' for '" + templateId + "'");
            }
            out[i] = num.intValue();
        }
        return out;
    }

    private static TemplateVariantSet buildBuiltinTemplateUncached(String normId) {
        String shortId = normId.startsWith("minecraft:") ? normId.substring("minecraft:".length()) : normId;
        return switch (shortId) {
            case "buried_treasure" -> buildRoomTemplate(
                    normId, 3, 3, 3, "minecraft:sandstone", "minecraft:cut_sandstone",
                    "minecraft:chest[facing=north,waterlogged=false]", "minecraft:chests/buried_treasure",
                    LcaStructureStamp.TerrainProjection.RIGID, false, List.of(), List.of());
            case "igloo" -> buildRoomTemplate(
                    normId, 7, 5, 8, "minecraft:snow_block", "minecraft:white_carpet",
                    "minecraft:chest[facing=south]", "minecraft:chests/igloo_chest",
                    LcaStructureStamp.TerrainProjection.RIGID, true, List.of(), List.of());
            case "swamp_hut" -> buildRoomTemplate(
                    normId, 7, 7, 9, "minecraft:spruce_planks", "minecraft:oak_log[axis=y]",
                    "minecraft:cauldron", "",
                    LcaStructureStamp.TerrainProjection.RIGID, true, List.of(), List.of());
            case "desert_pyramid" -> buildRoomTemplate(
                    normId, 21, 15, 21, "minecraft:sandstone", "minecraft:cut_sandstone",
                    "minecraft:chest[facing=north]", "minecraft:chests/desert_pyramid",
                    LcaStructureStamp.TerrainProjection.RIGID, true, List.of(), List.of());
            case "jungle_temple" -> buildStoneBrickRoomTemplate(
                    normId, 12, 10, 15, "minecraft:chests/jungle_temple",
                    LcaStructureStamp.TerrainProjection.RIGID);
            case "pillager_outpost" -> buildRoomTemplate(
                    normId, 15, 18, 15, "minecraft:dark_oak_planks", "minecraft:cobblestone",
                    "minecraft:chest[facing=east]", "minecraft:chests/pillager_outpost",
                    LcaStructureStamp.TerrainProjection.RIGID, true,
                    List.of(new LcaStructureStamp.StructureProcessorRule(2, 3, 0.30f, 0x51A7L, true)),
                    List.of("minecraft:mossy_cobblestone"));
            case "shipwreck", "shipwreck_beached" -> buildRoomTemplate(
                    normId, 9, 7, 18, "minecraft:oak_planks", "minecraft:spruce_log[axis=z]",
                    "minecraft:chest[facing=north,waterlogged=false]", "minecraft:chests/shipwreck_treasure",
                    LcaStructureStamp.TerrainProjection.RIGID, false, List.of(), List.of());
            case "ocean_ruin_cold", "ocean_ruin_warm" -> buildStoneBrickRoomTemplate(
                    normId, 8, 6, 8, "minecraft:chests/underwater_ruin_small",
                    LcaStructureStamp.TerrainProjection.RIGID);
            case "ruined_portal", "ruined_portal_ocean", "ruined_portal_mountain", "ruined_portal_desert" ->
                    buildPortalTemplate(normId);
            case "mineshaft", "mineshaft_mesa" -> buildJigsawHallTemplate(
                    normId, 9, 5, 5,
                    shortId.contains("mesa") ? "minecraft:dark_oak_planks" : "minecraft:oak_planks",
                    "minecraft:rail[shape=east_west]",
                    "minecraft:mob_spawner", "minecraft:cave_spider",
                    LcaStructureStamp.TerrainProjection.RIGID,
                    "minecraft:mineshaft/corridor", 2);
            case "village_plains", "village_plains/town_center" -> buildJigsawHallTemplate(
                    normId, 11, 6, 11, "minecraft:cobblestone", "minecraft:dirt_path",
                    "minecraft:bell[facing=north]", "",
                    LcaStructureStamp.TerrainProjection.TERRAIN_MATCHING,
                    "minecraft:village/plains/streets", 4);
            case "village/plains/streets", "village_plains/street_straight" -> buildJigsawHallTemplate(
                    normId, 12, 3, 5, "minecraft:dirt_path", "minecraft:grass_block",
                    "", "",
                    LcaStructureStamp.TerrainProjection.TERRAIN_MATCHING,
                    "minecraft:village/plains/houses", 2);
            case "village/plains/houses", "village_plains/house_small" -> buildRoomTemplate(
                    normId, 7, 6, 7, "minecraft:oak_planks", "minecraft:cobblestone",
                    "minecraft:chest[facing=south]", "minecraft:chests/village/village_plains_house",
                    LcaStructureStamp.TerrainProjection.RIGID, true, List.of(), List.of());
            case "trial_chambers", "trial_chambers/atrium" -> buildJigsawHallTemplate(
                    normId, 15, 9, 15, "minecraft:tuff_bricks", "minecraft:chiseled_copper",
                    "minecraft:vault[facing=north]", "minecraft:chests/trial_chambers/reward",
                    LcaStructureStamp.TerrainProjection.RIGID,
                    "minecraft:trial_chambers/corridor", 4);
            case "trial_chambers/corridor", "trial_chambers/chamber" -> buildJigsawHallTemplate(
                    normId, 13, 7, 9, "minecraft:polished_tuff", "minecraft:copper_grate[waterlogged=false]",
                    "minecraft:trial_spawner", "minecraft:breeze",
                    LcaStructureStamp.TerrainProjection.RIGID,
                    "minecraft:trial_chambers/chamber", 2);
            case "ancient_city", "ancient_city/city_center" -> buildJigsawHallTemplate(
                    normId, 17, 10, 17, "minecraft:deepslate_bricks", "minecraft:sculk",
                    "minecraft:chest[facing=north]", "minecraft:chests/ancient_city",
                    LcaStructureStamp.TerrainProjection.RIGID,
                    "minecraft:ancient_city/walkway", 4);
            case "ancient_city/walkway", "ancient_city/barracks" -> buildStoneBrickRoomTemplate(
                    normId, 13, 6, 9, "minecraft:chests/ancient_city",
                    LcaStructureStamp.TerrainProjection.RIGID);
            case "trail_ruins" -> buildRoomTemplate(
                    normId, 9, 6, 9, "minecraft:mud_bricks", "minecraft:terracotta",
                    "minecraft:suspicious_gravel", "minecraft:archaeology/trail_ruins_common",
                    LcaStructureStamp.TerrainProjection.RIGID, false,
                    List.of(new LcaStructureStamp.StructureProcessorRule(1, 3, 0.25f, 0x7A11L, true)),
                    List.of("minecraft:suspicious_gravel"));
            default -> buildStoneBrickRoomTemplate(
                    normId, 9, 6, 9, "minecraft:chests/simple_dungeon",
                    LcaStructureStamp.TerrainProjection.RIGID);
        };
    }

    private static TemplateVariantSet buildStoneBrickRoomTemplate(
            String id,
            int sx,
            int sy,
            int sz,
            String lootTable,
            LcaStructureStamp.TerrainProjection proj) {
        List<LcaStructureStamp.StructureProcessorRule> rules = List.of(
                new LcaStructureStamp.StructureProcessorRule(1, 4, 0.30f, 0x3055L, true),
                new LcaStructureStamp.StructureProcessorRule(1, 5, 0.20f, 0xC2AC4L, true));
        return buildRoomTemplate(
                id,
                sx,
                sy,
                sz,
                "minecraft:stone_bricks",
                "minecraft:chiseled_stone_bricks",
                "minecraft:chest[facing=north,waterlogged=false]",
                lootTable,
                proj,
                true,
                rules,
                List.of("minecraft:mossy_stone_bricks", "minecraft:cracked_stone_bricks"));
    }

    private static TemplateVariantSet buildPortalTemplate(String id) {
        List<String> pal = List.of(
                "minecraft:structure_void",
                "minecraft:netherrack",
                "minecraft:stone_bricks",
                "minecraft:obsidian",
                "minecraft:crying_obsidian",
                "minecraft:chest[facing=south,waterlogged=false]");
        List<RawBlockEntry> blocks = new ArrayList<>();
        int sx = 7;
        int sy = 7;
        int sz = 7;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if (y == 0) {
                        boolean corner = (x == 0 || x == sx - 1) && (z == 0 || z == sz - 1);
                        int floorStateIdx;
                        if (corner) {
                            floorStateIdx = 0;
                        } else if ((x + z) % 2 == 0) {
                            floorStateIdx = 1;
                        } else {
                            floorStateIdx = 2;
                        }
                        blocks.add(new RawBlockEntry(x, y, z, floorStateIdx, null));
                    } else if (z == 3 && x >= 2 && x <= 4 && y <= 5) {
                        boolean frame = (x == 2 || x == 4 || y == 1 || y == 5);
                        if (frame && !(x == 4 && y == 5)) {
                            blocks.add(new RawBlockEntry(x, y, z, (x + y) % 3 == 0 ? 4 : 3, null));
                        }
                    } else if (x == 1 && y == 1 && z == 1) {
                        Map<String, Object> be = new LinkedHashMap<>();
                        be.put("id", "minecraft:chest");
                        be.put("LootTable", "minecraft:chests/ruined_portal");
                        blocks.add(new RawBlockEntry(x, y, z, 5, be));
                    }
                }
            }
        }
        List<LcaStructureStamp.StructureProcessorRule> rules = List.of(
                new LcaStructureStamp.StructureProcessorRule(3, 4, 0.25f, 0x9027A1L, true));
        return bakeAllVariants(id, sx, sy, sz, pal, blocks, LcaStructureStamp.TerrainProjection.RIGID, rules);
    }

    private static TemplateVariantSet buildRoomTemplate(
            String id,
            int sx,
            int sy,
            int sz,
            String wallState,
            String trimState,
            String centerBlockState,
            String lootTable,
            LcaStructureStamp.TerrainProjection proj,
            boolean carveInteriorAir,
            List<LcaStructureStamp.StructureProcessorRule> rules,
            List<String> extraPalette) {
        List<String> pal = new ArrayList<>();
        pal.add("minecraft:structure_void"); // 0
        pal.add(canonicalizeStateString(wallState)); // 1
        pal.add(canonicalizeStateString(trimState)); // 2
        pal.add("minecraft:air"); // 3
        for (String ex : extraPalette) {
            pal.add(canonicalizeStateString(ex));
        }
        int centerIdx = -1;
        if (centerBlockState != null && !centerBlockState.isBlank()) {
            pal.add(canonicalizeStateString(centerBlockState));
            centerIdx = pal.size() - 1;
        }

        List<RawBlockEntry> blocks = new ArrayList<>(sx * sy * sz);
        int cx = sx / 2;
        int cz = sz / 2;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    boolean outerCorner = (x == 0 || x == sx - 1) && (z == 0 || z == sz - 1) && y == sy - 1;
                    if (outerCorner) {
                        blocks.add(new RawBlockEntry(x, y, z, 0, null));
                        continue;
                    }
                    boolean shell = (y == 0 || y == sy - 1 || x == 0 || x == sx - 1 || z == 0 || z == sz - 1);
                    if (shell) {
                        boolean doorway = (z == 0 && x == cx && (y == 1 || y == 2));
                        if (doorway) {
                            if (carveInteriorAir) {
                                blocks.add(new RawBlockEntry(x, y, z, 3, null));
                            }
                        } else {
                            boolean edgeTrim = (y == 0 && (x == cx || z == cz));
                            blocks.add(new RawBlockEntry(x, y, z, edgeTrim ? 2 : 1, null));
                        }
                    } else if (x == cx && z == cz && y == 1 && centerIdx >= 0) {
                        Map<String, Object> be = null;
                        String baseCenter = extractBaseName(pal.get(centerIdx));
                        if (lootTable != null && !lootTable.isEmpty()) {
                            be = new LinkedHashMap<>();
                            be.put("id", baseCenter);
                            be.put("LootTable", lootTable);
                        }
                        blocks.add(new RawBlockEntry(x, y, z, centerIdx, be));
                    } else if (carveInteriorAir) {
                        blocks.add(new RawBlockEntry(x, y, z, 3, null));
                    }
                }
            }
        }
        return bakeAllVariants(id, sx, sy, sz, pal, blocks, proj, rules);
    }

    private static TemplateVariantSet buildJigsawHallTemplate(
            String id,
            int sx,
            int sy,
            int sz,
            String floorWallState,
            String accentState,
            String featureBlockState,
            String lootOrEntityId,
            LcaStructureStamp.TerrainProjection proj,
            String childPool,
            int connectorSides) {
        List<String> pal = new ArrayList<>();
        pal.add("minecraft:structure_void"); // 0
        pal.add(canonicalizeStateString(floorWallState)); // 1
        pal.add(canonicalizeStateString(accentState)); // 2
        pal.add("minecraft:air"); // 3
        pal.add("minecraft:jigsaw[orientation=north_up]"); // 4
        pal.add("minecraft:jigsaw[orientation=south_up]"); // 5
        pal.add("minecraft:jigsaw[orientation=east_up]"); // 6
        pal.add("minecraft:jigsaw[orientation=west_up]"); // 7
        int featIdx = -1;
        if (featureBlockState != null && !featureBlockState.isBlank()) {
            pal.add(canonicalizeStateString(featureBlockState));
            featIdx = pal.size() - 1;
        }

        boolean terrainMatching = (proj == LcaStructureStamp.TerrainProjection.TERRAIN_MATCHING);
        List<RawBlockEntry> blocks = new ArrayList<>(sx * sy * sz);
        int cx = sx / 2;
        int cz = sz / 2;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if (y == 0) {
                        boolean centerStrip = (Math.abs(x - cx) <= 1 || Math.abs(z - cz) <= 1);
                        blocks.add(new RawBlockEntry(x, y, z, centerStrip ? 2 : 1, null));
                    } else if (y == 1 && z == 0 && x == cx && connectorSides >= 1) {
                        blocks.add(new RawBlockEntry(x, y, z, 4, jigsawNbt(childPool, "minecraft:air")));
                    } else if (y == 1 && z == sz - 1 && x == cx && connectorSides >= 2) {
                        blocks.add(new RawBlockEntry(x, y, z, 5, jigsawNbt(childPool, "minecraft:air")));
                    } else if (y == 1 && x == sx - 1 && z == cz && connectorSides >= 3) {
                        blocks.add(new RawBlockEntry(x, y, z, 6, jigsawNbt(childPool, "minecraft:air")));
                    } else if (y == 1 && x == 0 && z == cz && connectorSides >= 4) {
                        blocks.add(new RawBlockEntry(x, y, z, 7, jigsawNbt(childPool, "minecraft:air")));
                    } else if (y == 1 && x == cx && z == cz && featIdx >= 0) {
                        Map<String, Object> be = null;
                        if (lootOrEntityId != null && !lootOrEntityId.isEmpty()) {
                            be = new LinkedHashMap<>();
                            String base = extractBaseName(pal.get(featIdx));
                            be.put("id", base);
                            if (lootOrEntityId.contains("chests/") || lootOrEntityId.contains("archaeology/")) {
                                be.put("LootTable", lootOrEntityId);
                            } else {
                                be.put("config", lootOrEntityId);
                            }
                        }
                        blocks.add(new RawBlockEntry(x, y, z, featIdx, be));
                    } else if (!terrainMatching
                            && (y == sy - 1 || x == 0 || x == sx - 1 || z == 0 || z == sz - 1)) {
                        boolean archOpen = (y <= 3 && (x == cx || z == cz));
                        blocks.add(new RawBlockEntry(x, y, z, archOpen ? 3 : 1, null));
                    } else {
                        blocks.add(new RawBlockEntry(x, y, z, 3, null));
                    }
                }
            }
        }
        return bakeAllVariants(id, sx, sy, sz, pal, blocks, proj, List.of());
    }

    private static Map<String, Object> jigsawNbt(String pool, String finalState) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "minecraft:jigsaw");
        m.put("name", "minecraft:connector");
        m.put("target", "minecraft:connector");
        m.put("pool", pool);
        m.put("final_state", finalState);
        m.put("joint", "aligned");
        return m;
    }
}
