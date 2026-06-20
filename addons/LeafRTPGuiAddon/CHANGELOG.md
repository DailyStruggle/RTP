# Changelog - RTP GUI Addon

All notable changes to the RTP GUI addon (the DonutSMP-style destination picker)
are documented here. This file covers **only** the addon under
`addons/LeafRTPGuiAddon/`; changes to RTP itself live in the repository-root
`CHANGELOG.md`.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

### [1.0.1] - unreleased

### Fixed

- The Fabric/NeoForge GUI now opens (instead of falling back to classic teleport)
  on the `ServiceLoader` load path even when the platform accessor's typed
  `getServer()` returns null across the addon's child classloader. The renderers
  now resolve the live `ServerPlayer` directly from RTP's own player registry
  (`RTP.serverAccessor.getPlayer(uuid).handle()`) as the primary path - which does
  not depend on `getServer()` resolving cleanly - and only fall back to the bound
  `MinecraftServer`'s player list when the registry lookup fails. The previously
  silent `getServer()` resolution now logs the exact failing branch (accessor
  null / reflection threw / null server / type mismatch) for diagnosis.
- Renderer discovery no longer aborts on a modded (Fabric/NeoForge) runtime when
  the wrong-platform renderer's classes are absent. `registerServiceRenderers()`
  used `ServiceLoader.load(...)`, whose lazy iterator resolves each provider's
  constructor inside `hasNext()` (the loop condition); on NeoForge/Fabric the
  `BukkitMenuRenderer` entry threw `NoClassDefFoundError: org/bukkit/event/Listener`
  from that condition, escaping the per-entry `try/catch` and aborting the whole
  iteration before the live renderer registered - so `/rtp gui` and bare `/rtp`
  fell back to classic teleport. Discovery now reads the `META-INF/services`
  files directly and loads each class in its own `try/catch`, so an unloadable
  wrong-platform provider is skipped and the present platform's renderer still
  binds.
- The Fabric and NeoForge chest renderers now render on the `ServiceLoader`
  load path (the `plugins/RTP/addons/` folder or the bundled-in-jar demo), not
  just when installed as a standalone mod. `FabricMenuRenderer` /
  `NeoForgeMenuRenderer` were registered only by their mod entry points
  (`RTPGuiFabricInitializer` / `RTPGuiNeoForgeMod`), which never run on the
  `ServiceLoader` path, so `/rtp gui` and bare `/rtp` resolved no renderer and
  fell back to the classic teleport. Both are now published via a `MenuRenderer`
  `META-INF/services` descriptor (merged alongside the Bukkit one), each resolves
  the live `MinecraftServer` from RTP's own platform accessor (`getServer()`)
  when the entry point has not run, and `isAvailable()` is gated on the platform
  loader being present (`FabricLoader` / `FMLLoader`) rather than a captured
  server reference, so the shared `chest` style key never lets the wrong-platform
  renderer clobber the live one and registration is immune to startup ordering.
  Menu clicks are handled inside the server-side `DestinationPickerMenu`
  container, so no separate listener is needed.
- The Bukkit chest menu now responds to clicks on the `ServiceLoader` load path
  instead of opening as a dead display. The `DestinationPickerListener` (which
  translates a click into a teleport and cancels item removal to close the
  item-dupe vector) was registered only in `RTPGuiBukkitPlugin.onEnable()` - the
  `JavaPlugin` entry point that never runs when the addon is loaded via
  `ServiceLoader`. `BukkitMenuRenderer` now registers the listener through a
  shared, once-per-JVM guarded `registerClickListener(host)` on its SPI / no-arg
  load path, and `RTPGuiBukkitPlugin.onEnable()` routes through the same guard so
  the standalone-plugin path cannot double-register it.

## [1.0.0] - 06/19/2020

### Added

- Fabric and NeoForge support (Minecraft 26.x). The picker now opens as a
  native server-side container (chest) screen on both loaders, rendering the
  same destination model and routing clicks through the same validated
  `MenuActions.submit` teleport path as the Bukkit renderer.
  - New `rtp-gui-fabric` carrier (Loom-unobf, JDK 25): `RTPGuiFabricInitializer`,
    `FabricMenuRenderer`, `DestinationPickerMenu`.
  - New `rtp-gui-neoforge` carrier (ModDevGradle, JDK 25): `RTPGuiNeoForgeMod`,
    `NeoForgeMenuRenderer`, `DestinationPickerMenu`.
- Single multi-loader assembly jar (`rtp-gui`) that bundles `rtp-gui-common`
  and every platform renderer and carries all three loader descriptors
  (`plugin.yml`, `fabric.mod.json`, `META-INF/neoforge.mods.toml`). Each loader
  starts the matching entry point, which registers that platform's renderer at
  runtime. The Fabric/NeoForge carriers are gated by `-PexcludeJdk25` /
  `-PexcludeNeoforge` for JDK-21-only or network-constrained build hosts.
- SPI-style renderer discovery: `MenuRenderer` implementations published via
  `META-INF/services` are auto-registered by `RTPGuiCommonAddon`, so the
  present platform's renderer self-binds even where its entry point did not run.

### Notes

- The `chest` `menuStyle` key is shared across all platforms, so existing
  `guimenu.yml` files need no change to get the GUI on Fabric/NeoForge.
