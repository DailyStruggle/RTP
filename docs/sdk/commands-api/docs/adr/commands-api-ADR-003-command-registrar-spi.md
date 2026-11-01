# commands-api-ADR-003 - Registration seam via a `CommandRegistrar` (Bukkit), neutral command roots

**Status:** Accepted
**Date:** 2026-06-21

## Context

The `/rtp` command tree is authored once and executed across platforms. On the Bukkit family the root command class (`RTPCmdBukkit`) was forced to `extend BukkitTreeCommand extends org.bukkit.command.BukkitCommand` purely so the object *was* a `CommandExecutor` / `TabCompleter` that could be dropped into the legacy command map. Registration was thus baked into the *supertype* a command had to extend, which meant:

- The Bukkit root could not be authored as the same neutral `BaseRTPCmdImpl` that Fabric / NeoForge already use (their roots are wrapped by the `BrigadierCommandAdapter` rather than *being* a platform command).
- The RTP-specific dispatch logic (teleport-path sender checks, the `RTPCmd` cooldown / processing / reload guard, the help-row clickable rendering, success / fail event firing) was tangled with the platform command supertype.

This is the companion change to [commands-api-ADR-002](commands-api-ADR-002-message-sink-spi.md) (which moved reply-message delivery to a `MessageSink`). With message delivery already off the supertype, the only remaining reason to subclass a Bukkit command type was registration.

## Decision

Registration is owned by `commands-api`, not by the command supertype:

- `commands-api` provides `BukkitCommandRegistrar` (a `CommandExecutor` / `TabCompleter`) that wraps a platform-neutral `TreeCommand` root and binds it onto one or more `PluginCommand`s via `register(String... names)`. Caller `UUID` resolution, the installed `MessageSink` reply consumer, and tab-completion (`root.onTabComplete`) mirror the former `BukkitTreeCommand` behaviour exactly.
- The legacy `String[]`-args command path is delegated to an optional `StringCommandDispatcher` functional seam, so a command tree can inject pre-dispatch behaviour (RTP's sender checks + the `RTPCmd` guard) without subclassing a platform command type. When no dispatcher is supplied the registrar falls back to the generic `TreeCommand` args dispatch.

Consequently the Bukkit `/rtp` root (`RTPCmdBukkit`) now `extends BaseRTPCmdImpl implements RTPCmd` - the same neutral supertype Fabric / NeoForge use. It keeps its RTP-specific concerns as ordinary class members (they live in `rtp-plugin` and are platform-legitimate there):

- `successEvent` / `failEvent` continue to fire the Bukkit `TeleportCommandSuccessEvent` / `TeleportCommandFailEvent` for third-party observability.
- the help-row clickable `SendMessage` reply consumer stays on the parametric `onCommand`.
- the teleport-path sender-check list is lifted from `Predicate<CommandSender>` to the platform-neutral `Predicate<RTPCommandSender>` (it already received a neutral guard via an at-call-site adapter; the adapter is removed). The legacy command entry is exposed as `RTPCmdBukkit#dispatchString(UUID, String, String[])` and handed to the registrar.

## Consequences

- **Positive:**
    - The Bukkit `/rtp` root is a neutral command class; adding a platform is "implement a registrar (or reuse the Brigadier bridge) + a `MessageSink`", not "subclass a platform command type".
    - The legacy Bukkit command-map path is preserved (no loss of Spigot support); it moves from "the supertype you extend" to "a thing the registrar wraps".
    - Behaviour is preserved: the Bukkit dispatch (sender checks, the `RTPCmd` guard, help-row rendering, events) runs exactly as before through `dispatchString`; Fabric / NeoForge are untouched (their own root classes do not override the guard path, so no cross-platform guard behaviour changes).

- **Negative / Trade-offs:**
    - `BukkitTreeCommand` remains in `commands-api` as a still-valid base for other consumers (notably `effects-api`'s command classes), which are intentionally **out of scope** here and unaffected.
    - The `StringCommandDispatcher` is a small additional seam; it is optional and only used where a command needs pre-dispatch behaviour beyond the generic args dispatch.

## Scope

This ADR covers the Bukkit registration seam and the conversion of the `/rtp` Bukkit root to a neutral type. It does **not** convert `effects-api`'s `BukkitTreeCommand` subclasses, and it does not unify the three platform root classes into a single shared class (their menu wiring and parameter sources still differ); that further de-duplication is a separate change.

## References

- `api/commands-api/src/main/java/.../bukkit/BukkitCommandRegistrar.java`
- `api/commands-api/src/main/java/.../bukkit/StringCommandDispatcher.java`
- `rtp-plugin/src/main/java/.../bukkit/commands/RTPCmdBukkit.java` - neutral root + `dispatchString`
- `rtp-plugin/src/main/java/.../bukkit/BootstrapSupport.java` - registrar wiring
- [commands-api-ADR-002](commands-api-ADR-002-message-sink-spi.md) - the message-delivery seam (Phase 1)
- [commands-api-ADR-001](commands-api-ADR-001-brigadier-bridge.md) - the Fabric / NeoForge registration path
