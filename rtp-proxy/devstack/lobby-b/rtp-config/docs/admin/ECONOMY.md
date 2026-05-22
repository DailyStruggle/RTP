# Economy Configuration Reference (`economy.yml`)

This document provides a detailed reference for all configuration options available in `plugins/RTP/economy.yml`.

> 📎 **Requirement**: This configuration requires **Vault** and a compatible economy plugin. If Vault is absent, these settings are ignored.

---

## Costs & Refunds

| Key | Type | Default | Description |
|---|---|---|---|
| `price` | Double | `50.0` | Base cost for a standard `/rtp` command. |
| `priceOther` | Double | `200.0` | Cost to teleport another player via `/rtp <player>`. |
| `paramsPrice` | Double | `0.0` | Additional cost for using custom parameters (e.g., `region:`, `shape:`, `vert:`). |
| `biomePrice` | Double | `0.0` | Additional cost for specifying a biome target (`biome:`). |
| `refundOnCancel` | Boolean | `true` | If `true`, the cost is refunded and the cooldown is reset if the teleport is cancelled (e.g., by movement). |
| `balanceFloor` | Double | `0.0` | Minimum balance a player must retain after paying. Prevents players from going into debt. |

---

## Versioning
- `version`: Internal config version (do not change).
