# rtp-proxy-ADR-013 - `RTPProxyAccessor` Registration (Mirror of `RTPServerAccessor`)

**Status:** Proposed
**Date:** 2026-05-18
**Refines:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md), [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md)
**Depends on:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md)

## Context

`rtp-proxy-common` must answer two questions at runtime that no platform-agnostic code can answer:

1. "What proxy platform am I running on?" (`role: auto` resolution per ADR-002).
2. "How do I push a player from this server to that server?" (cross-server transfer; only the proxy adapter knows the call - Velocity's `Player#createConnectionRequest()`, Bungee's `ProxiedPlayer#connect()`).

The earlier ADR-002 text resolved (1) by reflective classpath probing (`Class.forName("com.velocitypowered.api.proxy.ProxyServer")`). This worked but mixed two unrelated concerns: it leaked Velocity-API class-name strings into the proxy-agnostic module, and it did not answer (2) at all.

The rest of the project already has a clean precedent: `RTPServerAccessor` is a public interface in `rtp-api`, every platform adapter (`rtp-bukkit-common`, `rtp-paper-common`, `rtp-folia-common`, `rtp-fabric-common`) contributes a concrete implementation, and `rtp-core` reads `RTP.serverAccessor` without ever reflecting on a platform class. Reflection lives only at the outer bootstrap (`rtp-plugin`'s `BootstrapSupport`, `MetricsBindingDispatcher`) where the platform identity is genuinely ambiguous.

`rtp-proxy-common` should mirror that pattern.

## Decision

A new public interface `RTPProxyAccessor` is added to `rtp-proxy-common` (package `io.github.dailystruggle.rtp.proxy.common.platform`). A new public holder class `RtpProxy` in the same module exposes:

```java
public final class RtpProxy {
    public static volatile RTPProxyAccessor proxyAccessor;
    private RtpProxy() {}
}
```

Each proxy adapter (`rtp-proxy-velocity`, future `rtp-proxy-bungee`) constructs its concrete `RTPProxyAccessor` during its bootstrap event and assigns `RtpProxy.proxyAccessor` **before** the network config loader runs. `rtp-proxy-common` performs no classpath probing; it reads `RtpProxy.proxyAccessor` and consults the abstraction.

### `RTPProxyAccessor` shape (target; final signatures pinned during the Phase 2b code turn)

```java
public interface RTPProxyAccessor {
    Role role();

    String proxyId();

    void sendMessage(UUID playerId, MessageKey key, Map<String, String> placeholders);

    CompletableFuture<Void> transferPlayer(UUID playerId, String targetServerId);

    enum Role {
        PROXY_VELOCITY,
        PROXY_BUNGEE
    }
}
```

Method names and exact signatures are illustrative; the Phase 2b code-landing turn finalises them. The shape constraint is: **every method is something only a platform adapter can answer**.

### S-006 null-guard contract

`rtp-proxy-common` consumers (`NetworkConfigLoader`, `BackendSelector`, `RtpDispatcher`, `ProxyStatePublisher`, the `/rtp` Brigadier registrar) shall check `RtpProxy.proxyAccessor` and throw `IllegalStateException("RTPProxyAccessor not registered; addon called before adapter bootstrap")` if `null`. This mirrors S-006 ("No NPE when addons call API before core loads"): a deterministic, attributable failure rather than a silent no-op.

Tests that instantiate `rtp-proxy-common` components without an adapter shall install a `FakeProxyAccessor` (a test fixture) before exercising the components.

### Registration order requirement

In `RtpVelocityPlugin#onProxyInitializeEvent`:

```
1. Construct VelocityProxyAccessor(proxyServer)
2. Assign RtpProxy.proxyAccessor = velocityProxyAccessor
3. Load network.yml via NetworkConfigLoader
4. Open NetworkTransport
5. (... rest of Phase 2b/2e wiring per ADR-006 ...)
```

Steps 1 and 2 must complete before step 3. The Phase 2a no-op shell (already shipped, REQ-RTP-PROXY-VELOCITY-001) does not register an accessor because it does not load the config; it remains a strict no-op until Phase 2b lands.

### What this ADR does **not** standardise

- The exact method set of `RTPProxyAccessor` (finalised during Phase 2b code-landing).
- Whether `transferPlayer` returns `CompletableFuture<Void>` or `CompletableFuture<TransferOutcome>`. Both are defensible; pinned later.
- Hot-swap of the accessor at runtime. v1 disallows this (assignment after first read logs WARNING and is rejected); revisit if a `/rtp reload` scenario surfaces.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| Reflective `Class.forName` probe in `rtp-proxy-common` (status quo per ADR-002 pre-amendment) | Bakes Velocity-API class-name strings into the proxy-agnostic module; does not solve `transferPlayer` / `sendMessage`; requires editing `rtp-proxy-common` for every new proxy adapter. |
| Slot on `RTP` itself (`RTP.proxyAccessor` in `rtp-core`) | Bleeds proxy concerns into `rtp-core`, which is platform-and-proxy-agnostic; violates the architecture-boundary rule in `.junie/AGENTS.md`. |
| Encapsulated `setProxyAccessor(...)` on the config loader | More encapsulated but less symmetric with `RTP.serverAccessor`; harder to discover. The static holder pattern is the established project idiom (see `RTP.java:83`). |
| Service-loader registration (`ServiceLoader<RTPProxyAccessor>`) | Standard JVM mechanism but adds a `META-INF/services` file per adapter and obscures the boot-order requirement; project does not use `ServiceLoader` elsewhere. |

## Consequences

- **Positive:** symmetric with the rest of the codebase (`RTPServerAccessor`); no platform-name strings in `rtp-proxy-common`; future proxy adapters (Bungee, anything else) are purely additive; testable via a `FakeProxyAccessor` fixture.
- **Negative:** introduces a new public static field, which is a project-style convention that newcomers may flag as a singleton anti-pattern; documented as the established `RTP.serverAccessor` precedent.
- **Neutral:** does not change ADR-001 (SPI shape) or ADR-006 (Velocity bootstrap); only adds a registration channel.

## References

- ADR-002 (network.yml schema; section Validation Rules amended 2026-05-18 to consult this accessor).
- ADR-012 (proxy-role default; the wiring matrix consults `RTPProxyAccessor.role()`).
- `rtp-core/src/main/java/.../RTP.java` line 83 (`public static RTPServerAccessor serverAccessor;` precedent).
- `rtp-api/src/main/java/.../api/server/RTPServerAccessor.java` (shape precedent).
- S-006 (REQ-RTP-S-006) - null-guard contract.
