# rtp-proxy-ADR-010 — Security Hardening (HMAC, `schemaVersion`, Kill Switch)

**Status:** Proposed
**Date:** 2026-05-13
**Refines:** [ADR-036](../../../docs/adr/ADR-036-network-mode-multi-server-multi-proxy.md)
**Depends on:** [rtp-proxy-ADR-001](rtp-proxy-ADR-001-spi-shape.md), [rtp-proxy-ADR-002](rtp-proxy-ADR-002-network-yml-schema.md), [rtp-proxy-ADR-005](rtp-proxy-ADR-005-redis-binding.md), [rtp-proxy-ADR-007](rtp-proxy-ADR-007-postgres-binding.md), [rtp-proxy-ADR-009](rtp-proxy-ADR-009-generic-sql-binding.md)

## Context

`MULTI_SERVER_PLAN.md` enumerates the security risks of running RTP across multiple hosts on a shared transport:

- Any plugin sharing the Redis/Postgres/SQL instance can spoof requests if the wire isn't authenticated.
- Stale or malicious payloads with old `schemaVersion` values could attempt downgrade attacks (replay reservation tokens against newer backends).
- A compromised proxy could write malicious reservation tokens that legitimate backends would honour.

D4 (locked) chose **env var `RTP_NET_SECRET`** as the v1 key-distribution mechanism. This ADR pins the cryptographic envelope, the version-negotiation rules, and the operator-facing kill switch (REQ-RTP-PROXY-007, REQ-RTP-NET-009).

## Decision

### Key Material

- One shared secret per network deployment, named by `network.secretEnv` (default `RTP_NET_SECRET`).
- 256-bit minimum; the binding refuses to enable if `length(secret) < 32` bytes after Base64 decode.
- Operators are responsible for setting the env var on every backend and proxy. A configuration sanity check at boot fails fast with a configurable message if the var is unset while `network.enabled: true`.
- **Rotation in v1 is operator-coordinated downtime.** Hot rotation (dual-key window) is deferred to v2 — recorded as a "Notes" item in this ADR.

### HMAC Envelope

Every wire payload (heartbeat row, reservation token, wait-queue entry) is HMAC-SHA-256 signed:

```
hmac = HMAC-SHA-256(secret, schemaVersion || '|' || canonicalJson(payload))
```

- `canonicalJson` enforces sorted keys, no whitespace, and UTF-8 to keep the signature stable across implementations.
- The signature is stored alongside the payload (Redis HASH field `hmac`; SQL column `hmac BYTEA`/`VARBINARY`).
- Verification rejects on **any** of: missing HMAC, length mismatch, constant-time comparison failure, or `schemaVersion` outside the supported range.

Verification failures are:

1. Logged at `WARNING` via `RTP.log(Level.WARNING, …)` (S-004 audit, REQ-RTP-PROXY-007).
2. Counted in a `securityRejections` metric exposed via `proxy_state` / `backend_state` telemetry.
3. Never silently dropped — the dispatcher surfaces `Failed(reason=AUTH_FAILED)` to the caller with a configurable message.

### `schemaVersion` Negotiation

- Every wire record carries `schemaVersion : int`.
- The running binary declares a `supportedSchemaRange = [minSupported, currentVersion]`.
- A payload outside the supported range is rejected (REQ-RTP-NET-009).
- Mismatched proxy/backend versions on the network surface as a heartbeat-level **warning**: each host logs the version skew once on first contact and includes it in `proxy_state.schemaVersionWarnings[]`.
- **Forward-compatible additive changes** (new optional metric field) do not bump `schemaVersion`. **Breaking changes** (renamed field, removed column, changed semantics) bump it; the older binary then refuses to participate until upgraded.

### Replay Resistance

- Every reservation token includes `created_at_ms` and `expires_at_ms`. A token whose `expires_at_ms < now` is rejected even with a valid HMAC.
- Heartbeat rows include `last_seen_epoch_ms`; verifiers reject rows whose timestamp is more than `heartbeat.staleAfterMs * 3` in the past (anti-replay window).
- Token IDs are random 128-bit UUIDs; collision probability over the network lifetime is negligible.

### Kill Switch

A `network.killSwitch: true` config knob (default false) forces every backend and proxy to **immediately**:

- Stop accepting new RTP requests (return `Failed(reason=KILL_SWITCH)`).
- Stop publishing heartbeats.
- Mark all `PENDING` tokens as `EXPIRED`.
- Leave the plugin loaded but inert; admin commands continue to function.

The kill switch propagates via the transport (`KILL_SWITCH` flag in the first byte of every heartbeat payload). Operators flip the flag on any one host; the rest see it within one heartbeat interval. This is the **incident-response affordance** REQ-RTP-PROXY-007 implies: a way to "stop the network" without restarting every host.

### Threat Model Summary

| Threat | Mitigation |
|---|---|
| Shared transport tenancy (another plugin on same Redis) | HMAC + kill switch |
| Replay of captured token | TTL + token-state machine (CONSUMED is terminal) |
| Downgrade attack via old `schemaVersion` | `minSupported` floor + version skew warning |
| Compromised proxy issuing malicious tokens | Out of scope for v1 — proxies are trusted hosts (operator's own infra) |
| Compromised secret | Operator-coordinated rotation; hot rotation deferred to v2 |
| DoS via mass HMAC-verify | Rate-limited per-source rejection counter; default cap 100/s/source before dropping subsequent invalid payloads silently for 60s |

The "compromised proxy" item is intentionally deferred — RTP's network mode assumes operator-controlled hosts. Cross-tenant trust (untrusted proxies) would require a per-proxy keypair + Ed25519 signing, which is a v2 ADR.

### Logging & Audit

- All security rejections route through `RTP.log(Level.WARNING, …)` (no `printStackTrace()`, no `System.out`).
- The rejection log line carries: `kind={hmac|version|expired|kill}`, `sourceId`, `schemaVersion`, `tokenId?`, sanitised payload hash. **Never** the secret itself.
- `rtp test full network` (Phase 4) exercises HMAC reject, version reject, expired-token reject, and kill-switch propagation as a single end-to-end audit.

## Alternatives Considered

| Alternative | Why Rejected |
|---|---|
| TLS-on-the-wire only (no HMAC) | Doesn't protect against shared-instance tenancy (a co-tenant on the same Redis sees plaintext). |
| Per-host keypair (Ed25519) for v1 | Higher operator complexity; D4 explicitly chose env-var symmetric secret for v1. |
| SHA-1 HMAC for compatibility | Weaker; no compatibility reason since both ends are our code. |
| No version negotiation, just "newest binary wins" | Breaks rolling upgrades; the explicit `minSupported` floor enables zero-downtime upgrades. |
| Token nonces instead of TTL | Adds a nonce-tracking burden across all bindings; TTL is portable and sufficient. |

## Consequences

- **Positive:** clear, auditable security envelope; satisfies REQ-RTP-PROXY-007 and the umbrella ADR-036 "Top Risks" entry on shared-tenant Redis. Kill switch gives operators an incident-response affordance without code changes.
- **Negative:** every wire payload pays the HMAC compute cost (~µs on modern hardware; negligible). Operator must set an env var on every host — but this is the floor for any cross-host secret model.

## Notes

- v2 work (out of scope here): hot key rotation (dual-key acceptance window), per-host Ed25519 signing for untrusted-proxy deployments, mTLS at the transport layer for additional defence-in-depth.

## References

- ADR-036; `MULTI_SERVER_PLAN.md` *Top Risks*, *Security* sections.
- `REQ-RTP-NET-009` (authenticated + versioned relay), `REQ-RTP-PROXY-007`.
- `REQ-RTP-S-004` (audit logging).
