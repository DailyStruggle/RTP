# Lobby world (optional)

Drop a WorldEdit / FastAsyncWorldEdit schematic file into this directory to
have the devstack's two lobby Paper servers (`lobby-a`, `lobby-b`) boot into
that build instead of a vanilla generated world.

## Accepted formats

- `*.schem` (modern Sponge schematic, v2 or v3) - preferred.
- `*.schematic` (legacy MCEdit) - also accepted by FastAsyncWorldEdit.

Only the **first** matching file (alphabetical order) is used per bake. If you
keep multiple here, rename or delete the ones you don't want pasted.

## How it works

The schematic itself is never auto-pasted by the devstack (there is no clean
fully-headless schematic-to-Anvil-world converter for modern Paper). Instead:

1. **One-time bake** (per schematic change): bring the devstack up, paste the
   schematic into `lobby-a` from a Minecraft client using FastAsyncWorldEdit,
   then run `.\scripts\bake-lobby-world.ps1` to zip the resulting `world/`
   directory into `shared/lobby-world.zip`. The full walkthrough lives in the
   devstack `README.md` under "Lobby world (optional)".
2. **Every subsequent `docker compose up`**: when `shared/lobby-world.zip`
   exists, `run-acceptance.ps1` automatically layers
   `docker-compose.lobby-world.yml` on top of the base compose file. That
   override mounts the zip into each lobby container; the
   itzg/minecraft-server image unpacks it on every boot
   (`FORCE_WORLD_COPY=TRUE`), so both lobbies always start from the canned
   world in ~10 s.

If no schematic and no baked zip are present, lobbies fall back to a vanilla
default world (current behavior).

## Not committed to git

`*.schem`, `*.schematic`, and the derived `lobby-world.zip` are gitignored.
Most BuiltByBit / marketplace schematic licenses forbid redistribution; keep
the file local to your machine.

## Manual rebake

```powershell
# From devstack/, after pasting the schematic into lobby-a from a
# live MC client and verifying the result in-game:
.\scripts\bake-lobby-world.ps1                  # zips lobby-a/world/ -> shared/lobby-world.zip
.\scripts\bake-lobby-world.ps1 -Source lobby-b  # bake from lobby-b instead
.\scripts\bake-lobby-world.ps1 -Force           # overwrite an existing zip without prompting
```
