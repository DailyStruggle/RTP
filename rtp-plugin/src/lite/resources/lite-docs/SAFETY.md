# Safety filters (RTP-lite)

> Stripped from `docs/admin/configuration/SAFETY.md` and `docs/admin/HAZARDS.md`. Lite ships
> the **full base `safety.yml`** (no longer a trimmed lite-specific copy): the material
> allow/deny lists plus **vanilla block-tag** (`#minecraft:<tag>`) and **state-predicate**
> support from ADR-017. Only the `rtp-tags` module (`tags/`, `tagsRefresh.yml`) stays Pro.

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

Lite bundles the same `safety.yml` as the full edition (block tags and state
predicates included). See `docs/admin/configuration/SAFETY.md` for the full
reference.

## After editing safety.yml

Spatial memory was validated under the old rules. Always:

```
/rtp scan reset  region:<name>
/rtp scan start  region:<name>
```

## Not in lite (Pro only)

- `tags/`, `tagsRefresh.yml` (the `rtp-tags` module).

## Anvil pre-filter

Lite **does** ship the anvil pre-filter (`rtp-anvil`, ADR-016). It speeds up
biome rejection on cold-chunk worlds without violating S-005 — no synchronous
chunk I/O is performed on the main thread.
