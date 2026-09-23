# LeafRTPZonesAddon

Timed area teleport ("RTP zones") for LeafRTP. Feature request; no code yet.

## Requested behavior

An operator marks a 3D box in a world. Every player standing inside it when the timer expires is teleported through the normal `/rtp` pipeline into a configured region. A countdown is shown while they wait; a player can opt out and stay put.

This is the "arena lobby" pattern: a hub pad that periodically sends everyone standing on it out into the world together.

## Why this fits LeafRTP

- The teleport itself is the existing pipeline. A zone is only a trigger plus a participant list; it needs no new selection code and inherits the pre-verified cache, claim checks, safety grammar, and effects.
- Group dispatch (spread participants near one anchor, or scatter them) is what `LeafRTPGroupAddon` already specifies. A zone with `spread: true` hands its participant list to a group profile instead of running one `/rtp` per player.
- Countdown display reuses the `LeafRTPCountdownAddon` phases (title, action bar, sound ramp) rather than inventing a second timer.

## Scope

Ships in the addon:

- `definitions/zones/*.yml`, one file per zone: world, two corner coordinates, `interval` (seconds), `region` (target region name), `spread` (group profile name or `none`), `minPlayers`, and `optOutPermission`.
- `/rtp zone create <name>` from two corner selections, `/rtp zone list`, `/rtp zone remove <name>`, `/rtp zone pause|resume <name>`.
- `rtp.zone.admin` for the verbs above, `rtp.zone.optout` for players who want to stand inside a zone without being sent.
- Countdown rendered through the existing effects engine; nothing is hard-coded.
- Zone membership is computed on the tick before dispatch, off-tick where the platform allows, and never loads a chunk (S-005). A zone whose target region has no cached location falls back to the region's normal path; a dispatch that fails is reported per player (S-004).

Not in the first cut:

- Holograms. The countdown goes through the effects engine (title / action bar). A hologram effect can be added to `effects-api` later, with FancyHolograms or a display-entity backend behind it, and zones would pick it up without changes here.
- Per-zone rewards, kits, or scoreboard hooks.
- Cross-server zones. The proxy pipeline can carry a group dispatch later; a zone on backend A sending to backend B is out of scope until that lands.

## Configuration sketch

```yaml
# definitions/zones/hub-pad.yml
world: hub
min: {x: -5, y: 64, z: -5}
max: {x: 5, y: 67, z: 5}
interval: 30
minPlayers: 1
region: wild
spread: party          # group profile from LeafRTPGroupAddon, or none
optOutPermission: rtp.zone.optout
```

## Install

Drop `LeafRTPZonesAddon-<version>.jar` into `plugins/RTP/addons/`.
