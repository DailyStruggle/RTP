# ADR-070 - Platform-neutral `/rtp` command root (`CoreRtpRoot`) for every platform

**Status:** Accepted
**Date:** 2026-06-21

## Context

The `/rtp` command tree is authored once and executed across platforms. Earlier work centralized the shared subcommand / parameter assembly into `rtp-core` (`CoreCommandTreeBuilder`) and moved Bukkit registration and message delivery off the command supertype (commands-api-ADR-002, commands-api-ADR-003). After those phases the three platform roots were thin, but the Fabric (`RTPCmdFabricRoot`) and NeoForge (`RTPCmdNeoForgeRoot`) roots still duplicated each other almost verbatim: the same `BaseRTPCmdImpl` plumbing, the same `onCommand` dispatch to `compute`, the same no-op `successEvent` / `failEvent`, the same `player` / `world` parameter validators routed through `RTP.serverAccessor`, and the same `/rtp menu` permission probe. The only genuine per-platform differences were the menu renderer instance and the `player` parameter's online-name source (`FabricServerAccessor` vs `NeoForgeServerAccessor`, both already exposing `getOnlinePlayerNames()`).

## Decision

The platform-neutral `/rtp` root lives in `rtp-core` as `CoreRtpRoot extends BaseRTPCmdImpl implements RTPCmd`. It assembles the entire tree via `CoreCommandTreeBuilder`, sources the `player` / `world` parameters from a new accessor-backed `ServerAccessorCommandParameters` (a `PlatformCommandParameters` impl), builds the identical `/rtp menu` permission probe internally, and provides the no-op `successEvent` / `failEvent` defaults. The menu renderer and anvil / chat-prompt opener are the only per-platform pieces and are injected through the constructor.

To let the neutral `player` parameter surface tab-completion without a platform downcast, `RTPServerAccessor` gains a default `getOnlinePlayerNames()` (returns an empty set; Fabric / NeoForge accessors already implemented it and now `@Override` it).

Consequently `RTPCmdFabricRoot` and `RTPCmdNeoForgeRoot` collapse to thin `CoreRtpRoot` subclasses whose only body is a constructor passing the platform renderer + opener. They remain as concrete, named `CommandsAPICommand`s for the Brigadier adapter to convert and so each platform owns its renderer choice.

## Update (2026-06-21): Bukkit folded in

`RTPCmdBukkit` is deleted; the Bukkit family now uses `CoreRtpRoot` directly (built by `BootstrapSupport.registerRtpAndWildCommands` and registered through commands-api's `BukkitCommandRegistrar`). The three concerns that previously kept the Bukkit root bespoke are now small seams:

- **Outcome events.** `CoreRtpRoot.successEvent` / `failEvent` fan out through the static `RTPCommandEvents` registry (mirroring RTP's in-house `CopyOnWriteArrayList`-of-callbacks pattern, e.g. `DispatchingPlayerLifecycleHook`). The Bukkit bootstrap subscribes `BukkitCommandEvents` once at startup to republish `TeleportCommandSuccessEvent` / `TeleportCommandFailEvent`; Fabric / NeoForge subscribe nothing.
- **Reply rendering.** An optional `Function<UUID, Consumer<String>>` reply-renderer is injected into `CoreRtpRoot`. Bukkit supplies `BukkitHelpReplyRenderer` (clickable `/rtp help` rows via `SendMessage`); other platforms pass `null` and use the neutral message path.
- **Sender checks + legacy dispatch.** `senderChecks` / `addSenderCheck` / `dispatchString` are lifted into `CoreRtpRoot` (already neutral: `Predicate<RTPCommandSender>` + `RTP.serverAccessor`). The cross-server waitlist guard registers against `CoreRtpRoot`. Brigadier-bridged platforms never invoke `dispatchString`.
- **`player` / `world` parameters.** Bukkit now uses the accessor-backed `ServerAccessorCommandParameters` too; `AbstractServerAccessor` overrides `getOnlinePlayerNames()` for tab-completion. The Bukkit menu permission probe already routed through `RTPServerAccessor.menuPermissionProbe`.

Classpath-conditional menu-renderer / anvil-opener selection stays Bukkit-side in `BukkitMenuBindings` and is injected. `BukkitTestCmd` (Bukkit-typed test children) is registered onto the neutral root at the bootstrap call site.

## Update (2026-06-21): menu bindings discovered via a ServiceLoader SPI (not reflection)

`BukkitMenuBindings` previously resolved the Paper `BookMenuRenderer` and `AnvilInputSession` by hard-coded `Class.forName` string lookups. That reflection existed only because a single Bukkit-family jar must run on plain Spigot and Paper, so those implementation classes are present on the runtime classpath only when a Paper adapter is shaded in. The string lookups are replaced by two `java.util.ServiceLoader` SPIs:

- `MenuRendererProvider` (`rtp-api`, alongside `MenuRenderer`) - `String id()` + `MenuRenderer create()`. `BukkitMenuBindings` discovers every provider and selects by the configured `menu.renderer` id (default `book`).
- `AnvilInputOpenerProvider` (`rtp-core`, alongside `MenuRedeemSubcommand.AnvilInputOpener`, which is a `rtp-core` type) - `AnvilInputOpener create()`. The implementation self-registers any platform listener it needs.

The Paper adapter (`rtp-paper-common`) ships `PaperBookMenuRendererProvider` / `PaperAnvilInputOpenerProvider` and the matching `META-INF/services` files; the anvil provider resolves its owning plugin via `JavaPlugin.getProvidingPlugin` and registers the listener itself, so `BukkitMenuBindings.selectAnvilOpener()` no longer takes a `Plugin`. `rtp-plugin` keeps no compile-time dependency on `rtp-paper-common` (it references only the two SPI interfaces), `ServiceLoader` yields nothing on plain Spigot (graceful `null` fallback), and the `io.github.dailystruggle.rtp.*` packages are not relocated by shadow so the shaded service files resolve unchanged. Behavior is identical: the same renderer is selected for the same config, with the same `null`-tolerant degradation to `menuInvalid`. This is the book-output / menu SPI follow-up flagged in the consequences above.

## Update (2026-06-21): menu-binding selection lifted into rtp-core; every platform completes the same SPI

The discovery + selection logic described in the previous update (then resident in the Bukkit-only `BukkitMenuBindings`) is lifted verbatim into `rtp-core` as `io.github.dailystruggle.rtp.common.commands.menu.MenuBindingSupport`. It owns both seams for every platform: `discoverRenderer()` reads the `menu.renderer` config id list (default `[book]`, empty list = no renderer), loads `MenuRendererProvider` via `ServiceLoader`, selects the first provider matching a configured id, and WARNING-skips unknown / throwing ids; `discoverAnvilOpener()` returns the first discoverable `AnvilInputOpenerProvider` (null-tolerant). `BukkitMenuBindings` is deleted - its sole purpose was this now-shared selection.

`CoreRtpRoot` gains two self-discovering constructors that route through `MenuBindingSupport`: a no-arg `CoreRtpRoot()` (Fabric / NeoForge) and `CoreRtpRoot(Function<UUID, Consumer<String>> replyRenderer)` (the Bukkit family's clickable `/rtp help` sink). The explicit-injection `CoreRtpRoot(MenuRenderer, AnvilInputOpener, replyRenderer)` ctor is kept for tests / back-compat. Consequently `RTPCmdFabricRoot` / `RTPCmdNeoForgeRoot` collapse to a bare `super()` body and `BootstrapSupport` builds `new CoreRtpRoot(new BukkitHelpReplyRenderer())`; no platform root names a renderer / opener class.

Every platform adapter now ships the same two providers + `META-INF/services` files: Paper (`PaperBookMenuRendererProvider` / `PaperAnvilInputOpenerProvider`), Fabric (`FabricBookMenuRendererProvider` / `FabricAnvilInputOpenerProvider`, rtp-fabric-ADR-012), NeoForge (`NeoForge...` symmetric set). This closes the prior live behavior gap where a non-`book` / empty `menu.renderer` id was honored on Bukkit but silently ignored on Fabric / NeoForge - selection is now identical on all platforms. The command tree itself is unchanged (no verb / parameter / permission / order difference); only the menu-binding plumbing is unified.

## Update (2026-06-21): provider selection is platform-gated (universal-jar fix)

The unified discovery in the previous update had no platform gate: every adapter's `book` provider advertises the same `id()`, and `MenuBindingSupport` selected the first `ServiceLoader`-enumerated match. That is correct for a single-platform jar, but the Bukkit-family plugin jar shades `rtp-fabric-common` (and the NeoForge adapter) into the same artifact, so on a Paper runtime all three `book` providers - and all three anvil-opener providers - are on the classpath at once with their `META-INF/services` entries merged. First-wins could therefore pick `FabricBookMenuRenderer` on Paper; that renderer cannot resolve a `FabricBookOpener` for a Bukkit player and silently degraded to its injected `ChatMenuRenderer` (so `/rtp admin` rendered into chat via `SendMessage` instead of opening a book), and the Fabric anvil opener logged its `ServerMessageEvents` unavailability on a non-Fabric server.

`MenuRendererProvider` (rtp-api) and `AnvilInputOpenerProvider` (rtp-core) gain an optional `default @Nullable PlatformFamily platformFamily()` (default `null` = platform-neutral, never skipped). `MenuBindingSupport` skips any provider whose declared family does not equal the running `RTP.serverAccessor.getPlatformFamily()` (the gate stays open when the accessor is unwired or the family is `UNKNOWN`, so tests and unclassified runtimes are unaffected). Paper providers declare `BUKKIT`, Fabric `FABRIC`, NeoForge `NEOFORGE`. This uses the general `rtp-api` compatibility surface mandated by the `.junie/AGENTS.md` "Addon Self-Registration Gating" rule rather than package-name or `Class.forName` probing.

## Update (2026-06-21): jar assembly concatenates `META-INF/services` (the gate's missing half)

The platform gate above is only correct if every platform's provider line actually reaches the universal jar. It did not. `rtp-paper-common` and `rtp-fabric-common` both ship a `META-INF/services/io.github.dailystruggle.rtp.api.menu.MenuRendererProvider` (and the matching `AnvilInputOpenerProvider`), but the `shadowJar` / `shadowLiteJar` tasks did not call `mergeServiceFiles()`, so the duplicate service file collided and only the last-seen copy (Fabric) survived. Separately, the post-remap `mergeNeoForgeBytecodeIntoJar` step skipped all `META-INF/services/*` entries from the NeoForge carriers outright, so NeoForge's providers never registered at all. Net effect once the gate landed: on a Paper runtime the only registered provider was Fabric, which the gate correctly skips - leaving no provider and disabling `/rtp menu` (`menu.renderer 'book' has no provider on this platform` / `menu.renderer list exhausted`).

Two build-side fixes restore the registrations the gate selects among: `shadowJar` and `shadowLiteJar` now call `mergeServiceFiles()` (concatenating the shaded Paper + Fabric entries), and `mergeNeoForgeBytecodeIntoJar` now concatenates the NeoForge carriers' `META-INF/services` lines into the existing host service files (merging in-place, or emitting a carrier-only service file) instead of dropping them. The released `LeafRTP-<v>.jar` and `LeafRTP-Pro-<v>.jar` now list all three providers (`Fabric`, `Paper`, `NeoForge`) under each menu SPI, so the runtime gate picks the matching one on each platform.

## Consequences

- **Positive:** there is now exactly one `/rtp` root class for every platform; the Bukkit-specific command subclass is gone. Adding a platform is "build/extend `CoreRtpRoot`, supply a renderer (+ event/reply seams if the platform has them)". The accessor SPI grew platform-independence-enabling methods (`getOnlinePlayerNames()`), a pattern to repeat for further unification (e.g. a future book-output SPI).
- **Cost:** the event-firing seam moved from a per-instance override to a static `RTPCommandEvents` registry; the Bukkit subscriber is registered idempotently so `/reload` does not double-fire.
- **Behavior:** byte-for-byte equivalent command tree on every platform (same verbs, parameters, permissions, aliases, menu wiring); Bukkit reply rendering, event firing, sender checks, and legacy `String[]` dispatch are preserved through the seams.
