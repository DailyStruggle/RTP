# Declarative Action Engine

The **Action Engine** (`ADR-093`, `ADR-095`, `ADR-097`, `ADR-098`) is LeafRTP's generalized subsystem for scripted, multi-entity, confined, and anchor-driven teleports.

Instead of writing custom plugins or rigid command chains to handle specialized gameplay modes (such as 1v1 duels, squad arena battles, landing near a random player, or dropping near landmark coordinates), administrators can declare these behaviors as YAML files in `definitions/actions/<id>.yml`.

---

## Overview & Architecture

Each `.yml` file in `definitions/actions/` defines one autonomous action. The action engine handles:
1. **Command Registration:** Automatically registers `/rtp action <id>` and optional standalone custom top-level commands (e.g. `/challenge`, `/duel`, `/nearplayer`).
2. **Anchor Resolution:** Anchors the teleport destination to an entity, a coordinate landmark, a claim perimeter, or a traditional region queue (`ADR-095`).
3. **Subspace Group Placement:** Positions multiple players into an arena or cluster with guaranteed minimum separation (`minSeparation`) and elevation tolerance (`elevationTolerance`) (`ADR-093`).
4. **Confinement & Boundaries:** Restricts players to an active boundary (`SUBSPACE`, `REGION`, `LEASH`, or custom `SHAPE`) with automatic pull-backs for boundary breaches, duration timers, and non-blocking disarm lifecycles.
5. **Scoreboards & Lifecycles:** Tracks session state, violation counts, and timers using isolated dummy scoreboards, executing scripted console/player commands on lifecycle triggers (`onEnqueue`, `onStart`, `onBoundaryViolation`, `onExpire`, `onDeath`, `onCancel`).

---

## Command Syntax

### Running an Action

```text
/rtp action <actionId> [player=<target>] [parameters...]
```

* **Permission:** `rtp.action` (base permission to execute actions) plus the action's specific configured permission (e.g. `rtp.action.<id>`).
* **Cancelling an Active Session:**
  ```text
  /rtp action cancel [session_id]
  ```
  Cancels the calling player's active action session, disarming confinement and running the `onCancel` lifecycle script.

### Standalone Custom Commands

When an action configures a `command:` block, LeafRTP exposes the action directly as a root server command:

```text
/<command_name> [parameters...]
```

For example, the bundled `challenge.yml` action exposes `/challenge [player]` (aliases: `/duelreq`) with subcommands like `/challenge leave`.

---

## Configuration Reference (`definitions/actions/<id>.yml`)

Below is the annotated schema of an action definition file:

```yaml
# Unique alias / display identifier
alias: "challenge"
permission: "rtp.action.challenge"
description: "Symmetrical duel challenge with clickable chat reciprocity."

# (Optional) Expose as a standalone top-level server command
command:
  name: "challenge"
  permission: "rtp.command.challenge"
  description: "Challenge a player to a duel, or accept their pending challenge"
  aliases:
    - "duelreq"
  subcommands:
    leave:
      description: "Forfeit the duel"
      permission: "rtp.command.challenge.leave"
      aliases:
        - "surrender"
        - "forfeit"
      run:
        - CONSOLE: "kill [player_name]"
  parameters:
    - name: "player"
      type: "player"
      required: false
      permission: "rtp.command.challenge.target"
      default: "any"

# Placement & Anchor Configuration
placement:
  # Target world or region
  region: "default"

  # Anchor type: regionQueue | entity | location | nearclaim | scatter
  anchor: "regionQueue"

  # Shape footprint used to arrange participants around the anchor
  shape:
    name: "SQUARE"         # SQUARE, CIRCLE, POLYGON, ELLIPSE, RECTANGLE
    radius: 4c             # Footprint radius (supports 'c' for chunks or integer blocks)
    centerRadius: 0

  # Minimum spacing between teleported participants (blocks)
  minSeparation: 24

  # Maximum allowed Y-elevation delta across participants
  elevationTolerance: 64

  # Number of pre-validated placement slots to warm in background cache
  cacheSize: 5

# Confinement & Boundary Enforcement
confinement:
  # Boundary type: NONE | SUBSPACE | REGION | LEASH | SHAPE
  boundary: "SUBSPACE"

  # Session duration (e.g. 3m, 30s, 0s for one-shot instantaneous actions)
  duration: "3m"

  # Leash radius in blocks (when boundary is LEASH)
  leashRadius: 48.0

  # Custom shape boundary (when boundary is SHAPE)
  shape:
    name: "CIRCLE"
    radius: 5c

  # Whether players can cancel their own session
  cancellable: false

# Matchmaking & Reciprocity Gates
gate:
  # Minimum number of players required to trigger the match
  - players: ">= 2"
  # Minecraft command condition checks
  - command:
      - "execute if entity @a[name=[sender_name_1],tag=rtp_chal_[target_name_1]]"
      - "execute if entity @a[name=[sender_name_2],tag=rtp_chal_[target_name_2]]"

# Lifecycle Script Triggers
lifecycle:
  onEnqueue:
    - CONSOLE: "tag [sender_name] add rtp_chal_[target_name]"
    - MESSAGE: "<gray>Challenge sent to <yellow>[target_name]</yellow>! Waiting for response...</gray>"
    - MESSAGE: "<gold>[sender_name] has challenged you! </gold><green><bold><click:run_command:'/rtp action challenge player=[sender_name]'>[CLICK TO ACCEPT]</click></bold></green>"

  onStart:
    - CONSOLE: "tag [sender_name_1] remove rtp_chal_[sender_name_2]"
    - CONSOLE: "tag [sender_name_2] remove rtp_chal_[sender_name_1]"
    - FOR_EACH:
        - MESSAGE: "<green><bold>Duel match started! Prepare to fight!</bold></green>"

  onBoundaryViolation:
    - gate:
        scoreboard:
          objective: "rtp_violations"
          matches: "< 3"
      run:
        - ACTION: "PULL_BACK"
        - MESSAGE: "<red>Stay within the arena boundary!</red>"

  onExpire:
    - FOR_EACH:
        - MESSAGE: "<yellow><bold>Duel match ended in a draw! Time expired.</bold></yellow>"
    - ACTION: "DISARM"

  onDeath:
    - FOR_EACH:
        - MESSAGE: "<red>[player_name] was defeated in the duel!</red>"
    - ACTION: "DISARM"

  onCancel:
    - CONSOLE: "tag [sender_name] remove rtp_chal_[target_name]"
    - MESSAGE: "<gray>Challenge was cancelled.</gray>"

version: "1.0"
```

---

## Anchor Types (`ADR-095`)

Anchor providers define where the center point of the subspace placement is rooted:

| Anchor | Description | Use Case |
|---|---|---|
| `regionQueue` | Sourced from LeafRTP's warm region location buffer | Standard arena or match placement inside a configured world region. |
| `entity` | Sourced dynamically from an active entity or target player's live position | Teleporting near an online player (`nearplayer`). |
| `location` | Fixed coordinates specified in configuration (`x`, `y`, `z`, `world`) | Dropping groups near landmark structures, ruins, or world spawn. |
| `nearclaim` | Edge perimeter of a claimed territory or protected land | Faction raiding, base exploration, or siege encounters. |
| `scatter` | Alias of `regionQueue` (any unrecognised value also falls back to the region queue) | One-shot group scatters drawn from the target region's pre-warmed buffer. |

---

## Bundled Sample Actions

LeafRTP ships with built-in production templates in `definitions/actions/`:

1. **`nearplayer.yml`**
   * Drops a player into a safe ring (`radius: 6c`, `centerRadius: 1c`) around a random active player using `anchor: entity`. Reproduces competitor "nearplayer" mechanics natively on the unified subspace engine.
2. **`nearclaim.yml`**
   * Teleports players near a claim perimeter without inline claim-plugin overhead.
3. **`challenge.yml`**
   * Symmetrical mutual 1v1 duel system featuring interactive clickable chat challenge prompts (`/challenge <player>`), reciprocal tag verification, arena boundary confinement, pull-back enforcement, and death handling.
4. **`quickchallenge.yml`**
   * One-shot instant duel pairing without lingering session tracking (`duration: 0s`).
5. **`location.yml`**
   * Fixed coordinate landmark anchoring.
