# Changelog - RTP GUI Addon

All notable changes to the RTP GUI addon (the DonutSMP-style destination picker)
are documented here. This file covers **only** the addon under
`addons/LeafRTPGuiAddon/`; changes to RTP itself live in the repository-root
`CHANGELOG.md`.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.0.0] - Unreleased

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
