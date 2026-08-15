# Common RTP Recipes

Many servers reach for a second plugin (or a pile of Skript/ConditionalEvents glue) to bolt a common behavior onto random teleport: open a menu, scatter a player on first join, re-scatter them on death, skip claimed land, show a cooldown on a scoreboard, charge money, or pre-generate the map.

In LeafRTP most of those outcomes are **native config, a permission, or a typed API** - no glue plugin required. This page is the recipe book: one section per common "I want RTP to do X" task, with the LeafRTP-native way to do it.

!!! tip "Menus are covered separately"
    The single most common request is to put a destination menu in front of players. The built-in menu is configured in [config.yml](configuration/CORE_CONFIG.md#menu); to build your own GUI on the typed API, see [For addon developers](../FOR_ADDON_DEVELOPERS.md). This page covers everything else.

---

## RTP on first join (random spawn for new players)

**What you want:** scatter brand-new players into the world instead of dropping everyone on the same spawn.

**The LeafRTP way:** grant a permission. LeafRTP listens for the join itself and runs a full, safe, async teleport through the normal pipeline (cache, spatial memory, and safety checks all apply).

| Permission | Fires when |
|---|---|
| `rtp.onevent.firstjoin` | the player joins the server for the **first time** |
| `rtp.onevent.join` | the player joins (every login) |

```yaml
# LuckPerms example: scatter brand-new players, but not returning ones
permissions:
  rtp.onevent.firstjoin: true
```

!!! note "These are opt-in and not granted to OPs by default"
    Every `rtp.onevent.*` node is `default: false` in `plugin.yml` - even operators must be granted it explicitly. That is deliberate: an admin testing `/op` should not get silently teleported on every login. Grant the node to the group you actually want scattered (usually `default`).

!!! tip "First-join destination is just a region"
    The join teleport uses the player's world-to-region mapping like any `/rtp`. To send new players somewhere specific, configure that world's [region](configuration/REGIONS.md) (center, radius, shape) - you do not need a separate "spawn radius" setting. A login-reserve cache keeps a destination ready so first-join feels instant.

---

## RTP on respawn / death (deathban-style scatter)

**What you want:** when a player dies, wake them up somewhere random instead of at spawn or a bed.

**The LeafRTP way:** grant `rtp.onevent.respawn`. LeafRTP prepares a destination *at the moment of death* so it is ready by the time the player clicks respawn, and it respects the player's cooldown (a player who just teleported is not re-scattered).

| Permission | Fires when |
|---|---|
| `rtp.onevent.respawn` | the player dies / respawns |

```yaml
permissions:
  rtp.onevent.respawn: true
```

!!! warning "Order with bed/anchor and `/back`"
    LeafRTP cancels any in-flight teleport on death and then prepares the respawn destination. If you also run a "respawn at bed" plugin, decide which one wins - granting `rtp.onevent.respawn` means LeafRTP will move the player after vanilla respawn logic. Test the interaction on your stack.

---

## RTP on world change (per-world entry scatter)

**What you want:** scatter players into the wild when they enter a survival world from a lobby/hub.

**The LeafRTP way:** grant `rtp.onevent.changeworld`. A player entering a world they have the node for is scattered into that world's region. Teleports that LeafRTP itself triggered are skipped, so this does not loop.

| Permission | Fires when |
|---|---|
| `rtp.onevent.changeworld` | the player moves to a different world |

```yaml
permissions:
  rtp.onevent.changeworld: true
```

---

## Order RTP after a limbo / auth lobby

**What you want:** new players authenticate in a limbo or auth lobby first, and only get scattered once they actually reach the survival world - not while they are still logging in.

**The LeafRTP way:** drive the scatter off the **world the player ends up in**, not off the raw login. Two patterns work:

- **Use `rtp.onevent.changeworld` instead of `.join`.** If your auth flow lands the player in a `limbo`/`auth` world and then sends them to `world` after login, grant `changeworld` (not `join`). The scatter fires when they enter the survival world, after authentication, and never fires in limbo (limbo has no region configured, so there is nothing to scatter into).
- **Restrict the trigger world by region mapping.** LeafRTP only scatters into a world that has a region mapped to it. Leave your lobby/limbo worlds unmapped (no region), and only the survival world will ever trigger an on-event teleport - even if the player technically "joins" into the lobby first.

!!! warning "Don't grant `rtp.onevent.join` on an auth-lobby stack"
    `join` fires on every login, including the login that lands the player in limbo. On an auth-lobby setup that can teleport an unauthenticated player or fight with the auth plugin's own move. Prefer `changeworld`, and confirm the auth plugin finishes its world move before LeafRTP's trigger by testing the exact handoff on your stack.

!!! tip "Login-reserve cache still applies"
    Because the destination world has a region, its login-reserve cache keeps a coordinate ready, so the scatter still feels instant the moment the player crosses into the survival world.

---

## Skip claimed / protected land

**What you want:** never land a player inside someone else's claim or a protected region.

**The LeafRTP way:** the `reroll*` toggles in [integrations.yml](configuration/INTEGRATIONS.md). Each key, when set to `true`, makes LeafRTP reject and re-roll a destination that falls inside that plugin's protected land. A toggle is ignored unless its plugin is installed.

```yaml
# integrations.yml - avoid teleporting into protected land
rerollWorldGuard: true
rerollTownyAdvanced: true
rerollLands: true
rerollFactionsBridge: true     # bridges FactionsUUID, SaberFactions, FactionsX, KingdomsX, ...
rerollGriefPrevention: true
```

Supported plugins (full list on the [Integrations](configuration/INTEGRATIONS.md) page): SaberFactions, FactionsBridge, GriefDefender, GriefPrevention, Lands, RedProtect, Residence, CrashClaim, HuskClaims, KingdomsX, Towny Advanced, WorldGuard.

!!! note "This is a safety guarantee, not just a convenience"
    Re-rolling out of claims is enforced inside the teleport pipeline itself, so a destination cannot leak into protected land through the menu, the command, or the API. The reroll honours the bounded spiral selector and eventually exhausts attempts (`performance.yml` -> `maxAttempts`) rather than looping forever. See [Safety](configuration/SAFETY.md).

---

## Show cooldown / queue / region info on a scoreboard, tab, or hologram

**What you want:** display the player's cooldown, the cache depth, or region info in a scoreboard, tab list, or hologram.

**The LeafRTP way:** LeafRTP **registers its own PlaceholderAPI expansion** under `rtp`, so any PAPI-aware plugin can read `%rtp_<key>%` directly - no glue.

```text
%rtp_region%        ->  default
%rtp_cached%        ->  37          # ready-to-serve locations
%rtp_locationQueue% ->  2           # players waiting on a coordinate
%rtp_memCoveragePct%->  64          # how much of the region spatial memory has mapped
```

The same keys work as `[key]` inside LeafRTP's own [messages.yml](configuration/MESSAGES.md) **without** PlaceholderAPI installed. The full key catalog (region info, cache/queue depth, pipeline latency percentiles, spatial-memory coverage) lives on the [Integrations](configuration/INTEGRATIONS.md) page.

---

## Charge for a teleport / monetize RTP

**What you want:** make `/rtp` cost in-game currency, or sell teleports.

**The LeafRTP way:** [economy.yml](configuration/ECONOMY.md) (requires Vault + an economy plugin). Set a base price, an extra cost for teleporting others, and refund behavior; per-region price overrides live in each `regions/<name>.yml`.

```yaml
# economy.yml
price: 100.0          # cost of a standard /rtp
priceOther: 500.0     # cost of /rtp player=<name>
refundOnCancel: true  # refund + reset cooldown if the teleport is cancelled by movement
balanceFloor: 0.0     # don't let players go into debt
```

---

## Pre-generate / warm the map

**What you want:** make sure `/rtp` always has somewhere fast and safe to land, even for the very first players.

**The LeafRTP way:** `/rtp scan`. LeafRTP does not pre-generate the whole world; it pre-**verifies** candidates off the main thread and **remembers** which sectors are unsafe (persistent spatial memory), so the selector learns to avoid loading bad ground at all. The longer a region runs, the faster and more reliable its teleports get.

The recommended bring-up sequence for a new region is:

1. **Install** LeafRTP and start the server once so it generates its config tree.
2. **Configure** the region fully - center, radius, and shape - in its [region file](configuration/REGIONS.md). Do not scan before the geometry is final; changing a region's shape clears its learned data by design.
3. **Scan** each region whose size is reasonable:

   ```text
   /rtp scan start region=<name>   # walk the region's spiral, building spatial memory off-tick
   /rtp scan pause                 # pause the running scan
   /rtp scan resume                # resume a paused scan
   /rtp scan cancel                # stop
   /rtp scan reset region=<name>   # discard learned data and rebuild deliberately
   ```

   A bare `/rtp scan` resumes or starts a scan. The permission node is `rtp.scan`.

!!! tip "Scan is the preferred pre-generation tool, but it is not mandatory"
    Player traffic alone will populate spatial memory over time; `/rtp scan` just front-loads that learning so the very first players already get fast, reliable teleports. A separate world pre-generator is not required - they are complementary - but you do not need one to make `/rtp` fast. See [Commands](COMMANDS.md) for the full `/rtp scan` verb set and [Runbook](RUNBOOK.md) for operational guidance.

!!! warning "Only scan reasonably-sized regions"
    A scan walks the whole region's spiral. For an effectively unbounded region (e.g. a multi-million-block radius) a full scan is impractical; let traffic-driven learning and the location cache do the work there, and reserve `/rtp scan` for regions with a finite, sensible radius.

---

## Trigger RTP from an NPC, sign, or clickable item

**The simple way:** point a Citizens NPC, a clickable sign, or an item-interact plugin at the `/rtp` command. LeafRTP's command works exactly like any other, so existing command-runner setups work - just point them at `/rtp` (or `/rtp region=<name>`).

**The cleaner way for plugins:** if you are writing the integration yourself, do not shell out to the command. Bind the typed API instead and get a real result back (success / queued / reason), so your NPC or item can give the player accurate feedback. See [For addon developers](../FOR_ADDON_DEVELOPERS.md) for the `RTPAPI.teleport(...)` pattern, the root-action hook, and the full behavior-modification catalog.

---

## At a glance

| You want RTP to... | LeafRTP-native solution |
|---|---|
| Open a destination menu / GUI | Built-in [menu](configuration/CORE_CONFIG.md#menu), or [build your own](../FOR_ADDON_DEVELOPERS.md) on the typed API |
| Scatter new players on first join | Grant `rtp.onevent.firstjoin` |
| Scatter players on every login | Grant `rtp.onevent.join` |
| Scatter players on death | Grant `rtp.onevent.respawn` |
| Scatter players entering a world | Grant `rtp.onevent.changeworld` |
| Avoid teleporting into claims | `integrations.yml` `reroll*` toggles |
| Show cooldown / queue / region info | Native `%rtp_*%` PlaceholderAPI expansion |
| Charge money per teleport | `economy.yml` (+ Vault) |
| Always have a fast, safe landing spot | `/rtp scan start region=<name>` |
| Fire RTP from an NPC / sign / item | Point it at `/rtp`, or bind `RTPAPI.teleport(...)` |

## See also

- [Commands](COMMANDS.md) - the full command and permission reference, including every `rtp.onevent.*` trigger.
- [Integrations](configuration/INTEGRATIONS.md) - the claim/region reroll toggles and the PlaceholderAPI key catalog.
- [Events and effects](configuration/EVENTS_AND_EFFECTS.md) - how event-triggered teleports and effects are wired.
- [For addon developers](../FOR_ADDON_DEVELOPERS.md) - the typed menu/GUI and behavior-hook API.
