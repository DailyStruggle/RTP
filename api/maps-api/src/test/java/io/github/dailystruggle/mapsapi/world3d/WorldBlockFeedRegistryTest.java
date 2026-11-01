package io.github.dailystruggle.mapsapi.world3d;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link WorldBlockFeed} records, defensive copies, wire-byte calculations, and
 * {@link WorldBlockFeedRegistry} registration semantics ({@code maps-api-ADR-003},
 * {@code anvil-api-ADR-003}).
 */
@DisplayName("WorldBlockFeed & WorldBlockFeedRegistry SPI contract tests")
class WorldBlockFeedRegistryTest {

    @AfterEach
    void tearDown() {
        WorldBlockFeedRegistry.clear();
    }

    @Test
    @DisplayName("SubChunkSectionFrame enforces defensive copies and computes uniform vs active wireByteSize")
    void subChunkSectionFrameDefensiveCopiesAndWireBytes() {
        WorldBlockFeed.SubChunkSectionFrame uniformAir = new WorldBlockFeed.SubChunkSectionFrame(
                "world", 9, 4, -6, 1, 0L,
                List.of("minecraft:air"),
                new byte[0], (byte) 15, (byte) 0, new byte[0], new byte[0]
        );
        assertEquals(2, uniformAir.wireByteSize(), "Uniform section with constant light shall take 2 bytes on wire");

        byte[] runs = new byte[]{0x12, 0x34, 0x56};
        byte[] skyGray = new byte[]{0x01, 0x02};
        byte[] blkGray = new byte[]{0x05};
        WorldBlockFeed.SubChunkSectionFrame active = new WorldBlockFeed.SubChunkSectionFrame(
                "world", 9, 3, -6, 2, 0x00FF00FF00FF00FFL,
                List.of("minecraft:deepslate", "minecraft:hopper", "minecraft:chest"),
                runs, (byte) -1, (byte) -1, skyGray, blkGray
        );

        runs[0] = (byte) 0xFF;
        assertEquals(0x12, active.deltaGapRuns()[0]);
        assertNotSame(active.deltaGapRuns(), active.deltaGapRuns());
        assertEquals(16 + 6 + 3 + 2 + 1, active.wireByteSize());

        WorldBlockFeed.SubChunkSectionFrame copy = new WorldBlockFeed.SubChunkSectionFrame(
                "world", 9, 3, -6, 2, 0x00FF00FF00FF00FFL,
                List.of("minecraft:deepslate", "minecraft:hopper", "minecraft:chest"),
                active.deltaGapRuns(), (byte) -1, (byte) -1, active.skyLightGrayPlanes(), active.blockLightGrayPlanes()
        );
        assertEquals(active, copy);
        assertEquals(active.hashCode(), copy.hashCode());

        assertThrows(IllegalArgumentException.class, () -> new WorldBlockFeed.SubChunkSectionFrame(
                "world", 0, 0, 0, 0, 0L, List.of(), new byte[0], (byte) 0, (byte) 0, new byte[0], new byte[0]
        ));
        assertThrows(IllegalArgumentException.class, () -> new WorldBlockFeed.SubChunkSectionFrame(
                "world", 0, 0, 0, 4, 0L, List.of("minecraft:air"), new byte[0], (byte) 0, (byte) 0, new byte[0], new byte[0]
        ));
    }

    @Test
    @DisplayName("WorldBlockFeedRegistry registers, resolves active feed, and unregisters cleanly")
    void registryLifecycle() {
        WorldBlockFeed dummy = new WorldBlockFeed() {
            @Override
            public String providerId() {
                return "leafmap";
            }

            @Override
            public String displayName() {
                return "LeafMap 3D HCA Engine";
            }

            @Override
            public ClientDrawingKernel clientDrawingKernel() {
                return new ClientDrawingKernel(
                        "leafmap", "hca-v2.0", "window.LeafMapClientKernel={};",
                        Map.of("minecraft:stone", 0x7D7D7D)
                );
            }

            @Override
            public PacketDataBatch queryPacketBudgetedVolume(
                    String world, int centerBlockX, int centerBlockY, int centerBlockZ,
                    int chunkRadius, int sectionRadiusY, int maxPacketBytes
            ) {
                return new PacketDataBatch(
                        world, 0, 1, 0, centerBlockX >> 4, centerBlockZ >> 4, 2, 4,
                        new byte[]{1, 2, 3}, List.of(), true, 128,
                        "world/region/r.0.0.hca", 46.7, 130.8
                );
            }

            @Override
            public List<PacketDataBatch> streamProgressiveRegionPackets(
                    String world, int regionX, int regionZ, int maxPacketBytes
            ) {
                return List.of(queryPacketBudgetedVolume(world, regionX * 512, 64, regionZ * 512, 1, 1, maxPacketBytes));
            }

            @Override
            public String toWebPanelFeedJson(String world, int focusBlockX, int focusBlockY, int focusBlockZ) {
                return "{\"providerId\":\"leafmap\"}";
            }
        };

        WorldBlockFeedRegistry.register(dummy);
        assertTrue(WorldBlockFeedRegistry.activeFeed().isPresent());
        assertEquals("leafmap", WorldBlockFeedRegistry.activeFeed().get().providerId());
        assertTrue(WorldBlockFeedRegistry.find("leafmap").isPresent());
        assertEquals(1, WorldBlockFeedRegistry.allFeeds().size());

        WorldBlockFeed.PacketDataBatch batch = dummy.queryPacketBudgetedVolume("world", 144, 54, -96, 1, 1, 30_000);
        assertArrayEquals(new byte[]{1, 2, 3}, batch.surfaceHorizonHeader());
        assertTrue(batch.fitsInSinglePacket());
        assertEquals(46.7, batch.coldChunkLoadMicros(), 0.01);

        WorldBlockFeedRegistry.unregister("leafmap");
        assertTrue(WorldBlockFeedRegistry.activeFeed().isEmpty());
    }

    @Test
    @DisplayName("WorldMapMarker, WorldMapMarkerSet, and WorldMapDescriptor validate geometry and defaults")
    void worldMapMarkerAndDescriptorContracts() {
        WorldMapMarker.PointMarker point = new WorldMapMarker.PointMarker(
                "spawn", "World Spawn", "world", 144, 68, -96, "spawn", 0xA6E3A1, "Spawn Hub", 0.0, 16384.0
        );
        assertEquals("point", point.type());

        WorldMapMarker.ExtrudedPolygonMarker claim = new WorldMapMarker.ExtrudedPolygonMarker(
                "claim-1", "Spawn Citadel", "world",
                List.of(
                        new WorldMapMarker.Vec3i(0, 64, 0),
                        new WorldMapMarker.Vec3i(32, 64, 0),
                        new WorldMapMarker.Vec3i(32, 64, 32)
                ),
                60, 120, 0xA6E3A1, 0xA6E3A1, 1.5, "Protected"
        );
        assertEquals("polygon", claim.type());
        assertEquals(1.0, claim.fillOpacity(), 1e-6, "Opacity must clamp to [0.0, 1.0]");

        assertThrows(IllegalArgumentException.class, () -> new WorldMapMarker.ExtrudedPolygonMarker(
                "bad-poly", "Invalid", "world",
                List.of(new WorldMapMarker.Vec3i(0, 64, 0), new WorldMapMarker.Vec3i(1, 64, 1)),
                60, 120, 0, 0, 0.5, ""
        ));

        WorldMapMarker.LineMarker line = new WorldMapMarker.LineMarker(
                "road-1", "Highway", "world",
                List.of(new WorldMapMarker.Vec3i(0, 64, 0), new WorldMapMarker.Vec3i(100, 64, 100)),
                0x89DCEB, 3.0, "Road"
        );
        assertEquals("line", line.type());

        WorldMapMarker.VolumeMarker volume = new WorldMapMarker.VolumeMarker(
                "SLEUTH-01", "Hopper Sorter", "world",
                136, 48, -104, 156, 60, -86, 0xF38BA8, 4.82, "TILE_ENTITY_HOPPER", "4.82 ms/tick"
        );
        assertEquals("volume", volume.type());

        WorldMapMarker.LivePlayerMarker player = new WorldMapMarker.LivePlayerMarker(
                "u-1", "LeafAdmin", "world", "overworld", 144.0, 68.0, -96.0, 90.0f, 0.0f, 20.0, 20, "creative", false
        );
        assertEquals("player", player.type());
        assertEquals("u-1", player.id());
        assertEquals("LeafAdmin", player.label());

        WorldMapMarkerSet set = new WorldMapMarkerSet(
                "claims", "Land Claims", "world", true, false, 10, List.of(point, claim, line, volume, player)
        );
        assertEquals(5, set.markers().size());
    }
}
