package io.github.dailystruggle.rtp.api.group;

import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AnchorSource branch and contract coverage")
class AnchorSourceCoverageTest {

    @Test
    @DisplayName("regionQueue and claimHazard return completed future with null")
    void testSingletons() throws Exception {
        AnchorSource rq = AnchorSource.regionQueue();
        assertNotNull(rq);
        CompletableFuture<RTPCoords> f1 = rq.resolveAnchor(new Object());
        assertTrue(f1.isDone());
        assertNull(f1.get());

        AnchorSource ch = AnchorSource.claimHazard();
        assertNotNull(ch);
        CompletableFuture<RTPCoords> f2 = ch.resolveAnchor(new Object());
        assertTrue(f2.isDone());
        assertNull(f2.get());
    }

    @Test
    @DisplayName("fixed anchor source requires non-null and completes with coords")
    void testFixed() throws Exception {
        assertThrows(NullPointerException.class, () -> AnchorSource.fixed(null));

        RTPCoords coords = new RTPCoords("world", 10, 64, 20);
        AnchorSource fixed = AnchorSource.fixed(coords);
        CompletableFuture<RTPCoords> f = fixed.resolveAnchor("dummyContext");
        assertTrue(f.isDone());
        assertSame(coords, f.get());
    }

    @Test
    @DisplayName("entity anchor source requires non-null supplier and handles success/failure")
    void testEntity() throws Exception {
        assertThrows(NullPointerException.class, () -> AnchorSource.entity(null));

        RTPCoords coords = new RTPCoords("world_nether", 100, 70, -200);
        AnchorSource successSource = AnchorSource.entity(() -> coords);
        CompletableFuture<RTPCoords> fSuccess = successSource.resolveAnchor(null);
        assertTrue(fSuccess.isDone());
        assertSame(coords, fSuccess.get());

        AnchorSource failSource = AnchorSource.entity(() -> {
            throw new IllegalStateException("entity offline");
        });
        CompletableFuture<RTPCoords> fFail = failSource.resolveAnchor(null);
        assertTrue(fFail.isCompletedExceptionally());
        ExecutionException ex = assertThrows(ExecutionException.class, fFail::get);
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    @DisplayName("claimBoundary anchor source contracts and centroid checks")
    void testClaimBoundary() throws Exception {
        assertThrows(NullPointerException.class, () -> AnchorSource.claimBoundary(null));

        // centroid is null
        ClaimBoundary nullCentroid = new ClaimBoundary() {
            @Override public String id() { return "claim1"; }
            @Override public String world() { return "world"; }
            @Override public boolean contains(int x, int z) { return false; }
            @Override public int[] centroid() { return null; }
            @Override public int minChunkX() { return 0; }
            @Override public int minChunkZ() { return 0; }
            @Override public int maxChunkX() { return 1; }
            @Override public int maxChunkZ() { return 1; }
        };
        AnchorSource srcNullCentroid = AnchorSource.claimBoundary(nullCentroid);
        CompletableFuture<RTPCoords> fNull = srcNullCentroid.resolveAnchor(null);
        assertTrue(fNull.isDone());
        assertNull(fNull.get());

        // centroid length < 2
        ClaimBoundary shortCentroid = new ClaimBoundary() {
            @Override public String id() { return "claim2"; }
            @Override public String world() { return "world"; }
            @Override public boolean contains(int x, int z) { return false; }
            @Override public int[] centroid() { return new int[]{42}; }
            @Override public int minChunkX() { return 0; }
            @Override public int minChunkZ() { return 0; }
            @Override public int maxChunkX() { return 1; }
            @Override public int maxChunkZ() { return 1; }
        };
        AnchorSource srcShortCentroid = AnchorSource.claimBoundary(shortCentroid);
        CompletableFuture<RTPCoords> fShort = srcShortCentroid.resolveAnchor(null);
        assertTrue(fShort.isDone());
        assertNull(fShort.get());

        // valid centroid
        ClaimBoundary validCentroid = new ClaimBoundary() {
            @Override public String id() { return "claim3"; }
            @Override public String world() { return "world_the_end"; }
            @Override public boolean contains(int x, int z) { return true; }
            @Override public int[] centroid() { return new int[]{150, 350}; }
            @Override public int minChunkX() { return 0; }
            @Override public int minChunkZ() { return 0; }
            @Override public int maxChunkX() { return 1; }
            @Override public int maxChunkZ() { return 1; }
        };
        AnchorSource srcValid = AnchorSource.claimBoundary(validCentroid);
        if (srcValid instanceof AnchorSource.ClaimBoundaryAnchorSource cbas) {
            assertSame(validCentroid, cbas.boundary());
        }
        CompletableFuture<RTPCoords> fValid = srcValid.resolveAnchor(null);
        assertTrue(fValid.isDone());
        RTPCoords result = fValid.get();
        assertNotNull(result);
        assertEquals("world_the_end", result.worldName());
        assertEquals(150, result.x());
        assertEquals(64, result.y());
        assertEquals(350, result.z());
    }
}
