# commands-api-ADR-002 - Message-delivery seam via a `MessageSink` SPI

**Status:** Accepted
**Date:** 2026-06-21

## Context

The RTP command tree is authored once in `commands-api` / `rtp-core` and executed across platforms. Reply-message delivery, however, was platform-coupled in two places on the Bukkit family:

1. Each command instance carried a `messageMethodFactory` (`Function<CommandSender, Consumer<String>>`) set imperatively in the `RTPCmdBukkit` constructor, to route raw templates through the platform formatter / auditor (`SendMessage`).
2. A per-platform command base class (`BukkitBaseRTPCmd`) overrode `msgInvalidCommand` / `msgBadParameter` to pre-format error templates and avoid a duplicate console line, while suppressing the `RTP.log` the platform-neutral defaults emit.

The net effect was that a platform needed a bespoke command supertype purely to format and audit reply messages, and the command tree could not be authored as a single neutral type. This duplicated the delivery concern and obscured the actual command logic.

## Decision

A platform-supplied `MessageSink` SPI shall own reply-message delivery:

- `commands-api` defines `MessageSink#send(UUID callerId, String rawTemplate)` and a single installation point (`CommandsAPI.setMessageSink` / `getMessageSink`), plus `CommandsAPI.messageMethodFor(callerId, fallback)` which yields a sink-backed reply consumer when a sink is installed and the caller's fallback consumer otherwise.
- The dispatch layer (`BukkitTreeCommand`) sources the reply consumer from the installed sink rather than from a per-command `messageMethodFactory`.
- The platform-neutral command message defaults (`BaseRTPCmd#msgInvalidCommand` / `#msgBadParameter`) pre-format the template through `RTPServerAccessor#format` and deliver through the supplied consumer **without** an additional `RTP.log` when a sink is installed (the sink's delivery path owns audit interception). When no sink is installed, the prior behavior is unchanged.

The platform implements `MessageSink` once (e.g. `BukkitMessageSink`, delegating to `SendMessage`) and installs it during command registration. This removes the need for a platform-specific command base class for message delivery.

## Consequences

- **Positive:**
    - The Bukkit `/rtp` root is authored as a neutral `BukkitTreeCommand` subclass; `BukkitBaseRTPCmd` is deleted and the `messageMethodFactory` assignment is gone.
    - Adding a platform requires implementing `MessageSink`, not subclassing a platform command type for message routing.
    - Behavior is preserved byte-for-byte: normal replies route the raw template through the sink (identical to the former factory); error templates are pre-formatted before delivery and emit no duplicate `RTP.log` (identical to the former override).

- **Negative / Trade-offs:**
    - `MessageSink` is a process-global installation (`CommandsAPI` static). This matches the single-plugin-per-JVM assumption already present in `commands-api`; the most-recent installation wins.
    - The platform-neutral defaults branch on `CommandsAPI.getMessageSink() != null`; platforms that do not install a sink (Fabric / NeoForge, which deliver via the Brigadier bridge) keep the legacy `RTP.log` emitting path.

## Scope (Phase 1)

This ADR covers only the message-delivery seam. The companion idea of a `CommandRegistrar` SPI that would let `commands-api` own command *registration* (removing the requirement that the Bukkit root subclass `org.bukkit.command.Command`) is deferred to a separate, D-005 gated change. `effects-api`'s `BukkitTreeCommand` subclasses are out of scope and unaffected.

## References

- `api/commands-api/src/main/java/.../common/MessageSink.java`
- `api/commands-api/src/main/java/.../common/CommandsAPI.java` - `setMessageSink` / `getMessageSink` / `messageMethodFor`
- `rtp-core/src/main/java/.../common/commands/BaseRTPCmd.java` - sink-aware message defaults
- `rtp-plugin/src/main/java/.../bukkit/commands/BukkitMessageSink.java` - Bukkit implementation
- [commands-api-ADR-001](commands-api-ADR-001-brigadier-bridge.md) - Brigadier bridge (the Fabric / NeoForge delivery path)
