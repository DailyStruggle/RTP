# FlightSpeedHelper

Standalone Paper and Folia helper plugin providing unclamped `/flyspeed` multipliers beyond CraftBukkit's `[-1.0f, 1.0f]` (`10x`) cap and `/fly` toggling for high-speed world and storage streaming demos (such as `LeafAnvil` `.lca` flyovers).

Lives under [`helpers/`](..) with zero `rtp-api`, `rtp-core`, or `LeafAnvil` dependencies.

---

## Why this exists

1. Vanilla `/effect give <player> minecraft:speed` only scales ground `MOVEMENT_SPEED`, and `/attribute <player> minecraft:flying_speed` only affects AI mobs (`Player#getFlyingSpeed()` reads `Abilities#getFlyingSpeed()` directly).
2. CraftBukkit's `CraftPlayer#setFlySpeed(float)` validates inputs in `[-1.0f, 1.0f]` and divides by `2`, capping standard plugins (such as EssentialsX `/speed 10`) at `Abilities.flyingSpeed = 0.5f` (`10x` vanilla creative's `0.05f` default).
3. `net.minecraft.world.entity.player.Abilities#setFlyingSpeed(float)` and `ClientboundPlayerAbilitiesPacket` transmit an unclamped IEEE-754 `float` directly to the vanilla client. Writing `Abilities#setFlyingSpeed(0.05f * multiplier)` and invoking `ServerPlayer#onUpdateAbilities()` cleanly enables `15x`, `25x`, `50x+` flight speeds without client mods.

---

## Commands & Permissions

| Command | Usage | Description |
|---|---|---|
| `/flyspeed` (alias `/fspeed`) | `/flyspeed <multiplier\|reset> [player]` | Sets flight speed where `1` = vanilla (`0.05`), `10` = Bukkit max (`0.50`), and `>10` = unclamped NMS `Abilities` (up to `max-multiplier`, default `100.0`). Automatically enables `allowFlight(true)`. |
| `/fly` | `/fly [on\|off\|toggle] [player]` | Toggles or sets flight allowance in Survival/Adventure. |

- `flightspeedhelper.use` (default: `op`): Adjust own flight speed and flight state.
- `flightspeedhelper.others` (default: `op`): Adjust another online player's flight speed and flight state.

---

## Anti-Rubberband & Paper Chunk Streaming

- When `suppress-moved-too-quickly: true` (default in `config.yml`), `FlightSpeedHelper` allows Paper's `PlayerFailMoveEvent` (`FailReason.MOVED_TOO_QUICKLY`) for players currently holding a boosted multiplier (`> 1.0x`) while flying.
- For high-speed flyovers across wide view distances (`view-distance=32`), pair this helper with `allow-flight=true` in `server.properties` and raised per-player chunk rates in `config/paper-global.yml` (`player-max-chunk-load-rate: -1.0`, `player-max-chunk-send-rate: 500.0`).

---

## Build

```bash
./gradlew :helpers:FlightSpeedHelper:build
```

Output JAR: `helpers/FlightSpeedHelper/build/libs/FlightSpeedHelper-<version>.jar`.
