# Region Arrival Schematics (`advanced/schematics/`)

Region arrival schematics let every teleport into a region paste a structure - a lobby pad, an arrival shrine, a custom landing platform - centered on the spot where the player lands.

---

## Happy path

1. Create a **Sponge schematic file** (`.schem`, Sponge format **v2/v3** from WorldEdit/FAWE) or a **Litematica schematic file** (`.litematic` from the Litematica client mod).
2. Name the file after the region it belongs to and drop it into the `schematics` directory inside the plugin data folder:
   - Bukkit family: `plugins/RTP/advanced/schematics/`
   - Fabric / NeoForge: `config/rtp/advanced/schematics/`
3. That is the entire setup. The next teleport into that region pastes the schematic, centered on the landing location.

```
plugins/RTP/advanced/schematics/default.schem          # pasted on every teleport into the "default" region
plugins/RTP/advanced/schematics/spawn_island.litematic # pasted on every teleport into the "spawn_island" region
```

> **Note:** the schematic is matched purely by **file name = region name** (resolution precedence: `<region>.schem`, `<region>.schematic`, `<region>.litematic`). There is no config key to enable it - presence of the file is the switch. Remove the file to turn it off.

> **Warning:** **Sponge schematic format (v2/v3)** and **Litematica format (`.litematic`)** are supported natively. The old MCEdit/legacy `.schematic` format (numeric block IDs / metadata arrays from pre-1.13) is **not** decoded. The `.schematic` file extension is accepted, but the file's contents must still be Sponge-format or Litematica NBT (a legacy MCEdit `.schematic` will fail to decode and the paste is skipped).

---

## Behavior and guarantees

- **No WorldEdit or Litematica mod required on the server.** Both Sponge v2/v3 `.schem` and Litematica `.litematic` files (including multi-region Litematica sub-regions, negative region dimensions, block-state property palettes, and container tile entities) are decoded in-house, so no external schematic plugin or mod needs to be installed.
- **Cross-platform.** The same `.schem` or `.litematic` file works on Bukkit, Paper, Folia, Fabric, and NeoForge - each platform only supplies the block writer; the decode is shared.
- **Claim-aware.** Pasting never overwrites claim-protected land, consistent with the rest of RTP's safety invariants.
- **Centered on the landing spot.** The structure is placed relative to the player's arrival coordinate, not a fixed world position.
- **Supported extensions and precedence.** `<region>.schem` is checked first, followed by `<region>.schematic` and `<region>.litematic`. Action definitions (`placement.schematic` in `definitions/actions/<id>.yml`) also resolve `.schem`, `.schematic`, and `.litematic` files both with and without an explicit file extension.

---

## Common uses

| Goal | How |
|---|---|
| Lobby / arrival pad on a hub | `advanced/schematics/<hub-region>.schem` |
| Guaranteed safe platform in a void or skyblock world | A small platform schematic for that region, paired with the `FIXED` vert (see [REGIONS.md](REGIONS.md#vert-section)) |
| Themed arrival shrine per dimension | One file per region (e.g. `nether.schem`, `end.schem`) |

Some prefabs (e.g. Skyblock) ship a schematic baked into the jar and drop it here automatically when installed.

---

## See also

- [REGIONS.md](REGIONS.md) - region configuration (shape, vert, caches).
- [SAFETY.md](SAFETY.md) - landing safety checks and platform building.
