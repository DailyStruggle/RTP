# Checklist — Amend Platform-Support Requirements & Draft NeoForge ADR

> **Editable.** This is a scratch working note (per *Checklist-Based State Tracking* in `.junie/AGENTS.md`). Delete on submit, or once the work is folded into the real plan/ADR set. Findings tagged **FINDING** are surfaced for follow-up; the rest are mechanical task tracking.

**Effective Issue:** Amend requirements for platform support to admit NeoForge, and draft an ADR to address it.
**Mode:** `[CODE]`.
**Blocking decisions awaiting user approval (D-005):** none yet — this change is documentation-only and reverses a previously documented exclusion, so user approval will be sought via the submit summary rather than via a pre-implementation proposal.

---

## Steps

- [x] 1. Locate platform-support requirement text — `docs/dev/REQUIREMENTS.md` lines 25, 33, 99, 138.
- [x] 2. Determine next global ADR number — **ADR-033** (ADR-027 / ADR-029…031 already skipped in the existing sequence; ADR-032 is the last present).
- [x] 3. Amend `REQUIREMENTS.md`:
  - In-Scope (§0): add NeoForge to platform-adapter line; cross-link to ADR-033.
  - Out-of-Scope (§0): replace the blanket "Forge, NeoForge, and other mod loaders shall not be supported" clause with an enumerated unsupported list (legacy Forge, Sponge, Minestom, hybrid servers, Bedrock-native).
  - `REQ-RTP-NF-002`: enumerate Spigot/Paper/Folia/Fabric/NeoForge.
  - `REQ-RTP-SYS-002`: include NeoForge with an explicit gating clause referencing ADR-033.
- [x] 4. Draft `docs/adr/ADR-033-neoforge-platform-in-scope.md` — Proposed status, gated on Fabric stabilization, mirrors `rtp-fabric-ADR-002` shape, cross-links `NEOFORGE_NOTES.md`.
- [x] 5. Create this scratch checklist.
- [ ] 6. Submit with summary, calling out the open follow-ups below.

---

## Findings (editable — surface for follow-up)

- **FINDING-1 — `MULTI_PLATFORM_PLAN.md` rows not yet added.** ADR-033 explicitly defers NeoForge phase rows until the Fabric stabilization gate clears (no S-005 violations in `rtp-fabric`, `FabricServerAccessor.getLocationGenerator` null stub resolved, Loom dependency resolved, green `rtp test full` on a shipped MC carrier). No row added in this change. Confirm with user whether they want a placeholder "deferred" row added now or only on gate-clear.
- **FINDING-2 — Subproject ADR not created.** Per `.junie/AGENTS.md` *Self-Updating Protocol*, a `rtp-neoforge/docs/adr/rtp-neoforge-ADR-001-platform-in-scope.md` would mirror `rtp-fabric-ADR-002`. Deliberately deferred: there is no `rtp-neoforge/` directory yet, and creating an ADR for a non-existent subproject would invert the normal order (subproject ADR follows subproject creation). Revisit on gate-clear.
- **FINDING-3 — `TRACEABILITY.md` not touched.** No NeoForge REQ-traceable tests exist yet; ADR-033 explicitly defers adding rows. No-op for this change.
- **FINDING-4 — `NEOFORGE_NOTES.md` framing is now slightly outdated.** Its opening line calls NeoForge "out of scope until Fabric stabilizes." With ADR-033 it is **in scope but deferred**. Minor wording mismatch only; not worth amending in this change since the notes file is explicitly scratch and slated for deletion once a real plan exists. Left as-is.
- **FINDING-5 — `EXTERNAL_HOOKS.md` and ADR-026 catalog rows.** No FTB Chunks / OpenPartiesAndClaims hook exists today. ADR-033 only commits to the **mechanism** (reflection-gated soft hook via ADR-026), not the rows. No catalog row added.
- **FINDING-6 — `.junie/AGENTS.md` *Current Development Focus* mentions only Fabric.** It does not currently mention NeoForge as next-in-queue. Deliberately not edited here — that section is for the *active* frontier, not the deferred queue. If the user prefers an explicit "next: NeoForge (deferred)" line, that is a one-line addition to make on their cue.
- **FINDING-7 — CHANGELOG.md not touched.** Per *CHANGELOG Hygiene* this is a requirements/ADR change in an unreleased cycle; whether it deserves a bullet under `[3.0.0-beta.2]` depends on whether the released audience cares about scope-statement movement. Recommend a one-bullet entry under a "Documentation" sub-heading; deferred to user preference.

---

## Files Touched

- `docs/dev/REQUIREMENTS.md` — 4 edits (in-scope, out-of-scope, NF-002, SYS-002).
- `docs/adr/ADR-033-neoforge-platform-in-scope.md` — new ADR (Proposed).
- `docs/dev/scratch/CHECKLIST-neoforge-scope-amend.md` — this file (delete on completion).

## Files Deliberately Not Touched (and why)

- `docs/dev/NEOFORGE_NOTES.md` — see FINDING-4.
- `docs/dev/MULTI_PLATFORM_PLAN.md` — see FINDING-1.
- `docs/dev/TRACEABILITY.md` — see FINDING-3.
- `docs/dev/EXTERNAL_HOOKS.md` — see FINDING-5.
- `.junie/AGENTS.md` — see FINDING-6.
- `CHANGELOG.md` — see FINDING-7.
- Any `rtp-*` module REQUIREMENTS — no NeoForge module exists; cross-platform language in module-level docs continues to enumerate Spigot/Paper/Folia/Fabric until the NeoForge module lands.
