# Integration Settings Reference (`integrations.yml`)

`integrations.yml` controls third-party claim/region protection plugins. Each toggle **rerolls** the teleport destination when it lands inside a claim or region owned by the named plugin. A toggle only takes effect when the corresponding plugin is installed; otherwise it is ignored.

All keys are booleans defaulting to `false`. Set a key to `true` to avoid teleporting players into that plugin's protected land.

---

## Keys

| Key | Plugin | Default | Description |
|---|---|---|---|
| `rerollSaberFactions` | SaberFactions | `false` | Reroll if the location lands inside a SaberFactions claim. |
| `rerollFactionsBridge` | FactionsBridge | `false` | Reroll if the location lands inside Factions territory reported via the FactionsBridge API. FactionsBridge bridges many Factions forks (FactionsUUID, SaberFactions, FactionsX, KingdomsX, ...) behind one API; enable when the FactionsBridge plugin is installed. |
| `rerollGriefDefender` | GriefDefender | `false` | Reroll if the location lands inside a GriefDefender claim. |
| `rerollGriefPrevention` | GriefPrevention | `false` | Reroll if the location lands inside a GriefPrevention claim. |
| `rerollLands` | Lands | `false` | Reroll if the location lands inside a Lands claim. |
| `rerollRedProtect` | RedProtect | `false` | Reroll if the location lands inside a RedProtect region. |
| `rerollResidence` | Residence | `false` | Reroll if the location lands inside a Residence claim. |
| `rerollCrashClaim` | CrashClaim | `false` | Reroll if the location lands inside a CrashClaim claim. |
| `rerollHuskClaims` | HuskClaims | `false` | Reroll if the location lands inside a HuskClaims claim. |
| `rerollKingdomsX` | KingdomsX | `false` | Reroll if the location lands inside KingdomsX claimed land. |
| `rerollTownyAdvanced` | Towny Advanced | `false` | Reroll if the location lands inside a Towny Advanced claim. |
| `rerollWorldGuard` | WorldGuard | `false` | Reroll if the location lands inside a WorldGuard region. |

## Versioning

- `version`: Internal config version (do not change).

---

> Reroll honours the region's bounded selection algorithm; a destination that repeatedly lands in a claim eventually exhausts attempts (`performance.yml` -> `maxAttempts`). See [SAFETY.md](SAFETY.md) and [PERFORMANCE.md](PERFORMANCE.md).
