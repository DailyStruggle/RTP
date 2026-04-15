# Project Architecture

For a high-level overview of the plugin's fail-safe guarantees and bounded execution architecture, see [System Architecture and Safety-Critical Design](DESIGN.md).

The RTP (Random Teleport) plugin is built with a multi-module architecture to ensure scalability, ease of maintenance, and compatibility with various server software environments such as Spigot, Paper, and Folia.

## Module Breakdown

### Core Modules
* **rtp-api**: Contains the interfaces, APIs, and shared models used by the plugin and external integrations. Addon developers should compile against this module.
* **rtp-core**: Contains the core logic of the plugin. This includes region management, random location selection algorithms (shapes), queue management, database interactions, and memory tracking. It is agnostic of the specific server platform (Spigot, Paper, Folia).

### Platform Adapters
These modules implement platform-specific features to maximize performance on their respective servers while maintaining a unified core codebase.
* **rtp-spigot**: The adapter for standard Spigot servers, implementing Bukkit/Spigot-specific event handling and chunk loading.
* **rtp-paper**: The adapter for Paper servers, utilizing Paper-specific APIs for enhanced performance, such as asynchronous chunk loading.
* **rtp-folia**: The adapter for Folia servers, handling Folia's unique region-based multithreading to ensure teleports and tasks run safely on the correct regional thread.

### Plugin Entry Point
* **rtp-plugin**: This module contains the main `JavaPlugin` implementation (`RTPBukkitPlugin.java`). It bridges the gap between `rtp-core` and the platform adapters. It handles configuration initialization, command registration, and standard Bukkit event listeners.

### Addons
* **addons**: A directory containing subprojects that integrate RTP with external plugins. These examples (e.g., `RTP_ClaimPluginIntegrations`, `RTP_Glide`, `RTP_Iris_integration`) demonstrate how to utilize `rtp-api` to extend the plugin's capabilities.

## Key Concepts

* **Regions**: The world is divided into manageable teleport regions. Each region handles its own teleport queue and shape parameters.
* **Shapes**: Mathematical structures (circle, square, rectangle) governing how random points are generated. They support various distributions (normal, flat, exponential) for tailored teleport selection algorithms.
* **Queue System**: Pre-generating and validating random locations before they are needed ensures teleports are instant for the end-user.
