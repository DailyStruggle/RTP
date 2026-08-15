# Region Arrival Schematics (`advanced/schematics/`)

Region arrival schematics let every teleport into a region paste a structure - a lobby pad, an arrival shrine, a custom landing platform - centered on the spot where the player lands.

---

## Happy path

1. Create a Sponge schematic file (Sponge format **v3**) - for example by copying a build in WorldEdit/FAWE and saving it as a `.schem`.
2. Name the file after the region it belongs to and drop it into the `schematics` directory inside the plugin data folder:
   - Bukkit family: `plugins/RTP/advanced/schematics/`
   - Fabric / NeoForge: `config/rtp/advanced/schematics/`
3. That is the entire setup. The next teleport into that region pastes the schematic, centered on the landing location.

```
plugins/RTP/advanced/schematics/default.schem      # pasted on every teleport into the "default" region
plugins/RTP/advanced/schematics/spawn_island.schem # pasted on every teleport into the "spawn_island" region
```

> **Note:** the schematic is matched purely by **file name = region name**. There is no config key to enable it - presence of the file is the switch. Remove the file to turn it off.

> **Warning:** only the **Sponge schematic format (v3)** is supported. The old MCEdit/legacy `.schematic` format is **not** decoded. The `.schematic` file extension is accepted, but the file's contents must still be Sponge-format NBT (a legacy MCEdit `.schematic` will fail to decode and the paste is skipped). When exporting from WorldEdit/FAWE, save as `.schem` (Sponge v3), not the legacy format.

---

## Behavior and guarantees

- **No WorldEdit required.** Sponge v3 `.schem` files are decoded in-house, so WorldEdit (or any other plugin) does not need to be installed for pasting to work.
- **Cross-platform.** The same file works on Bukkit, Paper, Folia, Fabric, and NeoForge - each platform only supplies the block writer; the decode is shared.
- **Claim-aware.** Pasting never overwrites claim-protected land, consistent with the rest of RTP's safety invariants.
- **Centered on the landing spot.** The structure is placed relative to the player's arrival coordinate, not a fixed world position.
- **Both extensions accepted, one format.** `<region>.schem` is preferred; `<region>.schematic` also resolves - but in either case the file must contain Sponge v3 NBT. The extension does not change the decoder; legacy MCEdit `.schematic` data is not supported.

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
