# Item Compatibility Single-Server Devstack

First-class single-server devstack fixture for verifying third-party item plugin compatibility (`ItemsAdder`, `Oraxen`, `Nexo`, `HeadDatabase`, `CustomModelData`).

## Purpose
Exercises the GUI addon's prefix-routed reflection resolver (`BukkitCustomItemResolver`) against real Bukkit plugin lifecycles on a single Paper server without the overhead of proxy and multi-backend network topologies.

## Topology
A single Paper 1.21.11 instance (`backend-compat`) running on `127.0.0.1:25565`:
- Loads `LeafRTP` and `LeafRTPGuiAddon`.
- Loads `MockItemsAdder`, `MockOraxen`, `MockNexo`, and `MockHeadDatabase` from `helpers/MockItemPlugins`.
- Seeds a custom `guimenu.yml` utilizing `ia:`, `oraxen:`, `nexo:`, `hdb:`, and `MATERIAL:CMD` icon specifications.

## Running the Verification
Both scripts automatically tear down the container on exit unless `-Keep` / `--keep` is passed.

From PowerShell:
```powershell
.\test-item-compat.ps1
.\test-item-compat.ps1 -Keep   # leave container running after verification
```
Or from Bash:
```bash
./test-item-compat.sh
./test-item-compat.sh --keep   # leave container running after verification
```

Manual tear down (when started with `-Keep` / `--keep`):
```powershell
.\test-item-compat.ps1 -Down
```
