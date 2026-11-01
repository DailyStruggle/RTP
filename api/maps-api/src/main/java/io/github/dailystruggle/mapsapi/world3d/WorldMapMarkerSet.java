package io.github.dailystruggle.mapsapi.world3d;

import java.util.List;
import java.util.Objects;

/**
 * Named, toggleable layer of {@link WorldMapMarker} entries for a target world
 * ({@code maps-api-ADR-003}, {@code leafmap-ADR-001}, {@code leafrtp-ADR-067}, {@code ADR-115}).
 *
 * <p>Provides structural parity with BlueMap {@code MarkerSet}, Dynmap {@code MarkerSet},
 * Pl3xMap {@code LayerProvider}, and Squaremap {@code SimpleLayerProvider}.
 *
 * @param id            unique layer identifier (e.g. {@code "claims"}, {@code "leafrtp"}, {@code "leafsleuth"}, {@code "spawns"})
 * @param label         human-readable layer toggle label in the web map sidebar
 * @param world         target world name (or {@code "*"} for cross-world marker sets)
 * @param toggleable    whether users can toggle visibility in the web UI
 * @param defaultHidden whether the layer starts hidden by default
 * @param priority      layer sort order in the UI sidebar (lower renders first)
 * @param markers       immutable list of markers belonging to this set
 *
 * @governedBy maps-api-ADR-003
 * @governedBy leafmap-ADR-001
 * @governedBy leafrtp-ADR-067
 * @governedBy ADR-115
 * @trace REQ-RTP-MAP-001
 * @trace REQ-RTP-MAP-002
 * @trace S-004
 */
public record WorldMapMarkerSet(
        String id,
        String label,
        String world,
        boolean toggleable,
        boolean defaultHidden,
        int priority,
        List<WorldMapMarker> markers
) {
    public WorldMapMarkerSet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(markers, "markers");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        markers = List.copyOf(markers);
    }
}
