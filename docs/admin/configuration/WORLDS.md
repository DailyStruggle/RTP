# World Configuration Reference (`worlds/*.yml`)

Each file in the `worlds/` folder maps a world to its default random teleportation region and permission settings. The filename (without `.yml`) must match the world name.

A world file answers one question: when a player runs `/rtp` from this world (or targets it with `world=<name>`), which [region](REGIONS.md) should be used? Each world points at exactly one default region. If a world has no config file yet, RTP copies and renames `default.yml` - **do not delete `default.yml`**. A world added at runtime that is not recognized should be available on the next restart.

The region you map to may live in a *different* world: a region's own `world` key decides where players actually land, so a world file can redirect `/rtp` to a region that teleports the player elsewhere.

---

## Editing

The recommended way to edit a world mapping is **in-game via the menu**: `/rtp menu` -> **Admin panel -> Config editor** -> pick the Worlds file, then the world and key. It validates the value, writes the file, and reloads for you.

You can also edit the file on disk and run `/rtp reload worlds`, or change a key at runtime:

```
/rtp config worlds <worldname> region=<regionname>
```

With the `rtp.info` permission, inspect a world's settings with `/rtp info` or `/rtp info world=<name>`.

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
