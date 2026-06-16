# World Configuration Reference (`worlds/*.yml`)

Each file in the `worlds/` folder maps a world to its default random teleportation region and permission settings. The filename (without `.yml`) must match the world name.

---

## Settings

| Key | Type | Default | Description |
|---|---|---|---|
| `region` | String | `"default"` | The default region used when a player runs `/rtp` while in this world. |
| `requirePermission` | Boolean | `false` | If `true`, players need the `rtp.worlds.<worldname>` permission to use RTP in this world. |
| `override` | String | `"[0]"` | The world to redirect to if a player lacks permission for this world. Supports `[0]`, `[1]`, `[2]` placeholders. |

---

## Versioning
- `version`: Internal config version (do not change).
