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

---

## Why is this running slowly?

These setups work, but they make teleports expensive - either lagging the player who
teleports or stalling the whole server.

!!! warning "Don't run the plugin for the first time on a production server"
    On first run the plugin is unlikely to be configured for your use case, and an
    unconfigured region has no warmed cache, so the first teleports do all the expensive
    chunk loading and safety checking on demand. Do a test run on your PC first, configure
    your regions, then warm them with `/rtp scan start region=<name>` before players
    arrive. See the [Quick start](../admin/QUICK_START.md) scan step.

!!! warning "Don't wire override parameters into player commands, signs, or portals"
    *Override* parameters (`shape=`, `radius=`, `centerX=`, ...) build a throwaway
    temporary region that is uncached and remembers no past failures, so every call pays
    full generation cost and can stall under load. Use them only to test settings, then
    bake them into a real named region and point players at a plain `/rtp`. (Targeting an
    existing region with `world=<name>` or `region=<name>` is fine - those stay cached.)
    See [Intended usage](intended-usage.md).

!!! warning "Don't over-restrict biomes"
    Blacklisting most biomes (or whitelisting only a few) leaves so few valid landing
    spots that the engine has to reject many candidates before finding one, which causes
    major lag during teleportation even when it eventually succeeds. (Restrict *all* of
    them and you cross into the "breaks entirely" bucket above.)

---

## See also

- [Intended usage](intended-usage.md) - the model these anti-patterns violate.
- [Quick start](../admin/QUICK_START.md) -
  warm the cache so first teleports are not slow.
- [Performance](../admin/configuration/PERFORMANCE.md) - the
  symptom-to-knob tuning playbook.
