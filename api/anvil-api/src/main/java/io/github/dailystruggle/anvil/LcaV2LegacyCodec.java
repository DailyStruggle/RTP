package io.github.dailystruggle.anvil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quarantined legacy {@code 0x0002} codec holding the version-pinned {@code STATIC_CORE_*}
 * lookup tables, pre-baked vanilla NBT byte slices, and legacy Slab-Local Micro-Palette /
 * {@code ChunkPatchTail} encoders and decoders.
 *
 * <p>Format version {@code 0x0003} ({@code LCA-ICD-3.0}) never reads or writes any
 * {@code STATIC_CORE_*} token; this class exists exclusively to preserve lossless backward-compatible
 * reading of existing {@code 0x0002} archives and atomic {@code 0x0002 -> 0x0003} upgrades.
 *
 * @governedBy anvil-api-ADR-003, anvil-api-ADR-006, ADR-115
 * @trace REQ-RTP-S-004, REQ-RTP-S-005
 */
final class LcaV2LegacyCodec {

    private LcaV2LegacyCodec() {}

    /**
     * 160-entry legacy {@code 0x0002} Static Core Base Block Name table
     * ({@code 0x80 | coreId} for {@code 0..127}, {@code 0x60 | (coreId - 128)} for {@code 128..159}).
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
     * Legacy {@code 0x0002} 1-byte property suffix table ({@code 0x80 | propId}, 128 entries {@code 0..127}).
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
    static final byte[][] STATIC_CORE_STATE_NBT_BYTES_MODERN = new byte[STATIC_CORE_STATES.length][];
    static final byte[][] STATIC_CORE_PROP_NBT_BYTES_MODERN = new byte[STATIC_CORE_PROP_SUFFIXES.length][];
    static final byte[][] STATIC_CORE_STATE_PROP_NBT_CACHE_MODERN = new byte[STATIC_CORE_STATES.length * 128][];
    static final byte[][] STATIC_CORE_BIOME_STRING_NBT_BYTES = new byte[STATIC_CORE_BIOMES.length][];
    static final byte[][] STATIC_CORE_SINGLE_BIOME_COMPOUND_NBT_BYTES = new byte[STATIC_CORE_BIOMES.length][];

    private static volatile byte[] STATIC_CORE_LIGHT_V1_CACHE;

    static {
        for (int i = 0; i < STATIC_CORE_PROP_SUFFIXES.length; i++) {
            STATIC_CORE_PROP_TO_ID.put(STATIC_CORE_PROP_SUFFIXES[i], i);
            STATIC_CORE_PROP_NBT_BYTES[i] = LcaSectionCodec.encodePropSuffixToNbtBytes(STATIC_CORE_PROP_SUFFIXES[i], false);
            STATIC_CORE_PROP_NBT_BYTES_MODERN[i] = LcaSectionCodec.encodePropSuffixToNbtBytes(STATIC_CORE_PROP_SUFFIXES[i], true);
        }
        for (int i = 0; i < STATIC_CORE_STATES.length; i++) {
            String s = STATIC_CORE_STATES[i];
            String base = LcaSectionCodec.stripBlockProperties(s);
            STATIC_CORE_BASE_NAMES[i] = base;
            STATIC_CORE_SINGLETON_PALETTES[i] = List.of(base);
            STATIC_CORE_STATE_TO_ID.put(s, i);
            STATIC_CORE_STATE_NBT_BYTES[i] = LcaSectionCodec.encodeCanonicalStateToNbtCompoundBytes(base, false);
            STATIC_CORE_STATE_NBT_BYTES_MODERN[i] = LcaSectionCodec.encodeCanonicalStateToNbtCompoundBytes(base, true);
        }
        for (int i = 0; i < STATIC_CORE_BIOMES.length; i++) {
            String b = STATIC_CORE_BIOMES[i];
            STATIC_CORE_SINGLETON_BIOMES[i] = List.of(b);
            STATIC_CORE_BIOME_TO_ID.put(b, i);
            byte[] strBytes = LcaSectionCodec.encodeNbtStringPayloadBytes(b);
            STATIC_CORE_BIOME_STRING_NBT_BYTES[i] = strBytes;
            STATIC_CORE_SINGLE_BIOME_COMPOUND_NBT_BYTES[i] = LcaSectionCodec.encodeSingleBiomeCompoundNbtBytes(strBytes);
        }
    }

    static byte[] staticCoreLightV1Table() {
        byte[] cached = STATIC_CORE_LIGHT_V1_CACHE;
        if (cached != null) {
            return cached;
        }
        byte[] tbl = new byte[STATIC_CORE_STATES.length];
        for (int i = 0; i < STATIC_CORE_STATES.length; i++) {
            tbl[i] = LcaV3Codec.classifyLightV1(STATIC_CORE_STATES[i]);
        }
        STATIC_CORE_LIGHT_V1_CACHE = tbl;
        return tbl;
    }

    /**
     * Encodes a legacy {@code 0x0002} Slab-Local Micro-Palette using {@code STATIC_CORE_*} tokens.
     */
    static byte[] encodeSlabLocalPaletteV2(
            List<String> localStates,
            int[] localStateGlobalIds,
            List<String> localBiomes,
            List<LcaSectionCodec.BiomeTemplate> biomeTemplates
    ) {
        LcaSectionCodec.ScratchArena arena = LcaSectionCodec.scratch();
        int tmplCount = biomeTemplates == null ? 0 : biomeTemplates.size();
        int maxNeed = 32 + (localStates.size() * 64) + (localBiomes.size() * 64);
        for (int i = 0; i < localStates.size(); i++) {
            maxNeed += localStates.get(i).length() * 3;
        }
        for (int i = 0; i < localBiomes.size(); i++) {
            maxNeed += localBiomes.get(i).length() * 3;
        }
        for (int t = 0; t < tmplCount; t++) {
            LcaSectionCodec.BiomeTemplate tmpl = biomeTemplates.get(t);
            maxNeed += LcaSectionCodec.maxEncodedBiomeBytes(tmpl.biomeIds, tmpl.rawBiomeDataLongs);
        }
        byte[] buf = maxNeed <= arena.byteStaging.length ? arena.byteStaging : new byte[maxNeed];
        int pos = writeSlabLocalEntryListV2(buf, 0, localStates, STATIC_CORE_STATE_TO_ID, true);
        pos = writeSlabLocalEntryListV2(buf, pos, localBiomes, STATIC_CORE_BIOME_TO_ID, false);

        pos = LcaSectionCodec.writeVarIntToBuf(buf, pos, tmplCount);
        for (int t = 0; t < tmplCount; t++) {
            LcaSectionCodec.BiomeTemplate tmpl = biomeTemplates.get(t);
            pos = LcaSectionCodec.encodeBiomesToBuf(buf, pos, tmpl.biomeIds, tmpl.rawBiomeDataLongs, -1, arena);
        }

        int propStateCount = 0;
        for (int i = 0; i < localStates.size(); i++) {
            if (localStates.get(i).indexOf('[') >= 0) propStateCount++;
        }
        pos = LcaSectionCodec.writeVarIntToBuf(buf, pos, propStateCount);
        for (int i = 0; i < localStates.size(); i++) {
            String s = localStates.get(i);
            int bracket = s.indexOf('[');
            if (bracket >= 0) {
                pos = LcaSectionCodec.writeVarIntToBuf(buf, pos, i);
                String propSuffix = s.substring(bracket);
                Integer propId = STATIC_CORE_PROP_TO_ID.get(propSuffix);
                if (propId != null) {
                    buf[pos++] = (byte) (0x80 | (propId & 0x7F));
                } else {
                    buf[pos++] = 0x40;
                    pos = LcaSectionCodec.writeVarIntToBuf(buf, pos, localStateGlobalIds[i]);
                }
            }
        }
        return Arrays.copyOf(buf, pos);
    }

    static int writeSlabLocalEntryListV2(
            byte[] buf, int pos, List<String> entries, Map<String, Integer> coreMap, boolean stripProps
    ) {
        pos = LcaSectionCodec.writeVarIntToBuf(buf, pos, entries.size());
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
                pos = LcaSectionCodec.writeVarIntToBuf(buf, pos, utf.length);
                System.arraycopy(utf, 0, buf, pos, utf.length);
                pos += utf.length;
            }
        }
        return pos;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static LcaSectionCodec.SlabLocalPalette decodeSlabLocalPaletteV2(
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
        int stateCount = LcaSectionCodec.readVarInt(frame, pos, limit);
        pos += LcaSectionCodec.varIntLen(stateCount);
        List<String> baseNames = new ArrayList<>(stateCount);
        List<List<String>> singletons = new ArrayList<>(stateCount);
        pos = readSlabLocalEntryListV2(
                frame, pos, limit, stateCount, STATIC_CORE_BASE_NAMES, STATIC_CORE_SINGLETON_PALETTES, baseNames, singletons);

        if (!needBiomes && !needCanonicalStates) {
            return new LcaSectionCodec.SlabLocalPalette(
                    Collections.emptyList(),
                    baseNames,
                    singletons,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList());
        }

        int biomeCount = LcaSectionCodec.readVarInt(frame, pos, limit);
        pos += LcaSectionCodec.varIntLen(biomeCount);
        String[] biomeArr = new String[biomeCount];
        List<String>[] singletonBiomeArr = new List[biomeCount];
        pos = readSlabLocalEntryArraysV2(
                frame, pos, limit, biomeCount, STATIC_CORE_BIOMES, STATIC_CORE_SINGLETON_BIOMES, biomeArr, singletonBiomeArr);
        List<String> biomes = Arrays.asList(biomeArr);
        List<List<String>> singletonBiomes = Arrays.asList(singletonBiomeArr);

        int tmplCount = LcaSectionCodec.readVarInt(frame, pos, limit);
        pos += LcaSectionCodec.varIntLen(tmplCount);
        List<LcaSectionCodec.BiomeTemplate> biomeTemplates = Collections.emptyList();
        if (tmplCount > 0) {
            LcaSectionCodec.BiomeTemplate[] tmplArr = needBiomes ? new LcaSectionCodec.BiomeTemplate[tmplCount] : null;
            LcaSectionCodec.ScratchArena arena = LcaSectionCodec.scratch();
            for (int t = 0; t < tmplCount; t++) {
                int bLen = LcaSectionCodec.readVarInt(frame, pos, limit);
                pos += LcaSectionCodec.varIntLen(bLen);
                if (needBiomes && bLen == 1) {
                    int bDictId = LcaSectionCodec.readVarInt(frame, pos, limit);
                    pos += LcaSectionCodec.varIntLen(bDictId);
                    if (bDictId < 0 || bDictId >= biomeCount) {
                        throw new CorruptRegionEntryException("CBIT T7: Biome dictId " + bDictId + " out of range");
                    }
                    tmplArr[t] = new LcaSectionCodec.BiomeTemplate(null, null, singletonBiomeArr[bDictId], null);
                } else {
                    List<String> tmplPal = needBiomes
                            ? new ArrayList<>(bLen == LcaSectionCodec.BIOME_4096_MARKER ? 8 : bLen)
                            : Collections.emptyList();
                    pos = LcaSectionCodec.decodeSectionBiomePayload(frame, pos, limit, bLen, biomes, !needBiomes, tmplPal, arena);
                    if (needBiomes) {
                        long[] packedLongs = arena.lastDecodedBiomePaletteLen > 1
                                ? LcaSectionCodec.packDecodedBiomesFromArena(arena)
                                : null;
                        tmplArr[t] = new LcaSectionCodec.BiomeTemplate(null, null, tmplPal, packedLongs);
                    }
                }
            }
            if (needBiomes) {
                biomeTemplates = Arrays.asList(tmplArr);
            }
        }

        if (!needCanonicalStates) {
            return new LcaSectionCodec.SlabLocalPalette(
                    Collections.emptyList(), baseNames, singletons, biomes, singletonBiomes, biomeTemplates);
        }

        List<String> canonicalStates = new ArrayList<>(baseNames);
        int propCount = LcaSectionCodec.readVarInt(frame, pos, limit);
        pos += LcaSectionCodec.varIntLen(propCount);
        for (int k = 0; k < propCount; k++) {
            int stateIdx = LcaSectionCodec.readVarInt(frame, pos, limit);
            pos += LcaSectionCodec.varIntLen(stateIdx);
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
                int globalId = LcaSectionCodec.readVarInt(frame, pos, limit);
                pos += LcaSectionCodec.varIntLen(globalId);
                if (regionCanonicalStates == null || globalId < 0 || globalId >= regionCanonicalStates.size()) {
                    throw new CorruptRegionEntryException("CBIT T7: Slab-local globalStateId " + globalId + " out of range");
                }
                canonicalStates.set(stateIdx, regionCanonicalStates.get(globalId));
            }
        }
        return new LcaSectionCodec.SlabLocalPalette(
                canonicalStates, baseNames, singletons, biomes, singletonBiomes, biomeTemplates);
    }

    @SuppressWarnings("java:S4276")
    static int[] decodeSlabLocalPaletteRuntimeIdsV2(
            byte[] frame,
            int fOff,
            int startRel,
            int limitRel,
            int[] dictIdToRuntimeIdOrNull,
            java.util.function.Function<String, Integer> fallbackRuntimeLookup
    ) throws CorruptRegionEntryException {
        final String globalPrefix = "\u0000GID:";
        List<String> syntheticGlobals = new java.util.AbstractList<>() {
            @Override
            public String get(int index) {
                return globalPrefix + index;
            }

            @Override
            public int size() {
                return dictIdToRuntimeIdOrNull != null
                        ? Math.max(dictIdToRuntimeIdOrNull.length, LcaSectionBacking.MAX_STATE_ID_19)
                        : LcaSectionBacking.MAX_STATE_ID_19;
            }
        };
        LcaSectionCodec.SlabLocalPalette localPal = decodeSlabLocalPaletteV2(
                frame, fOff, startRel, limitRel, true, false, syntheticGlobals);
        int stateCount = localPal.canonicalStates.size();
        int[] out = new int[stateCount];
        for (int i = 0; i < stateCount; i++) {
            String canon = localPal.canonicalStates.get(i);
            if (canon.startsWith(globalPrefix)) {
                int gId = Integer.parseInt(canon.substring(globalPrefix.length()));
                if (dictIdToRuntimeIdOrNull != null && gId >= 0 && gId < dictIdToRuntimeIdOrNull.length) {
                    out[i] = dictIdToRuntimeIdOrNull[gId];
                }
            } else if (fallbackRuntimeLookup != null) {
                Integer mapped = fallbackRuntimeLookup.apply(canon);
                out[i] = mapped != null ? mapped.intValue() : 0;
            }
        }
        return out;
    }

    static LcaSectionCodec.SlabLocalPaletteNbt decodeSlabLocalPaletteForNbtV2(
            byte[] frame,
            int fOff,
            int startRel,
            int limitRel,
            LcaSectionCodec.ParsedHeaderAndDict regionMeta,
            LcaSectionCodec.ScratchArena arena,
            boolean modernKeys
    ) throws CorruptRegionEntryException {
        int pos = fOff + startRel;
        int limit = fOff + limitRel;
        int stateCount = LcaSectionCodec.readVarInt(frame, pos, limit);
        pos += LcaSectionCodec.varIntLen(stateCount);
        if (stateCount < 0 || stateCount > LcaSectionCodec.SECTION_VOXELS) {
            throw new CorruptRegionEntryException("CBIT T5: Invalid slab-local stateCount " + stateCount);
        }
        byte[][] stateNbt = new byte[stateCount][];
        boolean buildV3Light = regionMeta != null && regionMeta.formatVersion >= LcaRegionReader.LCA_FORMAT_VERSION_V3;
        byte[] stateLightV1 = buildV3Light ? new byte[stateCount] : null;
        byte[] coreLightTbl = buildV3Light ? staticCoreLightV1Table() : null;
        String[] stateBaseNamesForLight = buildV3Light ? new String[stateCount] : null;
        int[] stateCoreIds = arena.sectionPaletteDictIds;
        byte[][] coreStateTable = modernKeys ? STATIC_CORE_STATE_NBT_BYTES_MODERN : STATIC_CORE_STATE_NBT_BYTES;
        for (int i = 0; i < stateCount; i++) {
            if (pos >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated slab-local palette");
            int tag = frame[pos++] & 0xFF;
            if ((tag & 0x80) != 0 || (tag >= 0x60 && tag < 0x80)) {
                int coreId = ((tag & 0x80) != 0) ? (tag & 0x7F) : (128 + (tag & 0x1F));
                if (coreId >= coreStateTable.length) {
                    throw new CorruptRegionEntryException("CBIT T5: Invalid static core token " + coreId);
                }
                stateNbt[i] = coreStateTable[coreId];
                stateCoreIds[i] = coreId;
                if (buildV3Light) {
                    stateLightV1[i] = coreLightTbl[coreId];
                    stateBaseNamesForLight[i] = STATIC_CORE_STATES[coreId];
                }
            } else {
                int utfLen = LcaSectionCodec.readVarInt(frame, pos, limit);
                pos += LcaSectionCodec.varIntLen(utfLen);
                if (utfLen < 0 || pos + utfLen > limit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated slab-local string");
                }
                stateNbt[i] = LcaSectionCodec.encodeBaseStateBytesFromFrame(frame, pos, utfLen, tag == 0x40, modernKeys);
                stateCoreIds[i] = -1;
                if (buildV3Light) {
                    String baseName = (tag == 0x40 ? "minecraft:" : "")
                            + new String(frame, pos, utfLen, StandardCharsets.UTF_8);
                    stateLightV1[i] = LcaV3Codec.classifyLightV1(baseName);
                    stateBaseNamesForLight[i] = baseName;
                }
                pos += utfLen;
            }
        }

        int biomeCount = LcaSectionCodec.readVarInt(frame, pos, limit);
        pos += LcaSectionCodec.varIntLen(biomeCount);
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
                int utfLen = LcaSectionCodec.readVarInt(frame, pos, limit);
                pos += LcaSectionCodec.varIntLen(utfLen);
                if (utfLen < 0 || pos + utfLen > limit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated slab-local biome string");
                }
                byte[] strBytes = LcaSectionCodec.encodeNbtStringPayloadBytesFromFrame(frame, pos, utfLen, tag == 0x40);
                biomeStrNbt[i] = strBytes;
                singleBiomeNbt[i] = LcaSectionCodec.encodeSingleBiomeCompoundNbtBytes(strBytes);
                pos += utfLen;
            }
        }

        int tmplCount = LcaSectionCodec.readVarInt(frame, pos, limit);
        pos += LcaSectionCodec.varIntLen(tmplCount);
        if (tmplCount < 0 || tmplCount > 64) {
            throw new CorruptRegionEntryException("CBIT T5: Invalid slab-local biome template count " + tmplCount);
        }
        byte[][] tmplNbt = tmplCount > 0 ? new byte[tmplCount][] : new byte[0][];
        for (int t = 0; t < tmplCount; t++) {
            int bLen = LcaSectionCodec.readVarInt(frame, pos, limit);
            pos += LcaSectionCodec.varIntLen(bLen);
            pos = LcaSectionCodec.decodeBiomePayloadToCompoundNbt(
                    frame, pos, limit, bLen, biomeStrNbt, singleBiomeNbt, arena, tmplNbt, t);
        }

        int propCount = LcaSectionCodec.readVarInt(frame, pos, limit);
        pos += LcaSectionCodec.varIntLen(propCount);
        byte[][] corePropTable = modernKeys ? STATIC_CORE_PROP_NBT_BYTES_MODERN : STATIC_CORE_PROP_NBT_BYTES;
        byte[][] coreStatePropCache = modernKeys ? STATIC_CORE_STATE_PROP_NBT_CACHE_MODERN : STATIC_CORE_STATE_PROP_NBT_CACHE;
        for (int k = 0; k < propCount; k++) {
            int stateIdx = LcaSectionCodec.readVarInt(frame, pos, limit);
            pos += LcaSectionCodec.varIntLen(stateIdx);
            if (stateIdx < 0 || stateIdx >= stateCount || pos >= limit) {
                throw new CorruptRegionEntryException("CBIT T5: Invalid slab-local property suffix index");
            }
            int tag = frame[pos++] & 0xFF;
            if ((tag & 0x80) != 0) {
                int propId = tag & 0x7F;
                if (propId >= corePropTable.length) {
                    throw new CorruptRegionEntryException("CBIT T5: Invalid static property suffix token " + propId);
                }
                int coreId = stateCoreIds[stateIdx];
                if (coreId >= 0) {
                    int cacheIdx = (coreId << 7) | propId;
                    byte[] cached = coreStatePropCache[cacheIdx];
                    if (cached == null) {
                        cached = LcaSectionCodec.combineBaseStateAndPropNbt(stateNbt[stateIdx], corePropTable[propId]);
                        coreStatePropCache[cacheIdx] = cached;
                    }
                    stateNbt[stateIdx] = cached;
                } else {
                    stateNbt[stateIdx] = LcaSectionCodec.combineBaseStateAndPropNbt(stateNbt[stateIdx], corePropTable[propId]);
                }
                if (buildV3Light) {
                    stateLightV1[stateIdx] = LcaV3Codec.classifyLightV1(
                            stateBaseNamesForLight[stateIdx] + STATIC_CORE_PROP_SUFFIXES[propId]);
                }
            } else {
                int globalId = LcaSectionCodec.readVarInt(frame, pos, limit);
                pos += LcaSectionCodec.varIntLen(globalId);
                if (regionMeta == null || globalId < 0 || globalId >= regionMeta.canonicalStates.size()) {
                    throw new CorruptRegionEntryException("CBIT T7: Slab-local globalStateId " + globalId + " out of range");
                }
                stateNbt[stateIdx] = regionMeta.stateNbtBytesAt(globalId, modernKeys);
                if (buildV3Light) {
                    stateLightV1[stateIdx] = regionMeta.getOrBuildStatePackedLightV1()[globalId];
                }
            }
        }
        return new LcaSectionCodec.SlabLocalPaletteNbt(stateNbt, biomeStrNbt, singleBiomeNbt, tmplNbt, stateLightV1);
    }

    static byte[] encodeChunkPatchTailBytesV2(
            int baseLocalStateCount,
            List<String> appendedStates,
            int[] appendedGlobalStateIds,
            List<LcaSectionCodec.SectionPatch> sectionPatches
    ) {
        LcaSectionCodec.ScratchArena arena = LcaSectionCodec.scratch();
        byte[] buf = arena.byteStaging;
        int pos = 0;

        List<String> appStates = appendedStates == null ? Collections.emptyList() : appendedStates;
        int appCount = appStates.size();
        if (appCount == 0) {
            pos = LcaSectionCodec.writeVarIntToBuf(buf, pos, 0);
        } else {
            pos = writeSlabLocalEntryListV2(buf, pos, appStates, STATIC_CORE_STATE_TO_ID, true);
            int propCount = 0;
            for (int i = 0; i < appCount; i++) {
                if (appStates.get(i).indexOf('[') >= 0) {
                    propCount++;
                }
            }
            pos = LcaSectionCodec.writeVarIntToBuf(buf, pos, propCount);
            for (int i = 0; i < appCount; i++) {
                String s = appStates.get(i);
                int bracket = s.indexOf('[');
                if (bracket >= 0) {
                    pos = LcaSectionCodec.writeVarIntToBuf(buf, pos, baseLocalStateCount + i);
                    String propSuffix = s.substring(bracket);
                    Integer propId = STATIC_CORE_PROP_TO_ID.get(propSuffix);
                    if (propId != null) {
                        buf[pos++] = (byte) (0x80 | (propId & 0x7F));
                    } else {
                        buf[pos++] = 0x40;
                        int gid = (appendedGlobalStateIds != null && i < appendedGlobalStateIds.length)
                                ? appendedGlobalStateIds[i]
                                : 0;
                        pos = LcaSectionCodec.writeVarIntToBuf(buf, pos, gid);
                    }
                }
            }
        }

        return LcaSectionCodec.finishChunkPatchTailEncoding(buf, pos, sectionPatches, arena);
    }

    static int decodeChunkPatchTailAppendedStatesV2(
            byte[] frame,
            int pPos,
            int payloadEnd,
            int appendedStateCount,
            boolean hasSlabLocalPalette,
            LcaSectionCodec.SlabLocalPalette localPal,
            List<String> regionCanonicalStates,
            boolean needCanonicalStates
    ) throws CorruptRegionEntryException {
        if (appendedStateCount == 0) {
            return pPos;
        }
        if (!hasSlabLocalPalette || localPal == null) {
            throw new CorruptRegionEntryException("CBIT T5: Appended micro-palette entries require hasSlabLocalPalette");
        }
        int baseCount = localPal.baseBlockNames.size();
        pPos = readSlabLocalEntryListV2(
                frame, pPos, payloadEnd, appendedStateCount,
                STATIC_CORE_BASE_NAMES, STATIC_CORE_SINGLETON_PALETTES,
                localPal.baseBlockNames, localPal.singletonBasePalettes);
        if (needCanonicalStates) {
            for (int i = 0; i < appendedStateCount; i++) {
                localPal.canonicalStates.add(localPal.baseBlockNames.get(baseCount + i));
            }
        }
        int appendedPropCount = LcaSectionCodec.readVarInt(frame, pPos, payloadEnd);
        pPos += LcaSectionCodec.varIntLen(appendedPropCount);
        if (appendedPropCount < 0 || appendedPropCount > appendedStateCount) {
            throw new CorruptRegionEntryException("CBIT T5: Invalid appendedPropCount " + appendedPropCount);
        }
        for (int k = 0; k < appendedPropCount; k++) {
            int stateIdx = LcaSectionCodec.readVarInt(frame, pPos, payloadEnd);
            pPos += LcaSectionCodec.varIntLen(stateIdx);
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
                int globalId = LcaSectionCodec.readVarInt(frame, pPos, payloadEnd);
                pPos += LcaSectionCodec.varIntLen(globalId);
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
        return pPos;
    }

    private static int readSlabLocalEntryArraysV2(
            byte[] frame,
            int pos,
            int limit,
            int count,
            String[] coreNames,
            List<String>[] coreSingletons,
            String[] namesOut,
            List<String>[] singletonsOut
    ) throws CorruptRegionEntryException {
        for (int i = 0; i < count; i++) {
            if (pos >= limit) throw new CorruptRegionEntryException("CBIT T5: Truncated slab-local palette");
            int tag = frame[pos++] & 0xFF;
            if ((tag & 0x80) != 0 || (tag >= 0x60 && tag < 0x80)) {
                int coreId = ((tag & 0x80) != 0) ? (tag & 0x7F) : (128 + (tag & 0x1F));
                if (coreId >= coreNames.length) {
                    throw new CorruptRegionEntryException("CBIT T5: Invalid static core token " + coreId);
                }
                namesOut[i] = coreNames[coreId];
                singletonsOut[i] = coreSingletons[coreId];
            } else {
                int utfLen = LcaSectionCodec.readVarInt(frame, pos, limit);
                pos += LcaSectionCodec.varIntLen(utfLen);
                if (pos + utfLen > limit) {
                    throw new CorruptRegionEntryException("CBIT T5: Truncated slab-local string");
                }
                String raw = new String(frame, pos, utfLen, StandardCharsets.UTF_8);
                pos += utfLen;
                String full = (tag == 0x40) ? ("minecraft:" + raw) : raw;
                namesOut[i] = full;
                singletonsOut[i] = List.of(full);
            }
        }
        return pos;
    }

    private static int readSlabLocalEntryListV2(
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
                int utfLen = LcaSectionCodec.readVarInt(frame, pos, limit);
                pos += LcaSectionCodec.varIntLen(utfLen);
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
}
