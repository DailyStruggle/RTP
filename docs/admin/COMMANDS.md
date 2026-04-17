# Command Reference

All RTP commands are subcommands of `/rtp`. The base command requires `rtp.use`.

---

## `/rtp` — Random Teleport

Teleports you to a random safe location in the default region for your current world.

```
/rtp
/rtp player:<player>
/rtp world:<world>
/rtp region:<region>
/rtp region:<region> [shape:<shape>] [vert:<vert>] [price:<price>] [worldborderoverride:<bool>]
/rtp biome:<biome>
/rtp toggletargetperms:<bool>
```

| Parameter | Permission | Description |
|---|---|---|
| *(none)* | `rtp.use` | Teleport yourself |
| `player:<name>` | `rtp.other` | Teleport another online player; target must not have `rtp.notme` |
| `world:<name>` | `rtp.world` + `rtp.worlds.<name>` | Target a specific world |
| `region:<name>` | `rtp.region` + `rtp.regions.<name>` | Target a specific region |
| `biome:<name>` | `rtp.biome` + `rtp.biome.<name>` | Require a specific biome |
| `shape:<name>` | `rtp.params` | Override the shape for this teleport (sub-param of `region`) |
| `vert:<name>` | `rtp.params` | Override the vertical adjustor (sub-param of `region`) |
| `price:<amount>` | `rtp.params` | Override the economy cost for this teleport (sub-param of `region`) |
| `worldborderoverride:<bool>` | `rtp.params` | Override world-border enforcement (sub-param of `region`) |
| `toggletargetperms:<bool>` | `rtp.params` | When `true`, check target player's permissions instead of the sender's |

**Examples:**
```
/rtp
/rtp player:Steve
/rtp world:world_nether
/rtp region:mining biome:BADLANDS
/rtp region:default shape:SQUARE vert:JUMP
```

---

## `/rtp reload` — Reload Configuration

Reloads all RTP configuration files without restarting the server. Region queues are rebuilt after reload.

```
/rtp reload
/rtp reload <config>
```

| Subcommand | Permission | Description |
|---|---|---|
| *(none)* | `rtp.reload` | Reload all configs |
| `<config>` | `rtp.reload` | Reload a specific config by name (e.g., `regions`, `economy`, `performance`) |

---

## `/rtp config` — Edit Configuration

View or modify a configuration value at runtime. Config files are matched as **subcommands**; their individual keys are then registered as **parameters** at runtime.

```
/rtp config <file> <key>:<value>
/rtp config <multifile> <subfile> <key>:<value>
/rtp config <multifile> add:<subfile>
/rtp config <multifile> remove:<subfile>
```

**Subcommands** (not `addParameter` — matched by name at routing time):

| Subcommand | Description |
|---|---|
| `<file>` | Flat config file name (e.g., `performance`, `economy`) |
| `<multifile>` | Multi-config group name (e.g., `regions`) |
| `<subfile>` | Entry within a multi-config group (e.g., `nether`, `default`) |

**Parameters** (registered via `addParameter` on the matched subcommand):

| Parameter | Permission | Description |
|---|---|---|
| `<key>:<value>` | `rtp.config` | Config key registered from the file's own keys; omit `:<value>` to read the current value |
| `add:<subfile>` | `rtp.config` | Create a new entry in a multi-config group (e.g., add a new region) |
| `remove:<subfile>` | `rtp.config` | Delete an entry from a multi-config group |

**Examples:**
```
/rtp config performance maxAttempts:20
/rtp config economy price:100
/rtp config regions nether world:world_nether
/rtp config regions nether shape.radius:128
/rtp config regions add:mining
/rtp config regions remove:mining
```

---

## `/rtp fill` — Pre-generate Location Cache

Manually triggers background pre-generation of safe locations for a region's queue.

```
/rtp fill [region:<region>]
/rtp fill start [region:<region>]
/rtp fill reset [region:<region>]
/rtp fill pause [region:<region>]
/rtp fill resume [region:<region>]
/rtp fill cancel [region:<region>]
```

| Subcommand / Parameter | Permission | Description |
|---|---|---|
| `fill` / `fill start` | `rtp.fill` | Start filling the cache (defaults to all regions) |
| `fill reset` | `rtp.fill` | Clear a region's MemoryShape data without starting a new fill |
| `fill pause` | `rtp.fill` | Pause an active fill operation |
| `fill resume` | `rtp.fill` | Resume a paused fill operation |
| `fill cancel` | `rtp.fill` | Cancel an active fill operation |
| `region:<name>` | `rtp.fill` | Limit the operation to a specific region (available on all fill subcommands) |

**Note:** The cache fills automatically in the background during normal operation. Use `/rtp fill` after a fresh install or after clearing the cache to warm it up immediately.

`/rtp fill reset` only wipes the stored bad-location data for a region — it does **not** start a new fill. Use it when you want a clean slate (e.g., after changing region geometry) without immediately triggering background pre-generation. Follow with `/rtp fill start` if you also want to rebuild the cache.

---

## `/rtp info` — Plugin Information

Displays the current state of the plugin: loaded worlds, regions, queue stats, and performance metrics.

```
/rtp info
/rtp info world:<world>
/rtp info region:<region>
```

| Parameter | Permission | Description |
|---|---|---|
| *(none)* | `rtp.info` | Display all worlds, regions, and runtime stats |
| `world:<name>` | `rtp.info` | Show configuration and state for a specific world |
| `region:<name>` | `rtp.info` | Show configuration and queue state for a specific region |

---

## `/rtp help` — Help

Displays available subcommands and usage hints.

```
/rtp help
/rtp help <subcommand>
```

---

## Full Permission Reference

| Permission | Default | Description |
|---|---|---|
| `rtp.use` | op | Use `/rtp` to teleport yourself |
| `rtp.see` | op | See RTP-related messages |
| `rtp.free` | op | Bypass economy cost |
| `rtp.noCooldown` | op | Bypass cooldown |
| `rtp.noDelay` | op | Bypass teleport delay |
| `rtp.noDelay.chunks` | op | Bypass chunk-load delay |
| `rtp.noCancel` | op | Prevent teleport from being cancelled |
| `rtp.other` | op | Teleport another player |
| `rtp.notme` | op | Exclude yourself when teleporting others |
| `rtp.reload` | op | Use `/rtp reload` |
| `rtp.config` | op | Use `/rtp config` |
| `rtp.info` | op | Use `/rtp info` |
| `rtp.fill` | op | Use `/rtp fill` |
| `rtp.unqueued` | op | Teleport without using the pre-generated queue |
| `rtp.personalqueue` | op | Use a personal location queue |
| `rtp.world` | op | Specify a world parameter |
| `rtp.worlds.<name>` | op | Target a specific world by name |
| `rtp.worlds.*` | op | Target any world |
| `rtp.region` | op | Specify a region parameter |
| `rtp.regions.<name>` | op | Target a specific region by name |
| `rtp.regions.*` | op | Target any region |
| `rtp.params` | op | Override shape and vert parameters |
| `rtp.biome` | op | Specify a biome parameter |
| `rtp.biome.<name>` | op | Target a specific biome |
| `rtp.biome.free` | op | Bypass biome cost |
| `rtp.biome.*` | op | Target any biome |
| `rtp.onevent.join` | false | Auto-RTP on first join |
| `rtp.onevent.firstJoin` | false | Auto-RTP on every join |
| `rtp.onevent.respawn` | false | Auto-RTP on death/respawn |
| `rtp.onevent.changeWorld` | false | Auto-RTP on world change |
| `rtp.onevent.move` | false | Auto-RTP on movement trigger |
| `rtp.onevent.teleport` | false | Auto-RTP on teleport event |
| `rtp.onevent.*` | false | All auto-RTP event triggers |

**Tip:** Grant `rtp.use` to your default player group and `rtp.free` + `rtp.noCooldown` to your VIP/donor group for a typical server setup.
