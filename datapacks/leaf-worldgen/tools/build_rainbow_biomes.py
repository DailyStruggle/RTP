import colorsys, json, os

# Generates the 12 leaf:rainbow_forest_NN biomes used by the "occasional rainbow
# flower forest". Each biome is a clone of vanilla flower_forest (1.21.5 schema,
# pack_format 71 -- datafixed up on load by the server) that differs ONLY in
# effects.foliage_color. We deliberately set foliage_color but NOT grass_color so
# the tint lands on leaf blocks (and vines) while grass keeps its default
# flower-forest green -- "tint only the leaves" per the design.
#
# The 12 hues are evenly spaced (30 deg apart) around the colour wheel at a
# moderate saturation/value so adjacent strips read as a gentle gradient rather
# than a harsh contrast. Re-run to regenerate the biome JSON files:
#   python datapacks/leaf-worldgen/tools/build_rainbow_biomes.py

N = 12
SAT = 0.55
VAL = 0.80

OUT_DIR = os.path.normpath(os.path.join(os.path.dirname(__file__), "..", "data",
                                        "leaf", "worldgen", "biome"))


def foliage_int(i):
    r, g, b = colorsys.hsv_to_rgb(i / float(N), SAT, VAL)
    R, G, B = int(r * 255), int(g * 255), int(b * 255)
    return (R << 16) | (G << 8) | B


def biome(foliage):
    return {
        "carvers": [
            "minecraft:cave",
            "minecraft:cave_extra_underground",
            "minecraft:canyon",
        ],
        "downfall": 0.8,
        "effects": {
            "fog_color": 12638463,
            "foliage_color": foliage,
            "mood_sound": {
                "block_search_extent": 8,
                "offset": 2.0,
                "sound": "minecraft:ambient.cave",
                "tick_delay": 6000,
            },
            "music": [
                {
                    "data": {
                        "max_delay": 24000,
                        "min_delay": 12000,
                        "replace_current_music": False,
                        "sound": "minecraft:music.overworld.flower_forest",
                    },
                    "weight": 1,
                }
            ],
            "music_volume": 1.0,
            "sky_color": 7972607,
            "water_color": 4159204,
            "water_fog_color": 329011,
        },
        "features": [
            [],
            ["minecraft:lake_lava_underground", "minecraft:lake_lava_surface"],
            ["minecraft:amethyst_geode"],
            ["minecraft:monster_room", "minecraft:monster_room_deep"],
            [],
            [],
            [
                "minecraft:ore_dirt",
                "minecraft:ore_gravel",
                "minecraft:ore_granite_upper",
                "minecraft:ore_granite_lower",
                "minecraft:ore_diorite_upper",
                "minecraft:ore_diorite_lower",
                "minecraft:ore_andesite_upper",
                "minecraft:ore_andesite_lower",
                "minecraft:ore_tuff",
                "minecraft:ore_coal_upper",
                "minecraft:ore_coal_lower",
                "minecraft:ore_iron_upper",
                "minecraft:ore_iron_middle",
                "minecraft:ore_iron_small",
                "minecraft:ore_gold",
                "minecraft:ore_gold_lower",
                "minecraft:ore_redstone",
                "minecraft:ore_redstone_lower",
                "minecraft:ore_diamond",
                "minecraft:ore_diamond_medium",
                "minecraft:ore_diamond_large",
                "minecraft:ore_diamond_buried",
                "minecraft:ore_lapis",
                "minecraft:ore_lapis_buried",
                "minecraft:ore_copper",
                "minecraft:underwater_magma",
                "minecraft:disk_sand",
                "minecraft:disk_clay",
                "minecraft:disk_gravel",
            ],
            [],
            ["minecraft:spring_water", "minecraft:spring_lava"],
            [
                "minecraft:glow_lichen",
                "minecraft:flower_forest_flowers",
                # Use the custom leaf:rainbow_trees placed feature instead of a
                # vanilla tree set. It is oak-only (oak_small/medium/large, no
                # birch) so the foliage_color tint reads cleanly -- birch leaves
                # use a fixed hardcoded colour and would break the gradient. It
                # also places trees at a real forest density (count 7) rather
                # than the near-treeless vanilla trees_plains placement, so the
                # rainbow canopy is actually populated.
                "leaf:rainbow_trees",
                "minecraft:flower_flower_forest",
                "minecraft:patch_grass_badlands",
                "minecraft:brown_mushroom_normal",
                "minecraft:red_mushroom_normal",
                "minecraft:patch_pumpkin",
                "minecraft:patch_sugar_cane",
                "minecraft:patch_firefly_bush_near_water",
            ],
            ["minecraft:freeze_top_layer"],
        ],
        "has_precipitation": True,
        "spawn_costs": {},
        "spawners": {
            "ambient": [
                {"type": "minecraft:bat", "maxCount": 8, "minCount": 8, "weight": 10}
            ],
            "axolotls": [],
            "creature": [
                {"type": "minecraft:sheep", "maxCount": 4, "minCount": 4, "weight": 12},
                {"type": "minecraft:pig", "maxCount": 4, "minCount": 4, "weight": 10},
                {"type": "minecraft:chicken", "maxCount": 4, "minCount": 4, "weight": 10},
                {"type": "minecraft:cow", "maxCount": 4, "minCount": 4, "weight": 8},
                {"type": "minecraft:rabbit", "maxCount": 3, "minCount": 2, "weight": 4},
            ],
            "misc": [],
            "monster": [
                {"type": "minecraft:spider", "maxCount": 4, "minCount": 4, "weight": 100},
                {"type": "minecraft:zombie", "maxCount": 4, "minCount": 4, "weight": 95},
                {"type": "minecraft:zombie_villager", "maxCount": 1, "minCount": 1, "weight": 5},
                {"type": "minecraft:skeleton", "maxCount": 4, "minCount": 4, "weight": 100},
                {"type": "minecraft:creeper", "maxCount": 4, "minCount": 4, "weight": 100},
                {"type": "minecraft:slime", "maxCount": 4, "minCount": 4, "weight": 100},
                {"type": "minecraft:enderman", "maxCount": 4, "minCount": 1, "weight": 10},
                {"type": "minecraft:witch", "maxCount": 1, "minCount": 1, "weight": 5},
            ],
            "underground_water_creature": [
                {"type": "minecraft:glow_squid", "maxCount": 6, "minCount": 4, "weight": 10}
            ],
            "water_ambient": [],
            "water_creature": [],
        },
        "temperature": 0.7,
    }


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for i in range(N):
        foliage = foliage_int(i)
        name = "rainbow_forest_%02d" % (i + 1)
        path = os.path.join(OUT_DIR, name + ".json")
        with open(path, "w", encoding="utf-8") as f:
            json.dump(biome(foliage), f, indent=2)
            f.write("\n")
        print("wrote %s  foliage=#%06X" % (path, foliage))


if __name__ == "__main__":
    main()
