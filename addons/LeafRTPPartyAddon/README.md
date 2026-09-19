# LeafRTPPartyAddon

Teleport a whole party/group to one prepared RTP destination (or a small adjacent cluster) in a
single operation.

## Why this fits LeafRTP

RTP prepares locations ahead of time in its supply pipeline (active-loaded / prefiltered / selected).
Serving one already-verified coordinate to N party members - or reserving a small adjacent cluster of
coordinates - is a natural, cheap extension of that pipeline rather than a new search mode. That makes
group teleport far cheaper here than in a search-per-player design.

## Status

Scaffold. This module currently wires configuration and lifecycle only; the party-detection,
coordinate-reservation, and grouped-teleport-dispatch logic is specified in
[`REQUIREMENTS.md`](REQUIREMENTS.md) and
[`docs/adr/leafrtp-party-addon-ADR-001-party-teleport-shared-destination.md`](docs/adr/leafrtp-party-addon-ADR-001-party-teleport-shared-destination.md)
and is the next implementation step. Until then the addon loads as a safe no-op.

## How it works (design)

- Platform-neutral `RTPAddon` discovered via `ServiceLoader` (ADR-057); no `org.bukkit.*` imports.
- Config is `addons/party.yml`, registered through RTP's `ConfigParser` and refreshed on `/rtp reload`.
- Placement modes: `SAME` (all members to one coordinate) or `CLUSTER` (one prepared coordinate per
  member, drawn adjacent). `maxPartySize` bounds how many prepared coordinates one party may consume.

## Configuration (`addons/party.yml`)

| Key | Default | Meaning |
|-----|---------|---------|
| `enabled` | `true` | Master on/off switch. |
| `placement` | `SAME` | `SAME` or `CLUSTER`. |
| `maxPartySize` | `8` | Max members per party teleport. |

## Install

Drop `LeafRTPPartyAddon-<version>.jar` into `plugins/RTP/addons/`.
