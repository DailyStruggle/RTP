# backend-c mods directory

This directory is bind-mounted into the Fabric backend container at
`/data/mods`.

The RTP mod itself is staged here automatically by `run-acceptance.ps1`:
because RTP ships ONE unified jar (ADR-022) that carries `plugin.yml`
(Bukkit/Folia) AND `fabric.mod.json` (Fabric) side by side, the same
`LeafRTP-Pro-<ver>.jar` the harness builds and drops into the Paper/Folia
backends' `plugins/` is also copied into this `mods/` directory. You do NOT
need to hand-build or hand-drop a separate Fabric jar. The two mods below
(Fabric API + a forwarding bridge) are NOT shipped by RTP and must be
supplied here manually before `docker compose up`.

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
2. **RTP itself** - staged automatically by `run-acceptance.ps1` as the
   unified `LeafRTP-Pro-<ver>.jar` (the same jar used by the Paper/Folia
   backends; it carries `fabric.mod.json`). No manual action needed. If you
   run the stack by hand (`docker compose up` without the harness), copy
   `rtp-plugin/build/libs/LeafRTP-Pro-<ver>.jar` into this directory yourself.
3. **A modern-forwarding bridge mod** - e.g. `FabricProxy-Lite` - so the
   Velocity forwarding secret in `/data/forwarding.secret` is honored.
   Without this, the proxy will reject the backend's player handshakes.

   Note: FabricProxy-Lite does NOT auto-read `/data/forwarding.secret`. The
   `backend-c` service in `docker-compose.yml` points the mod at that file
   via its `FABRIC_PROXY_SECRET_FILE=/data/forwarding.secret` env var, and the
   entrypoint makes `/data/config` writable so the mod can write its
   `FabricProxy-Lite.toml`. If the secret is left empty the mod fails its
   handshake with `Secret check failed ... Empty key` and every proxied login
   is rejected as "Unable to verify player details".

This directory intentionally does NOT ship any jars in source control:
the RTP jar is a build output staged by the harness, and Fabric API /
FabricProxy-Lite are third-party downloads. The directory itself exists so
the bind mount has a target.

## Why backend-c exists

`rtp-fabric` is the project's least-stable platform adapter. The point
of `backend-c` is to drive that adapter under the same Velocity + Redis
acceptance topology used for Paper (`backend-a`) and Folia (`backend-b`),
so platform-adapter regressions are caught here before users hit them in
production. Expect breakage; file the symptoms in
`docs/dev/POTENTIAL_BUGS.md` or the relevant `rtp-fabric` ADR.
