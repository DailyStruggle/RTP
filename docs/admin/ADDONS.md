# Addons

RTP can be extended with **addons** - small companion plugins/mods that hook into RTP's
public API (`rtp-api`) to add behavior such as extra safety verifiers, custom shapes, or
the GUI destination picker. This page explains, from an operator's point of view, the
ways an addon can be loaded, how the addons RTP bundles inside its own jar behave, and
how to turn an addon off.

> Writing your own addon? See the [Addon Development](https://github.com/DailyStruggle/RTP/wiki/Addon-Development)
> and [Addon Loading](https://github.com/DailyStruggle/RTP/wiki/Addon-Loading) wiki pages,
> and the worked [Example Addon](https://github.com/DailyStruggle/RTP/wiki/Example-Addon).
> This page is for server administrators.

---

## The three load paths

An addon can reach RTP in three ways. You usually do not need to think about which one a
given addon uses - they all end up registered the same way - but it helps to know the
differences when troubleshooting.

| Load path | Where the file goes | How it loads | When to use it |
|---|---|---|---|
| **Standalone plugin/mod** | `plugins/` (Bukkit/Paper/Folia) or `mods/` (Fabric/NeoForge) | The server's own plugin/mod loader enables it; it then registers itself with RTP. | An addon distributed as its own download. |
| **Addons folder** | `plugins/RTP/addons/<addon>.jar` | RTP scans this folder on startup and loads each jar through its own addon classloader. | Dropping an addon jar in without it being a full server plugin. |
| **Bundled in the RTP jar** | (already inside `RTP.jar`) | RTP unpacks it into `plugins/RTP/addons/` on first run, then loads it from there (see below). | Demo/companion addons RTP ships with, e.g. the GUI picker. |

All three end with the addon's code on RTP's classpath and its `RTPAddon` registered, so
features (commands like `/rtp gui`, safety verifiers, renderers) behave identically
regardless of how the addon arrived.

> **Note on the addons folder vs. a standalone plugin.** An addon dropped into
> `plugins/RTP/addons/` is **not** enabled as a separate server plugin - the server's
> plugin loader never sees it, so anything that addon declares in its own `plugin.yml`
> (its own top-level commands, listeners registered on plugin-enable, etc.) does not run.
> RTP loads only the addon's `rtp-api` entry point. Addons that need a command therefore
> register it on RTP's command tree (so it appears under RTP's namespace, e.g.
> `/rtp gui`), which works on every load path and every platform.

---

## Addons bundled inside the RTP jar

RTP ships one or more companion addons **inside its own jar** so the showcase works out
of the box with no second download. The GUI destination-picker is the current example.

### First-run auto-extraction

On startup RTP checks for the `plugins/RTP/addons/` folder:

- **If the folder does not exist yet** (a fresh install), RTP creates it and unpacks each
  bundled addon jar into it, then loads the folder normally.
- **If the folder already exists** (any later run, or you created it yourself), RTP does
  **not** unpack anything - it simply loads whatever jars are present.

This keying on "folder does not yet exist" is deliberate: it means the bundled addons are
extracted exactly once, on the very first run, and never silently re-appear afterwards.

> RTP never overwrites a jar you already have. Even on first-run extraction, a target
> file that already exists is left untouched.

### Turning a bundled addon off

Because a bundled addon is extracted to a real file in `plugins/RTP/addons/`, you have two
permanent opt-outs:

1. **Delete the extracted jar** from `plugins/RTP/addons/` (and restart). The folder now
   exists, so RTP will not re-extract it - it stays gone.
2. **Configure it off.** Most addons create their own config under `plugins/RTP/`. For the
   GUI picker, the relevant knob lives in its config file (see the GUI addon's README);
   setting the menu style back to classic makes bare `/rtp` teleport immediately again
   without removing the addon.

Deleting the entire `addons/` folder is **not** a reliable off switch for bundled addons:
on the next start the folder is missing again, so RTP treats it as a fresh install and
re-extracts the bundled jars. Delete the individual jar instead.

---

## The bundled GUI demo

The bundled GUI addon (LeafRTPGuiAddon) turns bare `/rtp` into a chest-style destination
picker and adds an explicit `/rtp gui` opener. It is **on by default** so a new server
gets the visual menu immediately.

- `/rtp gui` (permission `rtp.gui`) opens the picker explicitly. It is registered on RTP's
  command tree, so it is available on every platform and every load path.
- Bare `/rtp` opens the picker too, and **falls back to a classic teleport** when no menu
  renderer is available (e.g. wrong platform) or the caller is not a resolvable player -
  it never silently does nothing.
- To revert bare `/rtp` to the classic immediate teleport while keeping the addon, change
  the menu style in the GUI addon's config (see the [Menu](https://github.com/DailyStruggle/RTP/wiki/Menu) wiki page).
- To remove the GUI entirely, delete its jar from `plugins/RTP/addons/` and restart.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Bundled addon keeps coming back after I delete the folder | Deleting the whole `addons/` folder triggers a fresh first-run extraction | Delete the individual jar inside `addons/`, not the folder |
| `/rtp gui` says no menu / just teleports | No GUI renderer on this runtime, or the player is offline/unresolvable | Confirm the GUI addon jar is present in `addons/`; it falls back to classic teleport by design |
| An addon's own command does not appear | The addon was loaded via the `addons/` folder, where its own `plugin.yml` does not run | Use the command the addon registers on RTP's tree (e.g. `/rtp gui`), or install the addon as a standalone plugin if it requires that |
| Bundled addon never extracted | `plugins/RTP/addons/` already existed on first run | Expected - RTP only auto-extracts when the folder is absent. Add the jar manually if you want it |

---

## See also

- [FAQ.md](FAQ.md) - Development & Addons section.
- [Addon Loading](https://github.com/DailyStruggle/RTP/wiki/Addon-Loading) - how RTP discovers, loads, and unloads addons.
- [Addon Troubleshooting](https://github.com/DailyStruggle/RTP/wiki/Addon-Troubleshooting) - diagnosing addons that fail to load.
- [configuration/INTEGRATIONS.md](configuration/INTEGRATIONS.md) - bundled claim-plugin
  integrations (folded into the jar, not loaded as addons).
