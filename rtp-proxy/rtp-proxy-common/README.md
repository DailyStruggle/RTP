# `rtp-proxy-common/` — Proxy-Side Shared SPI

> **Status: scaffolding only.** No code, no `build.gradle` yet. Gated by Rule D-005 and ADR-036 (outstanding).

The proxy-side analogue of `rtp-core`'s `NetworkBridge`: pure-Java, no proxy-vendor imports, no backend-platform imports. Hosts the SPI that every concrete proxy adapter (`rtp-proxy-velocity`, `rtp-proxy-bungee`) consumes, plus reference implementations of the load balancer, trigger sources, reservation client, and config schema.

## Architectural rules

- **No `com.velocitypowered.*` imports.** No `net.md_5.bungee.*` imports. No `org.bukkit.*` imports. No `net.minecraft.*` imports. No `net.fabricmc.*` imports.
- **Depends only on** `rtp-api`, `rtp-core`, `commands-api`, the network-state member of `AbstractSQLDatabaseAccessor`, and the standard library.
- **Pure-function selector.** `BackendSelector#choose(RtpRequest, NetworkSnapshot)` does no I/O during evaluation.

## Planned package layout (notes only — not yet created)

```
src/main/java/io/github/dailystruggle/rtp/proxy/common/
├── spi/             # RtpDispatcher, BackendSelector, NetworkTransport, ProxySender, RtpTriggerSource
├── selector/        # Weighted-average BackendSelector + curve catalogue (linear/exp/log/sigmoid/step/power)
├── reservation/     # ReservationClient — proxy-side claim helper (PENDING→CLAIMED row-count atomicity)
├── trigger/         # CommandTriggerSource, JoinTriggerSource, EventTriggerSource
├── transport/       # NetworkTransport SPI + binding registry (concrete bindings ship in rtp-core)
├── snapshot/        # NetworkSnapshot DTO + freshness filter
├── telemetry/       # ProxyStatePublisher base (heartbeat row keyed by proxyId)
├── config/          # NetworkYamlSchema, validation, curve param bounds
└── message/         # Network message keys for messages.yml (REQ-RTP-NET-006)
```

## Planned top-level files (not yet created)

| File | Purpose | Created when |
|---|---|---|
| `REQUIREMENTS.md` | `REQ-RTP-PROXY-COMMON-NNN` requirements | **Next step** |
| `build.gradle` | Java library module | Phase 1 |
| `src/test/...`   | Unit tests for selector / curves / reservation race | Phase 1 |

## Optional further subdirectories (notes only)

- `src/main/resources/` — *will* host the bundled default `network.yml` schema and the default `messages` keys; created in Phase 1.
- `src/test/resources/` — *will* host fixture snapshots for the load-balancer unit tests; created in Phase 1.
- `docs/` — *not* planned. Module-level docs stay in `REQUIREMENTS.md`; design lore goes to `docs/dev/MULTI_SERVER_PLAN.md` or `LESSONS_LEARNED.md`.

## Cross-references

- [`../README.md`](../README.md) — umbrella overview.
- [`../../docs/dev/MULTI_SERVER_PLAN.md`](../../docs/dev/MULTI_SERVER_PLAN.md) §*Load Balancer*, §*Trigger Abstraction*, §*Reservation Tokens*.
