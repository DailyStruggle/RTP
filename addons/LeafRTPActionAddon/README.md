# LeafRTPActionAddon

Platform-agnostic declarative scripted action subsystem for RTP (ADR-093).

## Features
- Declarative action YAML parsing (`definitions/actions/*.yml`).
- Complex multi-stage actions: custom delays, warmups, gates, cooldowns, and post-teleport command sequences.
- Gate evaluation: Scoreboard conditions, spatial bounds, time restrictions, custom predicates.
- Subspace integration: Places participants into isolated or paired subspaces with anchor and separation controls.
- Dynamic root command and `/rtp action` command bridges.
- Pre-validated placement background pre-warming (ADR-097).
- Confinement enforcement: Restricts participants to action boundaries with pull-back safety relocation.

## Removability
This addon is packaged as a bundled addon in the main RTP jar. If an operator removes `LeafRTPActionAddon.jar` from `<dataFolder>/addons/`, the action subsystem is completely disabled and has zero residual memory or CPU overhead.
