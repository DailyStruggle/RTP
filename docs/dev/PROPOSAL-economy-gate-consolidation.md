# Proposal: Consolidate teleport economy charging onto a single `EconomyGate`

Status: APPROVED and IMPLEMENTED (Rule D-005)
Date: 2026-07-27
Scope: `rtp-core` (+ additive test); no `rtp-api` surface change

## Problem

Teleport charging logic exists in **two independent copies**:

1. `RTPCmd.compute(...)` (the `/rtp` command path) - inline, in two branches:
   - self branch (`RTPCmd.java` ~754-788): `EconomyKeys.price` + optional `paramsPrice`/`biomePrice` + `RegionKeys.price`, `balanceFloor` check, `EconomyHop.take`, and a `PlayerMessages.notEnoughMoney` chat message. Accumulates into `TeleportData.cost` for later refund-on-failure.
   - other-player branch (`RTPCmd.java` ~790-825): same, but uses `EconomyKeys.priceOther`, gated on `toggleTargetPerms` and `!uuid.equals(senderId)`, and honors the `rtp.notme` skip.
2. `EconomyGate.chargeSelf(...)` (added for the addon-facing API) - wired into `RTP.java`'s `teleportDelegate` and `runLocalTeleport`. Self-payer only; no params/biome (an `RtpTarget` carries none), no chat message, does not touch `TeleportData`.

Today the two paths do not overlap (the command schedules its own `TeleportPipelineTask` directly at `RTPCmd.java` ~931-957; it does not route through `RTPAPI.teleport`). But the duplication is a latent **double-charge / divergence hazard**: any future change that makes the command path funnel through the API delegate (or vice versa), or that edits one copy of the price math and not the other, silently produces a double charge or a pricing mismatch. One source of truth removes that class of bug.

## The economy SPI already exists (parity with biome checks)

The swappable provider seam is already in place - no new SPI is needed:

- `RTPEconomy` (`rtp-api`, `give`/`take`/`bal`) is the platform- and plugin-agnostic economy abstraction.
- It is bound at runtime through `RTPAPI.hooks().economy().bind(RTPEconomy)` (`EconomyProviderRegistry`, backed by the `RTP.economy` field in `DefaultRTPHooks`).
- `VaultChecker` (`rtp-plugin`) is merely the *default* binding installed by `RTPBukkitPlugin.setupIntegrations()`. An addon can call `bind(...)` with any implementation to replace Vault with another economy system.

This is directly analogous to the biome-check / region-verifier seam (`RegionVerifierRegistry.register(...)`, `AnvilPrefilterRegistry.bind(...)`): the provider is swappable, the core never hard-codes the backend. So "an addon can swap out Vault like they can for biome checks" is **already true at the provider level**.

What is *missing* is a single internal orchestrator that sits on top of that provider and owns the price computation + affordability + withdrawal. Today that orchestration is duplicated (command path inline vs. `EconomyGate.chargeSelf`). Consolidating onto `EconomyGate.charge`/`chargeSelf` makes the swappable `RTPEconomy` provider the *only* place money leaves an account, through the *one* gate - which is exactly the "lean toward using `chargeSelf`/`charge`" direction.

Layering after this change:
- `RTPEconomy` (rtp-api) = swappable provider SPI (addon-replaceable, Vault is just the default).
- `EconomyGate` (rtp-core, internal) = single price-math + floor + `take` orchestrator over whatever provider is bound.
- `RTPCmd` / `RTP.java` delegate / `runLocalTeleport` = call sites that own only messaging + `TeleportData.cost` bookkeeping.

## Goal

A single charging helper is the only place that:
- reads `EconomyKeys.price` / `priceOther` / `paramsPrice` / `biomePrice` and `RegionKeys.price`,
- performs the `balanceFloor` affordability check,
- performs the fire-and-forget `EconomyHop.take` withdrawal.

Message emission and `TeleportData.cost` bookkeeping stay at the call sites (they differ per path and must not be centralized).

## Proposed structure

Generalize `EconomyGate` into a single computation + charge call that returns the computed cost so callers can still record it for refunds.

```java
public final class EconomyGate {
  public enum Result { ALLOWED, INSUFFICIENT_FUNDS }

  /** Whether to charge the self price key or the "other player" price key. */
  public enum Payer { SELF, OTHER }

  /** Immutable outcome carrying the affordability verdict and the computed cost. */
  public record Charge(Result result, double cost) {}

  /**
   * Compute + apply the charge for one payer. Skips (returns ALLOWED, cost 0) when
   * no economy is bound, payer is the console id, or the payer has rtp.free.
   * On INSUFFICIENT_FUNDS it does NOT withdraw. On ALLOWED with cost>0 it fires the
   * EconomyHop.take. Does not send messages and does not touch TeleportData.
   */
  public static Charge charge(UUID payerId, RTPPlayer payer, Region region,
                              Payer which, boolean hasParams, boolean hasBiome);
}
```

Behavior mapping:
- `price` key selected by `which` (`SELF` -> `EconomyKeys.price`, `OTHER` -> `EconomyKeys.priceOther`).
- `hasParams` adds `paramsPrice`; `hasBiome` adds `biomePrice`.
- `region` (nullable) adds `RegionKeys.price`.
- `rtp.free` / console / no-economy -> `Charge(ALLOWED, 0.0)` with no withdrawal.
- affordability: `bal(payerId) - cost < balanceFloor` -> `Charge(INSUFFICIENT_FUNDS, cost)`, no withdrawal.

Keep the existing `chargeSelf(...)` as a thin deprecated-internal delegate to `charge(id, player, region, Payer.SELF, hasParams, hasBiome)` (or just replace its two call sites in `RTP.java` and delete it).

### Call-site changes

1. `RTP.java` `teleportDelegate` and `runLocalTeleport`: call `charge(uuid, player, targetRegion, Payer.SELF, false, false)`; on `INSUFFICIENT_FUNDS` complete the future with `RTPResult.Reason.INSUFFICIENT_FUNDS` and clear the processing lock (unchanged behavior; cost is ignored since the API has no refund path). 
2. `RTPCmd.compute` self branch: replace the inline block with `charge(senderId, player, region, Payer.SELF, shapeNames!=null||vertNames!=null||doWBO, biomeList!=null)`. On `INSUFFICIENT_FUNDS` send `notEnoughMoney`, clear locks, `return true` (as today). On `ALLOWED` set `data.cost += charge.cost()` for the refund path. Preserve the `rtp.notme` `continue` at the call site (it is control flow, not charging).
3. `RTPCmd.compute` other-player branch: same with `Payer.OTHER`, guarded by the existing `toggleTargetPerms && !uuid.equals(senderId)` condition, message sent to the target.

The `notEnoughMoney` message text, `[money]` substitution, `sendMessage` targets, `data.cost` accumulation, and the `take`-returned-false warning message all remain at the call sites. Only the price math + floor check + withdrawal move into `EconomyGate`.

## Requirements / ADR references

- REQ-RTP-F-013 (configurable user-facing messages) - `notEnoughMoney` stays call-site, so no change.
- S-004 (no silently discarded teleport failure) - money path is S-004-adjacent; the affordability rejection remains explicit (chat message on command path, `INSUFFICIENT_FUNDS` result on API path). No swallowing introduced.
- S-005 - withdrawal stays fire-and-forget via `EconomyHop`; no blocking future added.

## Risks and trade-offs

- Behavior-preserving refactor of a money path with existing coverage (`RTPTest`, `RtpApiTeleportSurfaceTest`, and the command-path economy assertions). Risk: subtle divergence in cost accumulation order or the `rtp.notme`/`toggleTargetPerms` gating. Mitigation: keep those gates as call-site control flow; add a unit test asserting `charge(...).cost()` equals the old inline sum for a params+biome+region combo on both `SELF` and `OTHER`.
- `Charge` record is a new internal type in `rtp-core` (not `rtp-api`), so no public surface change and not itself D-005-gated beyond this refactor.
- No double-charge is possible today; this change is preventive (single source of truth) plus dedup, not a live-bug fix.

## Verification plan

- New `EconomyGateTest` (or extend `RTPTest`): `SELF`/`OTHER` price selection, params/biome/region additivity, `rtp.free`/console/no-economy skip, floor rejection with no withdrawal.
- Re-run `RTPTest` (economy + existing 34) and `RtpApiTeleportSurfaceTest`.
- Full `.\gradlew.bat build`.

## Open questions for approval

1. Delete `chargeSelf(...)` outright (replace its 2 call sites) or keep it as a thin delegate?
2. Should the command path's `data.cost` refund semantics be documented/asserted as-is, or is a refund-on-failure test out of scope for this consolidation?
