# Operator Documentation Index

**Current Plugin Version:** `@version@`

Welcome to the LeafRTP operator documentation catalog. This index routes server administrators and network operators through all operational guides, command references, configuration specifications, and visual tools.

---

## Getting Started & Operations

| Guide | Description |
|-------|-------------|
| [Quick Start](QUICK_START.md) | 10-minute cold-start guide covering installation, default regions, and essential permissions. |
| [Web Workspace & Editor Guide](WEB_EDITOR_GUIDE.md) | Operational runbook for the browser-based 2D cartography canvas, live staging diff inspector, and ephemeral `/rtp editor` workflow (ADR-104). |
| [Command Reference](COMMANDS.md) | Exhaustive reference of every `/rtp` subcommand, parameter, syntax, and permission node. |
| [Operator Runbook](RUNBOOK.md) | Incident response procedures structured by **Symptom → Diagnosis → Resolution**. |
| [Actions & Arenas](ACTIONS.md) | Event triggers, world hooks, and arena boundaries. |
| [Prefabs](PREFABS.md) | Ready-to-deploy configuration blueprints for typical server setups. |
| [Common Recipes](RECIPES.md) | Practical recipes for popular server configurations and gameplay modes. |
| [FAQ](FAQ.md) | Frequently asked questions regarding performance, claim hooks, and biomes. |
| [Migration Guide](MIGRATION.md) | Step-by-step upgrade instructions when transitioning from legacy setups or third-party plugins. |
| [Addons](ADDONS.md) | Ecosystem addons, GUI menus, and claim integrations. |

---

## Configuration Reference

The complete reference suite for all configuration files lives under [`configuration/`](configuration/CONFIGURATION.md):

- [Configuration Overview](configuration/CONFIGURATION.md)
- [Configuration Lifecycle](configuration/CONFIG_LIFECYCLE.md)
- [Core Settings (`config.yml`)](configuration/CORE_CONFIG.md)
- [Regions (`regions/*.yml`)](configuration/REGIONS.md)
- [Worlds (`worlds/*.yml`)](configuration/WORLDS.md)
- [Schematics (`schematics/`)](configuration/SCHEMATICS.md)
- [Safety & Hazards (`safety.yml`)](configuration/SAFETY.md)
- [Performance & Caches (`performance.yml`)](configuration/PERFORMANCE.md)
- [Economy (`economy.yml`)](configuration/ECONOMY.md)
- [Events & Visual Effects (`events.yml`)](configuration/EVENTS_AND_EFFECTS.md)
- [Integrations & Claims](configuration/INTEGRATIONS.md)
- [Messages (`messages.yml`)](configuration/MESSAGES.md)
- [Language & Locales](configuration/LANGUAGE.md)
- [Logging](configuration/LOGGING.md)
- [Metrics & Health Signals](configuration/METRICS.md)
- [Spatial Memory TTL](configuration/TTL.md)

---

## Multi-Server & Proxy Mode

Admin guides for multi-backend network setups behind Velocity or BungeeCord proxies live under [`proxies/`](proxies/INDEX.md):

- [Proxy Mode Overview](proxies/INDEX.md)
- [Network Configuration (`network.yml`)](proxies/CONFIGURATION.md)
