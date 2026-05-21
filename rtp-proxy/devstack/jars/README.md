# Devstack plugin jars

Drop the built artifacts here before `docker compose up`:

- `jars/plugin/` mounted into both Paper backends at `/plugins`.
  Required: `rtp-plugin-<version>.jar` from `:rtp-plugin:shadowJar`.
- `jars/velocity/` mounted into both Velocity proxies at `/server/plugins`.
  Required: `rtp-proxy-velocity-<version>.jar` from `:rtp-proxy:rtp-proxy-velocity:shadowJar`.

Build everything from the repo root:

```powershell
.\gradlew :rtp-plugin:shadowJar :rtp-proxy:rtp-proxy-velocity:shadowJar
Copy-Item rtp-plugin\build\libs\rtp-plugin-*.jar rtp-proxy\devstack\jars\plugin\
Copy-Item rtp-proxy\rtp-proxy-velocity\build\libs\rtp-proxy-velocity-*.jar rtp-proxy\devstack\jars\velocity\
```

The `.gitkeep` files preserve the directories; the jars themselves are gitignored.
