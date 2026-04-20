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

/**
 * A centralized registry for custom location verifiers that apply to all regions.
 * This allows other plugins to hook into the location selection process to add
 * their own safety checks, such as claim protection or custom area restrictions.
 */
public class GlobalRegionVerifiers {
    private static final Semaphore regionVerifiersLock = new Semaphore(1);
    private static final List<Predicate<RTPCoords>> regionVerifiers = new ArrayList<>();
    private static final List<Function<RTPCoords, CompletableFuture<Boolean>>> asyncRegionVerifiers = new ArrayList<>();

    /**
     * Adds a synchronous global region verifier. This verifier will be executed
     * for every potential teleport location.
     *
     * @param locationCheck A predicate that returns {@code true} for a valid location,
     *                      or {@code false} for an invalid one.
     */
    public static void addGlobalRegionVerifier(Predicate<RTPCoords> locationCheck) {
        boolean acquired = false;
        try {
            regionVerifiersLock.acquire();
            acquired = true;
            regionVerifiers.add(locationCheck);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired) regionVerifiersLock.release();
        }
    }

    /**
     * Adds an asynchronous global region verifier. This is useful for checks that
     * may involve I/O or other long-running operations.
     *
     * @param locationCheck A function that returns a {@link CompletableFuture<Boolean>}
     *                      which completes with {@code true} for a valid location,
     *                      or {@code false} for an invalid one.
     */
    public static void addGlobalRegionVerifierAsync(Function<RTPCoords, CompletableFuture<Boolean>> locationCheck) {
        boolean acquired = false;
        try {
            regionVerifiersLock.acquire();
            acquired = true;
            asyncRegionVerifiers.add(locationCheck);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired) regionVerifiersLock.release();
        }
    }

    /**
     * Removes all registered global region verifiers.
     */
    public static void clearGlobalRegionVerifiers() {
        boolean acquired = false;
        try {
            regionVerifiersLock.acquire();
            acquired = true;
            regionVerifiers.clear();
            asyncRegionVerifiers.clear();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (acquired) regionVerifiersLock.release();
        }
    }

    /**
     * Checks a given location against all registered synchronous and asynchronous
     * global verifiers.
     *
     * @param location The location to check.
     * @return A {@link CompletableFuture<Boolean>} that completes with {@code true}
     *         if the location is valid according to all verifiers, otherwise {@code false}.
     */
    public static CompletableFuture<Boolean> checkGlobalRegionVerifiers(RTPCoords location) {
        try {
            regionVerifiersLock.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CompletableFuture.completedFuture(false);
        }

        try {
            for (Predicate<RTPCoords> verifier : regionVerifiers) {
                try {
                    if (!verifier.test(location)) {
                        return CompletableFuture.completedFuture(false);
                    }
                } catch (Throwable throwable) {
                    RTP.log(Level.WARNING, "Global region verifier threw an exception", throwable);
                }
            }

            if (asyncRegionVerifiers.isEmpty()) {
                return CompletableFuture.completedFuture(true);
            }

            CompletableFuture<Boolean> result = CompletableFuture.completedFuture(true);
            for (Function<RTPCoords, CompletableFuture<Boolean>> verifier : asyncRegionVerifiers) {
                result = result.thenCompose(pass -> {
                    if (!pass) return CompletableFuture.completedFuture(false);
                    try {
                        return verifier.apply(location);
                    } catch (Throwable throwable) {
                        RTP.log(Level.WARNING, "Async global region verifier threw an exception", throwable);
                        return CompletableFuture.completedFuture(false); // Fail safe
                    }
                });
            }
            return result;
        } finally {
            regionVerifiersLock.release();
        }
    }

    /**
     * A convenience method to check a {@link MutableRTPCoords} location against all
     * registered verifiers by converting it to an immutable {@link RTPCoords}.
     *
     * @param location The mutable location to check.
     * @return A {@link CompletableFuture<Boolean>} that completes with the result of the check.
     */
    public static CompletableFuture<Boolean> checkGlobalRegionVerifiers(MutableRTPCoords location) {
        return checkGlobalRegionVerifiers(location.toImmutable());
    }
}
