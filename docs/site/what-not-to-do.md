# What NOT to do!

Because common sense is not common, this page collects the things that go wrong with this
plugin. They fall into two very different buckets, so they are split below:

- **[This will break the mod entirely](#this-will-break-the-mod-entirely)** -
  misconfigurations that stop teleports from working at all (or make the plugin behave
  incorrectly).
- **[Why is this running slowly?](#why-is-this-running-slowly)** - setups that "work" but
  make teleports lag or stall the server.

!!! tip "Read the config files first"
    PLEASE read the configuration files in your RTP folder (also visible in this
    repository's resource folder) before asking for setup support. A lot of information is
    easily accessed and well described within the configuration files.

---

## This will break the mod entirely

These mistakes prevent teleportation from working correctly.

!!! warning "Don't blacklist every biome (or whitelist zero biomes)"
    If no biome is eligible, every teleport attempt has nowhere valid to land, so
    teleportation fails outright. Always leave at least one usable biome.

!!! warning "Don't run conflicting `/wild` or `/rtp` providers together"
    Mixing plugins that both register `/wild` or `/rtp` without resolving the command
    conflict means the wrong plugin (or no plugin) may answer the command. Resolve the
    command conflict before running both.

!!! warning "Don't delete the default region/world configuration"
    LeafRTP uses the default region and world entries as the template for any new world or
    region. Removing them leaves the plugin with nothing to build from, and it will not
    behave correctly. See [Intended usage](intended-usage.md).

!!! warning "Don't change `definitions/regions/default.yml` to `world_nether` just to teleport in the Nether"
    Changing `world: "world_nether"` on the `default` region redirects **all** unconfigured worlds (including your main overworld) into the Nether. Instead, create a dedicated region file (`definitions/regions/nether.yml` with `world: "world_nether"`, `vert.maxY: 120`, and `vert.requireSkyLight: false`) and map `definitions/worlds/world_nether.yml` to `region: "nether"` via `/rtp editor`, `/rtp admin setup`, or `/rtp config worlds world_nether region=nether`.

!!! warning "Don't rely on `worldBorderOverride: true` when `shape.radius` with unit suffixes will do"
    `worldBorderOverride: true` is generally not recommended because it replaces your entire `shape:` block with a plain square and discards your `shape.name`, `shape.radius`, and inner spawn-exclusion hole (`shape.centerRadius`). Instead, set `shape.radius` in `/rtp editor` or `definitions/regions/<region>.yml` using an explicit distance unit suffix (such as `2500b` or `5km`).

---

## Why is this running slowly?

These setups work, but they make teleports expensive - either lagging the player who
teleports or stalling the whole server.

!!! warning "Don't grant `rtp.unqueued` (or wildcard `rtp.*`) to regular players"
    Out of the box, LeafRTP pre-generates and verifies safe locations asynchronously in the background (`cacheCap` in `definitions/regions/default.yml`), so `/rtp` pops a ready spot without main-thread or chunk-generation lag. Granting `rtp.unqueued` (or `rtp.*`) to default players forces every `/rtp` to bypass the pre-generated cache and run live on-demand chunk selection and loading, which causes server lag spikes when multiple players teleport. Keep default players on `rtp.use` only.

!!! warning "Don't pass dynamic parameter overrides to regular players"
    Passing dynamic override parameters (`shape=`, `radius=`, `centerX=`, `centerZ=`, etc. under the `rtp.params` permission) constructs an ephemeral, throwaway region on the fly. Because temporary regions are uncached and have no persistent spatial memory, every invocation forces on-demand chunk loading and real-time safety verification. Keep dynamic parameters restricted to staff testing, and configure permanent named regions for everyday player use.

!!! warning "Don't wire override parameters into player commands, signs, or portals"
    *Override* parameters (`shape=`, `radius=`, `centerX=`, ...) build a throwaway
    temporary region that is uncached and remembers no past failures, so every call pays
    full generation cost and can stall under load. Use them only to test settings, then
    bake them into a real named region and point players at a plain `/rtp`. (Targeting an
    existing region with `world=<name>` or `region=<name>` is fine - those stay cached.)
    See [Intended usage](intended-usage.md).

!!! warning "Don't enable `syncLoading: true` or raise `viewDistanceSelect` in `advanced/performance.yml`"
    Setting `syncLoading: true` forces chunk loading onto the main server thread (keep `syncLoading: false`), and setting `viewDistanceSelect` above `0` loads surrounding chunks on every candidate check during search (keep `viewDistanceSelect: 0`). Because `autoTune: true` scales background compute relative to your configured baselines rather than overriding them, these settings compose together: tightening `syncAllottedTime` (e.g. `2`-`5` ms) or `maxMSPT` (`35.0`-`40.0` ms) with `autoTune: true` tightens the backend tick budget (invisible to players while the `hot` queue stays populated), while `viewDistanceSelect: 0` (surrounding chunks load after arrival) and raising `pregeneratedPreference` toward `0.8`-`1.0` (biasing toward already-generated terrain on disk) reduce per-candidate I/O work.

!!! warning "Don't over-restrict biomes"
    Blacklisting most biomes (or whitelisting only a few) leaves so few valid landing
    spots that the engine has to reject many candidates before finding one, which causes
    major lag during teleportation even when it eventually succeeds. (Restrict *all* of
    them and you cross into the "breaks entirely" bucket above.)

---

## See also

- [Intended usage](intended-usage.md) - the model these anti-patterns violate.
- [Quick start](../admin/QUICK_START.md) -
  the recommended end-to-end setup sequence.
- [Performance](../admin/configuration/PERFORMANCE.md) - the
  symptom-to-knob tuning playbook.
