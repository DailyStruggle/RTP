import json, copy, os, sys

# Path to the vanilla data generator's biome_parameters/minecraft/overworld.json
# report (run `java "-DbundlerMainClass=net.minecraft.data.Main" -jar
# <mojang-bundler>.jar --reports`). Pass as argv[1] or via the OVERWORLD_REPORT
# env var; defaults to the temp location used during development.
REPORT = (sys.argv[1] if len(sys.argv) > 1
          else os.environ.get("OVERWORLD_REPORT",
               os.path.join(os.environ.get("TEMP", "."), "mcdatagen", "generated",
                            "reports", "biome_parameters", "minecraft", "overworld.json")))
# Output is the datapack override, resolved relative to this script.
OUT = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "data",
                                    "minecraft", "dimension", "overworld.json"))

with open(REPORT, "r", encoding="utf-8") as f:
    report = json.load(f)

# Fraction of each vanilla flower_forest humidity span that is converted to the
# rainbow_forest gradient. The remaining (1 - RAINBOW_FRACTION) of the span stays
# vanilla minecraft:flower_forest, so only a SUBSET of flower forests are rainbow
# forests (they are now genuinely rarer, not a whole-biome substitution).
RAINBOW_FRACTION = 0.25

# Number of times the 12-colour rainbow gradient repeats WITHIN the rainbow
# sub-slice of a biome's humidity span. Humidity is a low-frequency field, so a
# higher repeat count cycles the same 12 leaf-colour biomes more times within the
# (now narrow) slice, producing many tight bands so the colours alternate very
# frequently and the gradient reads clearly. To tune the effect, change this value.
RAINBOW_REPEATS = 8

# Build the rainbow tier list. The 12-colour gradient (rainbow_forest_01..12) is
# cycled RAINBOW_REPEATS times and packed into the first RAINBOW_FRACTION of the
# humidity span (many narrow alternating bands); the remainder of the span is kept
# as vanilla minecraft:flower_forest so rainbow forests are only a subset.
def _rainbow_tiers(repeats=RAINBOW_REPEATS, fraction=RAINBOW_FRACTION):
    bands = 12 * repeats
    tiers = [
        ("leaf:rainbow_forest_%02d" % ((k % 12) + 1),
         fraction * k / float(bands),
         fraction * (k + 1) / float(bands))
        for k in range(bands)
    ]
    if fraction < 1.0:
        tiers.append(("minecraft:flower_forest", fraction, 1.0))
    return tiers

# Maple Forest is a WARM-palette cousin of the rainbow set: 8 leaf:maple_forest_NN
# biomes whose foliage_color steps across the warm arc only (deep red -> orange ->
# amber -> gold; see tools/build_maple_biomes.py). Same banding trick as the
# rainbow tiers -- cycle the 8-colour warm gradient MAPLE_REPEATS times across the
# biome's humidity span so the autumn canopy alternates in narrow strips.
MAPLE_COLORS = 8
MAPLE_REPEATS = 3

def _maple_tiers(repeats=MAPLE_REPEATS):
    bands = MAPLE_COLORS * repeats
    return [
        ("leaf:maple_forest_%02d" % ((k % MAPLE_COLORS) + 1), k / float(bands), (k + 1) / float(bands))
        for k in range(bands)
    ]

SPLITS = {
    # dark_forest humidity span is the whole top vanilla humidity band
    # ([0.3, 1.0]). Humidity noise rarely peaks high, so an even-thirds split
    # pinned dark_forest_core (the special-item heart biome) to the rarest top
    # third, making it almost unfindable. Instead use an UNEVEN split: body
    # takes the wide, most-commonly-sampled low band; core takes a generous
    # mid-to-high band so it is "relatively rare" yet reachable within a ~20k
    # RTP scan; edge is the thin rarest top sliver.
    "minecraft:dark_forest": [
        ("leaf:dark_forest_body", 0.0, 0.4),
        ("leaf:dark_forest_core", 0.4, 0.85),
        ("leaf:dark_forest_edge", 0.85, 1.0),
    ],
    "minecraft:taiga": [
        ("leaf:taiga_edge", 0.0, 1.0 / 3.0),
        ("leaf:taiga_body", 1.0 / 3.0, 2.0 / 3.0),
        ("leaf:taiga_core", 2.0 / 3.0, 1.0),
    ],
    # Plain forest gradient: mirror the dark_forest edge/body/core humidity-thirds
    # split, but the forest stays an OPEN (non-roofed) canopy -- trees are
    # sporadic enough to let light through at every tier. forest_body is pure
    # vanilla forest (vanilla trees_birch_and_oak_leaf_litter selector); edge is
    # sparse small oaks/birch, core is taller oak_large-weighted but still low
    # count so it never closes into a roof.
    "minecraft:forest": [
        ("leaf:forest_edge", 0.0, 1.0 / 3.0),
        ("leaf:forest_body", 1.0 / 3.0, 2.0 / 3.0),
        ("leaf:forest_core", 2.0 / 3.0, 1.0),
    ],
    # "Rainbow flower forest": split each vanilla flower_forest parameter point's
    # humidity span into RAINBOW_REPEATS cycles of the 12 leaf:rainbow_forest_NN
    # biomes (each differs only in foliage_color, a gentle 12-step hue gradient).
    # Because humidity is a continuous low-frequency field, one pass renders as
    # wide colour strips; cycling the gradient several times yields many narrow
    # bands so the colours alternate frequently (a tighter rainbow). See
    # tools/build_rainbow_biomes.py.
    "minecraft:flower_forest": _rainbow_tiers(),
    # Fun Content Backlog Tier 1 "flavored biomes": each fully replaces its
    # vanilla derivative across the whole humidity span (single tier 0.0..1.0),
    # the same whole-biome substitution pattern used for forest/taiga. JSON-only,
    # vanilla features/palette reused (see FUN-CONTENT-BACKLOG.md).
    "minecraft:meadow": [("leaf:alpine_meadow", 0.0, 1.0)],
    # Maple Forest: warm multicolor variant set (leaf:maple_forest_01..08), the
    # warm-palette cousin of the rainbow_forest split. Cycles the 8-colour warm
    # gradient across birch_forest's humidity span (see _maple_tiers).
    "minecraft:birch_forest": _maple_tiers(),
    "minecraft:cherry_grove": [("leaf:jacaranda_grove", 0.0, 1.0)],
    "minecraft:old_growth_pine_taiga": [("leaf:redwood_grove", 0.0, 1.0)],
    # Fun Content Backlog Tier 2 whole-biome substitutions. Same pattern as the
    # Tier 1 entries above: each fully replaces its vanilla derivative across the
    # whole humidity span. Boreal Shield supplants snowy_taiga (glacier-swept
    # granite + sparse spruce); Bayou supplants the rare mangrove_swamp
    # (cypress/mud wetland). See FUN-CONTENT-BACKLOG.md.
    "minecraft:snowy_taiga": [("leaf:boreal_shield", 0.0, 1.0)],
    "minecraft:mangrove_swamp": [("leaf:bayou", 0.0, 1.0)],
}


# Brand-new custom biomes injected as narrow climate slivers (not splits of an
# existing vanilla biome). glass_beach (coast continentalness) and
# volcanic_mountain (near-inland) share temperature/humidity/weirdness/erosion so
# they border each other along the coastline: the volcanic cone structure
# (leaf:volcano, gated to leaf:volcanic_mountain) always has a glass beach next to
# it. Slivers are additive, so every vanilla biome remains available.
def _sliver(biome, continentalness, depth):
    return {
        "biome": biome,
        "parameters": {
            "continentalness": continentalness,
            "depth": depth,
            "erosion": [-0.375, 0.05],
            "humidity": [-1.0, -0.35],
            "offset": 0.0,
            "temperature": [0.9, 1.0],
            "weirdness": [0.4, 0.7],
        },
    }

# Lavender Fields (Fun Content Backlog Tier 1 #5). Its intended vanilla
# derivative (flower_forest) is still mostly retained now that rainbow_forest only
# takes a RAINBOW_FRACTION sub-slice of the span, but it is kept as a distinct
# climate sliver (rather than another flower_forest split) so it occupies its own
# warm dry-ish niche. Instead of a whole-biome substitution it is injected as an
# additive climate sliver: warm, dry-ish, flat inland (high erosion) so it reads
# as an open purple bloom field. Additive => no vanilla biome is removed.
def _lavender(depth):
    return {
        "biome": "leaf:lavender_fields",
        "parameters": {
            "continentalness": [0.03, 0.3],
            "depth": depth,
            "erosion": [0.45, 0.85],
            "humidity": [-0.35, 0.1],
            "offset": 0.0,
            "temperature": [0.55, 0.8],
            "weirdness": [-0.2, 0.2],
        },
    }

# Volcanic Hot Springs (Fun Content Backlog Tier 2 #9). Sits just past the
# volcanic_mountain band (near-inland continentalness) so prismatic calcite
# pools cluster around the volcano, but with a cooler/wetter pine-taiga climate
# (distinct from the hot/dry volcanic sliver) so it reads as a separate biome.
# Additive => no vanilla biome is removed.
def _hot_springs(depth):
    return {
        "biome": "leaf:volcanic_hot_springs",
        "parameters": {
            "continentalness": [0.03, 0.1],
            "depth": depth,
            "erosion": [-0.375, 0.05],
            "humidity": [0.1, 0.5],
            "offset": 0.0,
            "temperature": [0.1, 0.4],
            "weirdness": [0.4, 0.7],
        },
    }

NEW_BIOMES = [
    _sliver("leaf:glass_beach", [-0.19, -0.11], 0.0),
    _sliver("leaf:glass_beach", [-0.19, -0.11], 1.0),
    _sliver("leaf:volcanic_mountain", [-0.11, 0.03], 0.0),
    _sliver("leaf:volcanic_mountain", [-0.11, 0.03], 1.0),
    # Ashen Plains (Tier 2 #7): the volcano's outer ash ring. Reuses the hot/dry
    # volcanic climate (_sliver) but one continentalness band further inland
    # [0.03, 0.1] so it forms a grey apron around the volcanic_mountain cone.
    _sliver("leaf:ashen_plains", [0.03, 0.1], 0.0),
    _sliver("leaf:ashen_plains", [0.03, 0.1], 1.0),
    _hot_springs(0.0),
    _hot_springs(1.0),
    _lavender(0.0),
    _lavender(1.0),
]

new_biomes = list(NEW_BIOMES)
counts = {k: 0 for k in SPLITS}
for entry in report["biomes"]:
    tiers = SPLITS.get(entry["biome"])
    if tiers:
        counts[entry["biome"]] += 1
        h = entry["parameters"]["humidity"]
        lo, hi = float(h[0]), float(h[1])
        span = hi - lo
        for name, a, b in tiers:
            c = copy.deepcopy(entry)
            c["biome"] = name
            c["parameters"]["humidity"] = [round(lo + span * a, 4), round(lo + span * b, 4)]
            new_biomes.append(c)
    else:
        new_biomes.append(entry)

dim = {
    "type": "minecraft:overworld",
    "generator": {
        "type": "minecraft:noise",
        "settings": "minecraft:overworld",
        "biome_source": {
            "type": "minecraft:multi_noise",
            "biomes": new_biomes,
        },
    },
}

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as f:
    json.dump(dim, f, indent=2)

for k, v in counts.items():
    print(f"{k} source entries:", v)
print("total biome entries written:", len(new_biomes))
print("output:", OUT)
