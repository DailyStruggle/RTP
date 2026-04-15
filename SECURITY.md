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

This policy covers the `rtp-api`, `rtp-core`, `rtp-spigot`, `rtp-paper`, and `rtp-folia` modules.

The `addons/` directory contains example integrations. Vulnerabilities in third-party plugins integrated via the addon API (GriefPrevention, WorldGuard, Vault, etc.) should be reported to their respective maintainers.

## Out of Scope

- Vulnerabilities in Minecraft itself, the JVM, or the server platform (Spigot/Paper/Folia)
- Issues caused by misconfiguration of the server or other plugins
- Denial-of-service attacks that require operator-level (`OP`) permissions to trigger

## Vulnerability Disclosure History

No vulnerabilities have been publicly disclosed for RTP as of 2026-04-15.

When a vulnerability is confirmed and patched, it will be recorded here with its CVE identifier (if assigned), affected versions, fixed version, and a brief description. This record is maintained so server operators can audit their exposure history.
