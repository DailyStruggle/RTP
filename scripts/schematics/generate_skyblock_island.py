#!/usr/bin/env python3
"""Generate the `skyblock_island.schem` test fixture for ADR-058.

This emits a Sponge Schematic *version 3* file (the format modern WorldEdit /
FastAsyncWorldEdit write by default) as GZIP-compressed NBT, with NO dependency
on a running server or WorldEdit. Because the file is authored entirely here, it
is license-clean under the repository's own license and is fully reproducible.

Sponge v3 schema (https://github.com/SpongePowered/Schematic-Specification):
  root (unnamed compound)
    Schematic (compound)
      Version      = 3
      DataVersion  = 3578            (Minecraft 1.20.1)
      Metadata     { Name, Author, Date }
      Width/Height/Length            unsigned short
      Offset       = [0,0,0]         int[3]
      Blocks {
        Palette        { "<blockstate>": index, ... }
        Data           varint[]  (one entry per cell, order x + z*W + y*W*L)
        BlockEntities  [ { Pos:int[3], Id:string, Data:compound }, ... ]
      }

Island design (per the issue spec):
  * L-shaped footprint of 27 columns (a 6x3 arm + a 3x3 arm).
  * 3 dirt layers under the footprint  -> 81 dirt blocks total.
  * grass_block on the top layer       -> 27 grass blocks.
  * one oak tree on the OUTER edge of the dirt (far +X edge).
  * one chest in the MIDDLE of the dirt on the OTHER side (the 3x3 arm),
    sitting on top of the grass, containing 1 ice + 1 lava bucket + 10 obsidian.
  * every other cell inside the bounding box is minecraft:air. The intended
    paste uses pasteAir=false (ADR-058) so air never overwrites terrain, and the
    BOTTOM_CENTER anchor places the grass platform directly under the player.

Run:  python scripts/schematics/generate_skyblock_island.py
Output: scripts/schematics/skyblock_island.schem  (+ a self-verification dump)
"""

import gzip
import struct
import os

DATA_VERSION = 3578  # Minecraft 1.20.1

# ---------------------------------------------------------------------------
# Minimal big-endian NBT writer (only the tags this fixture needs).
# ---------------------------------------------------------------------------
TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11


def _name(b: bytearray, s: str) -> None:
    raw = s.encode("utf-8")
    b += struct.pack(">H", len(raw))
    b += raw


def _payload(b: bytearray, tag: int, value) -> None:
    if tag == TAG_BYTE:
        b += struct.pack(">b", value)
    elif tag == TAG_SHORT:
        b += struct.pack(">h", value)
    elif tag == TAG_INT:
        b += struct.pack(">i", value)
    elif tag == TAG_LONG:
        b += struct.pack(">q", value)
    elif tag == TAG_STRING:
        raw = value.encode("utf-8")
        b += struct.pack(">H", len(raw))
        b += raw
    elif tag == TAG_BYTE_ARRAY:
        b += struct.pack(">i", len(value))
        b += bytes(value)
    elif tag == TAG_INT_ARRAY:
        b += struct.pack(">i", len(value))
        for v in value:
            b += struct.pack(">i", v)
    elif tag == TAG_COMPOUND:
        # value is a list of (tag, name, payload-value) triples
        for t, n, v in value:
            b.append(t)
            _name(b, n)
            _payload(b, t, v)
        b.append(TAG_END)
    elif tag == TAG_LIST:
        # value is (element_tag, [payload-values...])
        elem_tag, items = value
        b.append(elem_tag)
        b += struct.pack(">i", len(items))
        for item in items:
            _payload(b, elem_tag, item)
    else:
        raise ValueError("unsupported tag %d" % tag)


def varint(n: int) -> bytes:
    out = bytearray()
    while True:
        byte = n & 0x7F
        n >>= 7
        if n:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            break
    return bytes(out)


# ---------------------------------------------------------------------------
# Island geometry (design coordinates, normalized to origin 0 at the end).
# ---------------------------------------------------------------------------
AIR = "minecraft:air"
DIRT = "minecraft:dirt"
GRASS = "minecraft:grass_block[snowy=false]"
LOG = "minecraft:oak_log[axis=y]"
LEAVES = "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]"
CHEST = "minecraft:chest[facing=north,type=single,waterlogged=false]"

blocks: dict = {}  # (x, y, z) -> blockstate string


def setb(x, y, z, state):
    blocks[(x, y, z)] = state


# L-shaped footprint: 6x3 arm (z 0..2) + 3x3 arm (z 3..5) = 27 columns.
footprint = []
for x in range(0, 6):
    for z in range(0, 3):
        footprint.append((x, z))
for x in range(0, 3):
    for z in range(3, 6):
        footprint.append((x, z))
assert len(footprint) == 27, len(footprint)

# 3 dirt layers (y 0..2) + grass top (y 3).
dirt_count = 0
grass_count = 0
for (x, z) in footprint:
    for y in range(0, 3):
        setb(x, y, z, DIRT)
        dirt_count += 1
    setb(x, 3, z, GRASS)
    grass_count += 1
assert dirt_count == 81, dirt_count
assert grass_count == 27, grass_count

# Oak tree on the far +X outer edge of the 6x3 arm.
tx, tz = 5, 1
for y in (4, 5, 6, 7):  # 4 logs, trunk standing on the grass top
    setb(tx, y, tz, LOG)


def leaf(x, y, z):
    if (x, y, z) not in blocks:  # never overwrite the trunk
        setb(x, y, z, LEAVES)


# Two 5x5 canopy rings (corners removed) around the upper trunk.
for y in (6, 7):
    for dx in range(-2, 3):
        for dz in range(-2, 3):
            if abs(dx) == 2 and abs(dz) == 2:
                continue
            leaf(tx + dx, y, tz + dz)
# 3x3 cap.
for dx in range(-1, 2):
    for dz in range(-1, 2):
        leaf(tx + dx, 8, tz + dz)
# Plus-shaped crown.
for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
    leaf(tx + dx, 9, tz + dz)

# Chest in the middle of the 3x3 arm, on top of the grass.
cx, cy, cz = 1, 4, 4
setb(cx, cy, cz, CHEST)

# ---------------------------------------------------------------------------
# Normalize to origin and build the dense Data array.
# ---------------------------------------------------------------------------
xs = [p[0] for p in blocks]
ys = [p[1] for p in blocks]
zs = [p[2] for p in blocks]
minx, miny, minz = min(xs), min(ys), min(zs)
maxx, maxy, maxz = max(xs), max(ys), max(zs)
W = maxx - minx + 1
H = maxy - miny + 1
L = maxz - minz + 1

norm = {}
for (x, y, z), state in blocks.items():
    norm[(x - minx, y - miny, z - minz)] = state

# Palette: air first (index 0), then deterministic insertion order.
palette = {AIR: 0}
for y in range(H):
    for z in range(L):
        for x in range(W):
            state = norm.get((x, y, z), AIR)
            if state not in palette:
                palette[state] = len(palette)

data = bytearray()
for y in range(H):
    for z in range(L):
        for x in range(W):
            state = norm.get((x, y, z), AIR)
            data += varint(palette[state])

# Chest block-entity, position relative to normalized origin.
chest_pos = [cx - minx, cy - miny, cz - minz]
items = [
    (0, "minecraft:ice", 1),
    (1, "minecraft:lava_bucket", 1),
    (2, "minecraft:obsidian", 10),
]
item_compounds = []
for slot, item_id, count in items:
    item_compounds.append((TAG_COMPOUND, "", [
        (TAG_BYTE, "Slot", slot),
        (TAG_STRING, "id", item_id),
        (TAG_BYTE, "count", count),   # 1.20.5+ uses "count"; harmless extra
        (TAG_BYTE, "Count", count),   # pre-1.20.5 uses "Count"
    ]))

chest_be = (TAG_COMPOUND, "", [
    (TAG_INT_ARRAY, "Pos", chest_pos),
    (TAG_STRING, "Id", "minecraft:chest"),
    (TAG_COMPOUND, "Data", [
        (TAG_STRING, "id", "minecraft:chest"),
        (TAG_LIST, "Items", (TAG_COMPOUND, [c[2] for c in item_compounds])),
    ]),
])

palette_compound = [(TAG_INT, state, idx) for state, idx in palette.items()]

blocks_compound = [
    (TAG_COMPOUND, "Palette", palette_compound),
    (TAG_BYTE_ARRAY, "Data", data),
    (TAG_LIST, "BlockEntities", (TAG_COMPOUND, [chest_be[2]])),
]

schematic_compound = [
    (TAG_INT, "Version", 3),
    (TAG_INT, "DataVersion", DATA_VERSION),
    (TAG_COMPOUND, "Metadata", [
        (TAG_STRING, "Name", "skyblock_island"),
        (TAG_STRING, "Author", "RTP (ADR-058 fixture)"),
        (TAG_LONG, "Date", 0),
    ]),
    (TAG_SHORT, "Width", W),
    (TAG_SHORT, "Height", H),
    (TAG_SHORT, "Length", L),
    (TAG_INT_ARRAY, "Offset", [0, 0, 0]),
    (TAG_COMPOUND, "Blocks", blocks_compound),
]

root = bytearray()
root.append(TAG_COMPOUND)
_name(root, "")  # unnamed root
_payload(root, TAG_COMPOUND, [
    (TAG_COMPOUND, "Schematic", schematic_compound),
])
root.append(TAG_END)  # close root compound

out_dir = os.path.dirname(os.path.abspath(__file__))
out_path = os.path.join(out_dir, "skyblock_island.schem")
with gzip.open(out_path, "wb") as f:
    f.write(bytes(root))

print("wrote %s (%d bytes gzipped)" % (out_path, os.path.getsize(out_path)))
print("dimensions  W=%d H=%d L=%d  (%d cells)" % (W, H, L, W * H * L))
print("dirt=%d grass=%d  palette=%d entries" % (dirt_count, grass_count, len(palette)))
print("chest pos (normalized) = %s with items ice/lava_bucket/obsidian x10" % chest_pos)
print("palette:")
for state, idx in palette.items():
    print("  %2d  %s" % (idx, state))
