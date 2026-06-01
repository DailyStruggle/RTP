# backend-c mods directory

This directory is bind-mounted into the Fabric backend container at
`/data/mods`. Drop the Fabric build of RTP-Pro here before running
`docker compose up` (or `run-acceptance.ps1`).

Note on layout asymmetry vs the Paper/Folia backends:

- Mods (jars): `./backend-c/mods/` -> `/data/mods/` (Fabric convention; NOT
  `plugins/` like the Bukkit-family backends).
- RTP config: `./backend-c/rtp-config/` is seeded into `/data/config/rtp/`
  inside the container (lowercase `rtp`). This is what
  `FabricLoader.getConfigDir().resolve("rtp")` resolves to at runtime; see
  `FabricDatabaseHandler#resolveConfigDirectory` and
  `rtp-fabric-ADR-013 FabricNetworkModeBootstrap.ensureNetworkYml`. The
  Bukkit-family backends instead seed `/data/plugins/RTP/` (uppercase).

Required mods for a working backend-c boot:

1. **Fabric API** - the `fabric-api-<version>.jar` matching MC 1.21.11.
2. **RTP-Pro Fabric mod** - built from this repository's `rtp-fabric`
   module. The exact jar name is governed by the Gradle build (e.g.
   `rtp-fabric-1.21.11-3.0.0-beta.4.jar`); drop the assembled artifact
   here.
3. **A modern-forwarding bridge mod** - e.g. `FabricProxy-Lite` - so the
   Velocity forwarding secret in `/data/forwarding.secret` is honored.
   Without this, the proxy will reject the backend's player handshakes.

This directory intentionally does NOT ship any jars in source control:
the Fabric jars are platform-specific build outputs and must be produced
locally. The directory itself exists so the bind mount has a target.

## Why backend-c exists

`rtp-fabric` is the project's least-stable platform adapter. The point
of `backend-c` is to drive that adapter under the same Velocity + Redis
acceptance topology used for Paper (`backend-a`) and Folia (`backend-b`),
so platform-adapter regressions are caught here before users hit them in
production. Expect breakage; file the symptoms in
`docs/dev/POTENTIAL_BUGS.md` or the relevant `rtp-fabric` ADR.
