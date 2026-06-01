# rtp-fabric-ADR-012 - Menu renderer parity on Fabric (chat-first, book follow-up)

- **Status:** Accepted (2026-05-24)
- **Scope:** `rtp-fabric` (`rtp-fabric-common`, the per-version carriers, and `rtp-plugin/.../fabric/`)
- **Supersedes:** none. Implements the Fabric clause of [ADR-035](../../../../docs/adr/ADR-035-interactive-menus-book-first.md) and the menu-reflector contract of [ADR-044](../../../../docs/adr/ADR-044-command-tree-menu-reflector.md).
- **Related:** [ADR-035](../../../../docs/adr/ADR-035-interactive-menus-book-first.md) (Interactive Menus via Written Book - the project-wide menu ADR; pins Fabric's primary renderer to chat in the 2026-05-15 amendment), [ADR-044](../../../../docs/adr/ADR-044-command-tree-menu-reflector.md) (command-tree menu reflector / `MenuModel` contract), [ADR-048](../../../../docs/adr/ADR-048-menu-builders-behind-server-accessor.md) (`menuPermissionProbe` on `RTPServerAccessor`; subsumes section 2's per-call-site permission-probe wiring), [ADR-050](../../../../docs/adr/ADR-050-menus-stateless-commands.md) (deletes the menu token registry; menu clicks now carry literal `/rtp menu ...` commands), [rtp-fabric-ADR-009](rtp-fabric-ADR-009-obf-unobf-common-split.md) (obf/unobf carrier split; **does not apply** to the chat renderer in the shipped shape, see Amendment), [rtp-fabric-ADR-011](rtp-fabric-ADR-011-effective-permissions-enumeration.md) (`menuPermissionProbe` consumes the resolver from this ADR).

## Amendment (2026-05-31, book renderer un-defer)

Section 4 below deferred a Fabric written-book renderer. Per the maintainer's 2026-05-24 decision to un-defer it (MULTI_PLATFORM_PLAN Step I Session 3), the book renderer now ships for the 1.21+ obf carriers and section 4 no longer holds for those runtimes. Shipped shape:

- A platform-neutral `io.github.dailystruggle.rtp.fabric.menu.FabricBookMenuRenderer` (in `rtp-fabric-common`, no `net.minecraft.*` binding) translates the `MenuModel` into a fully-formatted `FabricBookSpec` (placeholders + colour codes resolved through `RTPServerAccessor.format`; click commands via the shared `MenuActionToCommand`) and dispatches it through a new `FabricVersionAdapter.openBookMenu(Object serverPlayer, FabricBookSpec)` SPI (default `false`).
- The per-carrier overrides (`v1_21_R1`, `v1_21_R5`, `v1_21_R11`) build a `WRITTEN_BOOK` `ItemStack` with a `WrittenBookContent` data component (one `Component` per page; fragments built by the existing `FabricLegacyText.parseInteractive(..., ClickKind.RUN)`), send a transient `ClientboundContainerSetSlotPacket` to the held hotbar slot, send `ClientboundOpenBookPacket(MAIN_HAND)`, then revert the slot. The server-side inventory is never mutated (this is the answer to section 4 blocker #2's "transient book without inventory placement" concern: a client-only `SetSlot` round-trip rather than a real inventory write).
- 1.20.x (no component `WrittenBookContent`) keeps the SPI default and stays on `ChatMenuRenderer`; the renderer also falls back to chat when the viewer is offline or the book dispatch throws (S-004 log-and-degrade). The chat renderer therefore remains the default for headless / accessibility / unsupported-carrier cases, as section 4 promised.
- Section 4 blocker #1 (per-version `WrittenBookContent` schema split) is handled by the existing per-version carrier pattern; each adapter compiles against its own MC mapping. Blocker #3 (parchment colour contrast) now *does* apply to the Fabric book renderer exactly as on Paper — `.junie/AGENTS.md` "Book Menu Color Contrast" — and is satisfied for free because the `MenuModel` producers' colour choices are shared with Paper.
- New test `FabricBookMenuRendererTest` (rtp-fabric-common) covers the pure `MenuModel` → `FabricBookSpec` conversion and the chat-fallback dispatch (no live server required).

**Update (2026-05-31, 26.x book support):** the deobf 26.x carriers (`v26_1_R1`, `v26_2_R1`) now also ship `openBookMenu`. Because the deobf MC line compiles against Mojang names that equal its runtime mappings, the per-carrier override is a typed port of the 1.21.11 implementation (same `WrittenBookContent` data component + transient `ClientboundContainerSetSlotPacket` -> `ClientboundOpenBookPacket(MAIN_HAND)` -> slot revert), with per-fragment hover/click delegated to the version-local `V26_*FabricLegacyText.parseInteractive(...)` (reflective `HoverEvent`/`ClickEvent` record-carrier probing). One 26.x ctor difference was handled: `WrittenBookContent`'s title is `Filterable<String>` (not `Filterable<Component>` as on 1.21.x). Only 1.20.x now stays on the chat renderer by default.

## Amendment (2026-05-24, on acceptance)

The original draft was written before [ADR-048](../../../../docs/adr/ADR-048-menu-builders-behind-server-accessor.md) (menu builders behind server accessor) and [ADR-050](../../../../docs/adr/ADR-050-menus-stateless-commands.md) (stateless concrete-command menus) landed. The shipped implementation therefore differs from sections 1 and 2 below in three editorial ways. The chat-first decision (section 1), the chat-prompt callback substitute (section 3), and the book-renderer deferral (section 4) stand as written.

1. **No `MenuRendererRegistry`; no carrier split for the chat renderer.** ADR-050 deleted the token system entirely, so the renderer no longer needs to mint per-fragment tokens. The shipped renderer is a single `io.github.dailystruggle.rtp.common.commands.menu.ChatMenuRenderer` class in `rtp-core` whose only platform coupling is one new `RTPServerAccessor.sendMessageWithRunCommand(...)` SPI method. Because that SPI dispatches in `rtp-api` types, the renderer itself has no `net.minecraft.*` binding; the carrier split applies only to `FabricServerAccessor.sendMessageWithRunCommand` (one method, routed through the existing `FabricLegacyText.parseInteractive(..., ClickKind)` path). The `ChatMenuRendererCommon` / `ChatMenuRendererObf` / `ChatMenuRendererUnobf` triple proposed in section 1 collapses to one class.
2. **`menuPermissionProbe` is supplied by `RTPServerAccessor`, not constructed in `RTPCmdFabricRoot`.** ADR-048 Phase B added `RTPServerAccessor.menuPermissionProbe(UUID)` as a default-method SPI; `FabricServerAccessor` (lines 1933-2034) overrides it to delegate to `FabricEffectivePermissionsResolver` per [rtp-fabric-ADR-011](rtp-fabric-ADR-011-effective-permissions-enumeration.md). `RTPCmdFabricRoot`'s wiring (section 2) is therefore a 5-line block that mirrors `RTPCmdBukkit:215-230` and consumes the SPI override; no Fabric-specific permission code is added.
3. **No `LocalMenuTokenRegistry` construction.** ADR-050 removed the registry; `MenuPlatformBindings` (post-ADR-050) carries only `(permissionProbe, MenuRenderer, AnvilInputOpener)` and `MenuWiringSupport.attachTo` installs all menu leaves with concrete-command click targets.

The shipped artifacts are: `rtp-core/.../commands/menu/ChatMenuRenderer.java`, `rtp-core/.../commands/menu/MenuActionToCommand.java` (shared `MenuAction -> /rtp menu ...` helper, used by both `BookMenuRenderer` and `ChatMenuRenderer`), `rtp-fabric-common/.../menu/FabricChatPromptCallback.java` (chat-prompt substitute per section 3), the `sendMessageWithRunCommand` SPI addition on `RTPServerAccessor` + overrides on `AbstractServerAccessor` (Bukkit), `AbstractFoliaServerAccessor` (Folia), and `FabricServerAccessor` (Fabric), and the wiring block in `RTPCmdFabricRoot:218-249`. Section 4 (book-renderer deferral) is unchanged and remains valid for this phase; the Path B serialization in [MULTI_PLATFORM_PLAN.md](../../../../docs/dev/MULTI_PLATFORM_PLAN.md) Step I sequences Session 3 (book renderer for 1.21+ Fabric carriers) after this.

## Context

The interactive-menu rollout ([ADR-035](../../../../docs/adr/ADR-035-interactive-menus-book-first.md), [ADR-044](../../../../docs/adr/ADR-044-command-tree-menu-reflector.md)) has shipped its platform-agnostic layer in `rtp-api` (the `MenuModel` / `MenuFragment` / `MenuAction` types and the `MenuRendererRegistry` SPI) and its concretes in `rtp-core` (`CommandTreeMenuBuilder`, `LocalMenuTokenRegistry`, `MenuRedeemSubcommand`, `FrontPageBuilder`, `AdminPanelBuilder`, and the config-view / param-picker / shape-vert / list-editor builders). Paper has `rtp-paper-common/.../menu/BookMenuRenderer.java` installed at boot and `RTPCmdBukkit` wires `LocalMenuTokenRegistry` + `menuPermissionProbe` + `MenuRedeemSubcommand` (lines 213, 239, 246, 259, 303 in the current tree). Paper menus are confirmed working end-to-end as of 2026-05-22 (user-reported, this cycle).

On Fabric none of this is wired. The symptoms today:

- `/rtp menu` (the no-arg curated front page from `FrontPageBuilder` / Stage B), `/rtp config <file>` (the config-view book pages from `CommandTreeMenuBuilder.buildConfigSelector` / `buildConfigFile`), the param-picker page, the shape/vert two-step picker, the list-editor page, and the staging-cart prompt are all no-ops on Fabric. They build a `MenuModel` and then have no `MenuRenderer` to dispatch to.
- `RTPCmdFabricRoot` does not construct a `LocalMenuTokenRegistry`, install a `menuPermissionProbe`, attach `MenuRedeemSubcommand`, or register anything against `MenuRendererRegistry`. Tokens never get minted, so even the `runCommand("/rtp menu:<token>")` round-trip is non-functional.
- `AdminPanelBuilder` has no entry point on Fabric (no `/rtp adminpanel` analogue).

The 2026-05-15 amendment to ADR-035 explicitly assigns Fabric's primary renderer to a chat (component-tree) renderer and defers a Fabric `BookMenuRenderer` to a follow-up phase via `OpenWrittenBookS2CPacket`. That decision was made *before* Paper menus had been exercised end-to-end and before the Fabric obf/unobf common split ([rtp-fabric-ADR-009](rtp-fabric-ADR-009-obf-unobf-common-split.md)) was in its current shape. This ADR ratifies the chat-first decision in light of those two things and pins the concrete shape of the implementation (carrier split, action dispatch, permission probe, prompt-anvil substitute).

There is a fourth menu interaction the project-wide ADRs do not specifically address for Fabric: the `PromptAnvilInput` action used by the param-picker and list-editor to ask the player to "type a value here". On Paper this opens a vanilla anvil GUI with the current value as the input slot's name. Fabric has no equivalent first-class "free-text input" GUI - the closest options are a sign-edit packet (`OpenSignEditorS2CPacket`, finicky on cross-version), a chat-prompt callback (intercepts the next chat message), or a book input page. The two Bukkit-side anvil dependencies (the `AnvilGUI` library and the per-version reflection backends) are unsuitable on Fabric for both licensing and binding reasons.

## Decision

Implement two coordinated changes.

### 1. Adopt `ChatMenuRenderer` as the Fabric primary renderer, in the obf/unobf split layout

Introduce a `ChatMenuRenderer` that lives on both sides of the carrier split per [rtp-fabric-ADR-009](rtp-fabric-ADR-009-obf-unobf-common-split.md):

- The platform-neutral menu-to-component translation logic (walk `MenuModel.pages()`, resolve each `MenuFragment` to a styled run with a `ClickEvent.runCommand("/rtp menu:<token>")` and optional `HoverEvent.showText`, glue page navigation onto "[prev] / page N of M / [next]" lines) lives in `rtp-fabric-common` under `io.github.dailystruggle.rtp.fabric.menu.ChatMenuRendererCommon`. It does not import `net.minecraft.*` directly; it emits a small internal `ComponentSpec` value type (text + color code + click action + hover text) that the carrier-side renderer consumes.
- The obf carrier (`rtp-fabric-common`, used by 1.20.x and 1.21.x runtimes) implements `ChatMenuRendererObf` and binds `ComponentSpec` to the intermediary `net.minecraft.class_2561` / `class_2583` / `class_2558` / `class_2568` types.
- The unobf carrier (`rtp-fabric-common-unobf`, used by deobf 1.26.x runtimes) implements `ChatMenuRendererUnobf` and binds `ComponentSpec` to the deobf `net.minecraft.network.chat.Component` / `Style` / `ClickEvent` / `HoverEvent` types.
- Per-version submodules contribute nothing renderer-specific. The carrier split already exists for `FabricEffectsHandler` / `FabricRTPPlayer`; the renderer follows the same pattern and is dispatched through `FabricVersionAdapter`.
- The renderer is installed at `RTPFabricMod.onInitialize()` via `MenuRendererRegistry.register("chat", new ChatMenuRendererCarrier(versionAdapter))`. The carrier delegate selects obf-vs-unobf at install time, identically to how `FabricEffectsHandler` is selected.

Click round-trip uses the existing `commands-api` Brigadier bridge: a `MenuFragment` whose action is `MenuAction.RunRtpCommand("/rtp <args>")` translates to a `ClickEvent.runCommand("/rtp menu:<token>")` where `<token>` is minted by `LocalMenuTokenRegistry` at render time. The token is redeemed by `MenuRedeemSubcommand` on click, which re-dispatches through the same command tree the player would have typed. No bespoke Fabric click-event handling is added.

### 2. Wire the menu surface in `RTPCmdFabricRoot`, mirroring `RTPCmdBukkit`

`RTPCmdFabricRoot` is modified to:

- Construct a `LocalMenuTokenRegistry` instance (per-server, not per-player) and store it on the root command object.
- Install a `menuPermissionProbe` that consumes [rtp-fabric-ADR-011](rtp-fabric-ADR-011-effective-permissions-enumeration.md)'s `FabricEffectivePermissionsResolver` for visibility filtering (callers see only command tree rows their effective permissions allow).
- Attach `MenuRedeemSubcommand` to the root tree under `menu`, with the token-registry handle bound at construction.
- Wire `FrontPageBuilder` for the no-arg `/rtp` and `/rtp menu` paths.
- Wire `AdminPanelBuilder` for `/rtp adminpanel` (no existing entry point on Fabric).
- Wire `CommandTreeMenuBuilder` for `/rtp config <file>`, the param-picker, the shape/vert two-step picker, and the list-editor / staging-cart flows. These are reused unchanged from `rtp-core`.

The wiring is byte-equivalent in structure to `RTPCmdBukkit` lines 213, 239, 246, 259, 303; only the renderer registration and the permission probe change shape.

### 3. Defer `PromptAnvilInput` to a chat-prompt callback substitute

Fabric does not get an anvil-input GUI in this phase. The `MenuAction.PromptAnvilInput(label, defaultValue, then)` action is translated by `ChatMenuRenderer` to:

1. A line in the rendered chat menu reading `"Type the new value in chat. Default: <defaultValue>. Type cancel to abort."` (locale-keyed).
2. A registration with a new `FabricChatPromptCallback` keyed by player UUID, with a TTL of 30 seconds, draining the next chat message the player sends. The callback resolves to the same `then` continuation that the Paper anvil GUI hands back.
3. A reminder line on every page-navigation refresh while the callback is pending, so the player does not lose context if they paginate.

A real `OpenSignEditorS2CPacket` or `OpenWrittenBookS2CPacket` input modal is **deferred** to a follow-up ADR. The chat-prompt substitute is acceptable for the param-picker / list-editor use cases and avoids a binding decision against an inherently flaky packet surface in this cycle.

### 4. Defer the `BookMenuRenderer` follow-up

A Fabric `BookMenuRenderer` via `OpenWrittenBookS2CPacket` remains a tracked follow-up. It is **not** implemented in this ADR. Three concrete blockers keep it deferred:

1. `WrittenBookContent` schema differs materially between 1.20.x (pre-component data) and 1.21.x (component-data); a per-carrier book layer doubles the carrier surface area for a marginal UX win over the chat renderer.
2. `Player#openBook` has no 1:1 packet path that opens a book modal **without** placing an item in the player's inventory. The closest path is `OpenWrittenBookS2CPacket`, which carries an inventory-slot index; the server-authoritative "transient book" trick used on Paper does not directly translate, and reproducing it requires per-version slot bookkeeping plus a follow-up `SetSlotS2CPacket` revert that is fragile across the 1.21 slot rework.
3. The *Book Menu Color Contrast* section of `.junie/AGENTS.md` (yellow/white wash on parchment) does **not** apply to the chat renderer (the chat-stream background is configurable / dark by default), so deferring the book renderer is a net UX-quality improvement on Fabric and an operator-config simplification.

When the book renderer ships, this ADR will be amended (not superseded); the chat renderer remains the default for headless / accessibility cases.

## Consequences

### Positive

- Closes the Step I gap in [MULTI_PLATFORM_PLAN.md](../../../../docs/dev/MULTI_PLATFORM_PLAN.md) with a minimal renderer footprint (no new dependency, no new per-version submodule).
- The chat renderer reuses the existing carrier-split SPI introduced in [rtp-fabric-ADR-009](rtp-fabric-ADR-009-obf-unobf-common-split.md); no new architectural shape.
- Permission-filtered visibility on Fabric reuses the resolver from [rtp-fabric-ADR-011](rtp-fabric-ADR-011-effective-permissions-enumeration.md); no second authorization path.
- The `Book` color-contrast constraints in `.junie/AGENTS.md` become irrelevant on Fabric, freeing the renderer to use the full color palette.
- A no-op-on-Fabric framework gains a working renderer without committing to the binding-fragile packet surface required for the book modal.

### Negative

- The chat renderer is a "menu scrolls with chat" experience by design (per ADR-035), not a modal screen. Long menus paginate but cannot suppress the player's chat stream while open. This is the documented trade-off accepted project-wide in ADR-035.
- `PromptAnvilInput` actions become a "type in chat" callback rather than a modal input. Accessibility is comparable, polish is worse. Tracked for the follow-up book renderer.
- Two carrier implementations (obf + unobf) must be kept in sync against `ComponentSpec`. The pattern is identical to `FabricEffectsHandler` / `FabricRTPPlayer` and the existing tests for those serve as a template.

### Limitations

1. **No modal input.** Players cannot edit a value in a dedicated input widget on Fabric in this phase. The chat-prompt substitute is the documented workaround.
2. **No book renderer.** A second pass is required for the book modal. Tracked as a Phase 3 follow-up; no ADR slot reserved yet.
3. **Cross-server menus reuse the live command tree.** Per the 2026-05-15 ADR-035 amendment, menu content for cross-server flows is the proxy-routed live tree, not a token-shared menu store. No Fabric-specific change is required for that path.
4. **`ChatMenuRenderer` cannot suppress chat-channel plugins on Fabric.** Same constraint as Paper, called out for symmetry; no Fabric chat-channel plugins are catalog-tracked today.

## Implementation plan (post-acceptance)

The actual implementation is a separate change after this ADR is accepted. Sketch:

1. New `rtp-fabric-common/.../menu/ChatMenuRendererCommon.java` (platform-neutral translation) and `ChatMenuRendererObf.java` (obf carrier binding).
2. New `rtp-fabric-common-unobf/.../menu/ChatMenuRendererUnobf.java` (deobf carrier binding).
3. New `rtp-fabric-common/.../menu/FabricChatPromptCallback.java` (TTL-bounded per-UUID chat-message callback registry; drained by a `ServerMessageEvents.CHAT_MESSAGE` listener on the unobf carrier, by the intermediary equivalent on the obf carrier).
4. Modify `RTPFabricMod.onInitialize()` to register the chat renderer against `MenuRendererRegistry` after the version adapter selects obf vs unobf.
5. Modify `RTPCmdFabricRoot` to construct `LocalMenuTokenRegistry`, install the permission probe (delegating to `FabricEffectivePermissionsResolver`), attach `MenuRedeemSubcommand`, and wire `FrontPageBuilder` / `AdminPanelBuilder` / `CommandTreeMenuBuilder` per the Paper template.
6. New `FabricChatMenuRendererTest` analogous to `BookMenuRendererTest`: emits a known `MenuModel`, captures the rendered `ComponentSpec` tree, asserts shape (page count, click actions resolve to mintable tokens, hover text present, permission-filtered rows omitted when probe returns false).
7. Renderer integration test parity rows added to [TRACEABILITY.md](../../../../docs/dev/TRACEABILITY.md) once the test class lands.
8. Stale-comment cleanup on `RTPCmdFabricRoot` (Step F deferred / always-true predicate notes) folded into the wiring change.
9. CHANGELOG entry under `[3.0.0-beta.4] - Unreleased`.

## Alternatives Considered

| Alternative | Why rejected |
|---|---|
| Implement `BookMenuRenderer` via `OpenWrittenBookS2CPacket` first | Per-version `WrittenBookContent` schema split + transient-inventory-slot bookkeeping doubles carrier surface area for a renderer that ADR-035 already designates as a follow-up. Chat renderer ships first, book follows. |
| Anvil GUI input via `AnvilGUI` library / per-version reflection | Library is Bukkit-specific; reflection backends bind against Bukkit NMS types absent on Fabric. Chat-prompt callback is the correct substitute. |
| Sign-edit packet input | `OpenSignEditorS2CPacket` requires a real sign block in the world or a fake block-update sequence that is fragile across 1.21 block-state rework. Not worth the binding cost in this phase. |
| Inventory-backed menu renderer | Explicitly rejected for the whole project in ADR-035 (`InventoryClickEvent` desync exploit class, no Fabric analogue, S-005 / `MemoryTracker` conflicts). |
| Defer the entire menu surface on Fabric until book renderer lands | Leaves `/rtp menu`, `/rtp config`, `/rtp adminpanel`, param-picker, shape/vert picker, list-editor, and staging cart as no-ops indefinitely. Unacceptable for a "first-class platform" classification per [rtp-fabric-ADR-002](rtp-fabric-ADR-002-platform-in-scope.md). |

## References

- [ADR-035 - Interactive Menus via Written Book](../../../../docs/adr/ADR-035-interactive-menus-book-first.md) (2026-05-15 amendment pins Fabric to chat renderer first)
- [ADR-044 - Command-Tree Menu Reflector](../../../../docs/adr/ADR-044-command-tree-menu-reflector.md)
- [rtp-fabric-ADR-009 - Obf/unobf common split](rtp-fabric-ADR-009-obf-unobf-common-split.md)
- [rtp-fabric-ADR-011 - Effective-permission enumeration on Fabric](rtp-fabric-ADR-011-effective-permissions-enumeration.md)
- [MULTI_PLATFORM_PLAN.md Step I](../../../../docs/dev/MULTI_PLATFORM_PLAN.md)
- [`.junie/AGENTS.md` Book Menu Color Contrast](../../../../.junie/AGENTS.md)
