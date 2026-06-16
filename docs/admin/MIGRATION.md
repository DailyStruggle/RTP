# Migration Guide

**Current Plugin Version:** `3.0.0-beta.1`

This document provides upgrade instructions for server operators and addon developers when moving between RTP versions.

> 📎 For the **mechanics** of how RTP upgrades on-disk YAML files (what `.old1`/`.old2` files are, how your customizations are read into memory before the file is replaced, and how they are overlaid onto the new defaults), see [CONFIG_LIFECYCLE.md](configuration/CONFIG_LIFECYCLE.md).

---

## Upgrading to 3.0.0-beta.1

> ⚠️ **This is a MAJOR version release.** The `rtp-api` public interface has breaking changes. Addon developers must recompile against the new `rtp-api` jar and review the source changes listed below.

### Summary of Breaking Changes

| Area | Change | Action Required |
|------|--------|-----------------|
| `rtp.fill` -> `rtp.scan` | The `rtp.fill` permission and `/rtp fill` command have been renamed to `rtp.scan` and `/rtp scan`. | Update your permission plugin (e.g., LuckPerms) to use `rtp.scan` instead of `rtp.fill`. |
| `rtp-api`: `ChunkReservation` added | Chunk ticket lifecycle is now managed via the `ChunkReservation` class (implements `AutoCloseable`) in `rtp-api`. | Addons that previously managed chunk tickets directly must migrate to `ChunkReservation`. |
| `rtp-api`: `CachedLocation` is now a record | `CachedLocation` has been refactored from a mutable class to an immutable Java record. | Any addon code that mutated `CachedLocation` fields directly must be updated to construct a new instance instead. |
| PaperLib removed | The `rtp-paper` adapter no longer depends on PaperLib. Native Paper async chunk APIs are used directly. | Remove PaperLib from your server's `plugins/` folder if RTP was its only consumer. |
| Folia support added | A new `rtp-folia` adapter is available for Folia servers. | Folia operators: use the new `rtp-folia` build. |
| Platform version targets | Spigot, Paper, and Folia targets updated to 26.1. | Ensure your server software is on a 26.1-compatible build. |

### Configuration Files

No configuration keys were renamed, removed, or restructured in 3.0.0-beta.1. Existing `config.yml`, `performance.yml`, `safety.yml`, `economy.yml`, `worlds/`, and `regions/` files are fully forward-compatible — no edits required.

### Database / Spatial Memory Cache

The spatial memory format (`MemoryShape` bad-sector index ranges) is unchanged. Your existing cache will be read correctly after upgrade — no rebuild required.

If you want a clean slate (e.g., after significantly changing a region's geometry), delete the relevant database entries or run:
```
/rtp scan reset
```

### Addon Developers (`rtp-api` consumers)

This is a **MAJOR** bump. You must recompile your addon against the new `rtp-api` jar. Review the following source-level changes:

1. **`ChunkReservation`** is now part of `rtp-api`. If your addon previously interacted with chunk tickets directly, replace that logic with `ChunkReservation` (use try-with-resources — it implements `AutoCloseable`).
3. **`CachedLocation`** is now an immutable record. Replace any field-mutation code with construction of a new `CachedLocation` instance.
4. All other `rtp-api` interfaces (`RTPEconomy`, `RTPCommandSender`, `RTPPlayer`, `RTPScheduler`, `ILocationGenerator`, `RTPServerAccessor`, `RTPWorld`, `RTPChunk`) remain unchanged.

---

## Upgrading from 2.0.18 to 3.0.0-beta.1

> The changes that shipped in the `2.0.18` tag are now fully documented under [Upgrading to 3.0.0-beta.1](#upgrading-to-300-beta) above. `2.0.18` was the last 2.x release; its changes (PaperLib removal, Folia adapter, platform target upgrade) were subsequently re-tagged as `3.0.0-beta.1` due to the breaking `rtp-api` changes introduced at the same time. Follow the 3.0.0-beta.1 instructions above.

---

## Upgrading from versions before 2.0.18

Detailed per-commit history is available via `git log`. For versions prior to 2.0.18, consult the [SpigotMC resource page](https://www.spigotmc.org/resources/rtp.94812/) changelog or open a [GitHub issue](https://github.com/DailyStruggle/RTP/issues) for upgrade assistance.

---

## General Upgrade Procedure

1. **Back up** your `plugins/RTP/` folder (configs, database files).
2. **Stop** the server.
3. **Replace** the RTP jar with the new version.
4. **Remove PaperLib** from `plugins/` if upgrading to 3.0.0-beta.1+ on a Paper server and PaperLib was only used by RTP.
5. **Start** the server. RTP will load existing config and cache files automatically.
6. Run `/rtp info` to confirm the new version is active.
7. Run `/rtp reload` if you want to force a full config re-read.
