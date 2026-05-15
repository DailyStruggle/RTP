# Research Checklist — Secure Inventory-Backed Menu Renderer (deferred follow-up to ADR-035)

> **Effective Issue (1-line)**: investigate whether a secure `InventoryMenuRenderer` can be added alongside the book/chat renderers from [ADR-035](../../adr/ADR-035-interactive-menus-book-first.md), without reopening the `InventoryClickEvent` desync exploit class.
>
> **Status**: research only. **No code lands** until a concrete design passes D-005 review and a successor ADR is accepted.
>
> **Origin**: deferred from ADR-035 *Out of scope* — "Inventory-backed menus … explicitly held open as a future follow-up". This file is the durable TODO record requested by the user; ADR-035's prose alone is not a tracked work item.
>
> **Mode when executing**: `[ADVANCED_CHAT]` for the research write-up; `[CODE]` only after a successor ADR is accepted.
>
> **Blocking decisions awaiting user approval (Rule D-005)**:
> - Any code under this checklist is gated on an accepted successor ADR to ADR-035.
> - The successor ADR is itself gated on the research items below producing a viable design (not on a calendar date).
>
> **Cleanup**: delete this file once either (a) the successor ADR is accepted and an implementation checklist supersedes it, or (b) the research concludes that no secure design exists and ADR-035 is amended to close the follow-up.

---

## Research items (must all be answered before drafting the successor ADR)

- [ ] **R1.** Catalogue every `InventoryClickEvent` interaction variant that has historically produced desync / duplication bugs in Bukkit-family plugins: cursor item, shift-click, number-key (hotbar) swap, drag (single / even split), offhand swap (`F`), creative middle-click, bundle interactions, the 1.21 inventory rework, double-click pickup-all, drop-key (`Q`), and any `InventoryDragEvent` corner cases. **Verification**: written matrix in the successor ADR draft; one row per variant with current safe-handling pattern and known footguns.
- [ ] **R2.** Determine whether a fully **server-authoritative virtual-inventory wrapper** is feasible — i.e. a wrapper that intercepts every variant in R1, never exposes the underlying `Inventory` to mutation, and re-renders rather than mutates on every click. **Verification**: design sketch in the ADR draft; explicit answer to "can a click ever leave the inventory state out of sync with the server's model?" with the reasoning.
- [ ] **R3.** Decide how clicks dispatch. **Constraint inherited from ADR-035**: every redeem MUST route through `MenuTokenRegistry` + `commands-api` so REQ-RTP-S-004 (no silent failures), S-005 (no main-thread chunk I/O), S-006 (API-before-core), and S-007 (configurable failure messages) are inherited, not re-implemented. **Verification**: design sketch shows the click handler minting / consuming the same opaque token type ADR-035 already defines, and dispatches via the `rtp menu:<token>` subcommand.
- [ ] **R4.** Cross-platform parity. **Constraint inherited from ADR-035 *Decision*** ("no platform logic in `rtp-core` / `rtp-api`"). Bukkit's inventory event model has no native `rtp-fabric` analogue; determine the Fabric-side equivalent (screen handler? container menu? deliberate "chat fallback on Fabric"?) **before** the renderer ships, so the renderer does not regress the parity rule. **Verification**: explicit Fabric strategy in the ADR draft; if "Fabric uses chat fallback indefinitely", that is acceptable but must be stated.
- [ ] **R5.** Threading audit. Confirm the click handler does **zero** synchronous chunk I/O and does not call into the teleport pipeline off the inventory event thread without going through the existing async chain (`TeleportPipelineTask` + `MemoryTracker`). **Verification**: design sketch references the same async path the book/chat renderers use; no new release-on-failure paths introduced.
- [ ] **R6.** Cross-server (multi-backend) interaction. The shared `MenuTokenRegistry` ([ADR-035 *Cross-server menus*](../../adr/ADR-035-interactive-menus-book-first.md)) already handles atomic consume across backends; verify the inventory renderer adds **no** new state outside that registry (no separate inventory-side cache that could diverge from the token store). **Verification**: ADR draft asserts the inventory renderer is purely a presentation layer over the existing token state machine.
- [ ] **R7.** Lite-assembly impact ([ADR-024](../../adr/ADR-024-rtp-lite-assembly-variant.md)): decide whether the inventory renderer is included by default, opt-in, or excluded from the lite jar. **Verification**: cap-key documented (e.g. `menu.renderer.inventory.enabled`); default chosen with rationale.
- [ ] **R8.** Survey prior art. Read the source of at least two well-regarded inventory-menu libraries (e.g. `InvUI`, `SmartInvs`, `IF`) and document **specifically** which `InventoryClickEvent` variants from R1 each one handles correctly, which it punts on, and which it gets wrong. **Verification**: short comparison table in the ADR draft. **Stay-on-task reminder**: any incidental bug found in those libraries is **not** ours to fix; do not record them in `POTENTIAL_BUGS.md` either — that file is for RTP findings.
- [ ] **R9.** D-005 review of the assembled design. **Verification**: explicit user approval recorded in the successor ADR's *Status* line; this checklist row only ticks after approval.
- [ ] **R10.** Draft the successor ADR using [`docs/adr/ADR-TEMPLATE.md`](../../adr/ADR-TEMPLATE.md). Title suggestion: *"Inventory-Backed Menu Renderer (successor to ADR-035 deferred follow-up)"*. Must reference ADR-035's held-open bullet and supersede it only on the inventory-renderer scope (book / chat renderers remain primary). **Verification**: ADR file present under `docs/adr/`; row added to [`docs/adr/README.md`](../../adr/README.md); ADR-035 *Out of scope* bullet amended to point at the new ADR.

---

## Out of scope for this research

- Implementing any `InventoryMenuRenderer` code. The whole point of this checklist is to keep the work in the research / ADR stage until R1–R9 are answered.
- Reopening the book / chat renderer design from ADR-035. Those are the primaries; the inventory renderer is purely additive.
- Inventory-based **input** affordances (anvil rename trick, sign-edit, etc.) — ADR-035 already considered and rejected them; revisiting them is a separate research thread, not this one.

---

## Cross-references

- [ADR-035](../../adr/ADR-035-interactive-menus-book-first.md) — primary menu ADR; *Out of scope* bullet is the origin of this research thread.
- [ADR-038](../../adr/ADR-038-rtpadmin-setup-wizards.md), [ADR-039](../../adr/ADR-039-rtpadmin-diagnostic-surfaces.md), [ADR-040](../../adr/ADR-040-cross-backend-metric-time-series-publication.md), [ADR-041](../../adr/ADR-041-config-command-and-save-implementation.md) — downstream consumers of the menu primitive; any inventory renderer must compose with them without breaking their book-first assumptions.
- `AGENTS.md` → *Architecture Boundaries*, *Propose Before Implementation (Rule D-005)*, *Stay-On-Task Policy* — the procedural guardrails this checklist obeys.
