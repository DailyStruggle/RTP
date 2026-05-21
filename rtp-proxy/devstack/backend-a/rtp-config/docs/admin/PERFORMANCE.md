# Performance Configuration Reference (`performance.yml`)

This document provides a detailed reference for all configuration options available in `plugins/RTP/performance.yml`.

---

## Task Timing & Limits

| Key | Type | Default | Description | Impact |
|---|---|---|---|---|
| `maxAttempts` | Integer | `32` | Maximum location search attempts before giving up. | **HIGH** — Higher values increase CPU per request. |
| `period` | Integer | `20` | Ticks between background cache cycles (20 ticks = 1 second). | **MEDIUM** — Lower values refill faster but use more CPU. |
| `syncAllottedTime` | Integer | `50` | Max milliseconds per tick spent on synchronous RTP tasks. | **MEDIUM** — Higher values allow faster processing at the risk of tick time. |
| `asyncAllottedTime` | Integer | `50` | Max milliseconds per tick spent on asynchronous RTP tasks. | **MEDIUM** |

## World Loading

| Key | Type | Default | Description | Impact |
|---|---|---|---|---|
| `viewDistanceSelect` | Integer | `0` | Chunk radius to pre-load around candidate locations during selection. | **HIGH** — Higher values cause lag spikes on selection. |
| `viewDistanceTeleport` | Integer | `0` | Chunk radius to pre-load around the destination before teleport. | **HIGH** |
| `syncLoading` | Boolean | `false` | Use synchronous chunk loading for location selection. **NOT RECOMMENDED**. | **HIGH** — Can cause server hangs. |

## Throttling & Queueing

| Key | Type | Default | Description | Impact |
|---|---|---|---|---|
| `minTPS` | Double | `19.0` | Minimum server TPS required to queue new locations. Range: 0.0–20.0. | **LOW** |
| `postTeleportQueueing` | Boolean | `false` | Immediately try to refill the cache after a teleport occurs. | **MEDIUM** |

## Parsing & Recall

| Key | Type | Default | Description | Impact |
|---|---|---|---|---|
| `onEventParsing` | Boolean | `false` | Parse permissions on every event. Incompatible with `*.*` permissions. | **MEDIUM** |
| `effectParsing` | Boolean | `true` | Parse effect permissions on startup. | **LOW** |
| `biomeRecall` | Boolean | `true` | Reuse previously found biome locations. | **LOW** |
| `biomeRecallForced` | Boolean | `false` | Only use biomes that have already been discovered and cached. | **MEDIUM** |
| `checkOnChunkLoads` | Boolean | `false` | Passive check for safe locations within all loaded chunks in range. | **HIGH** — Significant impact on busy servers. |

## Observational Mode (Visitor)

The **Region Data Visitor** allows the plugin to gather data (bad locations, biome harvesting) even when the cache is full, without adding load or triggering worldgen.

| Key | Type | Default | Description | Impact |
|---|---|---|---|---|
| `visitorEnabled` | Boolean | `true` | Enable background observation mode. | **LOW** — Work only happens when cache is full; skips unknown chunks. |

---

## Versioning
- `version`: Internal config version (do not change).
