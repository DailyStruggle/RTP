# `schematics-api` — Platform-Neutral Sponge (`.schem`) & Litematica (`.litematic`) Decoder and Planner

Zero-platform-dependency Java 17 library for decoding Sponge Schematic v2/v3 (`.schem`, `.schematic`) and Litematica (`.litematic`) files, caching decoded structures in memory, and planning 3D world block placements (`BlockDelta`, `BlockPlacement`) and container block-entity restorations (`PlacedBlockEntity`).

## Key Capabilities

- **`SpongeSchematicDecoder`:** Dependency-free GZIP-NBT decoder supporting Sponge v2/v3 (varint-packed `BlockData` / `Blocks.Data`, `Palette`, `BlockEntities`, `Offset`) and Litematica `.litematic` (`Regions`, negative region `Size` axes, `BlockStatePalette` property maps, cross-long bit-packed `BlockStates` `long[]`, and `TileEntities`).
- **`SchematicPlacementPlanner`:** Anchor-aware (`BOTTOM_CENTER`, `SURFACE_CENTER`, `CENTER`, `ORIGIN`) coordinate planner producing `List<BlockPlacement>`, `List<BlockDelta>`, and `List<PlacedBlockEntity>` from explicit `(x, y, z)` coordinates or any `SchematicCoordinate` implementation (such as `RTPLocation`).
- **`SchematicFileLoader`:** Thread-safe normalized-path schematic cache with off-tick decode and `ServerAccessors` failure auditing (`S-004`, `S-005`).
- **Package compatibility:** Preserves `io.github.dailystruggle.rtp.api.schematic` and `io.github.dailystruggle.rtp.api.platform.BlockDelta` package coordinates so `LeafRTP`, `LeafSleuth`, and third-party consumers share identical binary types.
