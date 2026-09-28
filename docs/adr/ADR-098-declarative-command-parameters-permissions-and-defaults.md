# ADR-098 - Declarative Command Parameters, Per-Parameter Permissions, and Authoritative Defaults

**Status:** Accepted (2026-09-26)

**Extends:** [ADR-093](ADR-093-declarative-scripted-actions-via-core-confinement-and-subspace-placement.md) (Declarative Scripted Actions)

## Context

Declarative actions (ADR-093) originally declared a command only as `name / permission / description / aliases`. There was no per-argument permission and no declared meaning for a missing argument. This produced a security-relevant defect that spanned multiple actions:

- Any argument naming another player was either silently honored or silently dropped. For a single-player teleport action (e.g. `location`), a caller could name an arbitrary player and teleport *them* rather than themselves.
- For `challenge`, "no target" is intended to mean "any target" (open matchmaking), but nothing declared that; open mode engaged only as a side effect of an unresolved `[target_name]` placeholder in the gate template, so the config did not actually drive the behavior.

The two "what does no-target mean" mechanisms (the gate template's `[target_name]` wildcard and the notion of a default) were uncoupled, so configuration could read as if it drove behavior while the real decision lived elsewhere.

## Decision

Extend the action command spec with declarative `parameters`. Each parameter binds an optional permission and a fallback default to a named argument.

```yaml
command:
  name: challenge
  permission: rtp.command.challenge
  parameters:
    - name: player
      type: player
      required: false
      permission: rtp.command.challenge.target   # required to name a specific opponent
      default: any                               # no arg -> open matchmaking
```

### Model

- `ParameterType` (rtp-api): `player`, `coordinate`, `region`, `world`, `number`, `string`. Each type declares the set of *symbolic* default keywords it accepts (`player`: `self`/`any`; `coordinate`: `self`/`spawn`; `region`/`world`: `self`; `number`/`string`: none). Any default that is not a recognized symbolic keyword is treated as a literal of the declared type.
- `ParameterSpec` (rtp-api): `name / type / required / permission / default`, with `validate()` that fails fast.

### Semantics (engine, not per-action code)

- Argument supplied **and** caller holds the parameter `permission` (or is `rtp.*`) -> use it (targeted mode).
- Argument supplied **but** caller lacks the permission -> deny with the configurable `noPerms` message (S-007); the argument is not silently dropped.
- Argument absent -> apply the declared `default`. For `player`: `self` resolves the target to the caller; `any` leaves the target unset (open matchmaking - a target-based reciprocity gate is skipped); a literal name resolves that player.

The parameter default is the **single authoritative decision point** for the no-argument case. Whether `target_*` metadata is set is the sole consequence the gate/lifecycle react to, so a gate template's `[target_name]` presence/absence is now a *consequence* of the resolved default rather than an independent source of truth.

### Fail-fast validation

Illegal configuration is rejected at config load (never degraded): a blank parameter name, or a symbolic default keyword that is illegal for the declared type (e.g. `default: any` on a `coordinate`), throws during `ActionConfigLoader` parsing so operators discover the mistake immediately.

### Permission convention

Per-parameter permission nodes follow `rtp.command.<action>.<qualifier>` (e.g. `rtp.command.challenge.target`, `rtp.command.location.other`). The `rtp.*` super-permission bypasses per-parameter checks; the wildcard-admin test is centralized behind `RTPCommandSender.isRtpAdmin()` rather than hardcoded per call site.

## Consequences

- Closes the arbitrary-teleport hole: naming another player requires an explicitly granted permission.
- `challenge` has two clean modes: `/challenge` (open matchmaking via `default: any`) and `/challenge <name>` (permission-gated targeted reciprocity).
- `location` teleports only the caller unless the caller holds `rtp.command.location.other`.
- Configuration is honest: `default` genuinely drives the no-argument case.
- Only the `player` type is consumed by the command layer today; `coordinate`/`region`/`world` are declared and validated but not yet wired to a consumer. `number`/`string` remain literal-only.

## Alternatives considered

- Making the raw command-dispatch boolean reliable for reciprocity: rejected earlier; platform dispatch returns `true` for `execute if entity ...` regardless of the predicate, so gate reliability was solved separately via native scoreboard-tag evaluation.
- Graceful degradation on invalid symbolic defaults: rejected in favor of fail-fast at load for this non-critical subset, so operators are notified immediately.
