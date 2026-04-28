# `rtp test full` — Pre‑Release Findings & Fix Plan

**Source**: `/rtp test full` invocation on Folia at `2026-04-27 01:02:51` (player `leaf26`).
**Status**: suite ran end‑to‑end without throwing. Two release‑blocking defects, one coverage gap, two output‑quality issues, one polish item.
**Release‑ready criterion**: every phase reports `ok` or an explicit `skipped(<reason>)`; no `TIMEOUT`; no `UNSUPPORTED` on the platform under test.

---

## Phase Results Snapshot

| Phase | Result | Notes |
|------|--------|-------|
| commands (audit) | ✅ ok | audited=59, issues=0 |
| api-compat | ✅ ok | ok=10, errors=0 |
| chunk-ticket | ✅ ok | residual=0 |
| disconnect-midflight | ✅ ok | all flags cleared |
| anvil-prefilter | ⚠ no probes | total=0 — ambiguous (disabled vs. no teleport) |
| biome-source | ⚠ no probes | total=0 — ambiguous (same) |
| async-chunk-load | ✅ ok | elapsedMs=15 |
| scheduler/async | ✅ ok | latency=266 µs |
| scheduler/primary | ✅ ok ⚠ high latency | latency=118 893 µs (~119 ms) |
| scheduler/region | ✅ ok | latency=31 734 µs (~32 ms) |
| folia-ownership | ❌ UNSUPPORTED | "no org.bukkit.Location handle" — *on Folia* |
| queue-starvation | ✅ ok | 3/3 successes, avg 10 ms |
| economy-isolation | ✅ ok | debit ran off region thread |
| commands-live | ✅ ok | 5 attempted, 0 silent failures |
| disconnect-job | ✅ ok | tickets/data cleared |
| async-reply | ❌ TIMEOUT | 5 000 ms, needles `teleporting`/`searching` |

---

## P0 — Hard Failures (must fix before release)

### F1. `async-reply` TIMEOUT (no tracked reply within 5 000 ms)

```
[RTP test/async-reply] TIMEOUT: no tracked reply within 5000ms (needles=teleporting/searching)
```

The probe registers an interceptor on `SendMessage` and waits up to 5 s for any message containing `teleporting` or `searching`. Nothing matched.

**Possible causes (rank‑ordered)**

1. The interceptor was attached *after* the synthetic teleport's "searching/teleporting" reply was already emitted. The log shows `[RTP test/async-reply] begin for player=leaf26` arriving in the middle of the `commands-live` block — i.e., the `begin` marker itself is late, suggesting registration follows dispatch.
2. The needle strings (`teleporting` / `searching`) no longer match what `messages.yml` emits — recent changes to `LanguageCmd.java`, `TRANSLATION_GUIDE.md`, and `lang/es/messages.yml` are listed in the touched‑files set.
3. On Folia the reply is dispatched on a `Folia Async Scheduler Thread`; the interceptor list may be keyed by region/thread.

**Action**

- Confirm both default and `lang/es/messages.yml` still emit a string containing `teleporting` or `searching` (case‑insensitive); update needles or messages so they intersect.
- Move interceptor registration *before* the synthetic teleport is dispatched (`AsyncReplyTestJob`).
- Increase the timeout only after root‑causing.

**Files**: `rtp-plugin/.../bukkit/commands/test/AsyncReplyTestJob.java`, `rtp-plugin/src/main/resources/messages.yml`, `rtp-plugin/src/main/resources/lang/*/messages.yml`.
**Traceability**: REQ‑RTP‑F‑013 (configurable user‑facing messages).

---

### F2. `folia-ownership` reports UNSUPPORTED on Folia

```
[RTP test/folia-ownership] UNSUPPORTED (no org.bukkit.Location handle on this platform)
```

The platform under test *is* Folia (thread names confirm: `Folia Async Scheduler Thread #11`). `org.bukkit.Location` is available on Folia. Reporting `UNSUPPORTED` defeats the purpose of the test on the only platform that needs it.

**Likely root cause**: `FoliaOwnershipTestJob` probes for `Location` via reflective `Class.forName` whose classloader is wrong, *or* the synthetic location was created with a null world reference and the job conflates "null location" with "no Location class".

**Action**

- Replace the reflective `Class.forName` guard with a direct import + `Bukkit.isOwnedByCurrentRegion(...)` call.
- Gate the `UNSUPPORTED` branch only on `!RTP.serverAccessor.isFolia()`.
- Add a unit/integration assertion that on Folia this phase reports `ok`.

**Files**: `rtp-plugin/.../bukkit/commands/test/FoliaOwnershipTestJob.java`.
**Traceability**: REQ‑RTP‑P‑Folia‑* (region ownership), Pre‑Flight Checklist §2.

---

## P1 — Coverage Gaps

### F3. Anvil pre‑filter & biome‑source recorded zero observations

```
[RTP test/anvil-prefilter] accepts=0 rejects=0 unknowns=0 total=0 reject-rate=0.000 hit-rate=0.000
[RTP test/biome-source] anvil-hits=0 live-hits=0 total=0 anvil-hit-rate=0.000
```

The suite itself notes the result is ambiguous (disabled vs. no teleport since startup). For an operator running `rtp test full` to certify a release, ambiguity must be resolved.

**Action**

- In `TestAnvilPrefilterCmd` / `TestBiomeSourceCmd`, either:
  - (a) trigger one non‑destructive synthetic candidate evaluation so counters become non‑zero, or
  - (b) emit `SKIPPED(<reason>)` with a single, unambiguous reason — `SafetyKeys.anvilPrefilterEnabled=false`, or `no candidate evaluated since startup`.
- Update the summary line so the operator sees `ok | skipped | fail`, not just counters.

**Files**: `rtp-plugin/.../bukkit/commands/test/TestAnvilPrefilterCmd.java`, `TestBiomeSourceCmd.java`.
**References**: ADR‑015 (stale‑chunk guard), ADR‑016 (anvil subsystem).

---

## P2 — Output Quality / Cosmetic

### F4. Duplicate emission of malformed‑input messages with raw `&c` color codes

```
&c[P0] invalid command argument player:thatdoesnotexist
[RTP] invalid command argument player:thatdoesnotexist
```

Each malformed‑input case prints two lines:

1. `&c[P0] …` — sent via `SendMessage` to the console sender; the `&c` legacy code is *not* translated and leaks into the log as literal text.
2. `[RTP] …` — emitted via `RTP.log(WARNING, …)` from the `BukkitBaseRTPCmd.msgInvalidCommand` / `msgBadParameter` overrides (mandated by the Logging & Feedback section for REQ‑RTP‑S‑004 auditing).

The dual emission is intentional (audit + user‑facing). The cosmetic issue is the literal `&c` and the `[P0]` priority prefix bleeding into the console copy.

**Action**

- In `SendMessage.log` (rtp‑spigot), translate `&` codes before forwarding to `Bukkit.getConsoleSender()` — Folia console does not always render legacy codes the same way Spigot did.
- Decide whether the `[P0]` priority prefix is intended for the audit log (`RTP.log`) only and should be stripped from the console echo. Document the chosen channel split.
- Add a regression assertion (style: `ReqRtpS004NullChunkAttributionTest`) that console output never contains the literal substring `&c`.

**Files**: `rtp-spigot/rtp-spigot-common/.../SendMessage.java`, `rtp-core/.../BaseRTPCmdImpl` (priority‑prefix builder).
**Traceability**: REQ‑RTP‑S‑004 (audit path must remain intact), REQ‑RTP‑F‑013.

---

### F5. Scheduler primary‑thread latency unusually high

```
[RTP test/scheduler] async:   ok latency=266us
[RTP test/scheduler] primary: ok latency=118893us
[RTP test/scheduler] region:  ok latency=31734us
```

~119 ms primary‑thread and ~32 ms region tick latency are *passing* but suspicious on an idle test server (async baseline is 266 µs).

**Action**

- Add soft thresholds in `TestSchedulerCmd` (e.g., WARN if primary > 50 ms, region > 20 ms) so future regressions surface.
- Not a release blocker.

**Files**: `rtp-plugin/.../bukkit/commands/test/TestSchedulerCmd.java`.

---

## P3 — Documentation / Polish

### F6. Phase interleaving in console output

The `commands-live` malformed‑input block prints *between* `scheduler async` and `scheduler primary` results because phases run concurrently. An operator reading the log linearly may not connect a `[P0] invalid command` line to the phase that produced it.

**Action**

- Tag each malformed‑input emission with the originating phase, e.g. `[RTP test/commands-live] dispatch: player:thatdoesnotexist`.
- Alternatively, serialize phases under `rtp test full` while keeping parallel execution available on individual sub‑commands.

**Files**: `LiveCommandDispatcherTestJob.java`, `TestFullCmd.java`.

---

## Suggested Execution Order

1. **F2** — folia‑ownership: small, isolated; restores a Folia‑critical signal.
2. **F1** — async‑reply TIMEOUT: likely a one‑line ordering fix in `AsyncReplyTestJob`; verify against localized `messages.yml`.
3. **F4** — `&c` leak / dual log: `SendMessage.log` color translation + regression test.
4. **F3** — anvil/biome `SKIPPED` vs `ok` clarity: extend summary lines.
5. **F5**, **F6** — polish; non‑blocking.

After each fix, re‑run `/rtp test full` on Folia and confirm the release‑ready criterion above.

---

## Out of Scope

- **Fabric** is explicitly out of scope per `REQUIREMENTS.md §0` and the *Current Development Focus* in `AGENTS.md`. No Fabric findings are listed even though `rtp-fabric` has known blockers (S‑005 in `FabricWorld.getChunkAt`; null `FabricServerAccessor.getLocationGenerator` stub; unresolved Loom dependency).

---

*Generated 2026-04-27 from a single-run console capture; re‑validate with a fresh `/rtp test full` after each fix.*
