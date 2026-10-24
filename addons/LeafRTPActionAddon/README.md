# LeafRTPActionAddon

Platform-agnostic declarative scripted action subsystem for RTP (ADR-093).

## Features
- Declarative action YAML parsing (`definitions/actions/*.yml`).
- Complex multi-stage actions: custom delays, warmups, gates, cooldowns, and post-teleport command sequences.
- Gate evaluation: Scoreboard conditions, spatial bounds, time restrictions, custom predicates.
- Subspace integration: Places participants into isolated or paired subspaces with anchor and separation controls.
- Dynamic root command and `/rtp action` command bridges.
- SAFE PBIT setup checks for rejected action definitions, command/trigger registration, batch scheduling and name collisions (`/rtp test action-setup`).
- Pre-validated placement background pre-warming (ADR-097).
- Confinement enforcement: Restricts participants to action boundaries with pull-back safety relocation.

## Removability
This addon is packaged as a bundled addon in the main RTP jar. If an operator removes `LeafRTPActionAddon.jar` from `<dataFolder>/addons/`, the action subsystem is completely disabled and has zero residual memory or CPU overhead.

BIT codes use the addon-owned `ACTADDON` namespace. The stable identities and remediation text are documented in [`docs/BIT_CODES.md`](docs/BIT_CODES.md); `docs/bit-codes.lock` prevents published ids and keys from being renamed or reused.
