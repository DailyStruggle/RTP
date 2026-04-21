# RTP Addons Requirements

This document details the requirements and constraints for any external addons integrating with the RTP plugin.

## 1. Strict Architectural Requirements

### 1.1 Fault Encapsulation and Isolation
- **Strict Isolation:** External addons (e.g., `RTP_ExampleAddon`, `RTP_Glide`, `RTP_Iris_integration`) must execute within strict isolation. Listeners and validation checks must wrap their logic in `try-finally` blocks to guarantee that third-party database timeouts or API errors do not fail the parent RTP queue pipelines. The claim-plugin integrations formerly shipped as `RTP_ClaimPluginIntegrations` are now bundled in `rtp-plugin` (see ADR-019); third-party addons should consult `RTP_ExampleAddon` as the canonical template.

### 1.2 Non-Blocking Operations
- **Strict Adherence to Non-Blocking IO:** Addons interacting with land-protection or biome APIs must perform verifications asynchronously. Pausing the main thread or halting a teleport queue while waiting on a synchronous third-party claim check is strictly prohibited.

### 1.3 Memory Management
- **Scoped Memory Bounding:** Addons must not maintain unbounded collections of active teleport states. They must limit their footprint by dynamically querying the core `MemoryTracker` or leveraging scoped, lock-free caches to maintain performance consistency.
