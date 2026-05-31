#!/usr/bin/env python3
"""Round-trip verifier for skyblock_island.schem.

Decompresses + parses the generated Sponge v3 NBT and asserts the structure and
block counts match the spec the issue requested. Not a build-time test; a quick
sanity check that the hand-written NBT is well-formed and WorldEdit-loadable
(same tag layout WorldEdit's SpongeSchematicV3Reader expects).
"""

import gzip
import struct
import os

f = os.path.join(os.path.dirname(os.path.abspath(__file__)), "skyblock_island.schem")
buf = gzip.open(f, "rb").read()
pos = 0


def u8():
    global pos
    v = buf[pos]
    pos += 1
    return v


def i16():
    global pos
    v = struct.unpack_from(">h", buf, pos)[0]
    pos += 2
    return v


def i32():
    global pos
    v = struct.unpack_from(">i", buf, pos)[0]
    pos += 4
    return v


def i64():
    global pos
    v = struct.unpack_from(">q", buf, pos)[0]
    pos += 8
    return v


def name():
    global pos
    n = struct.unpack_from(">H", buf, pos)[0]
    pos += 2
    s = buf[pos:pos + n].decode("utf-8")
    pos += n
    return s


def payload(tag):
    global pos
    if tag == 1:  # byte
        v = struct.unpack_from(">b", buf, pos)[0]; pos += 1; return v
    if tag == 2:
        return i16()
    if tag == 3:
        return i32()
    if tag == 4:
        return i64()
    if tag == 8:  # string
        return name()
    if tag == 7:  # byte array
        ln = i32(); v = buf[pos:pos + ln]; pos += ln; return v
    if tag == 11:  # int array
        ln = i32(); v = [i32() for _ in range(ln)]; return v
    if tag == 9:  # list
        et = u8(); ln = i32(); return [payload(et) for _ in range(ln)]
    if tag == 10:  # compound
        d = {}
        while True:
            t = u8()
            if t == 0:
                break
            nm = name()
            d[nm] = payload(t)
        return d
    raise ValueError("tag %d" % tag)


root_tag = u8()
assert root_tag == 10, root_tag
name()  # unnamed root
root = payload(10)
schem = root["Schematic"]

assert schem["Version"] == 3, schem["Version"]
W, H, L = schem["Width"], schem["Height"], schem["Length"]
blocks = schem["Blocks"]
palette = blocks["Palette"]
inv = {idx: state for state, idx in palette.items()}
data = blocks["Data"]

# decode varints
counts = {}
i = 0
n = 0
while i < len(data):
    val = 0
    shift = 0
    while True:
        b = data[i]; i += 1
        val |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    state = inv[val]
    counts[state] = counts.get(state, 0) + 1
    n += 1

assert n == W * H * L, (n, W * H * L)
dirt = counts.get("minecraft:dirt", 0)
grass = counts.get("minecraft:grass_block[snowy=false]", 0)
logs = counts.get("minecraft:oak_log[axis=y]", 0)
leaves = counts.get("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]", 0)
chest = counts.get("minecraft:chest[facing=north,type=single,waterlogged=false]", 0)

be = blocks["BlockEntities"]
chest_items = be[0]["Data"]["Items"]
item_ids = sorted((it["id"], it["Count"]) for it in chest_items)

print("OK  dims=%dx%dx%d cells=%d" % (W, H, L, n))
print("    dirt=%d grass=%d logs=%d leaves=%d chest=%d air=%d"
      % (dirt, grass, logs, leaves, chest, counts.get("minecraft:air", 0)))
print("    block-entities=%d  chest items=%s" % (len(be), item_ids))

assert dirt == 81, dirt
assert grass == 27, grass
assert logs == 4, logs
assert chest == 1, chest
assert len(be) == 1
assert item_ids == [("minecraft:ice", 1), ("minecraft:lava_bucket", 1), ("minecraft:obsidian", 10)], item_ids
print("ALL ASSERTIONS PASSED")
