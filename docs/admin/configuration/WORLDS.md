# World Configuration Reference (`definitions/worlds/*.yml`)

A world file is **routing, not tuning**. Each file in the `definitions/worlds/` folder answers one question: when a player runs `/rtp` from this world (or targets it with `world=<name>`), which [region](REGIONS.md) answers? The filename (without `.yml`) must match the world name.

Everything about *where and how* the player lands belongs to the region, not to the world: the destination world, the distance (`radius` / `centerRadius`), the shape, the vertical window, the price, the biome rules. That split is deliberate. A world can point at any region, several worlds can share one region, and a single world can be served by several regions (permission tiers via `requirePermission` / `override`, or an explicit `region=<name>` on the command). A radius attached to the world could express none of that.

So to change teleport distance, edit the region this world points at - see [Region Size: `radius` and `centerRadius`](REGIONS.md#region-size-radius-and-centerradius) - or point the world at a different region.

The region you map to may live in a *different* world: a region's own `world` key decides where players actually land, so a world file can redirect `/rtp` to a region that teleports the player elsewhere.

If a world has no config file yet, RTP creates one based on `definitions/worlds/default.yml` - **do not delete `default.yml`**. A world added at runtime that is not recognized will be available on the next restart.

---

## Updating Settings

You can update world mappings through:
1. **In-game admin menu**: Run `/rtp admin` or `/rtp menu` -> click **Worlds**.
2. **Command line**: Use `/rtp config world <worldname> <key>=<value>` (e.g. `/rtp config world world region=custom_region`).
3. **Direct editing**: Edit `definitions/worlds/<world>.yml` on disk and run `/rtp reload`.

> 📎 See [IN_GAME_CONFIG.md](IN_GAME_CONFIG.md) for full menu and command navigation details.

With the `rtp.info` permission, inspect a world's settings with `/rtp info` or `/rtp info world=<name>`.

---

## Settings

| Key | Type | Default | Description |
|---|---|---|---|
| `region` | String | `"default"` | The default region used when a player runs `/rtp` while in this world. |
| `requirePermission` | Boolean / String | `false` | If `true`, players need the `rtp.worlds.<worldname>` permission to use RTP in this world. Can also be set to `"@config"` to inherit from `config.yml`. |
| `override` | String | `"[0]"` | The world to redirect to if a player lacks permission for this world. Supports `[0]`, `[1]`, `[2]` placeholders. |

---

## Versioning
- `version`: Internal config version (do not change).
