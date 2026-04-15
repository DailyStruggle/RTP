# Stakeholders and Actors

This document defines the actors who interact with the RTP plugin, their roles, and their primary goals. Requirements in the module `REQUIREMENTS.md` files are written from the perspective of satisfying these actors.

---

## Actors

### 1. Server Administrator
**Who:** The operator of a Bukkit-derived Minecraft server (Spigot, Paper, or Folia) who installs and configures RTP.

**Goals:**
- Configure one or more teleport regions per world with independent shapes, distributions, and permission nodes.
- Ensure teleportation does not cause server lag or chunk memory leaks.
- Integrate RTP with existing land-protection plugins (GriefPrevention, WorldGuard, Towny) to prevent players from landing in claimed areas.
- Optionally charge players an economy cost per teleport via Vault.
- Reload or adjust region settings at runtime without restarting the server.

**Primary requirements:** REQ-RTP-F-001 through REQ-RTP-F-011, REQ-RTP-NF-001, REQ-RTP-NF-002.

---

### 2. End-User Player
**Who:** A player on the Minecraft server who executes the `/rtp` command (or a region-specific variant).

**Goals:**
- Receive a teleport response within 0–2 ticks (imperceptibly fast).
- Land in a safe, accessible location — not inside a block, underwater, in a claimed area, or in a disallowed biome.
- Understand why a teleport was denied (permission, cost, cooldown) via a clear message.

**Primary requirements:** REQ-RTP-F-001, REQ-RTP-F-007, REQ-RTP-F-011.

---

### 3. Addon Developer
**Who:** A third-party plugin developer who extends RTP by implementing custom shapes, vertical adjustors, biome filters, or claim-check hooks via `rtp-api`.

**Goals:**
- Compile against a stable, versioned API (`rtp-api`) without depending on internal core classes.
- Register custom geometry, validation logic, or commands without forking the plugin.
- Receive clear exceptions and pipeline guarantees so that bugs in their addon do not corrupt RTP's core execution.
- Rely on semantic versioning to know when an API update is breaking.

**Primary requirements:** REQ-API-F-001 through REQ-API-F-004, REQ-API-NF-001, REQ-API-NF-002, REQ-API-ARCH-001 through REQ-API-ARCH-004.

---

### 4. Core Contributor
**Who:** A developer who contributes to `rtp-core`, `rtp-api`, or a platform adapter module.

**Goals:**
- Understand the architectural boundaries between modules (no platform imports in core, no blocking calls in core/api).
- Add features or fix bugs without breaking the API contract for addon developers.
- Write tests that are automatically enforced by CI (architecture rules, unit tests, traceability check).
- Follow the requirement → traceability → test workflow when adding new requirements.

**Primary requirements:** All REQ-CORE-ARCH-* and REQ-API-ARCH-* requirements; see also `CONTRIBUTING.md` and `TRACEABILITY.md`.

---

### 5. Server Platform (System Actor)
**Who:** The underlying server software — Spigot, Paper, or Folia — that RTP runs on.

**Goals / Constraints imposed on RTP:**
- **Spigot:** All world/chunk operations must occur on the main thread or via the Bukkit scheduler.
- **Paper:** Async chunk loading APIs (`getChunkAtAsync`) are available and preferred.
- **Folia:** Each world region runs on its own thread; tasks must be dispatched to the correct regional scheduler. Cross-region calls are forbidden.

**Primary requirements:** REQ-RTP-NF-002, REQ-SPIGOT-ARCH-*, REQ-PAPER-ARCH-*, REQ-FOLIA-ARCH-*.

---

## Stakeholder–Requirement Coverage Summary

| Actor | Key Requirement Prefixes |
|---|---|
| Server Administrator | REQ-RTP-F, REQ-RTP-NF, REQ-CORE-F, REQ-SPIGOT-F |
| End-User Player | REQ-RTP-F-001, REQ-RTP-F-007, REQ-RTP-F-011 |
| Addon Developer | REQ-API-F, REQ-API-NF, REQ-API-ARCH |
| Core Contributor | REQ-CORE-ARCH, REQ-API-ARCH |
| Server Platform | REQ-RTP-NF-002, REQ-SPIGOT-ARCH, REQ-PAPER-ARCH, REQ-FOLIA-ARCH |
