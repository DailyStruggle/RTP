# Reading Minecraft Region Files (the anvil pre-filter)

To find safe teleport destinations without loading (and generating) chunks on the server thread, RTP reads Minecraft's region files (`.mca`, the "anvil" format) **directly from disk, read-only, off the main thread**. This page documents exactly which on-disk format RTP understands, and - just as importantly - what it does when it encounters data it does *not* understand. That pass-through behavior is what keeps RTP safe if a future Minecraft version changes its save format.

The feature is controlled by `anvilPrefilterEnabled` in [`safety.yml`](configuration/SAFETY.md) (default `true`). Turning it off makes every candidate skip the disk pre-filter and reach the live-load stage un-screened; it does not change safety, only speed.

---

## Where this fits: the cache pipeline

The anvil reader is a **screen**, not the teleport path itself. It never loads or generates a chunk, and it is **not** the stage that live-loads. Candidates flow through three cache tiers, and only the last one live-loads:

- **L3 (backlog).** Cheap shape-only picks are queued unverified. Each pulse, RTP reads **one** region file and screens the subset of queued picks that fall inside that `.mca`. A pick the reader **rejects** (a known-unsafe surface on disk) is dropped; a pick it **accepts** or cannot decide (`UNKNOWN`) is kept and flows down. The reader only ever *removes* known-bad candidates here - it does not live-load.
- **L2 (cold).** Filled from L3's screened output, or by screening a single location against an already-read region file.
- **L2 -> L1 (hot).** This is the only stage that **live-loads**: it loads (generating if necessary) the candidate's chunk, runs the full in-memory safety check on the real block data, and only then adds the location to L1, where it waits until `/rtp` consumes it.

So "falls back to live loading" is a property of the **L2 -> L1 promotion**, not of the anvil reader. The reader's whole point is to keep known-unsafe candidates from ever reaching that live load.

---

## What RTP reads

- **File type:** vanilla region files named `r.X.Z.mca`, in the world's region directory (`region/` for the overworld, `DIM-1/region/`, `DIM1/region/`, and custom-dimension subpaths for others).
- **Access mode:** **read-only**. RTP never writes, truncates, or rewrites a region file, and never loads or generates a chunk to read one. A region file that does not exist yet is simply treated as "not known" (see fallback below).
- **What it extracts:** only the few fields the safety check needs - the chunk's `DataVersion`, the `MOTION_BLOCKING_NO_LEAVES` heightmap, the block `sections` palette, and biome data. It does no block-entity, tile-tick, or entity interpretation.

### Region-file structure understood

- A 4 KiB (4096-byte) sector layout with the standard 8 KiB header (chunk location table + timestamp table).
- Per-chunk location entries giving a sector offset + sector count; a zeroed entry means "chunk not present in this region" and is treated as unknown.
- A per-chunk 5-byte payload header (4-byte big-endian length prefix + 1 compression-mode byte), followed by the compressed NBT chunk root.

### Compression modes understood

| Mode byte | Meaning | Supported |
|---|---|---|
| `1` | GZIP | Yes |
| `2` | Zlib / Deflate (the vanilla default) | Yes |
| `3` | Uncompressed | Yes |
| `4` | LZ4 | Yes |
| any other value | Unknown / future mode | No -> fallback |
| the `0x80` "external file" flag (e.g. `130` = `0x82`) | Oversized chunk stored in a sidecar `.mcc` file | No -> fallback |

### Minecraft versions validated

The chunk NBT layout is parity-checked against real region files captured from these releases (their `DataVersion` in parentheses):

- Minecraft 1.20.4 (`3465`)
- Minecraft 1.21.5 (`4671`)
- Minecraft 26.1 (`4788`)

There is **no version allow-list gate**: RTP attempts to decode a chunk of *any* `DataVersion`. The decoder itself is the correctness boundary - if a chunk from an unrecognized version does not match the expected NBT shape, decoding fails cleanly and the reader returns `UNKNOWN` (see below). This is deliberate: it means a new Minecraft release generally keeps working with no RTP update, and if it ever does break the layout, RTP declines to screen those candidates rather than making a wrong decision - they are simply passed down to the live-load stage.

---

## What happens when the data is invalid or unrecognized

The reader has exactly three outcomes: **ACCEPT** (every sampled column is safe on disk), **REJECT** (a sampled surface is a known-unsafe block - the candidate is dropped), and **UNKNOWN** (it could not decide from disk). `UNKNOWN` is **not a rejection**: the candidate is *not* dropped, it simply passes the screen unscreened and continues down the pipeline to the live-load stage (L2 -> L1), where the real chunk is loaded (and generated if needed) and the full in-memory safety check makes the final call. The reader is an optimization that removes known-bad candidates early; it is never the final safety authority.

`UNKNOWN` is returned - and the candidate is passed through rather than screened - when, among others:

- **No region file on disk** - the chunk has never been generated. Nothing on disk to screen; the live-load stage will generate it. (This is the "it just can't decide when a chunk is not generated" case: the reader does not reject, it declines to judge.)
- **Empty location entry** - the chunk slot is unused in that region file.
- **Region read failed** - the file vanished or could not be read.
- **Unknown compression mode**, or the **external-file (`0x80`) flag** - a compression variant RTP has not validated.
- **Corrupt / truncated region entry** - a length prefix or sector span that runs past the end of the file.
- **Missing `MOTION_BLOCKING_NO_LEAVES` heightmap**, or **no block sections** - a chunk shape the safety check cannot read.

In every one of these cases the outcome is the same: the reader neither accepts nor rejects from disk, so the candidate is passed through to be resolved by the live-load safety check. **A format RTP cannot read never produces an unsafe placement - it only means that candidate is confirmed the slow way instead of screened the fast way.**

### If a future Minecraft version changes the format

This is the scenario the fallback is designed for. If a new release changes the chunk NBT layout, the compression scheme, or the region-file structure in a way RTP has not been updated for:

1. The affected chunks decode as `UNKNOWN`, so the reader stops screening them and passes them through to the live-load safety check. Teleports keep working and stay safe; you may see the pre-filter's hit rate drop and teleports take a little longer.
2. RTP logs a rate-limited diagnostic naming the cause (e.g. `unsupported-dataversion=...`, `missing-heightmap`, or an unknown-compression message). These appear at `FINE` level; the first few per cause are surfaced and the rest are suppressed to avoid log spam.

To confirm what the pre-filter is doing on your server, run the built-in diagnostics (`/rtp test biome-source` / `/rtp test anvil-prefilter`) and watch the `anvil-hit` counter: a healthy server shows hits climbing, while a stuck `anvil-hits=0` alongside the `UNKNOWN:*` log lines points at exactly which fallback cause is firing. See [COMMANDS.md](COMMANDS.md) for the diagnostic commands.

---

## See also

- [SAFETY.md](configuration/SAFETY.md) - `anvilPrefilterEnabled` and the landing safety pipeline.
- [REGIONS.md](configuration/REGIONS.md#backlog-cache-l3) - how the backlog cache verifies one `.mca` bin at a time using this reader.
- [ADR-016](../adr/ADR-016-anvil-subsystem.md) - the design decision behind the read-only anvil pre-filter and the `UNKNOWN` -> pass-through (resolved at the live-load stage) contract.
- [ADR-028](../adr/ADR-028-l3-backlog-cache.md) - the L3 backlog cache and its one-`.mca`-per-pulse screening.
