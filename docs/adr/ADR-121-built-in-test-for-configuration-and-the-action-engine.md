# ADR-121 - Built-In Test for Configuration and the Action Engine

**Status:** Accepted (D-005 approved 2026-10-21)
**Date:** 2026-10-21
**Extends:** [ADR-093](ADR-093-declarative-scripted-actions-via-core-confinement-and-subspace-placement.md) (scripted actions), [ADR-098](ADR-098-declarative-command-parameters-permissions-and-defaults.md) (fail-fast parameter validation), [ADR-037](ADR-037-harden-rtp-config-commands.md) (config transactions).
**Builds on:** [ADR-064](ADR-064-config-comment-format-summary-line-as-menu-hover.md) (`@type`/`@options`/`@range` directives), [ADR-073](ADR-073-config-default-inheritance-references.md) (`@<file>` references), [ADR-107](ADR-107-addon-editor-extensions-and-protocol-negotiation.md) (Hot-Apply `validate`), [ADR-116](ADR-116-effects-through-neutral-server-accessor.md) (standalone-module precedent; not a dependency).

## Context

Configuration, and action definitions in particular, can be written so that it loads without complaint and then does nothing or the wrong thing in play. Minigames written by an operator or by an LLM hit this most often: the YAML looks right and the first sign of a mistake is a broken match in front of players. The defects below are all real code paths:

| Location | Behaviour today |
|----------|-----------------|
| `ActionConfigLoader.parseActionFromMap` (`default ->` branch) | Unknown step keys (`CONSOL:`, `MESAGE:`) are dropped with no message. |
| `ActionConfigLoader` trigger parsing | An invalid `type:` is caught and replaced with `STEP_IN` (`// Ignored: fallback to STEP_IN default`). |
| `ActionSessionImpl` `ACTION` dispatch | An unknown `ACTION:` name loads and only logs `Unknown action command` when the step fires mid-session. |
| `GateEvaluator.evaluate` | An unknown gate key, a `scoreboard` gate without `objective`/`matches`, or an unparsable `spatial.target` returns `false`. Fail-closed is correct at runtime (ADR-093), but a typo such as `spacial:` disables the branch with no signal. |
| `GateEvaluator` `scoreboard` branch | A `null` from `RTPServerAccessor.getScoreboardScore` (missing objective) is evaluated as `0` silently. A near-miss of a managed name (`rtp_violation`) is treated as an external objective. |
| `ActionPlaceholderSanitizer.substitute` | A placeholder outside its lifecycle stage (`[victim]` in `onStart`) is passed through as literal text. |
| Vanilla command payloads | Hand-escaped JSON in `title`/`tellraw` is never checked. |

Partial checks exist but are ad hoc: ADR-098 `ParameterSpec.validate()` fails at load, and unknown parameter types and confinement boundaries log warnings. Nothing gathers findings, gives a location, or can be run without a server.

The repository already has the start of a Built-In Test (BIT) contract: `anvil-api` `io.github.dailystruggle.rtp.anvil.bit` defines `BuiltInTestable` (`executePbit()`, `getHealthStatus()`), `PbitResult` and `BitHealth`, implemented by `DynamicZstdBinding`, `LinearRegionReader` and `RegionFormatDetector`. `anvil-api` must keep zero RTP dependencies (ADR-016, ADR-077), so it cannot host a config-facing contract as is. The same contract is wanted by other plugins planned for this monorepo, so it must not be RTP-specific either.

## Decision

### 1. One BIT model, three tiers

Every subsystem that owns configuration or runtime behaviour shall report through the same three tiers:

| Tier | Runs | Scope |
|------|------|-------|
| **PBIT** (power-on) | Load and reload | Structural, reference and semantic checks, plus an engine self-test against bundled known-good cases. |
| **CBIT** (continuous) | While running | Counters on existing hot paths only; no extra per-tick work. |
| **IBIT** (initiated) | On request: command or Gradle task | A full simulated run of one definition with a recording dispatcher. |

Each tier emits **findings**: `severity` (a JDK `java.lang.System.Logger.Level`: `ERROR`, `WARNING`, `INFO`, `DEBUG` or `TRACE`; `ALL` and `OFF` are filters and are rejected; no BIT-specific severity enum), `code` (stable identifier, e.g. `ACTION_UNKNOWN_STEP_KEY`), `source` (file path), `path` (YAML key path, e.g. `actions.koth.onStart[3].CONSOL`), `message`, `expected` (allowed values or shape) and `suggestion` (nearest allowed value by edit distance, when one exists). Each subsystem reports one `BitHealth`: `HEALTHY`, `DEGRADED` (warnings or CBIT anomalies) or `FAILED` (errors).

### 2. The contract is a standalone, plugin-agnostic `bit-api` module

BIT is a common case for every plugin in the monorepo, not an RTP feature. The contract shall live in a new standalone module `api/bit-api` (Gradle path `:bit-api`, package `io.github.dailystruggle.bit`), following the `commands-api` / `metrics-api` precedent: no `io.github.dailystruggle.rtp` dependency, no platform imports, no YAML library binding, Java 17 bytecode. It does not wait for the ADR-116 neutral module and does not go into `rtp-api`.

The module splits into what is generic and what stays with each plugin:

| `bit-api` (generic, reusable by any plugin) | Plugin side (RTP: `rtp-core`, addons) |
|---------------------------------------------|----------------------------------------|
| `BuiltInTestable`, `BitHealth`, `PbitResult` (moved from `anvil-api`) | Which subsystems register and what they check |
| `BitFinding`, `BitReport` (with `schema` version), JSON writer, `BitLevels` (severity rules over `System.Logger.Level`, health mapping, `java.util.logging` bridge) | Which codes exist and the checks that emit them (`ACTION_*`, `CONFIG_*`) |
| `BitCode`, `BitTier`, `BitCodeRegistry`, `BitCodeNamespace` (section 10) | Reserving a namespace and registering codes next to each check |
| `BitRegistry` (register, run PBIT, aggregate health) | Wiring the registry to `RTP.scheduler`, `RTP.log` and `messages.yml` |
| `KeyPath` (YAML/map key-path builder, e.g. `actions.koth.onStart[3]`) | Action, gate, trigger and placeholder vocabularies |
| `Vocabulary` (named allowed-value set with nearest-match `suggest()` by edit distance) | The declared sets themselves, owned by the consuming class (section 3) |
| `MapValidator` helpers: unknown key, required key, type, enum, range, reference resolves | ADR-064 directive extraction, cross-file reference resolution |
| `CbitCounter` / `GateOutcomeCounter` (lock-free counters, never/always-passed detection) | Where counters are incremented (`GateEvaluator`, dispatch) |
| `RecordingDispatcher`, `SimulatedClock` for IBIT | `ActionSimulator`, engine self-test definitions |
| `BitCli` headless runner skeleton (loads a directory, runs registered checks, prints text or JSON, exit code) | The `:rtp-core:bit` task that supplies RTP's checks |
| Drift-test helper: assert a declared set equals the labels a switch/dispatcher handles | The drift tests for RTP's switches |

The module is threading-neutral: it never schedules work itself, it takes an executor or runs on the caller's thread, so each plugin applies its own scheduler rules. Logging goes through a supplied sink, never `System.out` or a global logger. User-facing text is not produced by `bit-api`; it emits stable `code`s and structured fields, and each plugin renders them through its own message files.

`anvil-api` depends on `bit-api` (which keeps `anvil-api` RTP-free, ADR-016, ADR-077). The `io.github.dailystruggle.rtp.anvil.bit` types are removed rather than deprecated, and `DynamicZstdBinding`, `LinearRegionReader` and `RegionFormatDetector` implement `io.github.dailystruggle.bit.BuiltInTestable` directly. The `< 5 ms`, never-throw PBIT rule stays for in-line component checks.

### 3. Allowed values live with the code that uses them

Step keys, `ACTION:` names, gate types and their sub-keys, trigger types, managed `rtp_*` objectives and per-stage placeholders shall each be declared once, as an enum or constant set, in the class that consumes them (`ActionConfigLoader`, `ActionSessionImpl`, `GateEvaluator`, `PhysicalTriggerSpec`, `ActionPlaceholderSanitizer`). The validator reads those sets; it never keeps its own list. A drift test asserts that every runtime `switch` label appears in the declared set and vice versa.

### 4. Action engine and scoreboard PBIT

- **Structural:** unknown step keys, `ACTION:` names, gate types, gate sub-keys and trigger types are `ERROR` findings. The silent `default ->` drop, the silent replacement of an invalid trigger `type:` with `STEP_IN`, and the load-time acceptance of unknown `ACTION:` names end. A trigger with an invalid `type:` is reported and skipped; a trigger with no `type:` key still defaults to `STEP_IN`, so the default remains a fallback for an absent value and never masks a typo.
- **Placeholder scope:** each lifecycle stage declares its tokens (for example `onDeath` adds `[victim]`, `onBoundaryViolation` adds `[violator]`, `FOR_EACH` adds `[player]` to its body). A token used outside its scope is an `ERROR`; an unknown `[token]` is a `WARNING`.
- **Scoreboard:** managed objectives are the fixed set written by `ActionSessionImpl.initializeScoreboards` (`rtp_session_id`, `rtp_time_left`, `rtp_violations`, `rtp_in_bounds`, `rtp_dist_sq`, `rtp_alive`). A name within edit distance 2 of a managed name is an `ERROR` with a suggestion. Any other objective is checked against the server through the accessor when one is present (`WARNING` if absent). Every `matches:`/`range:`/`distance:`/`elevationDelta:` expression must parse with `GateExpressionParser`.
- **Cross-references:** `placement.region` must name a loaded region; schematic paths must resolve; `parameters:` keep ADR-098 validation and report through the same findings.
- **Vanilla payloads:** balanced quotes, brackets and braces, and JSON that parses where a JSON component is expected. Command names are checked against the platform only when the accessor exposes a command list. Brigadier is not re-implemented.
- **Engine self-test:** a small set of bundled definitions runs through the real `GateEvaluator` and `ActionSessionImpl` with a synthetic context and a recording dispatcher; the recorded commands are compared with expected output. A mismatch marks the action subsystem `FAILED`.

### 5. Action engine CBIT

Counters, read by `/rtp test status` and `RTPMetricsExtension`:

- missing-objective reads (`getScoreboardScore` returned `null`), per objective;
- gates evaluated N times that never passed or always passed (N configurable, default 200);
- `[token]` remaining in a command after substitution, counted before dispatch;
- unknown `ACTION:` at dispatch (feeds health as well as the existing log);
- sessions started without reaching expire, cancel or teardown within the ADR-093 orphan-reaper window.

### 6. Configuration PBIT in general

Each configuration owner (`config.yml`, `regions/`, `worlds/`, `safety.yml`, `effects.yml`, `messages.yml`, `network.yml`, addon configs) registers a `BuiltInTestable` with a `BitRegistry` in `rtp-core`. Checks are derived from existing metadata first:

- ADR-064 `@type`, `@options`, `@range` and `@default` give type, enum and range checks.
- ADR-073 `@<file>` references must resolve.
- The `LocaleParityTest` key-set comparison runs as a `messages.yml` PBIT.
- Cross-file references: region -> world, action `placement.region` -> region, schematic -> file, effect group -> stage.
- Semantic constraints declared next to the owning loader (for example `radius > centerRadius`, `shrinkTo <= initialSize`).

### 7. Load, reload and Hot-Apply policy

- An action with any `ERROR` finding is not registered; other actions load. Warnings load. Every finding is logged through `RTP.log` with its `path` (REQ-RTP-S-004 spirit: nothing is swallowed).
- `/rtp reload` and Hot-Apply (ADR-107) run PBIT on a candidate copy inside the ADR-037 transaction and reject the candidate on any `ERROR`, keeping the running config.
  - The candidate is a read-only `ConfigSnapshot` of the data folder. Hot-Apply lays its payload over that snapshot. Building the snapshot writes nothing, constructs no parser and changes no static state.
  - The gate sits at the start of `Configs#reload()`, after pending file-database writes are flushed and before the reconnect, teleport cancellation, migrations or the parser swap. Every reload caller therefore goes through it.
  - Hot-Apply also checks before any `.bak` or file write.
  - A rejection is reported through the `reloadRejected` (`system.yml`) and `editorApplyRejected` (`commands.yml`) messages in every locale. Every finding is logged with its path.
  - The ADR-037 commit/rollback and locking steps are not part of this gate.
- Action definitions are not part of the gate; they keep the per-action rejection above.
- Configuration codes for input that earlier releases loaded without complaint are staged behind `advanced/bit.yml` `strictConfig` (default `false`, so they report as `WARNING`), mirroring `strictActions`. Unparseable YAML and violated semantic constraints are not staged.
- Gates still fail closed at runtime; BIT makes that visible, it does not change it.

### 8. Headless and in-game entry points

- `./gradlew :rtp-core:bit -Pconfig=<dir> [-Pjson]` runs PBIT and, with `-Paction=<id>`, IBIT against a config directory with no server; exit code is non-zero on any `ERROR`. JSON output is the `BitReport` schema, intended for LLM write-check-fix loops.
- `/rtp test` is the in-game BIT runner and output command (section 11). `/rtp test status` prints PBIT and CBIT state; `/rtp test action <id> [players=<n>] [script=<violate,die,expire>]` runs IBIT against a simulated clock and prints gate outcomes with reasons and fully substituted commands. Its output states that it is a simulation; it dispatches nothing.
- The headless Gradle task keeps the name `bit`, because Gradle's `test` task is the unit-test suite.
- CI runs the headless task over `addons/LeafRTPActionAddon/src/main/resources/definitions/actions/` and the shipped default config; any `ERROR` fails the build, so shipped examples are trustworthy templates.

### 9. Constraints

- PBIT beyond in-line component checks, the engine self-test and IBIT run through `RTP.scheduler` asynchronously and never block startup or a main/region thread (Rule F-001..F-003).
- No check loads a chunk (S-005); region checks are config-level only.
- The action addon registers `action-definitions`, `action-engine-selftest` and `action-setup` as `SAFE` PBIT components. `action-setup` checks definition registration, command and physical-trigger registration, batch-wave scheduling, and action/command name collisions. The Claim addon registers `claim-integrations` as a `SAFE` PBIT component on Bukkit-family platforms; its `CLAIM` namespace distinguishes disabled, absent, late-enabled and failed integrations. Startup also registers `anvil-zstd`, `anvil-linear` and `anvil-format-detector`; one aggregate summary runs asynchronously at tick +2, after tick +1 database-accessor startup, on Bukkit, Fabric and NeoForge.
- A missing host Zstandard runtime remains a degraded fallback warning, not a failed PBIT; the anvil checks use the existing in-memory `asBit()` adapters.
- Admin-facing BIT output goes through `messages.yml` with locale parity (S-007, REQ-RTP-F-013); finding `code`s are stable and not translated.
- `rtp-core` and `rtp-api` gain no platform imports.

### 10. BIT code catalog: a registry, not an enum

Every finding code is a row in a BIT table: a stable namespace-local `int` id paired with a stable `UPPER_SNAKE` key, plus default severity, tier (`PBIT`/`CBIT`/`IBIT`), subsystem, `messageKey` and remediation text. The table is built at runtime by registration, so adding a check never edits a central enum.

- **`BitCodeRegistry`** (`bit-api`) maps `(namespace, id) <-> key` (`byId(namespace, id)`, `byKey` case-insensitive, `idOf`, `keyOf(namespace, id)`, `require`). Writes are serialised, lookups are lock-free.
- **Namespaces.** An owner reserves a unique key prefix (`reserve("ACTION", owner)`) and registers codes through the returned `BitCodeNamespace`. Numeric ids are local to the prefix, so two namespaces may both use id `1`; a key shall start with `PREFIX_`. Prefixes and keys are unique within one registry, and conflicting code registrations fail immediately.
- **Namespace uniqueness PBIT.** The registry records the owner (plugin or mod id) of each prefix. A second owner reserving the same prefix is recorded as a conflict rather than thrown, and the SAFE startup component `bit-namespaces` reports each conflict as ERROR `BIT_NAMESPACE_CONFLICT` naming both owners, so a clash between independently developed addons is visible on the first server that loads both.
- **Idempotent.** Re-registering an identical code returns the existing one, so reloads and repeated initialisation are safe.
- **Identity and retirement.** The permanent identity is `(namespace, id, key)`. A published identity is never renamed, renumbered or reused. Removed codes are declared with a retirement marker and remain in the generated catalog.
- **Release lock.** `docs/dev/bit-codes.lock` records each published namespace, id, key and default severity. `:rtp-plugin:checkBitCodes` fails on a missing, renamed, renumbered or reused identity and on an unrefreshed severity change. New identities are additive; `:rtp-plugin:updateBitCodeLock` appends them and refreshes severities without permitting identity changes. The initial lock is the pre-release baseline.
- **Registration site.** Each check declares `static final BitCode` fields in the class that emits them (for example `ActionDefinitionValidator.UNKNOWN_STEP_KEY`). `rtp-core` holds the process-wide registry and namespace reservations in `RtpBitCodes`; class initialization declares data but does not register components as a side effect. The owner bootstrap registers public static final `BitComponent` fields, and the class-file audit requires both code and component owners to be listed. Bukkit adds its owners before configuration loads, and the `/rtp test` command tree passes its own probe classes to the bootstrap on every platform, so Fabric and NeoForge list the probes they load.
- **Findings.** `BitFinding` carries `codeId` and `code`; plugins build findings with `BitFinding.of(BitCode, ...)`. JSON writes `"id"`, `"ns"` and `"code"`; `codeId` `0` marks a legacy string-only finding with an empty namespace. The first published JSON shape remains schema 1.
- **Catalog export.** `BitCodeRegistry.toCatalogJson()` emits namespaces and codes sorted by namespace then local id, with a separate retired-code list. The generated `docs/dev/BIT_CODES.md` has a table per namespace; `bit-codes.json` retains the same records. CI checks both files and the release lock. The exporter initializes every owner first.
- **Addon catalogs.** Each in-tree addon keeps its codes in its own namespace, Markdown catalog and append-only lock under its `docs/` directory. Addon PBIT checks use those codes directly; valid `advanced/bit.yml` overrides for an addon code are held until its namespace registers and are warned about only if still unresolved when startup PBIT begins. The Action and Claim addon catalogs are generated and mechanically checked independently; GUI has no BIT component yet.

Core namespace ownership (the existing ids are preserved as local ids):

| Prefix | Owner |
|--------|-------|
| `BIT` | `bit-api` itself (`BitCodes`) |
| `RTP` | `rtp-core` general configuration (section 6) |
| `ACTION` | Action engine and scoreboard (sections 4, 5) |
| `TEST` | `/rtp test` probes (section 11) |
| `PROBE` | Runtime-only RTP diagnostic probes (section 11) |
| `HEATMAP` | Mock teleport landing heatmap (section 11) |

Every addon or plugin namespace uses its own unique prefix and local ids; no shared global number range is allocated. A public addon-registration API is a separate extension surface and shall use the same identity and lock principles.

Per-server severity overrides (`advanced/bit.yml`, `overrides: { <KEY>: ERROR|WARNING|INFO|DEBUG|TRACE|OFF }`, parsed as `System.Logger.Level` names, with `OFF` suppressing the code) key on the registered code and supersede the single `strictActions` switch, which becomes a preset.

### 11. `/rtp test` is the BIT runner and output command

"Built-in test" is what `/rtp test` already means, so BIT keeps that name: `/rtp test` is the single in-game front end for BIT, and BIT is its documented purpose. There is no separate `/rtp bit` command. `/rtp test` is not one BIT source among many; it sits above every registered `BuiltInTestable` (probes, action and config PBIT, effects PBIT, startup checks, CBIT counters) and owns how their results are run and printed.

- **Components, not output.** A probe or check returns a `BitReport` and registers with the `BitRunner` under a component id, tier and side-effect class: `SAFE` (may run as part of a group), `NEEDS_WORLD`, `NEEDS_PLAYER`, `MUTATING` or `HEAVY` (for example `config-set`, `reload-safety`, `stress`). Components do not format text or JSON.
- **Runner.** `bit-api` provides a plugin-neutral `BitRunner` (component registry, selector, caller-supplied executor, per-component timeout) and a `BitOutput` interface with text and JSON renderers. Timeout, thrown-check and unknown-selector findings use the stable `BIT_COMPONENT_TIMEOUT`, `BIT_COMPONENT_THROWN` and `BIT_COMPONENT_UNKNOWN` codes. The runner creates no threads; timeout enforcement depends on the supplied executor dispatching asynchronously, while a direct executor preserves caller-thread behavior. `rtp-core` supplies both execution and output transports.
- **Selectors:** the runner supports a named component, a tier (`pbit|cbit|ibit`), a subsystem and `all`; group selectors include only `SAFE` components, while `MUTATING` and `HEAVY` components run only when named. `BitSelector.parse` accepts the plugin-neutral strings `tier:<name>` and `subsystem:<name>`; these are not command syntax. On `/rtp test` they are commands-api parameters and bind only with `=`: `/rtp test tier=pbit`, `/rtp test tier=pbit,ibit`, `/rtp test subsystem=test.commands`, `/rtp test all` and `/rtp test all json=true`. Comma-separated values form a union; `tier` and `subsystem` cannot be combined. A token such as `json:true` contains no `=`, so the parser treats it as a subcommand name and rejects it. The network parent accepts `network json=true`; its `tokens` and `heartbeat` leaves retain table output. `/rtp test status` prints cached results or `NOT_RUN` without rerunning checks. `cancel` stays a control subcommand, not a component.
- **Action simulation:** `/rtp test action <id> [players=<n>] [script=<violate,die,expire>]` accepts a positional action id or `id=<id>`; option parameters use `=`. `players` defaults to 2 and is bounded to 1-16; `script` defaults to no events and accepts a comma-separated sequence. The command runs asynchronously with synthetic participants, a simulated clock and a recording dispatcher; its `SIMULATION ONLY` output includes gate results/reasons and substituted commands, and dispatches nothing. Gates on terminal stages without a gate context are identified in the output rather than silently claimed as evaluated.
- **Command grammar is tested through the parser.** Every documented `/rtp test` invocation shall have a test that dispatches the literal argument tokens through `TreeCommand.onCommand(callerId, perm, msg, String[] args, 0, null)` and drains `CommandsAPI.commandPipeline`, asserting the bound parameter map or the rejection. Tests that call `onCommand(UUID, Map, ...)` directly bypass tokenization and do not verify syntax (`TestJsonParameterSyntaxTest`).
- **One code path.** Startup and reload PBIT call the same runner with the PBIT tier selector, and the headless `bit` Gradle task (section 8) uses it too, so in-game, startup and headless results cannot drift apart. Moving `all` into the platform-neutral runner gives Fabric and NeoForge the same sweep as Bukkit.
- **Startup summary.** Bukkit, Fabric and NeoForge register the same SAFE action-definition, action-engine-selftest and anvil PBIT components and schedule one asynchronous summary after the tick +1 database-accessor startup. PBIT runs at tick +2 through `RTP.scheduler`; no component loads chunks.
- **Verdicts.** `all` and the legacy `full` sweep use each component's own report and health when a result completed during that step; a `FAILED` component fails the step. The logged-WARNING count is only the fallback when no fresh component result exists and remains an extra signal.
- **Permissions:** `rtp.test` runs `SAFE` and read-only components; `rtp.test.mutating` is required for `MUTATING` and `HEAVY` components. The Bukkit-only legacy `full` sweep retains its separate `rtp.test.full` permission and is not an alias for `all`; it excludes `action` because simulation requires an explicit definition id and is tested separately.
- Each probe registers its checks in the `TEST` namespace (for example `TEST_ACCESSOR_MATERIALS`, `TEST_CHUNK_TICKET_TRACKER_LEAK`) and maps each failing boolean in its `Result` to a finding whose `path` names the probed surface (`accessor.materials`, `chunk-ticket.release`). Existing ids remain stable; the release lock, rather than reserved numeric blocks, prevents reuse. `TEST_CHUNK_TICKET_STALE_SENTINEL` is an advisory warning. `accessor` is `MUTATING` because it sends world-border packets; `chunk-ticket` is `NEEDS_WORLD`, so neither is included in a group run.
- **Mock-player teleport probe.** `teleport` (namespace `TEST`, subsystem `test.teleport`, IBIT, `HEAVY`) drives `RTPAPI.teleport` for synthetic players through the real pipeline, including real async chunk I/O during selection (S-005), so it runs only when named.
  - **Lifetime.** Each mock is an `rtp-bit-<8 hex>` `RuntimeMockRTPPlayer` owned by a `MockPlayerSession`, which registers it in `MockPlayerRegistry` and, in `finally`, unregisters it and purges its `TeleportSessions` entries. The `-` is illegal in Minecraft names, so a mock never shadows a real player.
  - **Lookup.** The Bukkit, Folia, Fabric and NeoForge server accessors return a registered mock from `getPlayer(UUID)`, `getPlayer(String)` and `getSender(UUID)` before any native lookup, and never list mocks in `getOnlinePlayers()` or `getOnlinePlayerNames()`.
  - **No persistent side effects.** `TeleportPipelineTask` reaches teleport history, usage-cap counts, the shared last-teleport time and the ADR-104 `LandingHeatmap` only through `SyntheticSideEffects`, which skips them for mocks and appends the landing (world, x, y, z, region, attempts, duration) to the bounded in-memory `BitTeleportLog` instead; a source test forbids direct sink calls in the pipeline. `EconomyGate` charges mocks nothing, `RTPTeleportCancel` refunds them nothing, and neither `RTPCommandEvents` nor the Bukkit pipeline events fire for them. Addon-registered pipeline hooks still see mocks.
  - **Verdict.** The probe passes when the mock's `setLocation` was reached and, after the API future completes, `processingPlayers` and the `TeleportData-<uuid>` `MemoryTracker` label hold nothing for it. Every report carries the INFO finding `TEST_TELEPORT_MOCKED`, including failed runs, because the platform teleport itself is never exercised.
- **SAFE probes.** `commands`, `scheduler`, `events`, `economy-isolation`, `disconnect-job`, `network`, `safety-verifier` and `api-compat` use the `TEST` namespace; their existing ids are preserved and new ids are locked append-only. `stress`, `queue-starvation`, `disconnect-midflight`, `folia-ownership`, `async-reply` and `hologram` use `PROBE`; `teleport-heatmap` uses `HEATMAP`. `stress` and the mock half of `async-reply` are named `HEAVY` components; their real-player variants are `stress-player` and `async-reply-player` (`NEEDS_PLAYER`). Queue starvation is `HEAVY`; disconnect-midflight and Folia ownership are `NEEDS_WORLD`; hologram is `NEEDS_PLAYER`. `HEAVY` entry points require `rtp.test.mutating`; these effects are excluded from group selectors.
- **Mock teleport heatmap.** `teleport-heatmap` (namespace `HEATMAP`, subsystem `test.heatmap`, IBIT, `HEAVY`) runs only when named and requires `rtp.test.mutating`. `/rtp test teleport-heatmap [region=<name>] [samples=N]` clamps samples to 1-256 (default 64), sends sequential registered mock players through `RTPAPI.teleport`, and bins their `BitTeleportLog` landings (world, x, y, z, region, attempts, duration) into a CSV and an ADR-119-rendered PNG under the plugin data folder's `reports/adr-121/teleport-heatmap`; headless test output stays under `build/`. Filenames are timestamped and artifacts are not automatically pruned. It never writes to ADR-104 `LandingHeatmap`. Every path records a `ProbeVerdict`, every timeout is `BIT_COMPONENT_TIMEOUT`, and every report includes the INFO `HEATMAP_MOCKED` finding.
- **Mock and real-player split.** `/rtp test stress [count=1-16] [iterations=N] [region=<name>]` uses registered `MockPlayerSession`s when `player=` is absent and checks successful teleports, `setLocation` and `processingPlayers`; `player=<name>` retains the existing platform teleport path under `stress-player`. `/rtp test async-reply` runs the pipeline half with a registered mock and checks its teleport future and `processingPlayers`; `async-reply player=<name>` remains the real-player thread-classification probe. `folia-ownership` uses a mock in the default region world, while `disconnect-midflight` uses a mock UUID when a region world is available and otherwise reports its isolated synthetic-UUID fallback. `queue-starvation` defaults console callers to the default region. `hologram` remains player-only; console invocation reports the active provider informationally.
- **Timeout and compatibility contract.** Every asynchronous path is recorded through `ProbeVerdict` and cached by `RtpBit.RUNNER`; each timeout is `BIT_COMPONENT_TIMEOUT`, not a `TIMEOUT`-only terminal line. Existing `[RTP test/<probe>]` lines remain stable, and BIT table/JSON output uses the separate `[RTP bit/<component>]` prefix. Literal command syntax, including `player=<name>`, is verified through the actual `/rtp test` parser.
- **Probe safety and output.** Each of the eight probes above registers an `IBIT` component with subsystem `test.<id>` and `SAFE` side-effect class. The `events` probe dispatches synthetic command, prefab and player-move events that add-on listeners may observe; listener callbacks are not isolated. The `network` group component runs only synthetic token checks and releases its tokens; it requires no live proxy and reports `NOT_CONFIGURED` as informational when no local transport is bound. Heartbeat probes publish fake `READY` backends and therefore run only when requested explicitly. The `safety-verifier` hang mock applies only to the exact sampled `RTPCoords` instance and closes only its own verifier registration, preserving other claim verifiers (S-003).
- `ProbeBitReport` in `common/commands/test` builds findings. The runner owns the component result cache; `ProbeBitReport.last` delegates to it. JSON output uses `[RTP bit/test.<component>]`, which never matches `[RTP test/`.
- **Default log output is a colored, aligned CSV table** (`BitTable` in `bit-api`, `BitTableOutput` in `rtp-core`): one header row, then one row per finding, or one row per component with no findings (`NOT_RUN` when it has not run). Columns: `component, tier, sideEffect, health, E, W, severity, id, code, path, message`. Padding goes after each comma and values containing a comma, quote or edge whitespace use RFC 4180 quoting, so removing the `&` color codes and the fixed `[RTP bit] ` prefix leaves CSV that a reader trimming leading spaces parses back exactly. Fabric and NeoForge `latest.log` files may also contain ANSI escape sequences, which must be stripped before parsing. Widths are measured on the uncolored text. The log always gets the full table, whoever the caller is. `json=true` replaces the table with the nested JSON report.
- **Chat output is compact** (amended 2026-10-23). A full row is wider than the default chat box, and wrapping breaks the columns. A player caller first gets one summary with the worst component status and counts of components by their highest status (`PASS`, `WARN`, `FAIL`, `INFO`, `SKIP`); it is followed by distinct `FAIL`, `WARN` and `INFO` finding lines, in that order. Each detail line is `STATUS component CODE`; repeated (component, status, code) findings collapse to `xN`. `PASS` and `SKIP` components are counted only. Status tokens: `PASS` (`HEALTHY`), `WARN` (`WARNING`/`DEGRADED`), `FAIL` (`ERROR`/`FAILED`), `INFO` (`INFO`/`DEBUG`/`TRACE`), `SKIP` (`NOT_RUN`). Tier, side effect, counts, id, path and message stay in the log table and JSON; console and RCON callers have no player chat. Per-code meaning and remediation live in the generated `docs/dev/BIT_CODES.md`; the status and column tables live in `docs/admin/configuration/BIT.md`. Chat lines are wrapped in the `minecraft:uniform` font where supported; imperfect alignment is accepted where it is not.
- **BIT output is developer diagnostics, not operator copy.** Like exception text, its wording, colors and font are fixed in code and are not `messages.yml` keys; S-007 and REQ-RTP-F-013 cover operator- and player-facing messages. Finding codes, paths and messages stay English and stable so logs, CI and LLM tooling can match them.
- Compatibility: all existing probe summary lines remain unchanged because acceptance and version-sweep scripts grep them; BIT table and JSON lines never contain `[RTP test/<probe>]` prefixes. `/rtp test status` retains its legacy line when no components have registered; BIT JSON uses its machine-readable prefix.
- `/rtp test all` and the root `tier=` / `subsystem=` selectors run only `SAFE` components; `/rtp test status` lists registered cached state. SAFE startup PBIT also registers `action-definitions`, `action-engine-selftest` and the three anvil canaries. Probe classes only declare components; the explicit owner bootstrap registers their public static final fields, and merely initializing a class does not mutate the runner.

## D-005 Proposal

**Affected modules and classes**

| Module | Change |
|--------|--------|
| new `api/bit-api` (`:bit-api`, `io.github.dailystruggle.bit`) | Generic contract and helpers listed in section 2; own README, tests and architecture test (no `io.github.dailystruggle.rtp`, `org.bukkit`, `net.minecraft` imports). |
| `settings.gradle` | `include 'bit-api'` with `projectDir = file('api/bit-api')`. |
| `anvil-api` | Depends on `bit-api`; implementers use the `bit-api` types; the `anvil.bit` package is removed. |
| `rtp-core` `common/action` | `ActionConfigLoader` records findings instead of dropping; allowed-value sets in `ActionConfigLoader`, `ActionSessionImpl`, `GateEvaluator`, `ActionPlaceholderSanitizer`; new `ActionDefinitionValidator`, `ActionEngineSelfTest`, `ActionCbitCounters`, `ActionSimulator`. |
| `rtp-core` (config) | Uses `bit-api` `BitRegistry`; ADR-064 directive checker; reload path runs PBIT on the candidate. |
| `rtp-core` (commands) | `/rtp test` becomes the BIT runner and output command (selectors, `status`, `action`); probes become registered components. |
| `rtp-core` `build.gradle` | `bit` JavaExec task. |
| `rtp-plugin` resources and `lang/` | Only operator-facing results of BIT (for example a reload rejected by PBIT) get message keys, in all locales; BIT diagnostic output itself is not localized. |
| `LeafRTPActionAddon` | Bundled self-test definitions; CI wiring over shipped examples. |
| `rtp-api` `trigger` | `PhysicalTriggerSpec.TriggerType` exposed as the allowed trigger set (no signature change). |

**Before / after**

- Before: each loader validates its own corner; unknown input is dropped, defaulted or deferred to runtime; no server-less check.
- After: one findings model, a registry of testable config owners, actions with errors are not registered, reload rejects failing candidates, and the same checks run headless with JSON output.

**Requirements and ADRs:** REQ-RTP-S-004, REQ-RTP-F-013, S-005, S-007; ADR-037, ADR-064, ADR-073, ADR-093, ADR-098, ADR-107, ADR-116.

**Delivery order** (each independently shippable):

1. `bit-api` module with the generic contract and helpers; `anvil-api` moved onto it; `ActionConfigLoader` silent drops become findings; allowed-value sets plus drift test.
2. Placeholder scoping and scoreboard PBIT.
3. Headless `bit` task with JSON output; CI over shipped examples.
4. Engine self-test and IBIT (`/rtp test action`).
5. Action CBIT counters.
6. General config PBIT via ADR-064 metadata, cross-file references and reload/Hot-Apply gating.
7. BIT code catalog (section 10): `BitCode` registry with namespaces in `bit-api`, action codes registered in `rtp-core`, generated code docs and JSON, message-key and "every code has a firing test" checks. Lands before step 2 so new codes register from the start.
8. `/rtp test` as the BIT runner (section 11): `BitRunner` and `BitOutput` in `bit-api`; `accessor` and `chunk-ticket` as the first registered components, then the remaining probes; `status`, selectors and `all`.

**Risks and trade-offs**

- Existing operator configs with typos that "worked" by being ignored will now disable that action. Mitigation: the first release that ships this logs findings as `WARNING` for structural codes behind `advanced/bit.yml` `strictActions: false`, with `true` the default one release later; the CHANGELOG states the change.
- Moving the `bit` package touches `anvil-api` consumers; the old package is removed without deprecated aliases, so an external implementer must switch imports to `io.github.dailystruggle.bit`.
- Registration-time catalogs are only complete once owners initialise; a lazily loaded owner can be missing from `/rtp test status` or the export. The owner list and its test (section 10) close this.
- Shaded copies of `bit-api` in separate plugins get separate registries; unique namespace prefixes keep keys distinct and ids are local to each namespace. The release lock prevents identity changes within the RTP catalog.
- A generic module tempts RTP-specific growth. The `bit-api` architecture test and the section 2 split keep domain vocabularies, codes and message rendering in each plugin.
- `bit-api` becomes a published artifact other plugins pin; its types and JSON `schema` follow semantic versioning independent of the RTP release number.
- The JSON report becomes a public format for tooling; `code`s and field names are versioned with a `schema` field.
- IBIT simulation can diverge from live behaviour where the platform matters (real scoreboards, command parsing); output states that it is a simulation.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| JSON Schema only | Catches shape, not placeholder scope, cross-file references, gate overlap or engine regressions. A schema may still be exported from the same allowed-value sets later. |
| Fail the whole addon on any error | One bad definition would take every minigame down; per-action rejection keeps the blast radius to the defect. |
| Fail open on unknown gates | Contradicts ADR-093's fail-closed rule; visibility belongs in BIT, not in looser runtime semantics. |
| A separate validator with its own lists | Drifts from the runtime; the drift test only works when the runtime owns the lists. |
| Re-implement Brigadier to check vanilla commands | Large, version-sensitive, and duplicates the server; cheap lexical checks catch the common LLM errors. |
| Keep BIT inside `anvil-api` | Would add RTP dependencies to a module that must stay RTP-free. |
| Put the contract in `rtp-api` or the ADR-116 neutral module | `rtp-api` is RTP-specific, and the ADR-116 module is a server-accessor seam; neither is reusable by other plugins in the monorepo, and the second would couple BIT to an unrelated proposal's schedule. |
| A separate `/rtp bit` command next to `/rtp test` | Two commands for one purpose; `test` already means built-in test, so BIT is documented as its purpose instead. |

## Consequences

- **Positive:**
  - Config mistakes surface at load or in CI with a key path and a suggestion, before players see them.
  - An LLM or operator can iterate headless against the same checks the server runs.
  - Shipped action examples are verified on every build.
  - Runtime anomalies that slip past PBIT (missing objectives, dead gates, leftover placeholders, orphaned sessions) appear in health and metrics.
- **Negative / Trade-offs:**
  - More code on the load path and a new command surface to maintain and translate.
  - Stricter loading can disable previously tolerated configs (mitigated by the staged `strictActions` default).
  - Each new action key, gate or placeholder must be added to its declared set; the drift test enforces this.

## References

- `rtp-core/.../common/action/ActionConfigLoader.java`, `GateEvaluator.java`, `ActionSessionImpl.java`, `ActionPlaceholderSanitizer.java`, `GateExpressionParser.java`.
- `api/bit-api/.../bit/BuiltInTestable.java`, `PbitResult.java`, `BitHealth.java` (replacing the removed `anvil.bit` package).
- `addons/LeafRTPActionAddon/src/main/resources/definitions/actions/` (shipped examples, e.g. `koth.yml`).
- `docs/dev/CONFIG_COMMENT_STYLE.md` (ADR-064 directives); `LocaleParityTest`.
- `api/commands-api`, `api/metrics-api` (precedent for standalone, plugin-agnostic modules).
- ADR-016, ADR-037, ADR-064, ADR-073, ADR-077, ADR-093, ADR-098, ADR-107, ADR-116.
