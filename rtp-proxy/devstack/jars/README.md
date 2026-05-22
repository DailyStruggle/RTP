# Devstack plugin jars

Staging area for built artifacts. `run-acceptance.ps1` fans them out from here
into the per-service plugin directories before `docker compose up`.

- `jars/plugin/` is the staging source for the unified RTP uber-jar
  (`RTP-Pro-<version>.jar` from `:rtp-plugin:shadowJar`). The acceptance
  harness copies it from here into:
  - `./backend-a/plugins/` (mounted at `/data/plugins` in `backend-a`)
  - `./backend-b/plugins/` (mounted at `/data/plugins` in `backend-b`)
  - `./proxy-a/plugins/` and `./proxy-b/plugins/` (Velocity also reads the
    uber-jar via its `velocity-plugin.json` descriptor).

  Each backend must NOT share a host bind for `/data/plugins`: Paper writes
  its remap cache to `/data/plugins/.paper-remapped/` on boot, and two
  backends racing on the same host directory corrupt that cache
  (`ZipException: invalid LOC header (bad signature)`).

- `jars/velocity/` mounted into both Velocity proxies at `/server/plugins`.
  Holds the compile-only `rtp-proxy-velocity-<version>.jar` from
  `:rtp-proxy:rtp-proxy-velocity:shadowJar` (not a deployable plugin; the
  uber-jar above is what proxies actually load at runtime).

Build everything from the repo root:

```powershell
.\gradlew :rtp-plugin:shadowJar :rtp-proxy:rtp-proxy-velocity:shadowJar
Copy-Item rtp-plugin\build\libs\RTP-Pro-*.jar rtp-proxy\devstack\jars\plugin\
Copy-Item rtp-proxy\rtp-proxy-velocity\build\libs\rtp-proxy-velocity-*.jar rtp-proxy\devstack\jars\velocity\
```

If you are NOT using `run-acceptance.ps1`, manually mirror the uber-jar into
`./backend-a/plugins/` and `./backend-b/plugins/` as well; `docker-compose.yml`
binds those directories directly.

The `.gitkeep` files preserve the directories; the jars themselves are gitignored.
