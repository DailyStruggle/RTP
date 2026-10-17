# LeafRTP benchmark config (Paper 26.2)

Copied from the Paper 26.2 bench server's `plugins/RTP/` folder (snapshot taken 2026-10-17). Kept: the files that set teleport timing, the landing area, queue sizes and refill pacing, plus the unsafe-block list. Other files on the server were not compared against the shipped defaults.

`config.yml` was last edited on 2026-10-11, after the 2026-10-08 and 2026-10-09 runs cited on the front page. This is the most recent config, not a per-run record.

## What differs from the shipped defaults

| File | Key | Bench | Shipped | Why |
|---|---|---|---|---|
| `config.yml` | `teleportDelay` | `0` | `2` | Same for every plugin: no delays |
| `config.yml` | `teleportCooldown` | `0` | `300` | Same for every plugin: no cooldowns |
| `config.yml` | `consoleCommands` | empty | a `say` broadcast | No chat broadcast per teleport |
| `config.yml` | `defaults.shape.radius` | `16384b` | `4096b` | Same 1,024 to 16,384 block circle as the other plugins (`centerRadius: 64` chunks is the shipped value) |
| `config.yml` | `defaults.shape.mode` | `REROLL` | `ACCUMULATE` | |
| `config.yml` | `defaults.cacheCap` | `10` | `50` | Smaller ready queue |
| `config.yml` | `defaults.backlogCacheCap` | `0` (off) | `1000` | No backlog of unverified spots |
| `config.yml` | `defaults.activeChunkCap` | `5` | `10` | |
| `advanced/performance.yml` | `period` | `1` | `20` | Refill every tick |
| `advanced/performance.yml` | `syncAllottedTime` | `50` | `5` | The current shipped file caps this at 25 |
| `advanced/performance.yml` | `asyncAllottedTime` | `50` | `25` | The current shipped file caps this at 25 |
| `advanced/performance.yml` | `minTPS` | `0.0` (off) | `19.0` | Refill doesn't pause on low TPS |
| `advanced/performance.yml` | `maxHeapPercent` | `95.0` | `85.0` | |
| `advanced/performance.yml` | `maxMSPT` | not in file | `45.0` | The file predates this key |

`backlogGenerationStrategy` is `FLAT_STRIDE`, the shipped default. `advanced/blocks.yml` holds the unsafe-block list used for every landing.
