package io.github.dailystruggle.rtp.api.claim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ClaimBoundary and ClaimBoundaryProvider default methods")
class ClaimBoundaryTest {

    @Test
    @DisplayName("ClaimBoundary default coordinate and chunk methods")
    void testClaimBoundaryDefaults() {
        ClaimBoundary boundary = new ClaimBoundary() {
            @Override public String id() { return "claim-1"; }
            @Override public String world() { return "world"; }
            @Override public boolean contains(int x, int z) { return x >= 0 && x <= 32 && z >= 0 && z <= 32; }
            @Override public int[] centroid() { return new int[]{16, 16}; }
            @Override public int minChunkX() { return 0; }
            @Override public int minChunkZ() { return 0; }
            @Override public int maxChunkX() { return 2; }
            @Override public int maxChunkZ() { return 2; }
        };

        assertEquals("claim-1", boundary.id());
        assertEquals("world", boundary.world());
        assertArrayEquals(new int[]{16, 16}, boundary.centroid());
        assertEquals(0, boundary.minX());
        assertEquals(0, boundary.minZ());
        assertEquals(47, boundary.maxX());
        assertEquals(47, boundary.maxZ());

        assertTrue(boundary.containsChunk(0, 0)); // Center column (8, 8) is inside
        assertTrue(boundary.containsChunk(1, 1)); // Center column (24, 24) is inside
        assertFalse(boundary.containsChunk(3, 3)); // Center column (56, 56) is outside
    }

    @Test
    @DisplayName("ClaimBoundaryProvider default priority and getBoundaryAt")
    void testClaimBoundaryProviderDefaults() {
        ClaimBoundaryProvider provider = new ClaimBoundaryProvider() {
            @Override public String namespace() { return "test"; }
            @Override
            public Optional<ClaimBoundary> getBoundary(UUID playerId, String worldName) {
                return Optional.empty();
            }
        };

        assertEquals("test", provider.namespace());
        assertEquals(0, provider.priority());
        assertTrue(provider.getBoundaryAt("world", 100, 100).isEmpty());
    }
}
