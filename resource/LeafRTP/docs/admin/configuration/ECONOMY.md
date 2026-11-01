# Economy Configuration Reference (`economy.yml`)

This document provides a detailed reference for all configuration options available in `plugins/RTP/economy.yml`.

> 📎 **Requirement**: This configuration requires **Vault** and a compatible economy plugin. If Vault is absent, these settings are ignored.

---

## Updating Settings

You can update economy settings through:
1. **In-game admin menu**: Run `/rtp admin` or `/rtp menu` -> click **Economy**.
2. **Command line**: Use `/rtp config economy <key>=<value>` (e.g. `/rtp config economy price=25.0`).
3. **Direct editing**: Edit `economy.yml` on disk and run `/rtp reload`.

> 📎 See [IN_GAME_CONFIG.md](IN_GAME_CONFIG.md) for full menu and command navigation details.

---

## Costs & Refunds

| Key | Type | Default | Description |
|---|---|---|---|
| `price` | Double | `50.0` | Base cost for a standard `/rtp` command. |
| `priceOther` | Double | `200.0` | Cost to teleport another player via `/rtp <player>`. |
| `paramsPrice` | Double | `1000000000.0` | Additional cost for using custom parameters (e.g., `region:`, `shape:`, `vert:`). Default high price acts as a soft disable unless deliberately lowered. |
| `biomePrice` | Double | `5000.0` | Additional cost for specifying a biome target (`biome:`). |
| `refundOnCancel` | Boolean | `true` | If `true`, the cost is refunded and the cooldown is reset if the teleport is cancelled (e.g., by movement). |
| `balanceFloor` | Double | `0.0` | Minimum balance a player must retain after paying. Prevents players from going into debt. |

---

## Versioning
- `version`: Internal config version (do not change).
