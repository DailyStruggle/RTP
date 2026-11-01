package io.github.dailystruggle.rtp.bukkit.maps;

import io.github.dailystruggle.mapsapi.BiomeColorSource;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bukkit-family {@link BiomeColorSource} implementation. Resolves a biome
 * name (uppercase, as stored by {@code MemoryShape.addBiomeLocation}) to
 * its native vanilla cartography colour by calling
 * {@code Biome#getMapColor()} reflectively. The reflection lets this class
 * compile on older Bukkit APIs that did not yet expose {@code getMapColor}
 * (1.19 / 1.20.x); on those platforms every biome falls back to the
 * built-in categorical palette in {@link BiomeColorSource#fallback(String)}.
 *
 * <p>Thread safety: results are cached in a {@link ConcurrentHashMap}; the
 * underlying reflective call is read-only against an enum / registry that
 * is immutable for the lifetime of the JVM. Safe to call from any thread
 * (REQ-RTP-S-005: no chunk I/O).
 *
 * <p>Returns {@code 0} for "no native colour available" so the caller's
 * categorical fallback kicks in. Catches every {@link Throwable} at the
 * outer boundary - this is best-effort diagnostic colour, never a path
 * that should crash a chart render.
 */
public final class BukkitBiomeColorSource implements BiomeColorSource {

    /**
     * Vanilla biome override table.
     *
     * <p>Rationale: {@code Biome#getMapColor()} returns Mojang's surface
     * map tint, which clusters tightly within biome families - all nether
     * biomes quantise to reddish bytes, all end biomes to pale-yellow,
     * and all forest-family overworld biomes (forest, dark forest, birch
     * forest, taiga, jungle, ...) collapse onto two or three near-identical
     * green bytes on a 128x128 map. The chart then reads as a single
     * colour per family, which is useless for distinguishing dark forest
     * from taiga, or warped forest from crimson forest.
     *
     * <p>For these well-known vanilla biome names we bypass
     * {@code getMapColor()} entirely and hand back a hand-picked,
     * perceptually-distinct hue. Modded biomes not in this table fall
     * through to {@code getMapColor()} and then to the categorical
     * fallback in {@link BiomeColorSource#fallback(String)}.
     *
     * <p>Keys are uppercase biome enum names (matching
     * {@code MemoryShape.addBiomeLocation}'s storage form).
     */
    private static final Map<String, Integer> DIMENSION_OVERRIDES = Map.ofEntries(
            // --- Nether ---
            Map.entry("NETHER_WASTES",        0x8B0000), // dark red
            Map.entry("CRIMSON_FOREST",       0xDC143C), // crimson
            Map.entry("WARPED_FOREST",        0x008B8B), // teal
            Map.entry("SOUL_SAND_VALLEY",     0x4682B4), // steel blue (soul fire)
            Map.entry("BASALT_DELTAS",        0xCD853F), // tan / burnt orange
            // --- End ---
            Map.entry("THE_END",              0xE5E4A8), // pale yellow
            Map.entry("END_HIGHLANDS",        0x32CD32), // lime green
            Map.entry("END_MIDLANDS",         0xFF8C00), // dark orange
            Map.entry("SMALL_END_ISLANDS",    0xDA70D6), // magenta
            Map.entry("END_BARRENS",          0x40E0D0), // cyan / turquoise
            // --- Overworld forest family (all quantise to similar greens via getMapColor) ---
            Map.entry("FOREST",               0x228B22), // forest green
            Map.entry("FLOWER_FOREST",        0xFF69B4), // hot pink
            Map.entry("BIRCH_FOREST",         0x98FB98), // pale green
            Map.entry("OLD_GROWTH_BIRCH_FOREST", 0x6B8E23), // olive
            Map.entry("DARK_FOREST",          0x2F4F2F), // very dark green
            Map.entry("PALE_GARDEN",          0xC0C8B0), // muted sage
            Map.entry("TAIGA",                0x4682B4), // muted blue-green
            Map.entry("SNOWY_TAIGA",          0xB0E0E6), // powder blue
            Map.entry("OLD_GROWTH_PINE_TAIGA", 0x556B2F), // dark olive
            Map.entry("OLD_GROWTH_SPRUCE_TAIGA", 0x3B5323), // deep spruce
            Map.entry("JUNGLE",               0x00FF7F), // spring green
            Map.entry("SPARSE_JUNGLE",        0xADFF2F), // yellow-green
            Map.entry("BAMBOO_JUNGLE",        0x9ACD32), // bamboo yellow-green
            Map.entry("MANGROVE_SWAMP",       0x8FBC8F), // dark sea green
            Map.entry("SWAMP",                0x6B4226), // brown swamp
            // --- Overworld plains / grass family ---
            Map.entry("PLAINS",               0x9ACD32), // soft lime
            Map.entry("SUNFLOWER_PLAINS",     0xFFD700), // gold
            Map.entry("MEADOW",               0xBDB76B), // dark khaki
            Map.entry("CHERRY_GROVE",         0xFFB7C5), // cherry blossom pink
            // --- Overworld savanna / badlands (yellow/orange cluster) ---
            Map.entry("SAVANNA",              0xDAA520), // goldenrod
            Map.entry("SAVANNA_PLATEAU",      0xBDB76B), // dark khaki
            Map.entry("WINDSWEPT_SAVANNA",    0xC0A040), // muted gold
            Map.entry("BADLANDS",             0xB22222), // firebrick
            Map.entry("ERODED_BADLANDS",      0xCD5C5C), // indian red
            Map.entry("WOODED_BADLANDS",      0x8B4513), // saddle brown
            // --- Overworld desert / beach (sand cluster) ---
            Map.entry("DESERT",               0xEEE8AA), // pale goldenrod
            Map.entry("BEACH",                0xF5DEB3), // wheat
            Map.entry("SNOWY_BEACH",          0xF0FFFF), // azure
            Map.entry("STONY_SHORE",          0x708090), // slate gray
            // --- Overworld cold / snowy (icy cluster) ---
            Map.entry("SNOWY_PLAINS",         0xF0F8FF), // alice blue
            Map.entry("ICE_SPIKES",           0xAFEEEE), // pale turquoise
            Map.entry("FROZEN_PEAKS",         0xE0FFFF), // light cyan
            Map.entry("JAGGED_PEAKS",         0xD3D3D3), // light gray
            Map.entry("STONY_PEAKS",          0xA9A9A9), // dark gray
            Map.entry("SNOWY_SLOPES",         0xFFFAFA), // snow
            Map.entry("GROVE",                0x8FBC8F), // dark sea green
            Map.entry("WINDSWEPT_HILLS",      0x9370DB), // medium purple
            Map.entry("WINDSWEPT_GRAVELLY_HILLS", 0x778899), // light slate gray
            Map.entry("WINDSWEPT_FOREST",     0x4B5320), // army green
            // --- Overworld ocean / river (blue cluster) ---
            Map.entry("OCEAN",                0x1E90FF), // dodger blue
            Map.entry("DEEP_OCEAN",           0x00008B), // dark blue
            Map.entry("COLD_OCEAN",           0x4169E1), // royal blue
            Map.entry("DEEP_COLD_OCEAN",      0x191970), // midnight blue
            Map.entry("FROZEN_OCEAN",         0xB0C4DE), // light steel blue
            Map.entry("DEEP_FROZEN_OCEAN",    0x483D8B), // dark slate blue
            Map.entry("LUKEWARM_OCEAN",       0x20B2AA), // light sea green
            Map.entry("DEEP_LUKEWARM_OCEAN",  0x008080), // teal
            Map.entry("WARM_OCEAN",           0x00CED1), // dark turquoise
            Map.entry("RIVER",                0x6495ED), // cornflower blue
            Map.entry("FROZEN_RIVER",         0xE6E6FA), // lavender
            // --- Overworld underground / cave ---
            Map.entry("DRIPSTONE_CAVES",      0xA0522D), // sienna
            Map.entry("LUSH_CAVES",           0x66CDAA), // medium aquamarine
            Map.entry("DEEP_DARK",            0x0A0F1E), // near-black blue
            Map.entry("MUSHROOM_FIELDS",      0xC71585)  // medium violet red
    );

    /** Cache of biome-name -> rgb (boxed so we can store the "0 / unknown" sentinel). */
    private final ConcurrentHashMap<String, Integer> cache = new ConcurrentHashMap<>();

    /**
     * {@code org.bukkit.block.Biome#getMapColor()} resolved once at
     * construction time; {@code null} on Bukkit / Paper / Folia versions
     * predating 1.21's {@code MapColor} accessor. When {@code null}, every
     * {@link #rgbFor(String)} call returns {@code 0} and the resolver
     * falls back to the categorical palette.
     */
    private final Method getMapColorMethod;

    /** {@code org.bukkit.Color#asRGB()} resolved once; never null under any supported runtime. */
    private final Method colorAsRgbMethod;

    /** {@code org.bukkit.block.Biome} class reference; null only on truly broken runtimes. */
    private final Class<?> biomeClass;

    public BukkitBiomeColorSource() {
        Method m = null;
        Method asRgb = null;
        Class<?> bc = null;
        try {
            bc = Class.forName("org.bukkit.block.Biome");
            try {
                m = bc.getMethod("getMapColor");
            } catch (NoSuchMethodException ignored) {
                // Older API; will return 0 from rgbFor and let the resolver fall back.
            }
            Class<?> colorClass = Class.forName("org.bukkit.Color");
            asRgb = colorClass.getMethod("asRGB");
        } catch (Throwable t) {
            // Leave fields null; rgbFor will short-circuit to 0.
        }
        this.biomeClass = bc;
        this.getMapColorMethod = m;
        this.colorAsRgbMethod = asRgb;
    }

    @Override
    public int rgbFor(String biomeName) {
        if (biomeName == null || biomeName.isEmpty()) return 0;
        // Dimension override takes precedence over Biome#getMapColor():
        // nether and end biomes all quantise to ~one map byte per
        // dimension via vanilla map colour, so we hand back our own
        // perceptually-distinct hues for the well-known nether/end biome
        // names. Overworld biomes are unaffected.
        Integer override = DIMENSION_OVERRIDES.get(biomeName);
        if (override != null) return override;
        if (biomeClass == null || getMapColorMethod == null || colorAsRgbMethod == null) {
            return 0;
        }
        Integer cached = cache.get(biomeName);
        if (cached != null) return cached;
        int resolved = resolveUncached(biomeName);
        // Cache even the 0 sentinel so we don't repeat the reflection on
        // every pixel of every render for an unknown / modded biome.
        cache.put(biomeName, resolved);
        return resolved;
    }

    private int resolveUncached(String biomeName) {
        Object biome = lookupBiome(biomeName);
        if (biome == null) return 0;
        try {
            Object color = getMapColorMethod.invoke(biome);
            if (color == null) return 0;
            Object rgb = colorAsRgbMethod.invoke(color);
            if (!(rgb instanceof Integer)) return 0;
            return ((Integer) rgb) & 0xFFFFFF;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Looks up a {@code Biome} value by uppercase name. Tries enum-style
     * {@code Biome.valueOf} first (covers Bukkit / Paper / Folia through
     * 1.21.0 where {@code Biome} is still an enum); falls back to
     * {@code Registry.BIOME.get(NamespacedKey.minecraft(lowercase))} on
     * runtimes where {@code Biome} is no longer an enum.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object lookupBiome(String biomeName) {
        // Enum.valueOf path (works while Biome is still an enum class).
        if (biomeClass != null && biomeClass.isEnum()) {
            try {
                return Enum.valueOf((Class<? extends Enum>) biomeClass, biomeName);
            } catch (Throwable ignored) {
                return null;
            }
        }
        // Registry path: org.bukkit.Registry.BIOME.get(NamespacedKey.minecraft(...))
        try {
            Class<?> regCls = Class.forName("org.bukkit.Registry");
            Object biomeRegistry = regCls.getField("BIOME").get(null);
            Class<?> keyCls = Class.forName("org.bukkit.NamespacedKey");
            Method mc = keyCls.getMethod("minecraft", String.class);
            Object key = mc.invoke(null, biomeName.toLowerCase(java.util.Locale.ROOT));
            Method get = regCls.getMethod("get", Class.forName("org.bukkit.NamespacedKey"));
            return get.invoke(biomeRegistry, key);
        } catch (Throwable t) {
            return null;
        }
    }
}
