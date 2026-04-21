# YAML Simplification Plan

Status: **draft / deferred** — 2026-04-19

This document captures **functional** simplifications that could be made to the
plugin's bundled YAML resources (`rtp-plugin/src/main/resources/**/*.yml`).
The commenting/documentation pass completed on 2026-04-19 is **comment-only**
— none of the items below have been acted on. Each item is listed with a
risk note so a future change can be scoped safely.

Scope of this document: the files under `rtp-plugin/src/main/resources/` at
the top level (`config.yml`, `economy.yml`, `logging.yml`, `messages.yml`,
`performance.yml`, `plugin.yml`, `safety.yml`) and one directory down
(`lang/*.lang.yml`, `regions/default.yml`, `worlds/default.yml`).

The deeper `lang/shape/*.yml` and `lang/vert/*.yml` trees are out of scope
for this plan.

---

### Cross-cutting constraints

Any change that removes or renames a *key* touches three places at once and
must be treated as a coordinated refactor:

1. The schema YAML under `resources/` (e.g. `config.yml`).
2. The matching `lang/<name>.lang.yml` entry (the enum → display-name map).
3. The enum class in `rtp-core` that backs the `FactoryValue` (e.g.
   `ConfigKeys`, `EconomyKeys`, `SafetyKeys`, `LoggingKeys`,
   `PerformanceKeys`, `MessagesKeys`, `GenericRegionKeys`, `WorldKeys`).
4. User-migration impact: bumping the file `version:` field triggers
   `ConfigParser.check(...)` to rewrite the user's copy. Any removed key is
   silently dropped from the user's config on the next load. Any renamed key
   loses its customised value unless the parser is taught to migrate it.

Guardrails:

- REQ-RTP-F-013 — every user-facing message **shall** remain configurable
  via `messages.yml`. Do **not** remove any `MessagesKeys` entry without a
  replacement.
- REQ-RTP-S-004 — every failure path **shall** produce a player-visible
  message. Removing a `MessagesKeys` entry that is wired into a failure path
  (e.g. `unsafe`, `badArg`, `invalidCommand`, `busy`,
  `consoleCmdNotAllowed`, `noPerms`) would violate S-004.
- `GLOSSARY.md` — renamed keys that correspond to domain terms need a
  glossary update.

---

### Candidate simplifications — top-level schema files

#### `config.yml`

- **Empty `playerCommands` entry** — the bundled default is
  `playerCommands: [ - "" ]`. The empty string is consumed as a no-op
  command. It would be cleaner to ship `playerCommands: []`, **but** doing
  so today causes the key to disappear from the `/rtp config` command
  surface (empty lists are not registered as `ListCmd` sub-commands, and
  the YAML round-trip may drop the key entirely). Full root-cause analysis
  and fix plan: see `docs/admin/EMPTY_LIST_CONFIG_PLAN.md`. **Do not remove
  the sentinel** until that plan is executed.
- **`database:` credentials for local types** — `host`, `port`, `name`,
  `username`, `password` are ignored when `type` is `yaml` or `sqlite`. A
  future simplification would be to split the `database:` block into a
  tagged union (e.g. `database: { type: sqlite }` vs.
  `database: { type: mysql, host: ..., ... }`). Risk: breaks the existing
  flat shape; requires a migration step in `ConfigParser.check(...)`.

#### `economy.yml`

- **`paramsPrice: 1000000000.0`** — the billion-dollar default is
  intentional (it's a soft deny) but is surprising and undocumented in the
  default. A reader-friendlier default would be to ship a much lower value
  and let users opt into a deny-price explicitly. **Deferred** — this is a
  UX change, not a simplification.

#### `logging.yml`

- **Mixed snake_case and camelCase** — `detailed_reload`,
  `detailed_region_init`, `event_changeworld`, `event_join`,
  `event_respawn`, `event_move`, `event_teleport`,
  `selection_failure`, `system_memory_tracker`, `system_database` are
  snake_case while every *other* config file uses camelCase. Unifying on
  camelCase would be a pure-cosmetic rename. **Risk: Medium** — key rename
  requires enum + lang mapping + migration path; users with existing
  `logging.yml` files would silently lose their settings without a
  migration hook.

#### `messages.yml`

- **`infoDisclaimerHeader` / `infoDisclaimer`** — only rendered by the
  console variant of `/rtp info`. Consider folding into `infoTitle` or a
  single multi-line key. **Risk: Low–Medium** — touches `RTP.log` output
  formatting and `InfoCmd`.
- **`PLAYER_*` placeholders** — five flat keys
  (`PLAYER_AVAILABLE`, `PLAYER_COOLDOWN`, `PLAYER_SETUP`, `PLAYER_LOADING`,
  `PLAYER_TELEPORTING`) could become a nested `playerStatus:` map. **Risk:
  Medium** — requires `MessagesKeys` enum refactor and changes to the
  PlaceholderAPI bridge; purely cosmetic benefit.
- **Section 11 / Section 12 ordering** — `version:` (Section 11) is placed
  before `showDevTag:` (Section 12). Moving `version:` to the very end (as
  in every other schema file) would be a trivial reorder.

#### `performance.yml`

- **`viewDistanceSelect` vs. `viewDistanceTeleport`** — both default to `0`
  and in practice are almost always set together. Consider collapsing into
  a single `viewDistance:` key or a nested `viewDistance: { select, teleport }`.
  **Risk: Medium** — breaks existing configs.
- **`syncAllottedTime` / `asyncAllottedTime`** — same observation; could be
  `allottedTime: { sync, async }`. Same risk profile.

#### `safety.yml`

- **`airBlocks` list is huge (~190 entries)** — dominated by per-wood sign
  and per-wood button variants that are trivially enumerable at runtime
  from `Material#isAir()` and the `Tag.*` constants. A future refactor
  could:
  1. Ship an empty default `airBlocks: []` and have `SafetyKeys` fall back
     to a code-side baseline of `Material#isAir()` + `Tag.SIGNS` +
     `Tag.BUTTONS` + `Tag.FLOWERS` + `Tag.SAPLINGS`.
  2. Only list user overrides in the YAML.
  - **Risk: High** — this is behavioral. Any Bukkit API version drift
    (modded forks, pre-1.20 servers) could flip the effective air set.
    Requires platform-adapter coverage for `Tag.*` availability and a
    comprehensive test matrix against every supported Minecraft version.
- **`# NOTE: the flat-id GRASS ...` comment** — the comment on line ~69–70
  exists because of the 1.20.3 rename. If/when pre-1.20.3 support is
  dropped, the comment can go.

#### `plugin.yml`

- ~~**`rtp.onevent.firstJoin`** is declared but is **not** included in the
  `rtp.onevent.*` aggregate children.~~ **Fixed 2026-04-19** —
  `rtp.onevent.firstJoin: true` added to the aggregate. The listener logic
  in `OnEventTeleports.onPlayerJoin` already branches on `firstJoin`
  (uses `!player.hasPlayedBefore()`) ahead of the general `join` branch,
  so the permission is functionally distinct from `rtp.onevent.join`
  (firstJoin fires once per player; join fires on every login). Operators
  who want *only* first-join behavior should grant `rtp.onevent.firstJoin`
  directly rather than the aggregate.
- ~~**`rtp.*` aggregate** omits `rtp.unqueued`.~~ **Fixed 2026-04-19** —
  `rtp.unqueued: true` added to the `rtp.*` aggregate children. (The
  permission was previously only reachable via the `rtp.params` aggregate,
  which was inconsistent with the "`rtp.*` grants every admin capability"
  contract.)
- **`rtp.*` aggregate intentionally omits `rtp.onevent.*`.** Confirmed by
  the plugin owner on 2026-04-19: the auto-RTP-on-event permissions are
  opt-in by design and must not be granted implicitly by an operator-wide
  aggregate. Do **not** add `rtp.onevent.*: true` to the `rtp.*` children.

#### `worlds/default.yml` and `regions/default.yml`

- Already minimal; no simplification recommended.

---

### Candidate simplifications — `lang/*.lang.yml`

All eight files are identity maps (`key: key` for every entry). The
identity default is already synthesised at runtime by
`ConfigParser.loadLangFile(...)` when the file is missing (see
`rtp-core/src/main/java/io/github/dailystruggle/rtp/common/configuration/ConfigParser.java`
~line 204). The bundled English `.lang.yml` files are therefore **entirely
redundant in the English-default case**.

Possible simplification: **stop shipping the English-default lang files**
and only generate them on first run (which already happens). Users who want
a translation would drop in a non-identity `.lang.yml`. This would remove
eight files and ~200 lines.

- **Risk: Low (behavior)** — `ConfigParser.loadLangFile` already handles
  the missing-file path by generating identity defaults.
- **Risk: Medium (coupling)** — a handful of tests copy from
  `rtp-plugin/src/main/resources/lang/` into `rtp-core/target/test-configs/`
  and `rtp-core/target/test-data/`. Those fixtures would need to be
  regenerated or updated.
- **Benefit**: every future enum addition no longer requires adding a
  matching identity row to the lang file (a maintenance trap that has
  caused drift in the past).

Recommendation: **defer**. The files are harmless as-shipped and serve as a
discoverable template for translators. Revisit only if the resource set is
being reorganised for other reasons.

---

### Execution checklist (for any future simplification PR)

Before shipping any item from this plan:

- [ ] Bump the relevant `version:` string in the affected schema file.
- [ ] Add a migration arm to `ConfigParser.check(...)` (or the per-file
      update hook) so existing user configs are rewritten without losing
      customisations.
- [ ] Update the matching enum in `rtp-core`.
- [ ] Update the matching `lang/<name>.lang.yml` entry.
- [ ] Add a REQ-traceable test (see `docs/dev/COVERAGE_PLAN.md`,
      `docs/dev/TRACEABILITY.md`) if the change is behavioural.
- [ ] Update `docs/admin/MIGRATION.md` with the user-facing change.
- [ ] If a key corresponds to a domain term, update
      `docs/dev/GLOSSARY.md`.
