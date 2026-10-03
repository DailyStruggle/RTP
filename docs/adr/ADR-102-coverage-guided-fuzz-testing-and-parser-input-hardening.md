# ADR-102 — Coverage-Guided Fuzz Testing and Parser Input Hardening

**Status:** Accepted
**Date:** 2026-10-02
**Relevant Requirements:** REQ-RTP-SYS-001, REQ-RTP-S-001, REQ-RTP-S-004, REQ-RTP-S-005
**Related ADRs:** [ADR-016](ADR-016-anvil-subsystem.md), [ADR-077](ADR-077-multi-format-region-support.md), [ADR-100](ADR-100-superseding-adr-024-sla-and-support-tier.md)

---

## 1. Context

RTP processes external, untrusted, or semi-trusted data across multiple performance-critical surfaces:
1. **World chunk data (`.mca` Anvil and `.linear` ZSTD region files):** Parsed off-tick by `anvil-api` (`AnvilReader`, `LinearRegionReader`, and `Nbt`). In Minecraft, players can manipulate chunk state or exploit vanilla glitches to create corrupted chunks, oversized NBT structures, or invalid heightmaps.
2. **Redis network frames (`RespProtocol`):** Used in proxy/network mode (`rtp-proxy-common`). In shared hosting or multi-plugin Redis networks, malformed frames or rogue plugins can inject malicious payloads.
3. **Player command arguments and gate expressions (`commands-api`, `GateExpressionParser`):** Executed on live threads during gameplay.

Traditional static unit testing and random property-based generators (`jqwik`) can miss edge-case byte sequences, magic-byte boundaries, and resource exhaustion vectors (e.g. integer overflow or unchecked memory allocations).

---

## 2. Decision

1. **Parser Input Bounds Hardening:**
   - Enforce explicit maximum size ceilings on dynamic array allocations in binary/frame decoders. Specifically, `RespProtocol` shall enforce `MAX_BULK_STRING_LENGTH` (16 MiB) on bulk string allocations, failing closed with a typed `IOException` instead of allocating unbounded buffers.
   - All parser failure paths must fail closed safely, producing typed checked exceptions (`IOException`, `CorruptRegionEntryException`, `RespException`) or safe defaults (`Verdict.UNKNOWN`), with zero unhandled `NullPointerException`, `NegativeArraySizeException`, or thread-hanging infinite loops.

2. **Adopt Coverage-Guided In-Process Fuzz Testing (Jazzer) & Two-Tier CI Model:**
   - Introduce `com.code-intelligence:jazzer-junit` into the test toolchain (`gradle/libs.versions.toml`).
   - Write `@FuzzTest` suites targeting `AnvilReader` (for `.mca` and `.linear` chunk decoding) and `RespProtocol` (for RESP2 wire parsing).
   - **Tier 1 (Fast Regression on PR/Push):** In standard CI and test execution (`./gradlew test`), fuzz tests execute deterministic regression seeds in milliseconds without launching the genetic mutation loop, ensuring zero slowdown or non-deterministic test times on PRs.
   - **Tier 2 (Scheduled & Dispatch Exploratory Fuzzing in CI):** A dedicated CI workflow (`.github/workflows/fuzzing.yml`) executes extended in-process coverage-guided mutation loops weekly and on manual dispatch. It executes `./gradlew test` with `JAZZER_FUZZ=1` enabled, tests the fuzz targets for extended durations, and captures crash artifacts/reproducers if violations or hangs are uncovered.

---

## 3. Consequences

### Positive
- **Guaranteed Fail-Closed Stability:** Prevents server crashes or off-tick thread exhaustion when reading corrupted world save data.
- **DDoS/Memory Bomb Immunity:** Eliminates unbounded heap allocations in network frame decoders.
- **Continuous Regressions Guard:** Seed corpuses captured during fuzz runs serve as permanent regression unit tests in CI.

### Neutral / Trade-offs
- `jazzer-junit` adds an opt-in test dependency in `anvil-api` and `rtp-proxy-common`; standard unit testing continues unaffected.
