# Security Policy

## Supported Versions

Only the latest release of RTP receives security fixes. Older versions are not backported.

| Version | Supported |
|---------|-----------|
| Latest  | ✅ Yes    |
| Older   | ❌ No     |

## Reporting a Vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

If you discover a security vulnerability in RTP, please report it privately so it can be assessed and patched before public disclosure.

### How to Report

Use GitHub's private vulnerability reporting:
1. Go to the [Security tab](../../security) of this repository.
2. Click **"Report a vulnerability"**.
3. Fill in the details: affected version, reproduction steps, and potential impact.

Alternatively, contact the maintainer directly via the SpigotMC resource page private message system:
🔗 https://www.spigotmc.org/resources/rtp.94812/

### What to Include

- RTP version (`/rtp version` output)
- Server platform and version (`/version` output)
- A clear description of the vulnerability and its potential impact
- Steps to reproduce or a proof-of-concept (if safe to share)

## Response Timeline

| Stage | Target |
|-------|--------|
| Acknowledgement | Within 72 hours |
| Initial assessment | Within 7 days |
| Patch release (if confirmed) | Within 30 days |

## Scope

This policy covers:
- Core modules: `rtp-api`, `rtp-core`
- Backend platform adapters: `rtp-bukkit`, `rtp-paper`, `rtp-folia`, `rtp-fabric`, `rtp-neoforge`
- SPI and subsystem modules: `commands-api`, `effects-api`, `maps-api`, `metrics-api`, `anvil-api`, `tags-api`, `yaml-api`
- Proxy / network modules: `rtp-proxy-common`, `rtp-proxy-velocity`
- Release deliverable assemblies: `rtp-plugin` (LeafRTP and LeafRTP Pro jars)

The `addons/` directory contains first-party and example integrations. Vulnerabilities in external third-party plugins integrated via the addon API (GriefPrevention, WorldGuard, Vault, etc.) should be reported to their respective upstream maintainers.

## Out of Scope

- Vulnerabilities in Minecraft itself, the underlying JVM, or host operating system
- Upstream vulnerabilities in server software (Spigot, Paper, Folia, Fabric Loader, NeoForge)
- Issues caused by operator misconfiguration or insecure permissions setups
- Denial-of-service vectors that require operator-level (`OP`) permissions to trigger
- Untrusted third-party addons executing outside the official API boundaries

## Vulnerability Disclosure History

| Advisory ID / CVE | Severity | Affected Versions | Fixed In | Summary | Published Date |
|---|---|---|---|---|---|
| *None* | - | - | - | No public vulnerabilities recorded to date. | - |

When a vulnerability is confirmed and patched, it will be published via a GitHub Security Advisory and recorded in the table above with its CVE identifier (if assigned), CVSS severity score, affected versions, fixed version, and an operator advisory summary.
