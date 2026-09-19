# In-Game and Command-Line Configuration

RTP provides two live, interactive methods for inspecting, managing, and updating settings without needing to edit YAML files on disk:

1. **Interactive Admin Panel & Menu** (`/rtp admin` or `/rtp menu` -> Admin panel)
2. **Command-Line Configuration** (`/rtp config <section> <key>=<value>`)

Both methods update settings safely, validate inputs, apply changes immediately in memory, and persist modifications to disk.

---

## 1. The Interactive Admin Panel (`/rtp admin`)

The `/rtp admin` command opens a centralized operator control hub. Rather than functioning solely as a configuration editor, the admin panel provides quick access to setup wizards, diagnostics, scan controls, visual maps, lifecycle management, and the visual configuration editor.

### Opening the Panel
Run either command in-game:
- `/rtp admin` (direct shortcut to the admin control panel)
- `/rtp menu` -> click **Admin Panel**

*(Requires permission `rtp.menu.admin` or server operator status.)*

### Interface Style
- **Paper / Folia / Fabric / NeoForge**: Renders as an interactive, clickable Book interface.
- **Plain Spigot / Bukkit**: Plain Spigot lacks native Adventure support out of the box, so `/rtp menu` degrades to chat/command line interaction (or the GUI addon). On runtimes without native book opening (such as Fabric 1.20.x), it transparently falls back to a clickable chat interface.

### Admin Panel Sections & Features

The panel is organized into five operational sections:

#### A. Setup (Quick Start)
- **Setup Prefabs** (`/rtp menu prefab`): Browse and apply bundled configuration presets (such as standard survival, nether, end, or custom community templates). Selecting a prefab previews the changes and prompts for confirmation before applying.

#### B. Configuration (Visual Config Editor)
- **Config Editor** (`⚙ Config editor`): Opens the dedicated visual configuration editor.
  - **Category Navigation**: The top-level config selector (`config files`) presents direct navigation buttons to individual settings files and subdirectories:
    - **`⚙ Regions`**: Opens the per-region configuration directory ([`REGIONS.md`](REGIONS.md)) to inspect, create, or edit shape, radius, and cache settings for individual regions in `definitions/regions/*.yml`.
    - **`⚙ Worlds`**: Opens the per-world configuration directory ([`WORLDS.md`](WORLDS.md)) to configure default region assignments and permissions in `definitions/worlds/*.yml`.
    - **`⚙ Effects`**: Opens the per-group teleport effects directory ([`EVENTS_AND_EFFECTS.md`](EVENTS_AND_EFFECTS.md)) to manage particle, sound, and potion triggers in `definitions/effects/*.yml`.
    - **`⚙ advanced/`**: Drills into advanced sub-configurations, including:
      - **`performance.yml`**: Cache sizes, pipeline limits, and task scheduling ([`PERFORMANCE.md`](PERFORMANCE.md)).
      - **`logging.yml`**: Logger categories and debug verbosity levels ([`LOGGING.md`](LOGGING.md)).
      - **`metrics.yml`**: Runtime TPS/MSPT monitoring and health metrics ([`METRICS.md`](METRICS.md)).
      - **`database.yml`**: SQL storage and connection pooling settings ([`CONFIGURATION.md#database-persistence-advanceddatabaseyml`](CONFIGURATION.md#database-persistence-advanceddatabaseyml)).
      - **`network.yml`**: Cross-server proxy and transport configuration ([`../proxies/CONFIGURATION.md`](../proxies/CONFIGURATION.md)).
      - **`blocks.yml` / `biomes.yml`**: Block safety checks and biome whitelists/blacklists ([`SAFETY.md`](SAFETY.md)).
    - **`⚙ messages/`** (or `advanced/messages/`): Opens message configuration files ([`MESSAGES.md`](MESSAGES.md)) for `player.yml`, `commands.yml`, `system.yml`, `network.yml`, and `placeholders.yml`.
    - **`config.yml`**: Core plugin settings, teleportation delays, and global defaults ([`CORE_CONFIG.md`](CORE_CONFIG.md)).
    - **`safety.yml`**: Landing safety checks, platform generation, and PvP combat settings ([`SAFETY.md`](SAFETY.md)).
    - **`economy.yml`**: Teleport pricing, per-biome and per-parameter costs, and refunds ([`ECONOMY.md`](ECONOMY.md)).
    - **`language.yml`**: Active language and locale selection ([`LANGUAGE.md`](LANGUAGE.md)).
    - **`⚲ search configs`**: Opens an interactive search prompt to find specific configuration keys across all files.

#### C. Diagnostics & Monitoring
- **Server Info** (`/rtp info`): Opens the runtime status book displaying server platform, plugin version, registered region counts, memory footprint, and active cache queue depth (hot L1, cold L2, backlog L3).
- **Full Diagnostics** (`/rtp test full`): Executes the complete self-test diagnostic suite to verify platform hooks, ticket lifecycles, and scheduler integrity.
- **Memory Tracker Snapshot** (`/rtp test memory`): Dumps active chunk tickets and pipeline task allocations to verify memory health and check for unreleased tickets.
- **Scan Control** (`/rtp menu scan`): Manages the background region safety pre-scanner (start, pause, cancel, and monitor spiral pre-scan progress).
- **Visualizations**: Opens spatial memory maps and distribution views showing pre-scanned safe and unsafe locations across regions.

#### D. Lifecycle
- **Reload** (`/rtp reload`): Triggers a clean live reload of all configuration files, region definitions, and world mappings from disk.

#### E. Command Tree Browser
- **Browse All Commands**: Opens the full reflected `/rtp` command tree, allowing operators to visually navigate and execute any subcommand or parameter.

---

## 2. Prefab Management via Command Line (`/rtp admin prefab`)

In addition to the interactive GUI, admins can inspect and apply setup presets directly from the console or chat:

| Command | Description |
|---|---|
| `/rtp admin prefab list` | Lists all available bundled setup prefabs |
| `/rtp admin prefab apply <id>` | Previews a prefab and generates a one-time confirmation token |
| `/rtp admin prefab confirm <id> <token>` | Applies and commits the selected prefab configuration |
| `/rtp admin prefab rollback <id>` | Reverts configuration to the pre-prefab state |

*(Requires permission `rtp.admin.prefab`.)*

---

## 3. Command-Line Configuration (`/rtp config`)

For fast updates from the console or chat without opening the menu, use the `/rtp config` subcommands.

### Command Syntax
```text
/rtp config <section> <key>=<value>
```

### Available Configuration Sections
| Section | Description | Target File |
|---|---|---|
| `/rtp config language <locale>` | Changes the active language | `language.yml` |
| `/rtp config safety <key>=<value>` | Safety parameters, chunk limits, PvP tags | `safety.yml` |
| `/rtp config performance <key>=<value>` | Pipeline thresholds, cache settings, memory caps | `advanced/performance.yml` |
| `/rtp config economy <key>=<value>` | Teleport pricing, per-biome and per-param costs | `economy.yml` |
| `/rtp config logging <key>=<value>` | Log categories and verbosity levels | `advanced/logging.yml` |
| `/rtp config messages <key>=<value>` | Message strings and system notices | `advanced/messages/*.yml` |
| `/rtp config world <name> <key>=<value>` | World definitions and region overrides | `definitions/worlds/<world>.yml` |
| `/rtp config region <name> <key>=<value>` | Region bounds, shape, radius, and center | `definitions/regions/<region>.yml` |

### Examples
- Change language:
  ```text
  /rtp config language es
  ```
- Change default teleport price:
  ```text
  /rtp config economy price=10.0
  ```
- Adjust safety platform restore time:
  ```text
  /rtp config safety platformRestoreSeconds=120
  ```
- Enable debug logging for the teleport pipeline:
  ```text
  /rtp config logging pipeline=DEBUG
  ```

---

## 4. Direct File Editing (Fallback Method)

If you prefer editing raw YAML files:
1. Open the target configuration file under `plugins/RTP/` using a text editor.
2. Edit the required values and save the file.
3. Run `/rtp reload` in the console or in-game to apply changes.

> See [CONFIGURATION.md](CONFIGURATION.md) for the complete list of configuration files, and [CONFIG_LIFECYCLE.md](CONFIG_LIFECYCLE.md) for how upgrades and reloads are handled.
