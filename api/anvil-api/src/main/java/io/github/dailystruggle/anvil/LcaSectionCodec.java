package io.github.dailystruggle.anvil;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Package-private {@code .lca} (LeafCraft Anvil) $16 \times 16 \times 16$ section
 * codec, static space-filling curve permutation tables (`00:YZX`, `01:H_3`, `10:Y+H_2`, `11:XZY`),
 * 1-pass directional gradient predictor, single-pass $O(4096)$ adaptive Delta-Roaring minority
 * state container builder, `leafrtp-ADR-072` flat bit-packed ceiling, 4-bitplane reflected binary
 * Gray-code light codec, and thread-local {@link ScratchArena} (`anvil-api-ADR-003` Sections 4-5,
 * Rule D-008).
 *
 * @governedBy anvil-api-ADR-003
 * @governedBy anvil-api-ADR-002
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
final class LcaSectionCodec {

    static final int SECTION_VOXELS = 4096;
    static final int BIOME_CELLS = 64;

    /** Curve 00: Y -> Z -> X horizontal strata scanline. */
    static final int CURVE_YZX = 0;
    /** Curve 01: 3D Hilbert/Morton octree curve H_3. */
    static final int CURVE_H3 = 1;
    /** Curve 10: Horizontal 2D Hilbert slabs Y + H_2. */
    static final int CURVE_Y_H2 = 2;
    /** Curve 11: Vertical column X -> Z -> Y. */
    static final int CURVE_XZY = 3;

    /** Block encoding mode 00: Uniform section (|P| = 1). */
    static final int BLOCK_MODE_UNIFORM = 0;
    /** Block encoding mode 01: Per-state Delta-Roaring containers (p = 1..|P|-1). */
    static final int BLOCK_MODE_DELTA_ROARING = 1;
    /** Block encoding mode 10: Flat bit-packed array ceiling (leafrtp-ADR-072). */
    static final int BLOCK_MODE_FLAT_BITPACK = 2;
    /** Block encoding mode 11: Delta-Roaring containers with all 64 octants active (octreeOccupancyMask64 = -1L elided). */
    static final int BLOCK_MODE_DELTA_ROARING_FULL_MASK = 3;

    /** Minority state container tag 00: Single voxel occurrence (2 bytes total: 0x0000 | h12). */
    static final int TAG_SINGLETON = 0;
    /** Minority state container tag 01: Adaptive 4|4 or 6|2 delta-run stream (bit 13 selects 0=4|4, 1=6|2). */
    static final int TAG_DELTA_RUN = 1;
    /** Minority state container tag 10: 1-byte / 2-byte delta-gap array for single-voxel occurrences (tVox == rCount). */
    static final int TAG_DELTA_ARRAY = 2;
    /** Minority state container tag 11: 512-byte direct 4,096-bit occupancy bitmap. */
    static final int TAG_BITMAP = 3;

    /** Light mode 00: Light channel absent / omitted from section NBT (0 bytes). */
    static final int LIGHT_MODE_ABSENT = 0;
    /** Light mode 01: Uniform constant nibbles 0..15 (shared 1-byte (blkConst4 << 4) | skyConst4). */
    static final int LIGHT_MODE_CONST = 1;
    /** Light mode 10: 4 Reflected Binary Gray-code bitplanes (1-byte packed 2b x 4 planeModes8 header). */
    static final int LIGHT_MODE_GRAY_PLANES = 2;
    /** Light mode 11: Raw 2,048-byte nibble array. */
    static final int LIGHT_MODE_RAW = 3;

    static final int[][] CURVE_TO_YZX = new int[4][SECTION_VOXELS];
    static final int[][] YZX_TO_CURVE = new int[4][SECTION_VOXELS];
    static final int[] YZX_TO_OCTANT6 = new int[SECTION_VOXELS];

    /**
     * Per-worker thread-local scratch arena (`anvil-api-ADR-003` Section 5.5) avoiding heap
     * allocations during hot section reads and saves (`~26 KiB` in L1/L2 cache).
     */
    static final class ScratchArena {
        final int[] yzxPaletteIdx = new int[SECTION_VOXELS];
        final int[] curvePaletteIdx = new int[SECTION_VOXELS];
        final int[] runStaging = new int[SECTION_VOXELS];
        final int[] runPoolPacked = new int[SECTION_VOXELS];
        final int[] runPoolNext = new int[SECTION_VOXELS];
        final int[] stateLastCursor = new int[512];
        final int[] stateRunCount = new int[512];
        final int[] stateTotalVoxels = new int[512];
        final int[] stateSinglePos = new int[512];
        final int[] stateHeadRun = new int[512];
        final int[] stateTailRun = new int[512];
        final int[] freqCounts = new int[512];
        final int[] paletteOrder = new int[512];
        final int[] oldToSortedPalette = new int[512];
        final int[] sortedGlobalIds = new int[512];
        final byte[] skyLightNibbles = new byte[SECTION_VOXELS];
        final byte[] blkLightNibbles = new byte[SECTION_VOXELS];
        final int[] grayCurve = new int[SECTION_VOXELS];
        final int[] biomeCells = new int[BIOME_CELLS];
        final byte[] byteStaging = new byte[16384];
        final byte[] sectionStaging = new byte[16384];
        final long[] packedBlockLongs = new long[1024];
        final long[] packedBiomeLongs = new long[64];
        final int[] sectionPaletteDictIds = new int[SECTION_VOXELS];
        final int[] biomePaletteDictIds = new int[BIOME_CELLS];
        final int[] heightmapCols = new int[256];
        final int[] sectionEntries = new int[128];
        final int[] sectionRelStarts = new int[128];
        byte[] nbtOutBuf = new byte[131072];

        byte[] ensureNbtCap(int required) {
            if (required <= nbtOutBuf.length) {
                return nbtOutBuf;
            }
            int newCap = Math.max(required, nbtOutBuf.length << 1);
            nbtOutBuf = Arrays.copyOf(nbtOutBuf, newCap);
            return nbtOutBuf;
        }
    }

    @SuppressWarnings("java:S5164")
    private static final ThreadLocal<ScratchArena> SCRATCH = ThreadLocal.withInitial(ScratchArena::new);

    static void clearThreadLocalScratch() {
        SCRATCH.remove();
    }

    /**
     * Extended Hamming `(72, 64)` SEC-DED syndrome tables (`anvil-api-ADR-003` Section 7.2).
     * Maps each of the 64 data bits of a chunk pointer word to a unique 7-bit code with popcount >= 2,
     * plus bit 7 as overall parity, enabling $O(1)$ 1-bit error correction (`SEC`) and 2-bit error
     * detection (`DED`).
     */
    static final int[] SECDED_DATA_MASKS = new int[64];
    static final int[] SECDED_SYNDROME_TO_BIT = new int[128];

    /**
     * 160-entry Static Core Base Block Name (`0x80 | coreId` for `0..127`, `0x60 | (coreId - 128)`
     * for `128..159`) & 60-entry Static Core Biome token tables for Slab-Local Chunk Micro-Palettes
     * (`anvil-api-ADR-003` Section 4.2). Common vanilla overworld/nether/end blocks encode their
     * base name as a single byte inside the chunk slab and decode in `< 80 ns` with zero `String`,
     * `substring`, or `List` heap allocations.
     */
    static final String[] STATIC_CORE_STATES = {
        "minecraft:air", "minecraft:stone", "minecraft:deepslate", "minecraft:dirt",
        "minecraft:grass_block", "minecraft:podzol", "minecraft:coarse_dirt", "minecraft:rooted_dirt",
        "minecraft:mycelium", "minecraft:dirt_path", "minecraft:farmland", "minecraft:mud",
        "minecraft:packed_mud", "minecraft:clay", "minecraft:gravel", "minecraft:sand",
        "minecraft:red_sand", "minecraft:sandstone", "minecraft:red_sandstone", "minecraft:bedrock",
        "minecraft:water", "minecraft:lava", "minecraft:granite", "minecraft:diorite",
        "minecraft:andesite", "minecraft:tuff", "minecraft:calcite", "minecraft:dripstone_block",
        "minecraft:pointed_dripstone", "minecraft:smooth_basalt", "minecraft:basalt", "minecraft:blackstone",
        "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:cobblestone", "minecraft:mossy_cobblestone",
        "minecraft:coal_ore", "minecraft:deepslate_coal_ore", "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
        "minecraft:copper_ore", "minecraft:deepslate_copper_ore", "minecraft:gold_ore", "minecraft:deepslate_gold_ore",
        "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
        "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore", "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore",
        "minecraft:raw_iron_block", "minecraft:raw_copper_block", "minecraft:raw_gold_block", "minecraft:amethyst_block",
        "minecraft:budding_amethyst", "minecraft:amethyst_cluster", "minecraft:snow", "minecraft:snow_block",
        "minecraft:powder_snow", "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice",
        "minecraft:moss_block", "minecraft:moss_carpet", "minecraft:glow_lichen", "minecraft:vine",
        "minecraft:cave_vines", "minecraft:cave_vines_plant", "minecraft:spore_blossom", "minecraft:hanging_roots",
        "minecraft:big_dripleaf", "minecraft:big_dripleaf_stem", "minecraft:small_dripleaf", "minecraft:azalea",
        "minecraft:flowering_azalea", "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log",
        "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log", "minecraft:mangrove_log",
        "minecraft:cherry_log", "minecraft:oak_leaves", "minecraft:spruce_leaves", "minecraft:birch_leaves",
        "minecraft:jungle_leaves", "minecraft:acacia_leaves", "minecraft:dark_oak_leaves", "minecraft:mangrove_leaves",
        "minecraft:cherry_leaves", "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves", "minecraft:short_grass",
        "minecraft:grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
        "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid", "minecraft:allium",
        "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip", "minecraft:white_tulip",
        "minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:lily_of_the_valley",
        "minecraft:sweet_berry_bush", "minecraft:pumpkin", "minecraft:melon", "minecraft:sugar_cane",
        "minecraft:bamboo", "minecraft:kelp", "minecraft:kelp_plant", "minecraft:seagrass",
        "minecraft:tall_seagrass", "minecraft:cave_air", "minecraft:void_air", "minecraft:infested_stone",
        "minecraft:infested_deepslate", "minecraft:sculk", "minecraft:sculk_vein", "minecraft:netherrack",
        "minecraft:magma_block", "minecraft:bubble_column", "minecraft:firefly_bush", "minecraft:brown_mushroom",
        "minecraft:small_amethyst_bud", "minecraft:medium_amethyst_bud", "minecraft:large_amethyst_bud", "minecraft:oak_fence",
        "minecraft:oak_planks", "minecraft:cobweb", "minecraft:chest", "minecraft:spawner",
        "minecraft:rail", "minecraft:wall_torch", "minecraft:end_stone", "minecraft:soul_sand",
        "minecraft:soul_soil", "minecraft:glowstone", "minecraft:nether_quartz_ore", "minecraft:nether_gold_ore",
        "minecraft:crimson_nylium", "minecraft:warped_nylium", "minecraft:nether_wart_block", "minecraft:warped_wart_block",
        "minecraft:shroomlight", "minecraft:ancient_debris", "minecraft:bone_block", "minecraft:stone_bricks",
        "minecraft:mossy_stone_bricks", "minecraft:cracked_stone_bricks", "minecraft:torch", "minecraft:lantern"
    };
    static final String[] STATIC_CORE_BASE_NAMES = new String[STATIC_CORE_STATES.length];
    @SuppressWarnings({"unchecked", "rawtypes"})
    static final List<String>[] STATIC_CORE_SINGLETON_PALETTES = new List[STATIC_CORE_STATES.length];
    static final Map<String, Integer> STATIC_CORE_STATE_TO_ID = new LinkedHashMap<>(256);

    /**
     * Static 1-byte property suffix table (`0x80 | propId`, 128 entries `0..127`) for `Part B` of the
     * Slab-Local Chunk Micro-Palette (`anvil-api-ADR-003` Section 4.2), replacing 40-78 byte ASCII
     * `[property=value,...]` strings in every chunk frame with a single byte.
     */
    static final String[] STATIC_CORE_PROP_SUFFIXES = {
        "[axis=y]", "[axis=x]", "[axis=z]",
        "[lit=false]", "[lit=true]",
        "[snowy=false]", "[snowy=true]",
        "[waterlogged=false]", "[waterlogged=true]",
        "[half=lower]", "[half=upper]", "[half=bottom]", "[half=top]",
        "[drag=true]", "[drag=false]",
        "[berries=false]", "[berries=true]",
        "[level=0]", "[level=1]", "[level=2]", "[level=3]", "[level=4]", "[level=5]", "[level=6]", "[level=7]",
        "[level=8]", "[level=9]", "[level=10]", "[level=11]", "[level=12]", "[level=13]", "[level=14]", "[level=15]",
        "[layers=1]", "[layers=2]", "[layers=3]", "[layers=4]", "[layers=5]", "[layers=6]", "[layers=7]", "[layers=8]",
        "[distance=1,persistent=false,waterlogged=false]", "[distance=2,persistent=false,waterlogged=false]",
        "[distance=3,persistent=false,waterlogged=false]", "[distance=4,persistent=false,waterlogged=false]",
        "[distance=5,persistent=false,waterlogged=false]", "[distance=6,persistent=false,waterlogged=false]",
        "[distance=7,persistent=false,waterlogged=false]",
        "[persistent=false,waterlogged=false,distance=1]", "[persistent=false,waterlogged=false,distance=2]",
        "[persistent=false,waterlogged=false,distance=3]", "[persistent=false,waterlogged=false,distance=4]",
        "[persistent=false,waterlogged=false,distance=5]", "[persistent=false,waterlogged=false,distance=6]",
        "[persistent=false,waterlogged=false,distance=7]",
        "[facing=up,waterlogged=false]", "[facing=down,waterlogged=false]",
        "[facing=north,waterlogged=false]", "[facing=south,waterlogged=false]",
        "[facing=east,waterlogged=false]", "[facing=west,waterlogged=false]",
        "[facing=north]", "[facing=south]", "[facing=east]", "[facing=west]", "[facing=up]", "[facing=down]",
        "[age=0]", "[age=1]", "[age=2]", "[age=3]", "[age=4]", "[age=5]", "[age=6]", "[age=7]",
        "[age=8]", "[age=9]", "[age=10]", "[age=11]", "[age=12]", "[age=13]", "[age=14]", "[age=15]",
        "[age=16]", "[age=17]", "[age=18]", "[age=19]", "[age=20]", "[age=21]", "[age=22]", "[age=23]",
        "[age=24]", "[age=25]",
        "[age=20,berries=false]", "[age=21,berries=false]", "[age=22,berries=false]",
        "[age=23,berries=false]", "[age=24,berries=false]", "[age=25,berries=false]",
        "[age=23,berries=true]", "[age=24,berries=true]", "[age=25,berries=true]",
        "[waterlogged=false,shape=east_west]", "[waterlogged=false,shape=north_south]",
        "[shape=north_south,waterlogged=false]", "[shape=east_west,waterlogged=false]",
        "[stage=0]", "[stage=1]",
        "[hanging=false,waterlogged=false]", "[hanging=true,waterlogged=false]",
        "[type=bottom,waterlogged=false]", "[type=top,waterlogged=false]", "[type=double,waterlogged=false]",
        "[east=false,north=false,south=false,up=false,west=false]",
        "[bites=0]", "[moisture=0]", "[moisture=7]",
        "[distance=7,persistent=true,waterlogged=false]", "[distance=1,persistent=true,waterlogged=false]",
        "[eye=false,facing=north]", "[eye=false,facing=south]", "[eye=false,facing=east]", "[eye=false,facing=west]",
        "[pickles=1,waterlogged=true]", "[candles=1,lit=false,waterlogged=false]",
        "[facing=north,type=single,waterlogged=false]", "[facing=south,type=single,waterlogged=false]",
        "[facing=east,type=single,waterlogged=false]"
    };
    static final Map<String, Integer> STATIC_CORE_PROP_TO_ID = new LinkedHashMap<>(256);

    static final String[] STATIC_CORE_BIOMES = {
        "minecraft:plains", "minecraft:sunflower_plains", "minecraft:snowy_plains", "minecraft:ice_spikes",
        "minecraft:desert", "minecraft:swamp", "minecraft:mangrove_swamp", "minecraft:forest",
        "minecraft:flower_forest", "minecraft:birch_forest", "minecraft:dark_forest", "minecraft:old_growth_birch_forest",
        "minecraft:old_growth_pine_taiga", "minecraft:old_growth_spruce_taiga", "minecraft:taiga", "minecraft:snowy_taiga",
        "minecraft:savanna", "minecraft:savanna_plateau", "minecraft:windswept_hills", "minecraft:windswept_gravelly_hills",
        "minecraft:windswept_forest", "minecraft:windswept_savanna", "minecraft:jungle", "minecraft:sparse_jungle",
        "minecraft:bamboo_jungle", "minecraft:badlands", "minecraft:eroded_badlands", "minecraft:wooded_badlands",
        "minecraft:meadow", "minecraft:cherry_grove", "minecraft:grove", "minecraft:snowy_slopes",
        "minecraft:frozen_peaks", "minecraft:jagged_peaks", "minecraft:stony_peaks", "minecraft:river",
        "minecraft:frozen_river", "minecraft:beach", "minecraft:snowy_beach", "minecraft:stony_shore",
        "minecraft:warm_ocean", "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean", "minecraft:ocean",
        "minecraft:deep_ocean", "minecraft:cold_ocean", "minecraft:deep_cold_ocean", "minecraft:frozen_ocean",
        "minecraft:deep_frozen_ocean", "minecraft:mushroom_fields", "minecraft:dripstone_caves", "minecraft:lush_caves",
        "minecraft:deep_dark", "minecraft:nether_wastes", "minecraft:crimson_forest", "minecraft:warped_forest",
        "minecraft:soul_sand_valley", "minecraft:basalt_deltas", "minecraft:the_end", "minecraft:the_void",
        "minecraft:small_end_islands", "minecraft:end_midlands", "minecraft:end_highlands", "minecraft:end_barrens",
        "minecraft:pale_garden"
    };
    @SuppressWarnings({"unchecked", "rawtypes"})
    static final List<String>[] STATIC_CORE_SINGLETON_BIOMES = new List[STATIC_CORE_BIOMES.length];
    static final Map<String, Integer> STATIC_CORE_BIOME_TO_ID = new LinkedHashMap<>(128);
    static final byte[][] STATIC_CORE_STATE_NBT_BYTES = new byte[STATIC_CORE_STATES.length][];
    static final byte[][] STATIC_CORE_PROP_NBT_BYTES = new byte[STATIC_CORE_PROP_SUFFIXES.length][];
    static final byte[][] STATIC_CORE_STATE_PROP_NBT_CACHE = new byte[STATIC_CORE_STATES.length * 128][];
    static final byte[][] STATIC_CORE_BIOME_STRING_NBT_BYTES = new byte[STATIC_CORE_BIOMES.length][];
    static final byte[][] STATIC_CORE_SINGLE_BIOME_COMPOUND_NBT_BYTES = new byte[STATIC_CORE_BIOMES.length][];

    private static final byte[] MINECRAFT_COLON_UTF8 = "minecraft:".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LCA_BOUNDARY_MARKER_UTF8 = "_lca_boundary_".getBytes(StandardCharsets.UTF_8);
    private static final byte[] STATUS_KEY_UTF8 = "Status".getBytes(StandardCharsets.UTF_8);

    private static final byte[] EMPTY_BLOCK_ENTITIES_NBT = buildEmptyCompoundListTag("block_entities");
    private static final byte[] EMPTY_BLOCK_TICKS_NBT = buildEmptyCompoundListTag("block_ticks");
    private static final byte[] EMPTY_FLUID_TICKS_NBT = buildEmptyCompoundListTag("fluid_ticks");
    private static final byte[] IS_LIGHT_ON_TRUE_NBT = buildByteTag("isLightOn", (byte) 1);
    private static final byte[] EMPTY_STRUCTURES_NBT = buildEmptyStructuresTag();
    private static final byte[] POST_PROCESSING_PREFIX_NBT = buildListTagHeaderPrefix("PostProcessing", Nbt.TAG_LIST);
    private static final byte[] STARLIGHT_VERSION_PREFIX_NBT = buildTagHeaderPrefix(Nbt.TAG_INT, "starlight.light_version");
    private static final byte[] HEIGHTMAPS_HEADER_NBT = buildTagHeaderPrefix(Nbt.TAG_COMPOUND, "Heightmaps");
    private static final byte[] SECTIONS_PREFIX_NBT = buildListTagHeaderPrefix("sections", Nbt.TAG_COMPOUND);
    private static final byte[] BLOCK_STATES_PALETTE_PREFIX_NBT = buildBlockStatesPalettePrefix();
    private static final byte[] DATA_LONG_ARRAY_PREFIX_NBT = buildTagHeaderPrefix(Nbt.TAG_LONG_ARRAY, "data");
    private static final byte[] BIOMES_PALETTE_PREFIX_NBT = buildBiomesPalettePrefix();
    private static final byte[] SKY_LIGHT_2048_HEADER_NBT = buildByteArray2048Header("SkyLight");
    private static final byte[] BLOCK_LIGHT_2048_HEADER_NBT = buildByteArray2048Header("BlockLight");

    static {
        for (int i = 0; i < STATIC_CORE_PROP_SUFFIXES.length; i++) {
            STATIC_CORE_PROP_TO_ID.put(STATIC_CORE_PROP_SUFFIXES[i], i);
            STATIC_CORE_PROP_NBT_BYTES[i] = encodePropSuffixToNbtBytes(STATIC_CORE_PROP_SUFFIXES[i]);
        }
        for (int i = 0; i < STATIC_CORE_STATES.length; i++) {
            String s = STATIC_CORE_STATES[i];
            String base = stripBlockProperties(s);
            STATIC_CORE_BASE_NAMES[i] = base;
            STATIC_CORE_SINGLETON_PALETTES[i] = List.of(base);
            STATIC_CORE_STATE_TO_ID.put(s, i);
            STATIC_CORE_STATE_NBT_BYTES[i] = encodeCanonicalStateToNbtCompoundBytes(base);
        }
        for (int i = 0; i < STATIC_CORE_BIOMES.length; i++) {
            String b = STATIC_CORE_BIOMES[i];
            STATIC_CORE_SINGLETON_BIOMES[i] = List.of(b);
            STATIC_CORE_BIOME_TO_ID.put(b, i);
            byte[] strBytes = encodeNbtStringPayloadBytes(b);
            STATIC_CORE_BIOME_STRING_NBT_BYTES[i] = strBytes;
            STATIC_CORE_SINGLE_BIOME_COMPOUND_NBT_BYTES[i] = encodeSingleBiomeCompoundNbtBytes(strBytes);
        }
        Arrays.fill(SECDED_SYNDROME_TO_BIT, -1);
        int bitIdx = 0;
        for (int code = 3; bitIdx < 64; code++) {
            if (Integer.bitCount(code) >= 2) {
                SECDED_DATA_MASKS[bitIdx] = code;
                SECDED_SYNDROME_TO_BIT[code] = bitIdx;
                bitIdx++;
            }
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int yzx = (y << 8) | (z << 4) | x;
                    CURVE_TO_YZX[CURVE_YZX][yzx] = yzx;
                    YZX_TO_CURVE[CURVE_YZX][yzx] = yzx;

                    int h2 = xyToHilbert2D16(x, z);
                    int yh2 = (y << 8) | h2;
                    CURVE_TO_YZX[CURVE_Y_H2][yh2] = yzx;
                    YZX_TO_CURVE[CURVE_Y_H2][yzx] = yh2;

                    int xzy = (x << 8) | (z << 4) | y;
                    CURVE_TO_YZX[CURVE_XZY][xzy] = yzx;
                    YZX_TO_CURVE[CURVE_XZY][yzx] = xzy;

                    int h3 = xyzToOctreeHilbert12(x, y, z);
                    CURVE_TO_YZX[CURVE_H3][h3] = yzx;
                    YZX_TO_CURVE[CURVE_H3][yzx] = h3;

                    YZX_TO_OCTANT6[yzx] = h3 >>> 6;
                }
            }
        }
    }

    @FunctionalInterface
    private interface ZstdCompressorInvoker {
        byte[] compress(byte[] raw, int level) throws ReflectiveOperationException;
    }

    private static final ZstdCompressorInvoker ZSTD_COMPRESSOR = resolveZstdCompressor();

    private LcaSectionCodec() {}

    private static ZstdCompressorInvoker resolveZstdCompressor() {
        if (!DynamicZstdBinding.isAvailable()) {
            return null;
        }
        ClassLoader[] loaders = new ClassLoader[]{
                DynamicZstdBinding.resolvedZstdClass != null
                        ? DynamicZstdBinding.resolvedZstdClass.getClassLoader()
                        : null,
                LcaSectionCodec.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader()
        };

        // 1. Candidate: com.github.luben.zstd.Zstd#compress(byte[], int)
        for (ClassLoader cl : loaders) {
            if (cl == null) {
                continue;
            }
            try {
                Class<?> zstdClass = (DynamicZstdBinding.resolvedZstdClass != null
                        && DynamicZstdBinding.resolvedZstdClass.getClassLoader() == cl)
                        ? DynamicZstdBinding.resolvedZstdClass
                        : Class.forName("com.github.luben.zstd.Zstd", true, cl);
                Method compressMethod = zstdClass.getMethod("compress", byte[].class, int.class);
                ZstdCompressorInvoker candidate = (raw, level) ->
                        (byte[]) compressMethod.invoke(null, raw, Math.max(1, Math.min(22, level)));
                if (verifyCompressorCanary(candidate)) {
                    return candidate;
                }
            } catch (Throwable ignored) {
                // Native zstd-jni unavailable on this classloader, try next candidate
            }
        }

        // 2. Candidate: pure-Java io.airlift.compress.zstd.ZstdCompressor
        String[] airliftClasses = new String[]{
                "io.airlift.compress.zstd.ZstdCompressor",
                "net.leaf.anvil.shaded.io.airlift.compress.zstd.ZstdCompressor"
        };
        for (String className : airliftClasses) {
            for (ClassLoader cl : loaders) {
                if (cl == null) {
                    continue;
                }
                try {
                    Class<?> compClass = Class.forName(className, true, cl);
                    Object compressor = compClass.getConstructor().newInstance();
                    Method maxLenMethod = compClass.getMethod("maxCompressedLength", int.class);
                    Method compressMethod = compClass.getMethod(
                            "compress", byte[].class, int.class, int.class, byte[].class, int.class, int.class);
                    ZstdCompressorInvoker candidate = (raw, level) -> {
                        int maxLen = (int) maxLenMethod.invoke(compressor, raw.length);
                        byte[] dst = new byte[maxLen];
                        int written = (int) compressMethod.invoke(compressor, raw, 0, raw.length, dst, 0, maxLen);
                        return written == dst.length ? dst : Arrays.copyOf(dst, written);
                    };
                    if (verifyCompressorCanary(candidate)) {
                        return candidate;
                    }
                } catch (Throwable ignored) {
                    // Aircompressor fallback unavailable on this classloader
                }
            }
        }
        return null;
    }

    private static boolean verifyCompressorCanary(ZstdCompressorInvoker candidate) {
        try {
            byte[] probe = "LCA_MODE_B_CANARY".getBytes(StandardCharsets.UTF_8);
            byte[] compressed = candidate.compress(probe, 1);
            if (compressed == null || compressed.length == 0) {
                return false;
            }
            byte[] decompressed = DynamicZstdBinding.decompress(compressed, 0, compressed.length, 64);
            return Arrays.equals(probe, decompressed);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean isZstdCompressionAvailable() {
        return ZSTD_COMPRESSOR != null && DynamicZstdBinding.isAvailable();
    }

    static byte[] compressZstd1(byte[] raw) throws IOException {
        return compressZstd(raw, 1);
    }

    /**
     * Off-thread Zstd frame compressor (`REQ-RTP-S-005`). Invoked exclusively from asynchronous
     * region I/O worker threads (`RTP-Anvil-IO`, Moonrise I/O pool, or offline batch converter);
     * never called on the server main thread.
     */
    static byte[] compressZstd(byte[] raw, int level) throws IOException {
        if (ZSTD_COMPRESSOR == null) {
            throw new UnsupportedAnvilFormatException(
                    "DynamicZstdBinding unavailable for .lca Mode B: "
                            + DynamicZstdBinding.pbitResult().diagnosticMessage());
        }
        int boundedLevel = Math.max(1, Math.min(22, level));
        try {
            return ZSTD_COMPRESSOR.compress(raw, boundedLevel);
        } catch (Throwable e) {
            Throwable cause = (e instanceof InvocationTargetException ite && ite.getCause() != null)
                    ? ite.getCause()
                    : e;
            throw new CorruptRegionEntryException("Mode B Zstd-" + boundedLevel + " frame compression failed: " + cause.getMessage());
        }
    }

    /**
     * Encodes a `Mode B` (`codec1 = 1`) `ChunkFrame` by keeping the `headerLen`-byte `ChunkFrame`
     * header (`24 + 2 * sectionCount` bytes: `selfTagAndDataVersion`, timestamps, `statusOrdinal`,
     * `chunkFlags`, `residualNbtRelOffset16B`, and `uint16 sectionOffsets[]`) uncompressed at the
     * start of the slab, followed immediately by `headerWords = (headerLen + 7) >>> 3` bytes of
     * Extended Hamming `(72, 64)` SEC-DED syndromes protecting every 64-bit word of the uncompressed
     * header against bit rot, and Zstd-1 compressing only the body `[headerLen .. chunkFrame.length)`.
     */
    static byte[] encodeModeBChunkFrameWithUncompressedHeader(byte[] chunkFrame, int headerLen) throws IOException {
        return encodeModeBChunkFrameWithUncompressedHeader(chunkFrame, headerLen, 1);
    }

    static byte[] encodeModeBChunkFrameWithUncompressedHeader(byte[] chunkFrame, int headerLen, int zstdLevel) throws IOException {
        int headerWords = (headerLen + 7) >>> 3;
        int bodyLen = Math.max(0, chunkFrame.length - headerLen);
        byte[] compressedBody;
        if (bodyLen == 0) {
            compressedBody = new byte[0];
        } else {
            byte[] bodySlice = Arrays.copyOfRange(chunkFrame, headerLen, chunkFrame.length);
            compressedBody = compressZstd(bodySlice, zstdLevel);
        }
        byte[] out = new byte[headerLen + headerWords + compressedBody.length];
        System.arraycopy(chunkFrame, 0, out, 0, headerLen);
        for (int w = 0; w < headerWords; w++) {
            long word64 = readHeaderWord64BE(out, 0, headerLen, w);
            out[headerLen + w] = (byte) computeHammingSecDed8(word64);
        }
        if (compressedBody.length > 0) {
            System.arraycopy(compressedBody, 0, out, headerLen + headerWords, compressedBody.length);
        }
        return out;
    }

    /**
     * Verifies and algebraically heals any 1-bit flip in each 64-bit word of an uncompressed
     * `Mode B` `ChunkFrame` header (`[slabOffset .. slabOffset + headerLen)`) using the
     * `headerWords = (headerLen + 7) >>> 3` Extended Hamming `(72, 64)` SEC-DED syndrome bytes at
     * `syndromeOffset`.
     *
     * @return number of 64-bit header words healed (`>= 0`), or `-1` if an unrecoverable double-bit
     *         error (`DED`) was detected
     */
    static int verifyAndHealChunkHeaderSecDed(
            byte[] slab, int slabOffset, int headerLen, int syndromeOffset
    ) {
        int headerWords = (headerLen + 7) >>> 3;
        if (slab == null
                || slabOffset < 0
                || headerLen <= 0
                || syndromeOffset + headerWords > slab.length
                || slabOffset + headerLen > slab.length) {
            return -1;
        }
        int healedWords = 0;
        for (int w = 0; w < headerWords; w++) {
            long word64 = readHeaderWord64BE(slab, slabOffset, headerLen, w);
            int syn8 = slab[syndromeOffset + w] & 0xFF;
            long healed = healSingleBitFlip64(word64, syn8);
            if (healed == Long.MIN_VALUE) {
                return -1;
            }
            if (healed != word64) {
                writeHeaderWord64BE(slab, slabOffset, headerLen, w, healed);
                slab[syndromeOffset + w] = (byte) computeHammingSecDed8(healed);
                healedWords++;
            }
        }
        return healedWords;
    }

    private static long readHeaderWord64BE(byte[] buf, int baseOffset, int headerLen, int wordIdx) {
        int rel = wordIdx << 3;
        int rem = headerLen - rel;
        if (rem >= 8) {
            return (long) LcaRegionReader.LONG_BE_VIEW.get(buf, baseOffset + rel);
        }
        long v = 0L;
        for (int b = 0; b < 8; b++) {
            int byteVal = (b < rem) ? (buf[baseOffset + rel + b] & 0xFF) : 0;
            v = (v << 8) | byteVal;
        }
        return v;
    }

    private static void writeHeaderWord64BE(byte[] buf, int baseOffset, int headerLen, int wordIdx, long word64) {
        int rel = wordIdx << 3;
        int rem = headerLen - rel;
        if (rem >= 8) {
            LcaRegionReader.LONG_BE_VIEW.set(buf, baseOffset + rel, word64);
            return;
        }
        for (int b = 0; b < rem; b++) {
            buf[baseOffset + rel + b] = (byte) ((word64 >>> ((7 - b) << 3)) & 0xFF);
        }
    }

    static ScratchArena scratch() {
        return SCRATCH.get();
    }

    static int xyToHilbert2D16(int x, int z) {
        int d = 0;
        for (int s = 8; s > 0; s >>= 1) {
            int rx = (x & s) != 0 ? 1 : 0;
            int rz = (z & s) != 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ rz);
            if (rz == 0) {
                if (rx == 1) {
                    x = s - 1 - x;
                    z = s - 1 - z;
                }
                int t = x;
                x = z;
                z = t;
            }
        }
        return d;
    }

    static int xyzToOctreeHilbert12(int x, int y, int z) {
        int octX = (x >>> 2) & 3;
        int octY = (y >>> 2) & 3;
        int octZ = (z >>> 2) & 3;
        int octant6 = (octY << 4) | xyToHilbert2D4(octX, octZ);
        int lx = x & 3;
        int ly = y & 3;
        int lz = z & 3;
        int local6 = (ly << 4) | xyToHilbert2D4(lx, lz);
        return (octant6 << 6) | local6;
    }

    private static int xyToHilbert2D4(int x, int z) {
        int d = 0;
        for (int s = 2; s > 0; s >>= 1) {
            int rx = (x & s) != 0 ? 1 : 0;
            int rz = (z & s) != 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ rz);
            if (rz == 0) {
                if (rx == 1) {
                    x = s - 1 - x;
                    z = s - 1 - z;
                }
                int t = x;
                x = z;
                z = t;
            }
        }
        return d;
    }

    /**
     * 1-Pass Directional Gradient Curve Predictor (`< 150 ns`, `anvil-api-ADR-003` Section 5.1).
     * Evaluates directional continuity across minority states (`cur != dominantOldIdx`) so the
     * unencoded dominant background (`palette[0]`, `90.8%` of voxels) does not mask axis gradients.
     */
    static int selectOptimalCurve1Pass(int[] yzxStates, int dominantOldIdx) {
        int sameX = 0;
        int sameY = 0;
        int sameZ = 0;
        for (int y = 0; y < 16; y += 2) {
            for (int z = 0; z < 16; z += 2) {
                for (int x = 0; x < 16; x += 2) {
                    int idx = (y << 8) | (z << 4) | x;
                    int cur = yzxStates[idx];
                    if (cur == dominantOldIdx) continue;
                    if (cur == yzxStates[idx + 1]) sameX++;
                    if (cur == yzxStates[idx + 16]) sameZ++;
                    if (cur == yzxStates[idx + 256]) sameY++;
                }
            }
        }
        if (sameY > sameX && sameY > sameZ) {
            return CURVE_XZY;
        }
        if (sameX + sameZ >= sameY) {
            return (sameX > sameZ + 16) ? CURVE_YZX : CURVE_Y_H2;
        }
        return CURVE_H3;
    }

    /**
     * Computes the 64-bit XXH64-style checksum over the first 56 bytes (`0x00..0x37`) of the
     * `.lca` superblock (`anvil-api-ADR-003` Section 2.1, `CBIT T2`).
     */
    static long computeSuperblockChecksum64(byte[] buf, int offset) {
        long h = 0x9E3779B97F4A7C15L;
        ByteBuffer bb = ByteBuffer.wrap(buf, offset, 56);
        for (int i = 0; i < 7; i++) {
            long k = bb.getLong(offset + (i << 3));
            k *= 0xBF58476D1CE4E5B9L;
            k = Long.rotateLeft(k, 31);
            k *= 0x94D049BB133111EBL;
            h ^= k;
            h = Long.rotateLeft(h, 27) * 5 + 0x52DCE729L;
        }
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    /**
     * Computes the 8-bit Extended Hamming `(72, 64)` SEC-DED syndrome byte for a 64-bit word
     * (`anvil-api-ADR-003` Section 7.2).
     */
    static int computeHammingSecDed8(long word64) {
        int syn7 = 0;
        long w = word64;
        while (w != 0L) {
            int b = Long.numberOfTrailingZeros(w);
            syn7 ^= SECDED_DATA_MASKS[b];
            w &= (w - 1L);
        }
        int overallParity = (Long.bitCount(word64) ^ Integer.bitCount(syn7)) & 1;
        return (overallParity << 7) | syn7;
    }

    /**
     * Evaluates a 64-bit word against its stored 8-bit Extended Hamming `(72, 64)` SEC-DED syndrome.
     *
     * <ul>
     *   <li>If no bit error (or a 1-bit flip inside the syndrome byte itself), returns {@code word64} unchanged.</li>
     *   <li>If a single-bit flip occurred in {@code word64} (`SEC`), flips the exact corrupted bit `0..63` back and returns the healed 64-bit word.</li>
     *   <li>If a multi-bit error (`DED`) is detected, returns {@code Long.MIN_VALUE} as a sentinel so the caller can invoke Tier-2 self-describing slab recovery.</li>
     * </ul>
     */
    static long healSingleBitFlip64(long word64, int storedSyndrome8) {
        int expectedSyn8 = computeHammingSecDed8(word64);
        int diff8 = (expectedSyn8 ^ storedSyndrome8) & 0xFF;
        if (diff8 == 0) {
            return word64;
        }
        int errSyn7 = diff8 & 0x7F;
        int totalFlippedParity = (Long.bitCount(word64) ^ Integer.bitCount(storedSyndrome8 & 0x7F) ^ ((storedSyndrome8 >>> 7) & 1)) & 1;
        if (totalFlippedParity == 1) {
            if (Integer.bitCount(errSyn7) <= 1) {
                // Single-bit flip in the stored syndrome byte itself; data word is clean
                return word64;
            }
            int corruptedBit = SECDED_SYNDROME_TO_BIT[errSyn7];
            if (corruptedBit >= 0) {
                return word64 ^ (1L << corruptedBit);
            }
        }
        return Long.MIN_VALUE;
    }

    static int writeVarIntToBuf(byte[] dst, int pos, int v) {
        while ((v & ~0x7F) != 0) {
            dst[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        dst[pos++] = (byte) (v & 0x7F);
        return pos;
    }

    static int readVarInt(byte[] buf, int pos, int limit) throws CorruptRegionEntryException {
        int val = 0;
        int shift = 0;
        int p = pos;
        while (p < limit) {
            int b = buf[p++] & 0xFF;
            val |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return val;
            }
            shift += 7;
            if (shift > 28) {
                throw new CorruptRegionEntryException("CBIT: VarInt overflow at byte offset " + pos);
            }
        }
        throw new CorruptRegionEntryException("CBIT: Truncated VarInt at byte offset " + pos + " (limit=" + limit + ")");
    }

    static int varIntLen(int v) {
        int len = 1;
        while ((v & ~0x7F) != 0) {
            len++;
            v >>>= 7;
        }
        return len;
    }

    static final int BP_FLAG_HAS_CHUNK_PATCH_TAIL = 0x4000;
    static final int PATCH_FLAG_VOXEL_DELTAS = 0x01;
    static final int PATCH_FLAG_REPLACEMENT_OFFSET = 0x02;
    static final int PATCH_FLAG_SKY_LIGHT = 0x04;
    static final int PATCH_FLAG_BLOCK_LIGHT = 0x08;

    /**
     * Returns the initial Slab-Local Micro-Palette upper bound $\min_k(\mathtt{relStart}_k)$ across all
     * non-uniform sections $k$ that precede $\mathtt{residualRelStart}$ (or $\mathtt{residualRelStart}$
     * if no non-uniform section precedes it; `LCA-ICD-1.0` Section 6.5).
     */
    static int findFirstSectionRelStart(int[] relStarts, int sectionCount, int residualRelStart) {
        int minStart = residualRelStart;
        for (int k = 0; k < sectionCount; k++) {
            int rk = relStarts[k];
            if (rk >= 0 && rk < minStart) {
                minStart = rk;
            }
        }
        return minStart;
    }

    /**
     * Order-independent upper bound $\mathtt{relEnd}_i$ for non-uniform `SectionFrame` $i$
     * (`LCA-ICD-1.0` Section 6.4, `anvil-api-ADR-003` Section 4.1):
     * $$\mathtt{relEnd}_i = \min\Bigl(\{\mathtt{relStart}_j \mid \mathtt{relStart}_j > \mathtt{relStart}_i\}
     *   \cup \{B \in \{\mathtt{residualRelStart}, \mathtt{frameLen}\} \mid B > \mathtt{relStart}_i\}\Bigr)$$
     */
    static int findSectionRelEnd(
            int[] relStarts, int idx, int sectionCount, int residualRelStart, int frameLen
    ) {
        int start = relStarts[idx];
        int bestEnd = (residualRelStart > start && residualRelStart < frameLen)
                ? residualRelStart
                : frameLen;
        for (int j = 0; j < sectionCount; j++) {
            int rj = relStarts[j];
            if (rj > start && rj < bestEnd) {
                bestEnd = rj;
            }
        }
        return bestEnd;
    }

    /**
     * Upper bound $\mathtt{residualRelEnd}$ of the Residual Tail (`LCA-ICD-1.0` Section 8):
     * $$\mathtt{residualRelEnd} = \min\Bigl(\{\mathtt{relStart}_j \mid \mathtt{relStart}_j > \mathtt{residualRelStart}\}
     *   \cup \{\mathtt{frameLen}\}\Bigr)$$
     */
    static int findResidualRelEnd(int[] relStarts, int sectionCount, int residualRelStart, int frameLen) {
        int bestEnd = frameLen;
        for (int j = 0; j < sectionCount; j++) {
            int rj = relStarts[j];
            if (rj > residualRelStart && rj < bestEnd) {
                bestEnd = rj;
            }
        }
        return bestEnd;
    }

    /**
     * Computes the 8-bit Extended Hamming `(72, 64)` SEC-DED parity byte over a 64-bit SplitMix
     * digest of `buf[offset .. offset + length)` (`LCA-ICD-1.0` Section 8.3).
     */
    static int computePatchParity8(byte[] buf, int offset, int length) {
        long h = 0x9E3779B97F4A7C15L ^ length;
        for (int i = 0; i < length; i++) {
            h = (h ^ (buf[offset + i] & 0xFFL)) * 0xBF58476D1CE4E5B9L;
            h ^= h >>> 31;
        }
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 33;
        return computeHammingSecDed8(h);
    }

    static int skipSectionFrame(byte[] buf, int offset, int limit) throws CorruptRegionEntryException {
        if (offset + 2 > limit) {
            throw new CorruptRegionEntryException("CBIT T5: SectionFrame truncated at offset " + offset);
        }
        int pos = offset;
        int sectionHeader = buf[pos++] & 0xFF;
        int blockMode = (sectionHeader >>> 4) & 3;
        int skyMode = (sectionHeader >>> 2) & 3;
        int blkMode = sectionHeader & 3;
        if (blockMode == BLOCK_MODE_DELTA_ROARING || blockMode == BLOCK_MODE_FLAT_BITPACK) {
            pos += 8;
        }
        int paletteLen = readVarInt(buf, pos, limit);
        pos += varIntLen(paletteLen);
        for (int i = 0; i < paletteLen; i++) {
            int d = readVarInt(buf, pos, limit);
            pos += varIntLen(d);
        }
        if (blockMode == BLOCK_MODE_UNIFORM || paletteLen == 1) {
            // 0 bytes
        } else if (blockMode == BLOCK_MODE_DELTA_ROARING || blockMode == BLOCK_MODE_DELTA_ROARING_FULL_MASK) {
            for (int p = 1; p < paletteLen; p++) {
                int word16 = readUint16BE(buf, pos);
                pos += 2;
                int tag = (word16 >>> 14) & 3;
                int countOrIdx = word16 & 0x3FFF;
                if (tag == TAG_SINGLETON) {
                    // 0 extra bytes
                } else if (tag == TAG_BITMAP) {
                    pos += 512;
                } else if (tag == TAG_DELTA_ARRAY) {
                    for (int r = 0; r < countOrIdx; r++) {
                        int b0 = buf[pos++] & 0xFF;
                        if (b0 >= 240) pos++;
                    }
                } else {
                    boolean subMode62 = (countOrIdx & 0x2000) != 0;
                    int runCount = countOrIdx & 0x1FFF;
                    for (int r = 0; r < runCount; r++) {
                        int tok = buf[pos++] & 0xFF;
                        if (subMode62) {
                            int adjGap = (tok >>> 2) & 0x3F;
                            int lenM1 = tok & 3;
                            if (adjGap == 63) {
                                int b0 = buf[pos++] & 0xFF;
                                if (b0 >= 240) pos++;
                            }
                            if (lenM1 == 3) {
                                int extra = readVarInt(buf, pos, limit);
                                pos += varIntLen(extra);
                            }
                        } else {
                            int adjGap = (tok >>> 4) & 0x0F;
                            int lenM1 = tok & 0x0F;
                            if (adjGap == 15) {
                                int b0 = buf[pos++] & 0xFF;
                                if (b0 >= 240) pos++;
                            }
                            if (lenM1 == 15) {
                                int extra = readVarInt(buf, pos, limit);
                                pos += varIntLen(extra);
                            }
                        }
                    }
                }
            }
        } else if (blockMode == BLOCK_MODE_FLAT_BITPACK) {
            int flatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteLen - 1));
            pos += (SECTION_VOXELS * flatBits + 7) >>> 3;
        }
        if (pos < limit) {
            int b0 = buf[pos] & 0xFF;
            if ((b0 & 0x80) != 0) {
                pos++;
            } else {
                int bLen = readVarInt(buf, pos, limit);
                pos += varIntLen(bLen);
                for (int i = 0; i < bLen; i++) {
                    int bId = readVarInt(buf, pos, limit);
                    pos += varIntLen(bId);
                }
                if (bLen > 1) {
                    int runCount = readVarInt(buf, pos, limit);
                    pos += varIntLen(runCount);
                    if (bLen <= 8) {
                        pos += runCount;
                    } else {
                        for (int r = 0; r < runCount; r++) {
                            int pIdx = readVarInt(buf, pos, limit);
                            pos += varIntLen(pIdx) + 1;
                        }
                    }
                }
            }
        }
        if (skyMode == LIGHT_MODE_CONST || blkMode == LIGHT_MODE_CONST) {
            pos++;
        }
        pos = skipLightChannel(buf, pos, limit, skyMode);
        pos = skipLightChannel(buf, pos, limit, blkMode);
        return pos;
    }

    static int findMaxPostResidualSectionStart(
            byte[] buf,
            int frameStart,
            int sectionCount,
            int sectionDirEnd,
            int unitShift,
            int residualStart,
            int patchPayloadStart,
            int patchPayloadLen,
            int limitBytes
    ) {
        int maxPostSecStart = -1;
        for (int i = 0; i < sectionCount; i++) {
            int entry16 = readUint16BE(buf, frameStart + 24 + (i << 1));
            if (entry16 != 0 && (entry16 & 0x8000) == 0) {
                int secStart = frameStart + sectionDirEnd + ((entry16 - 1) << unitShift);
                if (secStart > residualStart && secStart < limitBytes && secStart > maxPostSecStart) {
                    maxPostSecStart = secStart;
                }
            }
        }
        if (patchPayloadStart > 0 && patchPayloadLen >= 3 && patchPayloadStart + patchPayloadLen <= limitBytes) {
            try {
                int pEnd = patchPayloadStart + patchPayloadLen - 1;
                int pPos = patchPayloadStart;
                int appCount = readVarInt(buf, pPos, pEnd);
                pPos += varIntLen(appCount);
                for (int i = 0; i < appCount; i++) {
                    int tag = buf[pPos++] & 0xFF;
                    if ((tag & 0x80) == 0 && (tag < 0x60 || tag >= 0x80)) {
                        int uLen = readVarInt(buf, pPos, pEnd);
                        pPos += varIntLen(uLen) + uLen;
                    }
                }
                if (appCount > 0) {
                    int propCount = readVarInt(buf, pPos, pEnd);
                    pPos += varIntLen(propCount);
                    for (int k = 0; k < propCount; k++) {
                        int sIdx = readVarInt(buf, pPos, pEnd);
                        pPos += varIntLen(sIdx);
                        int pTag = buf[pPos++] & 0xFF;
                        if (pTag == 0x40) {
                            int gId = readVarInt(buf, pPos, pEnd);
                            pPos += varIntLen(gId);
                        }
                    }
                }
                int secCount = readVarInt(buf, pPos, pEnd);
                pPos += varIntLen(secCount);
                for (int s = 0; s < secCount && pPos + 2 <= pEnd; s++) {
                    pPos++; // secIdx
                    int pFlags = buf[pPos++] & 0xFF;
                    if ((pFlags & PATCH_FLAG_REPLACEMENT_OFFSET) != 0) {
                        int replEntry16 = readUint16BE(buf, pPos);
                        pPos += 2;
                        if (replEntry16 != 0 && (replEntry16 & 0x8000) == 0) {
                            int secStart = frameStart + sectionDirEnd + ((replEntry16 - 1) << unitShift);
                            if (secStart > residualStart && secStart < limitBytes && secStart > maxPostSecStart) {
                                maxPostSecStart = secStart;
                            }
                        }
                    }
                    if ((pFlags & PATCH_FLAG_VOXEL_DELTAS) != 0) {
                        int dCount = readVarInt(buf, pPos, pEnd);
                        pPos += varIntLen(dCount);
                        for (int d = 0; d < dCount; d++) {
                            pPos += 2;
                            int dictId = readVarInt(buf, pPos, pEnd);
                            pPos += varIntLen(dictId);
                        }
                    }
                    if ((pFlags & PATCH_FLAG_SKY_LIGHT) != 0) {
                        int m = buf[pPos++] & 0xFF;
                        pPos = (m == LIGHT_MODE_CONST) ? (pPos + 1) : skipLightChannel(buf, pPos, pEnd, m);
                    }
                    if ((pFlags & PATCH_FLAG_BLOCK_LIGHT) != 0) {
                        int m = buf[pPos++] & 0xFF;
                        pPos = (m == LIGHT_MODE_CONST) ? (pPos + 1) : skipLightChannel(buf, pPos, pEnd, m);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return maxPostSecStart;
    }

    private static int skipLightChannel(byte[] buf, int pos, int limit, int mode) throws CorruptRegionEntryException {
        if (mode == LIGHT_MODE_ABSENT || mode == LIGHT_MODE_CONST) {
            return pos;
        }
        if (mode == LIGHT_MODE_RAW) {
            return pos + 2048;
        }
        int planeModes8 = buf[pos++] & 0xFF;
        for (int plane = 0; plane < 4; plane++) {
            int pm = (planeModes8 >>> (plane << 1)) & 3;
            if (pm == 2) {
                int runCount = readVarInt(buf, pos, limit);
                pos += varIntLen(runCount);
                for (int r = 0; r < runCount; r++) {
                    int tok = buf[pos++] & 0xFF;
                    int adjGap = (tok >>> 4) & 0x0F;
                    int lenM1 = tok & 0x0F;
                    if (adjGap == 15) {
                        int extra = readVarInt(buf, pos, limit);
                        pos += varIntLen(extra);
                    }
                    if (lenM1 == 15) {
                        int extra = readVarInt(buf, pos, limit);
                        pos += varIntLen(extra);
                    }
                }
            } else if (pm == 3) {
                pos += 512;
            }
        }
        return pos;
    }

    /**
     * Splices or replaces the `HAS_CHUNK_PATCH_TAIL (0x4000)` field in an uncompressed `ChunkFrame`
     * right before `bit 10` (`residualNbtSlice`), preserving any trailing `residualNbtSlice` and any
     * post-residual appended `SectionFrame`s (`LCA-ICD-1.0` Section 8.3).
     */
    static byte[] injectChunkPatchTailIntoUncompressedFrame(
            byte[] frame, int sectionCount, byte[] patchTailPayload
    ) throws IOException {
        int sectionDirEnd = 24 + (sectionCount << 1);
        int chunkFlags = frame[21] & 0xFF;
        boolean hasResidualNbt = (chunkFlags & 1) != 0;
        boolean scale4B = (chunkFlags & 4) != 0;
        int unitShift = scale4B ? 2 : 0;
        int residualRelOffset = readUint16BE(frame, 22);
        int residualRelStart = hasResidualNbt
                ? sectionDirEnd + (residualRelOffset << unitShift)
                : frame.length;

        int[] relStarts = new int[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            int entry16 = readUint16BE(frame, 24 + (i << 1));
            relStarts[i] = (entry16 != 0 && (entry16 & 0x8000) == 0)
                    ? sectionDirEnd + ((entry16 - 1) << unitShift)
                    : -1;
        }
        int residualRelEnd = findResidualRelEnd(relStarts, sectionCount, residualRelStart, frame.length);

        byte[] patchPrefixAndPayload = new byte[varIntLen(patchTailPayload.length) + patchTailPayload.length];
        int pOff = writeVarIntToBuf(patchPrefixAndPayload, 0, patchTailPayload.length);
        System.arraycopy(patchTailPayload, 0, patchPrefixAndPayload, pOff, patchTailPayload.length);

        if (!hasResidualNbt) {
            int alignedRel = frame.length;
            if (scale4B) {
                alignedRel = (alignedRel + 3) & ~3;
            }
            int newResidualRelOffset = (alignedRel - sectionDirEnd) >>> unitShift;
            byte[] out = new byte[alignedRel + 2 + patchPrefixAndPayload.length];
            System.arraycopy(frame, 0, out, 0, frame.length);
            out[21] = (byte) (chunkFlags | 1);
            out[22] = (byte) ((newResidualRelOffset >>> 8) & 0xFF);
            out[23] = (byte) (newResidualRelOffset & 0xFF);
            out[alignedRel] = (byte) ((BP_FLAG_HAS_CHUNK_PATCH_TAIL >>> 8) & 0xFF);
            out[alignedRel + 1] = (byte) (BP_FLAG_HAS_CHUNK_PATCH_TAIL & 0xFF);
            System.arraycopy(patchPrefixAndPayload, 0, out, alignedRel + 2, patchPrefixAndPayload.length);
            return out;
        }

        int pos = residualRelStart;
        int limit = residualRelEnd;
        int bpFlags = readUint16BE(frame, pos);
        pos += 2;
        if ((bpFlags & 8192) != 0) {
            int mbnlLen = readVarInt(frame, pos, limit);
            pos += varIntLen(mbnlLen) + mbnlLen;
        }
        if ((bpFlags & 32) != 0) pos++;
        if ((bpFlags & 64) != 0) {
            int slVer = readVarInt(frame, pos, limit);
            pos += varIntLen(slVer);
        }
        if ((bpFlags & 128) != 0) pos++;
        if ((bpFlags & 256) != 0) pos++;
        if ((bpFlags & 512) != 0) {
            int secHmCount = frame[pos++] & 0xFF;
            for (int h = 0; h < secHmCount; h++) {
                int code = frame[pos++] & 0xFF;
                if (code >= STANDARD_HEIGHTMAP_NAMES.length) {
                    int kLen = readVarInt(frame, pos, limit);
                    pos += varIntLen(kLen) + kLen;
                }
                int payLen = readVarInt(frame, pos, limit);
                pos += varIntLen(payLen) + payLen;
            }
        }
        int patchInsertPos = pos;
        int afterOldPatchPos = pos;
        if ((bpFlags & BP_FLAG_HAS_CHUNK_PATCH_TAIL) != 0) {
            int oldLen = readVarInt(frame, pos, limit);
            afterOldPatchPos = pos + varIntLen(oldLen) + oldLen;
        }
        int deltaBytes = patchPrefixAndPayload.length - (afterOldPatchPos - patchInsertPos);
        byte[] out = new byte[frame.length + deltaBytes];
        System.arraycopy(frame, 0, out, 0, patchInsertPos);
        int newBpFlags = bpFlags | BP_FLAG_HAS_CHUNK_PATCH_TAIL;
        out[residualRelStart] = (byte) ((newBpFlags >>> 8) & 0xFF);
        out[residualRelStart + 1] = (byte) (newBpFlags & 0xFF);
        System.arraycopy(patchPrefixAndPayload, 0, out, patchInsertPos, patchPrefixAndPayload.length);
        System.arraycopy(
                frame,
                afterOldPatchPos,
                out,
                patchInsertPos + patchPrefixAndPayload.length,
                frame.length - afterOldPatchPos);

        // If any out-of-order section was placed after residualRelStart, adjust its sectionOffsets[i] by deltaBytes
        if (deltaBytes != 0) {
            for (int i = 0; i < sectionCount; i++) {
                if (relStarts[i] > residualRelStart) {
                    int newRel = relStarts[i] + deltaBytes;
                    int newEntry16 = ((newRel - sectionDirEnd) >>> unitShift) + 1;
                    out[24 + (i << 1)] = (byte) ((newEntry16 >>> 8) & 0xFF);
                    out[24 + (i << 1) + 1] = (byte) (newEntry16 & 0xFF);
                }
            }
        }
        return out;
    }

    /**
     * Encoded section result holding either an inline `uint16` directory entry (`bit 15 == 1`)
     * or a serialized `SectionFrame` byte slice.
     */
    static final class EncodedSection {
        final int inlineEntry16; // > 0 when bit 15 == 1; 0 when body bytes are present
        final byte[] frameBytes;
        final long octreeOccupancyMask64;

        EncodedSection(int inlineEntry16, byte[] frameBytes, long octreeOccupancyMask64) {
            this.inlineEntry16 = inlineEntry16;
            this.frameBytes = frameBytes;
            this.octreeOccupancyMask64 = octreeOccupancyMask64;
        }
    }

    /**
     * Encodes a single $16 \times 16 \times 16$ section according to `anvil-api-ADR-003` Sections 4.1 & 5.
     */
    static EncodedSection encodeSection(
            int[] rawPaletteGlobalDictIds,
            long[] rawBlockDataLongs,
            int[] rawBiomeGlobalDictIds,
            long[] rawBiomeDataLongs,
            byte[] skyLight2048,
            byte[] blockLight2048,
            int chunkDefaultBiomeDictId
    ) {
        return encodeSection(
                rawPaletteGlobalDictIds, rawBlockDataLongs, rawBiomeGlobalDictIds, rawBiomeDataLongs,
                skyLight2048, blockLight2048, chunkDefaultBiomeDictId, -1);
    }

    static EncodedSection encodeSection(
            int[] rawPaletteGlobalDictIds,
            long[] rawBlockDataLongs,
            int[] rawBiomeGlobalDictIds,
            long[] rawBiomeDataLongs,
            byte[] skyLight2048,
            byte[] blockLight2048,
            int chunkDefaultBiomeDictId,
            int sectionBiomeTemplateIdx
    ) {
        ScratchArena arena = scratch();
        int rawPaletteLen = rawPaletteGlobalDictIds.length;
        boolean singleBlockState = (rawPaletteLen == 1);
        int uniformBlockDictId = rawPaletteGlobalDictIds[0];

        // Fast 64-bit word uniformity check + nibble extraction using ScratchArena buffers
        int skyConst = extractLightNibblesFast(skyLight2048, arena.skyLightNibbles, 0);
        int blkConst = extractLightNibblesFast(blockLight2048, arena.blkLightNibbles, 0);

        boolean matchesChunkDefaultBiome = (rawBiomeGlobalDictIds == null && chunkDefaultBiomeDictId < 0)
                || (rawBiomeGlobalDictIds != null
                && rawBiomeGlobalDictIds.length == 1
                && rawBiomeGlobalDictIds[0] == chunkDefaultBiomeDictId);

        int skyLight2 = -1;
        if (skyConst == 0 && blkConst == 0) skyLight2 = 0b00;
        else if (skyConst == 15 && blkConst == 0) skyLight2 = 0b01;
        else if (skyConst == 15 && blkConst == 15) skyLight2 = 0b10;
        else if (skyConst == 0 && blkConst == 15) skyLight2 = 0b11;

        if (singleBlockState && skyLight2 >= 0) {
            if ((sectionBiomeTemplateIdx & ~3) == 0 && (uniformBlockDictId & ~0x07FF) == 0) {
                int inline16 = 0x8000
                        | (skyLight2 << 13)
                        | (sectionBiomeTemplateIdx << 11)
                        | uniformBlockDictId;
                return new EncodedSection(inline16, new byte[0], 0L);
            }
            if (sectionBiomeTemplateIdx < 0 && matchesChunkDefaultBiome && (uniformBlockDictId & ~0x1FFF) == 0) {
                int inline16 = 0x8000 | (skyLight2 << 13) | uniformBlockDictId;
                return new EncodedSection(inline16, new byte[0], 0L);
            }
        }

        int[] yzxIdx = arena.yzxPaletteIdx;
        int[] freq = arena.freqCounts.length >= rawPaletteLen ? arena.freqCounts : new int[rawPaletteLen];
        Arrays.fill(freq, 0, rawPaletteLen, 0);

        if (singleBlockState || rawBlockDataLongs == null || rawBlockDataLongs.length == 0) {
            Arrays.fill(yzxIdx, 0);
            freq[0] = SECTION_VOXELS;
        } else {
            int bits = PackedPaletteDecoder.bitsPerEntry(rawPaletteLen);
            int entriesPerLong = 64 / bits;
            long mask = (1L << bits) - 1L;
            int voxel = 0;
            for (int w = 0; w < rawBlockDataLongs.length && voxel < SECTION_VOXELS; w++) {
                long word = rawBlockDataLongs[w];
                int limit = Math.min(entriesPerLong, SECTION_VOXELS - voxel);
                for (int s = 0; s < limit; s++) {
                    int p = (int) (word & mask);
                    if (p >= rawPaletteLen) p = 0;
                    yzxIdx[voxel++] = p;
                    freq[p]++;
                    word >>>= bits;
                }
            }
            while (voxel < SECTION_VOXELS) {
                yzxIdx[voxel++] = 0;
                freq[0]++;
            }
        }

        // Zero-allocation primitive insertion sort by descending frequency
        int[] order = arena.paletteOrder.length >= rawPaletteLen ? arena.paletteOrder : new int[rawPaletteLen];
        for (int i = 0; i < rawPaletteLen; i++) {
            int keyFreq = freq[i];
            int j = i - 1;
            while (j >= 0 && freq[order[j]] < keyFreq) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = i;
        }

        int[] oldToNew = arena.oldToSortedPalette.length >= rawPaletteLen
                ? arena.oldToSortedPalette : new int[rawPaletteLen];
        int[] sortedGlobalIds = arena.sortedGlobalIds.length >= rawPaletteLen
                ? arena.sortedGlobalIds : new int[rawPaletteLen];
        int activePaletteSize = 0;
        for (int i = 0; i < rawPaletteLen; i++) {
            int oldIdx = order[i];
            if (freq[oldIdx] == 0 && activePaletteSize > 0) {
                oldToNew[oldIdx] = 0;
                continue;
            }
            oldToNew[oldIdx] = activePaletteSize;
            sortedGlobalIds[activePaletteSize++] = rawPaletteGlobalDictIds[oldIdx];
        }
        if (activePaletteSize == 0) {
            activePaletteSize = 1;
            sortedGlobalIds[0] = rawPaletteGlobalDictIds[0];
        }

        int curveId = (activePaletteSize == 1) ? CURVE_YZX : selectOptimalCurve1Pass(yzxIdx, order[0]);
        int[] curveToYzx = CURVE_TO_YZX[curveId];
        int[] curvePaletteIdx = arena.curvePaletteIdx;
        long octreeMask64 = 0L;

        if (activePaletteSize > 1) {
            for (int h = 0; h < SECTION_VOXELS; h++) {
                int yzx = curveToYzx[h];
                int p = oldToNew[yzxIdx[yzx]];
                curvePaletteIdx[h] = p;
                if (p != 0) {
                    octreeMask64 |= (1L << YZX_TO_OCTANT6[yzx]);
                }
            }
        }

        // Encode block payload into arena.byteStaging
        int blockEncodingMode;
        int blockPayloadLen = 0;
        byte[] blockStaging = arena.byteStaging;

        if (activePaletteSize == 1) {
            blockEncodingMode = BLOCK_MODE_UNIFORM;
        } else {
            int roaringLen = encodeMinorityContainersSinglePass(curvePaletteIdx, activePaletteSize, arena, blockStaging);
            int flatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(activePaletteSize - 1));
            int flatByteCount = (SECTION_VOXELS * flatBits + 7) >>> 3;
            if (roaringLen <= flatByteCount) {
                blockEncodingMode = BLOCK_MODE_DELTA_ROARING;
                blockPayloadLen = roaringLen;
            } else {
                blockEncodingMode = BLOCK_MODE_FLAT_BITPACK;
                encodeFlatBitPackYzx(yzxIdx, oldToNew, flatBits, flatByteCount, blockStaging);
                blockPayloadLen = flatByteCount;
            }
        }

        // Assemble SectionFrame directly into arena.sectionStaging
        if (blockEncodingMode == BLOCK_MODE_DELTA_ROARING && octreeMask64 == -1L) {
            blockEncodingMode = BLOCK_MODE_DELTA_ROARING_FULL_MASK;
        }
        byte[] secOut = arena.sectionStaging;
        int pos = 1; // reserve index 0 for sectionHeader
        if (blockEncodingMode == BLOCK_MODE_DELTA_ROARING || blockEncodingMode == BLOCK_MODE_FLAT_BITPACK) {
            writeLongBEToBuf(secOut, pos, octreeMask64);
            pos += 8;
        }

        pos = writeVarIntToBuf(secOut, pos, activePaletteSize);
        for (int i = 0; i < activePaletteSize; i++) {
            pos = writeVarIntToBuf(secOut, pos, sortedGlobalIds[i]);
        }

        if (blockPayloadLen > 0) {
            System.arraycopy(blockStaging, 0, secOut, pos, blockPayloadLen);
            pos += blockPayloadLen;
        }

        // Encode Biome 4x4x4 Payload immediately after BlockStates so readChunkView (skipLighting=true)
        // decodes BlockStates + Biomes and returns in O(1) without walking SkyLight/BlockLight bitplanes.
        if ((sectionBiomeTemplateIdx & ~0x3F) == 0) {
            secOut[pos++] = (byte) (0x80 | sectionBiomeTemplateIdx);
        } else {
            pos = encodeBiomesToBuf(secOut, pos, rawBiomeGlobalDictIds, rawBiomeDataLongs, chunkDefaultBiomeDictId, arena);
        }

        // Encode SkyLight & BlockLight (00=Absent, 01=ConstNibbles8, 10=GrayPlanes, 11=Raw2048B)
        boolean skyIsConst = (skyLight2048 != null && skyConst >= 0);
        boolean blkIsConst = (blockLight2048 != null && blkConst >= 0);
        if (skyIsConst || blkIsConst) {
            int sc = skyIsConst ? (skyConst & 0x0F) : 0;
            int bc = blkIsConst ? (blkConst & 0x0F) : 0;
            secOut[pos++] = (byte) ((bc << 4) | sc);
        }

        int skyMode;
        if (skyLight2048 == null) {
            skyMode = LIGHT_MODE_ABSENT;
        } else if (skyIsConst) {
            skyMode = LIGHT_MODE_CONST;
        } else {
            int grayStart = pos;
            int grayEnd = encodeGrayLightPlanesToBuf(arena.skyLightNibbles, CURVE_TO_YZX[CURVE_Y_H2], arena, secOut, grayStart);
            if (grayEnd - grayStart < 2048) {
                skyMode = LIGHT_MODE_GRAY_PLANES;
                pos = grayEnd;
            } else {
                skyMode = LIGHT_MODE_RAW;
                System.arraycopy(skyLight2048, 0, secOut, grayStart, 2048);
                pos = grayStart + 2048;
            }
        }

        int blkMode;
        if (blockLight2048 == null) {
            blkMode = LIGHT_MODE_ABSENT;
        } else if (blkIsConst) {
            blkMode = LIGHT_MODE_CONST;
        } else {
            int grayStart = pos;
            int grayEnd = encodeGrayLightPlanesToBuf(arena.blkLightNibbles, CURVE_TO_YZX[CURVE_H3], arena, secOut, grayStart);
            if (grayEnd - grayStart < 2048) {
                blkMode = LIGHT_MODE_GRAY_PLANES;
                pos = grayEnd;
            } else {
                blkMode = LIGHT_MODE_RAW;
                System.arraycopy(blockLight2048, 0, secOut, grayStart, 2048);
                pos = grayStart + 2048;
            }
        }

        secOut[0] = (byte) (((curveId & 3) << 6)
                | ((blockEncodingMode & 3) << 4)
                | ((skyMode & 3) << 2)
                | (blkMode & 3));

        return new EncodedSection(0, Arrays.copyOf(secOut, pos), octreeMask64);
    }

    /**
     * Single-pass $O(4096)$ minority container encoder: walks `curvePaletteIdx[0..4095]` once,
     * skipping `palette[0]` (`0`) and chaining runs per minority state `p = 1..|P|-1` in
     * `arena.runPoolPacked`.
     */
    private static int encodeMinorityContainersSinglePass(
            int[] curvePaletteIdx, int paletteSize, ScratchArena arena, byte[] dst
    ) {
        int[] lastCursor = arena.stateLastCursor.length >= paletteSize ? arena.stateLastCursor : new int[paletteSize];
        int[] runCounts = arena.stateRunCount.length >= paletteSize ? arena.stateRunCount : new int[paletteSize];
        int[] totalVoxels = arena.stateTotalVoxels.length >= paletteSize ? arena.stateTotalVoxels : new int[paletteSize];
        int[] singlePos = arena.stateSinglePos.length >= paletteSize ? arena.stateSinglePos : new int[paletteSize];
        int[] headRun = arena.stateHeadRun.length >= paletteSize ? arena.stateHeadRun : new int[paletteSize];
        int[] tailRun = arena.stateTailRun.length >= paletteSize ? arena.stateTailRun : new int[paletteSize];

        Arrays.fill(lastCursor, 0, paletteSize, 0);
        Arrays.fill(runCounts, 0, paletteSize, 0);
        Arrays.fill(totalVoxels, 0, paletteSize, 0);
        Arrays.fill(headRun, 0, paletteSize, -1);
        Arrays.fill(tailRun, 0, paletteSize, -1);

        int[] poolPacked = arena.runPoolPacked;
        int[] poolNext = arena.runPoolNext;
        int poolUsed = 0;

        int i = 0;
        while (i < SECTION_VOXELS) {
            int p = curvePaletteIdx[i];
            if (p == 0) {
                i++;
                continue;
            }
            int start = i++;
            while (i < SECTION_VOXELS && curvePaletteIdx[i] == p) {
                i++;
            }
            int adjGap = (runCounts[p] == 0) ? start : (start - lastCursor[p] - 1);
            int len = i - start;
            lastCursor[p] = i;
            totalVoxels[p] += len;
            singlePos[p] = start;
            runCounts[p]++;

            int node = poolUsed++;
            poolPacked[node] = (adjGap << 16) | ((len - 1) & 0xFFFF);
            poolNext[node] = -1;
            int prevTail = tailRun[p];
            if (prevTail < 0) {
                headRun[p] = node;
            } else {
                poolNext[prevTail] = node;
            }
            tailRun[p] = node;
        }

        int pos = 0;
        for (int p = 1; p < paletteSize; p++) {
            pos = emitStateContainerFromPool(
                    dst, pos, runCounts[p], totalVoxels[p], singlePos[p], headRun[p], poolPacked, poolNext, curvePaletteIdx, p);
        }
        return pos;
    }

    private static int emitStateContainerFromPool(
            byte[] dst,
            int pos,
            int rCount,
            int tVox,
            int singlePos,
            int headNode,
            int[] poolPacked,
            int[] poolNext,
            int[] curvePaletteIdx,
            int targetP
    ) {
        if (tVox == 1 && rCount == 1) {
            int word16 = (TAG_SINGLETON << 14) | (singlePos & 0x0FFF);
            dst[pos++] = (byte) ((word16 >>> 8) & 0xFF);
            dst[pos++] = (byte) (word16 & 0xFF);
            return pos;
        }

        int size44 = 2;
        int size62 = 2;
        int sizeArr = (tVox == rCount) ? 2 : Integer.MAX_VALUE;
        int node = headNode;
        while (node >= 0) {
            int packed = poolPacked[node];
            int adjGap = packed >>> 16;
            int lm1 = packed & 0xFFFF;
            size44 += 1 + (adjGap >= 15 ? boundedGap240Len(adjGap - 15) : 0) + (lm1 >= 15 ? varIntLen(lm1 - 15) : 0);
            size62 += 1 + (adjGap >= 63 ? boundedGap240Len(adjGap - 63) : 0) + (lm1 >= 3 ? varIntLen(lm1 - 3) : 0);
            if (tVox == rCount) {
                sizeArr += boundedGap240Len(adjGap);
            }
            node = poolNext[node];
        }

        int bestBytes = Math.min(sizeArr, Math.min(size44, size62));
        if (bestBytes > 514) {
            int word16 = (TAG_BITMAP << 14) | 512;
            dst[pos++] = (byte) ((word16 >>> 8) & 0xFF);
            dst[pos++] = (byte) (word16 & 0xFF);
            Arrays.fill(dst, pos, pos + 512, (byte) 0);
            for (int h = 0; h < SECTION_VOXELS; h++) {
                if (curvePaletteIdx[h] == targetP) {
                    dst[pos + (h >>> 3)] |= (byte) (1 << (h & 7));
                }
            }
            return pos + 512;
        }

        if (sizeArr <= size44 && sizeArr <= size62) {
            int word16 = (TAG_DELTA_ARRAY << 14) | (rCount & 0x3FFF);
            dst[pos++] = (byte) ((word16 >>> 8) & 0xFF);
            dst[pos++] = (byte) (word16 & 0xFF);
            int cur = headNode;
            while (cur >= 0) {
                int adjGap = poolPacked[cur] >>> 16;
                pos = writeBoundedGap240(dst, pos, adjGap);
                cur = poolNext[cur];
            }
            return pos;
        }

        if (size62 < size44 && rCount <= 0x1FFF) {
            int word16 = (TAG_DELTA_RUN << 14) | 0x2000 | (rCount & 0x1FFF);
            dst[pos++] = (byte) ((word16 >>> 8) & 0xFF);
            dst[pos++] = (byte) (word16 & 0xFF);
            int cur = headNode;
            while (cur >= 0) {
                int packed = poolPacked[cur];
                int adjGap = packed >>> 16;
                int lm1 = packed & 0xFFFF;
                int g6 = Math.min(63, adjGap);
                int l2 = Math.min(3, lm1);
                dst[pos++] = (byte) ((g6 << 2) | l2);
                if (g6 == 63) {
                    pos = writeBoundedGap240(dst, pos, adjGap - 63);
                }
                if (l2 == 3) {
                    pos = writeVarIntToBuf(dst, pos, lm1 - 3);
                }
                cur = poolNext[cur];
            }
            return pos;
        }

        int word16 = (TAG_DELTA_RUN << 14) | (rCount & 0x1FFF);
        dst[pos++] = (byte) ((word16 >>> 8) & 0xFF);
        dst[pos++] = (byte) (word16 & 0xFF);
        int cur = headNode;
        while (cur >= 0) {
            int packed = poolPacked[cur];
            int adjGap = packed >>> 16;
            int lm1 = packed & 0xFFFF;
            int gNib = Math.min(15, adjGap);
            int lNib = Math.min(15, lm1);
            dst[pos++] = (byte) ((gNib << 4) | lNib);
            if (gNib == 15) {
                pos = writeBoundedGap240(dst, pos, adjGap - 15);
            }
            if (lNib == 15) {
                pos = writeVarIntToBuf(dst, pos, lm1 - 15);
            }
            cur = poolNext[cur];
        }
        return pos;
    }

    private static int boundedGap240Len(int val) {
        return val < 240 ? 1 : 2;
    }

    private static int writeBoundedGap240(byte[] dst, int pos, int val) {
        if (val < 240) {
            dst[pos++] = (byte) val;
        } else {
            dst[pos++] = (byte) (0xF0 | ((val >>> 8) & 0x0F));
            dst[pos++] = (byte) (val & 0xFF);
        }
        return pos;
    }

    private static void encodeFlatBitPackYzx(
            int[] yzxOldIdx, int[] oldToNew, int bitsPerVoxel, int flatByteCount, byte[] dst
    ) {
        Arrays.fill(dst, 0, flatByteCount, (byte) 0);
        int bitPos = 0;
        for (int i = 0; i < SECTION_VOXELS; i++) {
            int val = oldToNew[yzxOldIdx[i]];
            for (int b = 0; b < bitsPerVoxel; b++) {
                if (((val >>> b) & 1) != 0) {
                    dst[bitPos >>> 3] |= (byte) (1 << (bitPos & 7));
                }
                bitPos++;
            }
        }
    }

    /**
     * Checks `packed2048` 8 bytes at a time via `LONG_BE_VIEW` (`< 25 ns` when uniform all-0 or all-15),
     * unpacking nibbles only when non-uniform.
     */
    private static int extractLightNibblesFast(byte[] packed2048, byte[] out4096, int defaultVal) {
        if (packed2048 == null || packed2048.length < 2048) {
            return defaultVal;
        }
        long firstLong = (long) LcaRegionReader.LONG_BE_VIEW.get(packed2048, 0);
        int b0 = packed2048[0] & 0xFF;
        int firstNib = b0 & 0x0F;
        if (((b0 >>> 4) & 0x0F) == firstNib) {
            long expectedWord = (b0 & 0xFFL) * 0x0101010101010101L;
            if (firstLong == expectedWord) {
                boolean allSame = true;
                for (int off = 8; off < 2048; off += 8) {
                    if ((long) LcaRegionReader.LONG_BE_VIEW.get(packed2048, off) != expectedWord) {
                        allSame = false;
                        break;
                    }
                }
                if (allSame) {
                    return firstNib;
                }
            }
        }
        for (int i = 0; i < 2048; i++) {
            int b = packed2048[i] & 0xFF;
            out4096[i << 1] = (byte) (b & 0x0F);
            out4096[(i << 1) | 1] = (byte) ((b >>> 4) & 0x0F);
        }
        return -1;
    }

    private static int encodeGrayLightPlanesToBuf(
            byte[] yzxNibbles, int[] curveToYzx, ScratchArena arena, byte[] dst, int startPos
    ) {
        int[] grayCurve = arena.grayCurve;
        for (int i = 0; i < SECTION_VOXELS; i++) {
            int l = yzxNibbles[curveToYzx[i]] & 0x0F;
            grayCurve[i] = l ^ (l >>> 1);
        }

        int pos = startPos + 1; // reserve dst[startPos] for packed 1-byte planeModes8 (2 bits x 4 planes)
        int planeModes8 = 0;
        int[] staging = arena.runStaging;
        for (int plane = 0; plane < 4; plane++) {
            int mask = 1 << plane;
            int runCount = 0;
            int ones = 0;
            int cursor = 0;
            int i = 0;
            while (i < SECTION_VOXELS) {
                if ((grayCurve[i] & mask) == 0) {
                    i++;
                    continue;
                }
                int start = i++;
                while (i < SECTION_VOXELS && (grayCurve[i] & mask) != 0) {
                    i++;
                }
                int gap = start - cursor;
                int len = i - start;
                ones += len;
                staging[runCount++] = (gap << 16) | ((len - 1) & 0xFFFF);
                cursor = i;
            }
            int mode;
            if (ones == 0) {
                mode = 0; // 00 = All-0 (0 B)
            } else if (ones == SECTION_VOXELS) {
                mode = 1; // 01 = All-1 (0 B)
            } else {
                int planeStartPos = pos;
                pos = writeVarIntToBuf(dst, pos, runCount);
                for (int r = 0; r < runCount; r++) {
                    int packed = staging[r];
                    int rawGap = packed >>> 16;
                    int adjGap = (r == 0) ? rawGap : (rawGap - 1);
                    int lenMinusOne = packed & 0xFFFF;
                    int gNib = Math.min(15, adjGap);
                    int lNib = Math.min(15, lenMinusOne);
                    dst[pos++] = (byte) ((gNib << 4) | lNib);
                    if (gNib == 15) pos = writeVarIntToBuf(dst, pos, adjGap - 15);
                    if (lNib == 15) pos = writeVarIntToBuf(dst, pos, lenMinusOne - 15);
                }
                if (pos - planeStartPos <= 512) {
                    mode = 2; // 10 = Delta-Run Stream
                } else {
                    pos = planeStartPos;
                    mode = 3; // 11 = Raw 512B Bitmap ceiling (Section 5.4)
                    Arrays.fill(dst, pos, pos + 512, (byte) 0);
                    for (int h = 0; h < SECTION_VOXELS; h++) {
                        if ((grayCurve[h] & mask) != 0) {
                            dst[pos + (h >>> 3)] |= (byte) (1 << (h & 7));
                        }
                    }
                    pos += 512;
                }
            }
            planeModes8 |= (mode << (plane << 1));
        }
        dst[startPos] = (byte) planeModes8;
        return pos;
    }

    static int encodeBiomesToBuf(
            byte[] dst,
            int pos,
            int[] rawBiomeGlobalDictIds,
            long[] rawBiomeDataLongs,
            int chunkDefaultBiomeDictId,
            ScratchArena arena
    ) {
        if (rawBiomeGlobalDictIds == null || rawBiomeGlobalDictIds.length == 0) {
            return writeVarIntToBuf(dst, pos, 0);
        }
        int bLen = rawBiomeGlobalDictIds.length;
        if (bLen == 1 && rawBiomeGlobalDictIds[0] == chunkDefaultBiomeDictId && chunkDefaultBiomeDictId >= 0) {
            return writeVarIntToBuf(dst, pos, 0);
        }
        pos = writeVarIntToBuf(dst, pos, bLen);
        for (int id : rawBiomeGlobalDictIds) {
            pos = writeVarIntToBuf(dst, pos, id);
        }
        if (bLen == 1) {
            return pos;
        }
        int[] cells = arena.biomeCells;
        if (rawBiomeDataLongs == null || rawBiomeDataLongs.length == 0) {
            Arrays.fill(cells, 0);
        } else {
            int bits = BiomePaletteSection.biomeBitsPerEntry(bLen);
            int entriesPerLong = 64 / bits;
            long mask = (1L << bits) - 1L;
            int idx = 0;
            for (int w = 0; w < rawBiomeDataLongs.length && idx < BIOME_CELLS; w++) {
                long word = rawBiomeDataLongs[w];
                int limit = Math.min(entriesPerLong, BIOME_CELLS - idx);
                for (int s = 0; s < limit; s++) {
                    int p = (int) (word & mask);
                    if (p >= bLen) p = 0;
                    cells[idx++] = p;
                    word >>>= bits;
                }
            }
            while (idx < BIOME_CELLS) {
                cells[idx++] = 0;
            }
        }
        int maxRun = (bLen <= 8) ? 32 : 64;
        int runCount = 0;
        int[] staging = arena.runStaging;
        int curVal = cells[0];
        int curLen = 1;
        for (int i = 1; i < BIOME_CELLS; i++) {
            int yzx = ((i & 3) << 4) | (i & 0x0C) | ((i >>> 4) & 3);
            int v = cells[yzx];
            if (v == curVal && curLen < maxRun) {
                curLen++;
            } else {
                staging[runCount++] = (curVal << 8) | ((curLen - 1) & 0xFF);
                curVal = v;
                curLen = 1;
            }
        }
        staging[runCount++] = (curVal << 8) | ((curLen - 1) & 0xFF);
        pos = writeVarIntToBuf(dst, pos, runCount);
        if (bLen <= 8) {
            for (int r = 0; r < runCount; r++) {
                int pIdx = (staging[r] >>> 8) & 7;
                int lm1 = staging[r] & 0x1F;
                dst[pos++] = (byte) ((pIdx << 5) | lm1);
            }
        } else {
            for (int r = 0; r < runCount; r++) {
                pos = writeVarIntToBuf(dst, pos, staging[r] >>> 8);
                dst[pos++] = (byte) (staging[r] & 0xFF);
            }
        }
        return pos;
    }

    /**
     * Decoded section representation used by {@link LcaRegionReader}.
     */
    static final class DecodedSection {
        final int sectionY;
        final int curveId;
        final long octreeOccupancyMask64;
        final List<String> blockStateCanonicalPalette;
        final List<String> blockBaseNamePalette;
        final long[] packedBlockLongs;
        final List<String> biomePalette;
        final long[] packedBiomeLongs;
        final byte[] skyLight2048;
        final byte[] blockLight2048;

        DecodedSection(
                int sectionY,
                int curveId,
                long octreeOccupancyMask64,
                List<String> blockStateCanonicalPalette,
                List<String> blockBaseNamePalette,
                long[] packedBlockLongs,
                List<String> biomePalette,
                long[] packedBiomeLongs,
                byte[] skyLight2048,
                byte[] blockLight2048
        ) {
            this.sectionY = sectionY;
            this.curveId = curveId;
            this.octreeOccupancyMask64 = octreeOccupancyMask64;
            this.blockStateCanonicalPalette = blockStateCanonicalPalette;
            this.blockBaseNamePalette = blockBaseNamePalette;
            this.packedBlockLongs = packedBlockLongs;
            this.biomePalette = biomePalette;
            this.packedBiomeLongs = packedBiomeLongs;
            this.skyLight2048 = skyLight2048;
            this.blockLight2048 = blockLight2048;
        }

        PaletteSection toPaletteSection() {
            return new PaletteSection(sectionY, blockBaseNamePalette, packedBlockLongs);
        }

        BiomePaletteSection toBiomePaletteSection() {
            if (biomePalette == null || biomePalette.isEmpty()) return null;
            return new BiomePaletteSection(sectionY, biomePalette, packedBiomeLongs);
        }
    }

    /**
     * Decodes a non-uniform (or overflow uniform) `SectionFrame` at `buf[offset .. limit)`,
     * enforcing `CBIT` tripwires `T6` (run cursor $h \le 4,096$) and `T7` (`dictId < totalDictStates`).
     */
    static DecodedSection decodeSectionFrame(
            byte[] buf,
            int offset,
            int limit,
            int sectionY,
            List<String> dictCanonicalStates,
            List<String> dictBaseNames,
            List<String> dictBiomes,
            boolean skipLighting,
            boolean skipBiomes
    ) throws CorruptRegionEntryException {
        return decodeSectionFrame(
                buf, offset, limit, sectionY, dictCanonicalStates, dictBaseNames, dictBiomes,
                Collections.emptyList(), skipLighting, skipBiomes);
    }

    static DecodedSection decodeSectionFrame(
            byte[] buf,
            int offset,
            int limit,
            int sectionY,
            List<String> dictCanonicalStates,
            List<String> dictBaseNames,
            List<String> dictBiomes,
            List<BiomeTemplate> chunkBiomeTemplates,
            boolean skipLighting,
            boolean skipBiomes
    ) throws CorruptRegionEntryException {
        if (offset + 2 > limit) {
            throw new CorruptRegionEntryException("CBIT T5: SectionFrame truncated at offset " + offset);
        }
        int pos = offset;
        int sectionHeader = buf[pos++] & 0xFF;
        int curveId = (sectionHeader >>> 6) & 3;
        int blockMode = (sectionHeader >>> 4) & 3;
        int skyMode = (sectionHeader >>> 2) & 3;
        int blkMode = sectionHeader & 3;

        long octreeMask64;
        if (blockMode == BLOCK_MODE_UNIFORM) {
            octreeMask64 = 0L;
        } else if (blockMode == BLOCK_MODE_DELTA_ROARING_FULL_MASK) {
            octreeMask64 = -1L;
        } else {
            if (pos + 8 > limit) {
                throw new CorruptRegionEntryException("CBIT T5: SectionFrame truncated reading octreeOccupancyMask64 at " + offset);
            }
            octreeMask64 = readLongBE(buf, pos);
            pos += 8;
        }

        int paletteLen = readVarInt(buf, pos, limit);
        pos += varIntLen(paletteLen);
        if (paletteLen < 1 || paletteLen > SECTION_VOXELS) {
            throw new CorruptRegionEntryException("CBIT T7: Invalid section paletteLen " + paletteLen);
        }

        int totalDictStates = dictBaseNames.size();
        List<String> canonicalPalette = skipLighting ? Collections.emptyList() : new ArrayList<>(paletteLen);
        List<String> baseNamePalette = new ArrayList<>(paletteLen);
        for (int i = 0; i < paletteLen; i++) {
            int dictId = readVarInt(buf, pos, limit);
            pos += varIntLen(dictId);
            if (dictId < 0 || dictId >= totalDictStates) {
                throw new CorruptRegionEntryException("CBIT T7: Section dictId " + dictId
                        + " >= totalDictStateCount " + totalDictStates);
            }
            if (!skipLighting) {
                canonicalPalette.add(dictCanonicalStates.get(dictId));
            }
            baseNamePalette.add(dictBaseNames.get(dictId));
        }

        ScratchArena arena = scratch();
        long[] packedBlockLongs = null;

        if (blockMode == BLOCK_MODE_UNIFORM || paletteLen == 1) {
            // Uniform section: packedBlockLongs stays null
        } else if (blockMode == BLOCK_MODE_DELTA_ROARING || blockMode == BLOCK_MODE_DELTA_ROARING_FULL_MASK) {
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteLen - 1));
            int entriesPerLong = 64 / bits;
            int numLongs = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
            packedBlockLongs = new long[numLongs];
            pos = decodeDeltaRoaringBlocks(
                    buf, pos, limit, paletteLen, CURVE_TO_YZX[curveId], octreeMask64,
                    packedBlockLongs, bits, entriesPerLong);
        } else if (blockMode == BLOCK_MODE_FLAT_BITPACK) {
            pos = decodeFlatBitPackBlocks(buf, pos, limit, paletteLen, octreeMask64, arena.yzxPaletteIdx);
            packedBlockLongs = packBlockLongs(arena.yzxPaletteIdx, paletteLen);
        } else {
            throw new CorruptRegionEntryException("CBIT: Unsupported blockEncodingMode " + blockMode);
        }

        if (skipLighting && skipBiomes) {
            return new DecodedSection(
                    sectionY, curveId, octreeMask64,
                    Collections.emptyList(), Collections.unmodifiableList(baseNamePalette),
                    packedBlockLongs, Collections.emptyList(), null, null, null);
        }

        List<String> biomePalette = Collections.emptyList();
        long[] packedBiomeLongs = null;
        if (pos < limit) {
            int firstByte = buf[pos] & 0xFF;
            if ((firstByte & 0x80) != 0) {
                pos++;
                int tmplIdx = firstByte & 0x7F;
                if (chunkBiomeTemplates == null || tmplIdx >= chunkBiomeTemplates.size()) {
                    throw new CorruptRegionEntryException("CBIT T7: Biome template index " + tmplIdx + " out of bounds");
                }
                if (!skipBiomes) {
                    BiomeTemplate tmpl = chunkBiomeTemplates.get(tmplIdx);
                    biomePalette = tmpl.palette;
                    packedBiomeLongs = tmpl.packedBiomeLongs;
                }
            } else {
                int bLen = readVarInt(buf, pos, limit);
                pos += varIntLen(bLen);
                if (bLen > 0) {
                    if (!skipBiomes) {
                        biomePalette = new ArrayList<>(bLen);
                    }
                    pos = decodeSectionBiomePayload(buf, pos, limit, bLen, dictBiomes, skipBiomes, biomePalette, arena);
                    if (!skipBiomes && bLen > 1) {
                        packedBiomeLongs = packBiomeLongs(arena.biomeCells, bLen);
                    }
                }
            }
        }

        if (skipLighting) {
            return new DecodedSection(
                    sectionY,
                    curveId,
                    octreeMask64,
                    Collections.emptyList(),
                    Collections.unmodifiableList(baseNamePalette),
                    packedBlockLongs,
                    biomePalette.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(biomePalette),
                    packedBiomeLongs,
                    null,
                    null
            );
        }

        return decodeSectionLightPayload(
                buf, pos, limit, sectionY, curveId, octreeMask64, skyMode, blkMode,
                canonicalPalette, baseNamePalette, packedBlockLongs, biomePalette, packedBiomeLongs, arena);
    }

    private static void setPackedVoxel(long[] packed, int yzx, long p, int bits, int entriesPerLong) {
        if (bits == 4) {
            packed[yzx >>> 4] |= p << ((yzx & 15) << 2);
        } else {
            packed[yzx / entriesPerLong] |= p << ((yzx % entriesPerLong) * bits);
        }
    }

    private static int decodeDeltaRoaringBlocks(
            byte[] buf,
            int pos,
            int limit,
            int paletteLen,
            int[] curveToYzx,
            long expectedOctreeMask64,
            long[] packedBlockLongs,
            int bits,
            int entriesPerLong
    ) throws CorruptRegionEntryException {
        long decodedOctreeMask64 = 0L;
        for (int p = 1; p < paletteLen; p++) {
            if (pos + 2 > limit) {
                throw new CorruptRegionEntryException("CBIT T6: Truncated minority container header for p=" + p);
            }
            int word16 = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
            pos += 2;
            int tag = (word16 >>> 14) & 3;
            int countOrIdx = word16 & 0x3FFF;
            long pLong = p;

            if (tag == TAG_SINGLETON) {
                if (countOrIdx >= SECTION_VOXELS) {
                    throw new CorruptRegionEntryException("CBIT T6: Singleton index out of bounds: " + countOrIdx);
                }
                int yzx = curveToYzx[countOrIdx];
                setPackedVoxel(packedBlockLongs, yzx, pLong, bits, entriesPerLong);
                decodedOctreeMask64 |= (1L << YZX_TO_OCTANT6[yzx]);
            } else if (tag == TAG_BITMAP) {
                if (pos + 512 > limit) {
                    throw new CorruptRegionEntryException("CBIT T6: Truncated TAG_BITMAP container");
                }
                for (int b = 0; b < 512; b++) {
                    int mask8 = buf[pos++] & 0xFF;
                    if (mask8 == 0) continue;
                    int base = b << 3;
                    for (int bit = 0; bit < 8; bit++) {
                        if ((mask8 & (1 << bit)) != 0) {
                            int yzx = curveToYzx[base + bit];
                            setPackedVoxel(packedBlockLongs, yzx, pLong, bits, entriesPerLong);
                            decodedOctreeMask64 |= (1L << YZX_TO_OCTANT6[yzx]);
                        }
                    }
                }
            } else if (tag == TAG_DELTA_ARRAY) {
                int cursor = 0;
                for (int r = 0; r < countOrIdx; r++) {
                    if (pos >= limit) {
                        throw new CorruptRegionEntryException("CBIT T6: Truncated TAG_DELTA_ARRAY");
                    }
                    int adjGap = buf[pos++] & 0xFF;
                    if (adjGap >= 240) {
                        if (pos >= limit) {
                            throw new CorruptRegionEntryException("CBIT T6: Truncated 2-byte TAG_DELTA_ARRAY gap");
                        }
                        adjGap = ((adjGap & 0x0F) << 8) | (buf[pos++] & 0xFF);
                    }
                    cursor += (r == 0) ? adjGap : (adjGap + 1);
                    if (cursor < 0 || cursor >= SECTION_VOXELS) {
                        throw new CorruptRegionEntryException("CBIT T6: TAG_DELTA_ARRAY cursor overflow: " + cursor);
                    }
                    int yzx = curveToYzx[cursor++];
                    setPackedVoxel(packedBlockLongs, yzx, pLong, bits, entriesPerLong);
                    decodedOctreeMask64 |= (1L << YZX_TO_OCTANT6[yzx]);
                }
            } else {
                boolean subMode62 = (countOrIdx & 0x2000) != 0;
                int runCount = countOrIdx & 0x1FFF;
                int cursor = 0;
                for (int r = 0; r < runCount; r++) {
                    if (pos >= limit) {
                        throw new CorruptRegionEntryException("CBIT T6: Truncated TAG_DELTA_RUN");
                    }
                    int tok = buf[pos++] & 0xFF;
                    int adjGap;
                    int lenMinusOne;
                    if (subMode62) {
                        adjGap = (tok >>> 2) & 0x3F;
                        lenMinusOne = tok & 3;
                        if (adjGap == 63) {
                            if (pos >= limit) throw new CorruptRegionEntryException("CBIT T6: Truncated 6|2 gap");
                            int b0 = buf[pos++] & 0xFF;
                            if (b0 < 240) {
                                adjGap += b0;
                            } else {
                                if (pos >= limit) throw new CorruptRegionEntryException("CBIT T6: Truncated 2B 6|2 gap");
                                adjGap += ((b0 & 0x0F) << 8) | (buf[pos++] & 0xFF);
                            }
                        }
                        if (lenMinusOne == 3) {
                            int extra = readVarInt(buf, pos, limit);
                            pos += varIntLen(extra);
                            lenMinusOne += extra;
                        }
                    } else {
                        adjGap = (tok >>> 4) & 0x0F;
                        lenMinusOne = tok & 0x0F;
                        if (adjGap == 15) {
                            if (pos >= limit) {
                                throw new CorruptRegionEntryException("CBIT T6: Truncated bounded gap byte");
                            }
                            int b0 = buf[pos++] & 0xFF;
                            if (b0 < 240) {
                                adjGap += b0;
                            } else {
                                if (pos >= limit) {
                                    throw new CorruptRegionEntryException("CBIT T6: Truncated 2-byte bounded gap");
                                }
                                adjGap += ((b0 & 0x0F) << 8) | (buf[pos++] & 0xFF);
                            }
                        }
                        if (lenMinusOne == 15) {
                            int extra = readVarInt(buf, pos, limit);
                            pos += varIntLen(extra);
                            lenMinusOne += extra;
                        }
                    }
                    cursor += (r == 0) ? adjGap : (adjGap + 1);
                    int runLen = lenMinusOne + 1;
                    if (cursor < 0 || cursor + runLen > SECTION_VOXELS) {
                        throw new CorruptRegionEntryException("CBIT T6: Delta-Roaring run cursor exceeded 4096 ("
                                + (cursor + runLen) + ")");
                    }
                    for (int k = 0; k < runLen; k++) {
                        int yzx = curveToYzx[cursor++];
                        setPackedVoxel(packedBlockLongs, yzx, pLong, bits, entriesPerLong);
                        decodedOctreeMask64 |= (1L << YZX_TO_OCTANT6[yzx]);
                    }
                }
            }
        }
        if (decodedOctreeMask64 != expectedOctreeMask64) {
            throw new CorruptRegionEntryException("CBIT T6b: Section octreeOccupancyMask64 syndrome mismatch (expected=0x"
                    + Long.toHexString(expectedOctreeMask64) + " actual=0x" + Long.toHexString(decodedOctreeMask64) + ")");
        }
        return pos;
    }

    private static int decodeFlatBitPackBlocks(
            byte[] buf, int pos, int limit, int paletteLen, long expectedOctreeMask64, int[] yzxIdx
    ) throws CorruptRegionEntryException {
        int flatBits = Math.max(1, 32 - Integer.numberOfLeadingZeros(paletteLen - 1));
        int flatByteCount = (SECTION_VOXELS * flatBits + 7) >>> 3;
        if (pos + flatByteCount > limit) {
            throw new CorruptRegionEntryException("CBIT T6: Truncated FlatBitPack section payload");
        }
        long decodedOctreeMask64 = 0L;
        int bitPos = 0;
        for (int i = 0; i < SECTION_VOXELS; i++) {
            int val = 0;
            for (int b = 0; b < flatBits; b++) {
                if (((buf[pos + (bitPos >>> 3)] & 0xFF) & (1 << (bitPos & 7))) != 0) {
                    val |= (1 << b);
                }
                bitPos++;
            }
            if (val >= paletteLen) {
                throw new CorruptRegionEntryException("CBIT T7: FlatBitPack palette index " + val + " >= " + paletteLen);
            }
            yzxIdx[i] = val;
            if (val != 0) {
                decodedOctreeMask64 |= (1L << YZX_TO_OCTANT6[i]);
            }
        }
        if (decodedOctreeMask64 != expectedOctreeMask64) {
            throw new CorruptRegionEntryException("CBIT T6b: FlatBitPack octreeOccupancyMask64 syndrome mismatch");
        }
        return pos + flatByteCount;
    }

    static int decodeSectionBiomePayload(
            byte[] buf,
            int pos,
            int limit,
            int bLen,
            List<String> dictBiomes,
            boolean skipBiomes,
            List<String> biomePaletteOut,
            ScratchArena arena
    ) throws CorruptRegionEntryException {
        if (bLen > BIOME_CELLS) {
            throw new CorruptRegionEntryException("CBIT T7: Invalid biome palette length " + bLen);
        }
        for (int i = 0; i < bLen; i++) {
            int bDictId = readVarInt(buf, pos, limit);
            pos += varIntLen(bDictId);
            if (bDictId < 0 || bDictId >= dictBiomes.size()) {
                throw new CorruptRegionEntryException("CBIT T7: Biome dictId " + bDictId + " out of range");
            }
            if (!skipBiomes) {
                biomePaletteOut.add(dictBiomes.get(bDictId));
            }
        }
        if (bLen > 1) {
            int runCount = readVarInt(buf, pos, limit);
            pos += varIntLen(runCount);
            int[] cells = arena.biomeCells;
            int cursor = 0;
            if (bLen <= 8) {
                for (int r = 0; r < runCount; r++) {
                    if (pos >= limit) throw new CorruptRegionEntryException("CBIT: Truncated 1B biome RLE");
                    int packed = buf[pos++] & 0xFF;
                    int pIdx = (packed >>> 5) & 7;
                    int len = (packed & 0x1F) + 1;
                    if (pIdx >= bLen || cursor + len > BIOME_CELLS) {
                        throw new CorruptRegionEntryException("CBIT: Biome RLE cursor overflow");
                    }
                    if (!skipBiomes) {
                        for (int k = 0; k < len; k++) {
                            int yzx = ((cursor & 3) << 4) | (cursor & 0x0C) | ((cursor >>> 4) & 3);
                            cells[yzx] = pIdx;
                            cursor++;
                        }
                    } else {
                        cursor += len;
                    }
                }
            } else {
                for (int r = 0; r < runCount; r++) {
                    int pIdx = readVarInt(buf, pos, limit);
                    pos += varIntLen(pIdx);
                    if (pos >= limit) throw new CorruptRegionEntryException("CBIT: Truncated biome RLE");
                    int len = (buf[pos++] & 0xFF) + 1;
                    if (pIdx < 0 || pIdx >= bLen || cursor + len > BIOME_CELLS) {
                        throw new CorruptRegionEntryException("CBIT: Biome RLE cursor overflow");
                    }
                    if (!skipBiomes) {
                        for (int k = 0; k < len; k++) {
                            int yzx = ((cursor & 3) << 4) | (cursor & 0x0C) | ((cursor >>> 4) & 3);
                            cells[yzx] = pIdx;
                            cursor++;
                        }
                    } else {
                        cursor += len;
                    }
                }
            }
        }
        return pos;
    }

    private static DecodedSection decodeSectionLightPayload(
            byte[] buf,
            int pos,
            int limit,
            int sectionY,
            int curveId,
            long octreeMask64,
            int skyMode,
            int blkMode,
            List<String> canonicalPalette,
            List<String> baseNamePalette,
            long[] packedBlockLongs,
            List<String> biomePalette,
            long[] packedBiomeLongs,
            ScratchArena arena
    ) throws CorruptRegionEntryException {
        int constNibbles = 0;
        if (skyMode == LIGHT_MODE_CONST || blkMode == LIGHT_MODE_CONST) {
            if (pos >= limit) {
                throw new CorruptRegionEntryException("CBIT: Truncated const light byte in SectionFrame");
            }
            constNibbles = buf[pos++] & 0xFF;
        }

        byte[] sky2048 = null;
        if (skyMode == LIGHT_MODE_ABSENT) {
            // Absent (0 bytes)
        } else if (skyMode == LIGHT_MODE_CONST) {
            int c = constNibbles & 0x0F;
            sky2048 = new byte[2048];
            if (c != 0) {
                Arrays.fill(sky2048, (byte) ((c << 4) | c));
            }
        } else if (skyMode == LIGHT_MODE_GRAY_PLANES) {
            pos = decodeGrayPlanes(buf, pos, limit, CURVE_TO_YZX[CURVE_Y_H2], arena);
            sky2048 = packLightNibbles(arena.skyLightNibbles);
        } else {
            if (pos + 2048 > limit) throw new CorruptRegionEntryException("CBIT: Truncated raw SkyLight");
            sky2048 = Arrays.copyOfRange(buf, pos, pos + 2048);
            pos += 2048;
        }

        byte[] blk2048 = null;
        if (blkMode == LIGHT_MODE_ABSENT) {
            // Absent (0 bytes)
        } else if (blkMode == LIGHT_MODE_CONST) {
            int c = (constNibbles >>> 4) & 0x0F;
            blk2048 = new byte[2048];
            if (c != 0) {
                Arrays.fill(blk2048, (byte) ((c << 4) | c));
            }
        } else if (blkMode == LIGHT_MODE_GRAY_PLANES) {
            decodeGrayPlanes(buf, pos, limit, CURVE_TO_YZX[CURVE_H3], arena);
            blk2048 = packLightNibbles(arena.skyLightNibbles);
        } else {
            if (pos + 2048 > limit) throw new CorruptRegionEntryException("CBIT: Truncated raw BlockLight");
            blk2048 = Arrays.copyOfRange(buf, pos, pos + 2048);
        }

        return new DecodedSection(
                sectionY,
                curveId,
                octreeMask64,
                Collections.unmodifiableList(canonicalPalette),
                Collections.unmodifiableList(baseNamePalette),
                packedBlockLongs,
                biomePalette.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(biomePalette),
                packedBiomeLongs,
                sky2048,
                blk2048
        );
    }

    private static int decodeGrayPlanes(
            byte[] buf, int pos, int limit, int[] curveToYzx, ScratchArena arena
    ) throws CorruptRegionEntryException {
        if (pos >= limit) throw new CorruptRegionEntryException("CBIT: Truncated Gray light planeModes8 header");
        int planeModes8 = buf[pos++] & 0xFF;
        int[] grayCurve = arena.grayCurve;
        Arrays.fill(grayCurve, 0);
        for (int plane = 0; plane < 4; plane++) {
            int mode = (planeModes8 >>> (plane << 1)) & 3;
            int bit = 1 << plane;
            if (mode == 0) {
                // All-0
            } else if (mode == 1) {
                for (int i = 0; i < SECTION_VOXELS; i++) grayCurve[i] |= bit;
            } else if (mode == 2) {
                int runCount = readVarInt(buf, pos, limit);
                pos += varIntLen(runCount);
                int cursor = 0;
                for (int r = 0; r < runCount; r++) {
                    if (pos >= limit) throw new CorruptRegionEntryException("CBIT: Truncated Gray light run");
                    int tok = buf[pos++] & 0xFF;
                    int adjGap = (tok >>> 4) & 0x0F;
                    int lenMinusOne = tok & 0x0F;
                    if (adjGap == 15) {
                        int extra = readVarInt(buf, pos, limit);
                        pos += varIntLen(extra);
                        adjGap += extra;
                    }
                    if (lenMinusOne == 15) {
                        int extra = readVarInt(buf, pos, limit);
                        pos += varIntLen(extra);
                        lenMinusOne += extra;
                    }
                    cursor += (r == 0) ? adjGap : (adjGap + 1);
                    int runLen = lenMinusOne + 1;
                    if (cursor < 0 || cursor + runLen > SECTION_VOXELS) {
                        throw new CorruptRegionEntryException("CBIT T6: Gray light run cursor overflow");
                    }
                    for (int k = 0; k < runLen; k++) {
                        grayCurve[cursor++] |= bit;
                    }
                }
            } else {
                if (pos + 512 > limit) {
                    throw new CorruptRegionEntryException("CBIT: Truncated 512B Gray light bitmap");
                }
                for (int b = 0; b < 512; b++) {
                    int mask8 = buf[pos + b] & 0xFF;
                    if (mask8 == 0) continue;
                    int base = b << 3;
                    for (int k = 0; k < 8; k++) {
                        if ((mask8 & (1 << k)) != 0) {
                            grayCurve[base + k] |= bit;
                        }
                    }
                }
                pos += 512;
            }
        }
        byte[] out = arena.skyLightNibbles;
        for (int k = 0; k < SECTION_VOXELS; k++) {
            int g = grayCurve[k];
            int l = g ^ (g >>> 1);
            l = l ^ (l >>> 2);
            out[curveToYzx[k]] = (byte) (l & 0x0F);
        }
        return pos;
    }

    private static byte[] packLightNibbles(byte[] nibbles4096) {
        byte[] out = new byte[2048];
        for (int i = 0; i < 2048; i++) {
            int lo = nibbles4096[i << 1] & 0x0F;
            int hi = nibbles4096[(i << 1) | 1] & 0x0F;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static long[] packBlockLongs(int[] yzxIdx, int paletteSize) {
        int bits = PackedPaletteDecoder.bitsPerEntry(paletteSize);
        int entriesPerLong = 64 / bits;
        int numLongs = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
        long[] longs = new long[numLongs];
        int voxel = 0;
        for (int w = 0; w < numLongs; w++) {
            long word = 0L;
            int limit = Math.min(entriesPerLong, SECTION_VOXELS - voxel);
            for (int s = 0; s < limit; s++) {
                word |= ((long) yzxIdx[voxel++]) << (s * bits);
            }
            longs[w] = word;
        }
        return longs;
    }

    private static long[] packBiomeLongs(int[] cells64, int paletteSize) {
        int bits = BiomePaletteSection.biomeBitsPerEntry(paletteSize);
        int entriesPerLong = 64 / bits;
        int numLongs = (BIOME_CELLS + entriesPerLong - 1) / entriesPerLong;
        long[] longs = new long[numLongs];
        int cell = 0;
        for (int w = 0; w < numLongs; w++) {
            long word = 0L;
            int limit = Math.min(entriesPerLong, BIOME_CELLS - cell);
            for (int s = 0; s < limit; s++) {
                word |= ((long) cells64[cell++]) << (s * bits);
            }
            longs[w] = word;
        }
        return longs;
    }

    static void writeLongBEToBuf(byte[] dst, int offset, long v) {
        LcaRegionReader.LONG_BE_VIEW.set(dst, offset, v);
    }

    static long readLongBE(byte[] buf, int offset) {
        return (long) LcaRegionReader.LONG_BE_VIEW.get(buf, offset);
    }

    static int readIntBE(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24)
                | ((buf[offset + 1] & 0xFF) << 16)
                | ((buf[offset + 2] & 0xFF) << 8)
                | (buf[offset + 3] & 0xFF);
    }

    static int readUint16BE(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 8) | (buf[offset + 1] & 0xFF);
    }

    static String stripBlockProperties(String canonicalState) {
        int bracket = canonicalState.indexOf('[');
        return bracket >= 0 ? canonicalState.substring(0, bracket) : canonicalState;
    }

    static LinkedHashMap<String, Object> parseCanonicalStateToNbtCompound(String canonicalState) {
        LinkedHashMap<String, Object> compound = new LinkedHashMap<>(4);
        int bracket = canonicalState.indexOf('[');
        if (bracket < 0 || !canonicalState.endsWith("]")) {
            compound.put("Name", canonicalState);
            return compound;
        }
        compound.put("Name", canonicalState.substring(0, bracket));
        String propsStr = canonicalState.substring(bracket + 1, canonicalState.length() - 1);
        if (!propsStr.isEmpty()) {
            LinkedHashMap<String, Object> props = new LinkedHashMap<>();
            int start = 0;
            while (start < propsStr.length()) {
                int comma = propsStr.indexOf(',', start);
                String pair = (comma < 0) ? propsStr.substring(start) : propsStr.substring(start, comma);
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    props.put(pair.substring(0, eq), pair.substring(eq + 1));
                }
                if (comma < 0) break;
                start = comma + 1;
            }
            if (!props.isEmpty()) {
                compound.put("Properties", props);
            }
        }
        return compound;
    }

    static String decodeStatusOrdinal(int ordinal) {
        return switch (ordinal) {
            case 0xFF -> "minecraft:full";
            case 0xEF -> "full";
            case 1 -> "minecraft:empty";
            case 2 -> "minecraft:structure_starts";
            case 3 -> "minecraft:structure_references";
            case 4 -> "minecraft:biomes";
            case 5 -> "minecraft:noise";
            case 6 -> "minecraft:surface";
            case 7 -> "minecraft:carvers";
            case 8 -> "minecraft:features";
            case 9 -> "minecraft:initialize_light";
            case 10 -> "minecraft:light";
            case 11 -> "minecraft:spawn";
            default -> "minecraft:full";
        };
    }

    static int encodeStatusOrdinal(Object statusObj) {
        if (!(statusObj instanceof String s)) return 0xFE;
        return switch (s) {
            case "minecraft:full" -> 0xFF;
            case "full" -> 0xEF;
            case "minecraft:empty", "empty" -> 1;
            case "minecraft:structure_starts", "structure_starts" -> 2;
            case "minecraft:structure_references", "structure_references" -> 3;
            case "minecraft:biomes", "biomes" -> 4;
            case "minecraft:noise", "noise" -> 5;
            case "minecraft:surface", "surface" -> 6;
            case "minecraft:carvers", "carvers" -> 7;
            case "minecraft:features", "features" -> 8;
            case "minecraft:initialize_light", "initialize_light" -> 9;
            case "minecraft:light", "light" -> 10;
            case "minecraft:spawn", "spawn" -> 11;
            default -> 0xFF;
        };
    }

    static final String[] STANDARD_HEIGHTMAP_NAMES = {
        "MOTION_BLOCKING",
        "OCEAN_FLOOR",
        "WORLD_SURFACE",
        "OCEAN_FLOOR_WG",
        "WORLD_SURFACE_WG",
        "MOTION_BLOCKING_NO_LEAVES"
    };

    static int encodeStandardHeightmapCode(String name) {
        for (int i = 0; i < STANDARD_HEIGHTMAP_NAMES.length; i++) {
            if (STANDARD_HEIGHTMAP_NAMES[i].equals(name)) return i;
        }
        return 0xFF;
    }

    record ResidualTailDecoded(
            LinkedHashMap<String, Object> rootMap,
            List<Object> lowBoundarySections,
            List<Object> highBoundarySections,
            boolean nonVoxelRoot
    ) {}

    @SuppressWarnings("java:S1199")
    static ResidualTailDecoded unpackResidualSectionAndNbt(
            byte[] frame, int fOff, int residualRelStart, int residualRelEnd, int minSectionY, int sectionCount
    ) throws IOException {
        int pos = fOff + residualRelStart;
        int limit = fOff + residualRelEnd;
        if (pos + 2 > limit) {
            return new ResidualTailDecoded(new LinkedHashMap<>(), Collections.emptyList(), Collections.emptyList(), false);
        }
        int bpFlags = readUint16BE(frame, pos);
        pos += 2;

        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        List<Object> lowBoundarySections = Collections.emptyList();
        List<Object> highBoundarySections = Collections.emptyList();
        boolean nonVoxelRoot = false;
        List<long[]> decodedHmByOrder = new ArrayList<>(6);
        LinkedHashMap<String, Object> hmOut = new LinkedHashMap<>();
        if ((bpFlags & 8192) != 0) {
            int mbnlLen = readVarInt(frame, pos, limit);
            pos += varIntLen(mbnlLen);
            if (mbnlLen < 0 || pos + mbnlLen > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated MOTION_BLOCKING_NO_LEAVES prefix");
            }
            if (mbnlLen > 0) {
                long[] baseLongs = decodeDeltaHeightmapLongs(frame, pos, mbnlLen);
                decodedHmByOrder.add(baseLongs);
                hmOut.put("MOTION_BLOCKING_NO_LEAVES", baseLongs);
            }
            pos += mbnlLen;
        }

        int tailLimit = limit;
        {

            if ((bpFlags & 1) != 0) {
                root.put("block_entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, Collections.emptyList()));
            }
            if ((bpFlags & 2) != 0) {
                root.put("block_ticks", new Nbt.NbtList(Nbt.TAG_COMPOUND, Collections.emptyList()));
            }
            if ((bpFlags & 4) != 0) {
                root.put("fluid_ticks", new Nbt.NbtList(Nbt.TAG_COMPOUND, Collections.emptyList()));
            }
            if ((bpFlags & 8) != 0) {
                root.put("isLightOn", (byte) 1);
            }
            if ((bpFlags & 16) != 0) {
                LinkedHashMap<String, Object> structs = new LinkedHashMap<>(4);
                structs.put("References", new LinkedHashMap<>());
                structs.put("Starts", new LinkedHashMap<>());
                root.put("structures", structs);
            }
            if ((bpFlags & 32) != 0 && pos < tailLimit) {
                int ppCount = frame[pos++] & 0xFF;
                List<Object> ppItems = new ArrayList<>(ppCount);
                for (int i = 0; i < ppCount; i++) {
                    ppItems.add(new Nbt.NbtList(Nbt.TAG_SHORT, Collections.emptyList()));
                }
                root.put("PostProcessing", new Nbt.NbtList(Nbt.TAG_LIST, ppItems));
            }
            if ((bpFlags & 64) != 0 && pos < tailLimit) {
                int slVer = readVarInt(frame, pos, tailLimit);
                pos += varIntLen(slVer);
                root.put("starlight.light_version", slVer);
            }
            if ((bpFlags & 128) != 0 && pos < tailLimit) {
                int lowFlags = frame[pos++] & 0xFF;
                lowBoundarySections = List.of(reconstructCompactBoundarySection(minSectionY - 1, lowFlags));
            }
            if ((bpFlags & 256) != 0 && pos < tailLimit) {
                int highFlags = frame[pos++] & 0xFF;
                highBoundarySections = List.of(reconstructCompactBoundarySection(minSectionY + sectionCount, highFlags));
            }
            if ((bpFlags & 512) != 0 && pos < tailLimit) {
                int secHmCount = frame[pos++] & 0xFF;
                for (int h = 0; h < secHmCount; h++) {
                    int code = frame[pos++] & 0xFF;
                    String hmKey;
                    if (code < STANDARD_HEIGHTMAP_NAMES.length) {
                        hmKey = STANDARD_HEIGHTMAP_NAMES[code];
                    } else {
                        int kLen = readVarInt(frame, pos, tailLimit);
                        pos += varIntLen(kLen);
                        hmKey = new String(frame, pos, kLen, StandardCharsets.UTF_8);
                        pos += kLen;
                    }
                    int payLen = readVarInt(frame, pos, tailLimit);
                    pos += varIntLen(payLen);
                    byte[] deltaBytes = Arrays.copyOfRange(frame, pos, pos + payLen);
                    pos += payLen;
                    long[] longs = decodeSecondaryHeightmapPayload(deltaBytes, decodedHmByOrder);
                    decodedHmByOrder.add(longs);
                    hmOut.put(hmKey, longs);
                }
            }
            if ((bpFlags & BP_FLAG_HAS_CHUNK_PATCH_TAIL) != 0) {
                int patchLen = readVarInt(frame, pos, tailLimit);
                pos += varIntLen(patchLen);
                if (patchLen < 3 || pos + patchLen > tailLimit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated chunkPatchTail in residual tail");
                }
                pos += patchLen;
            }
            if ((bpFlags & 1024) != 0 && pos < tailLimit) {
                byte[] nbtSlice = Arrays.copyOfRange(frame, pos, tailLimit);
                if ((bpFlags & 4096) != 0) {
                    try (java.util.zip.InflaterInputStream iis =
                            new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(nbtSlice))) {
                        nbtSlice = iis.readAllBytes();
                    }
                }
                LinkedHashMap<String, Object> parsedNbt = Nbt.readRootCompound(nbtSlice);
                if (parsedNbt.remove("_lca_boundary_low") instanceof Nbt.NbtList lowFallback) {
                    lowBoundarySections = lowFallback.items;
                }
                if (parsedNbt.remove("_lca_boundary_high") instanceof Nbt.NbtList highFallback) {
                    highBoundarySections = highFallback.items;
                }
                root.putAll(parsedNbt);
            }
            if ((bpFlags & 2048) != 0) {
                nonVoxelRoot = true;
            }
        }

        if (!hmOut.isEmpty()) {
            root.put("Heightmaps", hmOut);
        }
        return new ResidualTailDecoded(root, lowBoundarySections, highBoundarySections, nonVoxelRoot);
    }

    private static LinkedHashMap<String, Object> reconstructCompactBoundarySection(int sectionY, int flags) {
        LinkedHashMap<String, Object> sec = new LinkedHashMap<>(4);
        sec.put("Y", (byte) sectionY);
        int skyMode = flags & 3;
        int blkMode = (flags >>> 2) & 3;
        if (skyMode == 1 || skyMode == 2) {
            byte[] sky = new byte[2048];
            if (skyMode == 2) Arrays.fill(sky, (byte) 0xFF);
            sec.put("SkyLight", sky);
        }
        if (blkMode == 1 || blkMode == 2) {
            byte[] blk = new byte[2048];
            if (blkMode == 2) Arrays.fill(blk, (byte) 0xFF);
            sec.put("BlockLight", blk);
        }
        return sec;
    }

    private static long[] decodeSecondaryHeightmapPayload(byte[] deltaBytes, List<long[]> decodedByOrder)
            throws CorruptRegionEntryException {
        if (deltaBytes.length == 2 && (deltaBytes[0] & 0xFF) == 0xFE && !decodedByOrder.isEmpty()) {
            int refIdx = Math.min(deltaBytes[1] & 0xFF, decodedByOrder.size() - 1);
            long[] src = decodedByOrder.get(refIdx);
            return Arrays.copyOf(src, src.length);
        }
        if (deltaBytes.length >= 3 && (deltaBytes[0] & 0xFF) == 0xFD && !decodedByOrder.isEmpty()) {
            int refIdx = Math.min(deltaBytes[1] & 0xFF, decodedByOrder.size() - 1);
            return decodeCrossDeltaHeightmapLongs(decodedByOrder.get(refIdx), deltaBytes, 2);
        }
        return decodeDeltaHeightmapLongs(deltaBytes, 0, deltaBytes.length);
    }

    static long[] decodeCrossDeltaHeightmapLongs(long[] baseLongs, byte[] deltaBytes, int startPos)
            throws CorruptRegionEntryException {
        return decodeCrossDeltaHeightmapLongs(baseLongs, deltaBytes, startPos, deltaBytes.length - startPos);
    }

    static long[] decodeCrossDeltaHeightmapLongs(long[] baseLongs, byte[] deltaBytes, int startPos, int length)
            throws CorruptRegionEntryException {
        int limitPos = startPos + length;
        int numLongs = deltaBytes[startPos] & 0xFF;
        int pos = startPos + 1;
        long[] out = new long[numLongs];
        int bits = 9;
        int entriesPerLong = 64 / bits;
        long mask = (1L << bits) - 1L;
        int[] cols = scratch().heightmapCols;
        int c = 0;
        while (c < 256 && pos < limitPos) {
            int tok = deltaBytes[pos++] & 0xFF;
            if ((tok & 0x80) != 0) {
                int zeroRun = (tok & 0x7F) + 1;
                for (int k = 0; k < zeroRun && c < 256; k++, c++) {
                    int w = c / entriesPerLong;
                    int s = c % entriesPerLong;
                    cols[c] = (int) ((baseLongs[w] >>> (s * bits)) & mask);
                }
            } else {
                int zz = tok;
                if ((tok & 0x40) != 0) {
                    int extra = readVarInt(deltaBytes, pos, limitPos);
                    pos += varIntLen(extra);
                    zz = (tok & 0x3F) | (extra << 6);
                }
                int delta = (zz >>> 1) ^ -(zz & 1);
                int w = c / entriesPerLong;
                int s = c % entriesPerLong;
                int baseVal = (int) ((baseLongs[w] >>> (s * bits)) & mask);
                cols[c] = (baseVal + delta) & 0x1FF;
                c++;
            }
        }
        int col = 0;
        for (int w = 0; w < numLongs; w++) {
            long word = 0L;
            int limit = Math.min(entriesPerLong, 256 - col);
            for (int s = 0; s < limit; s++) {
                word |= ((long) cols[col++]) << (s * bits);
            }
            out[w] = word;
        }
        return out;
    }

    static long[] decodeDeltaHeightmapLongs(byte[] deltaBytes, int offset, int length) throws CorruptRegionEntryException {
        if (length < 2) {
            return new long[0];
        }
        int limitPos = offset + length;
        int numLongs = deltaBytes[offset] & 0xFF;
        int pos = offset + 1;
        long[] out = new long[numLongs];
        int[] cols = scratch().heightmapCols;
        int prev = 0;
        int c = 0;
        while (c < 256 && pos < limitPos) {
            int tok = deltaBytes[pos++] & 0xFF;
            if ((tok & 0x80) != 0) {
                int zeroRun = (tok & 0x7F) + 1;
                for (int k = 0; k < zeroRun && c < 256; k++, c++) {
                    cols[c] = prev;
                }
            } else {
                int zz = tok;
                if ((tok & 0x40) != 0) {
                    int extra = readVarInt(deltaBytes, pos, limitPos);
                    pos += varIntLen(extra);
                    zz = (tok & 0x3F) | (extra << 6);
                }
                int delta = (zz >>> 1) ^ -(zz & 1);
                prev = (prev + delta) & 0x1FF;
                cols[c++] = prev;
            }
        }
        int bits = 9;
        int entriesPerLong = 64 / bits;
        int col = 0;
        for (int w = 0; w < numLongs; w++) {
            long word = 0L;
            int limit = Math.min(entriesPerLong, 256 - col);
            for (int s = 0; s < limit; s++) {
                word |= ((long) cols[col++]) << (s * bits);
            }
            out[w] = word;
        }
        return out;
    }

    static final class ParsedHeaderAndDict {
        final int storageKind;
        final int regionX;
        final int regionZ;
        final int minSectionY;
        final int sectionCount;
        final int defaultDataVersion;
        final int flags;
        final long slabPoolBaseOffset;
        final List<String> canonicalStates;
        final List<String> baseBlockNames;
        final List<List<String>> singletonBasePalettes;
        final List<String> biomes;
        final List<List<String>> singletonBiomePalettes;
        private volatile byte[][] stateNbtBytes;
        private volatile byte[][] biomeStringNbtBytes;
        private volatile byte[][] singleBiomeCompoundNbtBytes;

        ParsedHeaderAndDict(
                int storageKind,
                int regionX,
                int regionZ,
                int minSectionY,
                int sectionCount,
                int defaultDataVersion,
                int flags,
                long slabPoolBaseOffset,
                List<String> canonicalStates,
                List<String> baseBlockNames,
                List<List<String>> singletonBasePalettes,
                List<String> biomes,
                List<List<String>> singletonBiomePalettes
        ) {
            this.storageKind = storageKind;
            this.regionX = regionX;
            this.regionZ = regionZ;
            this.minSectionY = minSectionY;
            this.sectionCount = sectionCount;
            this.defaultDataVersion = defaultDataVersion;
            this.flags = flags;
            this.slabPoolBaseOffset = slabPoolBaseOffset;
            this.canonicalStates = canonicalStates;
            this.baseBlockNames = baseBlockNames;
            this.singletonBasePalettes = singletonBasePalettes;
            this.biomes = biomes;
            this.singletonBiomePalettes = singletonBiomePalettes;
        }

        byte[][] ensureStateNbtBytes() {
            byte[][] cur = this.stateNbtBytes;
            if (cur != null) {
                return cur;
            }
            byte[][] built = new byte[canonicalStates.size()][];
            this.stateNbtBytes = built;
            return built;
        }

        byte[] stateNbtBytesAt(int globalId) {
            byte[][] arr = ensureStateNbtBytes();
            byte[] b = arr[globalId];
            if (b == null) {
                b = encodeCanonicalStateToNbtCompoundBytes(canonicalStates.get(globalId));
                arr[globalId] = b;
            }
            return b;
        }

        byte[][] ensureAllStateNbtBytes() {
            byte[][] arr = ensureStateNbtBytes();
            for (int i = 0; i < arr.length; i++) {
                if (arr[i] == null) {
                    arr[i] = encodeCanonicalStateToNbtCompoundBytes(canonicalStates.get(i));
                }
            }
            return arr;
        }

        byte[][] ensureBiomeStringNbtBytes() {
            byte[][] cur = this.biomeStringNbtBytes;
            if (cur != null) {
                return cur;
            }
            int n = biomes.size();
            byte[][] builtStr = new byte[n][];
            byte[][] builtSingle = new byte[n][];
            for (int i = 0; i < n; i++) {
                byte[] s = encodeNbtStringPayloadBytes(biomes.get(i));
                builtStr[i] = s;
                builtSingle[i] = encodeSingleBiomeCompoundNbtBytes(s);
            }
            this.singleBiomeCompoundNbtBytes = builtSingle;
            this.biomeStringNbtBytes = builtStr;
            return builtStr;
        }

        byte[][] ensureSingleBiomeCompoundNbtBytes() {
            byte[][] cur = this.singleBiomeCompoundNbtBytes;
            if (cur != null) {
                return cur;
            }
            ensureBiomeStringNbtBytes();
            return this.singleBiomeCompoundNbtBytes;
        }
    }

    static final class BiomeTemplate {
        final int[] biomeIds;
        final long[] rawBiomeDataLongs;
        final List<String> palette;
        final long[] packedBiomeLongs;

        BiomeTemplate(int[] biomeIds, long[] rawBiomeDataLongs, List<String> palette, long[] packedBiomeLongs) {
            this.biomeIds = biomeIds;
            this.rawBiomeDataLongs = rawBiomeDataLongs;
            this.palette = palette;
            this.packedBiomeLongs = packedBiomeLongs;
        }

        boolean matches(int[] otherIds, long[] otherData) {
            return Arrays.equals(this.biomeIds, otherIds) && Arrays.equals(this.rawBiomeDataLongs, otherData);
        }
    }

    /**
     * Encodes a chunk's Slab-Local Micro-Palette (`anvil-api-ADR-003` Section 4.2):
     * Part A stores 1-byte static core tokens (`0x80 | coreId`) for base block/biome names plus
     * chunk-level $4 \times 4 \times 4$ biome grid templates; Part B stores 1-byte static property
     * suffix tokens (`0x80 | propId`), 1-2 byte region dictionary IDs (`0x40` + VarInt), or raw
     * UTF-8 fallback (`0x00`).
     */
    static byte[] encodeSlabLocalPalette(
            List<String> localStates,
            int[] localStateGlobalIds,
            List<String> localBiomes,
            List<BiomeTemplate> biomeTemplates
    ) {
        ScratchArena arena = scratch();
        byte[] buf = arena.byteStaging;
        int pos = writeSlabLocalEntryList(buf, 0, localStates, STATIC_CORE_STATE_TO_ID, true);
        pos = writeSlabLocalEntryList(buf, pos, localBiomes, STATIC_CORE_BIOME_TO_ID, false);

        int tmplCount = biomeTemplates == null ? 0 : biomeTemplates.size();
        pos = writeVarIntToBuf(buf, pos, tmplCount);
        for (int t = 0; t < tmplCount; t++) {
            BiomeTemplate tmpl = biomeTemplates.get(t);
            pos = encodeBiomesToBuf(buf, pos, tmpl.biomeIds, tmpl.rawBiomeDataLongs, -1, arena);
        }

        int propStateCount = 0;
        for (int i = 0; i < localStates.size(); i++) {
            if (localStates.get(i).indexOf('[') >= 0) propStateCount++;
        }
        // Part B: property suffixes only for states that actually have '[...]' (skipped in O(1) by readChunkView!)
        pos = writeVarIntToBuf(buf, pos, propStateCount);
        for (int i = 0; i < localStates.size(); i++) {
            String s = localStates.get(i);
            int bracket = s.indexOf('[');
            if (bracket >= 0) {
                pos = writeVarIntToBuf(buf, pos, i);
                String propSuffix = s.substring(bracket);
                Integer propId = STATIC_CORE_PROP_TO_ID.get(propSuffix);
                if (propId != null) {
                    buf[pos++] = (byte) (0x80 | (propId & 0x7F));
                } else {
                    buf[pos++] = 0x40;
                    pos = writeVarIntToBuf(buf, pos, localStateGlobalIds[i]);
                }
            }
        }
        return Arrays.copyOf(buf, pos);
    }

    private static int writeSlabLocalEntryList(
            byte[] buf, int pos, List<String> entries, Map<String, Integer> coreMap, boolean stripProps
    ) {
        pos = writeVarIntToBuf(buf, pos, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            String s = entries.get(i);
            int bracket = stripProps ? s.indexOf('[') : -1;
            String base = bracket >= 0 ? s.substring(0, bracket) : s;
            Integer coreId = coreMap.get(base);
            if (coreId != null && coreId < 128) {
                buf[pos++] = (byte) (0x80 | coreId);
            } else if (coreId != null && coreId < 160) {
                buf[pos++] = (byte) (0x60 | (coreId - 128));
            } else {
                boolean mc = base.startsWith("minecraft:");
                byte[] utf = (mc ? base.substring(10) : base).getBytes(StandardCharsets.UTF_8);
                buf[pos++] = (byte) (mc ? 0x40 : 0x00);
                pos = writeVarIntToBuf(buf, pos, utf.length);
                System.arraycopy(utf, 0, buf, pos, utf.length);
                pos += utf.length;
            }
        }
        return pos;
    }

    static final class SlabLocalPalette {
        final List<String> canonicalStates;
        final List<String> baseBlockNames;
        final List<List<String>> singletonBasePalettes;
        final List<String> biomes;
        final List<List<String>> singletonBiomePalettes;
        final List<BiomeTemplate> biomeTemplates;

        SlabLocalPalette(
                List<String> canonicalStates,
                List<String> baseBlockNames,
                List<List<String>> singletonBasePalettes,
                List<String> biomes,
                List<List<String>> singletonBiomePalettes,
                List<BiomeTemplate> biomeTemplates
        ) {
            this.canonicalStates = canonicalStates;
            this.baseBlockNames = baseBlockNames;
            this.singletonBasePalettes = singletonBasePalettes;
            this.biomes = biomes;
            this.singletonBiomePalettes = singletonBiomePalettes;
            this.biomeTemplates = biomeTemplates;
        }
    }

    static SlabLocalPalette decodeSlabLocalPalette(
            byte[] frame,
            int fOff,
            int startRel,
            int limitRel,
            boolean needCanonicalStates,
            boolean needBiomes,
            List<String> regionCanonicalStates
    ) throws CorruptRegionEntryException {
        int pos = fOff + startRel;
        int limit = fOff + limitRel;
        int stateCount = readVarInt(frame, pos, limit);
        pos += varIntLen(stateCount);
        List<String> baseNames = new ArrayList<>(stateCount);
        List<List<String>> singletons = new ArrayList<>(stateCount);
        pos = readSlabLocalEntryList(
                frame, pos, limit, stateCount, STATIC_CORE_BASE_NAMES, STATIC_CORE_SINGLETON_PALETTES, baseNames, singletons);

        int biomeCount = readVarInt(frame, pos, limit);
        pos += varIntLen(biomeCount);
        List<String> biomes = new ArrayList<>(biomeCount);
        List<List<String>> singletonBiomes = new ArrayList<>(biomeCount);
        pos = readSlabLocalEntryList(
                frame, pos, limit, biomeCount, STATIC_CORE_BIOMES, STATIC_CORE_SINGLETON_BIOMES, biomes, singletonBiomes);

        int tmplCount = readVarInt(frame, pos, limit);
        pos += varIntLen(tmplCount);
        List<BiomeTemplate> biomeTemplates = (tmplCount > 0 && needBiomes)
                ? new ArrayList<>(tmplCount)
                : Collections.emptyList();
        ScratchArena arena = scratch();
        for (int t = 0; t < tmplCount; t++) {
            int bLen = readVarInt(frame, pos, limit);
            pos += varIntLen(bLen);
            List<String> tmplPal = needBiomes ? new ArrayList<>(bLen) : Collections.emptyList();
            pos = decodeSectionBiomePayload(frame, pos, limit, bLen, biomes, !needBiomes, tmplPal, arena);
            if (needBiomes) {
                long[] packedLongs = bLen > 1 ? packBiomeLongs(arena.biomeCells, bLen) : null;
                biomeTemplates.add(new BiomeTemplate(
                        null, null,
                        bLen == 1 && !tmplPal.isEmpty() ? List.of(tmplPal.get(0)) : Collections.unmodifiableList(tmplPal),
                        packedLongs));
            }
        }

        if (!needCanonicalStates) {
            return new SlabLocalPalette(Collections.emptyList(), baseNames, singletons, biomes, singletonBiomes, biomeTemplates);
        }

        List<String> canonicalStates = new ArrayList<>(baseNames);
        int propCount = readVarInt(frame, pos, limit);
        pos += varIntLen(propCount);
        for (int k = 0; k < propCount; k++) {
            int stateIdx = readVarInt(frame, pos, limit);
            pos += varIntLen(stateIdx);
            if (stateIdx >= stateCount || pos >= limit) {
                throw new CorruptRegionEntryException("CBIT T5: Invalid slab-local property suffix index");
            }
            int tag = frame[pos++] & 0xFF;
            if ((tag & 0x80) != 0) {
                int propId = tag & 0x7F;
                if (propId >= STATIC_CORE_PROP_SUFFIXES.length) {
                    throw new CorruptRegionEntryException("CBIT T5: Invalid static property suffix token " + propId);
                }
                canonicalStates.set(stateIdx, baseNames.get(stateIdx) + STATIC_CORE_PROP_SUFFIXES[propId]);
            } else {
                int globalId = readVarInt(frame, pos, limit);
                pos += varIntLen(globalId);
                if (regionCanonicalStates == null || globalId < 0 || globalId >= regionCanonicalStates.size()) {
                    throw new CorruptRegionEntryException("CBIT T7: Slab-local globalStateId " + globalId + " out of range");
                }
                canonicalStates.set(stateIdx, regionCanonicalStates.get(globalId));
            }
        }
        return new SlabLocalPalette(canonicalStates, baseNames, singletons, biomes, singletonBiomes, biomeTemplates);
    }

    private static int readSlabLocalEntryList(
            byte[] frame,
            int pos,
            int limit,
            int count,
            String[] coreNames,
            List<String>[] coreSingletons,
            List<String> namesOut,
            List<List<String>> singletonsOut
    ) throws CorruptRegionEntryException {
        for (int i = 0; i < count; i++) {
            if (pos >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated slab-local palette");
            int tag = frame[pos++] & 0xFF;
            if ((tag & 0x80) != 0 || (tag >= 0x60 && tag < 0x80)) {
                int coreId = ((tag & 0x80) != 0) ? (tag & 0x7F) : (128 + (tag & 0x1F));
                if (coreId >= coreNames.length) {
                    throw new CorruptRegionEntryException("CBIT T5: Invalid static core token " + coreId);
                }
                namesOut.add(coreNames[coreId]);
                singletonsOut.add(coreSingletons[coreId]);
            } else {
                int utfLen = readVarInt(frame, pos, limit);
                pos += varIntLen(utfLen);
                if (pos + utfLen > limit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated slab-local string");
                }
                String raw = new String(frame, pos, utfLen, StandardCharsets.UTF_8);
                pos += utfLen;
                String full = (tag == 0x40) ? ("minecraft:" + raw) : raw;
                namesOut.add(full);
                singletonsOut.add(List.of(full));
            }
        }
        return pos;
    }

    static final class SectionPatch {
        final int sectionIndex;
        final int patchFlags;
        final int replacementSectionEntry16;
        final int[] yzxCoords;
        final int[] dictIds;
        final byte[] skyLight2048;
        final byte[] blockLight2048;

        SectionPatch(
                int sectionIndex,
                int patchFlags,
                int replacementSectionEntry16,
                int[] yzxCoords,
                int[] dictIds,
                byte[] skyLight2048,
                byte[] blockLight2048
        ) {
            this.sectionIndex = sectionIndex;
            this.patchFlags = patchFlags;
            this.replacementSectionEntry16 = replacementSectionEntry16;
            this.yzxCoords = yzxCoords;
            this.dictIds = dictIds;
            this.skyLight2048 = skyLight2048;
            this.blockLight2048 = blockLight2048;
        }
    }

    static final class ChunkPatchTail {
        final SectionPatch[] sectionPatchesByIndex;
        final int patchedSectionCount;

        ChunkPatchTail(SectionPatch[] sectionPatchesByIndex, int patchedSectionCount) {
            this.sectionPatchesByIndex = sectionPatchesByIndex;
            this.patchedSectionCount = patchedSectionCount;
        }

        SectionPatch patchForSection(int secIdx) {
            return (secIdx >= 0 && secIdx < sectionPatchesByIndex.length) ? sectionPatchesByIndex[secIdx] : null;
        }

        boolean hasPatchForSection(int secIdx) {
            return patchForSection(secIdx) != null;
        }
    }

    /**
     * Encodes a `ChunkPatchTail` payload (`LCA-ICD-1.0` Section 8.3, `anvil-api-ADR-003` Section 4.3)
     * including the trailing 1-byte Extended Hamming `(72, 64)` `patchParity8` syndrome.
     */
    static byte[] encodeChunkPatchTailBytes(
            int baseLocalStateCount,
            List<String> appendedStates,
            int[] appendedGlobalStateIds,
            List<SectionPatch> sectionPatches
    ) {
        ScratchArena arena = scratch();
        byte[] buf = arena.byteStaging;
        int pos = 0;

        List<String> appStates = appendedStates == null ? Collections.emptyList() : appendedStates;
        int appCount = appStates.size();
        if (appCount == 0) {
            pos = writeVarIntToBuf(buf, pos, 0);
        } else {
            pos = writeSlabLocalEntryList(buf, pos, appStates, STATIC_CORE_STATE_TO_ID, true);
            int propCount = 0;
            for (int i = 0; i < appCount; i++) {
                if (appStates.get(i).indexOf('[') >= 0) {
                    propCount++;
                }
            }
            pos = writeVarIntToBuf(buf, pos, propCount);
            for (int i = 0; i < appCount; i++) {
                String s = appStates.get(i);
                int bracket = s.indexOf('[');
                if (bracket >= 0) {
                    pos = writeVarIntToBuf(buf, pos, baseLocalStateCount + i);
                    String propSuffix = s.substring(bracket);
                    Integer propId = STATIC_CORE_PROP_TO_ID.get(propSuffix);
                    if (propId != null) {
                        buf[pos++] = (byte) (0x80 | (propId & 0x7F));
                    } else {
                        buf[pos++] = 0x40;
                        int gid = (appendedGlobalStateIds != null && i < appendedGlobalStateIds.length)
                                ? appendedGlobalStateIds[i]
                                : 0;
                        pos = writeVarIntToBuf(buf, pos, gid);
                    }
                }
            }
        }

        List<SectionPatch> patches = sectionPatches == null ? Collections.emptyList() : sectionPatches;
        pos = writeVarIntToBuf(buf, pos, patches.size());
        for (int s = 0; s < patches.size(); s++) {
            SectionPatch sp = patches.get(s);
            buf[pos++] = (byte) (sp.sectionIndex & 0xFF);
            buf[pos++] = (byte) (sp.patchFlags & 0xFF);
            if ((sp.patchFlags & PATCH_FLAG_REPLACEMENT_OFFSET) != 0) {
                buf[pos++] = (byte) ((sp.replacementSectionEntry16 >>> 8) & 0xFF);
                buf[pos++] = (byte) (sp.replacementSectionEntry16 & 0xFF);
            }
            if ((sp.patchFlags & PATCH_FLAG_VOXEL_DELTAS) != 0) {
                int dCount = sp.yzxCoords.length;
                pos = writeVarIntToBuf(buf, pos, dCount);
                for (int d = 0; d < dCount; d++) {
                    int yzx = sp.yzxCoords[d] & 0x0FFF;
                    buf[pos++] = (byte) ((yzx >>> 8) & 0xFF);
                    buf[pos++] = (byte) (yzx & 0xFF);
                    pos = writeVarIntToBuf(buf, pos, sp.dictIds[d]);
                }
            }
            if ((sp.patchFlags & PATCH_FLAG_SKY_LIGHT) != 0) {
                pos = encodePatchLightChannelToBuf(sp.skyLight2048, CURVE_TO_YZX[CURVE_Y_H2], arena, buf, pos);
            }
            if ((sp.patchFlags & PATCH_FLAG_BLOCK_LIGHT) != 0) {
                pos = encodePatchLightChannelToBuf(sp.blockLight2048, CURVE_TO_YZX[CURVE_H3], arena, buf, pos);
            }
        }

        int parity8 = computePatchParity8(buf, 0, pos);
        buf[pos++] = (byte) parity8;
        return Arrays.copyOf(buf, pos);
    }

    private static int encodePatchLightChannelToBuf(
            byte[] light2048, int[] curveToYzx, ScratchArena arena, byte[] dst, int pos
    ) {
        int constNib = extractLightNibblesFast(light2048, arena.skyLightNibbles, 0);
        if (constNib >= 0) {
            dst[pos++] = (byte) LIGHT_MODE_CONST;
            dst[pos++] = (byte) (constNib & 0x0F);
            return pos;
        }
        int modeBytePos = pos++;
        int grayEnd = encodeGrayLightPlanesToBuf(arena.skyLightNibbles, curveToYzx, arena, dst, pos);
        if (grayEnd - pos < 2048) {
            dst[modeBytePos] = (byte) LIGHT_MODE_GRAY_PLANES;
            return grayEnd;
        }
        dst[modeBytePos] = (byte) LIGHT_MODE_RAW;
        System.arraycopy(light2048, 0, dst, pos, 2048);
        return pos + 2048;
    }

    private static int decodePatchLightChannel(
            byte[] buf, int pos, int limit, int[] curveToYzx, boolean skipLighting, byte[][] outRef, ScratchArena arena
    ) throws CorruptRegionEntryException {
        if (pos >= limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated light patch mode byte");
        }
        int mode = buf[pos++] & 0xFF;
        if (mode == LIGHT_MODE_CONST) {
            if (pos >= limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated const light patch nibble");
            }
            int c = buf[pos++] & 0x0F;
            if (!skipLighting) {
                byte[] arr = new byte[2048];
                if (c != 0) {
                    Arrays.fill(arr, (byte) ((c << 4) | c));
                }
                outRef[0] = arr;
            }
            return pos;
        } else if (mode == LIGHT_MODE_GRAY_PLANES) {
            pos = decodeGrayPlanes(buf, pos, limit, curveToYzx, arena);
            if (!skipLighting) {
                outRef[0] = packLightNibbles(arena.skyLightNibbles);
            }
            return pos;
        } else if (mode == LIGHT_MODE_RAW) {
            if (pos + 2048 > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated raw light patch payload");
            }
            if (!skipLighting) {
                outRef[0] = Arrays.copyOfRange(buf, pos, pos + 2048);
            }
            return pos + 2048;
        }
        throw new CorruptRegionEntryException("CBIT T5: Invalid light patch mode " + mode);
    }

    /**
     * Locates and decodes `HAS_CHUNK_PATCH_TAIL (0x4000)` inside the Residual Tail before section
     * evaluation (`LCA-ICD-1.0` Section 8.3). Returns `null` in $O(1)$ (`~1 ns`, `0` allocations)
     * when `(boilerplateFlags16 & 0x4000) == 0`.
     */
    static ChunkPatchTail locateAndDecodeChunkPatchTail(
            byte[] frame,
            int fOff,
            int frameLen,
            int residualRelStart,
            int residualRelEnd,
            int sectionCount,
            int sectionDirEnd,
            boolean scale4B,
            boolean hasSlabLocalPalette,
            SlabLocalPalette localPal,
            List<String> regionCanonicalStates,
            boolean needCanonicalStates,
            boolean skipLighting,
            int[] sectionEntries,
            int[] sectionRelStarts
    ) throws CorruptRegionEntryException {
        if (residualRelStart + 2 > residualRelEnd || residualRelEnd > frameLen) {
            return null;
        }
        int pos = fOff + residualRelStart;
        int limit = fOff + residualRelEnd;
        int bpFlags = readUint16BE(frame, pos);
        if ((bpFlags & BP_FLAG_HAS_CHUNK_PATCH_TAIL) == 0) {
            return null;
        }
        pos += 2;
        if ((bpFlags & 8192) != 0) {
            int mbnlLen = readVarInt(frame, pos, limit);
            pos += varIntLen(mbnlLen);
            if (mbnlLen < 0 || pos + mbnlLen > limit) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated MOTION_BLOCKING_NO_LEAVES prefix");
            }
            pos += mbnlLen;
        }
        if ((bpFlags & 32) != 0) {
            if (pos >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated ppEmptyCount");
            pos++;
        }
        if ((bpFlags & 64) != 0) {
            int slVer = readVarInt(frame, pos, limit);
            pos += varIntLen(slVer);
        }
        if ((bpFlags & 128) != 0) {
            if (pos >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated lowBoundaryFlags");
            pos++;
        }
        if ((bpFlags & 256) != 0) {
            if (pos >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated highBoundaryFlags");
            pos++;
        }
        if ((bpFlags & 512) != 0) {
            if (pos >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated secHmCount");
            int secHmCount = frame[pos++] & 0xFF;
            for (int h = 0; h < secHmCount; h++) {
                if (pos >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated secondary heightmap");
                int code = frame[pos++] & 0xFF;
                if (code >= STANDARD_HEIGHTMAP_NAMES.length) {
                    int kLen = readVarInt(frame, pos, limit);
                    pos += varIntLen(kLen) + kLen;
                }
                int payLen = readVarInt(frame, pos, limit);
                pos += varIntLen(payLen) + payLen;
                if (pos > limit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated secondary heightmap payload");
                }
            }
        }

        int patchTailByteLen = readVarInt(frame, pos, limit);
        pos += varIntLen(patchTailByteLen);
        if (patchTailByteLen < 3 || pos + patchTailByteLen > limit) {
            throw new CorruptRegionEntryException("CBIT T5: Truncated chunkPatchTail payload (len=" + patchTailByteLen + ")");
        }
        int payloadEnd = pos + patchTailByteLen - 1;
        int storedParity8 = frame[payloadEnd] & 0xFF;
        int expectedParity8 = computePatchParity8(frame, pos, patchTailByteLen - 1);
        if (storedParity8 != expectedParity8) {
            throw new CorruptRegionEntryException("CBIT T6b: ChunkPatchTail parity syndrome mismatch (expected=0x"
                    + Integer.toHexString(expectedParity8) + " actual=0x" + Integer.toHexString(storedParity8) + ")");
        }

        int pPos = pos;
        int appendedStateCount = readVarInt(frame, pPos, payloadEnd);
        pPos += varIntLen(appendedStateCount);
        if (appendedStateCount < 0 || appendedStateCount > SECTION_VOXELS) {
            throw new CorruptRegionEntryException("CBIT T5: Invalid appendedStateCount " + appendedStateCount);
        }
        if (appendedStateCount > 0) {
            if (!hasSlabLocalPalette || localPal == null) {
                throw new CorruptRegionEntryException("CBIT T5: Appended micro-palette entries require hasSlabLocalPalette");
            }
            int baseCount = localPal.baseBlockNames.size();
            pPos = readSlabLocalEntryList(
                    frame, pPos, payloadEnd, appendedStateCount,
                    STATIC_CORE_BASE_NAMES, STATIC_CORE_SINGLETON_PALETTES,
                    localPal.baseBlockNames, localPal.singletonBasePalettes);
            if (needCanonicalStates) {
                for (int i = 0; i < appendedStateCount; i++) {
                    localPal.canonicalStates.add(localPal.baseBlockNames.get(baseCount + i));
                }
            }
            int appendedPropCount = readVarInt(frame, pPos, payloadEnd);
            pPos += varIntLen(appendedPropCount);
            if (appendedPropCount < 0 || appendedPropCount > appendedStateCount) {
                throw new CorruptRegionEntryException("CBIT T5: Invalid appendedPropCount " + appendedPropCount);
            }
            for (int k = 0; k < appendedPropCount; k++) {
                int stateIdx = readVarInt(frame, pPos, payloadEnd);
                pPos += varIntLen(stateIdx);
                if (stateIdx < baseCount || stateIdx >= baseCount + appendedStateCount || pPos >= payloadEnd) {
                    throw new CorruptRegionEntryException("CBIT T5: Appended property suffix index out of range: " + stateIdx);
                }
                int propTag = frame[pPos++] & 0xFF;
                if ((propTag & 0x80) != 0) {
                    int propId = propTag & 0x7F;
                    if (propId >= STATIC_CORE_PROP_SUFFIXES.length) {
                        throw new CorruptRegionEntryException("CBIT T5: Invalid appended static property suffix " + propId);
                    }
                    if (needCanonicalStates) {
                        localPal.canonicalStates.set(
                                stateIdx, localPal.baseBlockNames.get(stateIdx) + STATIC_CORE_PROP_SUFFIXES[propId]);
                    }
                } else if (propTag == 0x40) {
                    int globalId = readVarInt(frame, pPos, payloadEnd);
                    pPos += varIntLen(globalId);
                    if (needCanonicalStates) {
                        if (regionCanonicalStates == null || globalId < 0 || globalId >= regionCanonicalStates.size()) {
                            throw new CorruptRegionEntryException("CBIT T7: Appended globalStateId " + globalId + " out of range");
                        }
                        localPal.canonicalStates.set(stateIdx, regionCanonicalStates.get(globalId));
                    }
                } else {
                    throw new CorruptRegionEntryException("CBIT T5: Invalid appended property tag 0x" + Integer.toHexString(propTag));
                }
            }
        }

        int patchedSecCount = readVarInt(frame, pPos, payloadEnd);
        pPos += varIntLen(patchedSecCount);
        if (patchedSecCount < 0 || patchedSecCount > sectionCount) {
            throw new CorruptRegionEntryException("CBIT T5: Invalid patchedSectionCount " + patchedSecCount);
        }
        SectionPatch[] byIndex = new SectionPatch[sectionCount];
        int unitShift = scale4B ? 2 : 0;
        ScratchArena arena = scratch();
        byte[][] lightRef = new byte[1][];

        for (int s = 0; s < patchedSecCount; s++) {
            if (pPos + 2 > payloadEnd) {
                throw new CorruptRegionEntryException("CBIT T5: Truncated SectionPatch header");
            }
            int secIdx = frame[pPos++] & 0xFF;
            int patchFlags = frame[pPos++] & 0xFF;
            if (secIdx >= sectionCount) {
                throw new CorruptRegionEntryException("CBIT T5: SectionPatch sectionIndex " + secIdx + " >= " + sectionCount);
            }
            if ((patchFlags & ~0x0F) != 0) {
                throw new CorruptRegionEntryException("CBIT T5: Unsupported SectionPatch flags 0x" + Integer.toHexString(patchFlags));
            }
            int replEntry16 = 0;
            if ((patchFlags & PATCH_FLAG_REPLACEMENT_OFFSET) != 0) {
                if (pPos + 2 > payloadEnd) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated replacementSectionEntry16");
                }
                replEntry16 = readUint16BE(frame, pPos);
                pPos += 2;
                sectionEntries[secIdx] = replEntry16;
                if (replEntry16 != 0 && (replEntry16 & 0x8000) == 0) {
                    int relPos = sectionDirEnd + ((replEntry16 - 1) << unitShift);
                    if (relPos < sectionDirEnd || relPos >= frameLen || relPos == residualRelStart) {
                        throw new CorruptRegionEntryException("CBIT T5: Replacement sectionOffset[" + secIdx + "] out of bounds: " + relPos);
                    }
                    for (int k = 0; k < sectionCount; k++) {
                        if (k != secIdx && sectionRelStarts[k] == relPos) {
                            throw new CorruptRegionEntryException("CBIT T5: Duplicate replacement sectionOffset at " + secIdx);
                        }
                    }
                    sectionRelStarts[secIdx] = relPos;
                } else {
                    sectionRelStarts[secIdx] = -1;
                }
            }
            int[] yzxCoords = null;
            int[] dictIds = null;
            if ((patchFlags & PATCH_FLAG_VOXEL_DELTAS) != 0) {
                int deltaCount = readVarInt(frame, pPos, payloadEnd);
                pPos += varIntLen(deltaCount);
                if (deltaCount < 1 || deltaCount > SECTION_VOXELS) {
                    throw new CorruptRegionEntryException("CBIT T6: Invalid voxel deltaCount " + deltaCount);
                }
                yzxCoords = new int[deltaCount];
                dictIds = new int[deltaCount];
                for (int d = 0; d < deltaCount; d++) {
                    if (pPos + 2 > payloadEnd) {
                        throw new CorruptRegionEntryException("CBIT T6: Truncated voxel delta coordinate");
                    }
                    int yzx12 = readUint16BE(frame, pPos);
                    pPos += 2;
                    if ((yzx12 & ~0x0FFF) != 0) {
                        throw new CorruptRegionEntryException("CBIT T6: Voxel delta coordinate out of bounds: " + yzx12);
                    }
                    int dictId = readVarInt(frame, pPos, payloadEnd);
                    pPos += varIntLen(dictId);
                    yzxCoords[d] = yzx12;
                    dictIds[d] = dictId;
                }
            }
            byte[] skyPatch2048 = null;
            if ((patchFlags & PATCH_FLAG_SKY_LIGHT) != 0) {
                lightRef[0] = null;
                pPos = decodePatchLightChannel(frame, pPos, payloadEnd, CURVE_TO_YZX[CURVE_Y_H2], skipLighting, lightRef, arena);
                skyPatch2048 = lightRef[0];
            }
            byte[] blkPatch2048 = null;
            if ((patchFlags & PATCH_FLAG_BLOCK_LIGHT) != 0) {
                lightRef[0] = null;
                pPos = decodePatchLightChannel(frame, pPos, payloadEnd, CURVE_TO_YZX[CURVE_H3], skipLighting, lightRef, arena);
                blkPatch2048 = lightRef[0];
            }
            if ((patchFlags & (PATCH_FLAG_VOXEL_DELTAS | PATCH_FLAG_SKY_LIGHT | PATCH_FLAG_BLOCK_LIGHT)) != 0) {
                byIndex[secIdx] = new SectionPatch(
                        secIdx, patchFlags, replEntry16, yzxCoords, dictIds, skyPatch2048, blkPatch2048);
            }
        }
        if (pPos != payloadEnd) {
            throw new CorruptRegionEntryException("CBIT T5: ChunkPatchTail cursor mismatch (" + pPos + " != " + payloadEnd + ")");
        }
        return new ChunkPatchTail(byIndex, patchedSecCount);
    }

    static DecodedSection createUniformDecodedSection(
            int sectionY,
            int entry16,
            boolean hasSlabLocalPalette,
            List<String> dictCanonicalStates,
            List<String> dictBaseNames,
            List<String> dictBiomes,
            List<BiomeTemplate> biomeTemplates,
            int defaultBiomeDictId,
            boolean skipLighting,
            boolean skipBiomes
    ) throws CorruptRegionEntryException {
        String canonState = "minecraft:air";
        String baseName = "minecraft:air";
        List<String> biomePal = Collections.emptyList();
        long[] packedBiomeLongs = null;
        byte[] sky2048 = null;
        byte[] blk2048 = null;

        if (entry16 != 0 && (entry16 & 0x8000) != 0) {
            int skyLight2 = (entry16 >>> 13) & 3;
            int globalDictId = hasSlabLocalPalette ? (entry16 & 0x07FF) : (entry16 & 0x1FFF);
            if (globalDictId >= dictBaseNames.size()) {
                throw new CorruptRegionEntryException("CBIT T7: Inline globalDictId out of bounds: " + globalDictId);
            }
            baseName = dictBaseNames.get(globalDictId);
            if (!skipLighting) {
                if (globalDictId >= dictCanonicalStates.size()) {
                    throw new CorruptRegionEntryException("CBIT T7: Inline globalDictId out of bounds: " + globalDictId);
                }
                canonState = dictCanonicalStates.get(globalDictId);
                if (skyLight2 == 0b01 || skyLight2 == 0b10) {
                    sky2048 = new byte[2048];
                    Arrays.fill(sky2048, (byte) 0xFF);
                }
                if (skyLight2 == 0b10 || skyLight2 == 0b11) {
                    blk2048 = new byte[2048];
                    Arrays.fill(blk2048, (byte) 0xFF);
                }
            }
            if (!skipBiomes) {
                if (hasSlabLocalPalette && biomeTemplates != null && !biomeTemplates.isEmpty()) {
                    int biomeTmplIdx = (entry16 >>> 11) & 3;
                    if (biomeTmplIdx < biomeTemplates.size()) {
                        BiomeTemplate tmpl = biomeTemplates.get(biomeTmplIdx);
                        biomePal = tmpl.palette;
                        packedBiomeLongs = tmpl.packedBiomeLongs;
                    }
                } else if (defaultBiomeDictId >= 0 && defaultBiomeDictId < dictBiomes.size()) {
                    biomePal = List.of(dictBiomes.get(defaultBiomeDictId));
                }
            }
        } else if (!skipBiomes && defaultBiomeDictId >= 0 && defaultBiomeDictId < dictBiomes.size()) {
            biomePal = List.of(dictBiomes.get(defaultBiomeDictId));
        }

        return new DecodedSection(
                sectionY,
                CURVE_H3,
                0L,
                skipLighting ? Collections.emptyList() : List.of(canonState),
                List.of(baseName),
                null,
                biomePal,
                packedBiomeLongs,
                sky2048,
                blk2048
        );
    }

    static DecodedSection applySectionPatch(
            DecodedSection base,
            SectionPatch patch,
            List<String> dictCanonicalStates,
            List<String> dictBaseNames,
            boolean skipLighting
    ) throws CorruptRegionEntryException {
        if (patch == null) {
            return base;
        }
        List<String> newCanonPal = base.blockStateCanonicalPalette;
        List<String> newBasePal = base.blockBaseNamePalette;
        long[] newPackedLongs = base.packedBlockLongs;

        if ((patch.patchFlags & PATCH_FLAG_VOXEL_DELTAS) != 0 && patch.yzxCoords != null) {
            ScratchArena arena = scratch();
            int[] yzxIdx = arena.yzxPaletteIdx;
            int basePalSize = (!skipLighting && !base.blockStateCanonicalPalette.isEmpty())
                    ? base.blockStateCanonicalPalette.size()
                    : base.blockBaseNamePalette.size();
            if (base.packedBlockLongs == null || basePalSize <= 1) {
                Arrays.fill(yzxIdx, 0);
            } else {
                int bits = PackedPaletteDecoder.bitsPerEntry(basePalSize);
                int entriesPerLong = 64 / bits;
                long mask = (1L << bits) - 1L;
                int voxel = 0;
                for (int w = 0; w < base.packedBlockLongs.length && voxel < SECTION_VOXELS; w++) {
                    long word = base.packedBlockLongs[w];
                    int lim = Math.min(entriesPerLong, SECTION_VOXELS - voxel);
                    for (int s = 0; s < lim; s++) {
                        yzxIdx[voxel++] = (int) (word & mask);
                        word >>>= bits;
                    }
                }
                while (voxel < SECTION_VOXELS) {
                    yzxIdx[voxel++] = 0;
                }
            }

            List<String> mutBase = new ArrayList<>(base.blockBaseNamePalette);
            List<String> mutCanon = skipLighting ? Collections.emptyList() : new ArrayList<>(base.blockStateCanonicalPalette);
            int totalStates = dictBaseNames.size();

            for (int d = 0; d < patch.yzxCoords.length; d++) {
                int yzx = patch.yzxCoords[d];
                int dictId = patch.dictIds[d];
                if (dictId < 0 || dictId >= totalStates) {
                    throw new CorruptRegionEntryException("CBIT T7: Patch voxel dictId " + dictId + " >= " + totalStates);
                }
                int palIdx;
                if (!skipLighting) {
                    String canon = dictCanonicalStates.get(dictId);
                    palIdx = mutCanon.indexOf(canon);
                    if (palIdx < 0) {
                        palIdx = mutCanon.size();
                        mutCanon.add(canon);
                        mutBase.add(dictBaseNames.get(dictId));
                    }
                } else {
                    String bName = dictBaseNames.get(dictId);
                    palIdx = mutBase.indexOf(bName);
                    if (palIdx < 0) {
                        palIdx = mutBase.size();
                        mutBase.add(bName);
                    }
                }
                yzxIdx[yzx] = palIdx;
            }

            boolean allZero = true;
            for (int i = 0; i < SECTION_VOXELS; i++) {
                if (yzxIdx[i] != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                newBasePal = List.of(mutBase.get(0));
                newCanonPal = skipLighting ? Collections.emptyList() : List.of(mutCanon.get(0));
                newPackedLongs = null;
            } else {
                newBasePal = Collections.unmodifiableList(mutBase);
                newCanonPal = skipLighting ? Collections.emptyList() : Collections.unmodifiableList(mutCanon);
                newPackedLongs = packBlockLongs(yzxIdx, (!skipLighting ? mutCanon.size() : mutBase.size()));
            }
        }

        byte[] newSky = (!skipLighting && (patch.patchFlags & PATCH_FLAG_SKY_LIGHT) != 0)
                ? patch.skyLight2048
                : base.skyLight2048;
        byte[] newBlk = (!skipLighting && (patch.patchFlags & PATCH_FLAG_BLOCK_LIGHT) != 0)
                ? patch.blockLight2048
                : base.blockLight2048;

        return new DecodedSection(
                base.sectionY,
                base.curveId,
                base.octreeOccupancyMask64,
                newCanonPal,
                newBasePal,
                newPackedLongs,
                base.biomePalette,
                base.packedBiomeLongs,
                newSky,
                newBlk
        );
    }

    // -------------------------------------------------------------------------
    // Direct Primitive ChunkFrame -> Mojang Binary NBT Emitter (Zero Boxed Map)
    // -------------------------------------------------------------------------

    private static final byte[][] STANDARD_HEIGHTMAP_LONG_ARRAY_PREFIXES = buildStandardHeightmapPrefixes();
    private static final byte[] DATA_VERSION_PREFIX_NBT = buildTagHeaderPrefix(Nbt.TAG_INT, "DataVersion");
    private static final byte[] X_POS_PREFIX_NBT = buildTagHeaderPrefix(Nbt.TAG_INT, "xPos");
    private static final byte[] Z_POS_PREFIX_NBT = buildTagHeaderPrefix(Nbt.TAG_INT, "zPos");
    private static final byte[] Y_POS_PREFIX_NBT = buildTagHeaderPrefix(Nbt.TAG_INT, "yPos");
    private static final byte[] LAST_UPDATE_PREFIX_NBT = buildTagHeaderPrefix(Nbt.TAG_LONG, "LastUpdate");
    private static final byte[] INHABITED_TIME_PREFIX_NBT = buildTagHeaderPrefix(Nbt.TAG_LONG, "InhabitedTime");
    private static final byte[] STATUS_FULL_TAG_NBT = buildStringTag("Status", "minecraft:full");

    private static byte[] buildStringTag(String name, String val) {
        byte[] prefix = buildTagHeaderPrefix(Nbt.TAG_STRING, name);
        byte[] vUtf = val.getBytes(StandardCharsets.UTF_8);
        byte[] out = Arrays.copyOf(prefix, prefix.length + 2 + vUtf.length);
        out[prefix.length] = (byte) ((vUtf.length >>> 8) & 0xFF);
        out[prefix.length + 1] = (byte) (vUtf.length & 0xFF);
        System.arraycopy(vUtf, 0, out, prefix.length + 2, vUtf.length);
        return out;
    }

    private static byte[] buildTagHeaderPrefix(byte tagType, String name) {
        byte[] utf = name.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[3 + utf.length];
        out[0] = tagType;
        out[1] = (byte) ((utf.length >>> 8) & 0xFF);
        out[2] = (byte) (utf.length & 0xFF);
        System.arraycopy(utf, 0, out, 3, utf.length);
        return out;
    }

    private static byte[] buildListTagHeaderPrefix(String name, byte elemType) {
        byte[] utf = name.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[4 + utf.length];
        out[0] = Nbt.TAG_LIST;
        out[1] = (byte) ((utf.length >>> 8) & 0xFF);
        out[2] = (byte) (utf.length & 0xFF);
        System.arraycopy(utf, 0, out, 3, utf.length);
        out[3 + utf.length] = elemType;
        return out;
    }

    private static byte[] buildEmptyCompoundListTag(String name) {
        byte[] prefix = buildListTagHeaderPrefix(name, Nbt.TAG_COMPOUND);
        return Arrays.copyOf(prefix, prefix.length + 4);
    }

    private static byte[] buildByteTag(String name, byte val) {
        byte[] prefix = buildTagHeaderPrefix(Nbt.TAG_BYTE, name);
        byte[] out = Arrays.copyOf(prefix, prefix.length + 1);
        out[prefix.length] = val;
        return out;
    }

    private static byte[] buildEmptyStructuresTag() {
        byte[] sPrefix = buildTagHeaderPrefix(Nbt.TAG_COMPOUND, "structures");
        byte[] refPrefix = buildTagHeaderPrefix(Nbt.TAG_COMPOUND, "References");
        byte[] startsPrefix = buildTagHeaderPrefix(Nbt.TAG_COMPOUND, "Starts");
        byte[] out = new byte[sPrefix.length + refPrefix.length + 1 + startsPrefix.length + 1 + 1];
        int p = 0;
        System.arraycopy(sPrefix, 0, out, p, sPrefix.length);
        p += sPrefix.length;
        System.arraycopy(refPrefix, 0, out, p, refPrefix.length);
        p += refPrefix.length;
        out[p++] = Nbt.TAG_END;
        System.arraycopy(startsPrefix, 0, out, p, startsPrefix.length);
        p += startsPrefix.length;
        out[p++] = Nbt.TAG_END;
        out[p] = Nbt.TAG_END;
        return out;
    }

    private static byte[] buildBlockStatesPalettePrefix() {
        byte[] bs = buildTagHeaderPrefix(Nbt.TAG_COMPOUND, "block_states");
        byte[] pal = buildListTagHeaderPrefix("palette", Nbt.TAG_COMPOUND);
        byte[] out = new byte[bs.length + pal.length];
        System.arraycopy(bs, 0, out, 0, bs.length);
        System.arraycopy(pal, 0, out, bs.length, pal.length);
        return out;
    }

    private static byte[] buildBiomesPalettePrefix() {
        byte[] bm = buildTagHeaderPrefix(Nbt.TAG_COMPOUND, "biomes");
        byte[] pal = buildListTagHeaderPrefix("palette", Nbt.TAG_STRING);
        byte[] out = new byte[bm.length + pal.length];
        System.arraycopy(bm, 0, out, 0, bm.length);
        System.arraycopy(pal, 0, out, bm.length, pal.length);
        return out;
    }

    private static byte[] buildByteArray2048Header(String name) {
        byte[] prefix = buildTagHeaderPrefix(Nbt.TAG_BYTE_ARRAY, name);
        byte[] out = Arrays.copyOf(prefix, prefix.length + 4);
        out[prefix.length] = 0;
        out[prefix.length + 1] = 0;
        out[prefix.length + 2] = 0x08;
        out[prefix.length + 3] = 0x00;
        return out;
    }

    private static byte[][] buildStandardHeightmapPrefixes() {
        byte[][] out = new byte[STANDARD_HEIGHTMAP_NAMES.length][];
        for (int i = 0; i < STANDARD_HEIGHTMAP_NAMES.length; i++) {
            out[i] = buildTagHeaderPrefix(Nbt.TAG_LONG_ARRAY, STANDARD_HEIGHTMAP_NAMES[i]);
        }
        return out;
    }

    static byte[] encodeNbtStringPayloadBytes(String s) {
        byte[] utf = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[2 + utf.length];
        out[0] = (byte) ((utf.length >>> 8) & 0xFF);
        out[1] = (byte) (utf.length & 0xFF);
        System.arraycopy(utf, 0, out, 2, utf.length);
        return out;
    }

    private static byte[] encodeNbtStringPayloadBytesFromFrame(byte[] frame, int pos, int utfLen, boolean mcPrefix) {
        int totalUtf = (mcPrefix ? 10 : 0) + utfLen;
        byte[] out = new byte[2 + totalUtf];
        out[0] = (byte) ((totalUtf >>> 8) & 0xFF);
        out[1] = (byte) (totalUtf & 0xFF);
        int dst = 2;
        if (mcPrefix) {
            System.arraycopy(MINECRAFT_COLON_UTF8, 0, out, dst, 10);
            dst += 10;
        }
        System.arraycopy(frame, pos, out, dst, utfLen);
        return out;
    }

    static byte[] encodeSingleBiomeCompoundNbtBytes(byte[] biomeStringPayloadBytes) {
        int pLen = BIOMES_PALETTE_PREFIX_NBT.length;
        byte[] out = new byte[pLen + 4 + biomeStringPayloadBytes.length + 1];
        System.arraycopy(BIOMES_PALETTE_PREFIX_NBT, 0, out, 0, pLen);
        out[pLen] = 0;
        out[pLen + 1] = 0;
        out[pLen + 2] = 0;
        out[pLen + 3] = 1;
        System.arraycopy(biomeStringPayloadBytes, 0, out, pLen + 4, biomeStringPayloadBytes.length);
        out[out.length - 1] = Nbt.TAG_END;
        return out;
    }

    private static byte[] encodeBaseStateBytesFromFrame(byte[] frame, int pos, int utfLen, boolean mcPrefix) {
        int totalUtf = (mcPrefix ? 10 : 0) + utfLen;
        byte[] out = new byte[9 + totalUtf + 1];
        out[0] = Nbt.TAG_STRING;
        out[1] = 0;
        out[2] = 4;
        out[3] = 'N';
        out[4] = 'a';
        out[5] = 'm';
        out[6] = 'e';
        out[7] = (byte) ((totalUtf >>> 8) & 0xFF);
        out[8] = (byte) (totalUtf & 0xFF);
        int dst = 9;
        if (mcPrefix) {
            System.arraycopy(MINECRAFT_COLON_UTF8, 0, out, dst, 10);
            dst += 10;
        }
        System.arraycopy(frame, pos, out, dst, utfLen);
        out[out.length - 1] = Nbt.TAG_END;
        return out;
    }

    static byte[] encodePropSuffixToNbtBytes(String propSuffix) {
        String propsStr = propSuffix.substring(1, propSuffix.length() - 1);
        byte[] tmp = new byte[256];
        int p = 0;
        tmp[p++] = Nbt.TAG_COMPOUND;
        tmp[p++] = 0;
        tmp[p++] = 10;
        byte[] propKey = "Properties".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(propKey, 0, tmp, p, 10);
        p += 10;
        int start = 0;
        while (start < propsStr.length()) {
            int comma = propsStr.indexOf(',', start);
            String pair = (comma < 0) ? propsStr.substring(start) : propsStr.substring(start, comma);
            int eq = pair.indexOf('=');
            if (eq > 0) {
                byte[] kBytes = pair.substring(0, eq).getBytes(StandardCharsets.UTF_8);
                byte[] vBytes = pair.substring(eq + 1).getBytes(StandardCharsets.UTF_8);
                if (p + 5 + kBytes.length + vBytes.length + 2 > tmp.length) {
                    tmp = Arrays.copyOf(tmp, (tmp.length + kBytes.length + vBytes.length + 64) << 1);
                }
                tmp[p++] = Nbt.TAG_STRING;
                tmp[p++] = (byte) ((kBytes.length >>> 8) & 0xFF);
                tmp[p++] = (byte) (kBytes.length & 0xFF);
                System.arraycopy(kBytes, 0, tmp, p, kBytes.length);
                p += kBytes.length;
                tmp[p++] = (byte) ((vBytes.length >>> 8) & 0xFF);
                tmp[p++] = (byte) (vBytes.length & 0xFF);
                System.arraycopy(vBytes, 0, tmp, p, vBytes.length);
                p += vBytes.length;
            }
            if (comma < 0) break;
            start = comma + 1;
        }
        tmp[p++] = Nbt.TAG_END; // End Properties compound
        tmp[p++] = Nbt.TAG_END; // End BlockState compound
        return Arrays.copyOf(tmp, p);
    }

    static byte[] combineBaseStateAndPropNbt(byte[] baseStateNbt, byte[] propSuffixNbt) {
        int basePrefixLen = baseStateNbt.length - 1;
        byte[] out = new byte[basePrefixLen + propSuffixNbt.length];
        System.arraycopy(baseStateNbt, 0, out, 0, basePrefixLen);
        System.arraycopy(propSuffixNbt, 0, out, basePrefixLen, propSuffixNbt.length);
        return out;
    }

    static byte[] encodeCanonicalStateToNbtCompoundBytes(String canonicalState) {
        int bracket = canonicalState.indexOf('[');
        if (bracket < 0 || !canonicalState.endsWith("]")) {
            byte[] utf = canonicalState.getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[9 + utf.length + 1];
            out[0] = Nbt.TAG_STRING;
            out[1] = 0;
            out[2] = 4;
            out[3] = 'N';
            out[4] = 'a';
            out[5] = 'm';
            out[6] = 'e';
            out[7] = (byte) ((utf.length >>> 8) & 0xFF);
            out[8] = (byte) (utf.length & 0xFF);
            System.arraycopy(utf, 0, out, 9, utf.length);
            out[out.length - 1] = Nbt.TAG_END;
            return out;
        }
        byte[] baseNbt = encodeCanonicalStateToNbtCompoundBytes(canonicalState.substring(0, bracket));
        String suffix = canonicalState.substring(bracket);
        Integer propId = STATIC_CORE_PROP_TO_ID.get(suffix);
        byte[] propNbt = (propId != null && STATIC_CORE_PROP_NBT_BYTES[propId] != null)
                ? STATIC_CORE_PROP_NBT_BYTES[propId]
                : encodePropSuffixToNbtBytes(suffix);
        return combineBaseStateAndPropNbt(baseNbt, propNbt);
    }

    static final class SlabLocalPaletteNbt {
        final byte[][] stateNbtBytes;
        final byte[][] biomeStringNbtBytes;
        final byte[][] singleBiomeCompoundNbtBytes;
        final byte[][] biomeTemplateNbtBytes;

        SlabLocalPaletteNbt(
                byte[][] stateNbtBytes,
                byte[][] biomeStringNbtBytes,
                byte[][] singleBiomeCompoundNbtBytes,
                byte[][] biomeTemplateNbtBytes
        ) {
            this.stateNbtBytes = stateNbtBytes;
            this.biomeStringNbtBytes = biomeStringNbtBytes;
            this.singleBiomeCompoundNbtBytes = singleBiomeCompoundNbtBytes;
            this.biomeTemplateNbtBytes = biomeTemplateNbtBytes;
        }
    }

    static SlabLocalPaletteNbt decodeSlabLocalPaletteForNbt(
            byte[] frame,
            int fOff,
            int startRel,
            int limitRel,
            ParsedHeaderAndDict regionMeta,
            ScratchArena arena
    ) throws CorruptRegionEntryException {
        int pos = fOff + startRel;
        int limit = fOff + limitRel;
        int stateCount = readVarInt(frame, pos, limit);
        pos += varIntLen(stateCount);
        if (stateCount < 0 || stateCount > SECTION_VOXELS) {
            throw new CorruptRegionEntryException("CBIT T5: Invalid slab-local stateCount " + stateCount);
        }
        byte[][] stateNbt = new byte[stateCount][];
        int[] stateCoreIds = arena.sectionPaletteDictIds;
        for (int i = 0; i < stateCount; i++) {
            if (pos >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated slab-local palette");
            int tag = frame[pos++] & 0xFF;
            if ((tag & 0x80) != 0 || (tag >= 0x60 && tag < 0x80)) {
                int coreId = ((tag & 0x80) != 0) ? (tag & 0x7F) : (128 + (tag & 0x1F));
                if (coreId >= STATIC_CORE_STATE_NBT_BYTES.length) {
                    throw new CorruptRegionEntryException("CBIT T5: Invalid static core token " + coreId);
                }
                stateNbt[i] = STATIC_CORE_STATE_NBT_BYTES[coreId];
                stateCoreIds[i] = coreId;
            } else {
                int utfLen = readVarInt(frame, pos, limit);
                pos += varIntLen(utfLen);
                if (utfLen < 0 || pos + utfLen > limit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated slab-local string");
                }
                stateNbt[i] = encodeBaseStateBytesFromFrame(frame, pos, utfLen, tag == 0x40);
                stateCoreIds[i] = -1;
                pos += utfLen;
            }
        }

        int biomeCount = readVarInt(frame, pos, limit);
        pos += varIntLen(biomeCount);
        if (biomeCount < 0 || biomeCount > 1024) {
            throw new CorruptRegionEntryException("CBIT T5: Invalid slab-local biomeCount " + biomeCount);
        }
        byte[][] biomeStrNbt = new byte[biomeCount][];
        byte[][] singleBiomeNbt = new byte[biomeCount][];
        for (int i = 0; i < biomeCount; i++) {
            if (pos >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated slab-local biome palette");
            int tag = frame[pos++] & 0xFF;
            if ((tag & 0x80) != 0 || (tag >= 0x60 && tag < 0x80)) {
                int coreId = ((tag & 0x80) != 0) ? (tag & 0x7F) : (128 + (tag & 0x1F));
                if (coreId >= STATIC_CORE_BIOME_STRING_NBT_BYTES.length) {
                    throw new CorruptRegionEntryException("CBIT T5: Invalid static biome token " + coreId);
                }
                biomeStrNbt[i] = STATIC_CORE_BIOME_STRING_NBT_BYTES[coreId];
                singleBiomeNbt[i] = STATIC_CORE_SINGLE_BIOME_COMPOUND_NBT_BYTES[coreId];
            } else {
                int utfLen = readVarInt(frame, pos, limit);
                pos += varIntLen(utfLen);
                if (utfLen < 0 || pos + utfLen > limit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated slab-local biome string");
                }
                byte[] strBytes = encodeNbtStringPayloadBytesFromFrame(frame, pos, utfLen, tag == 0x40);
                biomeStrNbt[i] = strBytes;
                singleBiomeNbt[i] = encodeSingleBiomeCompoundNbtBytes(strBytes);
                pos += utfLen;
            }
        }

        int tmplCount = readVarInt(frame, pos, limit);
        pos += varIntLen(tmplCount);
        if (tmplCount < 0 || tmplCount > 64) {
            throw new CorruptRegionEntryException("CBIT T5: Invalid slab-local biome template count " + tmplCount);
        }
        byte[][] tmplNbt = tmplCount > 0 ? new byte[tmplCount][] : new byte[0][];
        for (int t = 0; t < tmplCount; t++) {
            int bLen = readVarInt(frame, pos, limit);
            pos += varIntLen(bLen);
            pos = decodeBiomePayloadToCompoundNbt(frame, pos, limit, bLen, biomeStrNbt, singleBiomeNbt, arena, tmplNbt, t);
        }

        int propCount = readVarInt(frame, pos, limit);
        pos += varIntLen(propCount);
        for (int k = 0; k < propCount; k++) {
            int stateIdx = readVarInt(frame, pos, limit);
            pos += varIntLen(stateIdx);
            if (stateIdx < 0 || stateIdx >= stateCount || pos >= limit) {
                throw new CorruptRegionEntryException("CBIT T5: Invalid slab-local property suffix index");
            }
            int tag = frame[pos++] & 0xFF;
            if ((tag & 0x80) != 0) {
                int propId = tag & 0x7F;
                if (propId >= STATIC_CORE_PROP_NBT_BYTES.length) {
                    throw new CorruptRegionEntryException("CBIT T5: Invalid static property suffix token " + propId);
                }
                int coreId = stateCoreIds[stateIdx];
                if (coreId >= 0) {
                    int cacheIdx = (coreId << 7) | propId;
                    byte[] cached = STATIC_CORE_STATE_PROP_NBT_CACHE[cacheIdx];
                    if (cached == null) {
                        cached = combineBaseStateAndPropNbt(stateNbt[stateIdx], STATIC_CORE_PROP_NBT_BYTES[propId]);
                        STATIC_CORE_STATE_PROP_NBT_CACHE[cacheIdx] = cached;
                    }
                    stateNbt[stateIdx] = cached;
                } else {
                    stateNbt[stateIdx] = combineBaseStateAndPropNbt(stateNbt[stateIdx], STATIC_CORE_PROP_NBT_BYTES[propId]);
                }
            } else {
                int globalId = readVarInt(frame, pos, limit);
                pos += varIntLen(globalId);
                if (regionMeta == null || globalId < 0 || globalId >= regionMeta.canonicalStates.size()) {
                    throw new CorruptRegionEntryException("CBIT T7: Slab-local globalStateId " + globalId + " out of range");
                }
                stateNbt[stateIdx] = regionMeta.stateNbtBytesAt(globalId);
            }
        }
        return new SlabLocalPaletteNbt(stateNbt, biomeStrNbt, singleBiomeNbt, tmplNbt);
    }

    private static int decodeBiomePayloadToCompoundNbt(
            byte[] buf,
            int pos,
            int limit,
            int bLen,
            byte[][] dictBiomeStrNbt,
            byte[][] dictSingleBiomeCompoundNbt,
            ScratchArena arena,
            byte[][] targetArray,
            int targetIdx
    ) throws CorruptRegionEntryException {
        if (bLen <= 0 || bLen > BIOME_CELLS) {
            throw new CorruptRegionEntryException("CBIT T7: Invalid biome palette length " + bLen);
        }
        if (bLen == 1) {
            int bDictId = readVarInt(buf, pos, limit);
            pos += varIntLen(bDictId);
            if (bDictId < 0 || bDictId >= dictSingleBiomeCompoundNbt.length) {
                throw new CorruptRegionEntryException("CBIT T7: Biome dictId " + bDictId + " out of range");
            }
            targetArray[targetIdx] = dictSingleBiomeCompoundNbt[bDictId];
            return pos;
        }
        int[] bIds = arena.biomePaletteDictIds;
        int totalStrBytes = 0;
        for (int i = 0; i < bLen; i++) {
            int bDictId = readVarInt(buf, pos, limit);
            pos += varIntLen(bDictId);
            if (bDictId < 0 || bDictId >= dictBiomeStrNbt.length) {
                throw new CorruptRegionEntryException("CBIT T7: Biome dictId " + bDictId + " out of range");
            }
            bIds[i] = bDictId;
            totalStrBytes += dictBiomeStrNbt[bDictId].length;
        }
        int runCount = readVarInt(buf, pos, limit);
        pos += varIntLen(runCount);
        int[] cells = arena.biomeCells;
        int cursor = 0;
        if (bLen <= 8) {
            for (int r = 0; r < runCount; r++) {
                if (pos >= limit) throw new CorruptRegionEntryException("CBIT: Truncated 1B biome RLE");
                int packed = buf[pos++] & 0xFF;
                int pIdx = (packed >>> 5) & 7;
                int len = (packed & 0x1F) + 1;
                if (pIdx >= bLen || cursor + len > BIOME_CELLS) {
                    throw new CorruptRegionEntryException("CBIT: Biome RLE cursor overflow");
                }
                for (int k = 0; k < len; k++) {
                    int yzx = ((cursor & 3) << 4) | (cursor & 0x0C) | ((cursor >>> 4) & 3);
                    cells[yzx] = pIdx;
                    cursor++;
                }
            }
        } else {
            for (int r = 0; r < runCount; r++) {
                int pIdx = readVarInt(buf, pos, limit);
                pos += varIntLen(pIdx);
                if (pos >= limit) throw new CorruptRegionEntryException("CBIT: Truncated biome RLE");
                int len = (buf[pos++] & 0xFF) + 1;
                if (pIdx < 0 || pIdx >= bLen || cursor + len > BIOME_CELLS) {
                    throw new CorruptRegionEntryException("CBIT: Biome RLE cursor overflow");
                }
                for (int k = 0; k < len; k++) {
                    int yzx = ((cursor & 3) << 4) | (cursor & 0x0C) | ((cursor >>> 4) & 3);
                    cells[yzx] = pIdx;
                    cursor++;
                }
            }
        }
        int bits = BiomePaletteSection.biomeBitsPerEntry(bLen);
        int entriesPerLong = 64 / bits;
        int numLongs = (BIOME_CELLS + entriesPerLong - 1) / entriesPerLong;
        int pLen = BIOMES_PALETTE_PREFIX_NBT.length;
        int dLen = DATA_LONG_ARRAY_PREFIX_NBT.length;
        byte[] comp = new byte[pLen + 4 + totalStrBytes + dLen + 4 + (numLongs << 3) + 1];
        int cp = 0;
        System.arraycopy(BIOMES_PALETTE_PREFIX_NBT, 0, comp, cp, pLen);
        cp += pLen;
        writeIntBEToBuf(comp, cp, bLen);
        cp += 4;
        for (int i = 0; i < bLen; i++) {
            byte[] sb = dictBiomeStrNbt[bIds[i]];
            System.arraycopy(sb, 0, comp, cp, sb.length);
            cp += sb.length;
        }
        System.arraycopy(DATA_LONG_ARRAY_PREFIX_NBT, 0, comp, cp, dLen);
        cp += dLen;
        writeIntBEToBuf(comp, cp, numLongs);
        cp += 4;
        int cell = 0;
        for (int w = 0; w < numLongs; w++) {
            long word = 0L;
            int lim = Math.min(entriesPerLong, BIOME_CELLS - cell);
            for (int s = 0; s < lim; s++) {
                word |= ((long) cells[cell++]) << (s * bits);
            }
            writeLongBEToBuf(comp, cp, word);
            cp += 8;
        }
        comp[cp] = Nbt.TAG_END;
        targetArray[targetIdx] = comp;
        return pos;
    }

    static void writeIntBEToBuf(byte[] dst, int offset, int v) {
        dst[offset] = (byte) ((v >>> 24) & 0xFF);
        dst[offset + 1] = (byte) ((v >>> 16) & 0xFF);
        dst[offset + 2] = (byte) ((v >>> 8) & 0xFF);
        dst[offset + 3] = (byte) (v & 0xFF);
    }

    private static int writeIntTagWithPrefix(byte[] dst, int pos, byte[] prefix, int val) {
        System.arraycopy(prefix, 0, dst, pos, prefix.length);
        pos += prefix.length;
        writeIntBEToBuf(dst, pos, val);
        return pos + 4;
    }

    private static int writeLongTagWithPrefix(byte[] dst, int pos, byte[] prefix, long val) {
        System.arraycopy(prefix, 0, dst, pos, prefix.length);
        pos += prefix.length;
        writeLongBEToBuf(dst, pos, val);
        return pos + 8;
    }

    private static int writeStringTagToBuf(byte[] dst, int pos, String name, String val) {
        byte[] kUtf = name.getBytes(StandardCharsets.UTF_8);
        byte[] vUtf = val.getBytes(StandardCharsets.UTF_8);
        dst[pos++] = Nbt.TAG_STRING;
        dst[pos++] = (byte) ((kUtf.length >>> 8) & 0xFF);
        dst[pos++] = (byte) (kUtf.length & 0xFF);
        System.arraycopy(kUtf, 0, dst, pos, kUtf.length);
        pos += kUtf.length;
        dst[pos++] = (byte) ((vUtf.length >>> 8) & 0xFF);
        dst[pos++] = (byte) (vUtf.length & 0xFF);
        System.arraycopy(vUtf, 0, dst, pos, vUtf.length);
        return pos + vUtf.length;
    }

    private static boolean containsSubArray(byte[] hay, int offset, int len, byte[] needle) {
        int nLen = needle.length;
        if (len < nLen) return false;
        byte first = needle[0];
        int max = offset + len - nLen;
        outer:
        for (int i = offset; i <= max; i++) {
            if (hay[i] != first) continue;
            for (int j = 1; j < nLen; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /**
     * Streams a `.lca` `ChunkFrame` directly into an uncompressed Mojang binary NBT root
     * `TAG_Compound` byte array without allocating intermediate `LinkedHashMap` or `NbtList` trees.
     * Returns `null` if the chunk contains an un-compacted `_lca_boundary_` tag requiring map fallback.
     */
    @SuppressWarnings({"java:S1168", "java:S1199"})
    static byte[] encodeChunkFrameToNbtBytes(
            ParsedHeaderAndDict meta,
            byte[] frame,
            int fOff,
            int frameLen,
            int chunkDataVersion,
            long lastUpdateTick,
            long inhabitedTimeTicks,
            int statusOrdinal,
            int chunkFlags,
            int residualRelStart,
            int rx,
            int rz,
            int[] sectionEntries,
            int[] sectionRelStarts
    ) throws IOException {
        boolean hasResidualNbt = (chunkFlags & 1) != 0;
        boolean hasSlabLocalPalette = (chunkFlags & 2) != 0;
        int defaultBiomeCode = (chunkFlags >>> 3) & 0x1F;
        int defaultBiomeDictId = defaultBiomeCode > 0 ? (defaultBiomeCode - 1) : -1;
        int sectionCount = meta.sectionCount;
        int sectionDirEnd = 24 + (sectionCount << 1);

        int mbnlStart = 0;
        int mbnlLen = 0;
        int bpFlags = 0;
        int ppCount = 0;
        int slVer = 0;
        int lowBoundaryFlags = -1;
        int highBoundaryFlags = -1;
        int secHmCount = 0;
        int secHmPos = 0;
        int secHmLimit = 0;
        byte[] nbtSliceBuf = null;
        int nbtSliceOff = 0;
        int nbtSliceLen = 0;

        int residualRelEnd = findResidualRelEnd(sectionRelStarts, sectionCount, residualRelStart, frameLen);
        if (hasResidualNbt) {
            int pos = fOff + residualRelStart;
            int limit = fOff + residualRelEnd;
            if (pos + 2 <= limit) {
                int tailLimit = limit;
                bpFlags = readUint16BE(frame, pos);
                if ((bpFlags & BP_FLAG_HAS_CHUNK_PATCH_TAIL) != 0) {
                    return null;
                }
                pos += 2;
                if ((bpFlags & 8192) != 0) {
                    mbnlLen = readVarInt(frame, pos, tailLimit);
                    pos += varIntLen(mbnlLen);
                    if (mbnlLen < 0 || pos + mbnlLen > tailLimit) {
                        throw new CorruptRegionEntryException("CBIT T5: Truncated MOTION_BLOCKING_NO_LEAVES prefix");
                    }
                    mbnlStart = pos;
                    pos += mbnlLen;
                }
                {
                    if ((bpFlags & 32) != 0 && pos < tailLimit) {
                        ppCount = frame[pos++] & 0xFF;
                    }
                    if ((bpFlags & 64) != 0 && pos < tailLimit) {
                        slVer = readVarInt(frame, pos, tailLimit);
                        pos += varIntLen(slVer);
                    }
                    if ((bpFlags & 128) != 0 && pos < tailLimit) {
                        lowBoundaryFlags = frame[pos++] & 0xFF;
                    }
                    if ((bpFlags & 256) != 0 && pos < tailLimit) {
                        highBoundaryFlags = frame[pos++] & 0xFF;
                    }
                    if ((bpFlags & 512) != 0 && pos < tailLimit) {
                        secHmCount = frame[pos++] & 0xFF;
                        secHmPos = pos;
                        secHmLimit = tailLimit;
                        for (int h = 0; h < secHmCount; h++) {
                            if (pos >= tailLimit) {
                                throw new CorruptRegionEntryException("CBIT T5: Truncated secondary heightmap header");
                            }
                            int code = frame[pos++] & 0xFF;
                            if (code >= STANDARD_HEIGHTMAP_NAMES.length) {
                                int kLen = readVarInt(frame, pos, tailLimit);
                                pos += varIntLen(kLen) + kLen;
                            }
                            int payLen = readVarInt(frame, pos, tailLimit);
                            pos += varIntLen(payLen) + payLen;
                            if (pos > tailLimit) {
                                throw new CorruptRegionEntryException("CBIT T5: Truncated secondary heightmap payload");
                            }
                        }
                    }
                    if ((bpFlags & 1024) != 0 && pos < tailLimit) {
                        if ((bpFlags & 4096) != 0) {
                            try (java.util.zip.InflaterInputStream iis =
                                    new java.util.zip.InflaterInputStream(
                                            new java.io.ByteArrayInputStream(frame, pos, tailLimit - pos))) {
                                nbtSliceBuf = iis.readAllBytes();
                                nbtSliceOff = 0;
                                nbtSliceLen = nbtSliceBuf.length;
                            }
                        } else {
                            nbtSliceBuf = frame;
                            nbtSliceOff = pos;
                            nbtSliceLen = tailLimit - pos;
                        }
                        if (nbtSliceLen < 4
                                || nbtSliceBuf[nbtSliceOff] != Nbt.TAG_COMPOUND
                                || nbtSliceBuf[nbtSliceOff + 1] != 0
                                || nbtSliceBuf[nbtSliceOff + 2] != 0
                                || nbtSliceBuf[nbtSliceOff + nbtSliceLen - 1] != Nbt.TAG_END) {
                            return null;
                        }
                        if (containsSubArray(nbtSliceBuf, nbtSliceOff + 3, nbtSliceLen - 4, LCA_BOUNDARY_MARKER_UTF8)) {
                            return null;
                        }
                    }
                }
            }
        }

        ScratchArena arena = scratch();
        byte[] out = arena.ensureNbtCap(Math.max(65536, frameLen << 2));
        int outPos = 0;

        // Named root TAG_Compound ("")
        out[outPos++] = Nbt.TAG_COMPOUND;
        out[outPos++] = 0;
        out[outPos++] = 0;

        // Non-voxel chunks (entities/ and poi/): memcpy residual NBT children directly
        if ((bpFlags & 2048) != 0) {
            outPos = writeIntTagWithPrefix(out, outPos, DATA_VERSION_PREFIX_NBT, chunkDataVersion);
            if (nbtSliceBuf != null && nbtSliceLen > 4) {
                int bodyLen = nbtSliceLen - 4;
                out = arena.ensureNbtCap(outPos + bodyLen + 64);
                System.arraycopy(nbtSliceBuf, nbtSliceOff + 3, out, outPos, bodyLen);
                outPos += bodyLen;
            }
            if (statusOrdinal != 0xFE
                    && (nbtSliceBuf == null
                            || !containsSubArray(nbtSliceBuf, nbtSliceOff + 3, nbtSliceLen - 4, STATUS_KEY_UTF8))) {
                outPos = writeStringTagToBuf(out, outPos, "Status", decodeStatusOrdinal(statusOrdinal));
            }
            out[outPos++] = Nbt.TAG_END;
            return Arrays.copyOf(out, outPos);
        }

        byte[][] activeStateNbt;
        byte[][] activeBiomeStrNbt;
        byte[][] activeSingleBiomeCompoundNbt;
        byte[][] activeBiomeTemplateNbt;

        if (hasSlabLocalPalette) {
            int paletteLimitRel = findFirstSectionRelStart(sectionRelStarts, sectionCount, residualRelStart);
            SlabLocalPaletteNbt localPal = decodeSlabLocalPaletteForNbt(
                    frame, fOff, sectionDirEnd, paletteLimitRel, meta, arena);
            activeStateNbt = localPal.stateNbtBytes;
            activeBiomeStrNbt = localPal.biomeStringNbtBytes;
            activeSingleBiomeCompoundNbt = localPal.singleBiomeCompoundNbtBytes;
            activeBiomeTemplateNbt = localPal.biomeTemplateNbtBytes;
        } else {
            activeStateNbt = meta.ensureAllStateNbtBytes();
            activeBiomeStrNbt = meta.ensureBiomeStringNbtBytes();
            activeSingleBiomeCompoundNbt = meta.ensureSingleBiomeCompoundNbtBytes();
            activeBiomeTemplateNbt = new byte[0][];
        }

        outPos = writeIntTagWithPrefix(out, outPos, DATA_VERSION_PREFIX_NBT, chunkDataVersion);
        outPos = writeIntTagWithPrefix(out, outPos, X_POS_PREFIX_NBT, (meta.regionX << 5) + rx);
        outPos = writeIntTagWithPrefix(out, outPos, Z_POS_PREFIX_NBT, (meta.regionZ << 5) + rz);
        outPos = writeIntTagWithPrefix(out, outPos, Y_POS_PREFIX_NBT, meta.minSectionY);
        if (statusOrdinal == 0xFF) {
            System.arraycopy(STATUS_FULL_TAG_NBT, 0, out, outPos, STATUS_FULL_TAG_NBT.length);
            outPos += STATUS_FULL_TAG_NBT.length;
        } else if (statusOrdinal != 0xFE) {
            outPos = writeStringTagToBuf(out, outPos, "Status", decodeStatusOrdinal(statusOrdinal));
        }
        outPos = writeLongTagWithPrefix(out, outPos, LAST_UPDATE_PREFIX_NBT, lastUpdateTick);
        outPos = writeLongTagWithPrefix(out, outPos, INHABITED_TIME_PREFIX_NBT, inhabitedTimeTicks);

        if ((bpFlags & 1) != 0) {
            System.arraycopy(EMPTY_BLOCK_ENTITIES_NBT, 0, out, outPos, EMPTY_BLOCK_ENTITIES_NBT.length);
            outPos += EMPTY_BLOCK_ENTITIES_NBT.length;
        }
        if ((bpFlags & 2) != 0) {
            System.arraycopy(EMPTY_BLOCK_TICKS_NBT, 0, out, outPos, EMPTY_BLOCK_TICKS_NBT.length);
            outPos += EMPTY_BLOCK_TICKS_NBT.length;
        }
        if ((bpFlags & 4) != 0) {
            System.arraycopy(EMPTY_FLUID_TICKS_NBT, 0, out, outPos, EMPTY_FLUID_TICKS_NBT.length);
            outPos += EMPTY_FLUID_TICKS_NBT.length;
        }
        if ((bpFlags & 8) != 0) {
            System.arraycopy(IS_LIGHT_ON_TRUE_NBT, 0, out, outPos, IS_LIGHT_ON_TRUE_NBT.length);
            outPos += IS_LIGHT_ON_TRUE_NBT.length;
        }
        if ((bpFlags & 16) != 0) {
            System.arraycopy(EMPTY_STRUCTURES_NBT, 0, out, outPos, EMPTY_STRUCTURES_NBT.length);
            outPos += EMPTY_STRUCTURES_NBT.length;
        }
        if ((bpFlags & 32) != 0) {
            out = arena.ensureNbtCap(outPos + POST_PROCESSING_PREFIX_NBT.length + 4 + ppCount * 5 + 1024);
            System.arraycopy(POST_PROCESSING_PREFIX_NBT, 0, out, outPos, POST_PROCESSING_PREFIX_NBT.length);
            outPos += POST_PROCESSING_PREFIX_NBT.length;
            writeIntBEToBuf(out, outPos, ppCount);
            outPos += 4;
            for (int i = 0; i < ppCount; i++) {
                out[outPos++] = Nbt.TAG_SHORT;
                out[outPos++] = 0;
                out[outPos++] = 0;
                out[outPos++] = 0;
                out[outPos++] = 0;
            }
        }
        if ((bpFlags & 64) != 0) {
            System.arraycopy(STARLIGHT_VERSION_PREFIX_NBT, 0, out, outPos, STARLIGHT_VERSION_PREFIX_NBT.length);
            outPos += STARLIGHT_VERSION_PREFIX_NBT.length;
            writeIntBEToBuf(out, outPos, slVer);
            outPos += 4;
        }

        if (nbtSliceBuf != null && nbtSliceLen > 4) {
            int bodyLen = nbtSliceLen - 4;
            out = arena.ensureNbtCap(outPos + bodyLen + 4096);
            System.arraycopy(nbtSliceBuf, nbtSliceOff + 3, out, outPos, bodyLen);
            outPos += bodyLen;
        }

        if (mbnlLen > 0 || secHmCount > 0) {
            out = arena.ensureNbtCap(outPos + 2048);
            System.arraycopy(HEIGHTMAPS_HEADER_NBT, 0, out, outPos, HEIGHTMAPS_HEADER_NBT.length);
            outPos += HEIGHTMAPS_HEADER_NBT.length;

            long[][] decodedHm = new long[1 + secHmCount][];
            int hmDecodedCount = 0;
            if (mbnlLen > 0) {
                long[] baseLongs = decodeDeltaHeightmapLongs(frame, mbnlStart, mbnlLen);
                decodedHm[hmDecodedCount++] = baseLongs;
                byte[] mbnlPrefixHdr = STANDARD_HEIGHTMAP_LONG_ARRAY_PREFIXES[5];
                System.arraycopy(mbnlPrefixHdr, 0, out, outPos, mbnlPrefixHdr.length);
                outPos += mbnlPrefixHdr.length;
                writeIntBEToBuf(out, outPos, baseLongs.length);
                outPos += 4;
                for (int w = 0; w < baseLongs.length; w++) {
                    writeLongBEToBuf(out, outPos, baseLongs[w]);
                    outPos += 8;
                }
            }
            int pos = secHmPos;
            for (int h = 0; h < secHmCount; h++) {
                int code = frame[pos++] & 0xFF;
                if (code < STANDARD_HEIGHTMAP_LONG_ARRAY_PREFIXES.length) {
                    byte[] hdr = STANDARD_HEIGHTMAP_LONG_ARRAY_PREFIXES[code];
                    System.arraycopy(hdr, 0, out, outPos, hdr.length);
                    outPos += hdr.length;
                } else {
                    int kLen = readVarInt(frame, pos, secHmLimit);
                    pos += varIntLen(kLen);
                    out[outPos++] = Nbt.TAG_LONG_ARRAY;
                    out[outPos++] = (byte) ((kLen >>> 8) & 0xFF);
                    out[outPos++] = (byte) (kLen & 0xFF);
                    System.arraycopy(frame, pos, out, outPos, kLen);
                    outPos += kLen;
                    pos += kLen;
                }
                int payLen = readVarInt(frame, pos, secHmLimit);
                pos += varIntLen(payLen);
                long[] longs;
                if (payLen == 2 && (frame[pos] & 0xFF) == 0xFE && hmDecodedCount > 0) {
                    int refIdx = Math.min(frame[pos + 1] & 0xFF, hmDecodedCount - 1);
                    longs = decodedHm[refIdx];
                } else if (payLen >= 3 && (frame[pos] & 0xFF) == 0xFD && hmDecodedCount > 0) {
                    int refIdx = Math.min(frame[pos + 1] & 0xFF, hmDecodedCount - 1);
                    longs = decodeCrossDeltaHeightmapLongs(decodedHm[refIdx], frame, pos + 2, payLen - 2);
                } else {
                    longs = decodeDeltaHeightmapLongs(frame, pos, payLen);
                }
                pos += payLen;
                decodedHm[hmDecodedCount++] = longs;
                writeIntBEToBuf(out, outPos, longs.length);
                outPos += 4;
                for (int w = 0; w < longs.length; w++) {
                    writeLongBEToBuf(out, outPos, longs[w]);
                    outPos += 8;
                }
            }
            out[outPos++] = Nbt.TAG_END;
        }

        int totalEmittedSections = (lowBoundaryFlags >= 0 ? 1 : 0) + (highBoundaryFlags >= 0 ? 1 : 0);
        for (int i = 0; i < sectionCount; i++) {
            if (sectionEntries[i] != 0) {
                totalEmittedSections++;
            }
        }

        out = arena.ensureNbtCap(outPos + SECTIONS_PREFIX_NBT.length + 8);
        System.arraycopy(SECTIONS_PREFIX_NBT, 0, out, outPos, SECTIONS_PREFIX_NBT.length);
        outPos += SECTIONS_PREFIX_NBT.length;
        writeIntBEToBuf(out, outPos, totalEmittedSections);
        outPos += 4;

        if (lowBoundaryFlags >= 0) {
            out = arena.ensureNbtCap(outPos + 4160);
            outPos = writeCompactBoundarySectionToBuf(out, outPos, meta.minSectionY - 1, lowBoundaryFlags);
        }

        byte[] defaultBiomeCompoundBytes = (defaultBiomeDictId >= 0 && defaultBiomeDictId < activeSingleBiomeCompoundNbt.length)
                ? activeSingleBiomeCompoundNbt[defaultBiomeDictId]
                : null;

        for (int i = 0; i < sectionCount; i++) {
            int entry16 = sectionEntries[i];
            if (entry16 == 0) {
                continue;
            }
            out = arena.ensureNbtCap(outPos + 16384);
            int sectionY = meta.minSectionY + i;
            if ((entry16 & 0x8000) != 0) {
                int skyLight2 = (entry16 >>> 13) & 3;
                int globalDictId = hasSlabLocalPalette ? (entry16 & 0x07FF) : (entry16 & 0x1FFF);
                if (globalDictId >= activeStateNbt.length) {
                    throw new CorruptRegionEntryException("CBIT T7: Inline globalDictId " + globalDictId
                            + " >= stateCount " + activeStateNbt.length);
                }
                out[outPos++] = Nbt.TAG_BYTE;
                out[outPos++] = 0;
                out[outPos++] = 1;
                out[outPos++] = 'Y';
                out[outPos++] = (byte) sectionY;

                System.arraycopy(BLOCK_STATES_PALETTE_PREFIX_NBT, 0, out, outPos, BLOCK_STATES_PALETTE_PREFIX_NBT.length);
                outPos += BLOCK_STATES_PALETTE_PREFIX_NBT.length;
                writeIntBEToBuf(out, outPos, 1);
                outPos += 4;
                byte[] stBytes = activeStateNbt[globalDictId];
                System.arraycopy(stBytes, 0, out, outPos, stBytes.length);
                outPos += stBytes.length;
                out[outPos++] = Nbt.TAG_END; // End block_states

                if (hasSlabLocalPalette && activeBiomeTemplateNbt.length > 0) {
                    int biomeTmplIdx = (entry16 >>> 11) & 3;
                    if (biomeTmplIdx < activeBiomeTemplateNbt.length) {
                        byte[] bmBytes = activeBiomeTemplateNbt[biomeTmplIdx];
                        System.arraycopy(bmBytes, 0, out, outPos, bmBytes.length);
                        outPos += bmBytes.length;
                    }
                } else if (defaultBiomeCompoundBytes != null) {
                    System.arraycopy(defaultBiomeCompoundBytes, 0, out, outPos, defaultBiomeCompoundBytes.length);
                    outPos += defaultBiomeCompoundBytes.length;
                }

                if (skyLight2 == 0b01 || skyLight2 == 0b10) {
                    System.arraycopy(SKY_LIGHT_2048_HEADER_NBT, 0, out, outPos, SKY_LIGHT_2048_HEADER_NBT.length);
                    outPos += SKY_LIGHT_2048_HEADER_NBT.length;
                    Arrays.fill(out, outPos, outPos + 2048, (byte) 0xFF);
                    outPos += 2048;
                }
                if (skyLight2 == 0b10 || skyLight2 == 0b11) {
                    System.arraycopy(BLOCK_LIGHT_2048_HEADER_NBT, 0, out, outPos, BLOCK_LIGHT_2048_HEADER_NBT.length);
                    outPos += BLOCK_LIGHT_2048_HEADER_NBT.length;
                    Arrays.fill(out, outPos, outPos + 2048, (byte) 0xFF);
                    outPos += 2048;
                }
                out[outPos++] = Nbt.TAG_END; // End section compound
            } else {
                int relStart = sectionRelStarts[i];
                int relEnd = findSectionRelEnd(sectionRelStarts, i, sectionCount, residualRelStart, frameLen);
                outPos = decodeAndWriteSectionFrameToNbt(
                        frame, fOff + relStart, fOff + relEnd, sectionY,
                        activeStateNbt, activeBiomeStrNbt, activeSingleBiomeCompoundNbt,
                        activeBiomeTemplateNbt, defaultBiomeCompoundBytes, arena, outPos);
                out = arena.nbtOutBuf;
            }
        }

        if (highBoundaryFlags >= 0) {
            out = arena.ensureNbtCap(outPos + 4160);
            outPos = writeCompactBoundarySectionToBuf(out, outPos, meta.minSectionY + sectionCount, highBoundaryFlags);
        }

        out[outPos++] = Nbt.TAG_END; // End root compound
        return Arrays.copyOf(out, outPos);
    }

    private static int writeCompactBoundarySectionToBuf(byte[] out, int outPos, int sectionY, int flags) {
        out[outPos++] = Nbt.TAG_BYTE;
        out[outPos++] = 0;
        out[outPos++] = 1;
        out[outPos++] = 'Y';
        out[outPos++] = (byte) sectionY;
        int skyMode = flags & 3;
        int blkMode = (flags >>> 2) & 3;
        if (skyMode == 1 || skyMode == 2) {
            System.arraycopy(SKY_LIGHT_2048_HEADER_NBT, 0, out, outPos, SKY_LIGHT_2048_HEADER_NBT.length);
            outPos += SKY_LIGHT_2048_HEADER_NBT.length;
            Arrays.fill(out, outPos, outPos + 2048, skyMode == 2 ? (byte) 0xFF : (byte) 0x00);
            outPos += 2048;
        }
        if (blkMode == 1 || blkMode == 2) {
            System.arraycopy(BLOCK_LIGHT_2048_HEADER_NBT, 0, out, outPos, BLOCK_LIGHT_2048_HEADER_NBT.length);
            outPos += BLOCK_LIGHT_2048_HEADER_NBT.length;
            Arrays.fill(out, outPos, outPos + 2048, blkMode == 2 ? (byte) 0xFF : (byte) 0x00);
            outPos += 2048;
        }
        out[outPos++] = Nbt.TAG_END;
        return outPos;
    }

    private static int decodeAndWriteSectionFrameToNbt(
            byte[] buf,
            int offset,
            int limit,
            int sectionY,
            byte[][] dictStateNbt,
            byte[][] dictBiomeStrNbt,
            byte[][] dictSingleBiomeCompoundNbt,
            byte[][] chunkBiomeTemplateNbt,
            byte[] defaultBiomeCompoundBytes,
            ScratchArena arena,
            int outPos
    ) throws CorruptRegionEntryException {
        if (offset + 2 > limit) {
            throw new CorruptRegionEntryException("CBIT T5: SectionFrame truncated at offset " + offset);
        }
        int pos = offset;
        int sectionHeader = buf[pos++] & 0xFF;
        int curveId = (sectionHeader >>> 6) & 3;
        int blockMode = (sectionHeader >>> 4) & 3;
        int skyMode = (sectionHeader >>> 2) & 3;
        int blkMode = sectionHeader & 3;

        long octreeMask64;
        if (blockMode == BLOCK_MODE_UNIFORM) {
            octreeMask64 = 0L;
        } else if (blockMode == BLOCK_MODE_DELTA_ROARING_FULL_MASK) {
            octreeMask64 = -1L;
        } else {
            if (pos + 8 > limit) {
                throw new CorruptRegionEntryException("CBIT T5: SectionFrame truncated reading octreeOccupancyMask64 at " + offset);
            }
            octreeMask64 = readLongBE(buf, pos);
            pos += 8;
        }

        int paletteLen = readVarInt(buf, pos, limit);
        pos += varIntLen(paletteLen);
        if (paletteLen < 1 || paletteLen > SECTION_VOXELS) {
            throw new CorruptRegionEntryException("CBIT T7: Invalid section paletteLen " + paletteLen);
        }

        int totalDictStates = dictStateNbt.length;
        int[] secPalIds = arena.sectionPaletteDictIds;
        int totalPaletteBytes = 0;
        for (int i = 0; i < paletteLen; i++) {
            int dictId = readVarInt(buf, pos, limit);
            pos += varIntLen(dictId);
            if (dictId < 0 || dictId >= totalDictStates) {
                throw new CorruptRegionEntryException("CBIT T7: Section dictId " + dictId
                        + " >= totalDictStateCount " + totalDictStates);
            }
            secPalIds[i] = dictId;
            totalPaletteBytes += dictStateNbt[dictId].length;
        }

        byte[] out = arena.ensureNbtCap(outPos + totalPaletteBytes + 16384);
        out[outPos++] = Nbt.TAG_BYTE;
        out[outPos++] = 0;
        out[outPos++] = 1;
        out[outPos++] = 'Y';
        out[outPos++] = (byte) sectionY;

        System.arraycopy(BLOCK_STATES_PALETTE_PREFIX_NBT, 0, out, outPos, BLOCK_STATES_PALETTE_PREFIX_NBT.length);
        outPos += BLOCK_STATES_PALETTE_PREFIX_NBT.length;
        writeIntBEToBuf(out, outPos, paletteLen);
        outPos += 4;
        for (int i = 0; i < paletteLen; i++) {
            byte[] stBytes = dictStateNbt[secPalIds[i]];
            System.arraycopy(stBytes, 0, out, outPos, stBytes.length);
            outPos += stBytes.length;
        }

        if (blockMode == BLOCK_MODE_UNIFORM || paletteLen == 1) {
            // Uniform section: no data long array
        } else if (blockMode == BLOCK_MODE_DELTA_ROARING || blockMode == BLOCK_MODE_DELTA_ROARING_FULL_MASK) {
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteLen - 1));
            int entriesPerLong = 64 / bits;
            int numLongs = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
            long[] packed = arena.packedBlockLongs;
            Arrays.fill(packed, 0, numLongs, 0L);
            pos = decodeDeltaRoaringBlocks(
                    buf, pos, limit, paletteLen, CURVE_TO_YZX[curveId], octreeMask64,
                    packed, bits, entriesPerLong);
            System.arraycopy(DATA_LONG_ARRAY_PREFIX_NBT, 0, out, outPos, DATA_LONG_ARRAY_PREFIX_NBT.length);
            outPos += DATA_LONG_ARRAY_PREFIX_NBT.length;
            writeIntBEToBuf(out, outPos, numLongs);
            outPos += 4;
            for (int w = 0; w < numLongs; w++) {
                writeLongBEToBuf(out, outPos, packed[w]);
                outPos += 8;
            }
        } else if (blockMode == BLOCK_MODE_FLAT_BITPACK) {
            pos = decodeFlatBitPackBlocks(buf, pos, limit, paletteLen, octreeMask64, arena.yzxPaletteIdx);
            int bits = PackedPaletteDecoder.bitsPerEntry(paletteLen);
            int entriesPerLong = 64 / bits;
            int numLongs = (SECTION_VOXELS + entriesPerLong - 1) / entriesPerLong;
            System.arraycopy(DATA_LONG_ARRAY_PREFIX_NBT, 0, out, outPos, DATA_LONG_ARRAY_PREFIX_NBT.length);
            outPos += DATA_LONG_ARRAY_PREFIX_NBT.length;
            writeIntBEToBuf(out, outPos, numLongs);
            outPos += 4;
            int voxel = 0;
            int[] yzxIdx = arena.yzxPaletteIdx;
            for (int w = 0; w < numLongs; w++) {
                long word = 0L;
                int lim = Math.min(entriesPerLong, SECTION_VOXELS - voxel);
                for (int s = 0; s < lim; s++) {
                    word |= ((long) yzxIdx[voxel++]) << (s * bits);
                }
                writeLongBEToBuf(out, outPos, word);
                outPos += 8;
            }
        } else {
            throw new CorruptRegionEntryException("CBIT: Unsupported blockEncodingMode " + blockMode);
        }
        out[outPos++] = Nbt.TAG_END; // End block_states

        // Biomes
        if (pos < limit) {
            int firstByte = buf[pos] & 0xFF;
            if ((firstByte & 0x80) != 0) {
                pos++;
                int tmplIdx = firstByte & 0x7F;
                if (chunkBiomeTemplateNbt == null || tmplIdx >= chunkBiomeTemplateNbt.length) {
                    throw new CorruptRegionEntryException("CBIT T7: Biome template index " + tmplIdx + " out of bounds");
                }
                byte[] bmBytes = chunkBiomeTemplateNbt[tmplIdx];
                System.arraycopy(bmBytes, 0, out, outPos, bmBytes.length);
                outPos += bmBytes.length;
            } else {
                int bLen = readVarInt(buf, pos, limit);
                pos += varIntLen(bLen);
                if (bLen > 0) {
                    outPos = decodeAndWriteSectionBiomePayloadToBuf(
                            buf, pos, limit, bLen, dictBiomeStrNbt, dictSingleBiomeCompoundNbt, arena, out, outPos);
                    pos = arena.biomePaletteDictIds[0];
                } else if (defaultBiomeCompoundBytes != null) {
                    System.arraycopy(defaultBiomeCompoundBytes, 0, out, outPos, defaultBiomeCompoundBytes.length);
                    outPos += defaultBiomeCompoundBytes.length;
                }
            }
        } else if (defaultBiomeCompoundBytes != null) {
            System.arraycopy(defaultBiomeCompoundBytes, 0, out, outPos, defaultBiomeCompoundBytes.length);
            outPos += defaultBiomeCompoundBytes.length;
        }

        // Lighting (00=Absent, 01=ConstNibbles8, 10=GrayPlanes, 11=Raw2048B)
        int constNibbles = 0;
        if (skyMode == LIGHT_MODE_CONST || blkMode == LIGHT_MODE_CONST) {
            if (pos >= limit) {
                throw new CorruptRegionEntryException("CBIT: Truncated const light byte in SectionFrame");
            }
            constNibbles = buf[pos++] & 0xFF;
        }

        if (skyMode == LIGHT_MODE_ABSENT) {
            // Absent (0 bytes)
        } else if (skyMode == LIGHT_MODE_CONST) {
            int c = constNibbles & 0x0F;
            System.arraycopy(SKY_LIGHT_2048_HEADER_NBT, 0, out, outPos, SKY_LIGHT_2048_HEADER_NBT.length);
            outPos += SKY_LIGHT_2048_HEADER_NBT.length;
            Arrays.fill(out, outPos, outPos + 2048, (byte) ((c << 4) | c));
            outPos += 2048;
        } else if (skyMode == LIGHT_MODE_GRAY_PLANES) {
            System.arraycopy(SKY_LIGHT_2048_HEADER_NBT, 0, out, outPos, SKY_LIGHT_2048_HEADER_NBT.length);
            outPos += SKY_LIGHT_2048_HEADER_NBT.length;
            pos = decodeGrayPlanesDirectToPacked2048(buf, pos, limit, CURVE_TO_YZX[CURVE_Y_H2], out, outPos);
            outPos += 2048;
        } else {
            if (pos + 2048 > limit) throw new CorruptRegionEntryException("CBIT: Truncated raw SkyLight");
            System.arraycopy(SKY_LIGHT_2048_HEADER_NBT, 0, out, outPos, SKY_LIGHT_2048_HEADER_NBT.length);
            outPos += SKY_LIGHT_2048_HEADER_NBT.length;
            System.arraycopy(buf, pos, out, outPos, 2048);
            outPos += 2048;
            pos += 2048;
        }

        if (blkMode == LIGHT_MODE_ABSENT) {
            // Absent (0 bytes)
        } else if (blkMode == LIGHT_MODE_CONST) {
            int c = (constNibbles >>> 4) & 0x0F;
            System.arraycopy(BLOCK_LIGHT_2048_HEADER_NBT, 0, out, outPos, BLOCK_LIGHT_2048_HEADER_NBT.length);
            outPos += BLOCK_LIGHT_2048_HEADER_NBT.length;
            Arrays.fill(out, outPos, outPos + 2048, (byte) ((c << 4) | c));
            outPos += 2048;
        } else if (blkMode == LIGHT_MODE_GRAY_PLANES) {
            System.arraycopy(BLOCK_LIGHT_2048_HEADER_NBT, 0, out, outPos, BLOCK_LIGHT_2048_HEADER_NBT.length);
            outPos += BLOCK_LIGHT_2048_HEADER_NBT.length;
            decodeGrayPlanesDirectToPacked2048(buf, pos, limit, CURVE_TO_YZX[CURVE_H3], out, outPos);
            outPos += 2048;
        } else {
            if (pos + 2048 > limit) throw new CorruptRegionEntryException("CBIT: Truncated raw BlockLight");
            System.arraycopy(BLOCK_LIGHT_2048_HEADER_NBT, 0, out, outPos, BLOCK_LIGHT_2048_HEADER_NBT.length);
            outPos += BLOCK_LIGHT_2048_HEADER_NBT.length;
            System.arraycopy(buf, pos, out, outPos, 2048);
            outPos += 2048;
        }

        out[outPos++] = Nbt.TAG_END; // End section compound
        return outPos;
    }

    private static int decodeAndWriteSectionBiomePayloadToBuf(
            byte[] buf,
            int pos,
            int limit,
            int bLen,
            byte[][] dictBiomeStrNbt,
            byte[][] dictSingleBiomeCompoundNbt,
            ScratchArena arena,
            byte[] out,
            int outPos
    ) throws CorruptRegionEntryException {
        if (bLen > BIOME_CELLS) {
            throw new CorruptRegionEntryException("CBIT T7: Invalid biome palette length " + bLen);
        }
        if (bLen == 1) {
            int bDictId = readVarInt(buf, pos, limit);
            pos += varIntLen(bDictId);
            if (bDictId < 0 || bDictId >= dictSingleBiomeCompoundNbt.length) {
                throw new CorruptRegionEntryException("CBIT T7: Biome dictId " + bDictId + " out of range");
            }
            byte[] bmBytes = dictSingleBiomeCompoundNbt[bDictId];
            System.arraycopy(bmBytes, 0, out, outPos, bmBytes.length);
            arena.biomePaletteDictIds[0] = pos;
            return outPos + bmBytes.length;
        }
        int[] bIds = arena.biomePaletteDictIds;
        for (int i = 0; i < bLen; i++) {
            int bDictId = readVarInt(buf, pos, limit);
            pos += varIntLen(bDictId);
            if (bDictId < 0 || bDictId >= dictBiomeStrNbt.length) {
                throw new CorruptRegionEntryException("CBIT T7: Biome dictId " + bDictId + " out of range");
            }
            bIds[i] = bDictId;
        }
        int runCount = readVarInt(buf, pos, limit);
        pos += varIntLen(runCount);
        int[] cells = arena.biomeCells;
        int cursor = 0;
        if (bLen <= 8) {
            for (int r = 0; r < runCount; r++) {
                if (pos >= limit) throw new CorruptRegionEntryException("CBIT: Truncated 1B biome RLE");
                int packed = buf[pos++] & 0xFF;
                int pIdx = (packed >>> 5) & 7;
                int len = (packed & 0x1F) + 1;
                if (pIdx >= bLen || cursor + len > BIOME_CELLS) {
                    throw new CorruptRegionEntryException("CBIT: Biome RLE cursor overflow");
                }
                for (int k = 0; k < len; k++) {
                    int yzx = ((cursor & 3) << 4) | (cursor & 0x0C) | ((cursor >>> 4) & 3);
                    cells[yzx] = pIdx;
                    cursor++;
                }
            }
        } else {
            for (int r = 0; r < runCount; r++) {
                int pIdx = readVarInt(buf, pos, limit);
                pos += varIntLen(pIdx);
                if (pos >= limit) throw new CorruptRegionEntryException("CBIT: Truncated biome RLE");
                int len = (buf[pos++] & 0xFF) + 1;
                if (pIdx < 0 || pIdx >= bLen || cursor + len > BIOME_CELLS) {
                    throw new CorruptRegionEntryException("CBIT: Biome RLE cursor overflow");
                }
                for (int k = 0; k < len; k++) {
                    int yzx = ((cursor & 3) << 4) | (cursor & 0x0C) | ((cursor >>> 4) & 3);
                    cells[yzx] = pIdx;
                    cursor++;
                }
            }
        }
        System.arraycopy(BIOMES_PALETTE_PREFIX_NBT, 0, out, outPos, BIOMES_PALETTE_PREFIX_NBT.length);
        outPos += BIOMES_PALETTE_PREFIX_NBT.length;
        writeIntBEToBuf(out, outPos, bLen);
        outPos += 4;
        for (int i = 0; i < bLen; i++) {
            byte[] sb = dictBiomeStrNbt[bIds[i]];
            System.arraycopy(sb, 0, out, outPos, sb.length);
            outPos += sb.length;
        }
        int bits = BiomePaletteSection.biomeBitsPerEntry(bLen);
        int entriesPerLong = 64 / bits;
        int numLongs = (BIOME_CELLS + entriesPerLong - 1) / entriesPerLong;
        System.arraycopy(DATA_LONG_ARRAY_PREFIX_NBT, 0, out, outPos, DATA_LONG_ARRAY_PREFIX_NBT.length);
        outPos += DATA_LONG_ARRAY_PREFIX_NBT.length;
        writeIntBEToBuf(out, outPos, numLongs);
        outPos += 4;
        int cell = 0;
        for (int w = 0; w < numLongs; w++) {
            long word = 0L;
            int lim = Math.min(entriesPerLong, BIOME_CELLS - cell);
            for (int s = 0; s < lim; s++) {
                word |= ((long) cells[cell++]) << (s * bits);
            }
            writeLongBEToBuf(out, outPos, word);
            outPos += 8;
        }
        out[outPos++] = Nbt.TAG_END;
        bIds[0] = pos;
        return outPos;
    }

    private static int decodeGrayPlanesDirectToPacked2048(
            byte[] buf, int pos, int limit, int[] curveToYzx, byte[] dst, int dstOffset
    ) throws CorruptRegionEntryException {
        if (pos >= limit) throw new CorruptRegionEntryException("CBIT: Truncated Gray light planeModes8 header");
        int planeModes8 = buf[pos++] & 0xFF;
        Arrays.fill(dst, dstOffset, dstOffset + 2048, (byte) 0);
        int uniformXorNib = 0;
        for (int plane = 0; plane < 4; plane++) {
            int mode = (planeModes8 >>> (plane << 1)) & 3;
            int xorMask = (1 << (plane + 1)) - 1;
            if (mode == 0) {
                // All-0 plane
            } else if (mode == 1) {
                uniformXorNib ^= xorMask;
            } else if (mode == 2) {
                int runCount = readVarInt(buf, pos, limit);
                pos += varIntLen(runCount);
                int cursor = 0;
                for (int r = 0; r < runCount; r++) {
                    if (pos >= limit) throw new CorruptRegionEntryException("CBIT: Truncated Gray light run");
                    int tok = buf[pos++] & 0xFF;
                    int adjGap = (tok >>> 4) & 0x0F;
                    int lenMinusOne = tok & 0x0F;
                    if (adjGap == 15) {
                        int extra = readVarInt(buf, pos, limit);
                        pos += varIntLen(extra);
                        adjGap += extra;
                    }
                    if (lenMinusOne == 15) {
                        int extra = readVarInt(buf, pos, limit);
                        pos += varIntLen(extra);
                        lenMinusOne += extra;
                    }
                    cursor += (r == 0) ? adjGap : (adjGap + 1);
                    int runLen = lenMinusOne + 1;
                    if (cursor < 0 || cursor + runLen > SECTION_VOXELS) {
                        throw new CorruptRegionEntryException("CBIT T6: Gray light run cursor overflow");
                    }
                    for (int k = 0; k < runLen; k++) {
                        int yzx = curveToYzx[cursor++];
                        dst[dstOffset + (yzx >>> 1)] ^= (byte) (xorMask << ((yzx & 1) << 2));
                    }
                }
            } else {
                if (pos + 512 > limit) {
                    throw new CorruptRegionEntryException("CBIT: Truncated 512B Gray light bitmap");
                }
                for (int b = 0; b < 512; b++) {
                    int mask8 = buf[pos + b] & 0xFF;
                    if (mask8 == 0) continue;
                    int base = b << 3;
                    for (int k = 0; k < 8; k++) {
                        if ((mask8 & (1 << k)) != 0) {
                            int yzx = curveToYzx[base + k];
                            dst[dstOffset + (yzx >>> 1)] ^= (byte) (xorMask << ((yzx & 1) << 2));
                        }
                    }
                }
                pos += 512;
            }
        }
        if (uniformXorNib != 0) {
            long xorWord = (((uniformXorNib << 4) | uniformXorNib) & 0xFFL) * 0x0101010101010101L;
            for (int off = 0; off < 2048; off += 8) {
                long prev = (long) LcaRegionReader.LONG_BE_VIEW.get(dst, dstOffset + off);
                LcaRegionReader.LONG_BE_VIEW.set(dst, dstOffset + off, prev ^ xorWord);
            }
        }
        return pos;
    }
}
