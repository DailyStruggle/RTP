package io.github.dailystruggle.mapsapi.world3d;

import java.util.List;
import java.util.Objects;

/**
 * Platform-neutral 2D/3D map marker and spatial overlay primitives providing feature parity
 * with BlueMap, Dynmap, Pl3xMap, and Squaremap marker sets ({@code maps-api-ADR-003},
 * {@code leafmap-ADR-001}, {@code leafrtp-ADR-067}, {@code ADR-115}).
 *
 * <p>Supports 5 canonical marker geometries:
 * <ul>
 *   <li>{@link PointMarker}: 2D/3D point-of-interest pins (world spawn, teleport beacons, warps, beds).</li>
 *   <li>{@link ExtrudedPolygonMarker}: 2D/3D extruded polygonal/circular/rectangular regions
 *       (WorldGuard/GriefPrevention/Towny/Lands claims, {@code WorldBorder}, and {@code LeafRTP} region shapes).</li>
 *   <li>{@link LineMarker}: 3D polylines (roads, railways, {@code LeafRTP} walk paths).</li>
 *   <li>{@link VolumeMarker}: 3D volumetric bounding prisms ({@code LeafSleuth} lag hotspots, structure bounds).</li>
 *   <li>{@link LivePlayerMarker}: Real-time online player telemetry (pose, dimension, health, armor, visibility).</li>
 * </ul>
 *
 * @governedBy maps-api-ADR-003
 * @governedBy leafmap-ADR-001
 * @governedBy leafrtp-ADR-067
 * @governedBy ADR-115
 * @trace REQ-RTP-MAP-001
 * @trace REQ-RTP-MAP-002
 * @trace S-004
 */
public sealed interface WorldMapMarker permits
        WorldMapMarker.PointMarker,
        WorldMapMarker.ExtrudedPolygonMarker,
        WorldMapMarker.LineMarker,
        WorldMapMarker.VolumeMarker,
        WorldMapMarker.LivePlayerMarker {

    /**
     * Unique marker identifier within its {@link WorldMapMarkerSet}.
     */
    String id();

    /**
     * Human-readable label shown in map tooltips and the marker sidebar.
     */
    String label();

    /**
     * Target world name (e.g. {@code "world"}, {@code "world_nether"}, {@code "world_the_end"}).
     */
    String world();

    /**
     * Canonical marker geometry type name ({@code "point"}, {@code "polygon"}, {@code "line"},
     * {@code "volume"}, {@code "player"}).
     */
    String type();

    /**
     * Immutable 3D block/world coordinate vertex.
     */
    record Vec3i(int x, int y, int z) {
    }

    /**
     * 2D/3D point-of-interest marker (spawns, warps, teleport beacons, shrines).
     *
     * @param id          unique marker ID
     * @param label       display label
     * @param world       target world name
     * @param x           block X coordinate
     * @param y           block Y coordinate
     * @param z           block Z coordinate
     * @param icon        symbolic icon identifier (e.g. {@code "spawn"}, {@code "beacon"}, {@code "home"}, {@code "portal"})
     * @param colorRgb    24-bit RGB accent color
     * @param detailHtml  plain-text or sanitized tooltip description
     * @param minDistance minimum camera distance at which this marker is visible
     * @param maxDistance maximum camera distance at which this marker is visible
     */
    record PointMarker(
            String id,
            String label,
            String world,
            int x,
            int y,
            int z,
            String icon,
            int colorRgb,
            String detailHtml,
            double minDistance,
            double maxDistance
    ) implements WorldMapMarker {
        public PointMarker {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(detailHtml, "detailHtml");
            if (id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
        }

        @Override
        public String type() {
            return "point";
        }
    }

    /**
     * 2D/3D extruded polygon marker for land claims (WorldGuard, GriefPrevention, Lands, Towny),
     * {@code WorldBorder} perimeters, and {@code LeafRTP} region/deadzone shapes.
     *
     * @param id          unique marker ID
     * @param label       display label (e.g. claim owner / region name)
     * @param world       target world name
     * @param verticesXZ  ordered polygon vertices (only X and Z are used for footprint; Y comes from minY/maxY)
     * @param minY        bottom extrusion Y coordinate (for 2D flat claims, set {@code minY == maxY})
     * @param maxY        top extrusion Y coordinate
     * @param strokeRgb   24-bit RGB border stroke color
     * @param fillRgb     24-bit RGB interior fill color
     * @param fillOpacity interior opacity in {@code [0.0, 1.0]}
     * @param detailHtml  tooltip metadata (owner, flags, trust list, or RTP shape parameters)
     */
    record ExtrudedPolygonMarker(
            String id,
            String label,
            String world,
            List<Vec3i> verticesXZ,
            int minY,
            int maxY,
            int strokeRgb,
            int fillRgb,
            double fillOpacity,
            String detailHtml
    ) implements WorldMapMarker {
        public ExtrudedPolygonMarker {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(verticesXZ, "verticesXZ");
            Objects.requireNonNull(detailHtml, "detailHtml");
            if (verticesXZ.size() < 3) {
                throw new IllegalArgumentException("ExtrudedPolygonMarker requires at least 3 vertices");
            }
            verticesXZ = List.copyOf(verticesXZ);
            fillOpacity = Math.max(0.0, Math.min(1.0, fillOpacity));
        }

        @Override
        public String type() {
            return "polygon";
        }
    }

    /**
     * 3D polyline marker for roads, tunnels, borders, and {@code LeafRTP} walk paths.
     *
     * @param id         unique marker ID
     * @param label      display label
     * @param world      target world name
     * @param points     ordered 3D polyline vertices (at least 2 points)
     * @param strokeRgb  24-bit RGB line color
     * @param lineWidth  line width in pixels
     * @param detailHtml tooltip text
     */
    record LineMarker(
            String id,
            String label,
            String world,
            List<Vec3i> points,
            int strokeRgb,
            double lineWidth,
            String detailHtml
    ) implements WorldMapMarker {
        public LineMarker {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(points, "points");
            Objects.requireNonNull(detailHtml, "detailHtml");
            if (points.size() < 2) {
                throw new IllegalArgumentException("LineMarker requires at least 2 points");
            }
            points = List.copyOf(points);
        }

        @Override
        public String type() {
            return "line";
        }
    }

    /**
     * 3D volumetric bounding prism for {@code LeafSleuth} lag hotspots, redstone/hopper rooms,
     * and subterranean farm inspection volumes.
     *
     * @param id          unique marker ID
     * @param label       display label (e.g. {@code "SLEUTH-01 Hopper Sorter"})
     * @param world       target world name
     * @param minX        minimum block X
     * @param minY        minimum block Y
     * @param minZ        minimum block Z
     * @param maxX        maximum block X
     * @param maxY        maximum block Y
     * @param maxZ        maximum block Z
     * @param colorRgb    24-bit RGB volume color
     * @param msptImpact  attributed MSPT / tick cost (or {@code 0.0} for non-lag volumes)
     * @param category    attribution category (e.g. {@code "TILE_ENTITY_HOPPER"}, {@code "REDSTONE"})
     * @param detailHtml  remediation summary or tooltip details
     */
    record VolumeMarker(
            String id,
            String label,
            String world,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            int colorRgb,
            double msptImpact,
            String category,
            String detailHtml
    ) implements WorldMapMarker {
        public VolumeMarker {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(category, "category");
            Objects.requireNonNull(detailHtml, "detailHtml");
        }

        @Override
        public String type() {
            return "volume";
        }
    }

    /**
     * Real-time online player marker with pose, dimension, health/armor telemetry, and privacy state.
     *
     * @param uuid      player UUID string
     * @param name      player username
     * @param world     current world name
     * @param dimension dimension type ({@code "overworld"}, {@code "the_nether"}, {@code "the_end"})
     * @param x         block/world X coordinate
     * @param y         block/world Y coordinate
     * @param z         block/world Z coordinate
     * @param yaw       horizontal rotation in degrees
     * @param pitch     vertical pitch in degrees
     * @param health    current health points ({@code 0..20})
     * @param armor     current armor points ({@code 0..20})
     * @param gameMode  lowercase game mode ({@code "survival"}, {@code "creative"}, {@code "adventure"}, {@code "spectator"})
     * @param hidden    {@code true} if hidden by `/leafmap hide`, vanish, sneaking, or Y-depth privacy rules
     */
    record LivePlayerMarker(
            String uuid,
            String name,
            String world,
            String dimension,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            double health,
            int armor,
            String gameMode,
            boolean hidden
    ) implements WorldMapMarker {
        public LivePlayerMarker {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(gameMode, "gameMode");
        }

        @Override
        public String id() {
            return uuid;
        }

        @Override
        public String label() {
            return name;
        }

        @Override
        public String type() {
            return "player";
        }
    }
}
