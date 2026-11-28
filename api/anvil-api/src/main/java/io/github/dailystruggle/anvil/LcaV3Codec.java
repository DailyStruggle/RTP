package io.github.dailystruggle.anvil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Package-private `.lca` Format Version `0x0003` (`LCA-ICD-3.0`) codec primitives:
 * frozen `LCA_LIGHT_TABLE_V1`, bounded fixed-point Eikonal BFS with `ADR-005` `B1`
 * `64-bit` octant-bitmask packed 4-bit residual coding, `boilerplateFlags16` bit `15`
 * columnar `block_ticks`/`fluid_ticks` & template-deduplicated `block_entities` streams,
 * per-`SectionFrame` adaptive successive rank-exclusion `Delta-Roaring`, and non-mutating
 * `ADR-005` `D` (`litLen[b]`) `Mode B` cost estimation.
 *
 * @governedBy anvil-api-ADR-004
 * @governedBy anvil-api-ADR-005
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
final class LcaV3Codec {

    static final int SECTION_VOXELS = 4096;

    // v3 SectionFrame extension flags (paletteLen == 0 escape)
    static final int SEC_EXT_RANK_EXCLUDED = 0x01;
    static final int SEC_EXT_GRAY_BITPLANES = 0x02;
    static final int SEC_EXT_VALID_MASK = SEC_EXT_RANK_EXCLUDED | SEC_EXT_GRAY_BITPLANES;
    static final int MIN_RANK_EXCL_PALETTE = 3;
    static final int MAX_RANK_EXCL_PALETTE = 12;
    static final int MIN_GRAY_PALETTE = 3;
    static final int MAX_GRAY_PALETTE = 64;

    static boolean isValidSecExtFlags(int secExtFlags) {
        return (secExtFlags & ~SEC_EXT_VALID_MASK) == 0
                && (secExtFlags & SEC_EXT_VALID_MASK) != SEC_EXT_VALID_MASK;
    }

    // v3 Eikonal light residual sentinel & sub-modes
    static final int SENTINEL_MODE_MASK = 0x03;
    static final int SENTINEL_MODE_EIKONAL = 0x02;
    static final int PREDICTOR_BLOCK_LIGHT = 0;
    static final int PREDICTOR_SKY_OPEN = 1;
    static final int PREDICTOR_SKY_SEEDED = 2;
    static final int PREDICTOR_TABLE_V1 = 0;
    static final int RESIDUAL_MODE_B1_OCTANT_PACKED4 = 0x00;

    // v3 boilerplateFlags16 bit 15 (0x8000) extended residual flags
    static final int BP_FLAG_HAS_EXTENDED_FLAGS = 0x8000;
    static final int EXT_FLAG_COLUMNAR_BLOCK_TICKS = 0x01;
    static final int EXT_FLAG_COLUMNAR_FLUID_TICKS = 0x02;
    static final int EXT_FLAG_COLUMNAR_BLOCK_ENTITIES = 0x04;
    static final int EXT_FLAG_SPARSE_POST_PROCESSING = 0x08;
    static final int EXT_FLAG_DICT_STRUCTURES = 0x10;
    static final int EXT_FLAG_RESERVED_MASK = ~0x1F;
    static final int MAX_TICKS_PER_CHUNK = 65_536;
    static final int MAX_BLOCK_ENTITIES_PER_CHUNK = 16_384;
    static final int MAX_PP_SECTIONS = 255;
    static final int MAX_PP_SHORTS_PER_SECTION = 4096;
    static final int MAX_STRUCT_LOCAL_STRINGS = 65_536;
    static final int MAX_STRUCT_NBT_DEPTH = 64;

    // Feature mask for clean v3 profiling/testing:
    // Default enables Columnar Tail in Mode A & Mode B, plus Eikonal+B1 in Mode B (keeping Mode A ultra-fast for live saves).
    // RankExcl stays 100% supported on decode and opt-in for archival compaction.
    static final int FEATURE_EIKONAL_LIGHT = 1 << 0;
    static final int FEATURE_COLUMNAR_TAIL = 1 << 1;
    static final int FEATURE_RANK_EXCL = 1 << 2;
    static final int FEATURE_EIKONAL_MODE_A = 1 << 3;
    static final int FEATURE_GRAY_BITPLANES = 1 << 4;
    static final int DEFAULT_V3_FEATURES = FEATURE_EIKONAL_LIGHT | FEATURE_COLUMNAR_TAIL;
    static final int ALL_V3_FEATURES = FEATURE_EIKONAL_LIGHT
            | FEATURE_COLUMNAR_TAIL
            | FEATURE_RANK_EXCL
            | FEATURE_EIKONAL_MODE_A
            | FEATURE_GRAY_BITPLANES;

    @SuppressWarnings("java:S5164")
    private static final ThreadLocal<Integer> THREAD_FEATURE_MASK = ThreadLocal.withInitial(() -> DEFAULT_V3_FEATURES);

    @SuppressWarnings("java:S5164")
    private static final ThreadLocal<SoftReference<V3Scratch>> V3_SCRATCH = new ThreadLocal<>();

    private static final byte[] MINECRAFT_PREFIX_BYTES = "minecraft:".getBytes(StandardCharsets.UTF_8);

    private LcaV3Codec() {}

    static int currentFeatureMask() {
        return THREAD_FEATURE_MASK.get();
    }

    static void setThreadFeatureMask(int mask) {
        THREAD_FEATURE_MASK.set(mask & ALL_V3_FEATURES);
    }

    static void resetThreadFeatureMask() {
        THREAD_FEATURE_MASK.remove();
    }

    static V3Scratch v3Scratch() {
        SoftReference<V3Scratch> ref = V3_SCRATCH.get();
        V3Scratch scratch = ref != null ? ref.get() : null;
        if (scratch == null) {
            scratch = new V3Scratch();
            V3_SCRATCH.set(new SoftReference<>(scratch));
        }
        return scratch;
    }

    static void clearThreadLocalScratch() {
        V3_SCRATCH.remove();
        THREAD_FEATURE_MASK.remove();
    }

    static final class V3Scratch {
        final byte[] hatL = new byte[SECTION_VOXELS];
        final byte[] voxProps = new byte[SECTION_VOXELS];
        final byte[] palPackedLight = new byte[SECTION_VOXELS];
        final byte[] topFace256 = new byte[256];
        final int[] ring = new int[SECTION_VOXELS];
        final long[] inQueue = new long[64];
        final byte[] lightStaging = new byte[4096];
        final byte[] lightStagingAlt = new byte[4096];
        final long[] b1OctVoxMasks = new long[64];
        final byte[] b1NzNibbles = new byte[SECTION_VOXELS];
        int[] domainA;
        int[] domainB;
        byte[] claimed;
        byte[] rankStaging;
        int[] unpackedYzx;
        int[] grayAdjMatrix;
        int[] grayOldToNew;
        int[] grayNewToOld;
        boolean[] grayUsed;
        int[] grayRemappedGlobalIds;
        int[] grayCurveVals;
        int[] grayRunStaging;
        int[] grayRunStagingAlt;
        byte[] grayStaging;

        void ensureRankBuffers() {
            if (domainA == null) {
                domainA = new int[SECTION_VOXELS];
                domainB = new int[SECTION_VOXELS];
                claimed = new byte[SECTION_VOXELS];
                rankStaging = new byte[16384];
                unpackedYzx = new int[SECTION_VOXELS];
            }
        }

        void ensureGrayBuffers() {
            if (grayAdjMatrix == null) {
                grayAdjMatrix = new int[MAX_GRAY_PALETTE * MAX_GRAY_PALETTE];
                grayOldToNew = new int[MAX_GRAY_PALETTE];
                grayNewToOld = new int[MAX_GRAY_PALETTE];
                grayUsed = new boolean[MAX_GRAY_PALETTE];
                grayRemappedGlobalIds = new int[MAX_GRAY_PALETTE];
                grayCurveVals = new int[SECTION_VOXELS];
                grayRunStaging = new int[SECTION_VOXELS];
                grayRunStagingAlt = new int[SECTION_VOXELS];
                grayStaging = new byte[16384];
            }
        }
    }

    // =========================================================================
    // 1. Frozen LCA_LIGHT_TABLE_V1 (predictorTableId3 == 0)
    // =========================================================================

    /**
     * Normative `LCA_LIGHT_TABLE_V1` classifier (`ICD.md` §9.4): maps a canonical Mojang
     * block state string to packed `(opacity4 << 4) | (emission4 & 0x0F)`.
     * Explicitly classifies `minecraft:tinted_glass` as opacity `15` and avoids loose wildcard matches.
     */
    static byte classifyLightV1(String canonical) {
        if (canonical == null || canonical.isEmpty()) {
            return (byte) (15 << 4);
        }
        int bracket = canonical.indexOf('[');
        String base = bracket < 0 ? canonical : canonical.substring(0, bracket);

        int emit = 0;
        switch (base) {
            case "minecraft:torch", "minecraft:wall_torch", "minecraft:end_rod" -> emit = 14;
            case "minecraft:lantern", "minecraft:glowstone", "minecraft:sea_lantern",
                    "minecraft:shroomlight", "minecraft:jack_o_lantern", "minecraft:lava",
                    "minecraft:fire", "minecraft:ochre_froglight", "minecraft:verdant_froglight",
                    "minecraft:pearlescent_froglight", "minecraft:beacon", "minecraft:conduit",
                    "minecraft:lava_cauldron", "minecraft:respawn_anchor" -> emit = 15;
            case "minecraft:soul_torch", "minecraft:soul_wall_torch", "minecraft:soul_lantern",
                    "minecraft:soul_fire", "minecraft:crying_obsidian" -> emit = 10;
            case "minecraft:campfire" -> emit = hasProperty(canonical, bracket, "lit=false") ? 0 : 15;
            case "minecraft:soul_campfire" -> emit = hasProperty(canonical, bracket, "lit=false") ? 0 : 10;
            case "minecraft:glow_lichen" -> emit = 7;
            case "minecraft:magma_block" -> emit = 3;
            case "minecraft:enchanting_table", "minecraft:ender_chest" -> emit = 7;
            case "minecraft:amethyst_cluster" -> emit = 5;
            case "minecraft:large_amethyst_bud" -> emit = 4;
            case "minecraft:medium_amethyst_bud" -> emit = 2;
            case "minecraft:small_amethyst_bud" -> emit = 1;
            case "minecraft:brewing_stand", "minecraft:brown_mushroom" -> emit = 1;
            case "minecraft:redstone_torch", "minecraft:redstone_wall_torch" ->
                    emit = hasProperty(canonical, bracket, "lit=false") ? 0 : 7;
            case "minecraft:redstone_lamp", "minecraft:furnace", "minecraft:blast_furnace",
                    "minecraft:smoker" -> emit = hasProperty(canonical, bracket, "lit=true") ? 13 : 0;
            case "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore" ->
                    emit = hasProperty(canonical, bracket, "lit=true") ? 9 : 0;
            case "minecraft:cave_vines", "minecraft:cave_vines_plant" ->
                    emit = hasProperty(canonical, bracket, "berries=true") ? 14 : 0;
            default -> emit = 0;
        }

        int opac;
        if (base.equals("minecraft:tinted_glass")) {
            opac = 15;
        } else if (isTransparentV1(base)) {
            opac = 0;
        } else if (isTranslucentV1(base)) {
            opac = 1;
        } else {
            opac = 15;
        }
        return (byte) ((opac << 4) | (emit & 0x0F));
    }

    private static boolean hasProperty(String canonical, int bracket, String propEqVal) {
        if (bracket < 0) {
            return false;
        }
        int idx = canonical.indexOf(propEqVal, bracket + 1);
        if (idx < 0) {
            return false;
        }
        char before = canonical.charAt(idx - 1);
        int end = idx + propEqVal.length();
        char after = end < canonical.length() ? canonical.charAt(end) : ']';
        return (before == '[' || before == ',') && (after == ',' || after == ']');
    }

    private static boolean isTransparentV1(String base) {
        switch (base) {
            case "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
                    "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern",
                    "minecraft:large_fern", "minecraft:torch", "minecraft:wall_torch",
                    "minecraft:soul_torch", "minecraft:soul_wall_torch", "minecraft:redstone_torch",
                    "minecraft:redstone_wall_torch", "minecraft:lantern", "minecraft:soul_lantern",
                    "minecraft:glass", "minecraft:glass_pane", "minecraft:vine",
                    "minecraft:glow_lichen", "minecraft:snow", "minecraft:rail",
                    "minecraft:powered_rail", "minecraft:detector_rail", "minecraft:activator_rail",
                    "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
                    "minecraft:allium", "minecraft:azure_bluet", "minecraft:oxeye_daisy",
                    "minecraft:cornflower", "minecraft:lily_of_the_valley", "minecraft:wither_rose",
                    "minecraft:sugar_cane", "minecraft:seagrass", "minecraft:tall_seagrass",
                    "minecraft:kelp", "minecraft:kelp_plant", "minecraft:dead_bush",
                    "minecraft:flower_pot", "minecraft:ladder", "minecraft:fire",
                    "minecraft:soul_fire", "minecraft:end_rod", "minecraft:chain",
                    "minecraft:iron_bars", "minecraft:bamboo", "minecraft:bamboo_sapling",
                    "minecraft:cave_vines", "minecraft:cave_vines_plant", "minecraft:hanging_roots",
                    "minecraft:moss_carpet", "minecraft:pink_petals", "minecraft:leaf_litter",
                    "minecraft:wildflowers", "minecraft:bush", "minecraft:firefly_bush",
                    "minecraft:cactus_flower", "minecraft:short_dry_grass", "minecraft:tall_dry_grass",
                    "minecraft:redstone_wire", "minecraft:lever", "minecraft:tripwire",
                    "minecraft:tripwire_hook", "minecraft:repeater", "minecraft:comparator",
                    "minecraft:brown_mushroom", "minecraft:red_mushroom", "minecraft:sweet_berry_bush",
                    "minecraft:sunflower", "minecraft:lilac", "minecraft:rose_bush",
                    "minecraft:peony", "minecraft:big_dripleaf", "minecraft:big_dripleaf_stem",
                    "minecraft:small_dripleaf", "minecraft:spore_blossom", "minecraft:amethyst_cluster",
                    "minecraft:large_amethyst_bud", "minecraft:medium_amethyst_bud",
                    "minecraft:small_amethyst_bud", "minecraft:pointed_dripstone",
                    "minecraft:lily_pad", "minecraft:scaffolding", "minecraft:barrier",
                    "minecraft:light", "minecraft:structure_void" -> {
                return true;
            }
            default -> {
                return base.endsWith("_stained_glass")
                        || base.endsWith("_stained_glass_pane")
                        || base.endsWith("_fence")
                        || base.endsWith("_fence_gate")
                        || base.endsWith("_button")
                        || base.endsWith("_pressure_plate")
                        || base.endsWith("_sign")
                        || base.endsWith("_wall_sign")
                        || base.endsWith("_hanging_sign")
                        || base.endsWith("_carpet")
                        || base.endsWith("_sapling")
                        || base.endsWith("_tulip")
                        || base.endsWith("_banner")
                        || base.endsWith("_wall_banner")
                        || base.endsWith("_candle")
                        || base.endsWith("_coral")
                        || base.endsWith("_coral_fan")
                        || base.endsWith("_wall_coral_fan");
            }
        }
    }

    private static boolean isTranslucentV1(String base) {
        return base.equals("minecraft:water")
                || base.equals("minecraft:ice")
                || base.equals("minecraft:frosted_ice")
                || base.endsWith("_leaves")
                || base.equals("minecraft:cobweb")
                || base.equals("minecraft:slime_block")
                || base.equals("minecraft:honey_block")
                || base.equals("minecraft:lava");
    }

    static byte[] buildStatePackedLightV1(List<String> canonicalStates) {
        int n = canonicalStates.size();
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = classifyLightV1(canonicalStates.get(i));
        }
        return out;
    }

    // =========================================================================
    // 2. Bounded Fixed-Point Eikonal BFS + ADR-005 B1 Octant-Bitmask Residuals
    // =========================================================================

    static void predictBlockLightEikonal(byte[] voxLightProps, byte[] hatL, int[] ring, long[] inQueue) {
        Arrays.fill(hatL, (byte) 0);
        Arrays.fill(inQueue, 0L);
        int head = 0;
        int tail = 0;
        for (int idx = 0; idx < SECTION_VOXELS; idx++) {
            int emit = voxLightProps[idx] & 0x0F;
            if (emit > 0) {
                hatL[idx] = (byte) emit;
                if (emit > 1) {
                    ring[tail] = idx;
                    tail = (tail + 1) & 4095;
                    inQueue[idx >>> 6] |= (1L << (idx & 63));
                }
            }
        }
        relaxEikonal6Connected(voxLightProps, hatL, ring, inQueue, head, tail);
    }

    static void predictSkyLightEikonal(
            byte[] voxLightProps,
            boolean openSky,
            byte[] topFace256,
            byte[] hatL,
            int[] ring,
            long[] inQueue
    ) {
        Arrays.fill(hatL, (byte) 0);
        Arrays.fill(inQueue, 0L);
        int head = 0;
        int tail = 0;
        for (int z = 0; z < 16; z++) {
            int zOff = z << 4;
            for (int x = 0; x < 16; x++) {
                int topIdx = (15 << 8) | zOff | x;
                int cur;
                if (openSky) {
                    int opTop = (voxLightProps[topIdx] >>> 4) & 0x0F;
                    cur = (opTop == 0) ? 15 : 0;
                } else {
                    cur = topFace256[zOff | x] & 0x0F;
                }
                hatL[topIdx] = (byte) cur;
                if (cur > 1) {
                    ring[tail] = topIdx;
                    tail = (tail + 1) & 4095;
                    inQueue[topIdx >>> 6] |= (1L << (topIdx & 63));
                }
                for (int y = 14; y >= 0 && cur > 0; y--) {
                    int idx = (y << 8) | zOff | x;
                    int op = (voxLightProps[idx] >>> 4) & 0x0F;
                    if (op >= 15) {
                        cur = 0;
                    } else if (op > 0) {
                        cur = Math.max(0, cur - op);
                    }
                    if (cur > 0) {
                        hatL[idx] = (byte) cur;
                        if (cur > 1) {
                            ring[tail] = idx;
                            tail = (tail + 1) & 4095;
                            inQueue[idx >>> 6] |= (1L << (idx & 63));
                        }
                    }
                }
            }
        }
        relaxEikonal6Connected(voxLightProps, hatL, ring, inQueue, head, tail);
    }

    private static void relaxEikonal6Connected(
            byte[] voxLightProps, byte[] hatL, int[] ring, long[] inQueue, int head, int tail
    ) {
        while (head != tail) {
            int cur = ring[head];
            head = (head + 1) & 4095;
            inQueue[cur >>> 6] &= ~(1L << (cur & 63));
            int lv = hatL[cur] & 0x0F;
            if (lv <= 1) {
                continue;
            }
            int x = cur & 15;
            int z = (cur >>> 4) & 15;
            int y = cur >>> 8;
            if (x > 0) tail = tryRelax(cur - 1, lv, voxLightProps, hatL, ring, inQueue, tail);
            if (x < 15) tail = tryRelax(cur + 1, lv, voxLightProps, hatL, ring, inQueue, tail);
            if (z > 0) tail = tryRelax(cur - 16, lv, voxLightProps, hatL, ring, inQueue, tail);
            if (z < 15) tail = tryRelax(cur + 16, lv, voxLightProps, hatL, ring, inQueue, tail);
            if (y > 0) tail = tryRelax(cur - 256, lv, voxLightProps, hatL, ring, inQueue, tail);
            if (y < 15) tail = tryRelax(cur + 256, lv, voxLightProps, hatL, ring, inQueue, tail);
        }
    }

    private static int tryRelax(
            int nb, int lv, byte[] voxLightProps, byte[] hatL, int[] ring, long[] inQueue, int tail
    ) {
        int op = (voxLightProps[nb] >>> 4) & 0x0F;
        if (op >= 15) {
            return tail;
        }
        int step = op > 1 ? op : 1;
        int cand = lv - step;
        if (cand > (hatL[nb] & 0x0F)) {
            hatL[nb] = (byte) cand;
            if (cand > 1) {
                int w = nb >>> 6;
                long bit = 1L << (nb & 63);
                if ((inQueue[w] & bit) == 0L) {
                    inQueue[w] |= bit;
                    ring[tail] = nb;
                    tail = (tail + 1) & 4095;
                }
            }
        }
        return tail;
    }

    /**
     * Encodes `B1` (`64-bit` octant mask + per-active-octant `64-bit` voxel mask + packed 4-bit
     * non-zero residuals `(actual - hatL) & 0x0F`) into `dst[pos..]`, returning the new cursor.
     */
    private static int encodeB1Residuals(
            byte[] actualNibbles, byte[] hatL, V3Scratch sc, byte[] dst, int pos
    ) {
        dst[pos++] = (byte) RESIDUAL_MODE_B1_OCTANT_PACKED4;
        long octMask = 0L;
        long[] octVoxMasks = sc.b1OctVoxMasks;
        byte[] nzNibbles = sc.b1NzNibbles;
        int nzCount = 0;

        for (int oct = 0; oct < 64; oct++) {
            int baseY = (oct >>> 4) << 2;
            int baseZ = ((oct >>> 2) & 3) << 2;
            int baseX = (oct & 3) << 2;
            long vMask = 0L;
            int bitIdx = 0;
            for (int dy = 0; dy < 4; dy++) {
                int yOff = (baseY + dy) << 8;
                for (int dz = 0; dz < 4; dz++) {
                    int yzOff = yOff | ((baseZ + dz) << 4) | baseX;
                    for (int dx = 0; dx < 4; dx++) {
                        int yzx = yzOff | dx;
                        int r = (actualNibbles[yzx] - hatL[yzx]) & 0x0F;
                        if (r != 0) {
                            vMask |= (1L << bitIdx);
                            nzNibbles[nzCount++] = (byte) r;
                        }
                        bitIdx++;
                    }
                }
            }
            if (vMask != 0L) {
                octMask |= (1L << oct);
                octVoxMasks[oct] = vMask;
            }
        }

        LcaSectionCodec.writeLongBEToBuf(dst, pos, octMask);
        pos += 8;
        long m = octMask;
        while (m != 0L) {
            int oct = Long.numberOfTrailingZeros(m);
            m &= (m - 1L);
            LcaSectionCodec.writeLongBEToBuf(dst, pos, octVoxMasks[oct]);
            pos += 8;
        }
        int i = 0;
        while (i + 1 < nzCount) {
            dst[pos++] = (byte) ((nzNibbles[i] & 0x0F) | ((nzNibbles[i + 1] & 0x0F) << 4));
            i += 2;
        }
        if (i < nzCount) {
            dst[pos++] = (byte) (nzNibbles[i] & 0x0F);
        }
        return pos;
    }

    /**
     * Encodes a `256`-entry top-face sky seed via compact nibble RLE (`0x80 | (runLen - 1)` for
     * repeated values `len >= 2`, or `(count - 1)` + packed raw nibbles), returning byte length.
     */
    private static int encodeTopFaceSeedRle(byte[] topFace256, byte[] dst, int pos) {
        int start = pos;
        int i = 0;
        while (i < 256) {
            int v = topFace256[i] & 0x0F;
            int run = 1;
            while (i + run < 256 && run < 16 && (topFace256[i + run] & 0x0F) == v) {
                run++;
            }
            // Pack as single byte: hi4 = (run - 1), lo4 = nibble value (0..15)
            dst[pos++] = (byte) (((run - 1) << 4) | v);
            i += run;
        }
        return pos - start;
    }

    private static int decodeTopFaceSeedRle(byte[] buf, int pos, int limit, byte[] outTop256)
            throws CorruptRegionEntryException {
        int idx = 0;
        while (idx < 256) {
            if (pos >= limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated v3 SkyLight top-face RLE seed");
            }
            int tok = buf[pos++] & 0xFF;
            int run = (tok >>> 4) + 1;
            byte val = (byte) (tok & 0x0F);
            if (idx + run > 256) {
                throw new CorruptRegionEntryException("CBIT T5: v3 SkyLight top-face RLE seed overflow");
            }
            Arrays.fill(outTop256, idx, idx + run, val);
            idx += run;
        }
        return pos;
    }

    /**
     * Attempts to encode a `v3` Eikonal + `B1` light channel into `dst[outPos..]` if and only if
     * the total framed size (`1 + varIntLen(payloadLen) + payloadLen`) is strictly smaller than
     * `baselineByteLen`. Returns the new `dst` cursor if emitted, or `-1` to keep baseline.
     */
    static int tryEncodeEikonalLightB1(
            boolean isSkyLight,
            byte[] actualNibbles,
            byte[] voxLightProps,
            int baselineByteLen,
            byte[] dst,
            int outPos
    ) {
        if ((currentFeatureMask() & FEATURE_EIKONAL_LIGHT) == 0 || voxLightProps == null) {
            return -1;
        }
        V3Scratch sc = v3Scratch();
        if (!isSkyLight) {
            predictBlockLightEikonal(voxLightProps, sc.hatL, sc.ring, sc.inQueue);
            int payLen = encodeB1Residuals(actualNibbles, sc.hatL, sc, sc.lightStaging, 0);
            int total = 1 + LcaSectionCodec.varIntLen(payLen) + payLen;
            if (total >= baselineByteLen) {
                return -1;
            }
            int sentinel = SENTINEL_MODE_EIKONAL | (PREDICTOR_BLOCK_LIGHT << 2) | (PREDICTOR_TABLE_V1 << 4);
            dst[outPos++] = (byte) sentinel;
            outPos = LcaSectionCodec.writeVarIntToBuf(dst, outPos, payLen);
            System.arraycopy(sc.lightStaging, 0, dst, outPos, payLen);
            return outPos + payLen;
        }

        // SkyLight: inspect top face y=15 first to pick open-sky (subMode 1) vs seeded (subMode 2) in a single BFS pass
        boolean differsFromOpen = false;
        for (int z = 0; z < 16; z++) {
            int zOff = z << 4;
            for (int x = 0; x < 16; x++) {
                int topIdx = (15 << 8) | zOff | x;
                byte actual = actualNibbles[topIdx];
                sc.topFace256[zOff | x] = actual;
                int expectedOpen = (((voxLightProps[topIdx] >>> 4) & 0x0F) == 0) ? 15 : 0;
                if ((actual & 0x0F) != expectedOpen) {
                    differsFromOpen = true;
                }
            }
        }

        int bestSentinel;
        byte[] bestPayload;
        int bestPayLen;
        if (!differsFromOpen) {
            predictSkyLightEikonal(voxLightProps, true, null, sc.hatL, sc.ring, sc.inQueue);
            bestPayLen = encodeB1Residuals(actualNibbles, sc.hatL, sc, sc.lightStaging, 0);
            bestSentinel = SENTINEL_MODE_EIKONAL | (PREDICTOR_SKY_OPEN << 2) | (PREDICTOR_TABLE_V1 << 4);
            bestPayload = sc.lightStaging;
        } else {
            predictSkyLightEikonal(voxLightProps, false, sc.topFace256, sc.hatL, sc.ring, sc.inQueue);
            byte[] alt = sc.lightStagingAlt;
            int rleLen = encodeTopFaceSeedRle(sc.topFace256, alt, 0);
            int topSeedMode;
            int seedLen;
            if (rleLen < 128) {
                topSeedMode = 0;
                seedLen = rleLen;
            } else {
                topSeedMode = 1;
                seedLen = 128;
                for (int i = 0; i < 128; i++) {
                    alt[i] = (byte) ((sc.topFace256[i << 1] & 0x0F) | ((sc.topFace256[(i << 1) | 1] & 0x0F) << 4));
                }
            }
            bestPayLen = encodeB1Residuals(actualNibbles, sc.hatL, sc, alt, seedLen);
            bestSentinel = SENTINEL_MODE_EIKONAL
                    | (PREDICTOR_SKY_SEEDED << 2)
                    | (PREDICTOR_TABLE_V1 << 4)
                    | (topSeedMode << 7);
            bestPayload = alt;
        }
        int bestTotal = 1 + LcaSectionCodec.varIntLen(bestPayLen) + bestPayLen;

        if (bestTotal >= baselineByteLen) {
            return -1;
        }
        dst[outPos++] = (byte) bestSentinel;
        outPos = LcaSectionCodec.writeVarIntToBuf(dst, outPos, bestPayLen);
        System.arraycopy(bestPayload, 0, dst, outPos, bestPayLen);
        return outPos + bestPayLen;
    }

    /**
     * $O(1)$ skip for a `v3` Eikonal light channel (`REQ-RTP-S-005`). Caller has already consumed
     * `sentinelHdr` at `pos - 1` and verified `formatVersion >= 0x0003` and `(sentinelHdr & 0x03) == 2`.
     */
    static int skipEikonalLightPayload(byte[] buf, int pos, int limit) throws CorruptRegionEntryException {
        int payloadByteLen = LcaSectionCodec.readVarInt(buf, pos, limit);
        pos += LcaSectionCodec.varIntLen(payloadByteLen);
        if (payloadByteLen < 1 || pos + payloadByteLen > limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated or invalid v3 Eikonal light payloadByteLen");
        }
        return pos + payloadByteLen;
    }

    /**
     * Decodes a `v3` Eikonal + `B1` light channel into `outNibbles[0..4096)` (where `sentinelHdr`
     * has already been read from `buf[pos - 1]`). Returns the cursor immediately after the payload.
     */
    static int decodeEikonalLightToNibbles(
            byte[] buf,
            int pos,
            int limit,
            int sentinelHdr,
            boolean isSkyLight,
            byte[] voxLightProps,
            byte[] outNibbles
    ) throws CorruptRegionEntryException {
        int subMode = (sentinelHdr >>> 2) & 3;
        int tableId = (sentinelHdr >>> 4) & 7;
        int topSeedMode = (sentinelHdr >>> 7) & 1;

        if (tableId != PREDICTOR_TABLE_V1) {
            throw new CorruptRegionEntryException("CBIT T5: Unsupported v3 Eikonal predictorTableId3 " + tableId);
        }
        if (subMode == 3) {
            throw new CorruptRegionEntryException("CBIT T5: Reserved v3 Eikonal predictorSubMode 3");
        }
        if (isSkyLight && subMode == PREDICTOR_BLOCK_LIGHT) {
            throw new CorruptRegionEntryException("CBIT T5: BlockLight Eikonal subMode 0 on SkyLight channel");
        }
        if (!isSkyLight && subMode != PREDICTOR_BLOCK_LIGHT) {
            throw new CorruptRegionEntryException("CBIT T5: SkyLight Eikonal subMode " + subMode + " on BlockLight channel");
        }
        if (subMode != PREDICTOR_SKY_SEEDED && topSeedMode != 0) {
            throw new CorruptRegionEntryException("CBIT T5: Non-zero topSeedMode on non-seeded Eikonal light channel");
        }

        int payloadByteLen = LcaSectionCodec.readVarInt(buf, pos, limit);
        pos += LcaSectionCodec.varIntLen(payloadByteLen);
        if (payloadByteLen < 1 || pos + payloadByteLen > limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated v3 Eikonal light payload");
        }
        int payEnd = pos + payloadByteLen;
        V3Scratch sc = v3Scratch();

        if (subMode == PREDICTOR_BLOCK_LIGHT) {
            predictBlockLightEikonal(voxLightProps, sc.hatL, sc.ring, sc.inQueue);
        } else if (subMode == PREDICTOR_SKY_OPEN) {
            predictSkyLightEikonal(voxLightProps, true, null, sc.hatL, sc.ring, sc.inQueue);
        } else {
            if (topSeedMode == 1) {
                if (pos + 128 > payEnd) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated raw 128B SkyLight top-face seed");
                }
                for (int i = 0; i < 128; i++) {
                    int b = buf[pos++] & 0xFF;
                    sc.topFace256[i << 1] = (byte) (b & 0x0F);
                    sc.topFace256[(i << 1) | 1] = (byte) (b >>> 4);
                }
            } else {
                pos = decodeTopFaceSeedRle(buf, pos, payEnd, sc.topFace256);
            }
            predictSkyLightEikonal(voxLightProps, false, sc.topFace256, sc.hatL, sc.ring, sc.inQueue);
        }

        if (pos >= payEnd) {
            throw new CorruptRegionEntryException("CBIT T5: Missing v3 Eikonal residualEncodingMode byte");
        }
        int residMode = buf[pos++] & 0xFF;
        if (residMode != RESIDUAL_MODE_B1_OCTANT_PACKED4) {
            throw new CorruptRegionEntryException("CBIT T5: Unsupported v3 Eikonal residualEncodingMode " + residMode);
        }
        if (pos + 8 > payEnd) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated B1 octantMask64");
        }

        System.arraycopy(sc.hatL, 0, outNibbles, 0, SECTION_VOXELS);

        long octMask = LcaSectionCodec.readLongBE(buf, pos);
        pos += 8;
        int activeOcts = Long.bitCount(octMask);
        if (pos + (activeOcts << 3) > payEnd) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated B1 per-octant voxel masks");
        }

        long[] octVoxMasks = sc.b1OctVoxMasks;
        int nzTotal = 0;
        long m = octMask;
        while (m != 0L) {
            int oct = Long.numberOfTrailingZeros(m);
            m &= (m - 1L);
            long vm = LcaSectionCodec.readLongBE(buf, pos);
            pos += 8;
            octVoxMasks[oct] = vm;
            nzTotal += Long.bitCount(vm);
        }
        int packedBytes = (nzTotal + 1) >>> 1;
        if (pos + packedBytes != payEnd) {
            throw new CorruptRegionEntryException(
                    "CBIT T5: B1 residual payload length mismatch (" + (pos + packedBytes) + " != " + payEnd + ")");
        }

        int nibPos = 0;
        m = octMask;
        while (m != 0L) {
            int oct = Long.numberOfTrailingZeros(m);
            m &= (m - 1L);
            int baseY = (oct >>> 4) << 2;
            int baseZ = ((oct >>> 2) & 3) << 2;
            int baseX = (oct & 3) << 2;
            long vm = octVoxMasks[oct];
            while (vm != 0L) {
                int bit = Long.numberOfTrailingZeros(vm);
                vm &= (vm - 1L);
                int dy = bit >>> 4;
                int dz = (bit >>> 2) & 3;
                int dx = bit & 3;
                int yzx = ((baseY + dy) << 8) | ((baseZ + dz) << 4) | (baseX + dx);
                int b = buf[pos + (nibPos >>> 1)] & 0xFF;
                int r = ((nibPos & 1) == 0) ? (b & 0x0F) : (b >>> 4);
                nibPos++;
                outNibbles[yzx] = (byte) ((outNibbles[yzx] + r) & 0x0F);
            }
        }
        return payEnd;
    }

    /**
     * Populates `outVoxProps[0..4096)` from packed Mojang block longs and a section palette of
     * packed light properties `(opacity4 << 4) | emission4`.
     */
    static void buildVoxelLightPropsFromPackedLongs(
            long[] packedBlockLongs, int paletteLen, byte[] palPackedLight, byte[] outVoxProps
    ) {
        if (paletteLen <= 1 || packedBlockLongs == null || packedBlockLongs.length == 0) {
            Arrays.fill(outVoxProps, palPackedLight[0]);
            return;
        }
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteLen - 1));
        int entriesPerLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        int v = 0;
        for (int w = 0; w < packedBlockLongs.length && v < SECTION_VOXELS; w++) {
            long word = packedBlockLongs[w];
            int lim = Math.min(SECTION_VOXELS, v + entriesPerLong);
            if (word == 0L) {
                Arrays.fill(outVoxProps, v, lim, palPackedLight[0]);
                v = lim;
            } else {
                while (v < lim) {
                    int ord = (int) (word & mask);
                    outVoxProps[v++] = palPackedLight[ord < paletteLen ? ord : 0];
                    word >>>= bits;
                }
            }
        }
        if (v < SECTION_VOXELS) {
            Arrays.fill(outVoxProps, v, SECTION_VOXELS, palPackedLight[0]);
        }
    }

    // =========================================================================
    // 3. Layer 2: boilerplateFlags16 Bit 15 (Columnar Ticks & Template BEs)
    // =========================================================================

    static void writeZigZagVarLong(ByteArrayOutputStream out, long signedVal) {
        long u = (signedVal << 1) ^ (signedVal >> 63);
        while ((u & ~0x7FL) != 0L) {
            out.write((int) ((u & 0x7FL) | 0x80L));
            u >>>= 7;
        }
        out.write((int) u);
    }

    static void writeVarInt(ByteArrayOutputStream out, int val) {
        int v = val;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    static long readZigZagVarLong(byte[] buf, int[] posRef, int limit) throws CorruptRegionEntryException {
        int pos = posRef[0];
        long result = 0L;
        int shift = 0;
        while (pos < limit && shift < 64) {
            int b = buf[pos++] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                posRef[0] = pos;
                return (result >>> 1) ^ -(result & 1L);
            }
            shift += 7;
        }
        throw new CorruptRegionEntryException("CBIT T5: Truncated or malformed ZigZagVarLong");
    }

    /**
     * Validates `boilerplateFlags16` bit `15` mutual exclusion against empty-list/structure bits
     * `0, 1, 2, 4, 5` and reserved `extFlags` bits `5..31` (`CBIT T5`).
     */
    static void validateExtFlags(int bpFlags, int extFlags, int formatVersion) throws CorruptRegionEntryException {
        if (formatVersion < LcaRegionReader.LCA_FORMAT_VERSION_V3) {
            throw new CorruptRegionEntryException("CBIT T5: boilerplateFlags16 bit 15 forbidden in formatVersion " + formatVersion);
        }
        if ((extFlags & EXT_FLAG_RESERVED_MASK) != 0) {
            throw new CorruptRegionEntryException("CBIT T5: Non-zero reserved bits in extFlags: " + extFlags);
        }
        if ((bpFlags & 2) != 0 && (extFlags & EXT_FLAG_COLUMNAR_BLOCK_TICKS) != 0) {
            throw new CorruptRegionEntryException("CBIT T5: Mutual exclusion violated for block_ticks");
        }
        if ((bpFlags & 4) != 0 && (extFlags & EXT_FLAG_COLUMNAR_FLUID_TICKS) != 0) {
            throw new CorruptRegionEntryException("CBIT T5: Mutual exclusion violated for fluid_ticks");
        }
        if ((bpFlags & 1) != 0 && (extFlags & EXT_FLAG_COLUMNAR_BLOCK_ENTITIES) != 0) {
            throw new CorruptRegionEntryException("CBIT T5: Mutual exclusion violated for block_entities");
        }
        if ((bpFlags & 32) != 0 && (extFlags & EXT_FLAG_SPARSE_POST_PROCESSING) != 0) {
            throw new CorruptRegionEntryException("CBIT T5: Mutual exclusion violated for PostProcessing");
        }
        if ((bpFlags & 16) != 0 && (extFlags & EXT_FLAG_DICT_STRUCTURES) != 0) {
            throw new CorruptRegionEntryException("CBIT T5: Mutual exclusion violated for structures");
        }
    }

    /**
     * Single-pass binary NBT scanner & columnar encoder for `block_ticks` / `fluid_ticks`.
     * `tagStart..tagEnd` is the exact root-tag entry slice `[tagType=9, nameLen, nameBytes, elemType, count, ...]`.
     * Returns encoded `ColumnarTickStream` bytes if every element matches the exact 6-key vanilla
     * schema `{i: String, p: Int, t: Int, x: Int, y: Int, z: Int}`, or `null` to fall back to Bit 10.
     */
    @SuppressWarnings("java:S1168") // null signals non-columnarizable ticks so caller falls back to raw NBT
    static byte[] tryEncodeColumnarTicksFromNbtSlice(
            ByteBuffer src, int tagStart, int tagEnd, int chunkX, int chunkZ
    ) {
        if ((currentFeatureMask() & FEATURE_COLUMNAR_TAIL) == 0) {
            return null;
        }
        int p = tagStart;
        if (p + 3 > tagEnd || (src.get(p) & 0xFF) != Nbt.TAG_LIST) {
            return null;
        }
        int nameLen = src.getShort(p + 1) & 0xFFFF;
        p += 3 + nameLen;
        if (p + 5 > tagEnd) {
            return null;
        }
        int elemType = src.get(p++) & 0xFF;
        int count = src.getInt(p);
        p += 4;
        if (elemType != Nbt.TAG_COMPOUND || count <= 0 || count > MAX_TICKS_PER_CHUNK) {
            return null;
        }

        String[] ids = new String[count];
        int[] xs = new int[count];
        int[] ys = new int[count];
        int[] zs = new int[count];
        int[] ts = new int[count];
        int[] ps = new int[count];

        for (int k = 0; k < count; k++) {
            int seenMask = 0;
            int fieldCount = 0;
            while (true) {
                if (p >= tagEnd) {
                    return null;
                }
                int ft = src.get(p++) & 0xFF;
                if (ft == Nbt.TAG_END) {
                    break;
                }
                if (p + 2 > tagEnd) {
                    return null;
                }
                int kLen = src.getShort(p) & 0xFFFF;
                p += 2;
                if (kLen != 1 || p >= tagEnd) {
                    return null;
                }
                byte kc = src.get(p++);
                fieldCount++;
                if (kc == 'i' && ft == Nbt.TAG_STRING && (seenMask & 1) == 0) {
                    if (p + 2 > tagEnd) return null;
                    int sLen = src.getShort(p) & 0xFFFF;
                    p += 2;
                    if (p + sLen > tagEnd) return null;
                    byte[] sb = new byte[sLen];
                    for (int j = 0; j < sLen; j++) sb[j] = src.get(p++);
                    ids[k] = new String(sb, StandardCharsets.UTF_8);
                    seenMask |= 1;
                } else if (kc == 'p' && ft == Nbt.TAG_INT && (seenMask & 2) == 0) {
                    if (p + 4 > tagEnd) return null;
                    ps[k] = src.getInt(p);
                    p += 4;
                    seenMask |= 2;
                } else if (kc == 't' && ft == Nbt.TAG_INT && (seenMask & 4) == 0) {
                    if (p + 4 > tagEnd) return null;
                    ts[k] = src.getInt(p);
                    p += 4;
                    seenMask |= 4;
                } else if (kc == 'x' && ft == Nbt.TAG_INT && (seenMask & 8) == 0) {
                    if (p + 4 > tagEnd) return null;
                    xs[k] = src.getInt(p);
                    p += 4;
                    seenMask |= 8;
                } else if (kc == 'y' && ft == Nbt.TAG_INT && (seenMask & 16) == 0) {
                    if (p + 4 > tagEnd) return null;
                    ys[k] = src.getInt(p);
                    p += 4;
                    seenMask |= 16;
                } else if (kc == 'z' && ft == Nbt.TAG_INT && (seenMask & 32) == 0) {
                    if (p + 4 > tagEnd) return null;
                    zs[k] = src.getInt(p);
                    p += 4;
                    seenMask |= 32;
                } else {
                    return null; // Non-vanilla key or tag type -> fall back to Bit 10
                }
            }
            if (seenMask != 63 || fieldCount != 6) {
                return null;
            }
        }
        return encodeColumnarTicksArrays(count, ids, xs, ys, zs, ts, ps, chunkX, chunkZ);
    }

    @SuppressWarnings("java:S1168") // null signals non-columnarizable ticks so caller falls back to raw NBT
    static byte[] tryEncodeColumnarTicksFromList(Nbt.NbtList list, int chunkX, int chunkZ) {
        if ((currentFeatureMask() & FEATURE_COLUMNAR_TAIL) == 0
                || list == null
                || list.elementType != Nbt.TAG_COMPOUND
                || list.items == null
                || list.items.isEmpty()
                || list.items.size() > MAX_TICKS_PER_CHUNK) {
            return null;
        }
        int count = list.items.size();
        String[] ids = new String[count];
        int[] xs = new int[count];
        int[] ys = new int[count];
        int[] zs = new int[count];
        int[] ts = new int[count];
        int[] ps = new int[count];
        for (int k = 0; k < count; k++) {
            Object item = list.items.get(k);
            if (!(item instanceof Map<?, ?> m) || m.size() != 6) {
                return null;
            }
            Object oi = m.get("i");
            Object op = m.get("p");
            Object ot = m.get("t");
            Object ox = m.get("x");
            Object oy = m.get("y");
            Object oz = m.get("z");
            if (!(oi instanceof String s)
                    || !(op instanceof Integer pi)
                    || !(ot instanceof Integer ti)
                    || !(ox instanceof Integer xi)
                    || !(oy instanceof Integer yi)
                    || !(oz instanceof Integer zi)) {
                return null;
            }
            ids[k] = s;
            ps[k] = pi;
            ts[k] = ti;
            xs[k] = xi;
            ys[k] = yi;
            zs[k] = zi;
        }
        return encodeColumnarTicksArrays(count, ids, xs, ys, zs, ts, ps, chunkX, chunkZ);
    }

    static byte[] encodeColumnarTicksArrays(
            int count, String[] ids, int[] xs, int[] ys, int[] zs, int[] ts, int[] ps, int chunkX, int chunkZ
    ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        boolean allInChunkXZ = true;
        boolean allPriorityZero = true;
        Map<String, Integer> idMap = new LinkedHashMap<>();
        List<String> uniqueIds = new ArrayList<>();
        int[] idOrds = new int[count];

        for (int k = 0; k < count; k++) {
            int lx = xs[k] - baseX;
            int lz = zs[k] - baseZ;
            if ((lx & ~15) != 0 || (lz & ~15) != 0) {
                allInChunkXZ = false;
            }
            if (ps[k] != 0) {
                allPriorityZero = false;
            }
            Integer ord = idMap.get(ids[k]);
            if (ord == null) {
                ord = uniqueIds.size();
                idMap.put(ids[k], ord);
                uniqueIds.add(ids[k]);
            }
            idOrds[k] = ord;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(32 + count * 4);
        writeVarInt(out, count);
        int tickFlags = (allInChunkXZ ? 1 : 0) | (allPriorityZero ? 2 : 0);
        out.write(tickFlags);
        writeVarInt(out, uniqueIds.size());
        for (String id : uniqueIds) {
            boolean mcPrefix = id.startsWith("minecraft:");
            byte[] utf = (mcPrefix ? id.substring(10) : id).getBytes(StandardCharsets.UTF_8);
            writeVarInt(out, (utf.length << 1) | (mcPrefix ? 1 : 0));
            out.write(utf, 0, utf.length);
        }
        if (uniqueIds.size() > 1) {
            for (int k = 0; k < count; k++) {
                writeVarInt(out, idOrds[k]);
            }
        }
        if (allInChunkXZ) {
            for (int k = 0; k < count; k++) {
                out.write(((xs[k] - baseX) << 4) | (zs[k] - baseZ));
            }
        } else {
            for (int k = 0; k < count; k++) {
                writeZigZagVarLong(out, (long) xs[k] - baseX);
                writeZigZagVarLong(out, (long) zs[k] - baseZ);
            }
        }
        long prevY = 0L;
        for (int k = 0; k < count; k++) {
            writeZigZagVarLong(out, ys[k] - prevY);
            prevY = ys[k];
        }
        long prevT = 0L;
        for (int k = 0; k < count; k++) {
            writeZigZagVarLong(out, ts[k] - prevT);
            prevT = ts[k];
        }
        if (!allPriorityZero) {
            for (int k = 0; k < count; k++) {
                writeZigZagVarLong(out, ps[k]);
            }
        }
        return out.toByteArray();
    }

    static Nbt.NbtList decodeColumnarTicksToList(
            byte[] buf, int start, int limit, int chunkX, int chunkZ
    ) throws CorruptRegionEntryException {
        int[] posRef = {start};
        int count = readVarIntChecked(buf, posRef, limit);
        if (count <= 0 || count > MAX_TICKS_PER_CHUNK || posRef[0] >= limit) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid columnar tickCount " + count);
        }
        int tickFlags = buf[posRef[0]++] & 0xFF;
        if ((tickFlags & ~0x03) != 0) {
            throw new CorruptRegionEntryException("CBIT T5: Reserved tickFlags bits set: " + tickFlags);
        }
        boolean allInChunkXZ = (tickFlags & 1) != 0;
        boolean allPriorityZero = (tickFlags & 2) != 0;

        int uniqueIdCount = readVarIntChecked(buf, posRef, limit);
        if (uniqueIdCount <= 0 || uniqueIdCount > count) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid columnar uniqueIdCount " + uniqueIdCount);
        }
        String[] uniqueIds = new String[uniqueIdCount];
        for (int i = 0; i < uniqueIdCount; i++) {
            int hdr = readVarIntChecked(buf, posRef, limit);
            boolean mcPrefix = (hdr & 1) != 0;
            int uLen = hdr >>> 1;
            if (uLen < 0 || posRef[0] + uLen > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated columnar tick id string");
            }
            String s = new String(buf, posRef[0], uLen, StandardCharsets.UTF_8);
            posRef[0] += uLen;
            uniqueIds[i] = mcPrefix ? ("minecraft:" + s) : s;
        }
        int[] idOrds = new int[count];
        if (uniqueIdCount > 1) {
            for (int k = 0; k < count; k++) {
                int ord = readVarIntChecked(buf, posRef, limit);
                if (ord < 0 || ord >= uniqueIdCount) {
                    throw new CorruptRegionEntryException("CBIT T7: Columnar tick idOrd out of bounds: " + ord);
                }
                idOrds[k] = ord;
            }
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int[] xs = new int[count];
        int[] zs = new int[count];
        if (allInChunkXZ) {
            if (posRef[0] + count > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated columnar tick packed XZ");
            }
            for (int k = 0; k < count; k++) {
                int b = buf[posRef[0]++] & 0xFF;
                xs[k] = baseX + (b >>> 4);
                zs[k] = baseZ + (b & 0x0F);
            }
        } else {
            for (int k = 0; k < count; k++) {
                xs[k] = (int) (baseX + readZigZagVarLong(buf, posRef, limit));
                zs[k] = (int) (baseZ + readZigZagVarLong(buf, posRef, limit));
            }
        }
        int[] ys = new int[count];
        long prevY = 0L;
        for (int k = 0; k < count; k++) {
            prevY += readZigZagVarLong(buf, posRef, limit);
            ys[k] = (int) prevY;
        }
        int[] ts = new int[count];
        long prevT = 0L;
        for (int k = 0; k < count; k++) {
            prevT += readZigZagVarLong(buf, posRef, limit);
            ts[k] = (int) prevT;
        }
        int[] ps = new int[count];
        if (!allPriorityZero) {
            for (int k = 0; k < count; k++) {
                ps[k] = (int) readZigZagVarLong(buf, posRef, limit);
            }
        }
        if (posRef[0] != limit) {
            throw new CorruptRegionEntryException("CBIT T5: Columnar tick stream trailing bytes");
        }
        List<Object> items = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            LinkedHashMap<String, Object> m = new LinkedHashMap<>(8);
            m.put("i", uniqueIds[idOrds[k]]);
            m.put("p", ps[k]);
            m.put("t", ts[k]);
            m.put("x", xs[k]);
            m.put("y", ys[k]);
            m.put("z", zs[k]);
            items.add(m);
        }
        return new Nbt.NbtList(Nbt.TAG_COMPOUND, items);
    }

    static LcaDecodedChunkMeta.ColumnarTicks decodeColumnarTicksDirect(
            byte[] buf,
            int start,
            int limit,
            int chunkX,
            int chunkZ
    ) throws CorruptRegionEntryException {
        int[] posRef = {start};
        int count = readVarIntChecked(buf, posRef, limit);
        if (count <= 0 || count > MAX_TICKS_PER_CHUNK || posRef[0] >= limit) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid columnar tickCount " + count);
        }
        int tickFlags = buf[posRef[0]++] & 0xFF;
        if ((tickFlags & ~0x03) != 0) {
            throw new CorruptRegionEntryException("CBIT T5: Reserved tickFlags bits set: " + tickFlags);
        }
        boolean allInChunkXZ = (tickFlags & 1) != 0;
        boolean allPriorityZero = (tickFlags & 2) != 0;

        int uniqueIdCount = readVarIntChecked(buf, posRef, limit);
        if (uniqueIdCount <= 0 || uniqueIdCount > count) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid columnar uniqueIdCount " + uniqueIdCount);
        }
        String[] uniqueIds = new String[uniqueIdCount];
        for (int i = 0; i < uniqueIdCount; i++) {
            int hdr = readVarIntChecked(buf, posRef, limit);
            boolean mcPrefix = (hdr & 1) != 0;
            int uLen = hdr >>> 1;
            if (uLen < 0 || posRef[0] + uLen > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated columnar tick id string");
            }
            String suffix = new String(buf, posRef[0], uLen, StandardCharsets.UTF_8);
            uniqueIds[i] = mcPrefix ? ("minecraft:" + suffix) : suffix;
            posRef[0] += uLen;
        }
        int[] idOrds = new int[count];
        if (uniqueIdCount > 1) {
            for (int k = 0; k < count; k++) {
                int ord = readVarIntChecked(buf, posRef, limit);
                if (ord < 0 || ord >= uniqueIdCount) {
                    throw new CorruptRegionEntryException("CBIT T7: Columnar tick idOrd out of bounds: " + ord);
                }
                idOrds[k] = ord;
            }
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int[] xs = new int[count];
        int[] zs = new int[count];
        if (allInChunkXZ) {
            if (posRef[0] + count > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated columnar tick packed XZ");
            }
            for (int k = 0; k < count; k++) {
                int b = buf[posRef[0]++] & 0xFF;
                xs[k] = baseX + (b >>> 4);
                zs[k] = baseZ + (b & 0x0F);
            }
        } else {
            for (int k = 0; k < count; k++) {
                xs[k] = (int) (baseX + readZigZagVarLong(buf, posRef, limit));
                zs[k] = (int) (baseZ + readZigZagVarLong(buf, posRef, limit));
            }
        }
        int[] ys = new int[count];
        long prevY = 0L;
        for (int k = 0; k < count; k++) {
            prevY += readZigZagVarLong(buf, posRef, limit);
            ys[k] = (int) prevY;
        }
        int[] ts = new int[count];
        long prevT = 0L;
        for (int k = 0; k < count; k++) {
            prevT += readZigZagVarLong(buf, posRef, limit);
            ts[k] = (int) prevT;
        }
        int[] ps = new int[count];
        if (!allPriorityZero) {
            for (int k = 0; k < count; k++) {
                ps[k] = (int) readZigZagVarLong(buf, posRef, limit);
            }
        }
        if (posRef[0] != limit) {
            throw new CorruptRegionEntryException("CBIT T5: Columnar tick stream trailing bytes");
        }
        return new LcaDecodedChunkMeta.ColumnarTicks(uniqueIds, idOrds, xs, ys, zs, ts, ps);
    }

    static int decodeColumnarTicksToNbt(
            byte[] buf,
            int start,
            int limit,
            String listTagName,
            int chunkX,
            int chunkZ,
            LcaSectionCodec.ScratchArena arena,
            int outPos
    ) throws CorruptRegionEntryException {
        int[] posRef = {start};
        int count = readVarIntChecked(buf, posRef, limit);
        if (count <= 0 || count > MAX_TICKS_PER_CHUNK || posRef[0] >= limit) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid columnar tickCount " + count);
        }
        int tickFlags = buf[posRef[0]++] & 0xFF;
        if ((tickFlags & ~0x03) != 0) {
            throw new CorruptRegionEntryException("CBIT T5: Reserved tickFlags bits set: " + tickFlags);
        }
        boolean allInChunkXZ = (tickFlags & 1) != 0;
        boolean allPriorityZero = (tickFlags & 2) != 0;

        int uniqueIdCount = readVarIntChecked(buf, posRef, limit);
        if (uniqueIdCount <= 0 || uniqueIdCount > count) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid columnar uniqueIdCount " + uniqueIdCount);
        }
        byte[][] idBytes = new byte[uniqueIdCount][];
        for (int i = 0; i < uniqueIdCount; i++) {
            int hdr = readVarIntChecked(buf, posRef, limit);
            boolean mcPrefix = (hdr & 1) != 0;
            int uLen = hdr >>> 1;
            if (uLen < 0 || posRef[0] + uLen > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated columnar tick id string");
            }
            if (mcPrefix) {
                byte[] full = new byte[MINECRAFT_PREFIX_BYTES.length + uLen];
                System.arraycopy(MINECRAFT_PREFIX_BYTES, 0, full, 0, MINECRAFT_PREFIX_BYTES.length);
                System.arraycopy(buf, posRef[0], full, MINECRAFT_PREFIX_BYTES.length, uLen);
                idBytes[i] = full;
            } else {
                idBytes[i] = Arrays.copyOfRange(buf, posRef[0], posRef[0] + uLen);
            }
            posRef[0] += uLen;
        }
        int[] idOrds = new int[count];
        if (uniqueIdCount > 1) {
            for (int k = 0; k < count; k++) {
                int ord = readVarIntChecked(buf, posRef, limit);
                if (ord < 0 || ord >= uniqueIdCount) {
                    throw new CorruptRegionEntryException("CBIT T7: Columnar tick idOrd out of bounds: " + ord);
                }
                idOrds[k] = ord;
            }
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int[] xs = new int[count];
        int[] zs = new int[count];
        if (allInChunkXZ) {
            if (posRef[0] + count > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated columnar tick packed XZ");
            }
            for (int k = 0; k < count; k++) {
                int b = buf[posRef[0]++] & 0xFF;
                xs[k] = baseX + (b >>> 4);
                zs[k] = baseZ + (b & 0x0F);
            }
        } else {
            for (int k = 0; k < count; k++) {
                xs[k] = (int) (baseX + readZigZagVarLong(buf, posRef, limit));
                zs[k] = (int) (baseZ + readZigZagVarLong(buf, posRef, limit));
            }
        }
        int[] ys = new int[count];
        long prevY = 0L;
        for (int k = 0; k < count; k++) {
            prevY += readZigZagVarLong(buf, posRef, limit);
            ys[k] = (int) prevY;
        }
        int[] ts = new int[count];
        long prevT = 0L;
        for (int k = 0; k < count; k++) {
            prevT += readZigZagVarLong(buf, posRef, limit);
            ts[k] = (int) prevT;
        }
        int[] ps = new int[count];
        if (!allPriorityZero) {
            for (int k = 0; k < count; k++) {
                ps[k] = (int) readZigZagVarLong(buf, posRef, limit);
            }
        }
        if (posRef[0] != limit) {
            throw new CorruptRegionEntryException("CBIT T5: Columnar tick stream trailing bytes");
        }

        byte[] tagNameBytes = listTagName.getBytes(StandardCharsets.UTF_8);
        byte[] out = arena.ensureNbtCap(outPos + 8 + tagNameBytes.length + count * 96);
        out[outPos++] = Nbt.TAG_LIST;
        out[outPos++] = (byte) (tagNameBytes.length >>> 8);
        out[outPos++] = (byte) tagNameBytes.length;
        System.arraycopy(tagNameBytes, 0, out, outPos, tagNameBytes.length);
        outPos += tagNameBytes.length;
        out[outPos++] = Nbt.TAG_COMPOUND;
        outPos = writeIntBE(out, outPos, count);

        for (int k = 0; k < count; k++) {
            byte[] ib = idBytes[idOrds[k]];
            out = arena.ensureNbtCap(outPos + 64 + ib.length);
            // i (TAG_String)
            out[outPos++] = Nbt.TAG_STRING;
            out[outPos++] = 0;
            out[outPos++] = 1;
            out[outPos++] = 'i';
            out[outPos++] = (byte) (ib.length >>> 8);
            out[outPos++] = (byte) ib.length;
            System.arraycopy(ib, 0, out, outPos, ib.length);
            outPos += ib.length;
            // p, t, x, y, z (TAG_Int)
            outPos = writeNamedIntTag(out, outPos, (byte) 'p', ps[k]);
            outPos = writeNamedIntTag(out, outPos, (byte) 't', ts[k]);
            outPos = writeNamedIntTag(out, outPos, (byte) 'x', xs[k]);
            outPos = writeNamedIntTag(out, outPos, (byte) 'y', ys[k]);
            outPos = writeNamedIntTag(out, outPos, (byte) 'z', zs[k]);
            out[outPos++] = Nbt.TAG_END;
        }
        return outPos;
    }

    /**
     * Single-pass binary NBT scanner & template-deduplicating encoder for `block_entities`.
     * Strips `x, y, z` (`coordType2 = 0`) or `Pos[3]` (`coordType2 = 1`) or leaves coordinates
     * verbatim inside the template (`coordType2 = 2`).
     */
    @SuppressWarnings("java:S1168") // null signals non-columnarizable block entities so caller falls back to raw NBT
    static byte[] tryEncodeColumnarBlockEntitiesFromNbtSlice(
            ByteBuffer src, int tagStart, int tagEnd, int chunkX, int chunkZ
    ) {
        if ((currentFeatureMask() & FEATURE_COLUMNAR_TAIL) == 0) {
            return null;
        }
        int p = tagStart;
        if (p + 3 > tagEnd || (src.get(p) & 0xFF) != Nbt.TAG_LIST) {
            return null;
        }
        int nameLen = src.getShort(p + 1) & 0xFFFF;
        p += 3 + nameLen;
        if (p + 5 > tagEnd) {
            return null;
        }
        int elemType = src.get(p++) & 0xFF;
        int count = src.getInt(p);
        p += 4;
        if (elemType != Nbt.TAG_COMPOUND || count <= 0 || count > MAX_BLOCK_ENTITIES_PER_CHUNK) {
            return null;
        }

        int[] coordTypes = new int[count];
        int[] xs = new int[count];
        int[] ys = new int[count];
        int[] zs = new int[count];
        byte[][] rawTemplates = new byte[count][];
        ByteArrayOutputStream tmplBuf = new ByteArrayOutputStream(256);

        for (int k = 0; k < count; k++) {
            int elemStart = p;
            int xVal = 0;
            int yVal = 0;
            int zVal = 0;
            int xyzMask = 0;
            boolean hasPos3 = false;
            int posX = 0;
            int posY = 0;
            int posZ = 0;
            int xFieldStart = -1;
            int xFieldEnd = -1;
            int yFieldStart = -1;
            int yFieldEnd = -1;
            int zFieldStart = -1;
            int zFieldEnd = -1;
            int posFieldStart = -1;
            int posFieldEnd = -1;

            while (true) {
                if (p >= tagEnd) return null;
                int fieldStart = p;
                int ft = src.get(p++) & 0xFF;
                if (ft == Nbt.TAG_END) {
                    break;
                }
                if (p + 2 > tagEnd) return null;
                int kLen = src.getShort(p) & 0xFFFF;
                p += 2;
                if (p + kLen > tagEnd) return null;
                int kStart = p;
                p += kLen;

                if (kLen == 1 && ft == Nbt.TAG_INT) {
                    if (p + 4 > tagEnd) return null;
                    byte kc = src.get(kStart);
                    int iv = src.getInt(p);
                    p += 4;
                    if (kc == 'x' && (xyzMask & 1) == 0) {
                        xVal = iv;
                        xyzMask |= 1;
                        xFieldStart = fieldStart;
                        xFieldEnd = p;
                    } else if (kc == 'y' && (xyzMask & 2) == 0) {
                        yVal = iv;
                        xyzMask |= 2;
                        yFieldStart = fieldStart;
                        yFieldEnd = p;
                    } else if (kc == 'z' && (xyzMask & 4) == 0) {
                        zVal = iv;
                        xyzMask |= 4;
                        zFieldStart = fieldStart;
                        zFieldEnd = p;
                    }
                } else if (kLen == 3 && ft == Nbt.TAG_INT_ARRAY
                        && src.get(kStart) == 'P' && src.get(kStart + 1) == 'o' && src.get(kStart + 2) == 's'
                        && !hasPos3) {
                    if (p + 4 > tagEnd) return null;
                    int arrLen = src.getInt(p);
                    p += 4;
                    if (arrLen < 0 || p + arrLen * 4L > tagEnd) return null;
                    if (arrLen == 3) {
                        posX = src.getInt(p);
                        posY = src.getInt(p + 4);
                        posZ = src.getInt(p + 8);
                        hasPos3 = true;
                        posFieldStart = fieldStart;
                        posFieldEnd = p + 12;
                    }
                    p += arrLen * 4;
                } else {
                    p = skipNbtPayload(src, p, tagEnd, ft, 0);
                    if (p < 0) return null;
                }
            }
            int elemEnd = p; // includes trailing TAG_END

            tmplBuf.reset();
            if (xyzMask == 7) {
                coordTypes[k] = 0;
                xs[k] = xVal;
                ys[k] = yVal;
                zs[k] = zVal;
                copyExcluding3Ranges(src, elemStart, elemEnd, xFieldStart, xFieldEnd, yFieldStart, yFieldEnd, zFieldStart, zFieldEnd, tmplBuf);
            } else if (hasPos3) {
                coordTypes[k] = 1;
                xs[k] = posX;
                ys[k] = posY;
                zs[k] = posZ;
                copyExcluding1Range(src, elemStart, elemEnd, posFieldStart, posFieldEnd, tmplBuf);
            } else {
                coordTypes[k] = 2;
                copySlice(src, elemStart, elemEnd, tmplBuf);
            }
            rawTemplates[k] = tmplBuf.toByteArray();
        }
        return encodeColumnarBlockEntitiesArrays(count, coordTypes, xs, ys, zs, rawTemplates, chunkX, chunkZ);
    }

    @SuppressWarnings("java:S1168") // null signals non-columnarizable block entities so caller falls back to raw NBT
    static byte[] tryEncodeColumnarBlockEntitiesFromList(Nbt.NbtList list, int chunkX, int chunkZ) {
        if ((currentFeatureMask() & FEATURE_COLUMNAR_TAIL) == 0
                || list == null
                || list.elementType != Nbt.TAG_COMPOUND
                || list.items == null
                || list.items.isEmpty()
                || list.items.size() > MAX_BLOCK_ENTITIES_PER_CHUNK) {
            return null;
        }
        int count = list.items.size();
        int[] coordTypes = new int[count];
        int[] xs = new int[count];
        int[] ys = new int[count];
        int[] zs = new int[count];
        byte[][] rawTemplates = new byte[count][];

        try {
            for (int k = 0; k < count; k++) {
                Object item = list.items.get(k);
                if (!(item instanceof Map<?, ?> m)) {
                    return null;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> srcMap = (Map<String, Object>) m;
                LinkedHashMap<String, Object> tmpl = new LinkedHashMap<>(srcMap);
                Object ox = srcMap.get("x");
                Object oy = srcMap.get("y");
                Object oz = srcMap.get("z");
                Object oPos = srcMap.get("Pos");
                if (ox instanceof Integer xi && oy instanceof Integer yi && oz instanceof Integer zi) {
                    coordTypes[k] = 0;
                    xs[k] = xi;
                    ys[k] = yi;
                    zs[k] = zi;
                    tmpl.remove("x");
                    tmpl.remove("y");
                    tmpl.remove("z");
                } else if (oPos instanceof int[] pa && pa.length == 3) {
                    coordTypes[k] = 1;
                    xs[k] = pa[0];
                    ys[k] = pa[1];
                    zs[k] = pa[2];
                    tmpl.remove("Pos");
                } else {
                    coordTypes[k] = 2;
                }
                // Serialize compound body (strip 3-byte root header 0x0A 0x00 0x00)
                byte[] fullRoot = Nbt.writeNamedRoot("", tmpl);
                rawTemplates[k] = Arrays.copyOfRange(fullRoot, 3, fullRoot.length);
            }
        } catch (IOException ex) {
            return null;
        }
        return encodeColumnarBlockEntitiesArrays(count, coordTypes, xs, ys, zs, rawTemplates, chunkX, chunkZ);
    }

    private static byte[] encodeColumnarBlockEntitiesArrays(
            int count, int[] coordTypes, int[] xs, int[] ys, int[] zs, byte[][] rawTemplates, int chunkX, int chunkZ
    ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        boolean allInChunkXYZ = true;
        Map<ByteArrayKey, Integer> tmplMap = new HashMap<>();
        List<byte[]> templates = new ArrayList<>();
        int[] tmplOrds = new int[count];

        for (int k = 0; k < count; k++) {
            if (coordTypes[k] != 0
                    || ((xs[k] - baseX) & ~15) != 0
                    || ((zs[k] - baseZ) & ~15) != 0) {
                allInChunkXYZ = false;
            }
            ByteArrayKey key = new ByteArrayKey(rawTemplates[k]);
            Integer ord = tmplMap.get(key);
            if (ord == null) {
                ord = templates.size();
                tmplMap.put(key, ord);
                templates.add(rawTemplates[k]);
            }
            tmplOrds[k] = ord;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(64 + count * 8);
        writeVarInt(out, count);
        out.write(allInChunkXYZ ? 1 : 0);
        writeVarInt(out, templates.size());
        for (byte[] t : templates) {
            writeVarInt(out, t.length);
            out.write(t, 0, t.length);
        }
        if (allInChunkXYZ) {
            long prevY = 0L;
            boolean multiTmpl = templates.size() > 1;
            for (int k = 0; k < count; k++) {
                if (multiTmpl) {
                    writeVarInt(out, tmplOrds[k]);
                }
                out.write(((xs[k] - baseX) << 4) | (zs[k] - baseZ));
                writeZigZagVarLong(out, ys[k] - prevY);
                prevY = ys[k];
            }
        } else {
            long prevX = 0L;
            long prevY = 0L;
            long prevZ = 0L;
            for (int k = 0; k < count; k++) {
                writeVarInt(out, (tmplOrds[k] << 2) | coordTypes[k]);
                if (coordTypes[k] != 2) {
                    writeZigZagVarLong(out, xs[k] - prevX);
                    writeZigZagVarLong(out, ys[k] - prevY);
                    writeZigZagVarLong(out, zs[k] - prevZ);
                    prevX = xs[k];
                    prevY = ys[k];
                    prevZ = zs[k];
                }
            }
        }
        return out.toByteArray();
    }

    static Nbt.NbtList decodeColumnarBlockEntitiesToList(
            byte[] buf, int start, int limit, int chunkX, int chunkZ
    ) throws IOException {
        int[] posRef = {start};
        int count = readVarIntChecked(buf, posRef, limit);
        if (count <= 0 || count > MAX_BLOCK_ENTITIES_PER_CHUNK || posRef[0] >= limit) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid columnar block_entities count " + count);
        }
        int beFlags = buf[posRef[0]++] & 0xFF;
        if ((beFlags & ~0x01) != 0) {
            throw new CorruptRegionEntryException("CBIT T5: Reserved beFlags bits set: " + beFlags);
        }
        boolean allInChunkXYZ = (beFlags & 1) != 0;
        int tmplCount = readVarIntChecked(buf, posRef, limit);
        if (tmplCount <= 0 || tmplCount > count) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid block_entities templateCount " + tmplCount);
        }
        byte[][] templates = new byte[tmplCount][];
        for (int i = 0; i < tmplCount; i++) {
            int tLen = readVarIntChecked(buf, posRef, limit);
            if (tLen < 1 || posRef[0] + tLen > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated block_entities template payload");
            }
            templates[i] = Arrays.copyOfRange(buf, posRef[0], posRef[0] + tLen);
            posRef[0] += tLen;
        }

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        List<Object> items = new ArrayList<>(count);
        if (allInChunkXYZ) {
            long prevY = 0L;
            boolean multiTmpl = tmplCount > 1;
            for (int k = 0; k < count; k++) {
                int tOrd = multiTmpl ? readVarIntChecked(buf, posRef, limit) : 0;
                if (tOrd < 0 || tOrd >= tmplCount || posRef[0] >= limit) {
                    throw new CorruptRegionEntryException("CBIT T7: block_entities templateOrd out of bounds");
                }
                int xz = buf[posRef[0]++] & 0xFF;
                prevY += readZigZagVarLong(buf, posRef, limit);
                LinkedHashMap<String, Object> m = parseCompoundBodyToMap(templates[tOrd]);
                m.put("x", baseX + (xz >>> 4));
                m.put("y", (int) prevY);
                m.put("z", baseZ + (xz & 0x0F));
                items.add(m);
            }
        } else {
            long prevX = 0L;
            long prevY = 0L;
            long prevZ = 0L;
            for (int k = 0; k < count; k++) {
                int hdr = readVarIntChecked(buf, posRef, limit);
                int coordType = hdr & 3;
                int tOrd = hdr >>> 2;
                if (coordType == 3 || tOrd < 0 || tOrd >= tmplCount) {
                    throw new CorruptRegionEntryException("CBIT T7: Invalid block_entities instance header");
                }
                LinkedHashMap<String, Object> m = parseCompoundBodyToMap(templates[tOrd]);
                if (coordType == 0) {
                    prevX += readZigZagVarLong(buf, posRef, limit);
                    prevY += readZigZagVarLong(buf, posRef, limit);
                    prevZ += readZigZagVarLong(buf, posRef, limit);
                    m.put("x", (int) prevX);
                    m.put("y", (int) prevY);
                    m.put("z", (int) prevZ);
                } else if (coordType == 1) {
                    prevX += readZigZagVarLong(buf, posRef, limit);
                    prevY += readZigZagVarLong(buf, posRef, limit);
                    prevZ += readZigZagVarLong(buf, posRef, limit);
                    m.put("Pos", new int[] {(int) prevX, (int) prevY, (int) prevZ});
                }
                items.add(m);
            }
        }
        if (posRef[0] != limit) {
            throw new CorruptRegionEntryException("CBIT T5: Columnar block_entities stream trailing bytes");
        }
        return new Nbt.NbtList(Nbt.TAG_COMPOUND, items);
    }

    static int decodeColumnarBlockEntitiesToNbt(
            byte[] buf,
            int start,
            int limit,
            int chunkX,
            int chunkZ,
            LcaSectionCodec.ScratchArena arena,
            int outPos
    ) throws CorruptRegionEntryException {
        int[] posRef = {start};
        int count = readVarIntChecked(buf, posRef, limit);
        if (count <= 0 || count > MAX_BLOCK_ENTITIES_PER_CHUNK || posRef[0] >= limit) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid columnar block_entities count " + count);
        }
        int beFlags = buf[posRef[0]++] & 0xFF;
        if ((beFlags & ~0x01) != 0) {
            throw new CorruptRegionEntryException("CBIT T5: Reserved beFlags bits set: " + beFlags);
        }
        boolean allInChunkXYZ = (beFlags & 1) != 0;
        int tmplCount = readVarIntChecked(buf, posRef, limit);
        if (tmplCount <= 0 || tmplCount > count) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid block_entities templateCount " + tmplCount);
        }
        int[] tmplStarts = new int[tmplCount];
        int[] tmplLens = new int[tmplCount];
        for (int i = 0; i < tmplCount; i++) {
            int tLen = readVarIntChecked(buf, posRef, limit);
            if (tLen < 1 || posRef[0] + tLen > limit || buf[posRef[0] + tLen - 1] != Nbt.TAG_END) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated or unterminated block_entities template");
            }
            tmplStarts[i] = posRef[0];
            tmplLens[i] = tLen;
            posRef[0] += tLen;
        }

        byte[] tagName = "block_entities".getBytes(StandardCharsets.UTF_8);
        byte[] out = arena.ensureNbtCap(outPos + 8 + tagName.length + count * 32);
        out[outPos++] = Nbt.TAG_LIST;
        out[outPos++] = 0;
        out[outPos++] = (byte) tagName.length;
        System.arraycopy(tagName, 0, out, outPos, tagName.length);
        outPos += tagName.length;
        out[outPos++] = Nbt.TAG_COMPOUND;
        outPos = writeIntBE(out, outPos, count);

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        if (allInChunkXYZ) {
            long prevY = 0L;
            boolean multiTmpl = tmplCount > 1;
            for (int k = 0; k < count; k++) {
                int tOrd = multiTmpl ? readVarIntChecked(buf, posRef, limit) : 0;
                if (tOrd < 0 || tOrd >= tmplCount || posRef[0] >= limit) {
                    throw new CorruptRegionEntryException("CBIT T7: block_entities templateOrd out of bounds");
                }
                int xz = buf[posRef[0]++] & 0xFF;
                prevY += readZigZagVarLong(buf, posRef, limit);
                int bodyLen = tmplLens[tOrd] - 1; // exclude trailing TAG_END
                out = arena.ensureNbtCap(outPos + bodyLen + 32);
                if (bodyLen > 0) {
                    System.arraycopy(buf, tmplStarts[tOrd], out, outPos, bodyLen);
                    outPos += bodyLen;
                }
                outPos = writeNamedIntTag(out, outPos, (byte) 'x', baseX + (xz >>> 4));
                outPos = writeNamedIntTag(out, outPos, (byte) 'y', (int) prevY);
                outPos = writeNamedIntTag(out, outPos, (byte) 'z', baseZ + (xz & 0x0F));
                out[outPos++] = Nbt.TAG_END;
            }
        } else {
            long prevX = 0L;
            long prevY = 0L;
            long prevZ = 0L;
            for (int k = 0; k < count; k++) {
                int hdr = readVarIntChecked(buf, posRef, limit);
                int coordType = hdr & 3;
                int tOrd = hdr >>> 2;
                if (coordType == 3 || tOrd < 0 || tOrd >= tmplCount) {
                    throw new CorruptRegionEntryException("CBIT T7: Invalid block_entities instance header");
                }
                int bodyLen = tmplLens[tOrd] - 1;
                out = arena.ensureNbtCap(outPos + bodyLen + 36);
                if (bodyLen > 0) {
                    System.arraycopy(buf, tmplStarts[tOrd], out, outPos, bodyLen);
                    outPos += bodyLen;
                }
                if (coordType == 0) {
                    prevX += readZigZagVarLong(buf, posRef, limit);
                    prevY += readZigZagVarLong(buf, posRef, limit);
                    prevZ += readZigZagVarLong(buf, posRef, limit);
                    outPos = writeNamedIntTag(out, outPos, (byte) 'x', (int) prevX);
                    outPos = writeNamedIntTag(out, outPos, (byte) 'y', (int) prevY);
                    outPos = writeNamedIntTag(out, outPos, (byte) 'z', (int) prevZ);
                } else if (coordType == 1) {
                    prevX += readZigZagVarLong(buf, posRef, limit);
                    prevY += readZigZagVarLong(buf, posRef, limit);
                    prevZ += readZigZagVarLong(buf, posRef, limit);
                    out[outPos++] = Nbt.TAG_INT_ARRAY;
                    out[outPos++] = 0;
                    out[outPos++] = 3;
                    out[outPos++] = 'P';
                    out[outPos++] = 'o';
                    out[outPos++] = 's';
                    outPos = writeIntBE(out, outPos, 3);
                    outPos = writeIntBE(out, outPos, (int) prevX);
                    outPos = writeIntBE(out, outPos, (int) prevY);
                    outPos = writeIntBE(out, outPos, (int) prevZ);
                }
                out[outPos++] = Nbt.TAG_END;
            }
        }
        if (posRef[0] != limit) {
            throw new CorruptRegionEntryException("CBIT T5: Columnar block_entities stream trailing bytes");
        }
        return outPos;
    }

    // =========================================================================
    // 4. Layer 3: Adaptive Successive Rank-Exclusion & ADR-005 D Cost Estimator
    // =========================================================================

    /**
     * Encodes minority states `p = 1..paletteSize-1` over successively compacted unclaimed domains
     * $D_p = D_{p-1} \setminus S_{p-1}$, reusing `LcaSectionCodec#emitStateContainerFromPool`.
     */
    static int encodeRankExcludedMinorityContainers(
            int[] curvePaletteIdx,
            int activeVoxels,
            int paletteSize,
            LcaSectionCodec.ScratchArena arena,
            byte[] dst
    ) {
        V3Scratch sc = v3Scratch();
        sc.ensureRankBuffers();
        int[] curDom = sc.domainA;
        int[] nextDom = sc.domainB;
        int rem = activeVoxels;
        System.arraycopy(curvePaletteIdx, 0, curDom, 0, rem);

        int[] poolPacked = arena.runPoolPacked;
        int[] poolNext = arena.runPoolNext;
        long[] bitmapWords = arena.bitmapWords64;
        int pos = 0;

        for (int p = 1; p < paletteSize; p++) {
            int nextRem = 0;
            int rCount = 0;
            int tVox = 0;
            int singlePos = 0;
            int lastCursor = 0;
            int headNode = -1;
            int tailNode = -1;
            int poolUsed = 0;

            int i = 0;
            while (i < rem) {
                int v = curDom[i];
                if (v != p) {
                    nextDom[nextRem++] = v;
                    i++;
                    continue;
                }
                int start = i++;
                while (i < rem && curDom[i] == p) {
                    i++;
                }
                int adjGap = (rCount == 0) ? start : (start - lastCursor - 1);
                int len = i - start;
                lastCursor = i;
                tVox += len;
                singlePos = start;
                rCount++;

                int node = poolUsed++;
                poolPacked[node] = (adjGap << 16) | ((len - 1) & 0xFFFF);
                poolNext[node] = -1;
                if (tailNode < 0) {
                    headNode = node;
                } else {
                    poolNext[tailNode] = node;
                }
                tailNode = node;
            }

            int paddedRem = (rem + 63) & ~63;
            pos = LcaSectionCodec.emitStateContainerFromPool(
                    dst, pos, rCount, tVox, singlePos, headNode,
                    poolPacked, poolNext, paddedRem, bitmapWords);

            int[] tmp = curDom;
            curDom = nextDom;
            nextDom = tmp;
            rem = nextRem;
        }
        return pos;
    }

    /**
     * Decodes a `RANK_EXCLUDED` (`secExtFlags & 0x01`) `Delta-Roaring` section directly into
     * `packedBlockLongs`, enforcing mandatory `CBIT T6b` (`decodedOctreeMask64 == expectedOctreeMask64`).
     */
    static int decodeRankExcludedDeltaRoaringBlocks(
            byte[] buf,
            int pos,
            int limit,
            int paletteLen,
            int curveId,
            long expectedOctreeMask64,
            long[] packedBlockLongs,
            int bits
    ) throws CorruptRegionEntryException {
        int[] curveToLoc = LcaSectionCodec.CURVE_BITS_TO_PACKED_LOC[curveId][bits];
        V3Scratch sc = v3Scratch();
        sc.ensureRankBuffers();
        int[] curDom = sc.domainA;
        int[] nextDom = sc.domainB;
        byte[] claimed = sc.claimed;
        int rem = buildInitialCurveDomain(curveId, expectedOctreeMask64, curDom);

        long decodedOctreeMask64 = 0L;
        Arrays.fill(claimed, 0, rem, (byte) 0);
        for (int p = 1; p < paletteLen; p++) {
            if (pos + 2 > limit) {
                throw new CorruptRegionEntryException("CBIT T6: Truncated rank-excluded container header for p=" + p);
            }
            int word16 = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
            pos += 2;
            int tag = (word16 >>> 14) & 3;
            int countOrIdx = word16 & 0x3FFF;
            long pLong = p;
            byte pMark = (byte) (p & 0x7F);
            if (pMark == 0) {
                Arrays.fill(claimed, 0, rem, (byte) 0);
                pMark = 1;
            }

            if (tag == LcaSectionCodec.TAG_SINGLETON) {
                int subTag = (countOrIdx >>> 12) & 3;
                int idx12 = countOrIdx & 0x0FFF;
                if (subTag == 0) {
                    if (idx12 >= rem) {
                        throw new CorruptRegionEntryException("CBIT T6: RankExcl singleton out of bounds: " + idx12);
                    }
                    decodedOctreeMask64 |= markRankVoxel(idx12, pLong, pMark, curDom, curveToLoc, packedBlockLongs, claimed);
                } else if (subTag == 1 || subTag == 2) {
                    int runLen;
                    if (subTag == 1) {
                        if (pos >= limit) throw new CorruptRegionEntryException("CBIT T6: Truncated RankExcl 1B run");
                        runLen = (buf[pos++] & 0xFF) + 2;
                    } else {
                        if (pos + 2 > limit) throw new CorruptRegionEntryException("CBIT T6: Truncated RankExcl 2B run");
                        runLen = LcaSectionCodec.readUint16BE(buf, pos) + 2;
                        pos += 2;
                    }
                    if (idx12 + runLen > rem) {
                        throw new CorruptRegionEntryException("CBIT T6: RankExcl single-run overflow: " + (idx12 + runLen));
                    }
                    for (int k = idx12; k < idx12 + runLen; k++) {
                        decodedOctreeMask64 |= markRankVoxel(k, pLong, pMark, curDom, curveToLoc, packedBlockLongs, claimed);
                    }
                } else {
                    if (pos + 2 > limit) throw new CorruptRegionEntryException("CBIT T6: Truncated RankExcl two-run");
                    int lens = buf[pos++] & 0xFF;
                    int runLen0 = (lens >>> 4) + 1;
                    int runLen1 = (lens & 0x0F) + 1;
                    int adjGap = buf[pos++] & 0xFF;
                    if (adjGap >= 240) {
                        if (pos >= limit) throw new CorruptRegionEntryException("CBIT T6: Truncated RankExcl 2B gap");
                        adjGap = ((adjGap & 0x0F) << 8) | (buf[pos++] & 0xFF);
                    }
                    int end0 = idx12 + runLen0;
                    int start1 = end0 + 1 + adjGap;
                    int end1 = start1 + runLen1;
                    if (end0 > rem || end1 > rem) {
                        throw new CorruptRegionEntryException("CBIT T6: RankExcl two-run out of bounds: " + end1);
                    }
                    for (int k = idx12; k < end0; k++) {
                        decodedOctreeMask64 |= markRankVoxel(k, pLong, pMark, curDom, curveToLoc, packedBlockLongs, claimed);
                    }
                    for (int k = start1; k < end1; k++) {
                        decodedOctreeMask64 |= markRankVoxel(k, pLong, pMark, curDom, curveToLoc, packedBlockLongs, claimed);
                    }
                }
            } else if (tag == LcaSectionCodec.TAG_BITMAP) {
                if (countOrIdx == 512 || (countOrIdx & 0x1000) != 0) {
                    int byteLen = (countOrIdx == 512) ? 512 : (countOrIdx & 0x0FFF);
                    if (pos + byteLen > limit || (byteLen & 7) != 0) {
                        throw new CorruptRegionEntryException("CBIT T6: Truncated or invalid RankExcl bitmap");
                    }
                    int wordCount = byteLen >>> 3;
                    for (int w = 0; w < wordCount; w++) {
                        long bits64 = LcaSectionCodec.readLongBE(buf, pos);
                        pos += 8;
                        int baseC = w << 6;
                        while (bits64 != 0L) {
                            int bit = Long.numberOfTrailingZeros(bits64);
                            bits64 &= (bits64 - 1L);
                            int c = baseC | bit;
                            if (c >= rem) {
                                throw new CorruptRegionEntryException("CBIT T6: RankExcl bitmap bit out of bounds: " + c);
                            }
                            decodedOctreeMask64 |= markRankVoxel(c, pLong, pMark, curDom, curveToLoc, packedBlockLongs, claimed);
                        }
                    }
                } else {
                    int nonZeroWords = countOrIdx;
                    if (pos + 8 + (nonZeroWords << 3) > limit) {
                        throw new CorruptRegionEntryException("CBIT T6: Truncated RankExcl sparse bitmap");
                    }
                    long wordMask = LcaSectionCodec.readLongBE(buf, pos);
                    pos += 8;
                    if (Long.bitCount(wordMask) != nonZeroWords) {
                        throw new CorruptRegionEntryException("CBIT T6: RankExcl sparse bitmap popcount mismatch");
                    }
                    while (wordMask != 0L) {
                        int w = Long.numberOfTrailingZeros(wordMask);
                        wordMask &= (wordMask - 1L);
                        long bits64 = LcaSectionCodec.readLongBE(buf, pos);
                        pos += 8;
                        int baseC = w << 6;
                        while (bits64 != 0L) {
                            int bit = Long.numberOfTrailingZeros(bits64);
                            bits64 &= (bits64 - 1L);
                            int c = baseC | bit;
                            if (c >= rem) {
                                throw new CorruptRegionEntryException("CBIT T6: RankExcl sparse bitmap bit out of bounds: " + c);
                            }
                            decodedOctreeMask64 |= markRankVoxel(c, pLong, pMark, curDom, curveToLoc, packedBlockLongs, claimed);
                        }
                    }
                }
            } else if (tag == LcaSectionCodec.TAG_DELTA_ARRAY) {
                int cursor = 0;
                for (int r = 0; r < countOrIdx; r++) {
                    if (pos >= limit) throw new CorruptRegionEntryException("CBIT T6: Truncated RankExcl delta array");
                    int b0 = buf[pos++] & 0xFF;
                    int delta;
                    if (b0 < 240) {
                        delta = b0;
                    } else {
                        if (pos >= limit) throw new CorruptRegionEntryException("CBIT T6: Truncated 2B RankExcl delta");
                        delta = ((b0 & 0x0F) << 8) | (buf[pos++] & 0xFF);
                    }
                    cursor = (r == 0) ? delta : (cursor + delta + 2);
                    if (cursor >= rem) {
                        throw new CorruptRegionEntryException("CBIT T6: RankExcl delta array out of bounds: " + cursor);
                    }
                    decodedOctreeMask64 |= markRankVoxel(cursor, pLong, pMark, curDom, curveToLoc, packedBlockLongs, claimed);
                }
            } else {
                int subMode = (countOrIdx >>> 12) & 3;
                int runCount = countOrIdx & 0x0FFF;
                int lBits = 4 - subMode;
                int gMax = (1 << (4 + subMode)) - 1;
                int lMax = (1 << lBits) - 1;
                int cursor = 0;
                for (int r = 0; r < runCount; r++) {
                    if (pos >= limit) throw new CorruptRegionEntryException("CBIT T6: Truncated RankExcl delta-run");
                    int tok = buf[pos++] & 0xFF;
                    int adjGap = (tok >>> lBits) & gMax;
                    int lenM1 = tok & lMax;
                    if (adjGap == gMax) {
                        if (pos >= limit) throw new CorruptRegionEntryException("CBIT T6: Truncated RankExcl run gap");
                        int b0 = buf[pos++] & 0xFF;
                        int extraGap;
                        if (b0 < 240) {
                            extraGap = b0;
                        } else {
                            if (pos >= limit) throw new CorruptRegionEntryException("CBIT T6: Truncated 2B RankExcl run gap");
                            extraGap = ((b0 & 0x0F) << 8) | (buf[pos++] & 0xFF);
                        }
                        adjGap = gMax + extraGap;
                    }
                    int len;
                    if (lenM1 == lMax) {
                        int extra = LcaSectionCodec.readVarInt(buf, pos, limit);
                        pos += LcaSectionCodec.varIntLen(extra);
                        len = lMax + extra + 1;
                    } else {
                        len = lenM1 + 1;
                    }
                    cursor = (r == 0) ? adjGap : (cursor + adjGap + 1);
                    int end = cursor + len;
                    if (end > rem) {
                        throw new CorruptRegionEntryException("CBIT T6: RankExcl delta-run overflow: " + end);
                    }
                    for (int k = cursor; k < end; k++) {
                        decodedOctreeMask64 |= markRankVoxel(k, pLong, pMark, curDom, curveToLoc, packedBlockLongs, claimed);
                    }
                    cursor = end;
                }
            }

            if (p + 1 < paletteLen) {
                int nextRem = 0;
                for (int i = 0; i < rem; i++) {
                    if (claimed[i] != pMark) {
                        nextDom[nextRem++] = curDom[i];
                    }
                }
                int[] tmp = curDom;
                curDom = nextDom;
                nextDom = tmp;
                rem = nextRem;
            }
        }

        if (decodedOctreeMask64 != expectedOctreeMask64) {
            throw new CorruptRegionEntryException(
                    "CBIT T6b: RankExcl decodedOctreeMask64 mismatch (expected=0x"
                            + Long.toHexString(expectedOctreeMask64)
                            + ", decoded=0x" + Long.toHexString(decodedOctreeMask64) + ")");
        }
        return pos;
    }

    private static long markRankVoxel(
            int domIdx, long pLong, byte pMark, int[] curDom, int[] curveToLoc, long[] packedBlockLongs, byte[] claimed
    ) {
        claimed[domIdx] = pMark;
        int loc = curveToLoc[curDom[domIdx]];
        packedBlockLongs[loc >>> 12] |= pLong << loc;
        return 1L << (loc >>> 6);
    }

    private static int buildInitialCurveDomain(int curveId, long expectedOctreeMask64, int[] outDomain) {
        if (expectedOctreeMask64 != -1L && expectedOctreeMask64 != 0L) {
            if (curveId == LcaSectionCodec.CURVE_H3) {
                int idx = 0;
                long m = expectedOctreeMask64;
                while (m != 0L) {
                    int baseH3 = Long.numberOfTrailingZeros(m) << 6;
                    m &= (m - 1L);
                    for (int k = 0; k < 64; k++) {
                        outDomain[idx++] = baseH3 | k;
                    }
                }
                return idx;
            }
            if (curveId == LcaSectionCodec.CURVE_YZX || curveId == LcaSectionCodec.CURVE_Y_H2) {
                int idx = 0;
                for (int octY = 0; octY < 4; octY++) {
                    if (((expectedOctreeMask64 >>> (octY << 4)) & 0xFFFFL) != 0L) {
                        int baseH = octY << 10;
                        for (int k = 0; k < 1024; k++) {
                            outDomain[idx++] = baseH | k;
                        }
                    }
                }
                return idx;
            }
        }
        for (int i = 0; i < SECTION_VOXELS; i++) {
            outDomain[i] = i;
        }
        return SECTION_VOXELS;
    }

    // =========================================================================
    // 5. Layer 4: Dominant-Pinned Adjacency-Ordered Local Gray Bitplanes (0x02)
    // =========================================================================

    /**
     * Encodes active-domain voxels in {@code curvePaletteIdx[0..activeVoxels)} using a dominant-pinned,
     * 3D curve-adjacency-ordered local palette and Reflected Binary Gray bitplanes
     * ({@code SEC_EXT_GRAY_BITPLANES = 0x02}, {@code anvil-api-ADR-006}).
     *
     * <p>Writes {@code planeModes[ceil(b/4)] || planePayloads[0..b-1]} into {@code dst[0..len)} and
     * writes the reordered local palette global/micro IDs into {@code outRemappedGlobalIds[0..paletteSize)}
     * with {@code outRemappedGlobalIds[0] == sortedGlobalIds[0]} pinned.
     */
    static int tryEncodeAdjacencyGrayBitplanes(
            int[] curvePaletteIdx,
            int activeVoxels,
            int paletteSize,
            int[] sortedGlobalIds,
            int[] outRemappedGlobalIds,
            byte[] dst
    ) {
        if (activeVoxels <= 0 || paletteSize < 2 || paletteSize > MAX_GRAY_PALETTE) {
            return -1;
        }
        V3Scratch sc = v3Scratch();
        sc.ensureGrayBuffers();
        int[] adj = sc.grayAdjMatrix;
        int pSq = paletteSize * paletteSize;
        Arrays.fill(adj, 0, pSq, 0);

        int prev = curvePaletteIdx[0];
        for (int i = 1; i < activeVoxels; i++) {
            int cur = curvePaletteIdx[i];
            if (cur != prev) {
                adj[prev * paletteSize + cur]++;
                adj[cur * paletteSize + prev]++;
                prev = cur;
            }
        }

        int[] oldToNew = sc.grayOldToNew;
        int[] newToOld = sc.grayNewToOld;
        boolean[] used = sc.grayUsed;
        Arrays.fill(used, 0, paletteSize, false);
        newToOld[0] = 0;
        oldToNew[0] = 0;
        used[0] = true;

        int prevOld = 0;
        for (int k = 1; k < paletteSize; k++) {
            int rowBase = prevOld * paletteSize;
            int bestCand = -1;
            int bestWeight = -1;
            for (int cand = 1; cand < paletteSize; cand++) {
                if (used[cand]) continue;
                int w = adj[rowBase + cand];
                if (w > bestWeight) {
                    bestWeight = w;
                    bestCand = cand;
                }
            }
            newToOld[k] = bestCand;
            oldToNew[bestCand] = k;
            used[bestCand] = true;
            prevOld = bestCand;
        }

        for (int k = 0; k < paletteSize; k++) {
            outRemappedGlobalIds[k] = sortedGlobalIds[newToOld[k]];
        }

        int[] grayCurve = sc.grayCurveVals;
        for (int i = 0; i < activeVoxels; i++) {
            int np = oldToNew[curvePaletteIdx[i]];
            grayCurve[i] = np ^ (np >>> 1);
        }

        int b = 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        int planeModeBytes = (b + 3) >>> 2;
        Arrays.fill(dst, 0, planeModeBytes, (byte) 0);
        int pos = planeModeBytes;
        int rawPlaneBytes = (activeVoxels + 7) >>> 3;
        int[] stagingA = sc.grayRunStaging;
        int[] stagingB = sc.grayRunStagingAlt;

        for (int plane = 0; plane < b; plane++) {
            int mask = 1 << plane;
            int prevMask = plane > 0 ? (1 << (plane - 1)) : 0;

            int rawRunCount = 0;
            int ones = 0;
            int cursor = 0;
            int i = 0;
            while (i < activeVoxels) {
                if ((grayCurve[i] & mask) == 0) {
                    i++;
                    continue;
                }
                int start = i++;
                while (i < activeVoxels && (grayCurve[i] & mask) != 0) {
                    i++;
                }
                int gap = start - cursor;
                int len = i - start;
                ones += len;
                stagingA[rawRunCount++] = (gap << 16) | ((len - 1) & 0xFFFF);
                cursor = i;
            }

            int mode;
            if (ones == 0) {
                mode = 0;
            } else if (ones == activeVoxels) {
                mode = 1;
                dst[pos++] = (byte) 0xFF;
            } else {
                int bestKind = 3; // 0=singleRun, 1=rleA, 2=rleB, 3=rawBits
                int bestBytes = rawPlaneBytes;
                int bestRefCode = 0;
                boolean bestSplit62 = false;
                int bestRunCount = 0;

                if (rawRunCount == 1) {
                    int packed = stagingA[0];
                    int start = packed >>> 16;
                    int len = (packed & 0xFFFF) + 1;
                    int singleLen = (start + len == activeVoxels || start == 0)
                            ? 2
                            : (2 + LcaSectionCodec.varIntLen(len - 1));
                    if (singleLen <= bestBytes) {
                        bestBytes = singleLen;
                        bestKind = 0;
                    }
                } else {
                    int size44 = measureGrayRleSize(stagingA, rawRunCount, false);
                    int size62 = measureGrayRleSize(stagingA, rawRunCount, true);
                    int minRaw = Math.min(size44, size62);
                    if (minRaw <= bestBytes) {
                        bestBytes = minRaw;
                        bestKind = 1;
                        bestRefCode = 0;
                        bestSplit62 = size62 < size44;
                        bestRunCount = rawRunCount;
                    }
                }

                if (ones > (activeVoxels >>> 1)) {
                    int invRuns = extractActiveGrayPlaneRuns(grayCurve, activeVoxels, mask, 0, mask, stagingB);
                    int s44 = measureGrayRleSize(stagingB, invRuns, false);
                    int s62 = measureGrayRleSize(stagingB, invRuns, true);
                    int minInv = Math.min(s44, s62);
                    if (minInv < bestBytes) {
                        bestBytes = minInv;
                        bestKind = 2;
                        bestRefCode = 2;
                        bestSplit62 = s62 < s44;
                        bestRunCount = invRuns;
                    }
                }

                if (plane > 0) {
                    int[] xorTarget = (bestKind == 2) ? stagingA : stagingB;
                    int xorRuns = extractActiveGrayPlaneRuns(grayCurve, activeVoxels, mask, prevMask, 0, xorTarget);
                    int s44 = measureGrayRleSize(xorTarget, xorRuns, false);
                    int s62 = measureGrayRleSize(xorTarget, xorRuns, true);
                    int minXor = Math.min(s44, s62);
                    if (minXor < bestBytes) {
                        bestKind = (xorTarget == stagingA) ? 1 : 2;
                        bestRefCode = 1;
                        bestSplit62 = s62 < s44;
                        bestRunCount = xorRuns;
                    }
                }

                if (bestKind == 0) {
                    mode = 1;
                    int packed = stagingA[0];
                    int start = packed >>> 16;
                    int len = (packed & 0xFFFF) + 1;
                    if (start + len == activeVoxels) {
                        dst[pos++] = (byte) (0xE0 | ((start >>> 8) & 0x0F));
                        dst[pos++] = (byte) (start & 0xFF);
                    } else if (start == 0) {
                        int end = len - 1;
                        dst[pos++] = (byte) (0xD0 | ((end >>> 8) & 0x0F));
                        dst[pos++] = (byte) (end & 0xFF);
                    } else {
                        dst[pos++] = (byte) (0xC0 | ((start >>> 8) & 0x0F));
                        dst[pos++] = (byte) (start & 0xFF);
                        pos = LcaSectionCodec.writeVarIntToBuf(dst, pos, len - 1);
                    }
                } else if (bestKind == 1 || bestKind == 2) {
                    mode = 1;
                    int[] srcRuns = (bestKind == 1) ? stagingA : stagingB;
                    pos += LcaSectionCodec.emitGraySubModeCandidate(
                            srcRuns, bestRunCount, bestRefCode, bestSplit62, dst, pos);
                } else {
                    mode = 3;
                    Arrays.fill(dst, pos, pos + rawPlaneBytes, (byte) 0);
                    for (int h = 0; h < activeVoxels; h++) {
                        if ((grayCurve[h] & mask) != 0) {
                            dst[pos + (h >>> 3)] |= (byte) (1 << (h & 7));
                        }
                    }
                    pos += rawPlaneBytes;
                }
            }
            dst[plane >>> 2] |= (byte) (mode << ((plane & 3) << 1));
        }
        return pos;
    }

    private static int measureGrayRleSize(int[] staging, int runCount, boolean split62) {
        int size = 1 + (runCount >= 31 ? LcaSectionCodec.varIntLen(runCount - 31) : 0);
        int gMax = split62 ? 63 : 15;
        int lMax = split62 ? 3 : 15;
        for (int r = 0; r < runCount; r++) {
            int packed = staging[r];
            int rawGap = packed >>> 16;
            int adjGap = (r == 0) ? rawGap : (rawGap - 1);
            int lm1 = packed & 0xFFFF;
            size += 1 + (adjGap >= gMax ? LcaSectionCodec.varIntLen(adjGap - gMax) : 0)
                    + (lm1 >= lMax ? LcaSectionCodec.varIntLen(lm1 - lMax) : 0);
        }
        return size;
    }

    private static int extractActiveGrayPlaneRuns(
            int[] grayCurve, int activeVoxels, int mask, int refMask, int invertTarget, int[] staging
    ) {
        int runCount = 0;
        int cursor = 0;
        int i = 0;
        while (i < activeVoxels) {
            int bit = (grayCurve[i] & mask) ^ invertTarget;
            int ref = (refMask != 0 && (grayCurve[i] & refMask) != 0) ? mask : 0;
            if ((bit ^ ref) == 0) {
                i++;
                continue;
            }
            int start = i++;
            while (i < activeVoxels) {
                int b2 = (grayCurve[i] & mask) ^ invertTarget;
                int r2 = (refMask != 0 && (grayCurve[i] & refMask) != 0) ? mask : 0;
                if ((b2 ^ r2) == 0) break;
                i++;
            }
            int gap = start - cursor;
            int len = i - start;
            staging[runCount++] = (gap << 16) | ((len - 1) & 0xFFFF);
            cursor = i;
        }
        return runCount;
    }

    /**
     * Decodes a {@code SEC_EXT_GRAY_BITPLANES} ({@code 0x02}) section directly into
     * {@code packedBlockLongs}, enforcing strict bounds ({@code CBIT T5/T6}) and mandatory
     * {@code CBIT T6b} ({@code decodedOctreeMask64 == expectedOctreeMask64}).
     */
    static int decodeGrayBitplaneBlocks(
            byte[] buf,
            int pos,
            int limit,
            int paletteLen,
            int curveId,
            long expectedOctreeMask64,
            long[] packedBlockLongs,
            int bits
    ) throws CorruptRegionEntryException {
        if (paletteLen < 2 || paletteLen > SECTION_VOXELS) {
            throw new CorruptRegionEntryException("CBIT T7: Invalid Gray bitplane paletteLen " + paletteLen);
        }
        int payloadByteLen = LcaSectionCodec.readVarInt(buf, pos, limit);
        pos += LcaSectionCodec.varIntLen(payloadByteLen);
        int b = 32 - Integer.numberOfLeadingZeros(paletteLen - 1);
        int planeModeBytes = (b + 3) >>> 2;
        if (payloadByteLen < planeModeBytes || pos + payloadByteLen > limit) {
            throw new CorruptRegionEntryException(
                    "CBIT T5: Truncated Gray bitplane payload (len=" + payloadByteLen + ")");
        }
        int payloadEnd = pos + payloadByteLen;
        int modesOff = pos;
        pos += planeModeBytes;

        int usedTailBits = (b & 3) << 1;
        if (usedTailBits != 0) {
            int lastModeByte = buf[modesOff + planeModeBytes - 1] & 0xFF;
            if ((lastModeByte >>> usedTailBits) != 0) {
                throw new CorruptRegionEntryException("CBIT T5: Reserved Gray planeMode bits set");
            }
        }

        V3Scratch sc = v3Scratch();
        sc.ensureRankBuffers();
        sc.ensureGrayBuffers();
        int[] curDom = sc.domainA;
        int activeVoxels = buildInitialCurveDomain(curveId, expectedOctreeMask64, curDom);
        int[] grayCurve = sc.grayCurveVals;
        Arrays.fill(grayCurve, 0, activeVoxels, 0);
        int rawPlaneBytes = (activeVoxels + 7) >>> 3;

        for (int plane = 0; plane < b; plane++) {
            int mode = ((buf[modesOff + (plane >>> 2)] & 0xFF) >>> ((plane & 3) << 1)) & 3;
            int bit = 1 << plane;
            int prevBit = plane > 0 ? (1 << (plane - 1)) : 0;
            if (mode == 0) {
                // Const0
            } else if (mode == 2) {
                throw new CorruptRegionEntryException("CBIT T5: Forbidden Gray voxel planeMode 2 on plane " + plane);
            } else if (mode == 1) {
                if (pos >= payloadEnd) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated Gray voxel plane subHdr");
                }
                int subHdr = buf[pos++] & 0xFF;
                if (subHdr == 0xFF) {
                    for (int i = 0; i < activeVoxels; i++) {
                        grayCurve[i] |= bit;
                    }
                } else if ((subHdr & 0xC0) == 0xC0) {
                    int kind = (subHdr >>> 4) & 3;
                    if (kind == 3 || pos >= payloadEnd) {
                        throw new CorruptRegionEntryException("CBIT T5: Invalid or truncated Gray single-run subHdr");
                    }
                    int val12 = ((subHdr & 0x0F) << 8) | (buf[pos++] & 0xFF);
                    int start;
                    int end;
                    if (kind == 2) {
                        start = val12;
                        end = activeVoxels;
                    } else if (kind == 1) {
                        start = 0;
                        end = val12 + 1;
                    } else {
                        int lenM1 = LcaSectionCodec.readVarInt(buf, pos, payloadEnd);
                        pos += LcaSectionCodec.varIntLen(lenM1);
                        start = val12;
                        end = start + lenM1 + 1;
                    }
                    if (start < 0 || end > activeVoxels || start > end) {
                        throw new CorruptRegionEntryException("CBIT T6: Gray voxel single-run overflow");
                    }
                    for (int i = start; i < end; i++) {
                        grayCurve[i] |= bit;
                    }
                } else {
                    int refCode = (subHdr >>> 6) & 3;
                    if (refCode == 1 && prevBit == 0) {
                        throw new CorruptRegionEntryException("CBIT T5: Cross-plane XOR forbidden on Gray plane 0");
                    }
                    boolean split62 = (subHdr & 0x20) != 0;
                    int runCount = subHdr & 0x1F;
                    if (runCount == 31) {
                        int extra = LcaSectionCodec.readVarInt(buf, pos, payloadEnd);
                        pos += LcaSectionCodec.varIntLen(extra);
                        runCount += extra;
                    }
                    if (runCount < 0 || runCount > activeVoxels) {
                        throw new CorruptRegionEntryException("CBIT T5: Invalid Gray voxel runCount " + runCount);
                    }
                    if (refCode == 2) {
                        for (int i = 0; i < activeVoxels; i++) {
                            grayCurve[i] |= bit;
                        }
                    } else if (refCode == 1) {
                        for (int i = 0; i < activeVoxels; i++) {
                            if ((grayCurve[i] & prevBit) != 0) {
                                grayCurve[i] |= bit;
                            }
                        }
                    }
                    int lBits = split62 ? 2 : 4;
                    int gMax = split62 ? 63 : 15;
                    int lMax = split62 ? 3 : 15;
                    int cursor = 0;
                    for (int r = 0; r < runCount; r++) {
                        if (pos >= payloadEnd) {
                            throw new CorruptRegionEntryException("CBIT T5: Truncated Gray voxel run");
                        }
                        int tok = buf[pos++] & 0xFF;
                        int adjGap = (tok >>> lBits) & gMax;
                        int lenMinusOne = tok & lMax;
                        if (adjGap == gMax) {
                            int extra = LcaSectionCodec.readVarInt(buf, pos, payloadEnd);
                            pos += LcaSectionCodec.varIntLen(extra);
                            adjGap += extra;
                        }
                        if (lenMinusOne == lMax) {
                            int extra = LcaSectionCodec.readVarInt(buf, pos, payloadEnd);
                            pos += LcaSectionCodec.varIntLen(extra);
                            lenMinusOne += extra;
                        }
                        cursor += (r == 0) ? adjGap : (adjGap + 1);
                        int runLen = lenMinusOne + 1;
                        if (cursor < 0 || cursor + runLen > activeVoxels) {
                            throw new CorruptRegionEntryException("CBIT T6: Gray voxel run cursor overflow");
                        }
                        for (int k = 0; k < runLen; k++) {
                            grayCurve[cursor++] ^= bit;
                        }
                    }
                }
            } else {
                if (pos + rawPlaneBytes > payloadEnd) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated raw Gray voxel bitplane");
                }
                for (int bi = 0; bi < rawPlaneBytes; bi++) {
                    int mask8 = buf[pos + bi] & 0xFF;
                    if (mask8 == 0) continue;
                    int base = bi << 3;
                    int lim = Math.min(8, activeVoxels - base);
                    for (int k = 0; k < lim; k++) {
                        if ((mask8 & (1 << k)) != 0) {
                            grayCurve[base + k] |= bit;
                        }
                    }
                }
                pos += rawPlaneBytes;
            }
        }

        if (pos != payloadEnd) {
            throw new CorruptRegionEntryException("CBIT T5: Gray bitplane payload trailing bytes");
        }

        int[] curveToLoc = LcaSectionCodec.CURVE_BITS_TO_PACKED_LOC[curveId][bits];
        long decodedOctreeMask64 = 0L;
        for (int i = 0; i < activeVoxels; i++) {
            int g = grayCurve[i];
            if (g != 0) {
                int p = g ^ (g >>> 8);
                p ^= (p >>> 4);
                p ^= (p >>> 2);
                p ^= (p >>> 1);
                if (p >= paletteLen) {
                    throw new CorruptRegionEntryException(
                            "CBIT T6: Gray bitplane decoded ordinal " + p + " >= paletteLen " + paletteLen);
                }
                int loc = curveToLoc[curDom[i]];
                packedBlockLongs[loc >>> 12] |= ((long) p) << loc;
                decodedOctreeMask64 |= 1L << (loc >>> 6);
            }
        }

        if (decodedOctreeMask64 != expectedOctreeMask64) {
            throw new CorruptRegionEntryException(
                    "CBIT T6b: GrayBitplane decodedOctreeMask64 mismatch (expected=0x"
                            + Long.toHexString(expectedOctreeMask64)
                            + ", decoded=0x" + Long.toHexString(decodedOctreeMask64) + ")");
        }
        return pos;
    }

    /**
     * `anvil-api-ADR-005` Candidate `D`: Non-mutating `litLen[b]` bit-cost estimator for gating
     * `Mode B` (`LCD2`) section candidates without running double-`LCD2` trial encoding (`0 µs` decode cost).
     */
    static long estimateHuffmanBitCost(byte[] buf, int off, int len, int[] litLen) {
        if (litLen == null || litLen.length < 256) {
            return (long) len << 3;
        }
        long bits = 0L;
        int end = off + len;
        for (int i = off; i < end; i++) {
            int l = litLen[buf[i] & 0xFF];
            bits += (l > 0) ? l : 8;
        }
        return bits;
    }

    // =========================================================================
    // Internal NBT / ByteBuffer Helpers
    // =========================================================================

    private static int readVarIntChecked(byte[] buf, int[] posRef, int limit) throws CorruptRegionEntryException {
        int v = LcaSectionCodec.readVarInt(buf, posRef[0], limit);
        posRef[0] += LcaSectionCodec.varIntLen(v);
        return v;
    }

    private static int writeIntBE(byte[] out, int pos, int v) {
        out[pos++] = (byte) (v >>> 24);
        out[pos++] = (byte) (v >>> 16);
        out[pos++] = (byte) (v >>> 8);
        out[pos++] = (byte) v;
        return pos;
    }

    private static int writeNamedIntTag(byte[] out, int pos, byte keyChar, int val) {
        out[pos++] = Nbt.TAG_INT;
        out[pos++] = 0;
        out[pos++] = 1;
        out[pos++] = keyChar;
        return writeIntBE(out, pos, val);
    }

    private static LinkedHashMap<String, Object> parseCompoundBodyToMap(byte[] bodyWithEnd) throws IOException {
        byte[] root = new byte[3 + bodyWithEnd.length];
        root[0] = Nbt.TAG_COMPOUND;
        root[1] = 0;
        root[2] = 0;
        System.arraycopy(bodyWithEnd, 0, root, 3, bodyWithEnd.length);
        return Nbt.readRootCompound(root);
    }

    private static void copySlice(ByteBuffer src, int start, int end, ByteArrayOutputStream out) {
        for (int i = start; i < end; i++) {
            out.write(src.get(i));
        }
    }

    private static void copyExcluding1Range(
            ByteBuffer src, int start, int end, int exStart, int exEnd, ByteArrayOutputStream out
    ) {
        int i = start;
        while (i < end) {
            if (i >= exStart && i < exEnd) {
                i = exEnd;
                continue;
            }
            out.write(src.get(i));
            i++;
        }
    }

    private static void copyExcluding3Ranges(
            ByteBuffer src,
            int start,
            int end,
            int s0,
            int e0,
            int s1,
            int e1,
            int s2,
            int e2,
            ByteArrayOutputStream out
    ) {
        int i = start;
        while (i < end) {
            if (i >= s0 && i < e0) {
                i = e0;
                continue;
            }
            if (i >= s1 && i < e1) {
                i = e1;
                continue;
            }
            if (i >= s2 && i < e2) {
                i = e2;
                continue;
            }
            out.write(src.get(i));
            i++;
        }
    }

    private static int skipNbtPayload(ByteBuffer src, int p, int limit, int tagType, int depth) {
        if (depth > 128) {
            return -1;
        }
        switch (tagType) {
            case Nbt.TAG_BYTE -> { return (p + 1 <= limit) ? p + 1 : -1; }
            case Nbt.TAG_SHORT -> { return (p + 2 <= limit) ? p + 2 : -1; }
            case Nbt.TAG_INT, Nbt.TAG_FLOAT -> { return (p + 4 <= limit) ? p + 4 : -1; }
            case Nbt.TAG_LONG, Nbt.TAG_DOUBLE -> { return (p + 8 <= limit) ? p + 8 : -1; }
            case Nbt.TAG_BYTE_ARRAY -> {
                if (p + 4 > limit) return -1;
                int len = src.getInt(p);
                p += 4;
                return (len >= 0 && p + (long) len <= limit) ? p + len : -1;
            }
            case Nbt.TAG_STRING -> {
                if (p + 2 > limit) return -1;
                int len = src.getShort(p) & 0xFFFF;
                p += 2;
                return (p + len <= limit) ? p + len : -1;
            }
            case Nbt.TAG_LIST -> {
                if (p + 5 > limit) return -1;
                int et = src.get(p++) & 0xFF;
                int cnt = src.getInt(p);
                p += 4;
                if (cnt < 0) return -1;
                for (int i = 0; i < cnt; i++) {
                    p = skipNbtPayload(src, p, limit, et, depth + 1);
                    if (p < 0) return -1;
                }
                return p;
            }
            case Nbt.TAG_COMPOUND -> {
                while (true) {
                    if (p >= limit) return -1;
                    int ft = src.get(p++) & 0xFF;
                    if (ft == Nbt.TAG_END) return p;
                    if (p + 2 > limit) return -1;
                    int kl = src.getShort(p) & 0xFFFF;
                    p += 2 + kl;
                    if (p > limit) return -1;
                    p = skipNbtPayload(src, p, limit, ft, depth + 1);
                    if (p < 0) return -1;
                }
            }
            case Nbt.TAG_INT_ARRAY -> {
                if (p + 4 > limit) return -1;
                int len = src.getInt(p);
                p += 4;
                return (len >= 0 && p + len * 4L <= limit) ? p + len * 4 : -1;
            }
            case Nbt.TAG_LONG_ARRAY -> {
                if (p + 4 > limit) return -1;
                int len = src.getInt(p);
                p += 4;
                return (len >= 0 && p + len * 8L <= limit) ? p + len * 8 : -1;
            }
            default -> { return -1; }
        }
    }

    // =========================================================================
    // Sparse PostProcessing (EXT_FLAG_SPARSE_POST_PROCESSING = 0x08)
    // =========================================================================

    static void writeZigZagVarInt(ByteArrayOutputStream out, int signedVal) {
        int u = (signedVal << 1) ^ (signedVal >> 31);
        while ((u & ~0x7F) != 0) {
            out.write((u & 0x7F) | 0x80);
            u >>>= 7;
        }
        out.write(u);
    }

    static int readZigZagVarInt(byte[] buf, int[] posRef, int limit) throws CorruptRegionEntryException {
        int pos = posRef[0];
        int result = 0;
        int shift = 0;
        while (pos < limit && shift < 35) {
            int b = buf[pos++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                posRef[0] = pos;
                return (result >>> 1) ^ -(result & 1);
            }
            shift += 7;
        }
        throw new CorruptRegionEntryException("CBIT T5: Truncated or malformed ZigZagVarInt");
    }

    static byte[] encodeSparsePostProcessing(short[][] sectionShorts) {
        if (sectionShorts == null || sectionShorts.length == 0 || sectionShorts.length > MAX_PP_SECTIONS) {
            return null;
        }
        int activeCount = 0;
        int totalShorts = 0;
        for (short[] arr : sectionShorts) {
            if (arr != null && arr.length > 0) {
                if (arr.length > MAX_PP_SHORTS_PER_SECTION) {
                    return null;
                }
                activeCount++;
                totalShorts += arr.length;
            }
        }
        if (activeCount == 0) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + activeCount * 3 + totalShorts * 2);
        out.write(sectionShorts.length & 0xFF);
        writeVarInt(out, activeCount);
        for (int s = 0; s < sectionShorts.length; s++) {
            short[] arr = sectionShorts[s];
            if (arr == null || arr.length == 0) {
                continue;
            }
            out.write(s & 0xFF);
            writeVarInt(out, arr.length);
            int prev = 0;
            for (short v : arr) {
                int cur = v & 0xFFFF;
                writeZigZagVarInt(out, cur - prev);
                prev = cur;
            }
        }
        return out.toByteArray();
    }

    static byte[] tryEncodeSparsePostProcessingFromList(Nbt.NbtList ppList) {
        if (ppList == null || ppList.elementType != Nbt.TAG_LIST) {
            return null;
        }
        int sectionCount = ppList.items.size();
        if (sectionCount == 0 || sectionCount > MAX_PP_SECTIONS) {
            return null;
        }
        short[][] sections = new short[sectionCount][];
        boolean anyNonEmpty = false;
        for (int s = 0; s < sectionCount; s++) {
            Object item = ppList.items.get(s);
            if (!(item instanceof Nbt.NbtList sub)) {
                return null;
            }
            int len = sub.items.size();
            if (len == 0) {
                continue;
            }
            if (sub.elementType != Nbt.TAG_SHORT || len > MAX_PP_SHORTS_PER_SECTION) {
                return null;
            }
            short[] arr = new short[len];
            for (int i = 0; i < len; i++) {
                Object sv = sub.items.get(i);
                if (!(sv instanceof Short sh)) {
                    return null;
                }
                arr[i] = sh;
            }
            sections[s] = arr;
            anyNonEmpty = true;
        }
        return anyNonEmpty ? encodeSparsePostProcessing(sections) : null;
    }

    static byte[] tryEncodeSparsePostProcessingFromNbtSlice(ByteBuffer src, int tagStart, int tagEnd) {
        if (tagStart < 0 || tagEnd <= tagStart + 8 || src.get(tagStart) != Nbt.TAG_LIST) {
            return null;
        }
        int nameLen = src.getShort(tagStart + 1) & 0xFFFF;
        int pos = tagStart + 3 + nameLen;
        if (pos + 5 > tagEnd) {
            return null;
        }
        byte outerElem = src.get(pos++);
        int sectionCount = src.getInt(pos);
        pos += 4;
        if (outerElem != Nbt.TAG_LIST || sectionCount <= 0 || sectionCount > MAX_PP_SECTIONS) {
            return null;
        }
        short[][] sections = new short[sectionCount][];
        boolean anyNonEmpty = false;
        for (int s = 0; s < sectionCount; s++) {
            if (pos + 5 > tagEnd) {
                return null;
            }
            byte subElem = src.get(pos++);
            int subLen = src.getInt(pos);
            pos += 4;
            if (subLen < 0 || subLen > MAX_PP_SHORTS_PER_SECTION) {
                return null;
            }
            if (subLen == 0) {
                continue;
            }
            if (subElem != Nbt.TAG_SHORT || pos + subLen * 2 > tagEnd) {
                return null;
            }
            short[] arr = new short[subLen];
            for (int i = 0; i < subLen; i++) {
                arr[i] = src.getShort(pos);
                pos += 2;
            }
            sections[s] = arr;
            anyNonEmpty = true;
        }
        if (pos != tagEnd || !anyNonEmpty) {
            return null;
        }
        return encodeSparsePostProcessing(sections);
    }

    static Nbt.NbtList decodeSparsePostProcessingToList(byte[] buf, int start, int limit)
            throws CorruptRegionEntryException {
        if (start >= limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated sparse PostProcessing header");
        }
        int[] posRef = {start};
        int sectionCount = buf[posRef[0]++] & 0xFF;
        if (sectionCount == 0 || sectionCount > MAX_PP_SECTIONS) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid sparse PostProcessing sectionCount: " + sectionCount);
        }
        int activeCount = readVarIntChecked(buf, posRef, limit);
        if (activeCount <= 0 || activeCount > sectionCount) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid sparse PostProcessing activeCount: " + activeCount);
        }
        List<Object> outer = new ArrayList<>(sectionCount);
        for (int s = 0; s < sectionCount; s++) {
            outer.add(new Nbt.NbtList(Nbt.TAG_SHORT, List.of()));
        }
        int prevSec = -1;
        for (int a = 0; a < activeCount; a++) {
            if (posRef[0] >= limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated sparse PostProcessing section entry");
            }
            int secIdx = buf[posRef[0]++] & 0xFF;
            if (secIdx <= prevSec || secIdx >= sectionCount) {
                throw new CorruptRegionEntryException("CBIT T5: Out-of-order or OOB sparse PostProcessing secIdx: " + secIdx);
            }
            prevSec = secIdx;
            int cnt = readVarIntChecked(buf, posRef, limit);
            if (cnt <= 0 || cnt > MAX_PP_SHORTS_PER_SECTION) {
                throw new CorruptRegionEntryException("CBIT T4: Invalid sparse PostProcessing short count: " + cnt);
            }
            List<Object> shorts = new ArrayList<>(cnt);
            int prev = 0;
            for (int i = 0; i < cnt; i++) {
                prev += readZigZagVarInt(buf, posRef, limit);
                shorts.add((short) (prev & 0xFFFF));
            }
            outer.set(secIdx, new Nbt.NbtList(Nbt.TAG_SHORT, shorts));
        }
        if (posRef[0] != limit) {
            throw new CorruptRegionEntryException("CBIT T5: Trailing bytes in sparse PostProcessing payload");
        }
        return new Nbt.NbtList(Nbt.TAG_LIST, outer);
    }

    static short[][] decodeSparsePostProcessingDirect(
            byte[] buf,
            int start,
            int limit
    ) throws CorruptRegionEntryException {
        if (start >= limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated sparse PostProcessing header");
        }
        int[] posRef = {start};
        int sectionCount = buf[posRef[0]++] & 0xFF;
        if (sectionCount == 0 || sectionCount > MAX_PP_SECTIONS) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid sparse PostProcessing sectionCount: " + sectionCount);
        }
        int activeCount = readVarIntChecked(buf, posRef, limit);
        if (activeCount <= 0 || activeCount > sectionCount) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid sparse PostProcessing activeCount: " + activeCount);
        }
        short[][] sections = new short[sectionCount][];
        int prevSec = -1;
        for (int a = 0; a < activeCount; a++) {
            if (posRef[0] >= limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated sparse PostProcessing section entry");
            }
            int secIdx = buf[posRef[0]++] & 0xFF;
            if (secIdx <= prevSec || secIdx >= sectionCount) {
                throw new CorruptRegionEntryException("CBIT T5: Out-of-order or OOB sparse PostProcessing secIdx: " + secIdx);
            }
            prevSec = secIdx;
            int cnt = readVarIntChecked(buf, posRef, limit);
            if (cnt <= 0 || cnt > MAX_PP_SHORTS_PER_SECTION) {
                throw new CorruptRegionEntryException("CBIT T4: Invalid sparse PostProcessing short count: " + cnt);
            }
            short[] arr = new short[cnt];
            int prev = 0;
            for (int i = 0; i < cnt; i++) {
                prev += readZigZagVarInt(buf, posRef, limit);
                arr[i] = (short) (prev & 0xFFFF);
            }
            sections[secIdx] = arr;
        }
        if (posRef[0] != limit) {
            throw new CorruptRegionEntryException("CBIT T5: Trailing bytes in sparse PostProcessing payload");
        }
        return sections;
    }

    static int decodeSparsePostProcessingToNbt(
            byte[] buf,
            int start,
            int limit,
            LcaSectionCodec.ScratchArena arena,
            int outPos
    ) throws CorruptRegionEntryException {
        if (start >= limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated sparse PostProcessing header");
        }
        int[] posRef = {start};
        int sectionCount = buf[posRef[0]++] & 0xFF;
        if (sectionCount == 0 || sectionCount > MAX_PP_SECTIONS) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid sparse PostProcessing sectionCount: " + sectionCount);
        }
        int activeCount = readVarIntChecked(buf, posRef, limit);
        if (activeCount <= 0 || activeCount > sectionCount) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid sparse PostProcessing activeCount: " + activeCount);
        }
        short[][] sections = new short[sectionCount][];
        int totalShorts = 0;
        int prevSec = -1;
        for (int a = 0; a < activeCount; a++) {
            if (posRef[0] >= limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated sparse PostProcessing section entry");
            }
            int secIdx = buf[posRef[0]++] & 0xFF;
            if (secIdx <= prevSec || secIdx >= sectionCount) {
                throw new CorruptRegionEntryException("CBIT T5: Out-of-order or OOB sparse PostProcessing secIdx: " + secIdx);
            }
            prevSec = secIdx;
            int cnt = readVarIntChecked(buf, posRef, limit);
            if (cnt <= 0 || cnt > MAX_PP_SHORTS_PER_SECTION) {
                throw new CorruptRegionEntryException("CBIT T4: Invalid sparse PostProcessing short count: " + cnt);
            }
            short[] arr = new short[cnt];
            int prev = 0;
            for (int i = 0; i < cnt; i++) {
                prev += readZigZagVarInt(buf, posRef, limit);
                arr[i] = (short) (prev & 0xFFFF);
            }
            sections[secIdx] = arr;
            totalShorts += cnt;
        }
        if (posRef[0] != limit) {
            throw new CorruptRegionEntryException("CBIT T5: Trailing bytes in sparse PostProcessing payload");
        }

        // Write TAG_List("PostProcessing") of TAG_List of TAG_Short
        byte[] nameUtf = "PostProcessing".getBytes(StandardCharsets.UTF_8);
        int needed = 3 + nameUtf.length + 5 + sectionCount * 5 + totalShorts * 2 + 16;
        byte[] out = arena.ensureNbtCap(outPos + needed);
        out[outPos++] = Nbt.TAG_LIST;
        out[outPos++] = (byte) ((nameUtf.length >>> 8) & 0xFF);
        out[outPos++] = (byte) (nameUtf.length & 0xFF);
        System.arraycopy(nameUtf, 0, out, outPos, nameUtf.length);
        outPos += nameUtf.length;
        out[outPos++] = Nbt.TAG_LIST;
        LcaSectionCodec.writeIntBEToBuf(out, outPos, sectionCount);
        outPos += 4;
        for (int s = 0; s < sectionCount; s++) {
            short[] arr = sections[s];
            out[outPos++] = Nbt.TAG_SHORT;
            if (arr == null) {
                out[outPos++] = 0;
                out[outPos++] = 0;
                out[outPos++] = 0;
                out[outPos++] = 0;
            } else {
                LcaSectionCodec.writeIntBEToBuf(out, outPos, arr.length);
                outPos += 4;
                for (short v : arr) {
                    out[outPos++] = (byte) ((v >>> 8) & 0xFF);
                    out[outPos++] = (byte) (v & 0xFF);
                }
            }
        }
        return outPos;
    }

    // =========================================================================
    // Dictionary-Encoded Structures (EXT_FLAG_DICT_STRUCTURES = 0x10)
    // =========================================================================

    private static final String[] STATIC_STRUCT_STRINGS = {
        "starts", "references", "Starts", "References", "id", "Children", "BB", "O", "GD",
        "ChunkX", "ChunkZ", "PosX", "PosY", "PosZ", "Entrances", "MST", "hps", "hr", "sc",
        "Num", "pool_element", "element_type", "location", "processors", "projection",
        "override_liquid_settings", "ground_level_delta", "rotation", "junctions",
        "source_x", "source_y", "source_z", "delta_y", "dest_proj", "source_ground_y",
        "minecraft:jigsaw", "minecraft:single_pool_element", "minecraft:empty_pool_element",
        "minecraft:feature_pool_element", "minecraft:list_pool_element",
        "minecraft:legacy_single_pool_element", "rigid", "terrain_matching", "minecraft:empty",
        "minecraft:mineshaft", "minecraft:mineshaft_mesa", "minecraft:mineshaft_corridor",
        "minecraft:mineshaft_crossing", "minecraft:mineshaft_stairs", "minecraft:mineshaft_room",
        "minecraft:ruined_portal", "minecraft:ruined_portal_desert", "minecraft:ruined_portal_jungle",
        "minecraft:ruined_portal_mountain", "minecraft:ruined_portal_nether",
        "minecraft:ruined_portal_ocean", "minecraft:ruined_portal_swamp",
        "minecraft:stronghold", "minecraft:trial_chambers", "minecraft:ancient_city",
        "minecraft:village_plains", "minecraft:village_desert", "minecraft:village_savanna",
        "minecraft:village_snowy", "minecraft:village_taiga",
        "minecraft:shipwreck", "minecraft:shipwreck_beached", "minecraft:ocean_ruin_cold",
        "minecraft:ocean_ruin_warm", "minecraft:buried_treasure", "minecraft:igloo",
        "minecraft:desert_pyramid", "minecraft:jungle_pyramid", "minecraft:swamp_hut",
        "minecraft:monument", "minecraft:mansion", "minecraft:pillager_outpost",
        "minecraft:trail_ruins", "minecraft:nether_fossil", "minecraft:fortress",
        "minecraft:bastion_remnant", "minecraft:end_city",
        "INVALID", "Processed", "Valid", "tf", "Type", "C", "D", "H", "L", "W", "X", "Y", "Z",
        "NONE", "CLOCKWISE_90", "CLOCKWISE_180", "COUNTERCLOCKWISE_90",
        "ignore_waterlogging", "apply_waterlogging", "Template", "Rot", "Integrity", "BiomeType",
        "Large", "Mossiness", "AirPocket", "Overgrown", "Vines", "ReplaceWithBlackstone", "Cold"
    };

    private static final Map<String, Integer> STATIC_STRUCT_STRING_IDS;
    private static final byte[][] STATIC_STRUCT_STRING_UTF8;

    static {
        Map<String, Integer> m = new HashMap<>(STATIC_STRUCT_STRINGS.length * 2);
        byte[][] utf = new byte[STATIC_STRUCT_STRINGS.length][];
        for (int i = 0; i < STATIC_STRUCT_STRINGS.length; i++) {
            m.put(STATIC_STRUCT_STRINGS[i], i);
            utf[i] = STATIC_STRUCT_STRINGS[i].getBytes(StandardCharsets.UTF_8);
        }
        STATIC_STRUCT_STRING_IDS = m;
        STATIC_STRUCT_STRING_UTF8 = utf;
    }

    private static int internStructString(String s, Map<String, Integer> localMap, List<String> localList) {
        Integer staticId = STATIC_STRUCT_STRING_IDS.get(s);
        if (staticId != null) {
            return staticId;
        }
        Integer localId = localMap.get(s);
        if (localId != null) {
            return localId;
        }
        int id = STATIC_STRUCT_STRINGS.length + localList.size();
        localMap.put(s, id);
        localList.add(s);
        return id;
    }

    static byte[] tryEncodeDictStructuresFromRootNbtBytes(byte[] rootNbt, int chunkX, int chunkZ) {
        if (rootNbt == null || rootNbt.length < 4 || rootNbt[0] != Nbt.TAG_COMPOUND) {
            return null;
        }
        int nameLen = ((rootNbt[1] & 0xFF) << 8) | (rootNbt[2] & 0xFF);
        int bodyStart = 3 + nameLen;
        if (bodyStart >= rootNbt.length) {
            return null;
        }
        return encodeDictStructuresFromCompoundBody(
                ByteBuffer.wrap(rootNbt), bodyStart, rootNbt.length, chunkX, chunkZ, rootNbt.length);
    }

    static byte[] tryEncodeDictStructuresFromNbtSlice(
            ByteBuffer src, int tagStart, int tagEnd, int chunkX, int chunkZ) {
        if (tagStart < 0 || tagEnd <= tagStart + 4 || src.get(tagStart) != Nbt.TAG_COMPOUND) {
            return null;
        }
        int nameLen = src.getShort(tagStart + 1) & 0xFFFF;
        int bodyStart = tagStart + 3 + nameLen;
        if (bodyStart >= tagEnd) {
            return null;
        }
        return encodeDictStructuresFromCompoundBody(src, bodyStart, tagEnd, chunkX, chunkZ, tagEnd - tagStart);
    }

    private static byte[] encodeDictStructuresFromCompoundBody(
            ByteBuffer src, int bodyStart, int limit, int chunkX, int chunkZ, int rawLen) {
        Map<String, Integer> localMap = new HashMap<>(32);
        List<String> localList = new ArrayList<>(32);
        ByteArrayOutputStream bodyOut = new ByteArrayOutputStream(Math.max(64, rawLen >>> 2));
        int[] posRef = {bodyStart};
        if (!encodeDictNbtCompoundBodyFromBuf(src, posRef, limit, chunkX, chunkZ, localMap, localList, bodyOut, 0)) {
            return null;
        }
        if (posRef[0] != limit || localList.size() > MAX_STRUCT_LOCAL_STRINGS) {
            return null;
        }
        return finalizeDictStructuresPayload(localList, bodyOut.toByteArray());
    }

    private static boolean encodeDictNbtCompoundBodyFromBuf(
            ByteBuffer src,
            int[] posRef,
            int limit,
            int chunkX,
            int chunkZ,
            Map<String, Integer> localMap,
            List<String> localList,
            ByteArrayOutputStream out,
            int depth
    ) {
        if (depth > MAX_STRUCT_NBT_DEPTH) {
            return false;
        }
        while (posRef[0] < limit) {
            int tagType = src.get(posRef[0]++) & 0xFF;
            out.write(tagType);
            if (tagType == Nbt.TAG_END) {
                return true;
            }
            if (posRef[0] + 2 > limit) {
                return false;
            }
            int kLen = src.getShort(posRef[0]) & 0xFFFF;
            posRef[0] += 2;
            if (posRef[0] + kLen > limit) {
                return false;
            }
            byte[] kBytes = new byte[kLen];
            src.duplicate().position(posRef[0]).get(kBytes);
            posRef[0] += kLen;
            String key = new String(kBytes, StandardCharsets.UTF_8);
            int keyId = internStructString(key, localMap, localList);
            if (localList.size() > MAX_STRUCT_LOCAL_STRINGS) {
                return false;
            }
            writeVarInt(out, keyId);
            if (!encodeDictNbtPayloadFromBuf(src, posRef, limit, tagType, chunkX, chunkZ, localMap, localList, out, depth)) {
                return false;
            }
        }
        return false;
    }

    private static boolean encodeDictNbtPayloadFromBuf(
            ByteBuffer src,
            int[] posRef,
            int limit,
            int tagType,
            int chunkX,
            int chunkZ,
            Map<String, Integer> localMap,
            List<String> localList,
            ByteArrayOutputStream out,
            int depth
    ) {
        int p = posRef[0];
        switch (tagType) {
            case Nbt.TAG_BYTE -> {
                if (p + 1 > limit) return false;
                out.write(src.get(p) & 0xFF);
                posRef[0] = p + 1;
                return true;
            }
            case Nbt.TAG_SHORT -> {
                if (p + 2 > limit) return false;
                writeZigZagVarInt(out, src.getShort(p));
                posRef[0] = p + 2;
                return true;
            }
            case Nbt.TAG_INT -> {
                if (p + 4 > limit) return false;
                writeZigZagVarInt(out, src.getInt(p));
                posRef[0] = p + 4;
                return true;
            }
            case Nbt.TAG_LONG -> {
                if (p + 8 > limit) return false;
                writeZigZagVarLong(out, src.getLong(p));
                posRef[0] = p + 8;
                return true;
            }
            case Nbt.TAG_FLOAT -> {
                if (p + 4 > limit) return false;
                int bits = src.getInt(p);
                out.write((bits >>> 24) & 0xFF);
                out.write((bits >>> 16) & 0xFF);
                out.write((bits >>> 8) & 0xFF);
                out.write(bits & 0xFF);
                posRef[0] = p + 4;
                return true;
            }
            case Nbt.TAG_DOUBLE -> {
                if (p + 8 > limit) return false;
                long bits = src.getLong(p);
                for (int sh = 56; sh >= 0; sh -= 8) {
                    out.write((int) ((bits >>> sh) & 0xFFL));
                }
                posRef[0] = p + 8;
                return true;
            }
            case Nbt.TAG_BYTE_ARRAY -> {
                if (p + 4 > limit) return false;
                int len = src.getInt(p);
                p += 4;
                if (len < 0 || p + len > limit) return false;
                writeVarInt(out, len);
                for (int i = 0; i < len; i++) {
                    out.write(src.get(p + i) & 0xFF);
                }
                posRef[0] = p + len;
                return true;
            }
            case Nbt.TAG_STRING -> {
                if (p + 2 > limit) return false;
                int len = src.getShort(p) & 0xFFFF;
                p += 2;
                if (p + len > limit) return false;
                byte[] sBytes = new byte[len];
                src.duplicate().position(p).get(sBytes);
                posRef[0] = p + len;
                String sVal = new String(sBytes, StandardCharsets.UTF_8);
                int strId = internStructString(sVal, localMap, localList);
                if (localList.size() > MAX_STRUCT_LOCAL_STRINGS) return false;
                writeVarInt(out, strId);
                return true;
            }
            case Nbt.TAG_LIST -> {
                if (depth >= MAX_STRUCT_NBT_DEPTH || p + 5 > limit) return false;
                int elemType = src.get(p++) & 0xFF;
                int cnt = src.getInt(p);
                p += 4;
                if (cnt < 0 || cnt > Nbt.MAX_ARRAY_LENGTH) return false;
                posRef[0] = p;
                out.write(elemType);
                writeVarInt(out, cnt);
                for (int i = 0; i < cnt; i++) {
                    if (!encodeDictNbtPayloadFromBuf(src, posRef, limit, elemType, chunkX, chunkZ, localMap, localList, out, depth + 1)) {
                        return false;
                    }
                }
                return true;
            }
            case Nbt.TAG_COMPOUND -> {
                return encodeDictNbtCompoundBodyFromBuf(src, posRef, limit, chunkX, chunkZ, localMap, localList, out, depth + 1);
            }
            case Nbt.TAG_INT_ARRAY -> {
                if (p + 4 > limit) return false;
                int len = src.getInt(p);
                p += 4;
                if (len < 0 || p + len * 4L > limit) return false;
                writeVarInt(out, len);
                if (len == 6) {
                    int minX = src.getInt(p);
                    int minY = src.getInt(p + 4);
                    int minZ = src.getInt(p + 8);
                    int maxX = src.getInt(p + 12);
                    int maxY = src.getInt(p + 16);
                    int maxZ = src.getInt(p + 20);
                    writeZigZagVarInt(out, minX - (chunkX << 4));
                    writeZigZagVarInt(out, minY);
                    writeZigZagVarInt(out, minZ - (chunkZ << 4));
                    writeZigZagVarInt(out, maxX - minX);
                    writeZigZagVarInt(out, maxY - minY);
                    writeZigZagVarInt(out, maxZ - minZ);
                } else {
                    for (int i = 0; i < len; i++) {
                        writeZigZagVarInt(out, src.getInt(p + i * 4));
                    }
                }
                posRef[0] = p + len * 4;
                return true;
            }
            case Nbt.TAG_LONG_ARRAY -> {
                if (p + 4 > limit) return false;
                int len = src.getInt(p);
                p += 4;
                if (len < 0 || p + len * 8L > limit) return false;
                writeVarInt(out, len);
                for (int i = 0; i < len; i++) {
                    writeZigZagVarLong(out, src.getLong(p + i * 8));
                }
                posRef[0] = p + len * 8;
                return true;
            }
            default -> { return false; }
        }
    }

    @SuppressWarnings("unchecked")
    static byte[] tryEncodeDictStructuresFromMap(Map<String, Object> map, int chunkX, int chunkZ) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        Map<String, Integer> localMap = new HashMap<>(32);
        List<String> localList = new ArrayList<>(32);
        ByteArrayOutputStream bodyOut = new ByteArrayOutputStream(128);
        if (!encodeDictNbtCompoundFromMap(map, chunkX, chunkZ, localMap, localList, bodyOut, 0)) {
            return null;
        }
        if (localList.size() > MAX_STRUCT_LOCAL_STRINGS) {
            return null;
        }
        return finalizeDictStructuresPayload(localList, bodyOut.toByteArray());
    }

    @SuppressWarnings("unchecked")
    private static boolean encodeDictNbtCompoundFromMap(
            Map<String, Object> map,
            int chunkX,
            int chunkZ,
            Map<String, Integer> localMap,
            List<String> localList,
            ByteArrayOutputStream out,
            int depth
    ) {
        if (depth > MAX_STRUCT_NBT_DEPTH) {
            return false;
        }
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object val = e.getValue();
            int tagType = nbtTagTypeOf(val);
            if (tagType <= 0) {
                return false;
            }
            out.write(tagType);
            int keyId = internStructString(e.getKey(), localMap, localList);
            if (localList.size() > MAX_STRUCT_LOCAL_STRINGS) {
                return false;
            }
            writeVarInt(out, keyId);
            if (!encodeDictNbtValueFromObj(val, tagType, chunkX, chunkZ, localMap, localList, out, depth)) {
                return false;
            }
        }
        out.write(Nbt.TAG_END);
        return true;
    }

    private static int nbtTagTypeOf(Object val) {
        if (val instanceof Byte) return Nbt.TAG_BYTE;
        if (val instanceof Short) return Nbt.TAG_SHORT;
        if (val instanceof Integer) return Nbt.TAG_INT;
        if (val instanceof Long) return Nbt.TAG_LONG;
        if (val instanceof Float) return Nbt.TAG_FLOAT;
        if (val instanceof Double) return Nbt.TAG_DOUBLE;
        if (val instanceof byte[]) return Nbt.TAG_BYTE_ARRAY;
        if (val instanceof String) return Nbt.TAG_STRING;
        if (val instanceof Nbt.NbtList) return Nbt.TAG_LIST;
        if (val instanceof Map<?, ?>) return Nbt.TAG_COMPOUND;
        if (val instanceof int[]) return Nbt.TAG_INT_ARRAY;
        if (val instanceof long[]) return Nbt.TAG_LONG_ARRAY;
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static boolean encodeDictNbtValueFromObj(
            Object val,
            int tagType,
            int chunkX,
            int chunkZ,
            Map<String, Integer> localMap,
            List<String> localList,
            ByteArrayOutputStream out,
            int depth
    ) {
        switch (tagType) {
            case Nbt.TAG_BYTE -> {
                out.write(((Byte) val) & 0xFF);
                return true;
            }
            case Nbt.TAG_SHORT -> {
                writeZigZagVarInt(out, (Short) val);
                return true;
            }
            case Nbt.TAG_INT -> {
                writeZigZagVarInt(out, (Integer) val);
                return true;
            }
            case Nbt.TAG_LONG -> {
                writeZigZagVarLong(out, (Long) val);
                return true;
            }
            case Nbt.TAG_FLOAT -> {
                int bits = Float.floatToRawIntBits((Float) val);
                out.write((bits >>> 24) & 0xFF);
                out.write((bits >>> 16) & 0xFF);
                out.write((bits >>> 8) & 0xFF);
                out.write(bits & 0xFF);
                return true;
            }
            case Nbt.TAG_DOUBLE -> {
                long bits = Double.doubleToRawLongBits((Double) val);
                for (int sh = 56; sh >= 0; sh -= 8) {
                    out.write((int) ((bits >>> sh) & 0xFFL));
                }
                return true;
            }
            case Nbt.TAG_BYTE_ARRAY -> {
                byte[] arr = (byte[]) val;
                writeVarInt(out, arr.length);
                out.writeBytes(arr);
                return true;
            }
            case Nbt.TAG_STRING -> {
                int strId = internStructString((String) val, localMap, localList);
                if (localList.size() > MAX_STRUCT_LOCAL_STRINGS) return false;
                writeVarInt(out, strId);
                return true;
            }
            case Nbt.TAG_LIST -> {
                if (depth >= MAX_STRUCT_NBT_DEPTH) return false;
                Nbt.NbtList nl = (Nbt.NbtList) val;
                out.write(nl.elementType & 0xFF);
                writeVarInt(out, nl.items.size());
                for (Object item : nl.items) {
                    if (!encodeDictNbtValueFromObj(item, nl.elementType, chunkX, chunkZ, localMap, localList, out, depth + 1)) {
                        return false;
                    }
                }
                return true;
            }
            case Nbt.TAG_COMPOUND -> {
                return encodeDictNbtCompoundFromMap((Map<String, Object>) val, chunkX, chunkZ, localMap, localList, out, depth + 1);
            }
            case Nbt.TAG_INT_ARRAY -> {
                int[] arr = (int[]) val;
                writeVarInt(out, arr.length);
                if (arr.length == 6) {
                    writeZigZagVarInt(out, arr[0] - (chunkX << 4));
                    writeZigZagVarInt(out, arr[1]);
                    writeZigZagVarInt(out, arr[2] - (chunkZ << 4));
                    writeZigZagVarInt(out, arr[3] - arr[0]);
                    writeZigZagVarInt(out, arr[4] - arr[1]);
                    writeZigZagVarInt(out, arr[5] - arr[2]);
                } else {
                    for (int v : arr) {
                        writeZigZagVarInt(out, v);
                    }
                }
                return true;
            }
            case Nbt.TAG_LONG_ARRAY -> {
                long[] arr = (long[]) val;
                writeVarInt(out, arr.length);
                for (long v : arr) {
                    writeZigZagVarLong(out, v);
                }
                return true;
            }
            default -> { return false; }
        }
    }

    private static byte[] finalizeDictStructuresPayload(List<String> localList, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + localList.size() * 16 + body.length);
        writeVarInt(out, localList.size());
        for (String s : localList) {
            byte[] utf = s.getBytes(StandardCharsets.UTF_8);
            writeVarInt(out, utf.length);
            out.writeBytes(utf);
        }
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static String resolveDictStructString(int id, String[] localStrings) throws CorruptRegionEntryException {
        if (id >= 0 && id < STATIC_STRUCT_STRINGS.length) {
            return STATIC_STRUCT_STRINGS[id];
        }
        int localIdx = id - STATIC_STRUCT_STRINGS.length;
        if (localIdx < 0 || localIdx >= localStrings.length) {
            throw new CorruptRegionEntryException("CBIT T7: Dictionary structure string ID out of bounds: " + id);
        }
        return localStrings[localIdx];
    }

    private static byte[] resolveDictStructUtf8(int id, byte[][] localUtf8) throws CorruptRegionEntryException {
        if (id >= 0 && id < STATIC_STRUCT_STRING_UTF8.length) {
            return STATIC_STRUCT_STRING_UTF8[id];
        }
        int localIdx = id - STATIC_STRUCT_STRING_UTF8.length;
        if (localIdx < 0 || localIdx >= localUtf8.length) {
            throw new CorruptRegionEntryException("CBIT T7: Dictionary structure string ID out of bounds: " + id);
        }
        return localUtf8[localIdx];
    }

    static LinkedHashMap<String, Object> decodeDictStructuresToMap(
            byte[] buf, int start, int limit, int chunkX, int chunkZ) throws CorruptRegionEntryException {
        int[] posRef = {start};
        int localCount = readVarIntChecked(buf, posRef, limit);
        if (localCount < 0 || localCount > MAX_STRUCT_LOCAL_STRINGS) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid dict structures localCount: " + localCount);
        }
        String[] localStrings = new String[localCount];
        for (int i = 0; i < localCount; i++) {
            int uLen = readVarIntChecked(buf, posRef, limit);
            if (uLen < 0 || posRef[0] + uLen > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated dict structures local string");
            }
            localStrings[i] = new String(buf, posRef[0], uLen, StandardCharsets.UTF_8);
            posRef[0] += uLen;
        }
        LinkedHashMap<String, Object> root = decodeDictNbtCompoundToMap(buf, posRef, limit, chunkX, chunkZ, localStrings, 0);
        if (posRef[0] != limit) {
            throw new CorruptRegionEntryException("CBIT T5: Trailing bytes in dict structures payload");
        }
        return root;
    }

    private static LinkedHashMap<String, Object> decodeDictNbtCompoundToMap(
            byte[] buf, int[] posRef, int limit, int chunkX, int chunkZ, String[] localStrings, int depth
    ) throws CorruptRegionEntryException {
        if (depth > MAX_STRUCT_NBT_DEPTH) {
            throw new CorruptRegionEntryException("CBIT T5: Dict structures NBT depth exceeded");
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        while (posRef[0] < limit) {
            int tagType = buf[posRef[0]++] & 0xFF;
            if (tagType == Nbt.TAG_END) {
                return map;
            }
            int keyId = readVarIntChecked(buf, posRef, limit);
            String key = resolveDictStructString(keyId, localStrings);
            Object val = decodeDictNbtValueToObj(buf, posRef, limit, tagType, chunkX, chunkZ, localStrings, depth);
            map.put(key, val);
        }
        throw new CorruptRegionEntryException("CBIT T5: Unterminated dict structures TAG_Compound");
    }

    private static Object decodeDictNbtValueToObj(
            byte[] buf, int[] posRef, int limit, int tagType, int chunkX, int chunkZ, String[] localStrings, int depth
    ) throws CorruptRegionEntryException {
        switch (tagType) {
            case Nbt.TAG_BYTE -> {
                if (posRef[0] >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated dict TAG_Byte");
                return buf[posRef[0]++];
            }
            case Nbt.TAG_SHORT -> {
                return (short) readZigZagVarInt(buf, posRef, limit);
            }
            case Nbt.TAG_INT -> {
                return readZigZagVarInt(buf, posRef, limit);
            }
            case Nbt.TAG_LONG -> {
                return readZigZagVarLong(buf, posRef, limit);
            }
            case Nbt.TAG_FLOAT -> {
                if (posRef[0] + 4 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated dict TAG_Float");
                int bits = LcaSectionCodec.readIntBE(buf, posRef[0]);
                posRef[0] += 4;
                return Float.intBitsToFloat(bits);
            }
            case Nbt.TAG_DOUBLE -> {
                if (posRef[0] + 8 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated dict TAG_Double");
                long bits = (long) LcaRegionReader.LONG_BE_VIEW.get(buf, posRef[0]);
                posRef[0] += 8;
                return Double.longBitsToDouble(bits);
            }
            case Nbt.TAG_BYTE_ARRAY -> {
                int len = readVarIntChecked(buf, posRef, limit);
                if (len < 0 || posRef[0] + len > limit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated dict TAG_Byte_Array");
                }
                byte[] arr = Arrays.copyOfRange(buf, posRef[0], posRef[0] + len);
                posRef[0] += len;
                return arr;
            }
            case Nbt.TAG_STRING -> {
                int strId = readVarIntChecked(buf, posRef, limit);
                return resolveDictStructString(strId, localStrings);
            }
            case Nbt.TAG_LIST -> {
                if (depth >= MAX_STRUCT_NBT_DEPTH || posRef[0] >= limit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated or deep dict TAG_List");
                }
                byte elemType = buf[posRef[0]++];
                int cnt = readVarIntChecked(buf, posRef, limit);
                if (cnt < 0 || cnt > Nbt.MAX_ARRAY_LENGTH) {
                    throw new CorruptRegionEntryException("CBIT T4: Invalid dict TAG_List count: " + cnt);
                }
                List<Object> items = new ArrayList<>(Math.min(cnt, 64));
                for (int i = 0; i < cnt; i++) {
                    items.add(decodeDictNbtValueToObj(buf, posRef, limit, elemType & 0xFF, chunkX, chunkZ, localStrings, depth + 1));
                }
                return new Nbt.NbtList(elemType, items);
            }
            case Nbt.TAG_COMPOUND -> {
                return decodeDictNbtCompoundToMap(buf, posRef, limit, chunkX, chunkZ, localStrings, depth + 1);
            }
            case Nbt.TAG_INT_ARRAY -> {
                int len = readVarIntChecked(buf, posRef, limit);
                if (len < 0 || len > Nbt.MAX_ARRAY_LENGTH) {
                    throw new CorruptRegionEntryException("CBIT T4: Invalid dict TAG_Int_Array len: " + len);
                }
                int[] arr = new int[len];
                if (len == 6) {
                    int minX = readZigZagVarInt(buf, posRef, limit) + (chunkX << 4);
                    int minY = readZigZagVarInt(buf, posRef, limit);
                    int minZ = readZigZagVarInt(buf, posRef, limit) + (chunkZ << 4);
                    arr[0] = minX;
                    arr[1] = minY;
                    arr[2] = minZ;
                    arr[3] = minX + readZigZagVarInt(buf, posRef, limit);
                    arr[4] = minY + readZigZagVarInt(buf, posRef, limit);
                    arr[5] = minZ + readZigZagVarInt(buf, posRef, limit);
                } else {
                    for (int i = 0; i < len; i++) {
                        arr[i] = readZigZagVarInt(buf, posRef, limit);
                    }
                }
                return arr;
            }
            case Nbt.TAG_LONG_ARRAY -> {
                int len = readVarIntChecked(buf, posRef, limit);
                if (len < 0 || len > Nbt.MAX_ARRAY_LENGTH) {
                    throw new CorruptRegionEntryException("CBIT T4: Invalid dict TAG_Long_Array len: " + len);
                }
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = readZigZagVarLong(buf, posRef, limit);
                }
                return arr;
            }
            default -> throw new CorruptRegionEntryException("CBIT T5: Unknown dict NBT tagType: " + tagType);
        }
    }

    static int decodeDictStructuresToNbt(
            byte[] buf,
            int start,
            int limit,
            int chunkX,
            int chunkZ,
            LcaSectionCodec.ScratchArena arena,
            int outPos
    ) throws CorruptRegionEntryException {
        int[] posRef = {start};
        int localCount = readVarIntChecked(buf, posRef, limit);
        if (localCount < 0 || localCount > MAX_STRUCT_LOCAL_STRINGS) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid dict structures localCount: " + localCount);
        }
        byte[][] localUtf8 = new byte[localCount][];
        for (int i = 0; i < localCount; i++) {
            int uLen = readVarIntChecked(buf, posRef, limit);
            if (uLen < 0 || posRef[0] + uLen > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated dict structures local string");
            }
            localUtf8[i] = Arrays.copyOfRange(buf, posRef[0], posRef[0] + uLen);
            posRef[0] += uLen;
        }

        byte[] nameUtf = "structures".getBytes(StandardCharsets.UTF_8);
        byte[] out = arena.ensureNbtCap(outPos + 3 + nameUtf.length + 64);
        out[outPos++] = Nbt.TAG_COMPOUND;
        out[outPos++] = (byte) ((nameUtf.length >>> 8) & 0xFF);
        out[outPos++] = (byte) (nameUtf.length & 0xFF);
        System.arraycopy(nameUtf, 0, out, outPos, nameUtf.length);
        outPos += nameUtf.length;

        outPos = decodeDictNbtCompoundToNbt(buf, posRef, limit, chunkX, chunkZ, localUtf8, arena, outPos, 0);
        if (posRef[0] != limit) {
            throw new CorruptRegionEntryException("CBIT T5: Trailing bytes in dict structures payload");
        }
        return outPos;
    }

    private static int decodeDictNbtCompoundToNbt(
            byte[] buf,
            int[] posRef,
            int limit,
            int chunkX,
            int chunkZ,
            byte[][] localUtf8,
            LcaSectionCodec.ScratchArena arena,
            int outPos,
            int depth
    ) throws CorruptRegionEntryException {
        if (depth > MAX_STRUCT_NBT_DEPTH) {
            throw new CorruptRegionEntryException("CBIT T5: Dict structures NBT depth exceeded");
        }
        while (posRef[0] < limit) {
            int tagType = buf[posRef[0]++] & 0xFF;
            if (tagType == Nbt.TAG_END) {
                byte[] out = arena.ensureNbtCap(outPos + 1);
                out[outPos++] = Nbt.TAG_END;
                return outPos;
            }
            int keyId = readVarIntChecked(buf, posRef, limit);
            byte[] kUtf = resolveDictStructUtf8(keyId, localUtf8);
            byte[] out = arena.ensureNbtCap(outPos + 3 + kUtf.length + 16);
            out[outPos++] = (byte) tagType;
            out[outPos++] = (byte) ((kUtf.length >>> 8) & 0xFF);
            out[outPos++] = (byte) (kUtf.length & 0xFF);
            System.arraycopy(kUtf, 0, out, outPos, kUtf.length);
            outPos += kUtf.length;
            outPos = decodeDictNbtPayloadToNbt(buf, posRef, limit, tagType, chunkX, chunkZ, localUtf8, arena, outPos, depth);
        }
        throw new CorruptRegionEntryException("CBIT T5: Unterminated dict structures TAG_Compound");
    }

    private static int decodeDictNbtPayloadToNbt(
            byte[] buf,
            int[] posRef,
            int limit,
            int tagType,
            int chunkX,
            int chunkZ,
            byte[][] localUtf8,
            LcaSectionCodec.ScratchArena arena,
            int outPos,
            int depth
    ) throws CorruptRegionEntryException {
        switch (tagType) {
            case Nbt.TAG_BYTE -> {
                if (posRef[0] >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated dict TAG_Byte");
                byte[] out = arena.ensureNbtCap(outPos + 1);
                out[outPos++] = buf[posRef[0]++];
                return outPos;
            }
            case Nbt.TAG_SHORT -> {
                short v = (short) readZigZagVarInt(buf, posRef, limit);
                byte[] out = arena.ensureNbtCap(outPos + 2);
                out[outPos++] = (byte) ((v >>> 8) & 0xFF);
                out[outPos++] = (byte) (v & 0xFF);
                return outPos;
            }
            case Nbt.TAG_INT -> {
                int v = readZigZagVarInt(buf, posRef, limit);
                byte[] out = arena.ensureNbtCap(outPos + 4);
                LcaSectionCodec.writeIntBEToBuf(out, outPos, v);
                return outPos + 4;
            }
            case Nbt.TAG_LONG -> {
                long v = readZigZagVarLong(buf, posRef, limit);
                byte[] out = arena.ensureNbtCap(outPos + 8);
                LcaSectionCodec.writeLongBEToBuf(out, outPos, v);
                return outPos + 8;
            }
            case Nbt.TAG_FLOAT -> {
                if (posRef[0] + 4 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated dict TAG_Float");
                byte[] out = arena.ensureNbtCap(outPos + 4);
                System.arraycopy(buf, posRef[0], out, outPos, 4);
                posRef[0] += 4;
                return outPos + 4;
            }
            case Nbt.TAG_DOUBLE -> {
                if (posRef[0] + 8 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated dict TAG_Double");
                byte[] out = arena.ensureNbtCap(outPos + 8);
                System.arraycopy(buf, posRef[0], out, outPos, 8);
                posRef[0] += 8;
                return outPos + 8;
            }
            case Nbt.TAG_BYTE_ARRAY -> {
                int len = readVarIntChecked(buf, posRef, limit);
                if (len < 0 || posRef[0] + len > limit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated dict TAG_Byte_Array");
                }
                byte[] out = arena.ensureNbtCap(outPos + 4 + len);
                LcaSectionCodec.writeIntBEToBuf(out, outPos, len);
                outPos += 4;
                System.arraycopy(buf, posRef[0], out, outPos, len);
                posRef[0] += len;
                return outPos + len;
            }
            case Nbt.TAG_STRING -> {
                int strId = readVarIntChecked(buf, posRef, limit);
                byte[] sUtf = resolveDictStructUtf8(strId, localUtf8);
                byte[] out = arena.ensureNbtCap(outPos + 2 + sUtf.length);
                out[outPos++] = (byte) ((sUtf.length >>> 8) & 0xFF);
                out[outPos++] = (byte) (sUtf.length & 0xFF);
                System.arraycopy(sUtf, 0, out, outPos, sUtf.length);
                return outPos + sUtf.length;
            }
            case Nbt.TAG_LIST -> {
                if (depth >= MAX_STRUCT_NBT_DEPTH || posRef[0] >= limit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated or deep dict TAG_List");
                }
                byte elemType = buf[posRef[0]++];
                int cnt = readVarIntChecked(buf, posRef, limit);
                if (cnt < 0 || cnt > Nbt.MAX_ARRAY_LENGTH) {
                    throw new CorruptRegionEntryException("CBIT T4: Invalid dict TAG_List count: " + cnt);
                }
                byte[] out = arena.ensureNbtCap(outPos + 5);
                out[outPos++] = elemType;
                LcaSectionCodec.writeIntBEToBuf(out, outPos, cnt);
                outPos += 4;
                for (int i = 0; i < cnt; i++) {
                    outPos = decodeDictNbtPayloadToNbt(buf, posRef, limit, elemType & 0xFF, chunkX, chunkZ, localUtf8, arena, outPos, depth + 1);
                }
                return outPos;
            }
            case Nbt.TAG_COMPOUND -> {
                return decodeDictNbtCompoundToNbt(buf, posRef, limit, chunkX, chunkZ, localUtf8, arena, outPos, depth + 1);
            }
            case Nbt.TAG_INT_ARRAY -> {
                int len = readVarIntChecked(buf, posRef, limit);
                if (len < 0 || len > Nbt.MAX_ARRAY_LENGTH) {
                    throw new CorruptRegionEntryException("CBIT T4: Invalid dict TAG_Int_Array len: " + len);
                }
                byte[] out = arena.ensureNbtCap(outPos + 4 + len * 4);
                LcaSectionCodec.writeIntBEToBuf(out, outPos, len);
                outPos += 4;
                if (len == 6) {
                    int minX = readZigZagVarInt(buf, posRef, limit) + (chunkX << 4);
                    int minY = readZigZagVarInt(buf, posRef, limit);
                    int minZ = readZigZagVarInt(buf, posRef, limit) + (chunkZ << 4);
                    int maxX = minX + readZigZagVarInt(buf, posRef, limit);
                    int maxY = minY + readZigZagVarInt(buf, posRef, limit);
                    int maxZ = minZ + readZigZagVarInt(buf, posRef, limit);
                    LcaSectionCodec.writeIntBEToBuf(out, outPos, minX);
                    LcaSectionCodec.writeIntBEToBuf(out, outPos + 4, minY);
                    LcaSectionCodec.writeIntBEToBuf(out, outPos + 8, minZ);
                    LcaSectionCodec.writeIntBEToBuf(out, outPos + 12, maxX);
                    LcaSectionCodec.writeIntBEToBuf(out, outPos + 16, maxY);
                    LcaSectionCodec.writeIntBEToBuf(out, outPos + 20, maxZ);
                    return outPos + 24;
                }
                for (int i = 0; i < len; i++) {
                    LcaSectionCodec.writeIntBEToBuf(out, outPos, readZigZagVarInt(buf, posRef, limit));
                    outPos += 4;
                }
                return outPos;
            }
            case Nbt.TAG_LONG_ARRAY -> {
                int len = readVarIntChecked(buf, posRef, limit);
                if (len < 0 || len > Nbt.MAX_ARRAY_LENGTH) {
                    throw new CorruptRegionEntryException("CBIT T4: Invalid dict TAG_Long_Array len: " + len);
                }
                byte[] out = arena.ensureNbtCap(outPos + 4 + len * 8);
                LcaSectionCodec.writeIntBEToBuf(out, outPos, len);
                outPos += 4;
                for (int i = 0; i < len; i++) {
                    LcaSectionCodec.writeLongBEToBuf(out, outPos, readZigZagVarLong(buf, posRef, limit));
                    outPos += 8;
                }
                return outPos;
            }
            default -> throw new CorruptRegionEntryException("CBIT T5: Unknown dict NBT tagType: " + tagType);
        }
    }

    /**
     * Decodes a v3 {@code EXT_FLAG_DICT_STRUCTURES} payload directly into a caller-supplied
     * compound object via {@link LcaDecodedChunkMeta.DirectNbtBuilder} with zero intermediate NBT
     * byte synthesis ({@code anvil-api-ADR-007}).
     */
    static <C, L> C decodeDictStructuresDirect(
            byte[] buf,
            int start,
            int limit,
            int chunkX,
            int chunkZ,
            LcaDecodedChunkMeta.DirectNbtBuilder<C, L> builder
    ) throws CorruptRegionEntryException {
        int[] posRef = {start};
        int localCount = readVarIntChecked(buf, posRef, limit);
        if (localCount < 0 || localCount > MAX_STRUCT_LOCAL_STRINGS) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid dict structures localCount: " + localCount);
        }
        String[] localStrings = new String[localCount];
        for (int i = 0; i < localCount; i++) {
            int uLen = readVarIntChecked(buf, posRef, limit);
            if (uLen < 0 || posRef[0] + uLen > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated dict structures local string");
            }
            localStrings[i] = new String(buf, posRef[0], uLen, StandardCharsets.UTF_8);
            posRef[0] += uLen;
        }
        C root = decodeDictNbtCompoundDirect(buf, posRef, limit, chunkX, chunkZ, localStrings, builder, 0);
        if (posRef[0] != limit) {
            throw new CorruptRegionEntryException("CBIT T5: Trailing bytes in dict structures payload");
        }
        return root;
    }

    private static <C, L> C decodeDictNbtCompoundDirect(
            byte[] buf,
            int[] posRef,
            int limit,
            int chunkX,
            int chunkZ,
            String[] localStrings,
            LcaDecodedChunkMeta.DirectNbtBuilder<C, L> builder,
            int depth
    ) throws CorruptRegionEntryException {
        if (depth > MAX_STRUCT_NBT_DEPTH) {
            throw new CorruptRegionEntryException("CBIT T5: Dict structures NBT depth exceeded");
        }
        C compound = builder.newCompound();
        while (posRef[0] < limit) {
            int tagType = buf[posRef[0]++] & 0xFF;
            if (tagType == Nbt.TAG_END) {
                return compound;
            }
            int keyId = readVarIntChecked(buf, posRef, limit);
            String key = resolveDictStructString(keyId, localStrings);
            decodeDictNbtPutField(buf, posRef, limit, tagType, key, chunkX, chunkZ, localStrings, builder, compound, depth);
        }
        throw new CorruptRegionEntryException("CBIT T5: Unterminated dict structures TAG_Compound");
    }

    private static <C, L> void decodeDictNbtPutField(
            byte[] buf,
            int[] posRef,
            int limit,
            int tagType,
            String key,
            int chunkX,
            int chunkZ,
            String[] localStrings,
            LcaDecodedChunkMeta.DirectNbtBuilder<C, L> builder,
            C compound,
            int depth
    ) throws CorruptRegionEntryException {
        switch (tagType) {
            case Nbt.TAG_BYTE -> {
                if (posRef[0] >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated dict TAG_Byte");
                builder.putByte(compound, key, buf[posRef[0]++]);
            }
            case Nbt.TAG_SHORT -> builder.putShort(compound, key, (short) readZigZagVarInt(buf, posRef, limit));
            case Nbt.TAG_INT -> builder.putInt(compound, key, readZigZagVarInt(buf, posRef, limit));
            case Nbt.TAG_LONG -> builder.putLong(compound, key, readZigZagVarLong(buf, posRef, limit));
            case Nbt.TAG_FLOAT -> {
                if (posRef[0] + 4 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated dict TAG_Float");
                int bits = readIntBE(buf, posRef[0]);
                posRef[0] += 4;
                builder.putFloat(compound, key, Float.intBitsToFloat(bits));
            }
            case Nbt.TAG_DOUBLE -> {
                if (posRef[0] + 8 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated dict TAG_Double");
                long bits = readLongBE(buf, posRef[0]);
                posRef[0] += 8;
                builder.putDouble(compound, key, Double.longBitsToDouble(bits));
            }
            case Nbt.TAG_BYTE_ARRAY -> {
                int len = readVarIntChecked(buf, posRef, limit);
                if (len < 0 || posRef[0] + len > limit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated dict TAG_Byte_Array");
                }
                byte[] arr = Arrays.copyOfRange(buf, posRef[0], posRef[0] + len);
                posRef[0] += len;
                builder.putByteArray(compound, key, arr);
            }
            case Nbt.TAG_STRING -> {
                int strId = readVarIntChecked(buf, posRef, limit);
                builder.putString(compound, key, resolveDictStructString(strId, localStrings));
            }
            case Nbt.TAG_LIST -> builder.putList(
                    compound, key,
                    decodeDictNbtListDirect(buf, posRef, limit, chunkX, chunkZ, localStrings, builder, depth));
            case Nbt.TAG_COMPOUND -> builder.putCompound(
                    compound, key,
                    decodeDictNbtCompoundDirect(buf, posRef, limit, chunkX, chunkZ, localStrings, builder, depth + 1));
            case Nbt.TAG_INT_ARRAY -> builder.putIntArray(
                    compound, key, decodeDictIntArray(buf, posRef, limit, chunkX, chunkZ));
            case Nbt.TAG_LONG_ARRAY -> builder.putLongArray(
                    compound, key, decodeDictLongArray(buf, posRef, limit));
            default -> throw new CorruptRegionEntryException("CBIT T5: Unknown dict NBT tagType: " + tagType);
        }
    }

    private static <C, L> L decodeDictNbtListDirect(
            byte[] buf,
            int[] posRef,
            int limit,
            int chunkX,
            int chunkZ,
            String[] localStrings,
            LcaDecodedChunkMeta.DirectNbtBuilder<C, L> builder,
            int depth
    ) throws CorruptRegionEntryException {
        if (depth >= MAX_STRUCT_NBT_DEPTH || posRef[0] >= limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated or deep dict TAG_List");
        }
        byte elemType = buf[posRef[0]++];
        int cnt = readVarIntChecked(buf, posRef, limit);
        if (cnt < 0 || cnt > Nbt.MAX_ARRAY_LENGTH) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid dict TAG_List count: " + cnt);
        }
        L list = builder.newList(elemType, cnt);
        int et = elemType & 0xFF;
        for (int i = 0; i < cnt; i++) {
            switch (et) {
                case Nbt.TAG_BYTE -> {
                    if (posRef[0] >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated dict list TAG_Byte");
                    builder.addByte(list, buf[posRef[0]++]);
                }
                case Nbt.TAG_SHORT -> builder.addShort(list, (short) readZigZagVarInt(buf, posRef, limit));
                case Nbt.TAG_INT -> builder.addInt(list, readZigZagVarInt(buf, posRef, limit));
                case Nbt.TAG_LONG -> builder.addLong(list, readZigZagVarLong(buf, posRef, limit));
                case Nbt.TAG_FLOAT -> {
                    if (posRef[0] + 4 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated dict list TAG_Float");
                    int bits = readIntBE(buf, posRef[0]);
                    posRef[0] += 4;
                    builder.addFloat(list, Float.intBitsToFloat(bits));
                }
                case Nbt.TAG_DOUBLE -> {
                    if (posRef[0] + 8 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated dict list TAG_Double");
                    long bits = readLongBE(buf, posRef[0]);
                    posRef[0] += 8;
                    builder.addDouble(list, Double.longBitsToDouble(bits));
                }
                case Nbt.TAG_BYTE_ARRAY -> {
                    int len = readVarIntChecked(buf, posRef, limit);
                    if (len < 0 || posRef[0] + len > limit) {
                        throw new CorruptRegionEntryException("CBIT T5: Truncated dict list TAG_Byte_Array");
                    }
                    byte[] arr = Arrays.copyOfRange(buf, posRef[0], posRef[0] + len);
                    posRef[0] += len;
                    builder.addByteArray(list, arr);
                }
                case Nbt.TAG_STRING -> {
                    int strId = readVarIntChecked(buf, posRef, limit);
                    builder.addString(list, resolveDictStructString(strId, localStrings));
                }
                case Nbt.TAG_LIST -> builder.addList(
                        list, decodeDictNbtListDirect(buf, posRef, limit, chunkX, chunkZ, localStrings, builder, depth + 1));
                case Nbt.TAG_COMPOUND -> builder.addCompound(
                        list, decodeDictNbtCompoundDirect(buf, posRef, limit, chunkX, chunkZ, localStrings, builder, depth + 1));
                case Nbt.TAG_INT_ARRAY -> builder.addIntArray(
                        list, decodeDictIntArray(buf, posRef, limit, chunkX, chunkZ));
                case Nbt.TAG_LONG_ARRAY -> builder.addLongArray(
                        list, decodeDictLongArray(buf, posRef, limit));
                default -> throw new CorruptRegionEntryException("CBIT T5: Unknown dict list tagType: " + et);
            }
        }
        return list;
    }

    private static int[] decodeDictIntArray(byte[] buf, int[] posRef, int limit, int chunkX, int chunkZ)
            throws CorruptRegionEntryException {
        int len = readVarIntChecked(buf, posRef, limit);
        if (len < 0 || len > Nbt.MAX_ARRAY_LENGTH) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid dict TAG_Int_Array len: " + len);
        }
        int[] arr = new int[len];
        if (len == 6) {
            int minX = readZigZagVarInt(buf, posRef, limit) + (chunkX << 4);
            int minY = readZigZagVarInt(buf, posRef, limit);
            int minZ = readZigZagVarInt(buf, posRef, limit) + (chunkZ << 4);
            arr[0] = minX;
            arr[1] = minY;
            arr[2] = minZ;
            arr[3] = minX + readZigZagVarInt(buf, posRef, limit);
            arr[4] = minY + readZigZagVarInt(buf, posRef, limit);
            arr[5] = minZ + readZigZagVarInt(buf, posRef, limit);
            return arr;
        }
        for (int i = 0; i < len; i++) {
            arr[i] = readZigZagVarInt(buf, posRef, limit);
        }
        return arr;
    }

    private static long[] decodeDictLongArray(byte[] buf, int[] posRef, int limit)
            throws CorruptRegionEntryException {
        int len = readVarIntChecked(buf, posRef, limit);
        if (len < 0 || len > Nbt.MAX_ARRAY_LENGTH) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid dict TAG_Long_Array len: " + len);
        }
        long[] arr = new long[len];
        for (int i = 0; i < len; i++) {
            arr[i] = readZigZagVarLong(buf, posRef, limit);
        }
        return arr;
    }

    /**
     * Decodes a v3 {@code EXT_FLAG_COLUMNAR_BLOCK_ENTITIES} payload directly into a caller-supplied
     * list of compound objects via {@link DirectNbtBuilder} with zero intermediate NBT byte
     * synthesis or {@code FastNBTParser} ({@code anvil-api-ADR-007}).
     */
    public static <C, L> L decodeColumnarBlockEntitiesDirect(
            byte[] buf,
            int start,
            int limit,
            int chunkX,
            int chunkZ,
            LcaDecodedChunkMeta.DirectNbtBuilder<C, L> builder
    ) throws CorruptRegionEntryException {
        int[] posRef = {start};
        int count = readVarIntChecked(buf, posRef, limit);
        if (count <= 0 || count > MAX_BLOCK_ENTITIES_PER_CHUNK || posRef[0] >= limit) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid columnar block_entities count " + count);
        }
        int beFlags = buf[posRef[0]++] & 0xFF;
        if ((beFlags & ~0x01) != 0) {
            throw new CorruptRegionEntryException("CBIT T5: Reserved beFlags bits set: " + beFlags);
        }
        boolean allInChunkXYZ = (beFlags & 1) != 0;
        int tmplCount = readVarIntChecked(buf, posRef, limit);
        if (tmplCount <= 0 || tmplCount > count) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid block_entities templateCount " + tmplCount);
        }
        int[] tmplStarts = new int[tmplCount];
        int[] tmplEnds = new int[tmplCount];
        for (int i = 0; i < tmplCount; i++) {
            int tLen = readVarIntChecked(buf, posRef, limit);
            if (tLen < 1 || posRef[0] + tLen > limit || buf[posRef[0] + tLen - 1] != Nbt.TAG_END) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated or unterminated block_entities template");
            }
            tmplStarts[i] = posRef[0];
            tmplEnds[i] = posRef[0] + tLen;
            posRef[0] += tLen;
        }

        L list = builder.newList(Nbt.TAG_COMPOUND, count);
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        if (allInChunkXYZ) {
            long prevY = 0L;
            boolean multiTmpl = tmplCount > 1;
            for (int k = 0; k < count; k++) {
                int tOrd = multiTmpl ? readVarIntChecked(buf, posRef, limit) : 0;
                if (tOrd < 0 || tOrd >= tmplCount || posRef[0] >= limit) {
                    throw new CorruptRegionEntryException("CBIT T7: block_entities templateOrd out of bounds");
                }
                int xz = buf[posRef[0]++] & 0xFF;
                prevY += readZigZagVarLong(buf, posRef, limit);
                C compound = decodeRawNbtCompoundBodyDirect(buf, tmplStarts[tOrd], tmplEnds[tOrd], builder);
                builder.putInt(compound, "x", baseX + (xz >>> 4));
                builder.putInt(compound, "y", (int) prevY);
                builder.putInt(compound, "z", baseZ + (xz & 0x0F));
                builder.addCompound(list, compound);
            }
        } else {
            long prevX = 0L;
            long prevY = 0L;
            long prevZ = 0L;
            for (int k = 0; k < count; k++) {
                int hdr = readVarIntChecked(buf, posRef, limit);
                int coordType = hdr & 3;
                int tOrd = hdr >>> 2;
                if (coordType == 3 || tOrd < 0 || tOrd >= tmplCount) {
                    throw new CorruptRegionEntryException("CBIT T7: Invalid block_entities instance header");
                }
                C compound = decodeRawNbtCompoundBodyDirect(buf, tmplStarts[tOrd], tmplEnds[tOrd], builder);
                if (coordType == 0) {
                    prevX += readZigZagVarLong(buf, posRef, limit);
                    prevY += readZigZagVarLong(buf, posRef, limit);
                    prevZ += readZigZagVarLong(buf, posRef, limit);
                    builder.putInt(compound, "x", (int) prevX);
                    builder.putInt(compound, "y", (int) prevY);
                    builder.putInt(compound, "z", (int) prevZ);
                } else if (coordType == 1) {
                    prevX += readZigZagVarLong(buf, posRef, limit);
                    prevY += readZigZagVarLong(buf, posRef, limit);
                    prevZ += readZigZagVarLong(buf, posRef, limit);
                    builder.putIntArray(compound, "Pos", new int[] {(int) prevX, (int) prevY, (int) prevZ});
                }
                builder.addCompound(list, compound);
            }
        }
        if (posRef[0] != limit) {
            throw new CorruptRegionEntryException("CBIT T5: Columnar block_entities stream trailing bytes");
        }
        return list;
    }

    private static <C, L> C decodeRawNbtCompoundBodyDirect(
            byte[] buf,
            int start,
            int limit,
            LcaDecodedChunkMeta.DirectNbtBuilder<C, L> builder
    ) throws CorruptRegionEntryException {
        int[] posRef = {start};
        C root = decodeRawNbtCompoundDirect(buf, posRef, limit, builder, 0);
        if (posRef[0] != limit) {
            throw new CorruptRegionEntryException("CBIT T5: Trailing bytes in block_entities template");
        }
        return root;
    }

    private static <C, L> C decodeRawNbtCompoundDirect(
            byte[] buf,
            int[] posRef,
            int limit,
            LcaDecodedChunkMeta.DirectNbtBuilder<C, L> builder,
            int depth
    ) throws CorruptRegionEntryException {
        if (depth > MAX_STRUCT_NBT_DEPTH) {
            throw new CorruptRegionEntryException("CBIT T5: Raw NBT template depth exceeded");
        }
        C compound = builder.newCompound();
        while (posRef[0] < limit) {
            int tagType = buf[posRef[0]++] & 0xFF;
            if (tagType == Nbt.TAG_END) {
                return compound;
            }
            String key = readRawNbtString(buf, posRef, limit);
            decodeRawNbtPutField(buf, posRef, limit, tagType, key, builder, compound, depth);
        }
        throw new CorruptRegionEntryException("CBIT T5: Unterminated raw NBT TAG_Compound");
    }

    private static <C, L> void decodeRawNbtPutField(
            byte[] buf,
            int[] posRef,
            int limit,
            int tagType,
            String key,
            LcaDecodedChunkMeta.DirectNbtBuilder<C, L> builder,
            C compound,
            int depth
    ) throws CorruptRegionEntryException {
        switch (tagType) {
            case Nbt.TAG_BYTE -> {
                if (posRef[0] >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated raw TAG_Byte");
                builder.putByte(compound, key, buf[posRef[0]++]);
            }
            case Nbt.TAG_SHORT -> {
                if (posRef[0] + 2 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated raw TAG_Short");
                short v = (short) (((buf[posRef[0]] & 0xFF) << 8) | (buf[posRef[0] + 1] & 0xFF));
                posRef[0] += 2;
                builder.putShort(compound, key, v);
            }
            case Nbt.TAG_INT -> {
                if (posRef[0] + 4 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated raw TAG_Int");
                int v = readIntBE(buf, posRef[0]);
                posRef[0] += 4;
                builder.putInt(compound, key, v);
            }
            case Nbt.TAG_LONG -> {
                if (posRef[0] + 8 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated raw TAG_Long");
                long v = readLongBE(buf, posRef[0]);
                posRef[0] += 8;
                builder.putLong(compound, key, v);
            }
            case Nbt.TAG_FLOAT -> {
                if (posRef[0] + 4 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated raw TAG_Float");
                int bits = readIntBE(buf, posRef[0]);
                posRef[0] += 4;
                builder.putFloat(compound, key, Float.intBitsToFloat(bits));
            }
            case Nbt.TAG_DOUBLE -> {
                if (posRef[0] + 8 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated raw TAG_Double");
                long bits = readLongBE(buf, posRef[0]);
                posRef[0] += 8;
                builder.putDouble(compound, key, Double.longBitsToDouble(bits));
            }
            case Nbt.TAG_BYTE_ARRAY -> builder.putByteArray(compound, key, readRawByteArray(buf, posRef, limit));
            case Nbt.TAG_STRING -> builder.putString(compound, key, readRawNbtString(buf, posRef, limit));
            case Nbt.TAG_LIST -> builder.putList(
                    compound, key, decodeRawNbtListDirect(buf, posRef, limit, builder, depth));
            case Nbt.TAG_COMPOUND -> builder.putCompound(
                    compound, key, decodeRawNbtCompoundDirect(buf, posRef, limit, builder, depth + 1));
            case Nbt.TAG_INT_ARRAY -> builder.putIntArray(compound, key, readRawIntArray(buf, posRef, limit));
            case Nbt.TAG_LONG_ARRAY -> builder.putLongArray(compound, key, readRawLongArray(buf, posRef, limit));
            default -> throw new CorruptRegionEntryException("CBIT T5: Unknown raw NBT tagType: " + tagType);
        }
    }

    private static <C, L> L decodeRawNbtListDirect(
            byte[] buf,
            int[] posRef,
            int limit,
            LcaDecodedChunkMeta.DirectNbtBuilder<C, L> builder,
            int depth
    ) throws CorruptRegionEntryException {
        if (depth >= MAX_STRUCT_NBT_DEPTH || posRef[0] + 5 > limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated or deep raw TAG_List");
        }
        byte elemType = buf[posRef[0]++];
        int cnt = readIntBE(buf, posRef[0]);
        posRef[0] += 4;
        if (cnt < 0 || cnt > Nbt.MAX_ARRAY_LENGTH) {
            throw new CorruptRegionEntryException("CBIT T4: Invalid raw TAG_List count: " + cnt);
        }
        L list = builder.newList(elemType, cnt);
        int et = elemType & 0xFF;
        for (int i = 0; i < cnt; i++) {
            switch (et) {
                case Nbt.TAG_BYTE -> {
                    if (posRef[0] >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated raw list TAG_Byte");
                    builder.addByte(list, buf[posRef[0]++]);
                }
                case Nbt.TAG_SHORT -> {
                    if (posRef[0] + 2 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated raw list TAG_Short");
                    short v = (short) (((buf[posRef[0]] & 0xFF) << 8) | (buf[posRef[0] + 1] & 0xFF));
                    posRef[0] += 2;
                    builder.addShort(list, v);
                }
                case Nbt.TAG_INT -> {
                    if (posRef[0] + 4 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated raw list TAG_Int");
                    builder.addInt(list, readIntBE(buf, posRef[0]));
                    posRef[0] += 4;
                }
                case Nbt.TAG_LONG -> {
                    if (posRef[0] + 8 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated raw list TAG_Long");
                    builder.addLong(list, readLongBE(buf, posRef[0]));
                    posRef[0] += 8;
                }
                case Nbt.TAG_FLOAT -> {
                    if (posRef[0] + 4 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated raw list TAG_Float");
                    builder.addFloat(list, Float.intBitsToFloat(readIntBE(buf, posRef[0])));
                    posRef[0] += 4;
                }
                case Nbt.TAG_DOUBLE -> {
                    if (posRef[0] + 8 > limit) throw new CorruptRegionEntryException("CBIT T5: Truncated raw list TAG_Double");
                    builder.addDouble(list, Double.longBitsToDouble(readLongBE(buf, posRef[0])));
                    posRef[0] += 8;
                }
                case Nbt.TAG_BYTE_ARRAY -> builder.addByteArray(list, readRawByteArray(buf, posRef, limit));
                case Nbt.TAG_STRING -> builder.addString(list, readRawNbtString(buf, posRef, limit));
                case Nbt.TAG_LIST -> builder.addList(
                        list, decodeRawNbtListDirect(buf, posRef, limit, builder, depth + 1));
                case Nbt.TAG_COMPOUND -> builder.addCompound(
                        list, decodeRawNbtCompoundDirect(buf, posRef, limit, builder, depth + 1));
                case Nbt.TAG_INT_ARRAY -> builder.addIntArray(list, readRawIntArray(buf, posRef, limit));
                case Nbt.TAG_LONG_ARRAY -> builder.addLongArray(list, readRawLongArray(buf, posRef, limit));
                default -> throw new CorruptRegionEntryException("CBIT T5: Unknown raw list tagType: " + et);
            }
        }
        return list;
    }

    private static String readRawNbtString(byte[] buf, int[] posRef, int limit)
            throws CorruptRegionEntryException {
        if (posRef[0] + 2 > limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated raw NBT string length");
        }
        int uLen = ((buf[posRef[0]] & 0xFF) << 8) | (buf[posRef[0] + 1] & 0xFF);
        posRef[0] += 2;
        if (posRef[0] + uLen > limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated raw NBT string bytes");
        }
        String s = new String(buf, posRef[0], uLen, StandardCharsets.UTF_8);
        posRef[0] += uLen;
        return s;
    }

    private static byte[] readRawByteArray(byte[] buf, int[] posRef, int limit)
            throws CorruptRegionEntryException {
        if (posRef[0] + 4 > limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated raw TAG_Byte_Array len");
        }
        int len = readIntBE(buf, posRef[0]);
        posRef[0] += 4;
        if (len < 0 || posRef[0] + len > limit) {
            throw new CorruptRegionEntryException("CBIT T5: Invalid raw TAG_Byte_Array len: " + len);
        }
        byte[] arr = Arrays.copyOfRange(buf, posRef[0], posRef[0] + len);
        posRef[0] += len;
        return arr;
    }

    private static int[] readRawIntArray(byte[] buf, int[] posRef, int limit)
            throws CorruptRegionEntryException {
        if (posRef[0] + 4 > limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated raw TAG_Int_Array len");
        }
        int len = readIntBE(buf, posRef[0]);
        posRef[0] += 4;
        if (len < 0 || (long) posRef[0] + ((long) len << 2) > limit) {
            throw new CorruptRegionEntryException("CBIT T5: Invalid raw TAG_Int_Array len: " + len);
        }
        int[] arr = new int[len];
        for (int i = 0; i < len; i++) {
            arr[i] = readIntBE(buf, posRef[0]);
            posRef[0] += 4;
        }
        return arr;
    }

    private static long[] readRawLongArray(byte[] buf, int[] posRef, int limit)
            throws CorruptRegionEntryException {
        if (posRef[0] + 4 > limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated raw TAG_Long_Array len");
        }
        int len = readIntBE(buf, posRef[0]);
        posRef[0] += 4;
        if (len < 0 || (long) posRef[0] + ((long) len << 3) > limit) {
            throw new CorruptRegionEntryException("CBIT T5: Invalid raw TAG_Long_Array len: " + len);
        }
        long[] arr = new long[len];
        for (int i = 0; i < len; i++) {
            arr[i] = readLongBE(buf, posRef[0]);
            posRef[0] += 8;
        }
        return arr;
    }

    private static int readIntBE(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 24)
                | ((buf[off + 1] & 0xFF) << 16)
                | ((buf[off + 2] & 0xFF) << 8)
                | (buf[off + 3] & 0xFF);
    }

    private static long readLongBE(byte[] buf, int off) {
        return (((long) readIntBE(buf, off)) << 32) | (readIntBE(buf, off + 4) & 0xFFFF_FFFFL);
    }

    private static final class ByteArrayKey {
        private final byte[] data;
        private final int hash;

        ByteArrayKey(byte[] data) {
            this.data = data;
            this.hash = Arrays.hashCode(data);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return (o instanceof ByteArrayKey other) && Arrays.equals(data, other.data);
        }
    }
}
