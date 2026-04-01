package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;

public class GlobalRegionVerifiers {
    private static final Semaphore regionVerifiersLock = new Semaphore(1);
    private static final List<Predicate<RTPCoords>> regionVerifiers = new ArrayList<>();
    private static final List<Function<RTPCoords, CompletableFuture<Boolean>>> asyncRegionVerifiers = new ArrayList<>();

    /**
     * addGlobalRegionVerifier - add a region verifier to use for ALL regions
     *
     * @param locationCheck verifier method to reference. param: world name, 3D point return: boolean
     *     - true on good location, false on bad location
     */
    public static void addGlobalRegionVerifier(Predicate<RTPCoords> locationCheck) {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return;
        }
        regionVerifiers.add(locationCheck);
        regionVerifiersLock.release();
    }

    /**
     * addGlobalRegionVerifierAsync - add an async region verifier to use for ALL regions
     *
     * @param locationCheck verifier method to reference. param: world name, 3D point return: CompletableFuture<Boolean>
     *     - true on good location, false on bad location
     */
    public static void addGlobalRegionVerifierAsync(Function<RTPCoords, CompletableFuture<Boolean>> locationCheck) {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return;
        }
        asyncRegionVerifiers.add(locationCheck);
        regionVerifiersLock.release();
    }

    public static void clearGlobalRegionVerifiers() {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return;
        }
        regionVerifiers.clear();
        asyncRegionVerifiers.clear();
        regionVerifiersLock.release();
    }

    public static CompletableFuture<Boolean> checkGlobalRegionVerifiers(RTPCoords location) {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            regionVerifiersLock.release();
            return CompletableFuture.completedFuture(false);
        }

        for (int i = 0; i < regionVerifiers.size(); i++) {
            Predicate<RTPCoords> verifier = regionVerifiers.get(i);
            try {
                // if invalid placement, stop and return invalid
                // clone location to prevent methods from messing with the data
                if (!verifier.test(location)) {
                    regionVerifiersLock.release();
                    return CompletableFuture.completedFuture(false);
                }
            } catch (Throwable throwable) {
                RTP.log(Level.WARNING, throwable.getMessage(), throwable);
            }
        }

        if (asyncRegionVerifiers.isEmpty()) {
            regionVerifiersLock.release();
            return CompletableFuture.completedFuture(true);
        }

        CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);
        for (int i = 0; i < asyncRegionVerifiers.size(); i++) {
            final Function<RTPCoords, CompletableFuture<Boolean>> verifier = asyncRegionVerifiers.get(i);
            result = result.thenCompose(pass -> {
                if (!pass) return CompletableFuture.completedFuture(false);
                try {
                    return verifier.apply(location);
                } catch (Throwable throwable) {
                    RTP.log(Level.WARNING, throwable.getMessage(), throwable);
                    return CompletableFuture.completedFuture(true);
                }
            });
        }
        regionVerifiersLock.release();
        return result;
    }

    public static CompletableFuture<Boolean> checkGlobalRegionVerifiers(MutableRTPCoords location) {
        return checkGlobalRegionVerifiers(location.toImmutable());
    }
}
