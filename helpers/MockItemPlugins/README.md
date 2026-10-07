# MockItemPlugins

Standalone helper module producing lightweight mock stub plugins for third-party item management plugins:
- **ItemsAdder** (`CustomStack.getInstance(id).getItemStack()`)
- **Oraxen** (`OraxenItems.getItemById(id)`)
- **Nexo** (`NexoItems.itemFromId(id)`)
- **HeadDatabase** (`HeadDatabaseAPI#getItemHead(id)`)

## Purpose
Enables testing LeafRTP's reflection-based soft-depend integrations (ADR-026) in automated unit tests and devstack environments without requiring paid plugin purchases, commercial licenses, or shaded runtime dependencies.

## Output Artifacts
Running `./gradlew :helpers:MockItemPlugins:buildAllStubs` builds four standalone plugin JARs into `build/libs/`:
1. `MockItemsAdder.jar` (plugin name: `ItemsAdder`)
2. `MockOraxen.jar` (plugin name: `Oraxen`)
3. `MockNexo.jar` (plugin name: `Nexo`)
4. `MockHeadDatabase.jar` (plugin name: `HeadDatabase`)

## Usage
- **Unit Tests:** `rtp-gui-bukkit` includes `:helpers:MockItemPlugins` as a `testImplementation` dependency to verify that `BukkitCustomItemResolver` resolves item stacks through reflection when the respective plugins are enabled.
- **Devstack:** The single-server compatibility stack (`devstack/docker-compose.compat.yml` and `devstack/test-item-compat.ps1`) copies these four JARs into the Paper server's `plugins/` directory to verify end-to-end compatibility at runtime.
