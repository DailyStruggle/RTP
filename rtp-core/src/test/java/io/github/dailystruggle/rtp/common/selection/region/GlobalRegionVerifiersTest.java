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
}
