# ADR-035 — Interactive Menus via Written Book (Book-First, Chat Fallback)

**Status:** Proposed
**Date:** 2026-05-13
**Target release:** `3.0.0-beta.4`

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
- **`rtp-plugin`** — registers the renderer chosen by config (`menu.renderer: book | chat | auto`). No business logic. `auto` selects `book` where the renderer is available and falls back to `chat` otherwise.

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

### Cross-server menus (in scope)

A menu minted on backend **A** must be redeemable on backend **B** when the player crosses a proxy in the multi-server topology ([MULTI_SERVER_PLAN.md](../dev/MULTI_SERVER_PLAN.md)). The token registry therefore has two implementations behind the `MenuTokenRegistry` interface, selected by config (`menu.tokenStore: local | shared`; default `local`, auto-promoted to `shared` when a network transport is configured):

- **`LocalMenuTokenRegistry`** — the in-memory `ConcurrentHashMap` described above. Used for single-backend installs; tokens minted here are not visible to other backends.
- **`SharedMenuTokenRegistry`** — backed by an atomic shared store. Two drivers ship in beta.4:
  - **SQL driver** — reuses `AbstractSQLDatabaseAccessor` (HikariCP-backed); supported on PostgreSQL and MySQL. Atomicity is enforced by `UPDATE rtp_menu_tokens SET consumed_at = ? WHERE token = ? AND consumed_at IS NULL AND expires_at > ?` returning row-count `1`; H2 / SQLite are usable for single-node testing but not advertised as production cross-server stores.
  - **Redis driver** — `SET token payload NX PX <ttl>` for mint, and a Lua script (`GET` + conditional `DEL`) for atomic redeem. Used when `menu.tokenStore.driver: redis` is set; connection details live in the existing transport config block.
- **Payload contract.** The shared row / Redis value carries everything the executing backend needs to redeem without calling back to the origin: `token`, `playerUuid`, `originBackendId`, `targetBackendId` (nullable; null = any backend that can serve the action), `menuAction` (serialized as the sealed `MenuAction` JSON; never a raw command string), `mintedAt`, `expiresAt`, `consumedAt` (null until claimed). The schema is intentionally minimal — it must not carry plaintext command strings, region geometry, or chunk references; the executing backend resolves region names and permissions against its own config.
- **Action portability.** `MenuAction.RunRtpCommand` is serialized as `(subcommand, args[])`, not as a command line; the executing backend runs it through the same `commands-api` validation as a local invocation, so REQ-RTP-S-007 / S-004 / S-005 apply identically on both ends. Actions referencing regions that do not exist on the executing backend fail with the configurable `menu.unknownRegion` message (REQ-RTP-F-013).
- **Reservation-token alignment.** When a `RunRtpCommand` would otherwise issue a *reservation token* (REQ-RTP-NET-011 / 012 / 014; see *reservation token* in [GLOSSARY.md](../dev/GLOSSARY.md)), the menu redeem mints the reservation token after the atomic consume of the menu token, never before — single failure point, no double allocation.
- **Atomicity boundary.** "Atomic" here means single-statement compare-and-set semantics for token consume on the shared store. The menu redeem subcommand performs exactly one such CAS; if the CAS fails (already consumed, expired, foreign UUID), the redeem rejects with the appropriate configurable message and logs through `RTP.log` per S-004. The store is the authority on consume; backends do not gossip.
- **Failure / store-down mode.** If the shared store is unreachable at redeem time, the redeem rejects with the configurable `menu.storeUnavailable` message and logs a `RTP.log(Level.WARNING, …)`. The redeem never falls through to a local-only check (that would defeat the single-consume guarantee).

### Renderers (adapter layer)

- **`BookMenuRenderer`** — translates `MenuModel` to either Adventure `Book` (Paper/Folia/modern Spigot via paper-api shading where present) or a transient `ItemStack` of `WRITTEN_BOOK` with the version-correct pages payload (Spigot legacy / older Paper). Each `MenuFragment` becomes a styled run with an attached `ClickEvent.runCommand("/rtp menu:<token>")` and optional `HoverEvent.showText`. Pagination is `ClickEvent.changePage`. Multi-step menus re-open the book from the redeem-side action handler; the book does not attempt to mutate itself in place.
- **`ChatMenuRenderer`** — translates `MenuModel` to a `tellraw`-equivalent component tree on the active platform (BungeeCord chat-api on Spigot, Adventure on Paper / Folia, native `Component` on Fabric). Pagination collapses to "next page" / "prev page" lines that re-render. No chat-stream interception is performed; long menus simply scroll, which is acceptable for the fallback role.

### Concrete first consumer

To validate the design end-to-end while keeping blast radius small, the **only** consumer that ships with this ADR is **`/rtp` with no args → region picker**:

- Page 1 lists each region the player has permission for (one fragment per region; hover shows the region's distance / cooldown / queue depth as plain text).
- Clicking a region mints a token bound to `MenuAction.RunRtpCommand("--region", regionName)` and the redeem path executes the equivalent of `/rtp <region>`.
- All visible strings come from `messages.yml` under a new `menu:` section (`menu.regionPicker.title`, `menu.regionPicker.hover.distance`, `menu.regionPicker.empty`, `menu.invalid`, `menu.expired`, `menu.unknownPlayer`). REQ-RTP-F-013 applies to every one of them.

`/rtp biomes` and any `/rtpadmin` wizard refactor are explicitly **deferred to follow-up ADRs** so this change can land within the beta.4 window without coupling to admin UX work.

### Out of scope (this ADR)

- Chat suppression / replay buffering. Discarded: the book primary obviates it, and the `ChatMenuRenderer` fallback is documented as a "menu scrolls with chat" experience rather than a "chat is muted" experience.
- Inventory-backed menus. Not pursued for the reasons in *Context*.
- `/rtpadmin` setup wizards. Acknowledged as the highest-value future application; deferred so beta.4 ships the primitive without the full UX redesign attached.
- A `MenuRenderer` exposed to addons. The interface is **internal-public** (lives in `rtp-api` for layering reasons only); addon-facing menu rendering is a later, deliberate API decision after the in-tree consumer settles.
- Store drivers beyond PostgreSQL / MySQL / Redis in beta.4. H2 and SQLite are usable for single-node tests but explicitly **not** supported as production cross-server stores; cross-server installs that pick them on purpose receive a config-load warning.

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
| Tokens scoped per-server in a multi-server install (origin-backend-only) | Rejected: players cross servers between mint and click in the proxy topology ([MULTI_SERVER_PLAN.md](../dev/MULTI_SERVER_PLAN.md)). A menu minted on backend A and clicked from backend B would dead-redeem. The shared-store registry (SQL or Redis) carries the token across the network and remains atomic on consume. |
| Backend-to-backend gossip / pub-sub for token visibility instead of a shared atomic store | Two backends consuming the same token must agree on "who got it first". Eventual-consistency gossip cannot guarantee single-consume without an arbiter — which is exactly what Postgres or Redis already provide. Adds a second consensus problem RTP does not need. |
| Caller-backend RPC: redeeming backend calls origin backend to consume | Couples the redeem latency to origin backend availability and reintroduces a backend-graph dependency the proxy plan deliberately avoids. The shared store collapses both backends' interest into one row / one key. |
| Store choice: only SQL, or only Redis | Operators with an existing SQL footprint pay nothing to add `rtp_menu_tokens`; operators with an existing Redis footprint get sub-millisecond CAS and TTL semantics for free. Shipping both, behind the same `MenuTokenRegistry` interface, costs little and avoids forcing a deployment choice. |
| Store choice: H2 / SQLite as a production cross-server store | Neither is designed for concurrent writers across processes. Demoted to single-node testing only; config-load warns when selected with `menu.tokenStore: shared`. |
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
  - Cross-server menus introduce an external dependency (Postgres / MySQL / Redis) on installs that opt in to the shared registry. Single-backend installs are unaffected (`menu.tokenStore: local` is the default).
  - The shared store is on the redeem path; a store outage produces a clean per-click failure (configurable message + S-004 log) rather than a wrong result, but it does mean menu clicks are not available when the store is down. Acceptable: the same store backs reservation tokens (REQ-RTP-NET-011 / 012 / 014), so a store outage already implies the cross-server `/rtp` flow is degraded.
  - Fabric ships only the chat fallback in beta.4; book parity on Fabric is a tracked follow-up. Acceptable because the chat fallback is functional, and the Fabric platform itself is still on the active frontier per `AGENTS.md → Current Development Focus`.
  - Books are stateless from the client's perspective: a long-lived book showing stale state (region full, permission revoked) only learns about the change when the player clicks and the redeem path rejects. The token-redeem rejection message must be informative; this is captured in the `messages.yml` design.
  - Multi-step flows must re-open the book from the redeem-side handler because a `runCommand` click closes the book. Mechanically simple but adds one extra render call per step.
  - The token registry adds a small, bounded per-player map. Eviction is oldest-first under the per-player cap; the memory ceiling is `players × maxOutstandingTokensPerPlayer × sizeof(MenuSession)`, which is well below any realistic JVM heap on a backend that hosts a few hundred players.
  - The `rtp-spigot-v*` and `rtp-paper-v*` per-version subprojects pick up a small amount of new code for the 1.20.5 / 1.21 written-book component / data-component shift. This is the cost of going through the existing versioned-adapter pattern ([ADR-010](ADR-010-versioned-platform-adapter-submodules.md)) rather than special-casing.

## Migration / Rollout

- Beta.4 ships the `rtp-api` model, the `rtp-core` token registry (both `LocalMenuTokenRegistry` and `SharedMenuTokenRegistry` with SQL + Redis drivers) and redeem subcommand, the Paper / Folia / Spigot `BookMenuRenderer`, the Fabric `ChatMenuRenderer`, the `/rtp` no-args region picker as the sole in-tree consumer, and the `menu:` section in `messages.yml`.
- Default config: `menu.renderer: auto`, `menu.tokenStore: local` (auto-promoted to `shared` when a proxy transport is configured), `menu.tokenStore.driver: sql | redis` (driver picked from the existing database / transport config; no separate credentials block), `menu.tokenTtlSeconds: 60`, `menu.maxOutstandingTokensPerPlayer: 256`. The no-args `/rtp` behavior remains opt-out via the existing default-region resolution; admins can set `menu.regionPicker.enabled: false` to preserve the pre-beta.4 behavior.
- Lite assembly ([ADR-024](ADR-024-rtp-lite-assembly-variant.md)): the menu renderers are not excluded by default. Per the lite cap-keys convention, an explicit `menu.renderer: chat` and `menu.tokenStore: local` are the safe lite defaults if disk footprint, Adventure pull-in, or Redis client pull-in is a concern; revisit during the lite assembly review for beta.4.
- Traceability ([TRACEABILITY.md](../dev/TRACEABILITY.md)): the no-args region-picker tests, the local token-redeem tests, and a new `SharedMenuTokenRegistryAtomicConsumeTest` (one per driver) reference REQ-RTP-F-013, REQ-RTP-S-007, and the REQ-RTP-NET-011 / 012 / 014 row family; add `MenuRedeemSubcommandTest` and `SharedMenuTokenRegistryAtomicConsumeTest` rows.

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
- External: Mojang `tellraw` component / book-page JSON; Adventure `Book` / `Component` APIs; PostgreSQL / MySQL `UPDATE … WHERE … RETURNING` / row-count semantics; Redis `SET NX PX` and Lua `EVAL` atomicity.
