# Custom Item & Menu Icon Integration

LeafRTP's bundled GUI addon (`LeafRTPGuiAddon`) supports custom items from third-party plugins and vanilla mechanics as destination and filler icons in `plugins/RTP/addons/guimenu.yml`. This allows servers using custom resource packs, 3D models, or head databases to match their `/rtp` menu with the rest of their custom theme.

All third-party item integrations are decoupled through reflection and soft-dependencies (following ADR-026). The plugin carries zero shaded third-party dependencies, prevents classpath conflicts, and degrades gracefully to standard vanilla materials when a custom item plugin is absent or encounters invalid IDs.

---

## Supported Icon Syntaxes

Icon strings in `guimenu.yml` (such as `iconDefault`, `iconWorld`, `iconRegion`, `regionIcons`, and `menuFiller`) accept prefix-routed item specifiers:

| Prefix / Pattern | Provider | Example | Description | License / Cost |
|---|---|---|---|---|
| `ia:<id>` or `itemsadder:<id>` | ItemsAdder | `ia:my_items:ruby_sword` | Resolves custom items from ItemsAdder via `CustomStack`. | Commercial (Proprietary) |
| `oraxen:<id>` | Oraxen | `oraxen:amethyst_blade` | Resolves custom items from Oraxen via `OraxenItems`. | Commercial (Proprietary) |
| `nexo:<id>` | Nexo | `nexo:void_axe` | Resolves custom items from Nexo via `NexoItems`. | Open Source (GPL-3.0, Free) |
| `hdb:<id>` | HeadDatabase | `hdb:9999` | Resolves custom skull heads by HeadDatabase ID. | Commercial (Proprietary) |
| `head:<name>` | Player Skin | `head:Notch` | Creates a player head with the specified player's skin. | Vanilla (Free) |
| `base64:<hash>` | Skull Texture | `base64:eyJ0ZXh0dXJlcyI6...` | Creates a custom textured player skull from a Base64 string. | Vanilla (Free) |
| `<MAT>:<cmd>` or `<MAT>#<cmd>` | CustomModelData | `DIAMOND_SWORD:1005` | Vanilla material with CustomModelData integer applied to item meta. | Vanilla (Free) |
| `<MATERIAL>` | Vanilla Bukkit | `COMPASS` | Standard vanilla Minecraft material. | Vanilla (Free) |

---

## Visual Status Cues & Barrier Blocks

To provide immediate, at-a-glance readability, `guimenu.yml` includes the `barrierOnUnavailable` setting:

- **`barrierOnUnavailable: true` (default)**: When a player cannot teleport to a destination (due to active cooldown, PvP combat tag, insufficient funds, or missing permission), the destination icon is replaced with a `BARRIER` block (or configured `iconUnavailable`).
- **`barrierOnUnavailable: false`**: Preserves the destination's original custom icon regardless of availability status.

### Dynamic Lore Templates

Hover tooltips display status-specific lore lines configured in `guimenu.yml` under `loreTemplates`:

- `loreReady`: Applied when the player is permitted and ready to teleport.
- `loreCooldown`: Applied when the player is waiting on an active cooldown.
- `loreCombat`: Applied when the player is tagged in active PvP combat.
- `loreNoFunds`: Applied when the player lacks sufficient economy balance.
- `loreNoPermission`: Applied when the player lacks permission for the region.

### Template Placeholders

Hover templates support dynamic placeholder substitution:

- `{status}`: Human-readable availability status (`READY`, `ON COOLDOWN`, `IN COMBAT`, `LOCKED`).
- `{cooldown}`: Remaining cooldown or combat tag duration formatted in concise units (e.g. `1m 30s`, `45s`).
- `{delay}`: Warmup delay duration before teleportation initiates.
- `{cost}`: Configured teleport price or `Free`.
- `{target}`: Destination target name.
- `{world}`: Target world name.
- `{region}`: Target region name.

---

## Best-Effort Support Policy

Support for third-party item plugins is provided on a **best-effort basis** due to commercial licensing constraints and testing complexity:

### Proprietary Licensing & Testing Constraints
Commercial plugins such as ItemsAdder, Oraxen, and HeadDatabase carry individual commercial price tags and closed-source licenses. Because proprietary binaries cannot be legally redistributed or checked into public repositories, automated open-source continuous integration (CI) pipelines cannot execute live server tests against commercial releases.

### Verification Architecture
To ensure reliable integration without violating third-party licenses:

1. **Public API Contract Stubs**: LeafRTP maintains standalone mock stubs (`helpers/MockItemPlugins`) modeling official public API contracts (`CustomStack`, `OraxenItems`, `NexoItems`, `HeadDatabaseAPI`).
2. **Automated Reflection Tests**: Unit test suites (`BukkitCustomItemResolverTest`) verify that reflection signatures and item builders resolve correctly when plugins are present.
3. **Container Acceptance Fixtures**: LeafRTP provides automated devstack harnesses (`devstack/docker-compose.compat.yml`, `devstack/test-item-compat.ps1`) to stage stub jars and verify live server loading.
4. **Free & Open-Source Options**: For operators seeking fully tested, cost-free alternatives, **Nexo** is open-source (GPL-3.0), while **CustomModelData** (`MATERIAL:CMD`) and **Base64 textured heads** are completely native to vanilla Minecraft.

### Defensive Fallbacks
If a custom item plugin is not installed, disabled, or an invalid item identifier is provided:
- LeafRTP logs a single clear diagnostic notice to the console once on startup.
- The item resolver degrades gracefully to the configured vanilla fallback material (`Material.COMPASS` or `Material.BARRIER`).
- Teleport menus never throw `ClassNotFoundException` or crash the player's inventory screen.

If an upstream update in a proprietary plugin changes its public API signatures or breaks reflective access, please report it via the issue tracker, and the integration will be updated on a best-effort basis.
