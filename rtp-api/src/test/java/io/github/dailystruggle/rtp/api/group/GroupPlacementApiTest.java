package io.github.dailystruggle.rtp.api.group;

import io.github.dailystruggle.rtp.api.world.RTPLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surface coverage for the platform-neutral {@code group} placement API (S-004 fail-closed).
 *
 * <p>Exercises the factory validation, immutability, clamping, value semantics, and the
 * success/failure result contract of {@link GroupPlacementRequest}, {@link GroupProfileSpec},
 * and {@link GroupPlacementResult}.
 */
class GroupPlacementApiTest {

    private static GroupProfileSpec profile() {
        return GroupProfileSpec.of("circle", 128, 4, 3, 8);
    }

    // --- GroupProfileSpec ---

    @Test
    void profileClampsLowerBounds() {
        GroupProfileSpec spec = GroupProfileSpec.of("Square", -5, 0, -1, 0);
        assertEquals("Square", spec.distribution());
        assertEquals(0, spec.radius(), "radius clamps to >= 0");
        assertEquals(1, spec.minSeparation(), "minSeparation clamps to >= 1");
        assertEquals(0, spec.elevationTolerance(), "elevationTolerance clamps to >= 0");
        assertEquals(1, spec.maxGroupSize(), "maxGroupSize clamps to >= 1");
    }

    @Test
    void profileKeepsValuesAboveBounds() {
        GroupProfileSpec spec = GroupProfileSpec.of("circle", 200, 6, 10, 12);
        assertEquals(200, spec.radius());
        assertEquals(6, spec.minSeparation());
        assertEquals(10, spec.elevationTolerance());
        assertEquals(12, spec.maxGroupSize());
    }

    @Test
    void profileRejectsBlankDistribution() {
        assertThrows(IllegalArgumentException.class, () -> GroupProfileSpec.of(null, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> GroupProfileSpec.of("   ", 1, 1, 1, 1));
    }

    @Test
    void profileEqualityIsCaseInsensitiveOnDistribution() {
        GroupProfileSpec a = GroupProfileSpec.of("Circle", 10, 2, 1, 4);
        GroupProfileSpec b = GroupProfileSpec.of("circle", 10, 2, 1, 4);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(a, a);
        assertNotEquals(a, GroupProfileSpec.of("square", 10, 2, 1, 4));
        assertNotEquals(a, GroupProfileSpec.of("circle", 11, 2, 1, 4));
        assertNotEquals(a, null);
        assertNotEquals(a, "circle");
        assertTrue(a.toString().contains("Circle"));
    }

    // --- GroupPlacementRequest ---

    @Test
    void requestExposesImmutableParticipants() {
        List<UUID> ids = new ArrayList<>(Arrays.asList(UUID.randomUUID(), UUID.randomUUID()));
        GroupPlacementRequest req = GroupPlacementRequest.of("spawnRegion", profile(), ids);
        assertEquals("spawnRegion", req.regionName());
        assertSame(profile().getClass(), req.profileSpec().getClass());
        assertEquals(2, req.participantCount());
        assertEquals(2, req.participants().size());
        // Defensive copy: mutating the source list must not affect the request.
        ids.add(UUID.randomUUID());
        assertEquals(2, req.participantCount());
        assertThrows(UnsupportedOperationException.class,
                () -> req.participants().add(UUID.randomUUID()));
        assertTrue(req.toString().contains("spawnRegion"));
    }

    @Test
    void requestRejectsBadArguments() {
        List<UUID> ids = Collections.singletonList(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> GroupPlacementRequest.of(null, profile(), ids));
        assertThrows(IllegalArgumentException.class,
                () -> GroupPlacementRequest.of("  ", profile(), ids));
        assertThrows(NullPointerException.class,
                () -> GroupPlacementRequest.of("r", null, ids));
        assertThrows(IllegalArgumentException.class,
                () -> GroupPlacementRequest.of("r", profile(), null));
        assertThrows(IllegalArgumentException.class,
                () -> GroupPlacementRequest.of("r", profile(), Collections.emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> GroupPlacementRequest.of("r", profile(), Arrays.asList(UUID.randomUUID(), null)));
    }

    // --- GroupPlacementResult ---

    @Test
    void successCarriesImmutablePlacements() {
        UUID id = UUID.randomUUID();
        Map<UUID, RTPLocation> placements = new LinkedHashMap<>();
        placements.put(id, new RTPLocation(null, 1, 64, 2));
        GroupPlacementResult result = GroupPlacementResult.success(placements);
        assertTrue(result.isSuccess());
        assertEquals(GroupPlacementResult.Reason.SUCCESS, result.reason());
        assertEquals("ok", result.message());
        assertEquals(1, result.placements().size());
        // Defensive copy + unmodifiable view.
        placements.clear();
        assertEquals(1, result.placements().size());
        assertThrows(UnsupportedOperationException.class,
                () -> result.placements().put(UUID.randomUUID(), new RTPLocation(null, 0, 0, 0)));
        assertTrue(result.toString().contains("SUCCESS"));
    }

    @Test
    void successRejectsEmptyOrNullMap() {
        assertThrows(IllegalArgumentException.class, () -> GroupPlacementResult.success(null));
        assertThrows(IllegalArgumentException.class,
                () -> GroupPlacementResult.success(Collections.emptyMap()));
    }

    @Test
    void failureIsEmptyAndClassified() {
        GroupPlacementResult result =
                GroupPlacementResult.failure(GroupPlacementResult.Reason.NO_ANCHOR, "no anchor");
        assertFalse(result.isSuccess());
        assertEquals(GroupPlacementResult.Reason.NO_ANCHOR, result.reason());
        assertEquals("no anchor", result.message());
        assertTrue(result.placements().isEmpty());
        assertTrue(result.toString().contains("NO_ANCHOR"));
    }

    @Test
    void failureToleratesNullMessage() {
        GroupPlacementResult result =
                GroupPlacementResult.failure(GroupPlacementResult.Reason.CANCELLED, null);
        assertNull(result.message());
        assertFalse(result.toString().contains("null"));
    }

    @Test
    void failureRejectsNullOrSuccessReason() {
        assertThrows(IllegalArgumentException.class,
                () -> GroupPlacementResult.failure(null, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> GroupPlacementResult.failure(GroupPlacementResult.Reason.SUCCESS, "x"));
    }

    @Test
    void reasonEnumRoundTrips() {
        for (GroupPlacementResult.Reason reason : GroupPlacementResult.Reason.values()) {
            assertSame(reason, GroupPlacementResult.Reason.valueOf(reason.name()));
        }
    }
}
