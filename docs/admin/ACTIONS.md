# Declarative Action Engine

The **Action Engine** handles teleports that go beyond a plain `/rtp`: group placements, teleports near a player or landmark, confined arenas, and teleports started by stepping into a portal or zone.

Instead of writing custom plugins or rigid command chains to handle specialized gameplay modes (such as 1v1 duels, squad arena battles, landing near a random player, dropping near landmark coordinates, or portal triggers), administrators can declare these behaviors as YAML files in `definitions/actions/<id>.yml`.

---

## Overview & Architecture

LeafRTP does not ship hardcoded minigames. It provides building blocks - group placement, boundaries, conditions, scoreboards, and lifecycle scripts - and you describe each game mode (a duel, a team scatter) in YAML, calling vanilla commands where you need custom behavior.

Each `.yml` file in `definitions/actions/` defines one autonomous action. The action engine handles:
1. **Command Registration:** Automatically registers `/rtp action <id>` and optional standalone custom top-level commands (e.g. `/challenge`, `/duel`, `/nearplayer`, `/spawnzone`).
2. **Anchor Resolution:** Picks the center point for the teleport: a location from a region's prepared queue, a live player, fixed landmark coordinates, the edge of a known claim, or a claim/faction border.
3. **Group Placement:** Places several participants together with a minimum distance between them (`minSeparation`) and a maximum height difference (`elevationTolerance`). Searching happens in the background and gives up after a fixed number of retries.
4. **Confinement & Shrinking Boundaries:** Keeps players inside a boundary (`SUBSPACE`, `REGION`, `LEASH`, or custom `SHAPE`), shows them a world border, can shrink it over time (sudden death), deals damage outside it, and pulls players back inside.
5. **Physical World Triggers:** Activates actions via cuboid trigger zones (portals, launch pads, step-in thresholds) with support for wave-accumulator spatial lobbies (`/rtp trigger` and `triggers:` specs).
6. **Scoreboards & Lifecycles:** Tracks session state, violation counts, and timers using isolated dummy scoreboards, executing scripted console/player commands on lifecycle triggers (`onEnqueue`, `onStart`, `onBoundaryViolation`, `onExpire`, `onDeath`, `onCancel`).

---

## Command Syntax & Permissions

### 1. Executing an Action (`/rtp action`)

```text
/rtp action <actionId> [player=<target>] [parameters...]
```

* **Permissions:**
  * `rtp.action`: Base permission required to execute the action command tree.
  * `rtp.action.<id>`: Specific permission required for the targeted action (configured via `permission:`, defaults to `rtp.action.<id>`).
  * `rtp.action.other`: Required when executing actions on behalf of another player via `player=<target>`.

### 2. Cancelling Queues & Disarming Sessions (`/rtp action cancel`)

```text
/rtp action cancel [actionId] [player=<name>] [session=<uuid>]
```

The `cancel` subcommand handles two distinct operational lifecycles:

1. **Pending Wait-Queue Cancellation:**
   * Removes the calling player from matchmaking wait-queues (e.g. cancelling a duel request or lobby wait before placement).
   * Executes the action's `onCancel` lifecycle script.
   * Permission: `rtp.action.cancel`.
2. **Active Match Session Disarming:**
   * **Self-Cancellation (`cancellable: true`):** If the action definition sets `confinement.cancellable: true` (default), active participants can cancel their own running session.
   * **In-Match Restrictions (`cancellable: false`):** Competitive arenas and duels (such as `challenge.yml` and `arena.yml`) enforce `cancellable: false`. Regular participants cannot cancel mid-match; participants exit by surrendering through custom subcommands (e.g. `/duel leave`) or by dying.
   * **Administrative Force-Disarm:** Server operators can disarm any active session by session UUID (`/rtp action cancel session=<uuid>` or `/rtp action cancel <uuid>`) or for a specific participant (`/rtp action cancel player=<target>`).
   * Permission: `rtp.action.cancel.other` (or `rtp.*`).

### 3. Physical Trigger Management (`/rtp trigger`)

> ⚠️ **Roadmap status:** The `/rtp trigger` runtime command surface and WorldEdit selection integration are tracked on the roadmap for an upcoming release. In the current release, physical triggers are configured directly via the `triggers:` block in action YAML definitions (see [Physical Triggers & Spatial Lobbies](#physical-triggers--spatial-lobbies) below).

```text
/rtp trigger <create|remove|list> [args...]
```

Physical triggers bind in-game cuboid bounding boxes (such as portal frames, spawn thresholds, or launch pads) directly to actions.

| Command | Description | Permission |
|---|---|---|
| `/rtp trigger create <id> <actionId> [radius]` | Creates a physical trigger centered at your current location (default 3x3x3 block box). | `rtp.trigger` |
| `/rtp trigger remove <id>` | Unregisters a physical trigger by ID. | `rtp.trigger` |
| `/rtp trigger list` | Lists all registered physical triggers, target actions, worlds, and coordinate bounds. | `rtp.trigger` |

Triggers can also be declared permanently inside action YAML definitions via the `triggers:` block.

### 4. Standalone Custom Commands (`command:`)

When an action configures a `command:` block, LeafRTP exposes the action as a root server command during startup (e.g. `/challenge [player]`, `/duel`, `/nearplayer`):

```text
/<command_name> [parameters...]
/<command_name> <subcommand>
```

* **Startup Registration:** Top-level action commands and aliases are registered when the plugin starts. Adding or renaming a command or alias requires a server restart; `/rtp reload` updates placement, confinement, and lifecycle scripts but does not add or remove commands.
* **Built-in Cancel Subcommand:** Top-level commands automatically inherit a nested `cancel` subcommand (e.g. `/challenge cancel`).
* **Custom Subcommands:** Actions can declare custom subcommands (such as `/challenge leave` or `/duel surrender`) with dedicated permissions and execution scripts.

---

## Configuration Reference (`definitions/actions/<id>.yml`)

Below is the comprehensive schema for an action definition:

```yaml
# Unique alias / display identifier
alias: "arena"
permission: "rtp.action.arena"
description: "Confined 1v1 arena duel with shrinking world border and forfeit subcommands."
icon: "DIAMOND_SWORD"
title: "&c&l1v1 Arena Duel"

# (Optional) Top-level server command registration
command:
  name: "duel"
  permission: "rtp.command.duel"
  description: "Challenge players to an arena duel"
  aliases:
    - "fight"
    - "arena"
  subcommands:
    leave:
      description: "Surrender and exit the duel"
      permission: "rtp.command.duel.leave"
      aliases:
        - "surrender"
        - "forfeit"
      run:
        - CONSOLE: "kill [player_name]"
  parameters:
    - name: "player"
      type: "player"
      required: false
      permission: "rtp.command.duel.target"
      default: "any"              # 'any' for open matchmaking, 'self' for caller

# Placement & Anchor Configuration
placement:
  # Target world or parent region (inherits spatial memory, biomes, and verifiers)
  region: "default"

  # Anchor source: regionQueue | fixed | entity | claimHazard | claimBoundary | scatter
  anchor: "regionQueue"

  # Footprint shape used to arrange participants around the anchor coordinate
  shape:
    name: "SQUARE"               # SQUARE, CIRCLE, POLYGON, ELLIPSE, RECTANGLE
    radius: 4c                   # Footprint radius: 4c = 4 chunks = 64 blocks (64b also works)
    centerRadius: 0              # Inner exclusion ring (donut center); 0 = none

  # Spacing and elevation constraints
  minSeparation: 32              # Minimum blocks between participants
  elevationTolerance: 256        # Maximum Y-delta across participants

  # Retries and pre-checked caching
  retries: 3                     # Background attempts before the action fails with a message (default: 3)
  cacheSize: 5                   # Pre-warmed placement candidate slots (default: 0)

  # Anchor-specific parameters (e.g. for fixed landmarks or faction namespaces)
  parameters:
    anchorX: 0                   # Fixed landmark X coordinate (for anchor: fixed)
    anchorY: 64                  # Fixed landmark Y coordinate
    anchorZ: 0                   # Fixed landmark Z coordinate
    anchorWorld: "world"         # Landmark world (defaults to placement.region world)
    namespace: "factions"        # Claim boundary namespace (for anchor: faction)

# Confinement & Boundary Enforcement
confinement:
  # Boundary type: NONE | SUBSPACE | REGION | LEASH | SHAPE
  boundary: "LEASH"

  # Match duration (e.g. 5m, 30s, 0s for instantaneous one-shot actions)
  duration: "5m"

  # Leash radius in blocks (when boundary is LEASH)
  leashRadius: 48.0

  # Custom shape boundary (when boundary is SHAPE)
  shape:
    name: "CIRCLE"
    radius: 5c

  # Sudden-death dynamic world border shrinking
  initialSize: 128               # Initial world border width/diameter in blocks
  shrinkTo: 32                   # Final world border width in blocks
  shrinkOver: "4m"               # Shrink duration (e.g. 4m, 240s)
  damage: 2.0                    # Damage applied per interval when outside boundary
  damageBuffer: 0.0              # Distance outside border before damage begins
  damageInterval: 1s             # Frequency of boundary damage
  maxDistanceOutside: 8.0        # Hard boundary threshold for outside action
  outsideActions:
    - ACTION: "PULL_BACK"        # Declarative action on hard boundary breach

  # In-match participant cancellation toggle
  cancellable: false             # false = participants cannot cancel mid-match

# Physical World Triggers & Spatial Lobbies
triggers:
  - id: "arena_portal"
    type: "PORTAL"               # PORTAL, PRESSURE_PLATE, STEP_IN
    world: "world"
    pos1: { x: 100, y: 64, z: 100 }
    pos2: { x: 103, y: 68, z: 103 }
    cooldown: 5s                 # Per-player trigger cooldown
    batchInterval: 10s           # Wave accumulator lobby timer (0s = instant)

# Matchmaking & Reciprocity Gates
gate:
  # Participant count condition
  - players: ">= 2"

  # Vanilla Minecraft command conditions (e.g. reciprocity tags)
  - command:
      - "execute if entity @a[name=[sender_name_1],tag=rtp_chal_[target_name_1]]"
      - "execute if entity @a[name=[sender_name_2],tag=rtp_chal_[target_name_2]]"

# Lifecycle Script Execution
lifecycle:
  onEnqueue:
    - FOR_EACH:
        - MESSAGE: "<yellow>Entered duel matchmaking queue. Waiting for an opponent...</yellow>"
        - MESSAGE: "<gray>Run <click:run_command:'/duel cancel'><u>/duel cancel</u></click> to exit queue.</gray>"

  onStart:
    - CONSOLE: "tag [sender_name_1] remove rtp_chal_[sender_name_2]"
    - CONSOLE: "tag [sender_name_2] remove rtp_chal_[sender_name_1]"
    - FOR_EACH:
        - CONSOLE: "gamemode adventure [player]"
        - CONSOLE: "give [player] iron_sword 1"
        - MESSAGE: "<green><bold>Duel started! Fight to the death!</bold></green>"

  onBoundaryViolation:
    # First violation: warning and safe pull-back
    - gate:
        scoreboard:
          objective: "rtp_violations"
          matches: "< 2"
      run:
        - ACTION: "PULL_BACK"
        - FOR_EACH:
            CONSOLE: "title [violator] subtitle {\"text\":\"Stay inside the world border!\",\"color\":\"yellow\"}"

    # Repeated violation: disqualification
    - gate:
        scoreboard:
          objective: "rtp_violations"
          matches: ">= 2"
      run:
        - CONSOLE: "tellraw @a [{\"text\":\"[violator] fled the arena and was disqualified!\",\"color\":\"red\"}]"
        - CONSOLE: "kill [violator]"
        - ACTION: "DISARM"

  onExpire:
    - FOR_EACH:
        - CONSOLE: "gamemode survival [player]"
        - MESSAGE: "<yellow><bold>Match timed out! Ended in a draw.</bold></yellow>"
    - ACTION: "DISARM"

  onDeath:
    - CONSOLE: "tellraw @a [{\"text\":\"[winner] defeated [victim] in the arena!\",\"color\":\"gold\"}]"
    - CONSOLE: "give [winner] diamond 3"
    - FOR_EACH:
        - CONSOLE: "gamemode survival [player]"
    - ACTION: "DISARM"

  onCancel:
    - FOR_EACH:
        - MESSAGE: "<gray>Duel challenge / matchmaking was cancelled.</gray>"

version: "1.0"
```

---

## Anchor Types

The anchor decides where the center of the placement is. Anchors are resolved in the background and do not load chunks on the main thread:

| Anchor Key | Aliases | Origin & Resolution Mechanism | Unresolved Fallback |
|---|---|---|---|
| `regionQueue` | `scatter` | Polled from LeafRTP's pre-warmed shared region location buffer. (Note: `scatter` is an alias of `regionQueue`, drawing from the parent region's pre-warmed queue). | On-demand generation from parent region. |
| `fixed` | `location`, `landmark` | Sourced from static landmark coordinates (`anchorX`, `anchorY`, `anchorZ`, `anchorWorld`) defined in `placement.parameters` or context metadata. | Falls back to `regionQueue`. |
| `entity` | `player`, `nearplayer` | Targets an online player or entity. If none is given, picks a random online player who is not a participant. | The action fails with a message; it never quietly falls back to random terrain. |
| `claimHazard` | `claim`, `nearclaim` | Uses the region's memory of spots that earlier teleports rejected because they were claimed, and targets the edge of such a claim. It does not call the claim plugin. | The action fails with a message if no claimed spots have been recorded yet. |
| `claimBoundary` | `faction` | Looks up the exact claim or faction border of the first participant (Towny, GriefPrevention, SaberFactions, FactionsUUID; namespace `factions` for `faction`, overridable via `namespace`). | Falls back to `claimHazard`. |

---

## Placement & Cache Tuning

Group placement happens in the background, in two passes:

1. **Pre-screening:** LeafRTP picks about twice as many candidate spots as it needs inside the footprint and checks the saved region files on disk for biome, rivers, and oceans. No chunks are loaded in this pass.
2. **Validation:** The best candidates are checked against each other for height difference and minimum separation, then their chunks are loaded in the background and checked for safe blocks.
3. **Retries (`retries`):** Minimum `1` (default `3` for actions). If a claim plugin or an unsafe block rejects a set, LeafRTP remembers those chunks as rejected and tries a new anchor.
4. **Caches (`cacheSize`):** For actions whose anchor does not move (`scatter`, `location`, `nearclaim`, `arena`), `cacheSize: 3` keeps three ready-checked placement sets in memory so the action starts faster.
5. **Recheck before teleport:** A cached set is checked again for safe blocks and claims right before players are teleported. Sets that no longer pass are thrown away and their chunks are released.

---

## Confinement & Sudden-Death Arenas

The confinement engine restricts participants to an active spatial boundary:

### Boundary Modes

* `NONE`: No spatial confinement. Placement teleports participants and immediately disarms.
* `SUBSPACE`: Restricts players to the generated subspace footprint centered on the anchor.
* `REGION`: Restricts players to the parent region's shape. If the region has `expand: true`, the boundary grows with it and never shrinks.
* `LEASH`: Circle around the anchor, radius in blocks (`leashRadius: 48.0`).
* `SHAPE`: Custom shape boundary (a nested `shape:` block with `name: CIRCLE` and `radius: 5c`, i.e. 80 blocks).

### Clientbound Visual World Borders

When `boundary` is set to `LEASH` or `SUBSPACE`, participants see a world border centered on the anchor. Only session participants see it, and it is removed when the session ends.

### Sudden-Death Shrinking Borders

For battle-royale and duel arenas, the world border can shrink over time:
* `initialSize`: Starting border diameter in blocks (e.g. `128` or `8c`).
* `shrinkTo`: Target final border diameter in blocks (e.g. `32`).
* `shrinkOver`: Duration of the shrinking phase (e.g. `4m` or `240s`).
* `damage`: Amount of damage applied when outside the border (e.g. `2.0`).
* `damageBuffer`: Grace buffer distance outside the border before damage applies (default `0.0`).
* `damageInterval`: Interval between damage applications (e.g. `1s`).
* `maxDistanceOutside`: Maximum distance outside the boundary before triggering emergency `outsideActions` (such as `ACTION: PULL_BACK` or elimination).

---

## Physical Triggers & Spatial Lobbies

Actions can be activated by physical world triggers declared in `triggers:` or created via `/rtp trigger create`:

```yaml
triggers:
  - id: "spawn_portal"
    type: "PORTAL"               # PORTAL | PRESSURE_PLATE | STEP_IN
    world: "world"
    pos1:
      x: -10
      y: 64
      z: -10
    pos2:
      x: -8
      y: 68
      z: -8
    cooldown: 5s
    batchInterval: 15s           # Spatial lobby timer (0s for instant teleport)
```

RTP's YAML reader does not accept inline `{ ... }` maps; a file that uses them is quarantined as unparseable. Write `pos1` / `pos2` as nested blocks, as above.

### Wave Accumulator Spatial Lobbies (`batchInterval > 0`)

When `batchInterval` is set to a non-zero duration (e.g. `15s`), the trigger functions as a **Wave Accumulator Spatial Lobby**:
1. Stepping into the trigger volume registers the player as an active lobby occupant without immediately teleporting.
2. The trigger periodically ticks countdown notifications (evaluating the action's `onEnqueue` lifecycle with tokens like `[countdown]`, `[occupants]`, and `[action]`).
3. If an occupant leaves the bounding volume prior to expiration, they are removed from the wave.
4. When the countdown timer reaches zero, all accumulated occupants inside the volume are dispatched simultaneously into the target action as a synchronized group!

---

## Declarative Parameters & Permissions

Actions with custom `command:` blocks declare parameter validation and authoritative fallback defaults:

```yaml
command:
  name: "challenge"
  permission: "rtp.command.challenge"
  parameters:
    - name: "player"
      type: "player"             # player | coordinate | region | world | number | string
      required: false
      permission: "rtp.command.challenge.target"  # Permission required to target another player
      default: "any"             # 'any' = open matchmaking; 'self' = caller only
```

* **Targeting Protection:** Teleporting or challenging another player requires an explicit permission node (e.g. `rtp.command.<action>.target` or `rtp.command.<action>.other`). Without it, the command is refused with the configurable `noPerms` message.
* **Authoritative Defaults:** When an argument is omitted, the declared `default` dictates behavior:
  * `player` type: `self` defaults to the caller; `any` leaves the target open for matchmaking.
  * `coordinate` type: `self` or `spawn`.
  * `region` / `world` type: `self`.

---

## Lifecycle Scripts, Placeholders, & Scoreboards

### Script Actions

If a command in a lifecycle block fails, the error is logged and the remaining commands and cleanup still run:

* `CONSOLE: <command>`: Executes a command at server console privilege.
* `PLAYER: <command>`: Executes a command as the participant.
* `MESSAGE: <text>`: Sends an Adventure MiniMessage formatted chat message to participant(s).
* `MESSAGE_TARGET: <text>`: Sends a message specifically to the targeted opponent in a 2-player challenge.
* `ACTION: PULL_BACK`: Safely relocates a boundary violator back to a verified interior landing slot.
* `ACTION: DISARM`: Disarms confinement, destroys clientbound world borders, and clears active session tracking.
* `FOR_EACH:`: Loops over all session participants.

### Lifecycle Phases

* `onEnqueue`: Executed when a player enters a matchmaking queue or spatial trigger lobby.
* `onStart`: Executed immediately when group placement succeeds and participants teleport.
* `onBoundaryViolation`: Executed when a participant steps outside the confinement boundary.
* `onExpire`: Executed when the session duration timer reaches zero.
* `onDeath`: Executed when a participant dies or surrenders during an active match session.
* `onCancel`: Executed when a pending queue or cancellable session is cancelled.

### Available Tokens & Placeholders

All placeholders are sanitized before execution to prevent command injection:
* `[player]`, `[player_name]`, `[player_uuid]`: Current participant.
* `[sender]`, `[sender_name]`, `[sender_uuid]`: Challenge initiator.
* `[target]`, `[target_name]`, `[target_uuid]`: Challenge target.
* `[violator]`: Participant who breached the confinement boundary.
* `[winner]`, `[victim]`: Victor and defeated participant on death or surrender.
* `[session_id]`: Unique UUID of the active session.
* `[countdown]`, `[time_left]`, `[time_remaining]`: Remaining seconds.
* `[action]`, `[trigger]`: Action and trigger identifiers.

### Universal Scoreboards

LeafRTP automatically maintains dummy scoreboard objectives for state inspection without external bridge plugins:
* `rtp_session_id`: Active session token hash.
* `rtp_time_left`: Remaining seconds before session expiration.
* `rtp_violations`: Counter incremented on boundary breaches.
* `rtp_in_bounds`: Flag (`1` inside boundary, `0` in violation).
* `rtp_dist_sq`: Squared distance from anchor coordinate.
* `rtp_alive`: Count of living participants in the session.

These scoreboards work with vanilla target selectors (e.g. `@a[scores={rtp_violations=..2}]`), command blocks, and datapacks.

**Startup Orphan Reaper:** If a server crashes mid-session, LeafRTP's startup reaper sweeps and removes all lingering `rtp_session_*` tags and objectives on restart, preventing stranded score states.

---

## Bundled Production Templates

LeafRTP ships with built-in production templates in `definitions/actions/`:

1. **`scatter.yml`**
   * Single-player or group party scatter using `anchor: regionQueue`. Disperses queued parties across natural terrain with guaranteed minimum spacing (`minSeparation: 32`).
2. **`nearplayer.yml`**
   * Drops a player into a ring 16 to 96 blocks (`centerRadius: 1c`, `radius: 6c`) from a random online player using `anchor: entity`.
3. **`nearclaim.yml`**
   * Lands players just outside claims that earlier teleports ran into (`anchor: claimHazard`), without querying the claim plugin.
4. **`location.yml`**
   * Teleports players around a fixed landmark structure (`anchor: fixed`, `anchorX/Y/Z`) with permissions restricting targeting of others (`rtp.command.location.other`).
5. **`challenge.yml`**
   * Symmetrical 1v1 duel system with interactive clickable chat prompts, reciprocal tag verification, arena boundary confinement, pull-back enforcement, and forfeit subcommands (`/challenge leave`).
6. **`quickchallenge.yml`**
   * One-shot instant duel pairing without lingering session tracking (`duration: 0s`).
7. **`arena.yml`**
   * 1v1 PvP arena duel with visual shrinking world border (`initialSize: 128`, `shrinkTo: 32`, `shrinkOver: 4m`), boundary damage, disqualification on repeated violations, surrender subcommand (`/duel leave`), and victory rewards.
8. **`default.yml`**
   * Baseline random scatter template used as the default profile for newly generated actions.
