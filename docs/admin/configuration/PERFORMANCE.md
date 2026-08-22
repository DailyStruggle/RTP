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

## Server Health & Timing

| Key | Type | Default | Description | Impact |
|---|---|---|---|---|
| `minTPS` | Double | `19.0` | Minimum server TPS required to queue new locations. Range: 0.0–20.0. | **LOW** |
| `maxHeapPercent` | Double | `85.0` | Pause cache filling when JVM memory use passes this percentage. `0` or `100` disables. Range: 0.0–100.0. | **LOW** |
| `maxAttempts` | Integer | `32` | Maximum location search attempts before giving up. | **HIGH** — Higher values increase CPU per request. |
| `period` | Integer | `20` | Ticks between background cache cycles (20 ticks = 1 second). | **MEDIUM** — Lower values refill faster but use more CPU. |
| `syncAllottedTime` | Integer | `50` | Max milliseconds per tick spent on synchronous RTP tasks. | **MEDIUM** — Higher values allow faster processing at the risk of tick time. |
| `asyncAllottedTime` | Integer | `50` | Max milliseconds per tick spent on asynchronous RTP tasks. | **MEDIUM** |

## World Loading & View Distance

| Key | Type | Default | Description | Impact |
|---|---|---|---|---|
| `viewDistanceSelect` | Integer | `0` | Chunk radius to pre-load around candidate locations during selection. `0` = just the candidate chunk. | **HIGH** — Higher values cause lag spikes on selection. |
| `viewDistanceTeleport` | Integer | `0` | Chunk radius to pre-load around the destination before teleport. `0` = just the destination chunk. | **HIGH** |
| `viewDistanceRestoreInterval` | Integer | `0` | Ticks to ease view distance back to normal after arrival. Set to `0` (off) to prevent client-side flashing. | **LOW** |
| `syncLoading` | Boolean | `false` | Use synchronous chunk loading for location selection. **NOT RECOMMENDED**. | **HIGH** — Can cause server hangs. |

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
| `slowPipelineThresholdMs` | Integer | `5000` | Log a warning when a teleport takes longer than this threshold in ms. `0` or negative disables. | **LOW** |
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
