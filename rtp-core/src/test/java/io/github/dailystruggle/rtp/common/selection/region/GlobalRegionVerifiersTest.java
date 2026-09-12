package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalRegionVerifiersTest {

    @BeforeEach
    @AfterEach
    void clear() {
        GlobalRegionVerifiers.clearGlobalRegionVerifiers();
    }

    @Test
    void syncAndAsyncVerifiers_allPass_returnsTrue() throws ExecutionException, InterruptedException {
        GlobalRegionVerifiers.addGlobalRegionVerifier(coords -> coords.x() > 0);
        GlobalRegionVerifiers.addGlobalRegionVerifierAsync(coords -> CompletableFuture.completedFuture(coords.z() > 0));

        assertEquals(2, GlobalRegionVerifiers.registeredCount());

        RTPCoords valid = new RTPCoords("world", 10, 64, 10);
        assertTrue(GlobalRegionVerifiers.checkGlobalRegionVerifiers(valid).get());

        RTPCoords invalidX = new RTPCoords("world", -10, 64, 10);
        assertFalse(GlobalRegionVerifiers.checkGlobalRegionVerifiers(invalidX).get());

        RTPCoords invalidZ = new RTPCoords("world", 10, 64, -10);
        assertFalse(GlobalRegionVerifiers.checkGlobalRegionVerifiers(invalidZ).get());
    }

    @Test
    void throwingVerifier_failsSafe() throws ExecutionException, InterruptedException {
        GlobalRegionVerifiers.addGlobalRegionVerifier(coords -> {
            throw new RuntimeException("Simulated error");
        });

        RTPCoords coords = new RTPCoords("world", 10, 64, 10);
        assertFalse(GlobalRegionVerifiers.checkGlobalRegionVerifiers(coords).get());
    }

    @Test
    void unregisterBySource_and_autoCloseable_removesOnlyTargetVerifiers() throws ExecutionException, InterruptedException {
        class SourceA {}
        class SourceB {}

        AutoCloseable handleA1 = GlobalRegionVerifiers.addGlobalRegionVerifier(SourceA.class, coords -> coords.x() > 0);
        GlobalRegionVerifiers.addGlobalRegionVerifier(SourceA.class, coords -> coords.x() > 5);
        GlobalRegionVerifiers.addGlobalRegionVerifierAsync(SourceA.class, coords -> CompletableFuture.completedFuture(coords.y() > 0));

        AutoCloseable handleB1 = GlobalRegionVerifiers.addGlobalRegionVerifier(SourceB.class, coords -> coords.z() > 0);
        AutoCloseable handleB2 = GlobalRegionVerifiers.addGlobalRegionVerifierAsync(SourceB.class, coords -> CompletableFuture.completedFuture(coords.z() > 5));

        assertEquals(5, GlobalRegionVerifiers.registeredCount());

        // Close one handle of SourceB
        assertDoesNotThrow(handleB1::close);
        assertEquals(4, GlobalRegionVerifiers.registeredCount());

        // Unregister all SourceA
        int removedA = GlobalRegionVerifiers.removeGlobalRegionVerifiersBySource(SourceA.class);
        assertEquals(3, removedA);
        assertEquals(1, GlobalRegionVerifiers.registeredCount());

        // Remaining verifier should be handleB2 (coords.z() > 5)
        RTPCoords valid = new RTPCoords("world", -10, -10, 10);
        assertTrue(GlobalRegionVerifiers.checkGlobalRegionVerifiers(valid).get());

        RTPCoords invalidZ = new RTPCoords("world", 10, 10, 2);
        assertFalse(GlobalRegionVerifiers.checkGlobalRegionVerifiers(invalidZ).get());

        // Close handleB2
        assertDoesNotThrow(handleB2::close);
        assertEquals(0, GlobalRegionVerifiers.registeredCount());
    }
}
