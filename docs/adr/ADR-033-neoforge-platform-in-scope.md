# ADR-033 — NeoForge Platform In-Scope (Deferred Until Fabric Stabilizes)

**Status:** Proposed
**Date:** 2026-05-11

## Context

The supported-platform set in [`REQUIREMENTS.md`](../dev/REQUIREMENTS.md) historically enumerated Spigot, Paper, Folia, and Fabric, and explicitly listed NeoForge among **unsupported** mod loaders. That exclusion was reasonable when the active platform-expansion frontier was Fabric (still unstable; see *Current Development Focus* in [`.junie/AGENTS.md`](../../.junie/AGENTS.md) and the open S-005 / null-stub blockers tracked in [`MULTI_PLATFORM_PLAN.md`](../dev/MULTI_PLATFORM_PLAN.md)).

The platform landscape has shifted enough that the blanket exclusion now under-serves the strategic direction of the project:

- **NeoForge is the dominant modded-Java server platform on 1.20.4+.** It is the active successor to legacy Forge, has captured the large-modpack audience (the user profile that benefits most from a bounded-distribution RTP), and is on a release cadence aligned with vanilla Minecraft.
- **Most of the platform-abstraction work needed for NeoForge is already being paid for by Fabric.** The Mojmap-decoupling discipline ([rtp-fabric-ADR-007](../../rtp-fabric/docs/adr/rtp-fabric-ADR-007-mojmap-name-decoupling.md)), the obf/unobf carrier split ([rtp-fabric-ADR-009](../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md)), non-blocking chunk generation ([rtp-fabric-ADR-008](../../rtp-fabric/docs/adr/rtp-fabric-ADR-008-non-blocking-chunk-generation.md)), non-persistent chunk tickets ([rtp-fabric-ADR-003](../../rtp-fabric/docs/adr/rtp-fabric-ADR-003-non-persistent-chunk-tickets.md)), anvil-prefilter parity ([rtp-fabric-ADR-005](../../rtp-fabric/docs/adr/rtp-fabric-ADR-005-anvil-prefilter-parity.md)), and the typed block-tag snapshot ([rtp-fabric-ADR-010](../../rtp-fabric/docs/adr/rtp-fabric-ADR-010-typed-block-tag-snapshot.md)) all carry over near-verbatim to NeoForge.
- **NeoForge's distinct API surface is narrow.** It diverges from Fabric primarily at the platform entry point (`@Mod`-annotated class + mod/game `IEventBus` vs. `ModInitializer` + callback registries), the command-registration trampoline (`RegisterCommandsEvent` vs. `CommandRegistrationCallback` — both terminating in vanilla Brigadier, so [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) is reusable), mod metadata (`neoforge.mods.toml` vs. `fabric.mod.json`), and the build toolchain (NeoGradle / ModDevGradle vs. Loom). Threading and chunk-ticket substrate are vanilla — S-005 reasoning carries over unchanged with no region-ownership analog.
- **The exclusion was framed defensively, not architecturally.** No design constraint in `rtp-core` or `rtp-api` is hostile to NeoForge; the gap is implementation, not interface (mirroring the April 2026 Fabric gap analysis).

Pre-proposal landscape, API-surface delta, reuse map, risks, and module-layout sketch are captured in [`NEOFORGE_NOTES.md`](../dev/NEOFORGE_NOTES.md). This ADR converts that scratch into a binding scope decision.

What this ADR is **not**:

- It is **not** an approval to begin NeoForge implementation work today. Implementation remains gated on Fabric reaching the stability bar defined in [`MULTI_PLATFORM_PLAN.md`](../dev/MULTI_PLATFORM_PLAN.md) (no open S-005 violations in `rtp-fabric`, the `FabricServerAccessor.getLocationGenerator` null stub resolved, the Loom dependency resolved, and a green `rtp test full` on at least one shipped MC carrier).
- It is **not** an expansion of the supported platform set to mod loaders in general. Legacy Forge, Sponge, Minestom, hybrid servers (Mohist, Magma, Arclight), and Bedrock-native servers remain explicitly out of scope.

## Decision

1. **NeoForge becomes a first-class, in-scope target platform**, joining Spigot, Paper, Folia, and Fabric. The requirements documents are amended accordingly:
   - `REQ-RTP-NF-002` (Cross-Platform Thread Safety) extended to enumerate NeoForge.
   - `REQ-RTP-SYS-002` (Server Software) extended to include NeoForge.
   - The §0 *In Scope* and *Out of Scope* lists updated to reflect NeoForge as supported and to enumerate the platforms that remain unsupported.
2. **Activation of NeoForge work is gated** on Fabric stabilization, per the criteria above. Until that gate clears, NeoForge remains a documented in-scope target with **no committed delivery date** and **no module skeleton** under version control.
3. **When the gate clears**, the bring-up sequence shall be:
   1. A D-005 proposal referencing this ADR and `NEOFORGE_NOTES.md`.
   2. A subproject ADR `rtp-neoforge/docs/adr/rtp-neoforge-ADR-001-platform-in-scope.md` mirroring [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md) (per the *Self-Updating Protocol* in `.junie/AGENTS.md`, subproject ADRs restart numbering at `001`).
   3. Phase rows added to `MULTI_PLATFORM_PLAN.md` for the NeoForge axis.
   4. REQ-traceable tests for S-005 and S-006 (`ReqRtpNeoforgeS005ChunkLoadingTest`, `ReqRtpNeoforgeS006EarlyApiTest`) added to [`TRACEABILITY.md`](../dev/TRACEABILITY.md) **before** anvil/ticket parity work.
4. **Mod-side claim integrations** (FTB Chunks, OpenPartiesAndClaims, Argonauts, etc.) are handled identically to Bukkit claim plugins: reflection-gated soft hooks per [ADR-026](ADR-026-external-hook-api-surface.md), cataloged in [`EXTERNAL_HOOKS.md`](../dev/EXTERNAL_HOOKS.md). No claim-mod code lives in the pipeline (S-003).
5. **No retroactive obligation.** Existing prohibition requirements (S-001..S-007) apply to the NeoForge adapter on the same terms as every other platform; nothing in this ADR weakens or grandfathers them.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep NeoForge permanently out of scope. | Forfeits the largest modded-Java audience and contradicts the strategic priority order already implicit in `NEOFORGE_NOTES.md` and the Fabric work. The exclusion was defensive at a time when even Fabric was unproven; that justification no longer holds. |
| Approve NeoForge as in-scope **and** begin implementation immediately, in parallel with Fabric. | Splits the maintenance budget across two unstable bring-ups. Fabric has open S-005 and null-stub blockers; opening a second mod-loader axis risks regressing both. Sequencing Fabric → NeoForge captures the reuse map cleanly. |
| Cover NeoForge via a hybrid-server shim (e.g., Mohist) rather than a native adapter. | Hybrid servers expose a Paper API on top of Forge/NeoForge but are notoriously unstable, lag MC versions, and routinely violate the assumptions of both ecosystems. Supporting them transitively through `rtp-paper` is acceptable as a runtime-compatibility courtesy, but it is **not** a substitute for a native adapter. |
| Fold NeoForge into a shared "modded" module tree with Fabric. | Tempting (both are Mojmap-at-runtime on modern MC) but their build systems (Loom vs. NeoGradle/ModDevGradle), event models, and registration trampolines diverge enough that a shared tree would couple two unrelated dependency graphs and slow both. Keep adapters sibling, not nested. |
| Cover NeoForge via legacy Forge support. | Forge ≤1.20.1 is sunsetting; the active ecosystem has moved to NeoForge. Investing in Forge would buy a shrinking audience at full adapter cost. |

## Consequences

- **Positive:**
  - Aligns the documented scope with the realistic strategic direction; eliminates the contradiction between the "Forge/NeoForge shall not be supported" clause and the NeoForge-positive analysis already living in `NEOFORGE_NOTES.md`.
  - Lets Fabric work be done with NeoForge reuse in mind without ad-hoc justification ("we said it was out of scope, but…").
  - Gives third-party addon authors a clear forward signal: `rtp-api` remains the integration surface; no NeoForge-specific addon API is planned.
  - Codifies the gating discipline: NeoForge bring-up cannot begin until Fabric's S-005 / null-stub blockers are closed, which protects Fabric's stabilization timeline.

- **Negative / Trade-offs:**
  - The supported platform matrix grows from four to five families, expanding the release-test surface and the lite-jar assembly matrix ([ADR-024](ADR-024-rtp-lite-assembly-variant.md)) once NeoForge ships. The matrix expansion is bounded by per-version carriers ([ADR-010](ADR-010-versioned-platform-adapter-submodules.md)).
  - Documentation surface increases (a `rtp-neoforge/REQUIREMENTS.md`, subproject ADRs, plan-doc rows, TRACEABILITY entries) before any code lands. Mitigated by the scratch notes already covering most of the design ground.
  - Risk of premature implementation work if the Fabric gate is not strictly enforced. Mitigated by the explicit gating clause in §2 of the *Decision*.
  - Future requirement and ADR drift: every cross-platform requirement now must mentally include NeoForge even before the module exists. Mitigated by listing NeoForge alongside the other platforms in the amended requirement text.

## References

- [`NEOFORGE_NOTES.md`](../dev/NEOFORGE_NOTES.md) — pre-proposal scratch (rationale, API-surface delta, reuse map, risks, module-layout sketch, S-00x mapping).
- [`REQUIREMENTS.md`](../dev/REQUIREMENTS.md) — amended In-Scope list, Out-of-Scope clause, `REQ-RTP-NF-002`, and `REQ-RTP-SYS-002`.
- [`MULTI_PLATFORM_PLAN.md`](../dev/MULTI_PLATFORM_PLAN.md) — current phase status; NeoForge phase rows to be added on activation.
- [rtp-fabric-ADR-002](../../rtp-fabric/docs/adr/rtp-fabric-ADR-002-platform-in-scope.md) — precedent: Fabric in-scope decision; the NeoForge subproject ADR will mirror its shape.
- [rtp-fabric-ADR-007](../../rtp-fabric/docs/adr/rtp-fabric-ADR-007-mojmap-name-decoupling.md), [rtp-fabric-ADR-009](../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md) — Mojmap discipline and carrier-split pattern reused by NeoForge.
- [ADR-010](ADR-010-versioned-platform-adapter-submodules.md) — versioned per-MC carrier pattern.
- [ADR-024](ADR-024-rtp-lite-assembly-variant.md) — lite-jar assembly matrix (impacted on activation).
- [ADR-026](ADR-026-external-hook-api-surface.md) — reflection-gated hook surface for FTB Chunks and other mod-side claim integrations.
- [`.junie/AGENTS.md`](../../.junie/AGENTS.md) — *Current Development Focus* (Fabric frontier) and *Self-Updating Protocol* (subproject ADR numbering).
