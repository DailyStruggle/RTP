# LeafRTPClaimAddon

Claim/protection-plugin integrations for RTP, packaged as a standalone addon.

When a random teleport lands inside a claim owned by a supported plugin, this addon rerolls the
destination instead of dropping the player on protected land. Each integration is a safety
*verifier* registered through RTP's public hook facade (ADR-026); none of them run inline from a
command or teleport-pipeline stage, which preserves REQ-RTP-S-003.

## Supported plugins

WorldGuard, GriefDefender, GriefPrevention, Towny Advanced, SaberFactions, FactionsBridge, Lands,
RedProtect, Residence, CrashClaim, HuskClaims, KingdomsX.

## Install

Drop `LeafRTPClaimAddon-<version>.jar` into `plugins/RTP/addons/`. On first run RTP extracts
`integrations.yml` into the plugin data folder; flip the relevant `reroll<Plugin>` toggle to `true`
and run `/rtp reload`.

A checker only registers when **both** its `integrations.yml` toggle is enabled **and** the named
plugin is installed and enabled, so leaving the jar in place with everything off costs nothing.

## Platform

Every checker is `org.bukkit.*`-based, so the addon only acts on Bukkit-family servers
(Spigot/Paper/Folia and forks). It self-gates on `PlatformFamily.BUKKIT` queried from RTP and is a
no-op on Fabric / NeoForge.

## Layout

Single Bukkit module. `RTPClaimAddon` is the `RTPAddon` entry point (discovered via
`META-INF/services`); `ClaimIntegrations` owns the registration logic; one `*Checker` per plugin
answers "is this location claimed?" (reflectively or against the plugin's compile-only API).
