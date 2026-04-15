# RTP

## Overview

A spigot plugin for random teleportation.
https://www.spigotmc.org/resources/rtp.94812/

The goal of this plugin is function over form,

* unique geometry to produce flat random distributions and support a learning algorithm
* unique methods to learn from placements and avoid redundant selections, e.g. trying the same ocean thousands of times
* region-based design, supporting any number of rtp regions per world
* per-world and per-region configuration and teleport permissions
* adjustment of region and world settings by command or by config file
* adjustment of all plugin messages, except for utility logs

## Documentation

To help new developers understand our codebase and make safe changes, please refer to our documentation files:
- [Requirements Overview](REQUIREMENTS.md) - High-level functional, non-functional (safety & performance), and system requirements for the plugin.
- [System Architecture and Safety-Critical Design](DESIGN.md) - Overview of the plugin's fail-safe design, bounded execution guarantees, queue systems, and deterministic algorithms.
- [Architecture Overview](ARCHITECTURE.md) - Learn about the multi-module structure, platform adapters, and core logic.
- [Contributing Guidelines](CONTRIBUTING.md) - Find setup instructions, code formatting rules, and testing guidelines.

## Project and Directory Structure

To help you navigate the repository, here is a brief overview of the Gradle projects and root directories:

- **`rtp-api/`** - The API interface and shared models. External integrations and addons should compile against this module.
- **`rtp-core/`** - The platform-agnostic core logic. Handles region management, shape algorithms, queues, database, and memory tracking.
- **`rtp-plugin/`** - The main entry point for the plugin. Manages configurations, commands, and bridges the core logic with the appropriate platform adapter.
- **`rtp-spigot/`**, **`rtp-paper/`**, **`rtp-folia/`** - Platform-specific adapter modules to maximize performance and compatibility across different server types.
- **`addons/`** - Example subprojects demonstrating how to extend RTP via its API (e.g., Iris generation, Glide integration, claim plugin checks).
- **`lang/`** - Default localization, messages, and language files for the plugin.
- **`Python Test Scripts/`** - Python scripts utilized for external testing and visualizing random distribution models and geometric math.
- **`gradle/`** - Gradle wrapper files ensuring a consistent build environment across machines.

## Shapes

The plugin has some pretty useful shapes by default -

circle, using an exponential distribution of 0.1, 1.0, 10.0
![zu5gW62]( https://user-images.githubusercontent.com/28832622/210043913-fd624a9f-8bdd-45de-b877-6a5f5e3bf40a.png )

square, using an exponential distribution of 0.1, 1.0, 10.0
![3mrkKh1]( https://user-images.githubusercontent.com/28832622/210043922-4d94e3d6-e829-4adc-a21a-74cce484f8e6.png )

circle, using a normal distribution
![SUGBQk3]( https://user-images.githubusercontent.com/28832622/210043926-5c5013cf-032e-444c-9397-e381c17a4752.png )

square, using a normal distribution
![pzu9j63]( https://user-images.githubusercontent.com/28832622/210043956-df964dde-4c70-460b-a377-ffd49a365e69.png )

rectangle, using a flat distribution and a rotation
![3Yw2tBj]( https://user-images.githubusercontent.com/28832622/210043964-ca9725b8-be25-4e3c-a460-90f8b81326cb.png )

This plugin also allows arbitrary shape addition via API calls.

Check the addons directory for examples on adding shapes, biome methods, claim plugin integrations, and commands
