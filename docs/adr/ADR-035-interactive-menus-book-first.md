# ADR-035 — Interactive Menus via Written Book (Book-First, Chat Fallback)

**Status:** Accepted
**Date:** 2026-05-13 (Proposed) / 2026-05-15 (Accepted, amended)
**Target release:** `3.0.0-beta.3`

> **Amendment 2026-05-15.** Cross-server menu scope (the `SharedMenuTokenRegistry`, SQL/Redis token-store drivers, and the `menu.tokenStore` config block) is **removed from this ADR**. The single source of truth for cross-server menu content is the live `commands-api` command tree, which RTP updates at runtime from database / network-state changes; menus re-reflect that tree on every open. Cross-server *dispatch* of the resulting command is the proxy layer's responsibility ([MULTI_SERVER_PLAN.md](../dev/MULTI_SERVER_PLAN.md)), not the menu's. Tokens therefore remain **local-only** (`LocalMenuTokenRegistry`); they protect the click round-trip against replay, nothing more. The first concrete consumer is **`/rtp config`** (per [`CONFIG_COMMAND_SPEC.md §2.4`](../dev/CONFIG_COMMAND_SPEC.md)), not the no-args region picker; the region picker is deferred to a follow-up consumer. Renderer scope in this release is Paper + Folia only; Spigot per-version book branches and the Fabric chat fallback are deferred. The command-tree → `MenuModel` reflection layer is specified in [ADR-044](ADR-044-command-tree-menu-reflector.md).

## Context

RTP exposes most player-facing affordances through commands (`/rtp`, `/rtp biomes`, `/rtpadmin …`). Discoverability is poor: players must know region names, biome names, and subcommand argument grammar, and admins must read documentation before they can author or audit a region. The natural fix is an interactive menu.

Inventory-backed (chest-GUI) menus are the standard idiom in the Bukkit ecosystem but are unsuitable for this project for three reasons:

1. **Inventory desync as an exploit vector.** `InventoryClickEvent` semantics across cursor item, shift-click, number-key swap, drag, offhand swap, creative middle-click, bundles, and the 1.21 inventory rework have been a recurring source of duplication and impersonation bugs in third-party plugins. The class of bug is well-known and the safe-handling rules have edge cases.
2. **Cross-platform cost.** Bukkit's inventory event model has no native analogue in `rtp-fabric`; any inventory menu would require a per-platform implementation and would push platform-presentation logic close to `rtp-core` if not carefully gated by the architecture boundary rules.
3. **Conflict with the project's safety posture.** S-005 (no chunk loading on the main thread) and the `MemoryTracker` lifecycle (chunk-ticket release on every exit path) are easy to violate when a chest GUI's click handler kicks off teleport work directly off the inventory event thread. The current command pipeline already routes through validated entry points (`commands-api`, Brigadier bridge per [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md)) and has audit logging guarantees under S-004 / REQ-RTP-S-007. A menu mechanism that reuses the command pipeline inherits those guarantees for free.

Two non-inventory mechanisms can carry an interactive menu in Minecraft using only server-authoritative state:

- **`tellraw` chat components** — `ClickEvent.runCommand` / `suggestCommand` / `changePage` / `copyToClipboard` / `openUrl` plus `HoverEvent.showText`. Click events round-trip through the player's command pipeline.
- **Written book** — `Player#openBook(ItemStack)` (Spigot/Paper/Folia since 1.14; Adventure `Book` on platforms that ship Adventure) or `OpenWrittenBookS2CPacket` with a transient stack (Fabric). The book opens in a modal screen built from a server-supplied `ItemStack` that is **not** placed in the inventory; pages accept the same `ClickEvent` / `HoverEvent` primitives as `tellraw`.

A `tellraw`-only design forces the menu to interleave with the player's live chat stream. The only mitigations are (a) suppressing outgoing chat to the player and buffering it for replay, or (b) suppressing inbound chat from the player and routing typed input to the menu. Mitigation (a) is bounded but collides with chat-channel plugins (Chatty, CMI, DeluxeChat, staff-chat) that hold their own opinions about delivery; mitigation (b) is unworkable because it removes the player's only escape hatch and contends with every chat plugin's intent. A written book sidesteps both: the modal screen owns its own viewport, and chat continues unaffected behind it.

## Decision

Adopt **written book as the primary menu medium**, with `tellraw` as a fallback for short single-shot prompts and as the Fabric path until the book packet implementation lands. Define a platform-neutral menu model in `rtp-api`, render it through one of two adapter-layer renderers, and dispatch all click actions through the existing command pipeline using opaque single-use tokens. **Do not** ship a chat-pause / chat-buffer subsystem.

### Module placement (Architecture Boundaries)

- **`rtp-api`** — `MenuModel`, `MenuPage`, `MenuAction`, `MenuRenderer` interface, and the `MenuTokenRegistry` abstraction. No platform imports; no Adventure import; no `org.bukkit.*`. Public per [ADR-011](ADR-011-rtp-api-separate-module.md); throws `IllegalStateException` (not null / no-op) when called before core load per S-006.
- **`rtp-core`** — `MenuTokenRegistry` default implementation (`ConcurrentHashMap<UUID, MenuSession>` with TTL eviction), action validation, and the `rtp menu:<token>` internal subcommand wired through `commands-api`. No rendering. The internal subcommand is registered with `commands-api` exactly like any other RTP command and therefore routes through the existing Brigadier bridge on platforms that have one ([commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md)).
- **`rtp-paper` / `rtp-folia`** — `BookMenuRenderer` via Adventure `Book` (preferred) and the platform `Player#openBook` fallback. Adventure `Book` is already available on Paper and Folia and is the cleanest representation; no new dependency.
- **`rtp-spigot`** — `BookMenuRenderer` via `Player#openBook(ItemStack)` constructed with the version-appropriate writable-book payload. Per-version branches live under existing `rtp-spigot-v*` subprojects ([ADR-010](ADR-010-versioned-platform-adapter-submodules.md)); the 1.20.5 / 1.21 component / data-component shift is handled there, not in core. `ChatMenuRenderer` via `net.md_5.bungee.api.chat.*` for the legacy fallback path.
- **`rtp-fabric`** — `ChatMenuRenderer` via Mojang's native `Component` API in the deobf carrier and through the intermediary surface in the obf carrier ([rtp-fabric-ADR-009](../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md)). `BookMenuRenderer` follows in a later phase via `OpenWrittenBookS2CPacket`; explicitly deferred so this ADR's scope does not block on Fabric packet wiring.
- **`rtp-plugin`** — registers the renderer chosen by config. `menu.renderer` is an **ordered preference list** of renderer ids (e.g. `[book, chat]`); the framework instantiates the first entry whose adapter is registered and falls back to the next entry on instantiation or `render` exception. There is no `auto` value: every renderer will be implemented on every supported platform before release, so the list expresses operator preference, not capability detection. No business logic in `rtp-plugin`.

The "no platform logic in `rtp-core` / `rtp-api`" boundary (`AGENTS.md` → Architecture Boundaries) is preserved: the menu model is platform-free, only the renderers are platform-aware, and the click round-trip uses the command pipeline that already crosses the boundary.

### Menu model (`rtp-api`)

```
MenuModel        { title: Component-or-PlainText, pages: List<MenuPage> }
MenuPage         { lines: List<MenuLine> }
MenuLine         { fragments: List<MenuFragment> }
MenuFragment     { text, hover?: PlainText, action?: MenuAction }
MenuAction       { sealed: RunRtpCommand(args) | ChangePage(int) | SuggestInput(prefix) | OpenExternalUrl(uri) }
```

`MenuFragment#text` is plain text plus a small set of pre-validated color codes drawn from the existing locale system; this avoids leaking Adventure types onto the `rtp-api` surface and lets `ChatMenuRenderer` and `BookMenuRenderer` translate to their respective representations. `MenuAction.RunRtpCommand` carries the **caller's** intended `/rtp …` arguments — never a raw command string — so the renderer cannot smuggle in unrelated commands and the token registry can validate the action against the player's permissions before dispatch.

### Click handling and token registry (`rtp-core`)

1. When a renderer materializes a `MenuFragment` whose action is `RunRtpCommand`, it asks `MenuTokenRegistry#mint(playerUuid, action, ttl)` for an opaque token. The registry stores `(playerUuid, action, expiresAt, consumed=false, originBackendId)` and returns a short identifier (e.g. base32 of 96 bits).
2. The rendered click event is **always** `runCommand("/rtp menu:<token>")`. Renderers may not emit a click that runs anything else as a player command.
3. The `rtp menu:<token>` subcommand (`MenuRedeemSubcommand`, registered with `commands-api`) looks up the token, verifies *all* of: token exists, not expired, not previously consumed, `senderUuid == storedPlayerUuid`. On any failure the command rejects with the configurable `messages.yml → menu.invalid` (per REQ-RTP-F-013 and the existing REQ-RTP-S-007 / S-004 patterns); on success it atomically marks the token consumed (compare-and-set against the backing store) and dispatches the stored `MenuAction` against the live command pipeline as if the player had typed the equivalent `/rtp …` invocation. The atomic consume step is the same on the local (in-memory) registry and the shared-store registry (see *Cross-server menus*).
4. Tokens TTL out (default 60 s, config key `menu.tokenTtlSeconds`) and are swept on a periodic async task. The registry is bounded per player (default 256 outstanding tokens; `menu.maxOutstandingTokensPerPlayer`) and on overflow evicts oldest-first to bound memory. The same bound limits the per-menu fragment count a renderer can mint.
5. The redeem path obeys S-005: it does not load chunks. It enqueues through the existing teleport pipeline (`TeleportPipelineTask`, tracked by `MemoryTracker`) exactly like a direct `/rtp` invocation. No new release-on-failure paths are introduced.

### Cross-server menus (out of scope; see Amendment)

Per the 2026-05-15 amendment, cross-server menu content is delivered by the live `commands-api` command tree being updated at runtime from network-state changes; the menu re-reflects the tree on every open. No `SharedMenuTokenRegistry` ships with this ADR. Tokens are local-only: they protect the click round-trip against replay (single-use, TTL-bound, UUID-bound) and nothing more. Cross-server *dispatch* of the command produced by a redeem is the proxy layer's concern ([MULTI_SERVER_PLAN.md](../dev/MULTI_SERVER_PLAN.md)).

### Renderers (adapter layer)

- **`BookMenuRenderer`** — translates `MenuModel` to either Adventure `Book` (Paper/Folia/modern Spigot via paper-api shading where present) or a transient `ItemStack` of `WRITTEN_BOOK` with the version-correct pages payload (Spigot legacy / older Paper). Each `MenuFragment` becomes a styled run with an attached `ClickEvent.runCommand("/rtp menu:<token>")` and optional `HoverEvent.showText`. Pagination is `ClickEvent.changePage`. Multi-step menus re-open the book from the redeem-side action handler; the book does not attempt to mutate itself in place.
- **`ChatMenuRenderer`** — translates `MenuModel` to a `tellraw`-equivalent component tree on the active platform (BungeeCord chat-api on Spigot, Adventure on Paper / Folia, native `Component` on Fabric). Pagination collapses to "next page" / "prev page" lines that re-render. No chat-stream interception is performed; long menus simply scroll, which is acceptable for the fallback role.

### Concrete first consumer

Per the 2026-05-15 amendment, the **only** consumer that ships with this ADR is **`/rtp menu` → `/rtp config` browser**, driven by the command-tree reflector specified in [ADR-044](ADR-044-command-tree-menu-reflector.md):

- `/rtp menu` opens a `MenuModel` reflected from the root `/rtp` `commands-api` node, filtered to subcommands and parameters the player has permission for. `/rtp menu config` opens directly at the `config` subtree.
- Each subcommand fragment carries a `MenuAction.RunRtpCommand` for the nested `/rtp menu …` path; each leaf parameter fragment carries `MenuAction.SuggestInput` with the corresponding `/rtp config <file> <key>:` prefix (matching [CONFIG_COMMAND_SPEC §2.4](../dev/CONFIG_COMMAND_SPEC.md)).
- Hover text for a *parameter* fragment is the parameter's YAML block comment, read from the in-memory `RtpYamlSection#getComment` slot (block-comment preservation guaranteed by [ADR-042](ADR-042-yaml-comment-preservation-block-only.md)), with a fallback to declared type + bounds when the comment slot is empty. Hover text for a *command* fragment is the node's `CommandsAPICommand#description()` (resolved from `messages.yml`).
- All chrome strings come from `messages.yml` under a new `menu:` section (`menu.title`, `menu.empty`, `menu.invalid`, `menu.expired`, `menu.unknownPlayer`, `menu.hoverFallback.type`, `menu.hoverFallback.bounds`). REQ-RTP-F-013 applies to every one of them.

The `/rtp` no-args region picker, `/rtp biomes`, and any `/rtpadmin` wizard refactor are explicitly **deferred to follow-up ADRs** so this change can land within the beta.3 window without coupling to player-UX or admin-UX work. The expected migration path is: each follow-up consumer plugs into the same reflector (ADR-044) — no per-consumer renderer or registry work is required.

### Out of scope (this ADR)

- Chat suppression / replay buffering. Discarded: the book primary obviates it, and the `ChatMenuRenderer` fallback is documented as a "menu scrolls with chat" experience rather than a "chat is muted" experience.
- Inventory-backed menus. Not pursued in beta.4 for the reasons in *Context* (inventory desync as an exploit vector, no Fabric analogue, conflict with S-005 / `MemoryTracker` discipline). Explicitly held open as a **future follow-up**: if a design surfaces that neutralizes the `InventoryClickEvent` desync class (e.g. a fully server-authoritative virtual-inventory wrapper that mediates every click variant — cursor, shift, number-key swap, drag, offhand swap, creative middle-click, bundles, the 1.21 rework — without exposing the underlying `Inventory` to mutation, and that routes all redeems through the same `MenuTokenRegistry` + `commands-api` path as the book/chat renderers so REQ-RTP-S-004 / S-005 / S-007 are inherited rather than re-implemented), a successor ADR may add an `InventoryMenuRenderer` alongside the existing renderers. Until such a design exists and passes D-005 review, inventory GUIs remain unsupported. A Fabric-side equivalent is also required before the renderer can ship, to preserve the cross-platform parity rule in *Decision*.
- `/rtpadmin` setup wizards. Acknowledged as the highest-value future application; deferred so beta.4 ships the primitive without the full UX redesign attached.
- A `MenuRenderer` exposed to addons. The interface is **internal-public** (lives in `rtp-api` for layering reasons only); addon-facing menu rendering is a later, deliberate API decision after the in-tree consumer settles.
- Cross-server token storage. Per the 2026-05-15 amendment, no shared token store ships with this ADR; the command-tree reflection model makes it unnecessary.
- Renderers beyond Paper / Folia in this release. Per the 2026-05-15 amendment, the Spigot per-version `BookMenuRenderer` branches and the Fabric `ChatMenuRenderer` are deferred to follow-up work within the beta.3 cycle once the design is exercised end-to-end on Paper / Folia.

### Security boundary

- Tokens are unguessable (≥ 96 bits of entropy), single-use, UUID-bound, and TTL'd. A leaked token from a screenshot or log is useful only to the original player and only until the TTL elapses or the token is consumed.
- `MenuAction` is a sealed type, not a string; renderers cannot encode arbitrary commands into a click.
- The redeem path runs the stored action through the same permission, cooldown, and validation paths a direct `/rtp` invocation would hit. No command privilege is granted by virtue of going through the menu.
- All redeem failures (expired, foreign, unknown, consumed) log through `RTP.log(Level.WARNING, …)` per the S-004 auditing contract; failure messages to the player are configurable per REQ-RTP-F-013 / REQ-RTP-S-007.

### External-hook surface

Adventure usage on Paper / Folia is unaffected by other plugins. The chat fallback emits ordinary `tellraw`-equivalent components; chat-channel plugins see and route them as system messages, which is their existing behavior for plugin-emitted components. No new entry in [EXTERNAL_HOOKS.md](../dev/EXTERNAL_HOOKS.md) is required by this ADR; if a downstream coexistence issue surfaces during beta.4, document it there per [ADR-026](ADR-026-external-hook-api-surface.md) and the catalog rule in `AGENTS.md`.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| Inventory-backed chest GUI | Inventory desync (`InventoryClickEvent` semantics across cursor / shift / number-key / drag / offhand / creative middle-click / bundles / 1.21 rework) is a recurring exploit class. Bukkit-only — no Fabric analogue — so the adapter layer would balloon and platform-presentation logic would creep toward `rtp-core`. The safety guarantees the command pipeline provides (S-004 auditing, S-005 main-thread discipline, REQ-RTP-S-007 messages) would have to be reproduced in an inventory event handler. |
| `tellraw` primary + chat suppression / replay buffer | The buffering subsystem must hard-cap (memory DoS otherwise), TTL the menu, replay in order, and coexist with chat-channel plugins (Chatty, CMI, DeluxeChat, staff-chat). Coexistence is per-plugin and not solvable from RTP's side. The book renderer makes the entire subsystem unnecessary. |
| `tellraw` primary, suppress inbound chat from player and route as menu input | Removes the player's escape hatch when the menu glitches and conflicts with every chat plugin's intent on the server. `ClickEvent.suggestCommand` covers the same UX (pre-fill a slash command on T) with zero chat-channel hijack. |
| Anvil-text-input GUI (rename-an-item trick) | Solves a different problem (free-form text input), suffers from the same inventory-event class of bugs that drove this ADR, and still requires a fallback medium for the listing/picking step. |
| Sign-edit GUI for free-form input | Per-platform, only available for the writer-side `SignChangeEvent` flow which is in turn ill-suited to ephemeral menu input. Same inventory-adjacent risks. |
| Web UI / out-of-game admin panel | Different audience and different deployment story. Not a replacement for an in-game player picker. Considered for the eventual `/rtpadmin` wizards line of work but deferred. |
| Expose a public `MenuRenderer` SPI to addons in this ADR | Premature: the interface needs to be exercised by an in-tree consumer first. Locking the SPI before then risks a breaking revision against `rtp-api` consumers ([ADR-011](ADR-011-rtp-api-separate-module.md)). |
| Cross-server menus via a shared token store (SQL / Redis `SharedMenuTokenRegistry`) | Rejected in the 2026-05-15 amendment. The live `commands-api` command tree — updated at runtime from network-state changes — is the single source of truth for cross-server menu *content*. Cross-server *dispatch* of a redeemed command is the proxy layer's job ([MULTI_SERVER_PLAN.md](../dev/MULTI_SERVER_PLAN.md)). Local tokens therefore suffice, and the second-consensus problem (gossip vs. atomic CAS vs. RPC) is avoided entirely. |
| `runCommand` clicks carrying the literal user command (e.g. `/rtp goto worldname`) | A player can replay the same click string from any other context (alt account, macro, copy-paste from a screenshot) — turns the click event into a permanent unprotected command shortcut. Token indirection removes this. |
| Skip tokens and validate by re-binding via player UUID in the click handler | The Minecraft click event does not authenticate beyond "this client claims to be that player on this connection"; a token guarantees freshness (TTL) and one-shot semantics that pure UUID binding cannot. |
| Ship the book renderer only, with no chat fallback | Fabric's `openBook` path requires per-version packet work that is not ready in beta.4. The fallback exists specifically so the feature is usable on every supported platform on day one of beta.4. |

## Consequences

- **Positive:**
  - Eliminates the inventory-desync class of bugs from the menu surface entirely. There is no inventory state to desynchronize.
  - The menu reuses the command pipeline, so REQ-RTP-S-007 (configurable failure messages), S-004 (no silent teleport failures, audit logging via `RTP.log`), S-005 (async chunk path), and `MemoryTracker` lifecycle are inherited rather than re-implemented.
  - Cross-platform cost is minimized. Paper / Folia get Adventure `Book` natively. Spigot uses `Player#openBook` (since 1.14). Fabric ships on the chat fallback in beta.4; the book path lands incrementally without blocking the feature.
  - No `rtp-core` rendering code. `rtp-api` exposes a platform-free menu model; renderers live in adapter modules; the boundary in `AGENTS.md → Architecture Boundaries` is preserved.
  - Localization is uniform with the rest of RTP. All menu strings live in `messages.yml` under a new `menu:` section; REQ-RTP-F-013 traceability extends naturally.
  - Discoverability win for `/rtp` with no args is delivered in beta.4 without coupling to the larger `/rtpadmin` UX redesign.

- **Negative / Trade-offs:**
  - Spigot and Fabric ship after Paper / Folia in this release. Acceptable because the chosen first consumer (`/rtp config`) is an admin-facing surface most often used on a single representative backend, and the platform-neutral `rtp-api` types are stable from day one.
  - The reflector (ADR-044) couples the menu surface to the shape of the live `commands-api` tree. A command-tree refactor (e.g. parameter-registry rework) may force a reflector update; the cost is bounded by the small reflector surface.
  - Books are stateless from the client's perspective: a long-lived book showing stale state (region full, permission revoked) only learns about the change when the player clicks and the redeem path rejects. The token-redeem rejection message must be informative; this is captured in the `messages.yml` design.
  - Multi-step flows must re-open the book from the redeem-side handler because a `runCommand` click closes the book. Mechanically simple but adds one extra render call per step.
  - The token registry adds a small, bounded per-player map. Eviction is oldest-first under the per-player cap; the memory ceiling is `players × maxOutstandingTokensPerPlayer × sizeof(MenuSession)`, which is well below any realistic JVM heap on a backend that hosts a few hundred players.
  - The `rtp-spigot-v*` and `rtp-paper-v*` per-version subprojects pick up a small amount of new code for the 1.20.5 / 1.21 written-book component / data-component shift. This is the cost of going through the existing versioned-adapter pattern ([ADR-010](ADR-010-versioned-platform-adapter-submodules.md)) rather than special-casing.

## Migration / Rollout

- Beta.3 ships the `rtp-api` model, the `rtp-core` `LocalMenuTokenRegistry` and `MenuRedeemSubcommand` (`/rtp menu:<token>`), the `CommandTreeMenuBuilder` reflector (ADR-044), the Paper / Folia `BookMenuRenderer` (Adventure `Book`), the `/rtp menu` entry-point subcommand, the `/rtp config` browser as the sole in-tree consumer, and the `menu:` section in `messages.yml`.
- Spigot per-version `BookMenuRenderer` branches and the Fabric `ChatMenuRenderer` follow within the same beta.3 cycle as follow-up work once the Paper / Folia path is exercised end-to-end.
- Default config: `menu.renderer: [book]` (ordered preference list; the chat renderer is added to the default list when it ships in the follow-up. On exception or missing adapter the framework walks the list and, if the list is exhausted, logs `Level.WARNING` and sends the configurable `menuInvalid` message — S-004 / S-007). `menu.tokenTtlSeconds: 60`, `menu.maxOutstandingTokensPerPlayer: 256`. No `menu.tokenStore` key ships; the registry is unconditionally local.
- Lite assembly ([ADR-024](ADR-024-rtp-lite-assembly-variant.md)): the menu renderer is not excluded by default; the menu surface is small and shares the existing `messages.yml` resolution path. Revisit during the lite assembly review for beta.3 if disk footprint or Adventure pull-in is a concern.
- Traceability ([TRACEABILITY.md](../dev/TRACEABILITY.md)): add `MenuRedeemSubcommandTest`, `LocalMenuTokenRegistryTest`, and `CommandTreeMenuBuilderTest` rows referencing REQ-RTP-F-013, REQ-RTP-S-007, REQ-RTP-S-006, and (for the hover-text path) REQ-RTP-F-013 again.

## References

- [ADR-010](ADR-010-versioned-platform-adapter-submodules.md) — Versioned platform adapter submodules. Houses the 1.20.5 / 1.21 book-payload version branches.
- [ADR-011](ADR-011-rtp-api-separate-module.md) — `rtp-api` as a separately published addon interface. Governs the layering of the new `MenuModel` / `MenuRenderer` types.
- [ADR-024](ADR-024-rtp-lite-assembly-variant.md) — Lite assembly variant. Default-renderer choice on lite is part of this rollout.
- [ADR-026](ADR-026-external-hook-api-surface.md) — External hook API surface. Reference for any future chat-plugin coexistence entry.
- [commands-api-ADR-001](../../commands-api/docs/adr/commands-api-ADR-001-brigadier-bridge.md) — Brigadier bridge. The `rtp menu:<token>` subcommand registers through this path on platforms that have it.
- [rtp-fabric-ADR-009](../../rtp-fabric/docs/adr/rtp-fabric-ADR-009-obf-unobf-common-split.md) — Obf/unobf carrier split. Fabric chat renderer uses the same `FabricVersionAdapter` dispatch pattern; the future Fabric book renderer will too.
- [REQUIREMENTS.md §3](../dev/REQUIREMENTS.md) — Prohibitions; this ADR inherits S-004, S-005, S-006, and S-007 through the command pipeline.
- [TRACEABILITY.md](../dev/TRACEABILITY.md) — REQ-RTP-F-013 row covers the new `messages.yml → menu:` strings; REQ-RTP-NET-011 / 012 / 014 rows cover the cross-server consume path.
- [MULTI_SERVER_PLAN.md](../dev/MULTI_SERVER_PLAN.md) — Multi-server proxy plan; the shared `MenuTokenRegistry` reuses the same transport / database wiring as the network snapshot and reservation tokens.
- [GLOSSARY.md](../dev/GLOSSARY.md) — *Backend*, *Proxy*, *Network Snapshot*, *Reservation Token*; menu tokens are a sibling concept to reservation tokens and follow the same atomicity contract.
- [ADR-042](ADR-042-yaml-comment-preservation-block-only.md) — Block-comment preservation in the in-house YAML substrate. Unblocks parameter hover text from in-memory comments.
- [ADR-044](ADR-044-command-tree-menu-reflector.md) — Command-tree → `MenuModel` reflector. The mechanism by which `/rtp menu` materializes its pages.
- [CONFIG_COMMAND_SPEC §2.4](../dev/CONFIG_COMMAND_SPEC.md) — `view` sub-form contract; the menu is the GUI form of the same data contract.
- [`docs/dev/scratch/CHECKLIST-inventory-menu-research.md`](../dev/scratch/CHECKLIST-inventory-menu-research.md) — tracked TODO for the deferred inventory-backed renderer follow-up referenced in *Out of scope*; research items R1–R10 gate any successor ADR.
- External: Mojang `tellraw` component / book-page JSON; Adventure `Book` / `Component` APIs.
