# Devstack Hologram Testing Guide

This directory documents how to verify and test floating holograms and countdown displays across the devstack environments.

---

## 1. Zero-Dependency Native TextDisplay (Default on Paper / Folia 1.21)

Both `lobby-a`, `lobby-b`, `backend-a`, and `backend-b` run Paper 1.21+. On these servers, LeafRTP automatically binds to native vanilla `TextDisplay` entities (`BukkitTextDisplayHologramProvider`) via entity packets without requiring any third-party plugin.

### In-Game Verification:
Connect to the proxy (e.g. `localhost:25565` or direct to backend `localhost:25566`) and run:
```text
/rtp test hologram [seconds]
```
*(Example: `/rtp test hologram seconds:10`)*

**Expected Result:**
1. A billboard text display spawns 2 blocks in front of the player at eye height.
2. The display shows:
   - `LeafRTP Hologram Test`
   - `Provider: BukkitTextDisplayHologramProvider`
   - `Despawning in <N>s` (ticking down each second)
3. After the duration expires, the entity is cleanly despawned and removed from the world.

---

## 2. External Plugin Verification (DecentHolograms / HolographicDisplays)

To test soft-dependency priority and compatibility with external hologram managers:
1. Download `DecentHolograms.jar` (or `HolographicDisplays.jar`).
2. Drop the jar into `devstack/lobby-a/plugins/` (and/or `devstack/backend-a/plugins/`).
3. Restart or reload the server container (`docker compose restart lobby-a`).
4. Run `/rtp test hologram` in-game.

**Expected Result:**
- The command resolves and displays `Provider: DecentHologramsChecker` (or `HolographicDisplaysChecker`), verifying that LeafRTP prefers the external provider over the fallback TextDisplay.
