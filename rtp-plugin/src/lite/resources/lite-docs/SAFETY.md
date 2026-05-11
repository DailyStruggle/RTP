# Safety filters (RTP-lite)

> Stripped from `docs/admin/SAFETY.md` and `docs/admin/HAZARDS.md`. Lite ships
> a **flat material allow/deny list** only — block-tag / state-predicate
> sections from ADR-017 are not present (use RTP-Pro for those).

## What's enforced

The selection pipeline rejects any candidate that violates **S-001** (no
unsafe-block teleport destinations) before the player is moved. In lite this
is driven entirely by `safety.yml`:

- `unsafeBlocks:` — flat list of materials a player must not stand on.
- `unsafeBlocksAbove:` — flat list of materials a player's head/torso must not
  occupy.
- `biomeBlacklist:` — biomes excluded from selection.

A second-pass safety check inside the pipeline confirms the resolved location
still satisfies these lists at teleport time (regression-guarded by
`ReqRtpS001SafetyTest`).

## Default `safety.yml` (lite)

```yaml
unsafeBlocks:
  - LAVA
  - FIRE
  - SOUL_FIRE
  - CACTUS
  - MAGMA_BLOCK
  - SWEET_BERRY_BUSH
  - WITHER_ROSE
  - POWDER_SNOW
  - CAMPFIRE
  - SOUL_CAMPFIRE

unsafeBlocksAbove:
  - LAVA
  - FIRE
  - WATER          # head submerged
  - COBWEB

biomeBlacklist:
  - OCEAN
  - DEEP_OCEAN
  - COLD_OCEAN
  - DEEP_COLD_OCEAN
  - FROZEN_OCEAN
  - DEEP_FROZEN_OCEAN
  - LUKEWARM_OCEAN
  - DEEP_LUKEWARM_OCEAN
  - WARM_OCEAN
  - RIVER
  - FROZEN_RIVER
```

## After editing safety.yml

Spatial memory was validated under the old rules. Always:

```
/rtp scan reset  region:<name>
/rtp scan start  region:<name>
```

## Not in lite (Pro only)

- Block tags (e.g. `#minecraft:leaves`) in safety lists — ADR-017.
- State predicates (e.g. `oak_door[half=lower]`) — ADR-017.
- `tags/`, `tagsRefresh.yml`.

## Anvil pre-filter

Lite **does** ship the anvil pre-filter (`rtp-anvil`, ADR-016). It speeds up
biome rejection on cold-chunk worlds without violating S-005 — no synchronous
chunk I/O is performed on the main thread.
