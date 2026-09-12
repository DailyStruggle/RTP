# ADR-091 — Automated Headless Client for Devstack via Mineflayer

**Status:** Proposed  
**Date:** 2026-09-08  

## Context

The cross-server RTP test fixture (`devstack/`, `ADR-036`) boots 1 Redis + 2 Velocity proxies + 2 Paper lobbies + 3 backends (Paper / Folia / Folia) on a Docker Compose network. The test suite (`run-acceptance.sh` / `run-acceptance.ps1`) verifies cluster bootstrap, Redis heartbeats, kill-mid-flight reservation reaping, and emergency killswitches.

However, the pivotal end-to-end user scenario—`roundtrip`—is currently a **blocking manual checkpoint**:
- `test_roundtrip` in `run-acceptance.sh` prints instructions to the terminal and invokes `read -r _`, requiring a human operator to launch a Minecraft client, manually join `localhost:25577`, issue `/server backend-b`, `/server backend-c`, `/rtp`, verify teleportation visually, and press `<Enter>` to resume script execution.
- This manual gate prevents running the acceptance suite autonomously in GitHub Actions (`CI/CD`), leaving cross-server token handoffs, proxy player transfers, and backend arrival event handling unverified on automated pull-request builds.

## Decision

Automate the devstack `roundtrip` scenario by introducing a lightweight, headless protocol client using **Mineflayer** (`devstack/clients/mineflayer-bot.js`), fully integrated into `run-acceptance.sh` and `run-acceptance.ps1`.

### 1. Headless Bot Specification (`devstack/clients/mineflayer-bot.js`)

The bot operates headlessly using Node.js without a graphical client:
- **Connection Target:** `localhost:25577` (Velocity proxy-a) in offline/unauthenticated mode with player username `RtpAcceptanceBot`.
- **Pre-Flight Backend Seeding:**
  - On join to `lobby-a`, the bot dispatches `/server backend-b`, waits for the server transfer packet and spawn event, then dispatches `/server backend-c`, and returns to `lobby-a`. This seeds all backend player caches.
- **Teleport Trigger:** Dispatches `/rtp`.
- **Packet & Lifecycle Assertions:**
  - **Transfer Detection:** Listens for the Velocity player transfer packet / backend switch to verify the proxy routed the player to the selected destination backend.
  - **Position Verification:** Listens for the player position update packet (`move` event) from the destination backend. Asserts that coordinates $(X, Z)$ fall strictly within the destination region's bounding box and $Y \ge 62$ (above sea level).
  - **Latency SLA:** Asserts that the total duration from `/rtp` dispatch to final destination packet arrival is $< 5000\text{ ms}$.
- **Exit Contract:** On successful verification, logs JSON evidence to stdout and exits with status code `0`. On timeout ($> 30\text{ s}$) or invalid coordinates, logs failure diagnostics and exits with non-zero exit code.

### 2. Devstack Acceptance Harness Integration

Update `run-acceptance.sh` and `run-acceptance.ps1`:
- Replace the manual `read -r _` pause with:
  ```bash
  node "$scriptDir/clients/mineflayer-bot.js" --proxy localhost:25577 --timeout 30
  ```
- Capture the bot's stdout and write it into `acceptance-evidence.log` under tag `roundtrip.bot`.
- Continue subsequent verification of Redis reservation token deletion (`rtp:net:reservation:*`) and backend audit log lines (`JoinTriggerSource`, `redeem`).

### 3. GitHub Actions CI Integration

Provide a dedicated GitHub Actions workflow (`.github/workflows/devstack-acceptance.yml`) that:
1. Builds the plugin and proxy shadow jars (`:rtp-plugin:shadowJar`, `:rtp-proxy:rtp-proxy-velocity:shadowJar`).
2. Boots the Docker Compose devstack.
3. Installs dependencies (`npm --prefix devstack/clients install`).
4. Executes `./run-acceptance.sh --scenario all --no-logs`.
5. Uploads `acceptance-evidence.log` and per-service container logs on completion.

## Alternatives Considered

| Alternative | Why Rejected |
|-------------|--------------|
| **Manual Human Checkpoint (Status Quo)** | Prevents continuous integration in GitHub Actions; human verification is sporadic and does not guard against regression in PRs. |
| **Embedded Java Protocol Client (Steve-Client / Bespoke Netty)** | High implementation complexity; maintaining protocol encryption, compression, and packet serializers in Java across MC version bumps requires substantial ongoing maintenance. |
| **Server-Side Fake Player Injection (Bukkit/Carpet)** | Completely bypasses Velocity proxy forwarding, network handshake secrets, and proxy transfer packets, failing to test the true network-mode contract. |

## Consequences

- **Positive:**
  - The cross-server `/rtp` round-trip becomes 100% automated and headless.
  - Pull requests can validate multi-server token negotiation and Folia teleportation in GitHub Actions without human intervention.
  - Measures true end-to-end player network latency from initial command to client coordinate confirmation.
- **Negative / Trade-offs:**
  - Devstack acceptance testing introduces a Node.js runtime requirement (`node`, `npm`) on the runner host. (Ubuntu GitHub Actions runners have Node.js pre-installed).
  - Mineflayer dependencies must be kept compatible with the server's targeted Minecraft protocol versions.

## References

- [ADR-036: Network Mode (Multi-Server, Multi-Proxy RTP)](ADR-036-network-mode-multi-server-multi-proxy.md)
- [devstack/README.md](../../devstack/README.md)
- [devstack/run-acceptance.sh](../../devstack/run-acceptance.sh)
- [ADR-090: Test-Driver, Synthetic MCA Generator, and Dimension Benchmark Dataset](ADR-090-test-driver-and-synthetic-mca-architecture.md)
