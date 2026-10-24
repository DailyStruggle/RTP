# Performance Configuration Reference (`advanced/performance.yml`)

This document provides a detailed reference for all configuration options available in `plugins/RTP/advanced/performance.yml`.

---

## Updating Settings

You can update performance settings through:
1. **In-game admin menu**: Run `/rtp admin` or `/rtp menu` -> click **Performance**.
2. **Command line**: Use `/rtp config performance <key>=<value>` (e.g. `/rtp config performance minTPS=18.5`).
3. **Direct editing**: Edit `advanced/performance.yml` on disk and run `/rtp reload`.

> 📎 See [IN_GAME_CONFIG.md](IN_GAME_CONFIG.md) for full menu and command navigation details.

---

## Duration Values

Keys marked **Integer / Duration** below accept a plain number or a duration with unit suffixes (e.g. `40t`, `500ms`, `2s`, `24h`, `1d`, or composites such as `1m30s`). A plain number keeps the key's own unit (ticks for `period` and `viewDistanceRestoreInterval`, milliseconds for `syncAllottedTime`, `asyncAllottedTime` and `slowPipelineThresholdMs`). A suffixed value is converted to that unit and rounded, so `period: 2s` is `40` ticks and `slowPipelineThresholdMs: 5s` is `5000` ms. An unreadable value logs a warning and the default is used. Full suffix list: [CORE_CONFIG.md -> Duration Unit Suffixes & Parsing](CORE_CONFIG.md#duration-unit-suffixes--parsing).

---

## Server Health & Timing

| Key | Type | Default | Description | Impact |
|---|---|---|---|---|
| `autoTune` | Boolean | `true` | Adapt background compute (`asyncAllottedTime`, `syncAllottedTime`), refill cadence (`period`), cold-to-hot promotion rate, per-region hot-cache size (`activeChunkCap`), and chunk-generation concurrency to measured server headroom and player demand. While `true`, the configured budgets below (and region `activeChunkCap`) act as ceilings for background work, while waiting players keep the full configured budgets. Set `false` to run at the fixed configured values and refill once per `period`. | **LOW** |
| `minTPS` | Double | `19.0` | Hard backstop that pauses background cache filling (backlog picks, chunk loads for the ready queue, cold fill) when TPS drops below this. On Folia the server-wide pause uses the mean across regions, and each region below `minTPS` is handled on its own: RTP runs one background task per region tick there and fills from other regions instead. Players already waiting are still served. Resumes 0.5 TPS above the threshold. `0` disables. Range: 0.0–20.0. | **LOW** |
| `maxMSPT` | Double | `45.0` | Hard backstop that pauses background cache filling when a tick takes longer than this many ms. TPS stays at 20 until ticks pass 50 ms, so this catches a loaded server earlier. Resumes 5 ms below the threshold. When `autoTune: true`, also sets the soft CPU target (`0.8 * maxMSPT`, or `40.0` ms when `0`) where background work begins scaling down before the hard pause trips. On Folia only a region whose ticks overrun (over 52.5 ms apart) counts, and only for that region, because Folia exposes tick intervals rather than tick work. `0` disables. | **LOW** |
| `maxHeapPercent` | Double | `85.0` | Hard backstop that pauses cache filling when JVM memory use passes this percentage. When `autoTune: true`, also sets the soft heap target (`maxHeapPercent - 10` percentage points, or `80%` when disabled) where per-region `activeChunkCap` begins contracting before the hard pause trips. `0` or `100` disables. Range: 0.0–100.0. | **LOW** |
| `maxAttempts` | Integer | `32` | Maximum location search attempts before giving up. | **HIGH** — Higher values increase CPU per request. |
| `period` | Integer / Duration | `20` | Ticks between refills of the same region (`20` ticks = 1 second; supports duration units such as `2s`, `24h`, `1d`). When `autoTune: true`, acts as the demand credit horizon and idle cadence (`8 * period` ticks when a region has no demand) while regions with demand refill on every pulse within the per-tick ceilings. When `autoTune: false`, regions take turns at this fixed interval so each region gets one refill every `period` ticks. | **MEDIUM** — Lower values refill faster but use more CPU. |
| `syncAllottedTime` | Integer / Duration | `5` | Max milliseconds per tick for RTP's main-thread work (queued commands, scripted actions, sync task queue; supports duration units). When `autoTune: true`, acts as the per-tick ceiling for background sync work (scaled down as MSPT approaches the soft target), while player-facing work keeps the full configured value. On Folia it also applies as a per-region-thread ceiling: RTP adds at most this much to any region's tick, however many players' work lands there, and the rest waits behind other regions. Values above 25 are treated as 25, so at least half of each tick stays free for the server and other plugins. Range: 1–25. | **MEDIUM** — Higher values allow faster processing at the risk of tick time. |
| `asyncAllottedTime` | Integer / Duration | `25` | Max milliseconds per pulse for RTP's background work (supports duration units). When `autoTune: true`, acts as the per-pulse ceiling for background refill and scales with demand and CPU headroom, while waiting players keep the full configured value; when `autoTune: false`, acts as a fixed per-pulse budget. Values above 25 are treated as 25. Range: 1–25. | **MEDIUM** |

## World Loading & View Distance

| Key | Type | Default | Description | Impact |
|---|---|---|---|---|
| `viewDistanceSelect` | Integer | `0` | Chunk radius to pre-load around candidate locations during selection. `0` = just the candidate chunk. | **HIGH** — Higher values cause lag spikes on selection. |
| `viewDistanceTeleport` | Integer | `0` | Chunk radius to pre-load around the destination before teleport. `0` = just the destination chunk. | **HIGH** |
| `viewDistanceRestoreInterval` | Integer / Duration | `0` | Ticks to ease view distance back to normal after arrival. Set to `0` (off) to prevent client-side flashing. | **LOW** |
| `syncLoading` | Boolean | `false` | Use synchronous chunk loading for location selection. **NOT RECOMMENDED**. | **HIGH** — Can cause server hangs. |
| `teleportPath` | String | `AUTO` | Paper only. `AUTO` teleports in the same tick when the destination chunk is already loaded and the teleport starts on the main thread, otherwise uses `teleportAsync`. `ASYNC` always uses `teleportAsync` (up to one tick slower). Folia always uses `teleportAsync`. | **LOW** |

## Caching & Pre-fill

| Key | Type | Default | Description | Impact |
|---|---|---|---|---|
| `postTeleportQueueing` | Boolean | `false` | Immediately try to refill the cache after a teleport occurs. | **MEDIUM** |
| `checkOnChunkLoads` | Boolean | `true` | Learn biomes from chunks already loaded without doing extra work. | **LOW** |
| `backlogRefillThreshold` | Double | `0.5` | Refill the L3 backlog buffer when it drops to this fraction (e.g. `0.5` = 50%). Range: 0.0–1.0. | **LOW** |
| `pregeneratedPreference` | Double | `0.0` | Bias toward pregenerated chunks over generating new terrain. Range: 0.0–1.0 (0 = no preference, 1 = avoid ungenerated). | **LOW** |
| `loginCacheEnabled` | Boolean | `false` | Maintain a dedicated reserve of safe spots for players joining the server (`rtp.onevent.join`/`firstjoin`). | **LOW** |
| `loginCacheCap` | Integer | `0` | Max size of the login reserve cache. `0` = auto (server max players). | **LOW** |

## Parsing & Recall

| Key | Type | Default | Description | Impact |
|---|---|---|---|---|
| `biomeRecall` | Boolean | `true` | Reuse previously found biome locations. | **LOW** |
| `biomeRecallForced` | Boolean | `false` | Only use biomes that have already been discovered and cached. | **MEDIUM** |
| `onEventParsing` | Boolean | `false` | Parse permissions on every event. Incompatible with `*.*` permissions. | **MEDIUM** |
| `effectParsing` | Boolean | `true` | Parse effect permissions on startup. | **LOW** |

## Auditing & Diagnostics

| Key | Type | Default | Description | Impact |
|---|---|---|---|---|
| `slowPipelineThresholdMs` | Integer / Duration | `5000` | Log a warning when a teleport takes longer than this threshold in ms. `0` or negative disables. | **LOW** |
| `queueGrowthWarnThreshold` | Integer | `0` | Log a warning when the player wait queue depth exceeds this number. `0` or negative disables. | **LOW** |

## Observational Mode (Visitor)

The **Region Data Visitor** allows the plugin to gather data (bad locations, biome harvesting) even when the cache is full, without adding load or triggering worldgen.

| Key | Type | Default | Description | Impact |
|---|---|---|---|---|
| `visitorEnabled` | Boolean | `false` | Enable background observation mode. | **LOW** — Work only happens when cache is full; skips unknown chunks. |

## Prefab Backups

| Key | Type | Default | Description |
|---|---|---|---|
| `prefab.bakRetention` | Integer | `3` | Number of `.bak` backup files to retain per file when applying `/rtp admin prefab` presets. |

---

## Versioning
- `version`: Internal config version (do not change).
