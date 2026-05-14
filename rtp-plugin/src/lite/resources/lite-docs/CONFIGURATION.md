# Configuration (RTP-lite)

> Stripped from `docs/admin/CONFIGURATION.md`. Only files actually shipped in
> the lite jar are documented here (ADR-024).

## Files in `plugins/RTP/`

| File | Purpose |
|---|---|
| `config.yml` | Top-level toggles (default world list, debug logging level, etc.). |
| `performance.yml` | Queue tuning, scan task count, async/sync teleport scheduling. **Trimmed in lite** — no `visitorEnabled`, `loginCacheEnabled`, `loginCacheCap`, `effectParsing`, `onEventParsing`. |
| `messages.yml` | All user-facing messages. REQ-RTP-F-013 — every player-visible string is configurable. Lite ships the full `messages.yml` for Pro parity (2026-05-11 ADR-024 language-options amendment). |
| `language.yml` | Active locale selector (ADR-020). Lite ships `lang/**` and the multilingual bootstrap for Pro parity. |
| `safety.yml` | Flat material allow/deny list + biome blacklist. **No tag / state-predicate sections in lite** (see ADR-017 — Pro only). |
| `logging.yml` | Logger threshold per category. |
| `regions/<name>.yml` | One file per region (`default.yml` ships out of the box). |
| `worlds/<world>.yml` | One file per loaded world; created automatically on first start. |
| `effects/<name>.yml` | Optional per-permission effect bundles consumed by `effects-api`. |
| `lite-docs/` | This bundled doc set, extracted on first start. |

## Files NOT shipped in lite

`economy.yml`. Lite does not wire the Vault hook. (Claim-plugin softdepend
integrations and `integrations.yml` ship in lite as of the 2026-05-11
ADR-024 claim-plugin amendment; `lang/**` and `language.yml` ship as of the
2026-05-11 ADR-024 language-options amendment.)

## `regions/<name>.yml` — key keys

```yaml
world: "[0]"                  # case-sensitive world name; placeholders [0]/[1]/[2] resolve by load order
requirePermission: false
shape:
  name: "CIRCLE"              # CIRCLE | SQUARE | RECTANGLE | CIRCLE_NORMAL | SQUARE_NORMAL
  mode: "ACCUMULATE"          # selection mode (lite ships the full set)
  radius: 256                 # CHUNKS
  centerRadius: 64            # CHUNKS
  centerX: 0
  centerZ: 0
  weight: 1.0
verticalAdjustor:
  name: "JUMP"                # full set retained in lite
  minY: 50
  maxY: 200
biome:
  blacklist:                  # mapped from safety.yml's biome list
    - OCEAN
    - DEEP_OCEAN
cacheCap: 10                  # pregen queue size for this region
queue:
  maxAttemptsPerSecond: 4
```

## `performance.yml` — keys present in lite

```yaml
asyncTeleportTimeout: 30      # seconds
syncTeleportTimeout: 30
scanTaskCount: 4              # parallel scan crawlers per region
queueIntervalTicks: 4         # how often QueueTask wakes per region
chunkUnloadIntervalTicks: 20
```

Keys removed in lite: `visitorEnabled`, `loginCacheEnabled`, `loginCacheCap`,
`effectParsing`, `onEventParsing`.

## Reload

Most edits take effect with `/rtp reload`. Exceptions:

- Edits to `safety.yml` invalidate spatial memory: run
  `/rtp scan reset <region>` then `/rtp scan start <region>`.
- Adding/removing a region or world file requires `/rtp reload` (no restart).

## Validation

Use `/rtp info` and `/rtp info region:<name>` to inspect the runtime state
after a reload (queue depth, in-flight calcs, shape, cache cap).
