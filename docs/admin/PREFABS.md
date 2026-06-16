# Prefabs - one-click admin setups

**Applies to plugin version:** `3.0.0-beta.3+` (post-prefab landing). If your jar predates the `Setup (quick start)` section in `/rtp admin`, prefabs are not available; the rest of [QUICK_START.md](QUICK_START.md) still applies.

> **TL;DR:** A prefab is a curated overlay on `performance.yml` and `regions/default.yml` (and, for `multi-world`, synthesised per-world region files) you can apply in one click from the admin panel. Apply opens a confirmation menu that shows the exact diff; confirming writes `.bak.<timestamp>` siblings of every touched file before replacing them. Rollback restores the most recent bak.

---

## Why prefabs exist

A fresh RTP install picks one set of defaults that has to work everywhere: shared-CPU VPS, dedicated bare metal, Folia regional scheduler, Paper, small-population creative server, 1000-player survival network. The defaults are the conservative middle. Prefabs let you jump to a curated tuning bundle in one click, instead of reading [PERFORMANCE.md](configuration/PERFORMANCE.md) + [REGIONS.md](configuration/REGIONS.md) and hand-tuning four files.

Prefabs are **starting points**, not config-management substitutes. After applying a prefab you can still hand-edit any file the prefab touched. Prefabs only set the knobs they explicitly mention; everything else is preserved as you had it.

---

## The seven bundled prefabs

| id | When to pick it | What it changes |
|---|---|---|
| `survival-default` | You want to reset back to RTP's shipped defaults. | Nothing in your file structure, but the merge will revert any drift on the keys RTP cares about back to the on-jar defaults. |
| `low-performance` | Shared-CPU VPS, low RAM, the host is also running your game world. | Longer pulse period, smaller cache caps, fewer concurrent chunk tickets, login cache off. |
| `high-performance` | Dedicated hardware, large player counts, you want depth in the queue at all times. | Shorter period, larger sync/async tick budgets, larger cache caps, login cache on, post-teleport queueing on. |
| `folia-tuned` | You are on Folia and want regions to pulse fast per-thread instead of competing for one global budget. | Period drops to 5 ticks, sync budget shrinks (regions pulse on their own thread), small `cacheCap` per region, `activeChunkCap: 4` so each region keeps its chunk-ticket count bounded. |
| `lightweight` | Small server, you want RTP's footprint as small as the **pro** assembly allows. | Smaller region cache + chunk-ticket cap. *(If you also want L3 disabled, use the lite jar - the lite assembly ships with `backlogCacheCap: 0`. Prefabs do not change `backlogCacheCap`.)* |
| `fast-paced` | Normal server, normal hardware, you want `/rtp` to feel snappier without going full `high-performance`. | Shorter period, larger sync budget, bigger region cache and chunk-ticket cap. |
| `multi-world` | You want one RTP region per world on the server, instead of one default region covering one world. | At apply time, enumerates every loaded world and synthesises `regions/<worldName>.yml` cloning the current `regions/default.yml` with `world: "<worldName>"`. Existing per-world region files are left untouched. |

The exact set of knobs each prefab sets lives in `rtp-core` Java code under `io/github/dailystruggle/rtp/common/commands/prefab/builtin/`. The classes are short and readable - if you want the exhaustive diff, that is the source of truth.

### What prefabs do **not** touch

- `backlogCacheCap` (L3 backlog cache) - a pro-vs-lite assembly-time knob. The lite jar ships with it at `0`; the pro jar ships with it enabled. Runtime prefabs do not change it on either side.
- `config.yml`, `safety.yml`, `worlds/<world>.yml`, `economy.yml`, `effects.yml`, `logging.yml`. Prefabs are scoped to `performance.yml` + `regions/<id>.yml` by design. Hand-edit the rest.
- Comments. The on-disk YAML loader does not round-trip comments, so a confirmed apply writes a fresh file using the prefab's overlay merged onto the loaded keys. **The `.bak.<timestamp>` sibling preserves your original file byte-for-byte**, including comments; copy it back or use `/rtp admin prefab rollback` if you want them.

---

## Applying a prefab

### From the admin panel (recommended)

1. Run `/rtp admin` to open the admin panel (book on Paper, chat-paginated elsewhere).
2. Open the `Setup (quick start)` section at the top.
3. Click the prefab you want.
4. A **confirmation menu** opens, showing the per-file diff (which keys change, from what to what) and two rows:
   - `Confirm` - writes the change.
   - `Cancel` - returns to the admin panel without writing anything.
5. After `Confirm`, RTP writes `<file>.yml.bak.<timestamp>` siblings of every touched file, atomically replaces each file, then triggers `/rtp reload`. The result is the same as if you had hand-edited the files and run reload.

### From chat

```
/rtp admin prefab list
/rtp admin prefab apply <id>      # opens the confirmation menu
                                  # (a chat-paginated diff with clickable
                                  # Confirm / Cancel rows when no book is
                                  # available)
```

There is no `--commit` flag and no plain "write now" verb - applying always goes through the confirmation menu. The `Confirm` row dispatches `/rtp admin prefab confirm <id> <token>` internally, where `<token>` is a short-lived (~60 s) per-caller nonce. You don't type the confirm subcommand yourself; the menu emits it as a click action.

Permission required: `rtp.admin.prefab`. The Setup section and every panel row is hidden when the caller lacks it.

---

## Rolling back

```
/rtp admin prefab rollback <id>
```

Restores the most recent `<file>.yml.bak.<timestamp>` for every file that prefab would have touched (the files in its `appliesTo` set), then triggers `/rtp reload`. The bak files are kept on disk - rollback does not delete them.

RTP keeps the **last 3 baks per file** by default. Older baks are deleted on every confirm. If you need permanent backups, copy any `.bak.<timestamp>` aside before applying another prefab. The retention count is overridable via `performance.yml`:

```yaml
prefab:
  bakRetention: 3   # default; set higher if you apply prefabs often
```

---

## Picking the right prefab

A quick decision tree:

- **First-time install, not sure what to do?** Start without a prefab. Run `/rtp` once, see it works, then come back here if defaults feel wrong.
- **Server feels sluggish after install?** Run `/rtp info` and check TPS/MSPT. If TPS is below 19 under normal load, try `low-performance`. If TPS is fine but `/rtp` itself feels slow to deliver a location, try `fast-paced`.
- **On Folia?** Use `folia-tuned` after install. The other prefabs target the global scheduler model; Folia regions tick per-thread and want a different shape.
- **Going from one world to many?** Apply `multi-world` once after your worlds are created. Re-apply it whenever you add another world.
- **Want to fit on a small VPS?** `lightweight`. Pair with the lite jar if you also want L3 off.
- **Dedicated host, lots of players?** `high-performance`. If that still isn't enough, hand-edit further; you are past the curated zone.
- **Want defaults back?** `survival-default`. It is an identity overlay against the shipped defaults; applying it will revert any drift on the keys RTP cares about.

---

## What happens at apply time

1. **Read.** RTP loads the current on-disk YAML for every file in the prefab's `appliesTo` set.
2. **Merge.** For each file, the prefab's sparse overlay is merged onto the loaded tree. Keys the prefab does not mention are preserved exactly.
3. **Validate.** New regions created by `multi-world` are checked for required shape/vert/world keys; an incomplete region overlay aborts the apply with an error and writes nothing.
4. **Confirmation menu.** The merged result is diffed against the on-disk file; the diff is rendered as a per-file list of changed keys with old -> new values. You see this and pick `Confirm` or `Cancel`.
5. **Bak + atomic write.** On `Confirm`, each touched file is copied to `<file>.yml.bak.<timestamp>`. The merged YAML is written to `<file>.yml.tmp`, fsync'd, then renamed over `<file>.yml`.
6. **Reload.** RTP runs the equivalent of `/rtp reload` to pick up the new values.
7. **Audit log.** Every confirmed apply and every rollback is logged via `RTP.log(Level.INFO, ...)` with the caller id, prefab name, list of touched files, and bak paths. No silent-discard paths (REQ-RTP-S-004).

If you have players mid-teleport when the confirm runs, their in-flight pipelines complete against the pre-apply settings; new `/rtp` calls see the new settings after the reload completes. This is the same staleness contract a hand-edit + `/rtp reload` has.

---

## Known limitations (v1)

- **No custom prefabs.** The seven prefabs are hardcoded in Java. To add your own, you would need to fork or PR `rtp-core`. The design intentionally trades off extensibility for a small, curated, audited set.
- **Comments do not round-trip on the live file.** Use the `.bak` sibling if you need your comments back.
- **`multi-world` is not auto-re-applied.** If you add a new world after applying `multi-world`, the new world has no RTP region. Re-apply `multi-world` to pick it up; existing regions are not modified.
- **Per-platform filtering.** All seven prefabs show on every platform. Picking `folia-tuned` on Paper will apply the overlay but the regional-scheduler-specific advice will not apply (it just sets a shorter period and smaller caches, which is a valid Paper config too).

---

## Related docs

- [QUICK_START.md](QUICK_START.md) - end-to-end first-install sequence; prefabs are an optional shortcut, not a replacement.
- [PERFORMANCE.md](configuration/PERFORMANCE.md) - reference for every knob a prefab might set in `performance.yml`.
- [REGIONS.md](configuration/REGIONS.md) - reference for every knob a prefab might set in `regions/<id>.yml`.
- [RUNBOOK.md](RUNBOOK.md) - what to run when things go wrong after an apply (`/rtp info`, `/rtp test`, `/rtp scan reset`).
- [`docs/adr/ADR-024-rtp-lite-assembly-variant.md`](../adr/ADR-024-rtp-lite-assembly-variant.md) - the `lightweight` prefab is the pro-portable subset of the lite assembly's `regions/default.yml`; `backlogCacheCap` stays a lite-assembly-only knob.
- [`docs/dev/scratch/PROPOSAL-admin-panel-prefabs.md`](../dev/scratch/PROPOSAL-admin-panel-prefabs.md) - design rationale.
