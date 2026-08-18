# ADR-050 - Concrete Menu Commands Supersede Token Registries

**Status:** Accepted
**Date:** 2026-05-24 (Proposed) / 2026-05-24 (Accepted, all stages landed)
**Supersedes (in part):** [ADR-035](ADR-035-interactive-menus-book-first.md) sections on `MenuTokenRegistry` (section 3 "Click handling", section "Security boundary"); [ADR-047](ADR-047-declarative-chart-composition-bridge.md) sections on `ChartSpecTokens` opaque UUID handles for `OpenMap`.

## Context

ADR-035 (interactive menus, book-first) introduced a `MenuTokenRegistry` (`rtp-api`) plus a `LocalMenuTokenRegistry` (`rtp-core`) that mints opaque single-use, player-bound, TTL-expiring tokens for every clickable book fragment. Each click in the rendered book carries the opaque token as the entire payload of a `/rtp menu token=<token>` redeem command; the server-side `MenuRedeemSubcommand` consumes the token atomically and dispatches the stored `MenuAction`. ADR-047 layered an analogous `ChartSpecTokens` UUID registry on top of `OpenMap` so the map-rendering click payload would not embed the `ChartSpec` directly.

Two years of operating that design have exposed the following:

1. **Tokens add no security that the underlying permission check did not already enforce.** Every `dispatch*` helper in `MenuRedeemSubcommand` re-checks the relevant permission (`rtp.menu`, `rtp.menu.admin`, `rtp.config.view`, `rtp.config.edit`) before performing any state change. A "replayed" token is rejected by atomicity (already consumed) and by the player binding, but a *replayed concrete command* is rejected by the permission gate that already protects every administrative action. The token mechanism is redundant with the permission system, not additive to it.
2. **Tokens are user-hostile for operators.** The premise of a book-first config menu is that operators learn the underlying `/rtp ...` command grammar by clicking through it. Opaque tokens defeat this premise: a click event reads `/rtp menu token=AbCd1234...` regardless of what the click does, so the menu is no longer a teaching surface. Operators have asked repeatedly for the literal commands behind each row.
3. **Token TTL is a UX bug.** A book sitting open for longer than the configured TTL (default 5 minutes) silently invalidates every click. The configurable rejection message (`menu.expired`) is correct but the failure mode is unnecessary: every concrete command would still work.
4. **Tokens propagate as an architectural mistake.** ADR-047 cloned the pattern for chart specs. Future menu surfaces (network-mode dispatcher, multi-config editor) were already on track to add their own token registries. Each new registry duplicates the same cap-per-player, atomicity, lifecycle, and audit-log boilerplate.
5. **The 2026-05-15 amendment to ADR-035** already scoped tokens to single-backend (cross-server sharing was rejected). The remaining justification - "renderers cannot smuggle arbitrary commands" - is moot because (a) the renderer is first-party code in `rtp-bukkit`/`rtp-folia`/`rtp-fabric` (not addon-supplied) and (b) what the renderer "smuggles" is constrained to commands the player already has permission to run.

This decision retires both token registries in favor of concrete, non-expiring `/rtp menu ...` and `/rtp visualization ...` subcommands that self-document the action behind every click.

## Decision

1. Every `MenuAction` variant maps to a concrete subcommand under `/rtp` whose grammar is parsed by the standard `commands-api` `TreeCommand`. Permission gating on each leaf is the security boundary; there is no opaque indirection.

   | `MenuAction` variant | Concrete command |
   |---|---|
   | `RunRtpCommand(args)` | `/rtp <args...>` |
   | `OpenMenu(path)` | `/rtp menu open path=<dotted.path>` (empty path = root) |
   | `OpenParamPicker(parent, param)` | `/rtp menu picker path=<dotted.path> param=<name>` |
   | `ChangePage(n)` | `/rtp menu page n=<n>` |
   | `OpenAdminPanel()` | `/rtp menu admin` |
   | `OpenFrontPage()` | `/rtp menu front` |
   | `OpenVisualizations()` | `/rtp menu visualizations` |
   | `OpenConfigSelector()` | `/rtp menu config` |
   | `OpenConfigFile(file)` | `/rtp menu config file=<file>` |
   | `OpenConfigKey(file, param)` | `/rtp menu config file=<file> key=<param>` |
   | `OpenConfigSubParamPage(file, param, type)` | `/rtp menu config file=<file> key=<param> type=<type>` |
   | `OpenConfigSearchPrompt()` | `/rtp menu config search` |
   | `OpenConfigSearchResults(q, page)` | `/rtp menu config search query=<q> page=<n>` |
   | `StageConfigValue(file, k, v)` | `/rtp menu stage file=<file> key=<k> value=<v>` |
   | `UnstageConfigValue(file, k)` | `/rtp menu unstage file=<file> key=<k>` |
   | `ApplyStagedConfig(file)` | `/rtp menu apply file=<file>` |
   | `DiscardStagedConfig(file)` | `/rtp menu discard file=<file>` |
   | `OpenInfo(scope)` | `/rtp menu info scope=<global\|world:N\|region:N>` |
   | `SwitchInfoToText(scope)` | `/rtp menu info scope=... text=true` |
   | `OpenMultiConfigSelector(kind)` | `/rtp menu multi kind=<kind>` |
   | `OpenMultiConfigEntry(kind, name)` | `/rtp menu multi kind=<kind> entry=<name>` |
   | `MultiConfigMutate(kind, name, op)` | `/rtp menu multi kind=<kind> entry=<name> op=<add\|remove>` |
   | `OpenMap(regionName)` | `/rtp visualization x=<regionName>` |
   | `SuggestInput`, `PromptAnvilInput`, `OpenExternalUrl` | renderer-side only; no command leaf |

2. `MenuTokenRegistry` (`rtp-api`) and `LocalMenuTokenRegistry` (`rtp-core`) are deleted. `ChartSpecTokens` (`rtp-core`) is deleted. `MenuAction.OpenMap`'s `UUID chartSpecToken` field is replaced with a stable `String regionName`; ad-hoc charts that lacked a stable identifier are not surfaced from the menu (regression scope is map menu integration only, and only for non-region charts).

3. The `/rtp menu token=<token>` redeem grammar is removed. `menu.expired`, `menu.tokenCapExceeded`, and `menu.maxOutstandingTokensPerPlayer` keys are dropped. `menu.invalid` is retained because the concrete-command surface can still fail on a malformed path/file/key/region.

4. Permission gating remains exactly where it was: each `dispatch*` helper in `MenuRedeemSubcommand` continues to enforce its current permission node. The concrete-command leaves call the same helpers; no permission boundary moves.

5. Migration is staged across three sessions (see `docs/dev/scratch/CHECKLIST-concrete-menu-commands.md`):
   - **Stage 1 (this ADR's first landing):** concrete-command leaves are added alongside the existing token redeem path. The renderer still mints tokens. Both paths reach the same `dispatch*` helpers. The grammar is reachable end-to-end before any deletion.
   - **Stage 2:** the renderer switches to emitting concrete `runCommand` strings; `BookMenuRenderer`'s `MenuTokenRegistry` constructor dependency is dropped.
   - **Stage 3:** the token redeem branch, the registries, `ChartSpecTokens`, and the now-orphaned message keys are deleted. This ADR is promoted from Proposed to Accepted at the start of Stage 3. ADR-035 section 3 and section "Security boundary" and ADR-047's chart-spec-token sections are marked superseded by this ADR (with date) at the same point.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Keep the registry, make tokens permanent (drop TTL and single-use) | Preserves the opaque click surface that operators have complained about; does nothing to remove the duplication of cap/audit/lifecycle code that ADR-047 cloned. The registry's only remaining purpose - hiding the command from the click event - is the property we deliberately want to remove. |
| Keep `ChartSpecTokens` and only retire `MenuTokenRegistry` | Same pattern, same complaints, will drift back into duplication the moment a new menu surface adds its own opaque handle. Decision is to retire the *category*, not one instance. |
| Per-click HMAC signature instead of a stored token | Adds cryptographic infrastructure for no security benefit over permission gating. Discussed under ADR-035's 2026-05-15 amendment and rejected on the same grounds. |
| Keep tokens, expose the underlying command in the click tooltip | Half-measure: the click event still reads `token=<opaque>` in logs/audit, copy/paste of the visible command would not work because the parser still requires `token=...`. The teaching surface is the click event, not the tooltip. |

## Consequences

- **Positive:**
  - Operators can read the literal admin command from any click in any rendered book; the menu becomes a discovery surface for the command grammar (the original premise of ADR-035 section 1).
  - No more `menu.expired` UX failures from a book left open.
  - Net code reduction: `MenuTokenRegistry`, `LocalMenuTokenRegistry`, `ChartSpecTokens`, the cap/eviction logic, the token-cap message keys, and `MenuTokenRegistryTest` all disappear. Estimated 600-800 LoC net negative after Stage 3.
  - Future menu surfaces have no template for adding another opaque token registry; the pattern is the concrete-command grammar.
  - Map menu integration's `OpenMap(UUID)` is replaced by a self-documenting `/rtp visualization x=<regionName>`, which is also a usable CLI command for operators (not menu-only).

- **Negative / Trade-offs:**
  - The full text of a click's intended action becomes visible in the click event, in chat logs (if logged), and in screenshots. This is the *intent* (operators learn the grammar), but it does mean a screenshot of an admin menu reveals the menu's admin command grammar. Mitigation: permission gating already prevents the player without `rtp.config.edit` from executing the screenshotted command - that is the same posture the rest of the plugin's CLI surface already lives under.
  - Ad-hoc charts (non-region) cannot be reached from the menu after Stage 3 because they lack a stable name. Region maps - the only chart category the menu actually surfaces today - keep working. A future ADR can re-introduce ad-hoc charts via a separate stable-identifier mechanism if needed.
  - Stage 1 doubles the `MenuRedeemSubcommand` `TreeCommand` registration surface temporarily (concrete leaves + token leaf coexist). The cost is contained to one class and is removed in Stage 3.

## References

- [ADR-035](ADR-035-interactive-menus-book-first.md) - the original token-registry decision being superseded in part.
- [ADR-047](ADR-047-declarative-chart-composition-bridge.md) - the `ChartSpecTokens` clone of the pattern being deleted.
- [ADR-048](ADR-048-menu-builders-behind-server-accessor.md) - clarified menu builder ownership; unaffected by this decision (concrete commands still dispatch into the same builders).
- `docs/dev/scratch/CHECKLIST-concrete-menu-commands.md` - the staged execution plan.
- `rtp-core/src/main/java/io/github/dailystruggle/rtp/common/commands/menu/MenuRedeemSubcommand.java` - the host class for the new concrete leaves.
- REQ-RTP-S-004 (no silently discarded teleport failures), REQ-RTP-S-007 (configurable rejection messages), REQ-RTP-F-013 (user-facing strings configurable) - unchanged; concrete-command leaves continue to honor them.
