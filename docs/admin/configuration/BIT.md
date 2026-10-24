# Built-In Tests (BIT)

BIT reports configuration and runtime findings with stable codes, source files, YAML paths, and suggested corrections. Action-definition findings appear in the server log when the action definitions load and in the `action-definitions` PBIT report.

## Reading `/rtp test` output

Players running `/rtp test` get one chat summary followed by detail lines for `FAIL`, `WARN` and `INFO` findings. `PASS` and `SKIP` components are counted in the summary but do not get separate detail lines. The server log gets the full table. Console and RCON callers have no player chat; inspect the server log for their results.

For example, if four components include one pass, one failed component with both an error and a warning, one informational finding, and one component that has not run:

```
[RTP bit] FAIL 4 components: 1 pass, 0 warn, 1 fail, 1 info, 1 skip
[RTP bit] FAIL config-yaml RTP_YAML_UNPARSABLE
[RTP bit] WARN config-yaml RTP_REGION_UNKNOWN_WORLD x2
[RTP bit] INFO anvil-zstd BIT_OPTIONAL_UNAVAILABLE
```

A repeated code within the same component and status is shown once with a count (`x2`). Detail lines are ordered `FAIL`, then `WARN`, then `INFO`. The summary counts each component once by its highest status; its first token is the worst component status. Chat lines use the `minecraft:uniform` font where supported; clients that do not render it still show the same text, with imperfect alignment accepted.

| Status | Means | From |
|--------|-------|------|
| `PASS` | The component ran without a finding above informational severity. | health `HEALTHY` |
| `WARN` | A warning; the component may use a fallback or be degraded. | severity `WARNING`, health `DEGRADED` |
| `FAIL` | An error; the component failed, or the config or action is rejected. | severity `ERROR`, health `FAILED` |
| `INFO` | Information only; no action is needed. Optional host capabilities with safe fallbacks use this status. | severity `INFO`, `DEBUG`, `TRACE` |
| `SKIP` | The component has not run yet. `/rtp test status` shows cached results and does not run checks. | `NOT_RUN` |

For what a code means and how to fix it, look it up in [BIT Codes](../../dev/BIT_CODES.md). The file, YAML path and message for each finding are in the server log. Every line in the log starts with `[RTP bit]` and the columns are:

| Column | Content |
|--------|---------|
| `component` | Component id, as used by `/rtp test <component>`. |
| `tier` | `PBIT` (load and reload), `CBIT` (while running) or `IBIT` (on request). |
| `sideEffect` | `SAFE`, `NEEDS_WORLD`, `NEEDS_PLAYER`, `MUTATING` or `HEAVY`. Group selectors such as `all` run only `SAFE` components. |
| `health` | Component health: `HEALTHY`, `DEGRADED`, `FAILED` or `NOT_RUN`. |
| `E`, `W` | Number of errors and warnings in the component. |
| `severity` | Severity of this finding. |
| `id`, `code` | Stable `namespace:id` identity and key from the code catalog; ids are local to their namespace. |
| `path` | YAML key path or probed surface, for example `regions.default.world`. |
| `message` | Details of the finding. |

Removing the color codes and the `[RTP bit] ` prefix leaves CSV. Fabric and NeoForge `latest.log` files may also contain terminal ANSI escape sequences; remove those before parsing the CSV. Add `json=true` to get the complete report as JSON, which also includes the namespace (`ns`), source file, expected values and suggestions.

## Action registration policy

The policy is configured in `advanced/bit.yml`:

```yaml
strictActions: false
strictConfig: false
gateOutcomeSamples: 200
overrides:
version: 1.1
```

`strictActions` is a staged preset for the structural action codes `ACTION_UNKNOWN_LIFECYCLE_STAGE` (2001), `ACTION_UNKNOWN_STEP_KEY` (2002), `ACTION_UNKNOWN_ACTION_NAME` (2003), `ACTION_UNKNOWN_GATE_TYPE` (2004), and `ACTION_UNKNOWN_TRIGGER_TYPE` (2005).

- With the shipped default `false`, these findings are logged as `WARNING`, and the action remains registered unless an override raises a finding to `ERROR`. This release keeps existing action loading compatible while making structural problems visible.
- With `true`, the findings retain `ERROR` severity and an action with any effective `ERROR` is not registered. Other valid actions continue to load.
- Every finding is logged with its source and YAML path, allowed values, and a suggestion when available.
- An unknown `ACTION:` name is omitted from the parsed actions. A trigger with an unknown `type:` is skipped; a trigger without a `type:` still defaults to `STEP_IN`.

## Configuration checks and reload

Every configuration file is checked on startup, by `/rtp test subsystem=config`, and before every `/rtp reload` and editor Hot-Apply. Each owner reports as its own `config-<owner>` component (for example `config-yaml`, `config-messages`, `config-effects`). The checks cover:

- YAML that does not parse;
- values that break the `@type`, `@options` or `@range` hints in the shipped file comments;
- `@config`, `@economy` and `@safety` references;
- names that point at a missing world, region, schematic or effect group;
- impossible settings such as a `radius` that is not larger than `centerRadius`, or a `shrinkTo` larger than `initialSize`;
- message files that are missing keys.

The checks read files only and never load chunks.

`/rtp reload` and Hot-Apply check the files first. If any finding is an `ERROR`, the reload is rejected and the server keeps running its current configuration. Nothing is cancelled, swapped or written. The sender gets the `reloadRejected` message (or `editorApplyRejected` for `/rtp editor apply`) naming the first error, and the server log lists every finding. Action definitions are not part of this check: an action with an error is skipped on its own while the other actions load.

`strictConfig` is a staged preset for configuration checks that older releases accepted, such as a value outside its `@range` or a region that names an unknown world.

- With the shipped default `false`, these findings are logged as `WARNING`, so they do not reject a reload.
- With `true`, they keep `ERROR` severity and reject a reload.
- YAML that does not parse and impossible settings are always `ERROR`.

Other configuration findings, such as a missing message key, are warnings whatever `strictConfig` says.

## Action runtime health

The `action-cbit` check reports runtime action-engine anomalies through `/rtp test status`. It tracks missing external scoreboard objectives, gates that have only passed or only failed after enough evaluations, unresolved placeholders at dispatch, unknown `ACTION:` commands, and sessions still active beyond their reaper window. `RTPMetricsExtension` also includes an immutable snapshot of these counters.

The Action addon also registers the SAFE PBIT component `action-setup`. It reports action definitions rejected by ERROR findings, command registration failures, inactive physical triggers (including a missing batch timer), and colliding action or root-command names. The Claim addon registers `claim-integrations`, which reports enabled claim plugins whose integration flag is off, configured plugins that are absent, integrations enabled too late, and registration or API compatibility failures. An absent plugin with its integration flag off is healthy; an absent plugin with its flag on is informational.

Addon codes use an addon-owned namespace and are documented in that addon's `docs/BIT_CODES.md` and `docs/bit-codes.lock`. `advanced/bit.yml` overrides for addon codes are retained while the addon loads and are resolved before the startup PBIT report, so first-boot overrides work. The GUI addon does not yet provide a BIT component.

`gateOutcomeSamples` sets the minimum evaluations required before a gate is classified as never or always passing. The default is `200`; values must be positive.

## Per-code overrides

`overrides` uses registered BIT code keys and accepts `ERROR`, `WARNING`, `INFO`, `DEBUG`, `TRACE`, or `OFF`. A per-code override takes precedence over the `strictActions` and `strictConfig` presets. `OFF` suppresses the finding from logs and BIT reports.

```yaml
strictActions: true
overrides:
  ACTION_UNKNOWN_STEP_KEY: WARNING
  ACTION_UNKNOWN_TRIGGER_TYPE: OFF
version: 1.1
```

Unknown code keys and invalid levels are ignored and reported as warnings. Code keys are case-insensitive. Do not change `version`.

The available registered code catalog is documented in [BIT Codes](../../dev/BIT_CODES.md) and governed by [ADR-121](../../adr/ADR-121-built-in-test-for-configuration-and-the-action-engine.md).