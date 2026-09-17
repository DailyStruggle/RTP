# Devstack plugin jars

Staging area for built artifacts. `run-acceptance.ps1` fans them out from here
into the per-service plugin directories before `docker compose up`.

- `jars/plugin/` is the staging source for the unified RTP uber-jar
  (`LeafRTP-Pro-<version>.jar` from `:rtp-plugin:remapJar`). The acceptance
  harness copies it from here into:
  - `./backend-a/plugins/` (mounted at `/data/plugins` in `backend-a`)
  - `./backend-b/plugins/` (mounted at `/data/plugins` in `backend-b`)
  - `./proxy-a/plugins/` and `./proxy-b/plugins/` (Velocity also reads the
    uber-jar via its `velocity-plugin.json` descriptor).

  Each backend must NOT share a host bind for `/data/plugins`: Paper writes
  its remap cache to `/data/plugins/.paper-remapped/` on boot, and two
  backends racing on the same host directory corrupt that cache
  (`ZipException: invalid LOC header (bad signature)`).

Build from the repo root:

```powershell
.\gradlew :rtp-plugin:remapJar
Copy-Item rtp-plugin\build\libs\LeafRTP-Pro-*.jar devstack\jars\plugin\
```

If you are NOT using `run-acceptance.ps1`, manually mirror the uber-jar into
`./backend-a/plugins/` and `./backend-b/plugins/` as well; `docker-compose.yml`
binds those directories directly.

The `.gitkeep` files preserve the directories; the jars themselves are gitignored.
