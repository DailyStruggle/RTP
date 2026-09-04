# effects-api-ADR-007: Command Effect and Death Stage in the Effects Engine

## Status
Accepted

## Context
Prior to this decision, server and player commands triggered around teleports were hardcoded in `config.yml` under `consoleCommands` and `playerCommands` and executed strictly at `postteleport` inside `BukkitEffectsHandler`. This created several limitations:
1. Commands could not be executed at other stages of the teleport lifecycle (such as `preteleport`, `cancel`, `firstjoin`, `join`, or upon `death`).
2. Commands could not be configured per permission node or per effect group in `effects/*.yml`.
3. Commands were not platform-neutral, relying on Bukkit-specific dispatch in `rtp-plugin`.
4. Players dying while teleporting or at any point did not have a dedicated lifecycle stage in the effects engine. While `OnEventTeleports` listened to `PlayerDeathEvent` for respawn calculation, the effects engine lacked a `death` stage for operators to trigger effects (e.g. particles, sounds, titles, commands) on death.

## Decision

### 1. `CommandEffect` in `effects-api`
We introduce `CommandEffect` to `effects-api` under `io.github.dailystruggle.effectsapi.common.effects.CommandEffect`, registered in `EffectFactory` under the identifier `COMMAND`.

#### Parsing and Configuration Syntax
`CommandEffect` supports two execution modes:
- `CONSOLE`: Commands executed with console privileges.
- `PLAYER`: Commands executed as the target player.

In `effects/*.yml`, commands can be written cleanly and human-readably with support for quotes and backslash escapes:
```yaml
effects:
  - COMMAND CONSOLE say Player [player] teleported to [world]!
  - COMMAND PLAYER spawn
  - COMMAND CONSOLE "give [player] diamond 1"
  - COMMAND CONSOLE msg [player] Welcome\ to\ [world]
```

In permission nodes, tokens can use quotes or backslash escapes for whitespace and special characters:
- `rtp.effect.postteleport.command.console.say\ [player]\ has\ landed`
- `rtp.effect.death.command.console.broadcast\ [player]\ died`

#### Placeholder Substitution
The command string supports the following dynamic placeholders:
- `[player]` or `{player}`: The target player's name.
- `[uuid]` or `{uuid}`: The target player's UUID.
- `[world]` or `{world}`: Target location world name (if available).
- `[x]`, `[y]`, `[z]` or `{x}`, `{y}`, `{z}`: Target coordinate integers (if available).

### 2. Platform Command Dispatch via `HandleRegistry`
Command dispatching is abstracted via `HandleRegistry` and `HandleProvider`:
- `PlayerHandle`: Adds `void performCommand(String command)`.
- `HandleProvider`: Adds `void dispatchConsoleCommand(String command)` and `void dispatchPlayerCommand(PlayerHandle player, String command)`.
- `HandleRegistry`: Exposes static entry points `dispatchConsoleCommand` and `dispatchPlayerCommand`.

#### Concurrency and Folia Invariants
- On Bukkit / Spigot / Paper:
  - Console commands run on the main server thread.
  - On Folia, console commands are scheduled via `Bukkit.getGlobalRegionScheduler().run(...)`.
  - Player commands run on the player's entity thread (`player.getScheduler().run(...)` on Folia) or main server thread.
- On Fabric:
  - Console commands execute on the server thread via `MinecraftServer.getCommands().performPrefixedCommand(...)`.
  - Player commands execute via `ServerPlayer.getCommandSource()`.
- On NeoForge:
  - Console commands execute on the server thread via `MinecraftServer.execute(...)` with `server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command)`.
  - Player commands execute via `server.execute(...)` with `server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command)`.

### 3. `death` Stage in the Effects Engine
We add `death` to the standard lifecycle stage vocabulary established in `effects-api-ADR-005`:
- Stage identifier: `death`.
- Permission node prefix: `rtp.effect.death.<effect>...`.
- Configuration trigger: `when: death` in `effects/*.yml`.
- Dispatch hook: Dispatched whenever a player death event occurs on the server (`PlayerDeathEvent` on Bukkit, ServerPlayer death callback on Fabric, `LivingDeathEvent` on NeoForge).

## Consequences

### Positive
- Commands can be triggered on any lifecycle stage, including `death`, `preteleport`, `postteleport`, `cancel`, `join`, etc.
- Commands can be granted via permissions or organized in declarative groups in `effects/*.yml`.
- Death is a first-class effect stage, enabling rich custom animations, sounds, messages, or custom command triggers when players die.
- Full Folia thread-safety for command dispatch.

### Negative / Trade-offs
- Commands run with console authority require careful operator configuration. Permission-driven console commands must be granted only to trusted permission tracks.
