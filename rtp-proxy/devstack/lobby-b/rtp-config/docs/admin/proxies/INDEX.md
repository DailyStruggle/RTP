# RTP Proxy Mode — Admin Documentation

This directory hosts admin-facing documentation for running RTP across **multiple backend servers behind a proxy** (Velocity primary, BungeeCord/Waterfall secondary).

> Status: **Stub.** Proxy mode is not yet released. The design plan lives in [`../../dev/MULTI_SERVER_PLAN.md`](../../dev/MULTI_SERVER_PLAN.md) and is gated by Rule D-005 (see [`AGENTS.md`](../../../.junie/AGENTS.md)). This directory will be populated as Phase 2/3 of that plan lands.

This is **distinct from** multi-platform support (Spigot/Paper/Folia/Fabric) — see [`../../dev/MULTI_PLATFORM_PLAN.md`](../../dev/MULTI_PLATFORM_PLAN.md) for that axis.

---

## Planned pages

Each row links to the design source until the admin doc is authored.

| File | Topic | Source / status |
|------|-------|-----------------|
| [`SINGLE_BACKEND_VERIFICATION.md`](SINGLE_BACKEND_VERIFICATION.md) | Single-backend operator smoke test: `rtp test network` against real Redis (no proxy required) | **Available** (Phase 2e-Redis A1/A2 shipped) |
| [`CROSS_SERVER_VERIFICATION.md`](CROSS_SERVER_VERIFICATION.md) | Multi-proxy / multi-backend round-trip verification (claim → transfer → redeem); paired devstack at [`rtp-proxy/devstack/`](../../../rtp-proxy/devstack/README.md) | **Available** (Phase 2 L3) |
| `QUICK_START.md` | Minimal Velocity + 2× Paper setup with Redis transport | Phase 2 |
| `CONFIGURATION.md` | `network.yml` reference (transport, triggers, reservation, security) | Phase 2 |
| `LOAD_BALANCING.md` | Configurable weighted-average heuristics, curve catalogue, **rendered curve plots** | Phase 2 spec / Phase 3 visuals (see *Documentation follow-up* in `MULTI_SERVER_PLAN.md`) |
| `TRANSPORTS.md` | Redis vs. Postgres vs. generic SQL; `plugin-message` is dev-only (D2) | Phase 2 / Phase 3 |
| `SECURITY.md` | HMAC shared-secret distribution, replay protection, kill switch (D4) | Phase 2 (blocked on D4) |
| `TROUBLESHOOTING.md` | Stale-backend symptoms, reservation TTL expiry, version-skew negotiation | Phase 4 |
| `MIGRATION.md` | Single-server → proxy-mode migration; `network.enabled: false` no-op contract (REQ-RTP-NET-005) | Phase 4 |

---

## Contribution rules

- Admin docs here describe **operator-facing behaviour** only. Implementation details, ADRs, and engineering lore stay in `docs/dev/` and `docs/adr/`.
- Curve plot images and any generation script live alongside the admin doc that uses them; the script is reproducible (matplotlib or similar) and committed under `scripts/`.
- Cross-link the relevant REQ-* and ADR rows when documenting a behaviour rule (e.g. REQ-RTP-NET-005 for the no-op contract).
- Per `AGENTS.md > Self-Updating Protocol`, do not duplicate engineering pitfalls here — those go to `docs/dev/LESSONS_LEARNED.md`.
