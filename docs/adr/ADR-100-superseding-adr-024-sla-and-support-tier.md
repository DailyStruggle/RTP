# ADR-100 — Superseding ADR-024: Technical Parity and Pro as SLA & Support Tier

**Status:** Accepted
**Date:** 2026-10-01
**Supersedes:** [ADR-024](ADR-024-rtp-lite-assembly-variant.md) (RTP-lite Assembly Variant)
**Relevant Requirements:** REQ-RTP-SYS-001, REQ-RTP-F-013, REQ-RTP-S-004

---

## 1. Context

[ADR-024](ADR-024-rtp-lite-assembly-variant.md) introduced `LeafRTP` ("Lite") as a physically distinct jar assembly from `LeafRTP-Pro`. The historical rationales were:
1. **Package size reduction:** Trimming heavy third-party drivers (Jedis, Jackson, HikariCP) from single-server deployments.
2. **Operational simplicity:** Stripping enterprise clustering and proxy mechanics from operators who did not run proxy networks.
3. **Distribution separation:** Bundling addons exclusively in Pro.

### What Changed

Subsequent engineering advancements eliminated the technical foundation of the split:
- **Zero-Dependency Redis (`RespRedisClient`):** The replacement of `redis.clients:jedis` and Apache Commons Pool2 with an in-tree native RESP2 socket client dropped Redis transport footprint from ~1 MB down to ~15 KB.
- **Addon Parity:** `LeafRTPActionAddon`, `LeafRTPGuiAddon`, `LeafRTPClaimAddon`, and `LeafRTPCountdownAddon` were bundled across both distributions to ensure full gameplay functionality out of the box.
- **Size Convergence:** The net size delta between the Pro and Lite distributions shrank to ~370 KB (a ~6% difference on disk).
- **Market Dynamics:** Artificially withholding database engines (SQLite, MySQL, PostgreSQL) and clustering from open-source operators compromised adoption against competitors like BetterRTP and JustRTP, without delivering meaningful package savings.

---

## 2. Decision

1. **Supersede the Physical Feature Split:**
   The codebase provides 100% technical and functional parity across all core capabilities. Lite and Pro share identical engine math, selection shapes, database options (SQLite, MySQL, PostgreSQL, H2, Yaml), Redis transport, Anvil pre-filtering, Folia region scheduling, and bundled addons.

2. **Re-frame "Pro" as the SLA, Support Agreement, and Donor Tier:**
   The distinction between Pro and Lite is **commercial and operational separation**, not artificial code throttling:
   - **User Separation & Priority Support:** Pro builds, distributed via marketplaces (BuiltByBit, Polymart, SpigotMC Premium), embed buyer identification metadata (`DownloadInfo.userId()`, `resourceId()`, `nonce()`). When an operator requests help in Discord or GitHub issue trackers, support staff use the `/rtp info` signature and buyer metadata to verify commercial license status and route tickets into the Priority SLA support queue.
   - **Commercial Indemnity & Grant:** Pro provides explicit commercial rights under the PolyForm Commercial License for networks monetizing gameplay.
   - **Sponsorship & Sustainable Funding:** Pro is the donor/sponsor vehicle funding continuous open-source maintenance.

3. **Release Staging:**
   - **Pro Marketplaces:** Receive continuous production and early-access builds (with weekend releases).
   - **Public Distribution (Modrinth / SpigotMC Free):** Receives stable milestone builds on a scheduled cadence (e.g. 1-2 weeks post-commercial release) to ensure stability for community operators.

---

## 3. Consequences

### Positive
- **Total Market Competitiveness:** The open-source `LeafRTP` edition beats all competitors on performance, safety, and features with zero paywalled engine limitations.
- **Simplified Maintenance:** Eliminates divergent runtime bootstraps and redundant exclusion rules.
- **Clear Support Boundaries:** Marketplace metadata explicitly delineates priority SLA tier tickets from general community inquiries without intrusive DRM.

### Neutral / Trade-offs
- Operators who do not sponsor the project receive community-tier support rather than guaranteed SLA response times.
