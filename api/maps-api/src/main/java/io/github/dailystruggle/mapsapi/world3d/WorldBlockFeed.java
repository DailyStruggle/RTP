package io.github.dailystruggle.mapsapi.world3d;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Platform-neutral SPI for delivering client-side 2D/3D block drawing code and packet-budgeted
 * {@code .hca v2} / {@code .dlsh} multi-span and $16 \times 16 \times 16$ sub-chunk frames
 * ({@code maps-api-ADR-003}, {@code anvil-api-ADR-003}, {@code ADR-115}).
 *
 * <p>Designed around two complementary transport modes so {@code LeafMap} replaces BlueMap while
 * feeding the {@code LeafConfig} World Map panel:
 * <ol>
 *   <li><b>Packet-Constrained Stream ({@link #DEFAULT_MAX_PACKET_BYTES} = {@code 30,000 B}):</b>
 *       Slices $16^3$ vertical sub-chunks in $O(1)$ via {@code .hca v2}'s prefixed {@code uint16[24]}
 *       section pointer table (`~120-165 B` per active section, `2 B` per uniform section) and
 *       {@code LOD-3} (`S=64`, `~10.9 KB`) surface horizon headers so an entire 3D camera neighborhood
 *       or {@code LeafSleuth} hotspot volume (`3x3x3` sections $\approx 3.9\text{ KB}$) fits inside a
 *       single Minecraft plugin-message / signed-relay packet.</li>
 *   <li><b>Local Zero-Parse Archive Fallback:</b> When operating locally or across whole-world
 *       (`16 km`) datasets exceeding packet budgets, exposes the memory-mapped {@code .hca v2} /
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
     * Self-contained client-side 2D/3D voxel &amp; sub-chunk drawing kernel delivered by {@code LeafMap}
     * so host panels (such as {@code LeafConfig}) can decode and draw {@code .hca v2} sub-chunks,
     * multi-span overhangs, subterranean cutaways, and vanilla `[1.0, 0.8, 0.6, 0.5]` + AO shading
     * without embedding a separate rendering engine.
     *
     * @param providerId            provider identifier (e.g. {@code "leafmap"})
     * @param version               kernel schema version (e.g. {@code "hca-v2.0"})
     * @param clientDrawingModuleJs self-contained JavaScript module exposing {@code window.LeafMapClientKernel}
     * @param blockPaletteColors    shared region/world block-state ID to 24-bit RGB color dictionary
     */
    record ClientDrawingKernel(
            String providerId,
            String version,
            String clientDrawingModuleJs,
            Map<String, Integer> blockPaletteColors
    ) {
        public ClientDrawingKernel {
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(clientDrawingModuleJs, "clientDrawingModuleJs");
            Objects.requireNonNull(blockPaletteColors, "blockPaletteColors");
            if (providerId.isBlank()) {
                throw new IllegalArgumentException("providerId must not be blank");
            }
            blockPaletteColors = Collections.unmodifiableMap(new LinkedHashMap<>(blockPaletteColors));
        }
    }

    /**
     * Compact {@code .hca v2} $16 \times 16 \times 16$ (`4,096`-voxel) vertical sub-chunk frame
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
     * {@code .hca v2} / {@code .dlsh} archive descriptor when running locally or exceeding packet limits.
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
     * @param fitsInSinglePacket       {@code true} if {@code wireByteCount <= maxPacketBytes}
     * @param wireByteCount            total wire bytes in this packet frame
     * @param localFallbackArchivePath path to the local zero-parse `.hca` / `.dlsh` archive for instant local loading
     * @param coldChunkLoadMicros      empirical cold 1-chunk load latency in microseconds (`46.7 us` for `.hca v2`)
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
            boolean fitsInSinglePacket,
            int wireByteCount,
            String localFallbackArchivePath,
            double coldChunkLoadMicros,
            double compressionRatioVsBlueMap
    ) {
        public PacketDataBatch {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(surfaceHorizonHeader, "surfaceHorizonHeader");
            Objects.requireNonNull(subChunks, "subChunks");
            Objects.requireNonNull(localFallbackArchivePath, "localFallbackArchivePath");
            if (packetIndex < 0 || totalPackets < 1 || packetIndex >= totalPackets) {
                throw new IllegalArgumentException("Invalid packetIndex/totalPackets: " + packetIndex + "/" + totalPackets);
            }
            surfaceHorizonHeader = surfaceHorizonHeader.clone();
            subChunks = List.copyOf(subChunks);
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
    }

    /**
     * Returns the stable lowercase provider identifier (e.g. {@code "leafmap"}).
     */
    String providerId();

    /**
     * Returns the human-readable display name (e.g. {@code "LeafMap 3D HCA Engine"}).
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
}
