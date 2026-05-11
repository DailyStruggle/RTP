# Commands & Permissions (RTP-lite)

> Stripped from `docs/admin/COMMANDS.md`. Only commands & permissions present
> in the lite assembly are listed (ADR-024). Cross-server, language, and
> claim-related sub-commands belong to RTP-Pro.

## Player commands

| Command | Permission | Description |
|---|---|---|
| `/rtp` | `rtp.use` | Teleport caller to a random safe location in their current world's region. |
| `/rtp region:<name>` | `rtp.use` + `rtp.regions.<name>` | Teleport to a named region. |
| `/rtp world:<name>` | `rtp.use` + `rtp.worlds.<name>` | Teleport into another world's region. |
| `/rtp player:<name>` | `rtp.other` | Teleport another player. |
| `/rtp help` | `rtp.see` | Show in-game help. |

Argument syntax is `<key>:<value>`; unknown keys are rejected with a
configurable `messages.yml` entry.

## Operator / admin commands

| Command | Permission | Description |
|---|---|---|
| `/rtp reload` | `rtp.reload` | Reload all configs and rebuild region queues. |
| `/rtp reload <region>` | `rtp.reload` | Reload a single region. |
| `/rtp info` | `rtp.info` | List worlds & permanent regions. |
| `/rtp info region:<name>` | `rtp.info` | Queue depth, in-flight calcs, shape, cacheCap. |
| `/rtp config <file> <key>:<value>` | `rtp.config` | Read/write a config key (in progress; prefer YAML edits + `/rtp reload`). |
| `/rtp scan start \| pause \| resume \| cancel \| reset [region:<name>]` | `rtp.scan` | Manage spatial-memory pre-warm. |
| `/rtp test stress player:<name> [iterations:N] [intervalTicks:T]` | `rtp.test` | Drive the full pipeline against a real player; failures log at `WARNING`. |

`iterations` is clamped to `[1, 1000]` (default `10`); `intervalTicks` to
`[10, 6000]` (default `40`).

## Permissions only meaningful in lite

| Permission | Grants |
|---|---|
| `rtp.use` | base RTP usage |
| `rtp.see` | receive RTP messages |
| `rtp.noCooldown` | bypass cooldown |
| `rtp.other` | teleport other players |
| `rtp.reload` | `/rtp reload` |
| `rtp.config` | `/rtp config` |
| `rtp.info` | `/rtp info` |
| `rtp.scan` | `/rtp scan` |
| `rtp.test` | `/rtp test` |
| `rtp.regions.<name>` | use a specific region |
| `rtp.worlds.<world>` | use `/rtp` in a specific world (when `requirePermission: true`) |
| `rtp.effects.<name>` | gate per-permission effects defined in `effects/<name>.yml` |

`rtp.free` (economy bypass) is recognised but inert — lite does not ship the
economy hook.

## Not present in lite (RTP-Pro only)

- `/rtp language ...` (multilingual support — ADR-020).
- Claim-plugin diagnostic sub-commands (ADR-019).
- SQL-backed queue inspection.
- Folia regionised scheduling sub-commands.
