package io.github.dailystruggle.mapsapi.world3d;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Platform-neutral SPI for delivering client-side 2D/3D block drawing code and packet-budgeted
 * {@code .lca} / {@code .dlsh} multi-span and $16 \times 16 \times 16$ sub-chunk frames
 * ({@code maps-api-ADR-003}, {@code anvil-api-ADR-003}, {@code ADR-115}).
 *
 * <p>Designed around two complementary transport modes so {@code LeafMap} replaces BlueMap while
 * feeding the {@code LeafConfig} World Map panel:
 * <ol>
 *   <li><b>Packet-Constrained Stream ({@link #DEFAULT_MAX_PACKET_BYTES} = {@code 30,000 B}):</b>
 *       Slices $16^3$ vertical sub-chunks in $O(1)$ via {@code .lca}'s prefixed {@code uint16[24]}
 *       section pointer table (`~120-165 B` per active section, `2 B` per uniform section) and
 *       {@code LOD-3} (`S=64`, `~10.9 KB`) surface horizon headers so an entire 3D camera neighborhood
 *       or {@code LeafSleuth} hotspot volume (`3x3x3` sections $\approx 3.9\text{ KB}$) fits inside a
 *       single Minecraft plugin-message / signed-relay packet.</li>
 *   <li><b>Local Zero-Parse Archive Fallback:</b> When operating locally or across whole-world
 *       (`16 km`) datasets exceeding packet budgets, exposes the memory-mapped {@code .lca} /
 *       {@code .dlsh} / {@code .sqlite} archive descriptor (`46.7 us` cold chunk load, `130.8x`
 *       smaller than BlueMap {@code .prbm} meshes).</li>
 * </ol>
 *
 * @governedBy maps-api-ADR-003
 * @governedBy anvil-api-ADR-003
 * @governedBy ADR-115
 * @trace REQ-RTP-MAP-001
 * @trace REQ-RTP-MAP-002
 * @trace S-004
 * @trace S-005
 */
public interface WorldBlockFeed {

    /**
     * Default safe byte ceiling (`30,000 B`) for a single plugin-message (`32,767 B` protocol limit)
     * or signed loopback relay frame.
     */
    int DEFAULT_MAX_PACKET_BYTES = 30_000;

    /**
     * Compact procedural $16 \times 16$ block texture recipe descriptor delivered inside
     * {@link ClientDrawingKernel} so {@code window.LeafMapClientKernel} can synthesize crisp
     * pixel-art top and side textures, cutout alpha, and biome grass/foliage LUT tinting on the
     * client without shipping external PNG atlases.
     *
     * @param baseRgb24      primary 24-bit RGB color ({@code 0x000000..0xFFFFFF})
     * @param secondaryRgb24 secondary/grain/mortar 24-bit RGB color ({@code 0x000000..0xFFFFFF})
     * @param topSideDelta   signed brightness delta between top and side faces (`-64..64`)
     * @param patternId      procedural synthesis kernel selector (`0=NOISE_STONE`, `1=DEEPSLATE_GRAIN`,
     *                       `2=BRICK_MORTAR`, `3=PLANKS`, `4=LOG_BARK_RINGS`, `5=GRASS_TOP`,
     *                       `6=LEAVES_CUTOUT`, `7=WATER_CAUSTIC`, `8=GLASS_FRAME`, `9=ORE_FLECKS`,
     *                       `10=CROSS_FLORA`, `11=CONTAINER_TOP`, `12=REDSTONE_LAMP`)
     * @param alphaMode      alpha mode (`0=OPAQUE`, `1=CUTOUT`, `2=TRANSLUCENT`, `3=EMISSIVE`)
     * @param biomeTintMode  biome LUT tint selector (`0=NONE`, `1=GRASS_LUT`, `2=FOLIAGE_LUT`, `3=WATER_LUT`)
     */
    record BlockTextureRecipe(
            int baseRgb24,
            int secondaryRgb24,
            int topSideDelta,
            int patternId,
            int alphaMode,
            int biomeTintMode
    ) {
        public BlockTextureRecipe {
            baseRgb24 = baseRgb24 & 0xFFFFFF;
            secondaryRgb24 = secondaryRgb24 & 0xFFFFFF;
            topSideDelta = Math.max(-64, Math.min(64, topSideDelta));
            if (patternId < 0 || patternId > 15) {
                throw new IllegalArgumentException("patternId must be in [0, 15], got: " + patternId);
            }
            if (alphaMode < 0 || alphaMode > 3) {
                throw new IllegalArgumentException("alphaMode must be in [0, 3], got: " + alphaMode);
            }
            if (biomeTintMode < 0 || biomeTintMode > 3) {
                throw new IllegalArgumentException("biomeTintMode must be in [0, 3], got: " + biomeTintMode);
            }
        }
    }

    /**
     * Tier 1 chunk-resolution ($16 \times 16$-block cell) biome, elevation, and water horizon sample
     * streamed between the Tier 0 rough biome layout backdrop and Tier 2 $1:1$ {@code .lca} block
     * sections.
     *
     * @param chunkX         chunk X coordinate
     * @param chunkZ         chunk Z coordinate
     * @param biomeId        compact biome palette ID (`0..255`)
     * @param medianSurfaceY median solid surface Y coordinate in this chunk (`-64..319`)
     * @param waterDepth     median water column depth (`0..64`, `0` when dry)
     * @param topBlockId     dominant surface block state ID (e.g. {@code "minecraft:grass_block"})
     */
    record ChunkBiomeHorizonCell(
            int chunkX,
            int chunkZ,
            int biomeId,
            int medianSurfaceY,
            int waterDepth,
            String topBlockId
    ) {
        public ChunkBiomeHorizonCell {
            Objects.requireNonNull(topBlockId, "topBlockId");
            biomeId = biomeId & 0xFF;
            medianSurfaceY = Math.max(-64, Math.min(319, medianSurfaceY));
            waterDepth = Math.max(0, Math.min(255, waterDepth));
            if (topBlockId.isBlank()) {
                throw new IllegalArgumentException("topBlockId must not be blank");
            }
        }
    }

    /**
     * Self-contained client-side 2D/3D voxel &amp; sub-chunk drawing kernel delivered by {@code LeafMap}
     * so host panels (such as {@code LeafConfig}) can decode and draw {@code .lca} sub-chunks,
     * multi-span overhangs, subterranean cutaways, and vanilla `[1.0, 0.8, 0.6, 0.5]` + AO shading
     * without embedding a separate rendering engine.
     *
     * @param providerId            provider identifier (e.g. {@code "leafmap"})
     * @param version               kernel schema version (e.g. {@code "lca-v1.0"})
     * @param clientDrawingModuleJs self-contained JavaScript module exposing {@code window.LeafMapClientKernel}
     * @param blockPaletteColors    shared region/world block-state ID to 24-bit RGB color dictionary
     * @param blockPaletteRecipes   compact per-block procedural $16 \times 16$ texture recipe table
     */
    record ClientDrawingKernel(
            String providerId,
            String version,
            String clientDrawingModuleJs,
            Map<String, Integer> blockPaletteColors,
            Map<String, BlockTextureRecipe> blockPaletteRecipes
    ) {
        public ClientDrawingKernel(
                String providerId,
                String version,
                String clientDrawingModuleJs,
                Map<String, Integer> blockPaletteColors
        ) {
            this(providerId, version, clientDrawingModuleJs, blockPaletteColors, Map.of());
        }

        public ClientDrawingKernel {
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(clientDrawingModuleJs, "clientDrawingModuleJs");
            Objects.requireNonNull(blockPaletteColors, "blockPaletteColors");
            Objects.requireNonNull(blockPaletteRecipes, "blockPaletteRecipes");
            if (providerId.isBlank()) {
                throw new IllegalArgumentException("providerId must not be blank");
            }
            blockPaletteColors = Collections.unmodifiableMap(new LinkedHashMap<>(blockPaletteColors));
            blockPaletteRecipes = Collections.unmodifiableMap(new LinkedHashMap<>(blockPaletteRecipes));
        }
    }

    /**
     * Compact {@code .lca} $16 \times 16 \times 16$ (`4,096`-voxel) vertical sub-chunk frame
     * addressed by the chunk's prefixed {@code uint16[24]} section pointer table (`sectionY` in `-4..19`).
     *
     * <p>{@code paletteBlockIds.get(0)} represents {@code palette[0]} (the dominant state, e.g.
     * {@code minecraft:air} or {@code minecraft:deepslate}), which is implicitly zero-filled on the
     * client (`90.8%` of voxels skipped) and omitted from {@code deltaGapRuns}.
     *
     * @param world                  world name
     * @param chunkX                 chunk X coordinate
     * @param sectionY               vertical section index (`-4..19`, covering `Y = -64..319`)
     * @param chunkZ                 chunk Z coordinate
     * @param curveId                2-bit traversal curve selector (`0:YZX`, `1:H_3`, `2:Y+H_2`, `3:XZY`)
     * @param octreeOccupancyMask64  64-bit ($4^3$) sub-cube non-`palette[0]` occupancy mask
     * @param paletteBlockIds        frequency-sorted local section palette (`palette[0]` is implicit fill)
     * @param deltaGapRuns           packed 1-byte `gap4 | len4` delta-gap runs for minority states `1..|P|-1`
     * @param skyLightConstant       uniform SkyLight value (`0..15`), or `-1` when {@code skyLightGrayPlanes} is present
     * @param blockLightConstant     uniform BlockLight value (`0..15`), or `-1` when {@code blockLightGrayPlanes} is present
     * @param skyLightGrayPlanes     4-bit reflected binary Gray-code ($G = L \oplus (L \gg 1)$) SkyLight runs along `10:Y+H_2`
     * @param blockLightGrayPlanes   4-bit reflected binary Gray-code BlockLight runs
     */
    record SubChunkSectionFrame(
            String world,
            int chunkX,
            int sectionY,
            int chunkZ,
            int curveId,
            long octreeOccupancyMask64,
            List<String> paletteBlockIds,
            byte[] deltaGapRuns,
            byte skyLightConstant,
            byte blockLightConstant,
            byte[] skyLightGrayPlanes,
            byte[] blockLightGrayPlanes
    ) {
        public SubChunkSectionFrame {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(paletteBlockIds, "paletteBlockIds");
            Objects.requireNonNull(deltaGapRuns, "deltaGapRuns");
            Objects.requireNonNull(skyLightGrayPlanes, "skyLightGrayPlanes");
            Objects.requireNonNull(blockLightGrayPlanes, "blockLightGrayPlanes");
            if (paletteBlockIds.isEmpty()) {
                throw new IllegalArgumentException("paletteBlockIds must contain at least palette[0]");
            }
            if (curveId < 0 || curveId > 3) {
                throw new IllegalArgumentException("curveId must be in [0, 3]");
            }
            paletteBlockIds = List.copyOf(paletteBlockIds);
            deltaGapRuns = deltaGapRuns.clone();
            skyLightGrayPlanes = skyLightGrayPlanes.clone();
            blockLightGrayPlanes = blockLightGrayPlanes.clone();
        }

        @Override
        public byte[] deltaGapRuns() {
            return deltaGapRuns.clone();
        }

        @Override
        public byte[] skyLightGrayPlanes() {
            return skyLightGrayPlanes.clone();
        }

        @Override
        public byte[] blockLightGrayPlanes() {
            return blockLightGrayPlanes.clone();
        }

        /**
         * Returns the true wire byte footprint of this $16^3$ section frame (`2 B` when uniform,
         * or header + palette varints + delta-gap runs + non-uniform Gray light planes).
         */
        public int wireByteSize() {
            if (paletteBlockIds.size() == 1 && deltaGapRuns.length == 0
                    && skyLightConstant >= 0 && blockLightConstant >= 0) {
                return 2;
            }
            return 16 + (paletteBlockIds.size() * 2)
                    + deltaGapRuns.length
                    + skyLightGrayPlanes.length
                    + blockLightGrayPlanes.length;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SubChunkSectionFrame that)) return false;
            return chunkX == that.chunkX
                    && sectionY == that.sectionY
                    && chunkZ == that.chunkZ
                    && curveId == that.curveId
                    && octreeOccupancyMask64 == that.octreeOccupancyMask64
                    && skyLightConstant == that.skyLightConstant
                    && blockLightConstant == that.blockLightConstant
                    && world.equals(that.world)
                    && paletteBlockIds.equals(that.paletteBlockIds)
                    && Arrays.equals(deltaGapRuns, that.deltaGapRuns)
                    && Arrays.equals(skyLightGrayPlanes, that.skyLightGrayPlanes)
                    && Arrays.equals(blockLightGrayPlanes, that.blockLightGrayPlanes);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(world, chunkX, sectionY, chunkZ, curveId,
                    octreeOccupancyMask64, paletteBlockIds, skyLightConstant, blockLightConstant);
            result = 31 * result + Arrays.hashCode(deltaGapRuns);
            result = 31 * result + Arrays.hashCode(skyLightGrayPlanes);
            result = 31 * result + Arrays.hashCode(blockLightGrayPlanes);
            return result;
        }

        @Override
        public String toString() {
            return "SubChunkSectionFrame[world=" + world
                    + ", chunkX=" + chunkX
                    + ", sectionY=" + sectionY
                    + ", chunkZ=" + chunkZ
                    + ", curveId=" + curveId
                    + ", octreeOccupancyMask64=" + octreeOccupancyMask64
                    + ", paletteBlockIds=" + paletteBlockIds
                    + ", deltaGapRuns=" + Arrays.toString(deltaGapRuns)
                    + ", skyLightConstant=" + skyLightConstant
                    + ", blockLightConstant=" + blockLightConstant
                    + ", skyLightGrayPlanes=" + Arrays.toString(skyLightGrayPlanes)
                    + ", blockLightGrayPlanes=" + Arrays.toString(blockLightGrayPlanes)
                    + "]";
        }
    }

    /**
     * A packet-budgeted batch of 3D world data containing a progressive surface/overhang horizon
     * frame and/or prefixed $16^3$ {@link SubChunkSectionFrame} slices, plus the local zero-parse
     * {@code .lca} / {@code .dlsh} archive descriptor when running locally or exceeding packet limits.
     *
     * @param world                    world name
     * @param packetIndex              0-based packet sequence index
     * @param totalPackets             total packets in the logical transfer
     * @param lodLevel                 progressive LOD tier (`3` = `S=64` horizon, `2` = `S=16`, `1` = `S=4`, `0` = exact `1x1`)
     * @param centerChunkX             focus chunk X coordinate
     * @param centerChunkZ             focus chunk Z coordinate
     * @param minSectionY              minimum included vertical section index (`-4..19`)
     * @param maxSectionY              maximum included vertical section index (`-4..19`)
     * @param surfaceHorizonHeader     compact `DualLayerSpiralHilbertCurve` surface + overhang bitplane slice
     * @param subChunks                included $16^3$ vertical sub-chunk frames
     * @param chunkBiomeHorizon        Tier 1 chunk-resolution ($16 \times 16$) biome + elevation + water horizon cells
     * @param fitsInSinglePacket       {@code true} if {@code wireByteCount <= maxPacketBytes}
     * @param wireByteCount            total wire bytes in this packet frame
     * @param localFallbackArchivePath path to the local zero-parse `.lca` / `.dlsh` archive for instant local loading
     * @param coldChunkLoadMicros      empirical cold 1-chunk load latency in microseconds (`46.7 us` for `.lca`)
     * @param compressionRatioVsBlueMap compression ratio against equivalent BlueMap `.prbm` meshes (`130.8x` at `LOD-0`)
     */
    record PacketDataBatch(
            String world,
            int packetIndex,
            int totalPackets,
            int lodLevel,
            int centerChunkX,
            int centerChunkZ,
            int minSectionY,
            int maxSectionY,
            byte[] surfaceHorizonHeader,
            List<SubChunkSectionFrame> subChunks,
            List<ChunkBiomeHorizonCell> chunkBiomeHorizon,
            boolean fitsInSinglePacket,
            int wireByteCount,
            String localFallbackArchivePath,
            double coldChunkLoadMicros,
            double compressionRatioVsBlueMap
    ) {
        public PacketDataBatch(
                String world,
                int packetIndex,
                int totalPackets,
                int lodLevel,
                int centerChunkX,
                int centerChunkZ,
                int minSectionY,
                int maxSectionY,
                byte[] surfaceHorizonHeader,
                List<SubChunkSectionFrame> subChunks,
                boolean fitsInSinglePacket,
                int wireByteCount,
                String localFallbackArchivePath,
                double coldChunkLoadMicros,
                double compressionRatioVsBlueMap
        ) {
            this(
                    world,
                    packetIndex,
                    totalPackets,
                    lodLevel,
                    centerChunkX,
                    centerChunkZ,
                    minSectionY,
                    maxSectionY,
                    surfaceHorizonHeader,
                    subChunks,
                    List.of(),
                    fitsInSinglePacket,
                    wireByteCount,
                    localFallbackArchivePath,
                    coldChunkLoadMicros,
                    compressionRatioVsBlueMap
            );
        }

        public PacketDataBatch {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(surfaceHorizonHeader, "surfaceHorizonHeader");
            Objects.requireNonNull(subChunks, "subChunks");
            Objects.requireNonNull(chunkBiomeHorizon, "chunkBiomeHorizon");
            Objects.requireNonNull(localFallbackArchivePath, "localFallbackArchivePath");
            if (packetIndex < 0 || totalPackets < 1 || packetIndex >= totalPackets) {
                throw new IllegalArgumentException("Invalid packetIndex/totalPackets: " + packetIndex + "/" + totalPackets);
            }
            surfaceHorizonHeader = surfaceHorizonHeader.clone();
            subChunks = List.copyOf(subChunks);
            chunkBiomeHorizon = List.copyOf(chunkBiomeHorizon);
        }

        @Override
        public byte[] surfaceHorizonHeader() {
            return surfaceHorizonHeader.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PacketDataBatch that)) return false;
            return packetIndex == that.packetIndex
                    && totalPackets == that.totalPackets
                    && lodLevel == that.lodLevel
                    && centerChunkX == that.centerChunkX
                    && centerChunkZ == that.centerChunkZ
                    && minSectionY == that.minSectionY
                    && maxSectionY == that.maxSectionY
                    && fitsInSinglePacket == that.fitsInSinglePacket
                    && wireByteCount == that.wireByteCount
                    && Double.compare(that.coldChunkLoadMicros, coldChunkLoadMicros) == 0
                    && Double.compare(that.compressionRatioVsBlueMap, compressionRatioVsBlueMap) == 0
                    && world.equals(that.world)
                    && Arrays.equals(surfaceHorizonHeader, that.surfaceHorizonHeader)
                    && subChunks.equals(that.subChunks)
                    && chunkBiomeHorizon.equals(that.chunkBiomeHorizon)
                    && localFallbackArchivePath.equals(that.localFallbackArchivePath);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(
                    world,
                    packetIndex,
                    totalPackets,
                    lodLevel,
                    centerChunkX,
                    centerChunkZ,
                    minSectionY,
                    maxSectionY,
                    subChunks,
                    chunkBiomeHorizon,
                    fitsInSinglePacket,
                    wireByteCount,
                    localFallbackArchivePath,
                    coldChunkLoadMicros,
                    compressionRatioVsBlueMap);
            return 31 * result + Arrays.hashCode(surfaceHorizonHeader);
        }

        @Override
        public String toString() {
            return "PacketDataBatch[world=" + world
                    + ", packetIndex=" + packetIndex
                    + ", totalPackets=" + totalPackets
                    + ", lodLevel=" + lodLevel
                    + ", centerChunkX=" + centerChunkX
                    + ", centerChunkZ=" + centerChunkZ
                    + ", minSectionY=" + minSectionY
                    + ", maxSectionY=" + maxSectionY
                    + ", surfaceHorizonHeader=" + Arrays.toString(surfaceHorizonHeader)
                    + ", subChunks=" + subChunks
                    + ", chunkBiomeHorizon=" + chunkBiomeHorizon
                    + ", fitsInSinglePacket=" + fitsInSinglePacket
                    + ", wireByteCount=" + wireByteCount
                    + ", localFallbackArchivePath=" + localFallbackArchivePath
                    + ", coldChunkLoadMicros=" + coldChunkLoadMicros
                    + ", compressionRatioVsBlueMap=" + compressionRatioVsBlueMap
                    + "]";
        }
    }

    /**
     * Metadata descriptor for a mapped world/dimension (Overworld, Nether, End, or custom dimension)
     * providing parity with BlueMap, Dynmap, Pl3xMap, and Squaremap world selectors.
     *
     * @param world          canonical world folder/registry name (e.g. {@code "world"}, {@code "world_nether"})
     * @param displayName    human-readable world title
     * @param dimension      dimension environment ({@code "overworld"}, {@code "the_nether"}, {@code "the_end"})
     * @param minY           minimum build/section Y coordinate (e.g. {@code -64} or {@code 0})
     * @param maxY           maximum build/section Y coordinate (e.g. {@code 319} or {@code 255})
     * @param spawnX         world spawn block X
     * @param spawnY         world spawn block Y
     * @param spawnZ         world spawn block Z
     * @param borderCenterX  world border center X
     * @param borderCenterZ  world border center Z
     * @param borderSize     world border diameter in blocks
     * @param timeOfDayTicks world time of day in ticks ({@code 0..24000})
     * @param storming       whether rain/thunder is active
     * @param indexedRegions number of `.mca` / `.linear` / `.lca` regions indexed
     * @param indexedChunks  number of chunks indexed in the progressive multi-span cache
     */
    record WorldMapDescriptor(
            String world,
            String displayName,
            String dimension,
            int minY,
            int maxY,
            int spawnX,
            int spawnY,
            int spawnZ,
            double borderCenterX,
            double borderCenterZ,
            double borderSize,
            long timeOfDayTicks,
            boolean storming,
            int indexedRegions,
            int indexedChunks
    ) {
        public WorldMapDescriptor {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(dimension, "dimension");
            if (world.isBlank()) {
                throw new IllegalArgumentException("world must not be blank");
            }
        }

        /**
         * Creates a multi-server world descriptor whose {@link #world()} is prefixed with
         * {@code serverId + ":"} when {@code serverId} is non-blank and not {@code "local"}.
         */
        public static WorldMapDescriptor ofServer(
                String serverId,
                String world,
                String displayName,
                String dimension,
                int minY,
                int maxY,
                int spawnX,
                int spawnY,
                int spawnZ,
                double borderCenterX,
                double borderCenterZ,
                double borderSize,
                long timeOfDayTicks,
                boolean storming,
                int indexedRegions,
                int indexedChunks
        ) {
            String srv = (serverId == null || serverId.isBlank()) ? "local" : serverId.trim();
            String rawWorld = Objects.requireNonNull(world, "world").trim();
            String composite = ("local".equalsIgnoreCase(srv) || rawWorld.contains(":"))
                    ? rawWorld
                    : srv + ":" + rawWorld;
            return new WorldMapDescriptor(
                    composite, displayName, dimension,
                    minY, maxY, spawnX, spawnY, spawnZ,
                    borderCenterX, borderCenterZ, borderSize,
                    timeOfDayTicks, storming, indexedRegions, indexedChunks
            );
        }

        /**
         * Returns the upstream server identifier extracted from {@code serverId:world}, or
         * {@code "local"} when unprefixed.
         */
        public String serverId() {
            int colon = world.indexOf(':');
            if (colon > 0 && colon + 1 < world.length()) {
                String prefix = world.substring(0, colon).trim();
                if (!"minecraft".equalsIgnoreCase(prefix)) {
                    return prefix;
                }
            }
            return "local";
        }

        /**
         * Returns the bare world name without any {@code serverId:} prefix.
         */
        public String localWorldName() {
            int colon = world.indexOf(':');
            if (colon > 0 && colon + 1 < world.length()) {
                String prefix = world.substring(0, colon).trim();
                if (!"minecraft".equalsIgnoreCase(prefix)) {
                    return world.substring(colon + 1).trim();
                }
            }
            return world;
        }

        /**
         * Returns the canonical {@code serverId:world} composite key (or bare {@code world} for
         * {@code "local"}).
         */
        public String compositeWorldKey() {
            String srv = serverId();
            String bare = localWorldName();
            return "local".equalsIgnoreCase(srv) ? bare : srv + ":" + bare;
        }
    }

    /**
     * Descriptor for an upstream server aggregated into a multi-server {@code LeafMap} hub
     * ({@code leafmap-ADR-004}, {@code REQ-MAP-MULTI-001}).
     *
     * @param serverId        unique server identifier (e.g. {@code "survival-east"}, {@code "local"})
     * @param displayName     human-readable server label
     * @param worlds          worlds exposed by this server
     * @param pooledChunks    number of chunks currently held in the bounded `.lca` LRU pool
     * @param memoryBytesUsed L1 in-memory slab bytes attributed to this server
     * @param diskBytesUsed   L2 disk `.lca` archive bytes attributed to this server
     */
    record ServerMapDescriptor(
            String serverId,
            String displayName,
            List<String> worlds,
            int pooledChunks,
            long memoryBytesUsed,
            long diskBytesUsed
    ) {
        public ServerMapDescriptor {
            serverId = (serverId == null || serverId.isBlank()) ? "local" : serverId.trim();
            displayName = (displayName == null || displayName.isBlank()) ? serverId : displayName.trim();
            worlds = worlds == null ? List.of() : List.copyOf(worlds);
        }
    }

    /**
     * Component-level permission gates for a spatial overlay registered with {@code LeafMap}
     * ({@code maps-api-ADR-003}, {@code leafconfig-ADR-003}, {@code ADR-115}).
     *
     * <p>Allows peer plugins (such as {@code LeafRTP} or {@code LeafSleuth}) to separate read
     * visibility (e.g. drawing the RTP teleport donut or lag hotspot volume) from interactive
     * mutation capabilities (resizing outer/inner radii, dragging center coordinates, or invoking
     * remediation actions).
     *
     * @param viewPermission   permission required to view/render the overlay (empty = public)
     * @param resizePermission permission required to drag outer/inner radius or vertex grips
     * @param movePermission   permission required to drag the overlay center coordinate
     * @param actionPermission permission required to invoke overlay remediation or config actions
     */
    record OverlayPermissionGates(
            String viewPermission,
            String resizePermission,
            String movePermission,
            String actionPermission
    ) {
        public OverlayPermissionGates {
            viewPermission = viewPermission == null ? "" : viewPermission.trim();
            resizePermission = resizePermission == null ? "" : resizePermission.trim();
            movePermission = movePermission == null ? "" : movePermission.trim();
            actionPermission = actionPermission == null ? "" : actionPermission.trim();
        }

        public static OverlayPermissionGates viewOnly(String viewPermission) {
            return new OverlayPermissionGates(viewPermission, "op:false", "op:false", "op:false");
        }

        public static OverlayPermissionGates rtpRegionDefault() {
            return new OverlayPermissionGates(
                    "leafrtp.map.view",
                    "leafrtp.map.resize",
                    "leafrtp.map.move",
                    "leafrtp.admin.config"
            );
        }

        public static OverlayPermissionGates sleuthHotspotDefault() {
            return new OverlayPermissionGates(
                    "leafsleuth.map.view",
                    "op:false",
                    "op:false",
                    "leafsleuth.admin.remediate"
            );
        }

        public boolean canView(Predicate<String> permissionChecker) {
            if (viewPermission.isEmpty()) {
                return true;
            }
            if ("op:false".equalsIgnoreCase(viewPermission)) {
                return false;
            }
            return permissionChecker == null || permissionChecker.test(viewPermission);
        }

        public boolean canResize(Predicate<String> permissionChecker) {
            if ("op:false".equalsIgnoreCase(resizePermission) || resizePermission.isEmpty()) {
                return false;
            }
            return permissionChecker != null && permissionChecker.test(resizePermission);
        }

        public boolean canMove(Predicate<String> permissionChecker) {
            if ("op:false".equalsIgnoreCase(movePermission) || movePermission.isEmpty()) {
                return false;
            }
            return permissionChecker != null && permissionChecker.test(movePermission);
        }

        public boolean canAct(Predicate<String> permissionChecker) {
            if ("op:false".equalsIgnoreCase(actionPermission) || actionPermission.isEmpty()) {
                return false;
            }
            return permissionChecker != null && permissionChecker.test(actionPermission);
        }
    }

    /**
     * Spatial overlay descriptor registered by a peer plugin (such as {@code LeafRTP} or
     * {@code LeafSleuth}) directly with {@code LeafMap}, carrying geometry, config bindings,
     * and component-level {@link OverlayPermissionGates}.
     *
     * @param overlayId      stable overlay identifier (e.g. {@code "zone:default"} or {@code "hotspot:12,12"})
     * @param providerId     supplying plugin identifier (e.g. {@code "leafrtp"}, {@code "leafsleuth"})
     * @param providerName   human-readable supplier display name
     * @param label          human-readable overlay label
     * @param world          target world name
     * @param shape          geometric shape ({@code "CIRCLE"}, {@code "DONUT"}, {@code "SQUARE"}, {@code "POLYGON"}, {@code "VOLUME"}, {@code "HEATMAP"})
     * @param centerX        center block X coordinate
     * @param centerZ        center block Z coordinate
     * @param radius         outer radius in blocks
     * @param innerRadius    inner hole radius in blocks (for annular/donut regions)
     * @param color          symbolic color or hex string
     * @param targetFile     bound configuration file path (for resize/move writeback)
     * @param targetKey      bound configuration key path
     * @param snapStepBlocks coordinate grid snap step in blocks
     * @param permissions    component-level permission gates for viewing vs resizing/moving/acting
     * @param metadata       additional key/value attribution metadata
     */
    record InteractiveMapOverlay(
            String overlayId,
            String providerId,
            String providerName,
            String label,
            String world,
            String shape,
            double centerX,
            double centerZ,
            double radius,
            double innerRadius,
            String color,
            String targetFile,
            String targetKey,
            int snapStepBlocks,
            OverlayPermissionGates permissions,
            Map<String, String> metadata
    ) {
        public InteractiveMapOverlay {
            Objects.requireNonNull(overlayId, "overlayId");
            Objects.requireNonNull(providerId, "providerId");
            if (overlayId.isBlank()) {
                throw new IllegalArgumentException("overlayId must not be blank");
            }
            if (providerId.isBlank()) {
                throw new IllegalArgumentException("providerId must not be blank");
            }
            providerName = (providerName == null || providerName.isBlank()) ? providerId : providerName.trim();
            label = (label == null || label.isBlank()) ? overlayId : label.trim();
            world = (world == null || world.isBlank()) ? "world" : world.trim();
            shape = (shape == null || shape.isBlank()) ? "CIRCLE" : shape.trim().toUpperCase(Locale.ROOT);
            color = (color == null || color.isBlank()) ? "green" : color.trim();
            targetFile = targetFile == null ? "" : targetFile.trim();
            targetKey = targetKey == null ? "" : targetKey.trim();
            snapStepBlocks = Math.max(1, snapStepBlocks);
            permissions = permissions == null ? OverlayPermissionGates.rtpRegionDefault() : permissions;
            metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }

    /**
     * Returns the stable lowercase provider identifier (e.g. {@code "leafmap"}).
     */
    String providerId();

    /**
     * Returns the human-readable display name (e.g. {@code "LeafMap 3D LCA Engine"}).
     */
    String displayName();

    /**
     * Returns all worlds/dimensions currently exposed by the map engine.
     */
    default List<WorldMapDescriptor> worlds() {
        return List.of(new WorldMapDescriptor(
                "world", "Overworld", "overworld",
                -64, 319, 144, 68, -96, 0.0, 0.0, 16384.0, 6000L, false, 1, 1024
        ));
    }

    /**
     * Returns all servers aggregated into this map hub ({@code leafmap-ADR-004}, {@code REQ-MAP-MULTI-001}).
     */
    default List<ServerMapDescriptor> servers() {
        List<String> worldNames = worlds().stream()
                .map(WorldMapDescriptor::localWorldName)
                .distinct()
                .toList();
        return List.of(new ServerMapDescriptor("local", "Local Server", worldNames, 0, 0L, 0L));
    }

    /**
     * Returns all active {@link WorldMapMarkerSet} overlay layers for the specified world.
     *
     * @param world target world name (or {@code null} for all worlds)
     * @return immutable list of marker sets
     */
    default List<WorldMapMarkerSet> markerSets(String world) {
        return List.of();
    }

    /**
     * Returns visible online player markers for the specified world (excluding players hidden
     * by vanish, sneaking, Y-cutoff, or {@code /leafmap hide} privacy rules).
     *
     * @param world target world name (or {@code null} for all worlds)
     * @return immutable list of visible live player markers
     */
    default List<WorldMapMarker.LivePlayerMarker> livePlayers(String world) {
        return List.of();
    }

    /**
     * Returns the self-contained client-side 2D/3D block &amp; sub-chunk drawing kernel (`JS` code +
     * shared block palette) to embed or stream into web viewers and the {@code LeafConfig} Map Panel.
     */
    ClientDrawingKernel clientDrawingKernel();

    /**
     * Queries a packet-budgeted 3D neighborhood of $16^3$ sub-chunk frames and surface horizon
     * columns centered on {@code (centerBlockX, centerBlockY, centerBlockZ)}.
     *
     * @param world          target world name
     * @param centerBlockX   focus block X coordinate
     * @param centerBlockY   focus block Y coordinate
     * @param centerBlockZ   focus block Z coordinate
     * @param chunkRadius    horizontal chunk radius around the focus chunk (e.g. `1` for `3x3` chunks)
     * @param sectionRadiusY vertical $16$-block section radius around the focus Y section
     * @param maxPacketBytes maximum bytes allowed per packet frame (e.g. {@link #DEFAULT_MAX_PACKET_BYTES})
     * @return packet-budgeted batch with sub-chunk slices and local archive fallback metadata
     */
    PacketDataBatch queryPacketBudgetedVolume(
            String world,
            int centerBlockX,
            int centerBlockY,
            int centerBlockZ,
            int chunkRadius,
            int sectionRadiusY,
            int maxPacketBytes
    );

    /**
     * Streams a $512 \times 512$ region (`rx, rz`) as progressive packet-sized batches from
     * {@code LOD-3} (`S=64`, `~10.9 KB`, fits in 1 packet) down to {@code LOD-0} (`S=1`).
     *
     * @param world          target world name
     * @param regionX        region X coordinate (`chunkX >> 5`)
     * @param regionZ        region Z coordinate (`chunkZ >> 5`)
     * @param maxPacketBytes byte ceiling per packet frame
     * @return ordered list of progressive packet frames
     */
    List<PacketDataBatch> streamProgressiveRegionPackets(
            String world,
            int regionX,
            int regionZ,
            int maxPacketBytes
    );

    /**
     * Serializes both the {@link #clientDrawingKernel()} and the packet-budgeted 3D block/sub-chunk
     * feed around the requested focus coordinate into a self-contained JSON object suitable for
     * embedding into the {@code LeafConfig} World Map panel (`"leafMapFeed":{...}`).
     *
     * @param world        target world name
     * @param focusBlockX  initial camera/pinpoint block X coordinate
     * @param focusBlockY  initial camera/pinpoint block Y coordinate
     * @param focusBlockZ  initial camera/pinpoint block Z coordinate
     * @return valid JSON object string containing `providerId`, `kernel`, `packetBatch`, and `multiSpanColumns`
     */
    String toWebPanelFeedJson(String world, int focusBlockX, int focusBlockY, int focusBlockZ);

    /**
     * Registers or updates a spatial {@link InteractiveMapOverlay} (such as a {@code LeafRTP}
     * region donut or {@code LeafSleuth} lag hotspot volume) and its component-level
     * {@link OverlayPermissionGates} directly with the map engine.
     *
     * @param overlay interactive spatial overlay descriptor
     */
    default void registerInteractiveOverlay(InteractiveMapOverlay overlay) {
        Objects.requireNonNull(overlay, "overlay");
    }

    /**
     * Unregisters a spatial {@link InteractiveMapOverlay} by its {@code overlayId}.
     *
     * @param overlayId overlay identifier
     * @return {@code true} if an overlay was removed
     */
    default boolean unregisterInteractiveOverlay(String overlayId) {
        return false;
    }

    /**
     * Returns all active {@link InteractiveMapOverlay}s registered with this map engine for the
     * target world (or all worlds when {@code world} is {@code null} or {@code "*"}).
     *
     * @param world target world name or {@code null}
     * @return immutable list of registered interactive overlays
     */
    default List<InteractiveMapOverlay> interactiveOverlays(String world) {
        return List.of();
    }

    /**
     * Returns the monotonically increasing overlay/tab revision counter (`DSN-1` atomic sequencing).
     */
    default long overlayRevision() {
        return 0L;
    }

    /**
     * Builds the generic {@code LeafConfig} workspace extension bundle (`workspaceTabs` with the
     * {@code world-map} tab and {@code LeafMapClientKernel.mountWorkspaceTab} renderer) owned and
     * supplied by this map engine.
     *
     * @return generic workspace extension bundle map
     */
    default Map<String, Object> buildLeafConfigWorkspaceExtension() {
        return Map.of();
    }

    /**
     * Builds the generic {@code LeafConfig} workspace extension bundle filtered and gated for a
     * viewer session using the supplied {@code permissionChecker} (e.g. distinguishing viewing the
     * RTP donut vs resizing or moving it).
     *
     * @param permissionChecker predicate evaluating permission nodes for the target viewer
     * @return generic workspace extension bundle map
     */
    default Map<String, Object> buildLeafConfigWorkspaceExtensionForViewer(Predicate<String> permissionChecker) {
        return buildLeafConfigWorkspaceExtension();
    }

    /**
     * Registers a reactive push callback invoked whenever registered spatial overlays or map state
     * change so {@code LeafConfig} receives updated {@code workspaceTabs} bundles automatically.
     *
     * @param listener consumer receiving the updated workspace extension bundle map
     */
    default void onWorkspaceExtensionUpdated(Consumer<Map<String, Object>> listener) {
        Objects.requireNonNull(listener, "listener");
    }
}
