# Plan: Empty Lists Disappear From `/rtp config`

**Status:** Proposal — no code changes yet.
**Owner:** _unassigned_
**Related:** `docs/admin/YAML_SIMPLIFICATION_PLAN.md` (entry for `playerCommands` sentinel empty-string), REQ-RTP-F-013 (configurability), REQ-RTP-S-004 (no silent discards).

---

## 1. Problem Statement

An empty YAML list such as:

    playerCommands: []

**resolves to a no-op for the `/rtp config` runtime surface.** The key exists on
disk and is honored by the runtime (e.g. `BukkitEffectsHandler` reads
`ConfigKeys.playerCommands` with a `new ArrayList<>()` default), but:

1. It is not visible via `/rtp config config playerCommands …` tab-completion.
2. It cannot be edited (add / remove entries) via the command tree.
3. On an in-place file rewrite (triggered by editing _any_ sibling key), the
   empty list may be dropped from the on-disk representation entirely,
   depending on the serializer round-trip.

The net effect: **admins who wipe a list cannot re-populate it via the
in-game command**, and operators auditing the config via `/rtp config` cannot
see that the key exists at all. This is a silent usability and discoverability
defect — arguably a soft REQ-RTP-F-013 violation (the key is configurable via
file edit but not via the documented command surface).

Today’s bundled default `playerCommands: [ - "" ]` (a one-element list whose
only entry is the empty string) is itself a workaround for this exact bug:
it is a "keep-alive sentinel" that prevents the key from disappearing.
`BukkitEffectsHandler` silently skips empty-string commands, so the sentinel
is functionally a no-op at runtime but has structural side-effects in the
command tree.

## 2. Root Cause

Two independent issues compound each other.

### 2.1 `ConfigParser.check()` skips null values

`rtp-core/.../configuration/ConfigParser.java` (≈ line 286):

    for (E v : myClass.getEnumConstants()) {
      Object name = language_mapping.get(v.name());
      if (name == null) name = v.name();
      Object fromString = yamlFile.get(name.toString());
      if (fromString != null) {
        data.put(v, fromString);        // <-- only populated when non-null
      }
    }

`YamlFile#get` returns `null` for absent keys, but for an **empty list** it
typically returns an empty `ArrayList`, not `null`. So this branch is _not_
the primary cause for `playerCommands: []`. However, if the key is absent
entirely (which is the state after a round-trip that drops the empty list,
see 2.3), `data` never gets the entry, and `addParameters()` below never
sees it.

### 2.2 `SubConfigCmd.addParameters()` dispatches by runtime type

`rtp-core/.../commands/config/SubConfigCmd.java` (≈ line 389):

    } else if (o instanceof List) {
      Supplier<Set<String>> values = HashSet::new;
      // ...
      YamlFile yamlFile = configParser.fileDatabase.cachedLookup.get().get(configParser.name);
      if (yamlFile != null) addSubCommand(new ListCmd(name, this, values, yamlFile, s));
    }

When `data` has no entry for the enum key (either because YAML returned null
or because the key was dropped on a previous save), this branch is never
entered, so no `ListCmd` is registered, and the key is invisible to the
command tree. There is no `else` that registers a default list editor for
known-list-typed enum keys.

### 2.3 YAML round-trip of `[]`

Depending on the `simpleyaml` writer settings and the specific serializer
path used on `/rtp config` edits, an empty list may be written back as:

- `playerCommands: []` (preserved — desirable), **or**
- `playerCommands:` (null scalar — `yamlFile.get` then returns `null`), **or**
- the key is omitted entirely (worst case).

Any of the last two states feeds back into 2.1/2.2 and the key disappears.

### 2.4 Why the current sentinel "works"

`[ - "" ]` is never empty at the YAML layer, so `instanceof List` succeeds in
`SubConfigCmd` and `ListCmd` is wired up. `BukkitEffectsHandler` then ignores
the empty string at execution time. This hides the bug but locks us into a
non-obvious bundled default.

## 3. Goals

1. A user can delete every entry from a list-typed config value and still see
   and edit the key via `/rtp config`.
2. The bundled `config.yml` ships a clean `playerCommands: []` (no sentinel),
   without regressing discoverability.
3. Empty lists survive a `/rtp config` round-trip to disk verbatim.
4. No REQ-RTP-S-004 regression: any rejected / unknown user input still
   produces a player-visible message and a `Level.WARNING` log.

## 4. Proposed Fix (High-Level)

**Strategy: register list editors by _declared schema_, not by _observed value_.**

Today, `SubConfigCmd` learns the type of each enum key by looking at the
current runtime value. This is fragile for any empty / unset state. Instead,
we should teach the config schema to declare its expected Java type per enum
key, and have `SubConfigCmd` dispatch on that declaration.

### 4.1 Option A — Schema annotations on the enum (preferred)

Add a lightweight type tag to each config-key enum:

- `ConfigKeys.playerCommands` → tagged as `LIST<STRING>`.
- `ConfigKeys.consoleCommands` → tagged as `LIST<STRING>`.
- `SafetyKeys.unsafeBlocks` → tagged as `LIST<BLOCK>` (drives tab-completion).
- `SafetyKeys.biomes` → tagged as `LIST<BIOME>`.

`SubConfigCmd.addParameters()` then iterates `myClass.getEnumConstants()`
(not `configParser.getData()`), consults the tag, and always registers a
`ListCmd` / `BooleanParameter` / etc. regardless of whether the runtime
value is null, empty, or populated.

Impact:

- Touches `rtp-api` (tag enum or annotation) and every `*Keys` enum in `rtp-core`.
- `SubConfigCmd` shrinks: fewer `instanceof` branches, no value-peeking.
- `ConfigParser.check()` can still use `data` for runtime lookups; the
  schema-walk is orthogonal.

Risk: medium. Requires disciplined maintenance of the schema tag whenever
a new key is added. Can be enforced with an ArchUnit / reflection test.

### 4.2 Option B — Keep value-dispatch, add an "unknown list" fallback

Minimal-change alternative. In `SubConfigCmd.addParameters()`, after the
current loop over `configParser.getData()`, walk
`myClass.getEnumConstants()` a second time; for each enum key **not** in
`data`, inspect the default from the bundled jar resource (`saveResource`
path) and register a `ListCmd` if the default is a list.

Impact:

- Smaller blast radius, no enum signature changes.
- Still fragile: we now depend on the jar default to be parseable at
  command-registration time.

Risk: low–medium. Will not fix the round-trip `[]` → null / dropped-key
cases on its own; must be paired with 4.3.

### 4.3 YAML round-trip hardening (required for both A and B)

Audit every write path (`ListCmd.onCommand`, `SubConfigCmd.onCommand`,
`ConfigParser.save*`) to confirm:

- Empty `List` → serialized as `key: []` (flow style), **not** `key:` (null).
- The serializer does not elide the key just because its value is empty.

Add a round-trip test:

1. Load `config.yml` with `playerCommands: []`.
2. Edit an unrelated key (e.g. `teleportDelay`) via `SubConfigCmd`.
3. Reload from disk.
4. Assert `playerCommands` still exists and is an empty list.

Test FQCN suggestion: `io.github.dailystruggle.rtp.common.commands.config.EmptyListRoundTripTest`.

## 5. Out-of-Scope (Deferred)

- Unified list-element validation (e.g. reject unknown material names at
  `add` time) — tracked separately in `YAML_SIMPLIFICATION_PLAN.md`.
- Migrating `database:` to a tagged union — unrelated, already in
  `YAML_SIMPLIFICATION_PLAN.md`.
- Changing the runtime semantics of `playerCommands` itself
  (`BukkitEffectsHandler` behavior is fine; only the command surface and
  serializer are in scope here).

## 6. Execution Checklist (per PR)

- [ ] Choose Option A (preferred) or Option B; record decision in an ADR.
- [ ] Implement the schema-walk in `SubConfigCmd.addParameters()`.
- [ ] Verify YAML round-trip for `[]` (Section 4.3). Patch the serializer
      call site if it drops empty lists.
- [ ] Remove the `playerCommands: [ - "" ]` sentinel from the bundled
      `rtp-plugin/src/main/resources/config.yml`; replace with `playerCommands: []`.
      Bump `version:` in `config.yml` and add a migration arm that upgrades
      the sentinel to an empty list (REQ-RTP-F-013 continuity).
- [ ] Add `EmptyListRoundTripTest` (traceable to REQ-RTP-F-013).
- [ ] Extend `SubConfigCmdTest` with an "empty-list-typed key still
      registers a `ListCmd`" case.
- [ ] Update `docs/dev/TRACEABILITY.md`.
- [ ] Update `docs/admin/MIGRATION.md` with the new bundled default.
- [ ] Update the matching entry in `docs/admin/YAML_SIMPLIFICATION_PLAN.md`
      (`playerCommands` empty-entry bullet) to "implemented, see ADR-…".

## 7. REQ / ADR Touchpoints

- **REQ-RTP-F-013** — discoverability of configurable keys via the command
  surface. This plan strengthens compliance.
- **REQ-RTP-S-004** — the `ListCmd` error paths already log `WARNING` on
  IOException; no change needed, but add a regression assertion.
- **New ADR (proposed)** — "Schema-driven `/rtp config` parameter
  registration". Supersedes the value-dispatch behavior in `SubConfigCmd`
  if Option A is chosen.
