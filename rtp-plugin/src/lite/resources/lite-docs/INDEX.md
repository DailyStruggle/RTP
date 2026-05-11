# RTP-lite — Bundled Admin Docs

This folder is extracted from the lite jar on first start to
`plugins/RTP/lite-docs/`. It is a **stripped** subset of the full RTP admin
documentation, scoped to features that ship in the lite assembly (ADR-024).

For the full documentation set (SQL persistence, Folia, claim integrations,
multilingual support, login reserve cache, visitor mode, economy, etc.) use
the **RTP-Pro** jar, which ships the complete `docs/` tree.

## Contents

- `QUICK_START.md` — install, first region, verify.
- `COMMANDS.md` — commands & permissions available in lite.
- `CONFIGURATION.md` — config files actually shipped in lite.
- `SAFETY.md` — biome / block filters (flat allow/deny list only in lite).
- `FAQ.md` — common lite-specific questions.
- `figures/region-shape.txt` — ASCII diagram of region shape parameters.
- `figures/spiral-mapping.txt` — ASCII diagram of the Archimedean spiral.

## Lite scope reminder

Not present in lite (use RTP-Pro):

- SQL / Redis persistence (`H2`, `SQLite`, `MySQL`, `PostgreSQL`, Jedis).
- Folia adapter (Spigot / Paper / Fabric only).
- Claim-plugin integrations (GriefDefender, GriefPrevention, Lands, WorldGuard,
  Towny, Factions, HuskTowns, RedProtect).
- Economy / Vault hook.
- Multilingual support (`lang/**`, `language.yml`); locale is English only.
- Login reserve cache (`PerformanceKeys.loginCacheEnabled` / `loginCacheCap`).
- Visitor / observation mode (`PerformanceKeys.visitorEnabled`).
- `effectParsing` / `onEventParsing` startup permission parses.

Lite still ships: full selection surface (all shapes/modes/adjustors), pregen
queue, `MemoryTracker`, `S-005` stale-chunk guard, anvil pre-filter, scan task
+ spatial memory, biomeRecall, `commands-api` + Brigadier bridge, and
`effects-api` runtime.
