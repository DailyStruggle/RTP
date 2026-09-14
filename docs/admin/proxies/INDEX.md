# RTP Proxy Mode — Admin Documentation

This directory hosts admin-facing documentation for running RTP across **multiple backend servers behind a proxy** (Velocity primary, BungeeCord/Waterfall secondary).

The same jar you install on each backend also runs on a **Velocity** proxy, where it acts as the router for cross-server `/rtp`. The free build ships the `proxy-direct` transport (a lightweight TCP socket that needs no Redis or SQL), so a bare `/rtp` on one server can send a player to a region on another with just the jar on the proxy and each backend. The Redis and SQL shared-state transports are LeafRTP-Pro extras.

This is **distinct from** multi-platform support (the plugin running on Paper, Spigot, Folia, Fabric, or NeoForge) - proxy mode is about routing between several of those backends.

---

## Pages

| Page | Topic |
|------|-------|
| [Configuration (network.yml)](CONFIGURATION.md) | The `network.yml` reference: transport, triggers, reservation, security |
| [Single-backend verification](SINGLE_BACKEND_VERIFICATION.md) | Single-backend operator smoke test (no proxy required) |
| [Cross-server verification](CROSS_SERVER_VERIFICATION.md) | Multi-proxy / multi-backend round-trip (claim → transfer → redeem) |
